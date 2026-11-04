package app.sada.poc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import app.sada.poc.dsp.Agc
import app.sada.poc.dsp.Endpointer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records one spoken question: from the moment input is ready until the user stops talking.
 * Keeps it in memory only, so Stage 0 can play it back (there is no AI answer yet); it is discarded afterwards.
 */
class QuestionCapture {
    // 8 s to start speaking, up to 20 s of question, ends after 1.4 s of silence (Endpointer.DEFAULT_END_SILENCE_MS)
    // AnswerPlan.NO_SPEECH_MS to start speaking (4.5 s), then up to 20 s of question ending after 1.4 s of silence
    private val ep = Endpointer(AnswerPlan.NO_SPEECH_MS, AnswerPlan.MAX_QUESTION_MS)

    /** When the question was finished (end of speech detected, timeout, or tap); 0 while still recording. */
    @Volatile var doneAt = 0L
        private set
    private val buf = FloatArray(SAMPLE_RATE * 30)
    private var len = 0
    private var skip = 0
    @Volatile var done = false
        private set
    val startedAt = SystemClock.elapsedRealtime()

    /** Seeds the end-of-speech detector with the background measured while the microphone got ready. */
    fun seedNoiseFloor(db: Double) { ep.setNoiseFloorDb(db) }

    /** Ignore the first [ms] (our own "listening" tone). */
    fun skipFirst(ms: Int) { skip = ms * SAMPLE_RATE / 1000 }

    /**
     * Adds one 16 kHz block. [detect] is the signal BEFORE adaptive gain (used to find where speech starts and ends:
     * gain control raises the background after a quiet question, which made the old version run to the 12 s limit);
     * [audio] is what gets kept and played back. Returns true when the question is complete.
     */
    fun push(detect: FloatArray, audio: FloatArray): Boolean {
        if (done) return true
        if (skip > 0) { skip -= detect.size; return false }
        val n = min(audio.size, buf.size - len)
        System.arraycopy(audio, 0, buf, len, n)
        len += n
        if (ep.process(detect, detect.size) == Endpointer.State.DONE || len >= buf.size) { done = true; doneAt = SystemClock.elapsedRealtime() }
        return done
    }

    fun finishNow() { if (!done) doneAt = SystemClock.elapsedRealtime(); done = true }

    val reason: String get() = when {
        ep.reason() == Endpointer.Reason.END_OF_SPEECH -> "you stopped talking"
        ep.reason() == Endpointer.Reason.NO_SPEECH -> "no speech heard"
        ep.reason() == Endpointer.Reason.MAX_LENGTH -> "maximum length reached"
        else -> "stopped by tap"
    }
    val heardSpeech: Boolean get() = ep.speechStartBlock() >= 0

    /** Detected speech duration so far, in ms. */
    fun speechMs(): Long = if (!heardSpeech) 0 else (ep.speechEndBlock() - ep.speechStartBlock() + 1) * 16L

    /** Silence since the last speech block, in ms (0 if none). */
    fun msSinceSpeech(): Long = if (!heardSpeech) 0 else maxOf(0, ep.blocks() - 1 - ep.speechEndBlock()) * 16L

    /** A real question: detected speech, at a real level, long enough (never an empty or silent recording). */
    val usable: Boolean get() {
        val sp = speech()
        var peak = 0f
        for (v in sp) peak = max(peak, kotlin.math.abs(v))
        return CaptureHealth.isUsable(heardSpeech, peak.toDouble(), speechMs())
    }

    /** The spoken part plus 0.3 s on each side. */
    fun speech(): FloatArray {
        if (!heardSpeech) return FloatArray(0)
        val from = max(0, ep.speechStartBlock() * 256 - SAMPLE_RATE * 3 / 10)
        val to = min(len, (ep.speechEndBlock() + 1) * 256 + SAMPLE_RATE * 3 / 10)
        return buf.copyOfRange(from, max(from, to))
    }

    fun summary(input: String, setupMs: Long): String {
        val sp = speech()
        var peak = 0f
        for (v in sp) peak = max(peak, kotlin.math.abs(v))
        val snr = ep.speechLevelDb() - ep.noiseFloorDb()
        val waited = if (heardSpeech) ep.speechStartBlock() * 16 else -1
        return "input: $input${if (setupMs > 0) " (ready after $setupMs ms)" else ""}; " +
            (if (heardSpeech) "started talking after $waited ms; speech ${"%.1f".format(sp.size / 16000.0)} s; " else "") +
            "speech level ${"%.0f".format(ep.speechLevelDb())} dBFS, noise ${"%.0f".format(ep.noiseFloorDb())} dBFS, " +
            "SNR ${if (heardSpeech) "%.0f".format(snr) else "-"} dB; peak ${"%.2f".format(peak)}; ended: $reason"
    }
}

