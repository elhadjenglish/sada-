package app.sada.poc

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.media.AudioAttributes
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Stage 0 test screen. Plain Android views, no extra libraries. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var stateView: TextView
    private lateinit var qaView: TextView
    private lateinit var savedView: TextView
    private lateinit var stats: TextView
    private lateinit var events: TextView
    private lateinit var detections: LinearLayout
    private lateinit var startStop: Button
    private lateinit var attempts: EditText
    private lateinit var sourceLabel: TextView
    private lateinit var selfTestView: TextView
    private lateinit var micCheckView: TextView
    @Volatile private var selfTestRunning = false
    private var shownDetections = -1
    private val prefs by lazy { getSharedPreferences("stage0", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.init(this)
        Diag.checkLostSession(this)
        migrateSettings()
        Diag.testMode = prefs.getString("mode", "walking") ?: "walking"
        buildUi()
    }

    override fun onResume() { super.onResume(); main.post(refresh) }

    /**
     * While Sada is on screen and a session runs, the volume keys go to Sada (double-press Volume Up = Sada action;
     * other presses = podcast volume). Repeats of a held key are passed on too, so a hold is treated as volume.
     * In the background or with the screen off, Sada's media session receives them instead (where Android allows).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (Diag.serviceRunning && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val dir = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            startService(Intent(this, SadaService::class.java).setAction(SadaService.ACTION_VOLUME_KEY).putExtra(SadaService.EXTRA_DIRECTION, dir))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (Diag.serviceRunning && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }
    override fun onPause() { super.onPause(); main.removeCallbacks(refresh) }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(6), 0, dp(2))
    }
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setAllCaps(false); setOnClickListener { onClick() } }

    private fun buildUi() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        col.addView(text("Sada · Stage 0 wake-word test", 21f, true))
        col.addView(text("Double-press Volume Up to ask Sada; say “Continue” to resume.", 14f))

        col.addView(text("1. Set up (once)", 17f, true))
        col.addView(button("Allow microphone and notifications") { askPermissions() })
        col.addView(button("Let Sada run in the background (battery)") { askBatteryExemption() })
        col.addView(button("Open Sada's app settings (Infinix battery options)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        col.addView(text("2. Choose what to listen to", 17f, true))
        sourceLabel = text("", 14f)
        col.addView(sourceLabel)
        col.addView(button("Make the test episode (phone voice, ~1 min to prepare)") { makeTestEpisode() })
        col.addView(button("Or choose a podcast audio file on this phone") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), 42)
        })

        col.addView(text("3. Test type", 17f, true))
        val modes = RadioGroup(this)
        val walking = RadioButton(this).apply { id = View.generateViewId(); text = "Walking test" }
        val silence = RadioButton(this).apply { id = View.generateViewId(); text = "Silence test: I won't speak (every detection is false)" }
        modes.addView(walking); modes.addView(silence)
        modes.check(if (Diag.testMode == "silence") silence.id else walking.id)
        modes.setOnCheckedChangeListener { _, id -> Diag.testMode = if (id == silence.id) "silence" else "walking"; prefs.edit().putString("mode", Diag.testMode).apply() }
        col.addView(modes)

        col.addView(text("Sensitivity", 15f, true))
        val sens = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val opts = listOf("default" to "Default", "more" to "More sensitive", "fewer" to "Fewer false")
        val saved = prefs.getString("sens", "default")
        for ((key, label) in opts) {
            val rb = RadioButton(this).apply { id = View.generateViewId(); text = label; tag = key }
            sens.addView(rb)
            if (key == saved) sens.check(rb.id)
        }
        sens.setOnCheckedChangeListener { g, id -> prefs.edit().putString("sens", g.findViewById<RadioButton>(id).tag as String).apply() }
        col.addView(sens)
        col.addView(text("Changing sensitivity is experimental; start with Default.", 12f))

        col.addView(text("Usage mode", 15f, true))
        col.addView(text("Phone speaker: phone on the desk, podcast on its speaker, Sada listens with the phone microphone and removes the podcast from what it hears. Earbuds + phone mic: Build #8. Earbud call mode: experimental.", 12f))
        val usage = RadioGroup(this)
        val savedUsage = prefs.getString("usageMode", "speaker")
        for ((key, label) in listOf(
            "speaker" to "Phone speaker (primary)",
            "build8" to "Earbuds + phone mic (Build #8)",
            "earbudCall" to "Earbud call mode (experimental)",
        )) {
            val rb = RadioButton(this).apply { id = View.generateViewId(); text = label; tag = key }
            usage.addView(rb)
            if (key == savedUsage) usage.check(rb.id)
        }
        usage.setOnCheckedChangeListener { g, id -> prefs.edit().putString("usageMode", g.findViewById<RadioButton>(id).tag as String).apply() }
        col.addView(usage)

        col.addView(text("Microphone mode", 15f, true))
        val mic = RadioGroup(this)
        val savedMic = prefs.getString("mic", "recognition")
        for ((key, label) in listOf("recognition" to "Speech-recognition input (default)", "plain" to "Plain microphone (no processing)")) {
            val rb = RadioButton(this).apply { id = View.generateViewId(); text = label; tag = key }
            mic.addView(rb)
            if (key == savedMic) mic.check(rb.id)
        }
        mic.setOnCheckedChangeListener { g, id -> prefs.edit().putString("mic", g.findViewById<RadioButton>(id).tag as String).apply() }
        col.addView(mic)
        col.addView(CheckBox(this).apply {
            text = "Use the earbud microphone for questions (when earbuds are connected; the podcast is paused then)"
            isChecked = prefs.getBoolean("earbudMic", true)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("earbudMic", c).apply() }
        })
        col.addView(text("Echo cancellation (podcast on the phone speaker)", 15f, true))
        val aecGroup = RadioGroup(this)
        val savedAec = prefs.getString("aecKind", "sada")
        for ((key, label) in listOf(
            "sada" to "Sada's own, using the podcast signal (default)",
            "android" to "Android built-in (experimental A/B)",
            "off" to "Off (for comparison)",
        )) {
            val rb = RadioButton(this).apply { id = View.generateViewId(); text = label; tag = key }
            aecGroup.addView(rb)
            if (key == savedAec) aecGroup.check(rb.id)
        }
        aecGroup.setOnCheckedChangeListener { g, id -> prefs.edit().putString("aecKind", g.findViewById<RadioButton>(id).tag as String).apply() }
        col.addView(aecGroup)
        col.addView(CheckBox(this).apply {
            text = "Play the podcast during the test (untick for a listen-only test: nothing playing, so nothing can interfere)"
            isChecked = prefs.getBoolean("playback", true)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("playback", c).apply() }
        })

        col.addView(text("Detector self-test (no microphone)", 15f, true))
        col.addView(text("Checks the keywords, runs recorded speech through the detector and compares with the build machine, then feeds “Hey Sada” and “Continue” in the phone's voice. Takes about 30 seconds.", 12f))
        col.addView(button("Run detector self-test") { runSelfTest() })
        selfTestView = text(Diag.selfTest, 13f).apply { typeface = Typeface.MONOSPACE }
        col.addView(selfTestView)

        col.addView(text("4. Run", 17f, true))
        startStop = button("Start test") { toggle() }
        col.addView(startStop)
        col.addView(button("Ask Sada now") {
            if (!Diag.serviceRunning) toast("Start the test first.") else startService(Intent(this, SadaService::class.java).setAction(SadaService.ACTION_ASK))
        })
        col.addView(text("Earbuds: one tap = pause / continue; two quick taps (or your earbuds' next-track gesture) = Ask Sada; while you ask, a tap ends the question. The notification on the lock screen has Ask Sada and Pause / Continue buttons too.", 12f))

        col.addView(text("Phrase test (optional, with your consent)", 15f, true))
        col.addView(text("While a test runs: records 20 s of what the microphone and detector receive, keeps it only on this phone, and checks which phrases the detector finds and at what level. Stay silent for the first 5 seconds, then say “Hey Sada”, “Continue” and “Kada”, twice each, with a pause between.", 12f))
        col.addView(button("Record a 20-second phrase test") { askMicCheck() })
        val checkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        checkRow.addView(button("Play last check") { playMicCheck() })
        checkRow.addView(button("Delete all checks") { toast("Deleted ${MicCheck.deleteAll(this)} file(s).") })
        col.addView(checkRow)
        micCheckView = text("", 13f).apply { typeface = Typeface.MONOSPACE }
        col.addView(micCheckView)

        col.addView(text("Sada server", 17f, true))
        col.addView(text("Your Sada web app address (the Vercel one), e.g. https://your-sada.vercel.app. Questions go there; the Meta key stays on that server.", 12f))
        val server = EditText(this).apply {
            setText(prefs.getString("server", ""))
            hint = "https://your-sada.vercel.app"
            isSingleLine = true
        }
        col.addView(server)
        col.addView(button("Save address and test the connection") {
            val url = SadaApi.normalize(server.text.toString())
            server.setText(url)
            prefs.edit().putString("server", url).apply()
            toast("Testing…")
            Thread {
                val msg = try { "Connected: Muse Spark answered (model ${SadaApi(url).ping()})." }
                    catch (e: SadaApi.Failure) { "Not working: ${AnswerPlan.message(e.kind)} (${e.detail})" }
                    catch (e: Exception) { "Not working: ${e.javaClass.simpleName}" }
                runOnUiThread { toast(msg); Diag.log("server", msg) }
            }.start()
        })
        col.addView(text("Answer language", 15f, true))
        val langGroup = RadioGroup(this)
        val savedLang = prefs.getString("lang", "en")
        for ((key, label) in listOf("ar" to "Arabic", "en" to "Simple English", "both" to "Both (English, then Arabic)")) {
            val rb = RadioButton(this).apply { id = View.generateViewId(); text = label; tag = key }
            langGroup.addView(rb)
            if (key == savedLang) langGroup.check(rb.id)
        }
        langGroup.setOnCheckedChangeListener { g, id -> prefs.edit().putString("lang", g.findViewById<RadioButton>(id).tag as String).apply() }
        col.addView(langGroup)

        col.addView(text("How to use", 17f, true))
        col.addView(text("Double-press Volume Up (+): ask a question (the podcast pauses). After the answer: say “Continue” or double-press Volume Up to resume. If no question was heard, double-press Volume Down (−) to try again. Single presses change the podcast volume. The lock-screen notification also has Ask Sada and Pause / Continue.", 13f))
        stateView = text("", 18f, true)
        col.addView(stateView)
        qaView = text("", 14f)
        col.addView(qaView)
        col.addView(text("Saved words", 15f, true))
        savedView = text("", 13f)
        col.addView(savedView)
        col.addView(button("Share saved words") {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Sada saved words").putExtra(Intent.EXTRA_TEXT, SavedWords.asText(this))
            startActivity(Intent.createChooser(send, "Share saved words"))
        })

        col.addView(text("Live status", 17f, true))
        status = text("", 13f).apply { typeface = Typeface.MONOSPACE }
        col.addView(status)

        col.addView(text("Results", 17f, true))
        stats = text("", 13f).apply { typeface = Typeface.MONOSPACE }
        col.addView(stats)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text("Attempts: ", 14f))
        attempts = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(prefs.getString("attempts", "")); minEms = 3
            setOnFocusChangeListener { _, has -> if (!has) prefs.edit().putString("attempts", text.toString()).apply() }
        }
        row.addView(attempts)
        col.addView(row)
        col.addView(button("Copy report (to send to Claude)") { copyReport() })
        col.addView(button("Share report (WhatsApp, email, Claude…)") { shareReport() })
        col.addView(button("Restore default settings") { restoreDefaults(); recreate() })
        col.addView(button("Clear results") { Diag.reset(); prefs.edit().remove("attempts").apply(); attempts.setText(""); shownDetections = -1 })

        col.addView(text("Wake detections (tick the ones you did NOT say)", 15f, true))
        detections = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(detections)

        col.addView(text("Event log", 15f, true))
        events = text("", 12f).apply { typeface = Typeface.MONOSPACE }
        col.addView(events)

        setContentView(ScrollView(this).apply { addView(col) })
        updateSourceLabel()
    }

    private val refresh = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val running = Diag.serviceRunning
            startStop.text = if (running) "Stop test" else "Start test"
            val up = if (running) (now - Diag.startedAt) / 1000 else 0
            val hb = if (Diag.lastHeartbeat > 0) (now - Diag.lastHeartbeat) / 1000 else -1
            stateView.text = if (Diag.serviceRunning) Diag.stateText else "Not running"
            qaView.text = if (Diag.lastTranscript.isEmpty()) "" else "You asked: “${Diag.lastTranscript}”\nSada: ${Diag.lastAnswer}\n(${Diag.lastTiming})"
            val words = SavedWords.all(this@MainActivity) // inside the Runnable, plain "this" is the Runnable
            savedView.text = if (words.isEmpty()) "None yet. Ask about a word and Sada saves it here."
                else "${words.size} saved. Latest: " + words.takeLast(5).reversed().joinToString(", ") { it.optString("term") }
            status.text = buildString {
                append("Service: ${if (running) "RUNNING ${up / 60}m ${up % 60}s" else "stopped"} | state: ${Diag.phase}\n")
                if (running) append("Last heartbeat: ${hb}s ago | screen: ${if (Diag.screenOn) "on" else "off"}\n")
                append("Model: ${Diag.modelStatus}\n")
                append("Keywords: ${Diag.keywordCheck}\n")
                append("Microphone: ${Diag.micRoute}\n")
                append("Mic input: ${Diag.micSource}; ${Diag.micFormat}\n")
                append("Reached detector: ${"%.1f".format(Diag.samplesFed / 16000.0)} s of audio in ${Diag.buffersFed} buffers | empty reads ${Diag.emptyReads} | all-zero buffers ${Diag.zeroBuffers} | clipped ${Diag.clippedSamples} | peak ${"%.2f".format(Diag.micPeak)}\n")
                append("Near-miss listener: ${Diag.probeStatus}\n")
                append("Levels (1 s): raw ${"%.0f".format(Diag.rawLevelDb)} dBFS → +${"%.0f".format(Diag.inputGainDb)} dB → detector ${"%.0f".format(Diag.altLevelDb)} / ${"%.0f".format(Diag.mainLevelDb)} dBFS | now: ${Diag.lastSecondKind.lowercase().replace('_', ' ')}\n")
                append("Seconds: ${Diag.secondsText()}\n")
                append("Speech seconds with no detector output: ${Diag.speechSecondsNoKeyword} | mic recoveries ${Diag.micRecoveries} | call-mode repairs ${Diag.routeRepairs}\n")
                append("Health: ${Diag.healthText()}\n")
                append(Diag.earbudText()).append("\n")
                append("Echo cancellation: ${Diag.aecText()}\n")
                append("Alignment: ${Diag.alignText()}\n")
                append("Gain: ${"%.0f".format(Diag.agcGainDb)} dB, background ${"%.0f".format(Diag.noiseFloorDb)} dBFS | detections by stream: ${Diag.streamHitsText()}\n")
                append("Earbud mic for questions: ${if (Diag.earbudMicWanted) Diag.earbudMicStatus else "off"}\n")
                append("Last question: ${Diag.lastQuestion}\n")
                append("Playback:   ${Diag.playbackRoute}\n")
                append("Audio mode: ${Diag.audioMode} | Bluetooth call audio (SCO): ${if (Diag.scoOn) "ON ⚠" else "off"}\n")
                append(routeVerdict())
                append("Mic level: ${"%.0f".format(Diag.micLevelDb)} dBFS (avg ${"%.0f".format(Diag.micLevelAvgDb)})${if (Diag.micSilenced) " — SILENCED by Android" else ""}\n")
                append("Keyword engine: ${"%.1f".format(Diag.kwsChunkMsAvg)} ms per 100 ms audio (max ${"%.0f".format(Diag.kwsChunkMsMax)})\n")
                append("Battery: ${Diag.batteryPct}% (start ${Diag.batteryAtStart}%) | optimization ignored: ${batteryExempt()}\n")
                append("Standby bucket: ${Diag.standbyBucket} | background restricted: ${Diag.bgRestricted}\n")
                append("Keywords: ${Diag.keywordsMode} | test: ${Diag.testMode}")
            }
            val st = Diag.stats()
            stats.text = buildString {
                append("Listening: ${"%.1f".format(st.listeningMinutes)} min | stopped by Android: ${st.unexpectedStops}\n")
                append("Wake detections: ${st.wakes} | marked false: ${st.wakesMarkedFalse}\n")
                val a = attempts.text.toString().toIntOrNull()
                if (a != null && a > 0) {
                    val hits = (st.wakes - st.wakesMarkedFalse).coerceAtLeast(0)
                    append("Detected ${hits} of $a attempts (${100 * hits / a}%) | missed ${(a - hits).coerceAtLeast(0)}\n")
                }
                append("Misses marked by tap: wake ${st.missedWake}, continue ${st.missedContinue}\n")
                append("“Continue” heard while playing (false): ${st.continueWhilePlaying}\n")
                append("Raw detector outputs: ${st.rawDetections} | near misses: ${st.nearMisses} | ignored: ${st.ignored}\n")
                append("Wake → paused: ${Diag.summarize(st.pauseMs)}\n")
                append("Wake → response: ${Diag.summarize(st.answerMs)}\n")
                append("Continue → playing: ${Diag.summarize(st.resumeMs)}")
            }
            val all = Diag.snapshot()
            val wakes = all.filter { it.kind == "wake" }
            if (wakes.size != shownDetections) {
                shownDetections = wakes.size
                detections.removeAllViews()
                for (w in wakes.takeLast(40).reversed()) {
                    detections.addView(CheckBox(this@MainActivity).apply {
                        text = "${Diag.time(w.t)}  ${w.text}"
                        isChecked = Diag.isFalse(w.t)
                        setOnCheckedChangeListener { _, c -> Diag.setFalse(w.t, c) }
                    })
                }
            }
            micCheckView.text = Diag.recordingCheck
            events.text = all.takeLast(30).reversed().joinToString("\n") { "${Diag.time(it.t)} ${it.kind}${if (it.value != 0L && it.kind != "session-lost") " (${it.value})" else ""} ${it.text}" }
            main.postDelayed(this, 1000)
        }
    }

    private fun routeVerdict(): String {
        if (!Diag.serviceRunning) return ""
        val micOk = Diag.micRoute.startsWith("phone microphone")
        val playA2dp = Diag.playbackRoute.contains("A2DP")
        val call = Diag.scoOn || Diag.audioMode.contains("COMMUNICATION") || Diag.playbackRoute.contains("SCO")
        val asking = Diag.phase == "ARMING" || Diag.phase == "ASKING"
        return when {
            call && asking -> "• Earbud microphone in use for the question (call mode is expected now; the podcast is paused)\n"
            call -> "⚠ CALL-QUALITY AUDIO while not asking a question: Bluetooth stayed in call mode\n"
            micOk && Diag.playbackRoute.contains("speaker") -> "✓ Phone mic listening, podcast on the phone speaker (echo cancellation ${if (Diag.aecWanted) "on" else "OFF"})\n"
            micOk && playA2dp -> "✓ Phone mic listening, earbuds playing in music quality\n"
            !playA2dp -> "• Playback is not on Bluetooth A2DP (are the earbuds connected?)\n"
            else -> "• Microphone is not the phone mic: check the route above\n"
        }
    }

    // ---------- actions ----------

    private fun askPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(needed.toTypedArray(), 1)
    }

    private fun batteryExempt(): Boolean = (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun askBatteryExemption() {
        if (batteryExempt()) { toast("Already allowed to run in the background."); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun makeTestEpisode() {
        toast("Preparing the test episode with the phone's voice…")
        Thread {
            val err = TestEpisode.generate(this) { done, total -> runOnUiThread { sourceLabel.text = "Preparing test episode: $done of $total parts…" } }
            runOnUiThread {
                if (err == null) {
                    prefs.edit().putString("source", Uri.fromFile(TestEpisode.file(this)).toString()).apply()
                    toast("Test episode ready.")
                } else toast(err)
                updateSourceLabel()
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 42 && resultCode == RESULT_OK && uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            prefs.edit().putString("source", uri.toString()).apply()
            updateSourceLabel()
        }
    }

    private fun updateSourceLabel() {
        val s = prefs.getString("source", null)
        sourceLabel.text = when {
            s == null -> "Nothing chosen yet."
            s.startsWith("file:") -> "Using: test episode (Sada's 6 episodes, phone voice, loops)"
            else -> "Using: your audio file (loops)"
        }
    }

    private fun toggle() {
        if (Diag.serviceRunning) {
            startService(Intent(this, SadaService::class.java).setAction(SadaService.ACTION_STOP))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Allow the microphone first (step 1)."); askPermissions(); return
        }
        val playback = prefs.getBoolean("playback", true)
        val source = prefs.getString("source", null)
        if (playback && source == null) { toast("Choose what to listen to first (step 2), or untick “Play the podcast”."); return }
        prefs.edit().putString("attempts", attempts.text.toString()).apply()
        val i = Intent(this, SadaService::class.java)
            .setAction(SadaService.ACTION_START)
            .putExtra(SadaService.EXTRA_SOURCE, source)
            .putExtra(SadaService.EXTRA_SENSITIVITY, prefs.getString("sens", "default"))
            .putExtra(SadaService.EXTRA_MIC_MODE, prefs.getString("mic", "recognition"))
            .putExtra(SadaService.EXTRA_PLAYBACK, playback)
            .putExtra(SadaService.EXTRA_AEC, prefs.getString("aecKind", "sada") != "off")
            .putExtra(SadaService.EXTRA_ANDROID_AEC, prefs.getString("aecKind", "sada") == "android")
            .putExtra(SadaService.EXTRA_SPEAKER_MODE, prefs.getString("usageMode", "speaker") == "speaker")
            .putExtra(SadaService.EXTRA_WAKE_WORD, false) // voice wake word removed: Volume Up activates
            .putExtra(SadaService.EXTRA_EARBUD_MIC, prefs.getBoolean("earbudMic", true))
            .putExtra(SadaService.EXTRA_LISTEN_EARBUDS, prefs.getString("usageMode", "speaker") == "earbudCall")
        // Must start while this screen is visible: Android only grants background microphone use this way.
        startForegroundService(i)
    }

    private fun runSelfTest() {
        if (selfTestRunning) return
        selfTestRunning = true
        selfTestView.text = "Running…"
        Thread {
            val result = try { SelfTest.run(this) { p -> runOnUiThread { selfTestView.text = p + "\n…" } } }
                catch (e: Throwable) { "Self-test crashed: ${e.javaClass.simpleName}: ${e.message}" }
            runOnUiThread { selfTestView.text = result; selfTestRunning = false }
        }.start()
    }

    /**
     * Experimental options (sensitivity, microphone mode, echo cancellation, earbud mic) survive app updates and are only
     * cleared by uninstalling. Reset them once for this version so earlier experiments cannot quietly weaken detection.
     */
    private fun migrateSettings() {
        val version = 6 // volume-button interaction becomes primary; voice wake word optional (off)
        if (prefs.getInt("settingsVersion", 0) < version) {
            restoreDefaults()
            prefs.edit().putInt("settingsVersion", version).apply()
            Diag.log("settings", "Experimental settings reset to defaults for this version")
        }
    }

    private fun restoreDefaults() {
        prefs.edit().putString("sens", "default").putString("mic", "recognition").putBoolean("aec", true)
            .putBoolean("earbudMic", true).putBoolean("playback", true).putString("usageMode", "speaker").putString("aecKind", "sada").putBoolean("wakeWord", false).apply()
        toast("Default settings restored.")
    }

    /** Opens Android's share sheet with the report as plain text. */
    private fun shareReport() {
        val text = Diag.report(attempts.text.toString().toIntOrNull())
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Sada Stage 0 report")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Share the Sada report"))
    }

    private fun askMicCheck() {
        if (!Diag.serviceRunning) { toast("Start a test first."); return }
        AlertDialog.Builder(this)
            .setTitle("Record a phrase test?")
            .setMessage("Sada will record 20 seconds of what the microphone hears. The recording stays only on this phone (Sada has no internet access), is used to test the detector, and is deleted automatically after 24 hours. You can delete it now with “Delete all checks”.\n\nStay silent for 5 seconds, then say “Hey Sada”, “Continue” and “Kada”, twice each, with a pause between.")
            .setPositiveButton("Record") { _, _ -> startService(Intent(this, SadaService::class.java).setAction(SadaService.ACTION_RECORD_CHECK)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun playMicCheck() {
        val f = MicCheck.latest(this) ?: run { toast("No check recording on this phone."); return }
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        Thread { MicCheck.play(this, f, attrs) }.start()
    }

    private fun mark() {
        if (!Diag.serviceRunning) { toast("Start the test first."); return }
        startService(Intent(this, SadaService::class.java).setAction(SadaService.ACTION_MARK_MISS))
    }

    private fun copyReport() {
        prefs.edit().putString("attempts", attempts.text.toString()).apply()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Sada Stage 0 report", Diag.report(attempts.text.toString().toIntOrNull())))
        toast("Report copied. Paste it into your chat with Claude.")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
