package app.sada.poc.dsp;

/**
 * Fixed calibration gain with a soft limiter, applied to the raw microphone before anything else.
 *
 * Android requires the VOICE_RECOGNITION source to be calibrated so that 90 dB SPL reads about -22 dBFS, with no
 * automatic gain. Normal speech reaching a phone at chest height or in a pocket is 20–30 dB quieter than that, i.e.
 * around -45 to -55 dBFS: far below the levels keyword models are trained and self-tested on. A deterministic gain
 * brings it back to a normal range without the timing problems of adaptive gain.
 */
public final class InputGain {
    private float gain = 1f;
    private double gainDb = 0;

    public void setGainDb(double db) { gainDb = db; gain = (float) Math.pow(10, db / 20); }
    public double gainDb() { return gainDb; }

    /** out may be the same array as in. Values stay within (-1, 1). */
    public void process(float[] in, float[] out, int n) {
        for (int i = 0; i < n; i++) {
            float v = in[i] * gain;
            float a = Math.abs(v);
            out[i] = a <= 0.7f ? v : Math.signum(v) * (0.7f + 0.28f * (float) Math.tanh((a - 0.7f) / 0.28f)); // ceiling 0.98
        }
    }
}
