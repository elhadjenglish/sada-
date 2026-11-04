package app.sada.poc

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * The Sada server (the same Vercel functions the web app uses). The Meta API key stays on the server: this app only
 * sends the recorded question (/api/transcribe) and the transcribed text (/api/ai, task "ask").
 * Blocking calls: use from a worker thread.
 */
class SadaApi(rawBaseUrl: String) {
    class Failure(val kind: AnswerPlan.Failure, val detail: String) : Exception(detail)

    companion object {
        /**
         * The server the app uses: the build's SADA_SERVER (default https://sada-chi.vercel.app). Only the developer
         * diagnostics screen can override it ("serverOverride"); normal users are never asked for an address.
         */
        fun configured(ctx: android.content.Context): String {
            val override = ctx.getSharedPreferences("stage0", android.content.Context.MODE_PRIVATE).getString("serverOverride", "") ?: ""
            return normalize(if (override.isNotBlank()) override else BuildConfig.SADA_SERVER)
        }

        /** "your-sada.vercel.app" → "https://your-sada.vercel.app"; trailing slashes removed. */
        fun normalize(raw: String): String {
            val t = raw.trim().trimEnd('/')
            if (t.isEmpty()) return ""
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }
    }

    private val baseUrl = normalize(rawBaseUrl)

    /** Speech → text with Muse Voice Transcribe. wav: 16 kHz mono 16-bit. */
    fun transcribe(wav: ByteArray): String {
        val body = JSONObject().put("audio", Base64.encodeToString(wav, Base64.NO_WRAP))
        return post("/api/transcribe", body, 30_000).optString("text", "").trim()
    }

    /** The spoken question → Muse Spark answer (result object of task "ask"). */
    fun ask(question: String, lang: String, level: String, ctx: EpisodeContext, positionSec: Double): JSONObject {
        val payload = JSONObject()
            .put("question", question.take(500))
            .put("lang", lang)
            .put("level", level)
            .put("positionSec", positionSec)
        if (ctx.title.isNotEmpty()) payload.put("episode", ctx.title.take(120))
        if (ctx.recent.isNotEmpty()) payload.put("recent", ctx.recent.take(800))
        val out = post("/api/ai", JSONObject().put("task", "ask").put("payload", payload), 45_000)
        return out.optJSONObject("result") ?: throw Failure(AnswerPlan.Failure.AI_ERROR, "no result in the reply")
    }

    /**
     * Plain request for the web layer: returns (HTTP status, body). Status 0 = no connection (body is a message to show).
     * The web app sends its own JSON to the same /api routes it uses in a browser.
     */
    fun rawPost(path: String, jsonText: String, timeoutMs: Int): Pair<Int, String> {
        if (baseUrl.isBlank()) return Pair(0, "Set the Sada server address in Settings.")
        if (baseUrl.startsWith("http://")) return Pair(0, "The server address must start with https://")
        val c = try { URL(baseUrl + path).openConnection() as HttpURLConnection } catch (e: Exception) { return Pair(0, "The server address is not valid.") }
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("X-Sada-Client", "android")
            val bytes = jsonText.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(status, text)
        } catch (e: SocketTimeoutException) {
            return Pair(0, "No answer after ${timeoutMs / 1000} seconds. Check your connection and try again.")
        } catch (e: IOException) {
            return Pair(0, "No connection. Check your internet and try again.")
        } finally {
            c.disconnect()
        }
    }

    /** Connection test used by the settings screen. */
    fun ping(): String {
        val out = post("/api/ai", JSONObject().put("task", "ping"), 20_000)
        return out.optString("model", "ok")
    }

    private fun post(path: String, json: JSONObject, timeoutMs: Int): JSONObject {
        if (baseUrl.isBlank()) throw Failure(AnswerPlan.Failure.NO_SERVER, "no server address")
        // Android blocks unencrypted HTTP by default; say so instead of failing like a network outage.
        if (baseUrl.startsWith("http://")) throw Failure(AnswerPlan.Failure.NO_SERVER, "the address must start with https://")
        val url = try { URL(baseUrl.trimEnd('/') + path) } catch (e: Exception) { throw Failure(AnswerPlan.Failure.NO_SERVER, "bad server address") }
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("X-Sada-Client", "android")
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                val msg = try { JSONObject(text).optString("error", "") } catch (_: Exception) { "" }
                throw Failure(AnswerPlan.fromStatus(status, text), "HTTP $status${if (msg.isNotEmpty()) ": $msg" else ""}")
            }
            return try { JSONObject(text) } catch (e: Exception) { throw Failure(AnswerPlan.Failure.AI_ERROR, "unreadable reply") }
        } catch (e: Failure) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw Failure(AnswerPlan.Failure.TIMEOUT, "timed out after ${timeoutMs / 1000} s")
        } catch (e: IOException) {
            throw Failure(AnswerPlan.Failure.OFFLINE, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            c.disconnect()
        }
    }
}

/** Words and expressions saved from answers (on this phone). One JSON object per line. */
object SavedWords {
    private fun file(ctx: android.content.Context) = java.io.File(ctx.filesDir, "saved-words.jsonl")

    fun add(ctx: android.content.Context, term: String, sentence: String, explanation: String, episode: String) {
        if (term.isBlank()) return
        if (all(ctx).any { it.optString("term").equals(term, ignoreCase = true) && it.optString("sentence") == sentence }) return
        val o = JSONObject().put("term", term).put("sentence", sentence).put("explanation", explanation)
            .put("episode", episode).put("savedAt", System.currentTimeMillis())
        file(ctx).appendText(o.toString() + "\n")
    }

    fun all(ctx: android.content.Context): List<JSONObject> = try {
        file(ctx).readLines().filter { it.isNotBlank() }.mapNotNull { try { JSONObject(it) } catch (_: Exception) { null } }
    } catch (_: Exception) { emptyList() }

    fun asText(ctx: android.content.Context): String = all(ctx).joinToString("\n\n") { o ->
        buildString {
            append(o.optString("term"))
            if (o.optString("sentence").isNotEmpty()) append("\n“").append(o.optString("sentence")).append("”")
            if (o.optString("explanation").isNotEmpty()) append("\n").append(o.optString("explanation"))
        }
    }
}
