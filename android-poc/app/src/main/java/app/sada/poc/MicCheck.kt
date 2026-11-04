package app.sada.poc

import android.content.Context
import android.media.AudioAttributes
import app.sada.poc.dsp.BandwidthMeter
import app.sada.poc.dsp.DelayEstimate
import app.sada.poc.dsp.FrontEnd
import app.sada.poc.dsp.InputGain
import app.sada.poc.dsp.SignalClassifier
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Consented 10-second microphone check. Records the raw phone microphone and exactly what the detector received,
 * stores both only in the app's private storage (the app has no internet permission), and analyses them:
 * signal type per second, levels, and whether a fresh detector finds "Hey Sada" in the recording as received and at
 * several fixed gains. Never written to logs; deletable at any time; removed automatically after 24 hours.
 */
object MicCheck {
    /** Guided phrase test: 5 s silence (background/podcast leakage), then each phrase twice. */
    const val SECONDS = 20
    const val SILENT_SECONDS = 4

    fun dir(ctx: Context) = File(ctx.filesDir, "mic-checks").also { it.mkdirs() }

    class Recorder {
        val raw = FloatArray(SAMPLE_RATE * SECONDS)
        val det = FloatArray(SAMPLE_RATE * SECONDS)
        val ref = FloatArray(SAMPLE_RATE * SECONDS) // podcast reference at the timestamp-predicted position
        private var pos = 0
        /** Adds one block of each; returns true when full. */
        fun add(r: FloatArray, d: FloatArray, f: FloatArray): Boolean {
            val n = minOf(r.size, raw.size - pos)
            System.arraycopy(r, 0, raw, pos, n)
            System.arraycopy(d, 0, det, pos, n)
            System.arraycopy(f, 0, ref, pos, n)
            pos += n
            return pos >= raw.size
        }
    }

