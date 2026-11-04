package app.sada.poc.dsp;

/**
 * Maps microphone samples to the podcast sample that was coming out of the speaker at the same moment,
 * using Android's playback and recording timestamps (frame position at a known clock time).
 * The offset (reference index − mic index) is smoothed against timestamp jitter; large jumps re-sync at once.
 */
public final class ClockAlign {
    public static final int RATE = 16000;
    private double offset = Double.NaN;
    private int resyncs = 0;
    private double lastRaw = Double.NaN;

    /**
     * micFrame/micNanos: a recording timestamp (16 kHz frames). playFrame/playNanos/playRate: a playback timestamp.
     * Returns the raw offset in 16 kHz samples: reference index = mic index + offset.
     */
    public static double rawOffset(long micFrame, long micNanos, long playFrame, long playNanos, int playRate) {
        // playback position (in 16 kHz samples) at the mic timestamp's moment
        double playAtMic = (playFrame + (micNanos - playNanos) * 1e-9 * playRate) * RATE / playRate;
        return playAtMic - micFrame;
    }

    /** Feeds a new raw offset; returns the smoothed offset to use. */
    public double update(double raw) {
        lastRaw = raw;
        if (Double.isNaN(offset) || Math.abs(raw - offset) > 0.05 * RATE) { // >50 ms: re-sync
            if (!Double.isNaN(offset)) resyncs++;
            offset = raw;
        } else {
            offset += 0.05 * (raw - offset);
        }
        return offset;
    }

    public double offset() { return offset; }
    public double lastRaw() { return lastRaw; }
    public int resyncs() { return resyncs; }
    public boolean ready() { return !Double.isNaN(offset); }
    public void reset() { offset = Double.NaN; lastRaw = Double.NaN; }
}