/**
 * The earbud microphone (Bluetooth headset profile, "call mode"). Only used while the podcast is paused,
 * because call mode lowers Bluetooth audio to call quality. Android 12+ API.
 */
object EarbudMic {
    fun device(am: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < 31) return null
        return am.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    /** Switches to the earbud microphone; returns the time it took, or -1 if it did not become active. Blocking. */
    fun connect(am: AudioManager, dev: AudioDeviceInfo, timeoutMs: Long): Long {
        if (Build.VERSION.SDK_INT < 31) return -1
        val t0 = SystemClock.elapsedRealtime()
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!am.setCommunicationDevice(dev)) { am.mode = AudioManager.MODE_NORMAL; return -1 }
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            if (am.communicationDevice?.id == dev.id) {
                // Android has selected the route. Whether audio actually flows is checked next (awaitLive).
                return SystemClock.elapsedRealtime() - t0
            }
            SystemClock.sleep(50)
        }
        release(am)
        return -1
    }

    fun release(am: AudioManager) = AudioRouteGuard.release(am)

    /** Opens the call-mode recorder (routed to the earbud microphone while call mode is active). Null on failure. */
    fun open(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SAMPLE_RATE * 2))
        } catch (e: SecurityException) { return null }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); return null }
        return rec
    }

    /**
     * Reads (and discards) audio until the recorder delivers live audio, judged by [r]. Returns the time it took, or
     * -1 on timeout or a read error (e.g. Bluetooth disconnected). Nothing read here is part of the question: the user
     * has not been told to speak yet.
     */
    fun awaitLive(rec: AudioRecord, timeoutMs: Long, r: MicReadiness): Long {
        val t0 = SystemClock.elapsedRealtime()
        val pcm = ShortArray(256)
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            var got = 0
            while (got < 256) {
                val n = rec.read(pcm, got, 256 - got)
                if (n <= 0) return -1
                got += n
            }
            if (r.push(Pcm.stats(pcm, 256).rmsDb)) return SystemClock.elapsedRealtime() - t0
        }
        return -1
    }

    /** Discards whatever the recorder buffered (e.g. while Sada was speaking its cue). */
    fun drain(rec: AudioRecord) {
        val pcm = ShortArray(1024)
        try { while (rec.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING) > 0) {} } catch (_: Exception) {}
    }

    enum class RecordResult { DONE, LOST, CANCELLED }

    /**
     * Records from an opened recorder into [q] until it is done. Watches the audio itself (CaptureHealth): after a
     * Bluetooth disconnect Android often keeps delivering zeros instead of failing the read. routeOk is checked every
     * 8 blocks (128 ms). [lostReason] receives the reason when the result is LOST.
     */
    fun recordInto(rec: AudioRecord, q: QuestionCapture, cancelled: () -> Boolean, routeOk: () -> Boolean,
                   lostReason: (String) -> Unit): RecordResult {
        val agc = Agc()
        val health = CaptureHealth()
        val pcm = ShortArray(256)
        val f = FloatArray(256)
        val out = FloatArray(256)
        var route = true
        var blocks = 0
        while (!q.done) {
            if (cancelled()) return RecordResult.CANCELLED
            var got = 0
            while (got < 256) {
                val r = rec.read(pcm, got, 256 - got)
                if (r <= 0) { lostReason("the recorder stopped (read error $r)"); return RecordResult.LOST }
                got += r
            }
            if (blocks++ % 8 == 0) route = routeOk()
            if (health.push(Pcm.stats(pcm, 256).rmsDb, route)) { lostReason(health.reason() ?: "lost"); return RecordResult.LOST }
            for (i in 0 until 256) f[i] = pcm[i] / 32768f
            agc.process(f, out, 256)
            q.push(f, out)
        }
        return RecordResult.DONE
    }
}

/** Plays captured audio (16 kHz mono) once, through the normal media output. Blocking. */
object Replay {
    fun play(samples: FloatArray, attrs: AudioAttributes) {
        if (samples.isEmpty()) return
        // Normalise for listening: peak to about -3 dBFS.
        var peak = 1e-6f
        for (v in samples) peak = max(peak, kotlin.math.abs(v))
        val g = min(8f, 0.7f / peak)
        val pcm = ShortArray(samples.size) { (samples[it] * g * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            t.write(pcm, 0, pcm.size)
            t.play()
            SystemClock.sleep(pcm.size * 1000L / SAMPLE_RATE + 300)
        } finally {
            try { t.stop() } catch (_: Exception) {}
            t.release()
        }
    }

    fun levelDb(x: FloatArray): Double {
        var s = 0.0
        for (v in x) s += v * v
        return 10 * log10(s / max(1, x.size) + 1e-12)
    }

    @Suppress("unused")
    private fun rms(x: FloatArray) = sqrt(x.sumOf { (it * it).toDouble() } / max(1, x.size))
}