    private fun toPcm(x: FloatArray) = ShortArray(x.size) { (x[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }

    fun save(ctx: Context, r: Recorder): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        File(dir(ctx), "$stamp-raw.wav").writeBytes(Wav.encodeMono16(toPcm(r.raw), SAMPLE_RATE))
        File(dir(ctx), "$stamp-detector.wav").writeBytes(Wav.encodeMono16(toPcm(r.det), SAMPLE_RATE))
        return stamp
    }

    /** Worker thread. Returns a human-readable result (no audio content). */
    fun analyse(ctx: Context, r: Recorder): String {
        val sb = StringBuilder()
        // signal per second
        val c = SignalClassifier(FrontEnd.BLOCK, SAMPLE_RATE)
        val kinds = ArrayList<String>()
        val blk = FloatArray(FrontEnd.BLOCK)
        var i = 0
        while (i + FrontEnd.BLOCK <= r.raw.size) {
            System.arraycopy(r.raw, i, blk, 0, FrontEnd.BLOCK)
            c.process(blk, FrontEnd.BLOCK)?.let { kinds.add(it.name.lowercase().replace('_', ' ')) }
            i += FrontEnd.BLOCK
        }
        var peak = 0f
        var sum = 0.0
        for (v in r.raw) { peak = maxOf(peak, kotlin.math.abs(v)); sum += v * v }
        val rawDb = 10 * kotlin.math.log10(sum / r.raw.size + 1e-14)
        // background during the silent start = podcast leakage + noise reaching this microphone
        var bg = 0.0
        val bgN = SAMPLE_RATE * SILENT_SECONDS
        for (k in 0 until bgN) bg += r.raw[k] * r.raw[k]
        val bgDb = 10 * kotlin.math.log10(bg / bgN + 1e-14)
        var bgDet = 0.0
        for (k in 0 until bgN) bgDet += r.det[k] * r.det[k]
        val bgDetDb = 10 * kotlin.math.log10(bgDet / bgN + 1e-14)
        val gain = Diag.inputGainDb
        // reduction of what you did NOT say (podcast echo + noise) by the processing, with the calibration gain removed
        val reduction = bgDb + gain - bgDetDb
        val bw = BandwidthMeter()
        val b = FloatArray(BandwidthMeter.N)
        var j = 0
        while (j + BandwidthMeter.N <= r.raw.size) { System.arraycopy(r.raw, j, b, 0, BandwidthMeter.N); bw.process(b); j += BandwidthMeter.N }
        sb.append("Raw level ${"%.0f".format(rawDb)} dBFS, peak ${"%.2f".format(peak)}; first ${SILENT_SECONDS} s (you silent: podcast leakage + noise) ${"%.0f".format(bgDb)} dBFS; ")
        sb.append("link ${bw.verdict()}; seconds: ${kinds.joinToString(", ")}.\n")
        sb.append("Background while you were silent: ${"%.0f".format(bgDb)} dBFS at the mic → ${"%.0f".format(bgDetDb)} dBFS into the detector " +
            "(includes +${"%.0f".format(gain)} dB calibration): reduced by ${"%.0f".format(reduction)} dB (echo cancellation: ${Diag.aecKind}).\n")
        sb.append(voiceVsPodcast(r, bg / bgN)).append("\n")
        sb.append(echoDelay(r)).append("\n")
        sb.append(positiveControl(ctx, r)).append("\n")
        try {
            val sp = Kws.analysisSpotter(ctx, "$MODEL_DIR/keywords.txt")
            sb.append("Live detector on what it received: ${label(Kws.feedRecording(sp, r.det))}.\n")
        } catch (e: Throwable) {
            sb.append("Live-detector check failed: ${e.javaClass.simpleName}.\n")
        }
        // Fair comparison: same recording, same settings for every phrase (experiment keyword set, never used live).
        val exp = try { ctx.assets.open("experiment/keywords.txt").close(); true } catch (_: Exception) { false }
        if (!exp) {
            sb.append("Phrase comparison unavailable in this build (experiment keywords missing).")
        } else {
            try {
                val sp = Kws.analysisSpotter(ctx, "experiment/keywords.txt")
                sb.append("Phrase comparison (Hey Sada / Continue / Kada, equal settings), raw audio at gain:")
                for (g in listOf(0.0, 10.0, 20.0, 30.0)) {
                    val ig = InputGain().also { it.setGainDb(g) }
                    val y = FloatArray(r.raw.size)
                    ig.process(r.raw, y, r.raw.size)
                    val hits = Kws.feedRecording(sp, y)
                    val heySada = hits.count { it == "HEY_SADA" }
                    val cont = hits.count { it.startsWith("CONTINUE") }
                    val kada = hits.count { it.startsWith("KADA") }
                    sb.append(" +").append(g.toInt()).append(" dB → Hey Sada ").append(heySada)
                        .append(", Continue ").append(cont).append(", Kada ").append(kada).append(";")
                }
                val asReceived = Kws.feedRecording(sp, r.det)
                sb.append(" | on what the detector received (after processing) → Hey Sada ").append(asReceived.count { it == "HEY_SADA" })
                    .append(", Continue ").append(asReceived.count { it.startsWith("CONTINUE") })
                    .append(", Kada ").append(asReceived.count { it.startsWith("KADA") })
                sb.append(" (you said each twice)")
            } catch (e: Throwable) {
                sb.append("Phrase comparison failed: ${e.javaClass.simpleName}.")
            }
        }
        return sb.toString()
    }

    private fun power(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return s / maxOf(1, to - from)
    }

    private fun db(p: Double) = 10 * kotlin.math.log10(p + 1e-14)

    /**
     * Your voice compared with the podcast at the microphone: the 4 loudest seconds after the silent start, minus the
     * podcast/background measured while you were silent.
     */
    private fun voiceVsPodcast(r: Recorder, bgPower: Double): String {
        val secs = (SILENT_SECONDS + 1 until SECONDS).map { power(r.raw, it * SAMPLE_RATE, (it + 1) * SAMPLE_RATE) }.sortedDescending()
        val loud = secs.take(4).average()
        val voice = loud - bgPower
        return if (voice <= bgPower * 0.1) "Your voice vs the podcast at the microphone: not measurably above it (masked)."
        else "Your voice vs the podcast at the microphone: about ${"%.0f".format(db(voice) - db(bgPower))} dB " +
            "(loudest speech ${"%.0f".format(db(loud))} dBFS, podcast/background ${"%.0f".format(db(bgPower))} dBFS)."
    }

    /**
     * Where the echo really is: delay between the podcast reference (at the position Android's timestamps predict)
     * and the microphone, measured from the silent start of the recording (podcast only).
     */
    private fun echoDelay(r: Recorder): String {
        val n = SAMPLE_RATE * SILENT_SECONDS
        val res = DelayEstimate.estimate(r.raw.copyOf(n), r.ref.copyOf(n), SAMPLE_RATE / 5, SAMPLE_RATE * 9 / 10)
            ?: return "Echo delay: not measurable (no podcast reference: is the podcast playing on the phone speaker?)."
        val ms = res.lag / 16.0
        // The canceller reads the reference LEAD (32 ms) ahead and covers 256 ms: echo between -32 and +224 ms.
        val inside = ms > -FrontEnd.LEAD / 16.0 && ms < 256 - FrontEnd.LEAD / 16.0
        return "Echo delay measured in this recording: ${"%.0f".format(ms)} ms after the predicted position (match strength ${"%.0f".format(res.strength)}; " +
            "below ~5 = no clear echo match). Echo canceller window: -32 to +224 ms → ${if (inside) "inside" else "OUTSIDE: the canceller cannot work with this alignment"}."
    }

    /**
     * Positive control: the known-good phone-voice "Hey Sada", mixed into YOUR recorded podcast background at
     * 0, +10 and +20 dB above it, with the same calibration gain, through the live detector's keywords.
     * Shows how much louder than the podcast the phrase must be for this detector.
     */
    private fun positiveControl(ctx: Context, r: Recorder): String {
        return try {
            val files = PhoneVoice.render(ctx, listOf("Hey Sada.")) ?: return "Positive control: phone voice unavailable."
            val a = Wav.decode(files[0].readBytes()); files[0].delete()
            if (a == null) return "Positive control: phone voice format unreadable."
            val tts = Pcm.resample(a.samples, a.sampleRate, SAMPLE_RATE)
            // speech level of the phrase (louder half of 16 ms blocks)
            val blocks = (0 until tts.size / 256).map { b -> power(tts, b * 256, b * 256 + 256) }.sortedDescending()
            val ttsSpeech = blocks.take(maxOf(1, blocks.size / 2)).average()
            val bgN = SAMPLE_RATE * SILENT_SECONDS
            val bgPower = power(r.raw, 0, bgN)
            val sp = Kws.analysisSpotter(ctx, "$MODEL_DIR/keywords.txt")
            val gain = InputGain().also { it.setGainDb(Diag.inputGainDb) }
            val parts = ArrayList<String>()
            for (snr in listOf(0, 10, 20)) {
                val k = kotlin.math.sqrt(bgPower * Math.pow(10.0, snr / 10.0) / (ttsSpeech + 1e-20)).toFloat()
                val mix = r.raw.copyOf(bgN) // the podcast you actually had playing, while you were silent
                val start = SAMPLE_RATE / 2
                for (i in tts.indices) if (start + i < mix.size) mix[start + i] += tts[i] * k
                val y = FloatArray(mix.size)
                gain.process(mix, y, mix.size)
                val hit = Kws.feedRecording(sp, y).any { it == "HEY_SADA" }
                parts.add("${if (snr == 0) "same level" else "+$snr dB"} ${if (hit) "detected" else "missed"}")
            }
            "Positive control (known “Hey Sada” mixed into your podcast background): " + parts.joinToString(", ") + "."
        } catch (e: Throwable) {
            "Positive control failed: ${e.javaClass.simpleName}."
        }
    }

    private fun label(hits: List<String>) = if (hits.isEmpty()) "nothing" else hits.joinToString("+")

    fun latest(ctx: Context): File? = dir(ctx).listFiles { f -> f.name.endsWith("-raw.wav") }?.maxByOrNull { it.name }

    fun play(ctx: Context, f: File, attrs: AudioAttributes) {
        val a = Wav.decode(f.readBytes()) ?: return
        Replay.play(a.samples, attrs)
    }

    fun deleteAll(ctx: Context): Int {
        val files = dir(ctx).listFiles() ?: return 0
        files.forEach { it.delete() }
        return files.size
    }

    fun deleteOlderThanHours(ctx: Context, hours: Int) {
        val cutoff = System.currentTimeMillis() - hours * 3600_000L
        dir(ctx).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
