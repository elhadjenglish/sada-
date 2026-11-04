package app.sada.poc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import app.sada.poc.dsp.ReferenceBuffer
import app.sada.poc.dsp.StreamResampler
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Plays the podcast with its own decoder and AudioTrack (instead of MediaPlayer) so the echo canceller gets
 * exactly what was sent to the speaker (as 16 kHz mono in [reference]) together with Android's playback timestamps.
 * Pause/play keep the exact position. Loops at the end.
 */
class PodcastPlayer(
    private val ctx: Context,
    private val uri: Uri,
    private val attrs: AudioAttributes,
    private val reference: ReferenceBuffer,
    private val onEvent: (kind: String, text: String) -> Unit,
    /** Optional output device to request (earbud experiment: the headset's call output). */
    private val preferredOutput: () -> android.media.AudioDeviceInfo? = { null },
) {
    @Volatile private var running = false
    @Volatile private var wantPlay = false
    @Volatile var track: AudioTrack? = null
        private set
    @Volatile var sampleRate = 0
        private set
    @Volatile var channels = 0
        private set
    private var thread: Thread? = null

    val isPlaying: Boolean get() = wantPlay && track?.playState == AudioTrack.PLAYSTATE_PLAYING

    fun start() {
        running = true
        wantPlay = true
        thread = Thread({ loop() }, "sada-player").also { it.start() }
    }

    /** Podcast volume controlled by Sada (0..1), used when the volume keys are handled by Sada's media session. */
    @Volatile var volume = 1f
        private set

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        try { track?.setVolume(volume) } catch (_: Exception) {}
    }

    fun play() {
        wantPlay = true
        track?.play()
    }

    /** Pauses at the exact current position; play() continues from there. */
    fun pause() {
        wantPlay = false
        track?.pause()
    }

    fun positionMs(): Long {
        val t = track ?: return 0
        val sr = sampleRate
        return if (sr > 0) (t.playbackHeadPosition.toLong() and 0xffffffffL) * 1000 / sr else 0
    }

    /** Fills ts with the frame position that was being heard at ts.nanoTime. False if not available yet. */
    fun timestamp(ts: AudioTimestamp): Boolean = try { track?.getTimestamp(ts) ?: false } catch (_: Exception) { false }

    fun stop() {
        running = false
        wantPlay = false
        try { thread?.join(1500) } catch (_: InterruptedException) {}
        thread = null
    }

    private fun setupTrack(fmt: MediaFormat): AudioTrack? {
        val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING) && fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
            onEvent("error", "Unsupported decoder output (not 16-bit PCM)")
            return null
        }
        if (ch < 1 || ch > 2) { onEvent("error", "Unsupported channel count $ch"); return null }
        val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sr, mask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(max(minBuf * 2, sr * ch * 2 / 5)) // ~200 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        preferredOutput()?.let { d ->
            val ok = t.setPreferredDevice(d)
            onEvent("route", "Asked Android to play the podcast on ${AudioNames.describe(d)}: ${if (ok) "accepted" else "refused"}")
        }
        sampleRate = sr
        channels = ch
        track = t
        onEvent("player", "Playing ${sr} Hz, $ch channel(s); output buffer ${t.bufferSizeInFrames * 1000 / sr} ms")
        try { t.setVolume(volume) } catch (_: Exception) {}
        if (wantPlay) t.play()
        return t
    }

    /** Writes everything unless stopped; never blocks forever (so stop() cannot deadlock while paused). */
    private fun writeAll(t: AudioTrack, data: ShortArray) {
        var off = 0
        while (off < data.size && running) {
            val n = t.write(data, off, data.size - off, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) { onEvent("error", "AudioTrack write error $n"); return }
            off += n
            if (n == 0) SystemClock.sleep(10)
        }
    }

    private fun loop() {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var index = -1
            for (i in 0 until ex.trackCount) {
                if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { index = i; break }
            }
            if (index < 0) { onEvent("error", "No audio track in the file"); return }
            ex.selectTrack(index)
            val inFmt = ex.getTrackFormat(index)
            val c = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(inFmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var resampler: StreamResampler? = null
            var refIndex = 0L
            while (running) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (track == null) {
                        setupTrack(c.outputFormat) ?: return
                        resampler = StreamResampler(sampleRate, SAMPLE_RATE)
                    }
                } else if (outIdx >= 0) {
                    if (info.size > 0) {
                        if (track == null) {
                            setupTrack(c.outputFormat) ?: return
                            resampler = StreamResampler(sampleRate, SAMPLE_RATE)
                        }
                        val ob = c.getOutputBuffer(outIdx)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val pcm = ShortArray(info.size / 2)
                        ob.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm)
                        // Reference first (the player writes ahead of what is heard, so it is always early enough).
                        val ch = channels
                        val frames = pcm.size / ch
                        val mono = FloatArray(frames) { f ->
                            var s = 0f
                            for (k in 0 until ch) s += pcm[f * ch + k]
                            s / (ch * 32768f)
                        }
                        val r16 = resampler!!.process(mono, frames)
                        reference.write(refIndex, r16, r16.size)
                        refIndex += r16.size
                        writeAll(track!!, pcm)
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        c.flush()
                        inputDone = false
                        onEvent("player", "Reached the end; looping")
                    }
                }
            }
        } catch (e: Exception) {
            onEvent("error", "Playback failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
            try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) {}
            track = null
        }
    }
}
