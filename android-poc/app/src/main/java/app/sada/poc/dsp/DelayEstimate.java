package app.sada.poc.dsp;

/**
 * Measures how far the microphone's echo lags the podcast reference, directly from recorded audio (GCC-PHAT
 * cross-correlation). Independent of Android timestamps: used to check whether the echo actually falls inside the
 * window the echo canceller can handle.
 */
public final class DelayEstimate {
    /** lag in samples (positive = the echo in mic comes AFTER the reference sample), and peak strength over average. */
    public static final class Result {
        public final int lag; public final double strength;
        Result(int lag, double strength) { this.lag = lag; this.strength = strength; }
    }

    /** Searches lags in [-maxNeg, +maxPos] samples. Returns null if a signal is (almost) silent. */
    public static Result estimate(float[] mic, float[] ref, int maxNeg, int maxPos) {
        int n = Math.min(mic.length, ref.length);
        int size = 1; while (size < 2 * n) size <<= 1;
        float[] ar = new float[size], ai = new float[size], br = new float[size], bi = new float[size];
        double em = 0, er = 0;
        for (int i = 0; i < n; i++) { ar[i] = mic[i]; br[i] = ref[i]; em += mic[i] * mic[i]; er += ref[i] * ref[i]; }
        if (em < 1e-9 || er < 1e-9) return null;
        Fft f = new Fft(size);
        f.transform(ar, ai, false);
        f.transform(br, bi, false);
        // cross-spectrum MIC * conj(REF), phase-transform weighted (sharp peak, robust to the spectrum of speech)
        for (int k = 0; k < size; k++) {
            float re = ar[k] * br[k] + ai[k] * bi[k];
            float im = ai[k] * br[k] - ar[k] * bi[k];
            float mag = (float) Math.sqrt(re * re + im * im) + 1e-12f;
            ar[k] = re / mag; ai[k] = im / mag;
        }
        f.transform(ar, ai, true);
        int best = 0; double bestV = -1, sum = 0; int count = 0;
        for (int lag = -maxNeg; lag <= maxPos; lag++) {
            double v = Math.abs(ar[(lag + size) % size]);
            sum += v; count++;
            if (v > bestV) { bestV = v; best = lag; }
        }
        return new Result(best, bestV / (sum / count + 1e-20));
    }
}
