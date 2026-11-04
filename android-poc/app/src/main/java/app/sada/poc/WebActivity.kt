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
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.widget.EditText
import java.io.File
import java.io.FilterInputStream
import java.util.Locale
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
    private var pendingWebRequest: PermissionRequest? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
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
                val path = request.url.path ?: "/"
                if (path.startsWith("/imports/")) return importedFile(path, request.requestHeaders["Range"])
                return asset(path)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == host) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                return true
            }
        }
        // The web page's own microphone use (pronunciation practice): ask Android, then answer the page's request.
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (request.origin.host != host || !wantsMic) { request.deny(); return@runOnUiThread }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingWebRequest?.deny()
                        pendingWebRequest = request
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 12)
                    }
                }
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
        if (requestCode == 12) { // the web page asked for the microphone
            val r = pendingWebRequest
            pendingWebRequest = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) r?.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            else { r?.deny(); if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) showMicSettingsDialog() }
            return
        }
        val micIdx = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        val play = pendingPlay
        pendingPlay = null
        if (micIdx >= 0 && grantResults.getOrNull(micIdx) == PackageManager.PERMISSION_GRANTED) {
            if (play != null) startPlay(play)
        } else {
            NativeApp.error = "Sada needs the microphone permission to hear your questions. Allow it in the phone's settings for this app."
            if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) showMicSettingsDialog()
        }
    }

    /** The microphone was refused for good ("don't ask again"): offer the app's settings page. */
    private fun showMicSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Microphone is blocked")
            .setMessage("Sada needs the microphone to hear you. Open the app settings, tap Permissions, then Microphone, and choose Allow.")
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ---------------- pronunciation: the phone's own English voice ----------------

    private fun speakNow(text: String) {
        if (text.isBlank()) return
        val t = tts
        if (t == null) {
            tts = TextToSpeech(applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.setLanguage(Locale.US)
                    tts?.setSpeechRate(0.9f)
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "word")
                }
            }
        } else if (ttsReady) {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "word")
        }
    }

    // ---------------- audio files from the phone ----------------

    private fun tellWeb(url: String, name: String, error: String) {
        val js = "window.__sadaAudioPicked(${JSONObject.quote(url)},${JSONObject.quote(name)},${JSONObject.quote(error)})"
        runOnUiThread { if (!isDestroyed) web.evaluateJavascript(js, null) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 21) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { tellWeb("", "", "cancelled"); return }
        Thread {
            try {
                var name = "audio"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
                val extOfName = name.substringAfterLast('.', "").lowercase()
                val ext = if (extOfName.length in 2..5 && extOfName.all { it.isLetterOrDigit() }) extOfName else when (contentResolver.getType(uri)) {
                    "audio/mpeg" -> "mp3"
                    "audio/mp4", "audio/x-m4a" -> "m4a"
                    "audio/aac" -> "aac"
                    "audio/wav", "audio/x-wav" -> "wav"
                    "audio/ogg" -> "ogg"
                    "audio/flac" -> "flac"
                    else -> "mp3"
                }
                val dir = File(filesDir, "imports").also { it.mkdirs() }
                val dest = File(dir, "${System.currentTimeMillis()}.$ext")
                val input = contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open")
                input.use { src -> dest.outputStream().use { out -> src.copyTo(out) } }
                tellWeb("https://$host/imports/${dest.name}", name, "")
            } catch (e: Exception) {
                tellWeb("", "", "That file could not be imported.")
            }
        }.start()
    }

    /** An imported audio file, served to the page's audio player (with range support, so seeking works). */
    private fun importedFile(path: String, range: String?): WebResourceResponse {
        val name = path.removePrefix("/imports/")
        val f = File(File(filesDir, "imports"), name)
        if (name.contains('/') || name.contains("..") || !f.isFile) {
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        val total = f.length()
        val mime = when (name.substringAfterLast('.').lowercase()) {
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg", "oga", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            else -> "audio/mpeg"
        }
        var start = 0L
        var end = total - 1
        var status = 200
        var reason = "OK"
        val headers = mutableMapOf("Accept-Ranges" to "bytes", "Cache-Control" to "no-cache")
        val m = range?.let { Regex("bytes=(\d*)-(\d*)").matchEntire(it.trim()) }
        if (m != null) {
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            if (a.isNotEmpty()) { start = a.toLong(); if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1) }
            else if (b.isNotEmpty()) start = maxOf(0L, total - b.toLong())
            if (start > end) start = 0L
            status = 206
            reason = "Partial Content"
            headers["Content-Range"] = "bytes $start-$end/$total"
        }
        headers["Content-Length"] = (end - start + 1).toString()
        val raw = java.io.FileInputStream(f)
        raw.channel.position(start)
        val limited = object : FilterInputStream(raw) {
            var left = end - start + 1
            override fun read(): Int {
                if (left <= 0) return -1
                val r = super.read()
                if (r >= 0) left--
                return r
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = super.read(b, off, minOf(len.toLong(), left).toInt())
                if (n > 0) left -= n
                return n
            }
        }
        return WebResourceResponse(mime, null, status, reason, headers, limited)
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
        @JavascriptInterface fun cancel() { runOnUiThread { serviceAction(SadaService.ACTION_CANCEL) } }
        @JavascriptInterface fun speak(text: String) { runOnUiThread { speakNow(text) } }
        @JavascriptInterface fun pickAudio() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                try { startActivityForResult(i, 21) } catch (_: Exception) { tellWeb("", "", "No file picker is available on this phone.") }
            }
        }
        @JavascriptInterface fun stop() { runOnUiThread { serviceAction(SadaService.ACTION_STOP) } }
        @JavascriptInterface fun setPrefs(lang: String, level: String) { prefs.edit().putString("lang", lang).putString("level", level).apply() }
        @JavascriptInterface fun getServer(): String = server()
        @JavascriptInterface fun setServer(url: String) { prefs.edit().putString("server", SadaApi.normalize(url)).apply() }
        @JavascriptInterface fun openDiagnostics() { runOnUiThread { startActivity(Intent(this@WebActivity, MainActivity::class.java)) } }

        /** The web page's own requests to the Sada server's api routes: sent from here (same server, same routes), result handed back. */
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
        tts?.shutdown()
        web.removeJavascriptInterface("SadaNative")
        web.destroy()
        super.onDestroy()
    }
}
