package app.sada.poc

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Sada's real interface: the React app (onboarding, Today, Listen, Words, Practice) running in a WebView, served from
 * the app's own assets. The audio system (podcast player, volume-button control, microphone, questions, spoken answers)
 * stays native; the web page talks to it through the "SadaNative" bridge and shows its state.
 */
class WebActivity : Activity() {
    private lateinit var web: WebView
    private var pendingPlay: String? = null
    private val host = "appassets.androidplatform.net"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.init(this)
        web = WebView(this)
        setContentView(web)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.host != host) return null // fonts etc. load normally
                return asset(request.url.path ?: "/")
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == host) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "SadaNative")
        web.loadUrl("https://$host/index.html")
        askServerIfNeeded()
    }

    private fun asset(path: String): WebResourceResponse {
        var name = path.trimStart('/')
        if (name.isEmpty()) name = "index.html"
        val stream = try { assets.open("web/$name") } catch (_: Exception) {
            // a page route of the single-page app: serve the app itself
            try { assets.open("web/index.html") } catch (_: Exception) {
                val msg = "<html><body style='font-family:sans-serif;padding:24px'><h3>Sada</h3><p>The Sada interface is missing from this build. Rebuild the app with the web files.</p></body></html>"
                return WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-cache"), ByteArrayInputStream(msg.toByteArray()))
            }
        }
        val mime = mimeFor(name)
        val enc = if (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("svg+xml")) "utf-8" else null
        return WebResourceResponse(mime, enc, 200, "OK", mapOf("Cache-Control" to "no-cache"), stream)
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "svg" -> "image/svg+xml"
        "json", "webmanifest" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ico" -> "image/x-icon"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    // ---------------- settings, permissions ----------------

    private val prefs get() = getSharedPreferences("stage0", Context.MODE_PRIVATE)

    private fun server() = SadaApi.normalize(prefs.getString("server", "") ?: "")

    private fun askServerIfNeeded() {
        if (server().isNotBlank()) return
        val input = EditText(this).apply { hint = "https://your-sada.vercel.app"; isSingleLine = true }
        AlertDialog.Builder(this)
            .setTitle("Sada server address")
            .setMessage("Enter the address of your Sada web app (the Vercel address). Your questions and explanations are answered there; the Meta key stays on that server. You can change it later in Settings.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ -> prefs.edit().putString("server", SadaApi.normalize(input.text.toString())).apply() }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val micIdx = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        val play = pendingPlay
        pendingPlay = null
        if (micIdx >= 0 && grantResults.getOrNull(micIdx) == PackageManager.PERMISSION_GRANTED) {
            if (play != null) startPlay(play)
        } else {
            NativeApp.error = "Sada needs the microphone permission to hear your questions. Allow it in the phone's settings for this app."
        }
    }

    // ---------------- play an episode ----------------

    /** Earbuds or a wired headset connected → "Earbuds + phone mic"; otherwise "Phone Speaker" mode. */
    private fun headphonesConnected(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun startPlay(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        prefs.edit().putString("lang", o.optString("lang", "en")).putString("level", o.optString("level", "B1")).apply()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingPlay = json
            val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(needed.toTypedArray(), 11)
            return
        }
        val id = o.optString("id")
        val title = o.optString("title")
        val text = o.optString("text")
        val fraction = o.optDouble("startFraction", 0.0).coerceIn(0.0, 0.98)
        NativeApp.error = ""
        NativeApp.preparing = if (EpisodeAudio.fileFor(this, id, text).exists()) "" else "Preparing the narration with your phone's voice (first time for this episode)…"
        Thread {
            val (file, err) = EpisodeAudio.ensure(this, id, text)
            NativeApp.preparing = ""
            if (file == null) { NativeApp.error = err ?: "The narration could not be made."; return@Thread }
            val duration = EpisodeAudio.durationMs(file)
            runOnUiThread { launchService(file, id, title, text, duration, (fraction * duration).toLong()) }
        }.start()
    }

    private fun launchService(file: java.io.File, id: String, title: String, text: String, duration: Long, startMs: Long) {
        val earbuds = headphonesConnected()
        val i = Intent(this, SadaService::class.java)
            .setAction(SadaService.ACTION_LOAD)
            .putExtra(SadaService.EXTRA_SOURCE, Uri.fromFile(file).toString())
            .putExtra(SadaService.EXTRA_EPISODE_ID, id)
            .putExtra(SadaService.EXTRA_EPISODE_TITLE, title)
            .putExtra(SadaService.EXTRA_EPISODE_TEXT, text)
            .putExtra(SadaService.EXTRA_DURATION_MS, duration)
            .putExtra(SadaService.EXTRA_START_MS, startMs)
            .putExtra(SadaService.EXTRA_SENSITIVITY, "default")
            .putExtra(SadaService.EXTRA_MIC_MODE, "recognition")
            .putExtra(SadaService.EXTRA_PLAYBACK, true)
            .putExtra(SadaService.EXTRA_AEC, true)
            .putExtra(SadaService.EXTRA_ANDROID_AEC, false)
            .putExtra(SadaService.EXTRA_SPEAKER_MODE, !earbuds)   // headphones → Earbuds + phone mic; else Phone Speaker
            .putExtra(SadaService.EXTRA_WAKE_WORD, false)          // double-press Volume Up is the only activation
            .putExtra(SadaService.EXTRA_EARBUD_MIC, true)
            .putExtra(SadaService.EXTRA_LISTEN_EARBUDS, false)
        try {
            startForegroundService(i) // started while the app is open: Android allows microphone use only this way
        } catch (e: Exception) {
            NativeApp.error = "Keep Sada open while the first narration is prepared, then press play."
            return
        }
        // keep playing with the screen off: ask once to be exempt from battery optimisation
        if (!prefs.getBoolean("batteryAsked", false)) {
            prefs.edit().putBoolean("batteryAsked", true).apply()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            }
        }
    }

    private fun serviceAction(action: String, configure: Intent.() -> Unit = {}) {
        if (SadaService.live == null) return
        startService(Intent(this, SadaService::class.java).setAction(action).apply(configure))
    }

    // ---------------- the bridge ----------------

    inner class Bridge {
        @JavascriptInterface fun state(): String = NativeApp.stateJson()
        @JavascriptInterface fun drainEvents(): String = NativeApp.drainEvents(this@WebActivity)
        @JavascriptInterface fun play(json: String) { runOnUiThread { startPlay(json) } }
        @JavascriptInterface fun resume() { runOnUiThread { serviceAction(SadaService.ACTION_PLAY) } }
        @JavascriptInterface fun pause() { runOnUiThread { serviceAction(SadaService.ACTION_PAUSE) } }
        @JavascriptInterface fun seek(fraction: String) {
            val f = fraction.toDoubleOrNull() ?: return
            val duration = try { JSONObject(NativeApp.stateJson()).optLong("durationMs") } catch (_: Exception) { 0L }
            runOnUiThread { serviceAction(SadaService.ACTION_SEEK) { putExtra(SadaService.EXTRA_SEEK_MS, (f.coerceIn(0.0, 0.98) * duration).toLong()) } }
        }
        @JavascriptInterface fun ask() {
            runOnUiThread {
                if (SadaService.live == null) NativeApp.error = "Press play first, then ask."
                else serviceAction(SadaService.ACTION_ASK)
            }
        }
        @JavascriptInterface fun stop() { runOnUiThread { serviceAction(SadaService.ACTION_STOP) } }
        @JavascriptInterface fun setPrefs(lang: String, level: String) { prefs.edit().putString("lang", lang).putString("level", level).apply() }
        @JavascriptInterface fun getServer(): String = server()
        @JavascriptInterface fun setServer(url: String) { prefs.edit().putString("server", SadaApi.normalize(url)).apply() }
        @JavascriptInterface fun openDiagnostics() { runOnUiThread { startActivity(Intent(this@WebActivity, MainActivity::class.java)) } }

        /** The web page's own requests to /api/*: sent from here (same server, same routes), result handed back. */
        @JavascriptInterface fun fetchJson(id: Int, path: String, body: String) {
            Thread {
                val (status, text) = SadaApi(server()).rawPost(path, body, 60_000)
                val js = "window.__sadaFetchDone($id,$status,${JSONObject.quote(text)})"
                runOnUiThread { if (!isDestroyed) web.evaluateJavascript(js, null) }
            }.start()
        }
    }

    // ---------------- volume buttons while Sada is on screen ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (SadaService.live != null && Diag.serviceRunning && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val dir = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            startService(Intent(this, SadaService::class.java).setAction(SadaService.ACTION_VOLUME_KEY).putExtra(SadaService.EXTRA_DIRECTION, dir))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (SadaService.live != null && Diag.serviceRunning && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }

    /** Back leaves the app open in the background so the episode keeps playing. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { moveTaskToBack(true) }

    override fun onDestroy() {
        web.removeJavascriptInterface("SadaNative")
        web.destroy()
        super.onDestroy()
    }
}
