package app.sada.poc.dsp;

/**
 * Acoustic echo canceller for podcast playback through the phone speaker.
 *
 * Partitioned-block frequency-domain adaptive filter (overlap-save, constrained gradient): models the path
 * speaker → room → microphone and subtracts the predicted podcast from the microphone signal.
 * Block 256 samples (16 ms at 16 kHz), 16 partitions = 256 ms of echo path.
 *
 * Two-path design: a background filter adapts all the time; the foreground filter, which produces the output,
 * only takes the background's coefficients when the background is clearly cancelling more echo. When the user
 * talks over the podcast the background is disturbed but the foreground is untouched, so the user's voice is
 * not damaged; when the echo path changes (the phone moves), the background re-learns and is copied over.
 * No double-talk detector is needed.
 */
public final class EchoCanceller {
    public static final int N = 256;
    private static final int M = 2 * N;
    private final int partitions;
    private final Fft fft = new Fft(M);
    private final float[][] xr, xi;     // reference spectra history
    private final float[][] br, bi;     // background filter (always adapting)
    private final float[][] fr, fi;     // foreground filter (produces the output)
    private final float[] prevRef = new float[N];
    private final float[] pow = new float[M];
    private final float[] ar = new float[M], ai = new float[M], gr = new float[M], gi = new float[M];
    private final float[] yBlock = new float[N], yBack = new float[N], eBack = new float[N];
    private final float mu;

    // statistics (for filter selection and diagnostics)
    private double sF = 1e-12, sB = 1e-12;
    private int badBlocks = 0, blocks = 0, farBlocks = 0, disturbedBlocks = 0, resets = 0, copies = 0;
    private double erleDb = 0;

    public EchoCanceller(int partitions, float mu) {
        this.partitions = partitions;
        this.mu = mu;
        xr = new float[partitions][M]; xi = new float[partitions][M];
        br = new float[partitions][M]; bi = new float[partitions][M];
        fr = new float[partitions][M]; fi = new float[partitions][M];
        java.util.Arrays.fill(pow, 1e-6f);
    }

    public EchoCanceller() { this(16, 0.5f); }

    /** Echo estimate of the last processed block (for the residual suppressor). */
    public float[] echoEstimate() { return yBlock; }

    private void filter(float[][] wr, float[][] wi, float[] out) {
        java.util.Arrays.fill(ar, 0f); java.util.Arrays.fill(ai, 0f);
        for (int p = 0; p < partitions; p++) {
            float[] a = xr[p], b = xi[p], c = wr[p], d = wi[p];
            for (int k = 0; k < M; k++) { ar[k] += a[k] * c[k] - b[k] * d[k]; ai[k] += a[k] * d[k] + b[k] * c[k]; }
        }
        fft.transform(ar, ai, true);
        System.arraycopy(ar, N, out, 0, N);
    }

    /**
     * Processes one block: mic and ref are N samples each; ref is the podcast audio that was playing at (or a
     * little before) each mic sample. Writes the echo-cancelled signal into out.
     */
    public void process(float[] mic, float[] ref, float[] out) {
        float[] tr = xr[partitions - 1], ti = xi[partitions - 1];
        for (int p = partitions - 1; p > 0; p--) { xr[p] = xr[p - 1]; xi[p] = xi[p - 1]; }
        xr[0] = tr; xi[0] = ti;
        double px = 0;
        for (int i = 0; i < N; i++) { tr[i] = prevRef[i]; tr[i + N] = ref[i]; px += ref[i] * ref[i]; }
        java.util.Arrays.fill(ti, 0f);
        System.arraycopy(ref, 0, prevRef, 0, N);
        fft.transform(tr, ti, false);
        px /= N;

        filter(fr, fi, yBlock);
        filter(br, bi, yBack);
        double pd = 0, pf = 0, pb = 0;
        for (int i = 0; i < N; i++) {
            out[i] = mic[i] - yBlock[i];
            eBack[i] = mic[i] - yBack[i];
            pd += mic[i] * mic[i]; pf += out[i] * out[i]; pb += eBack[i] * eBack[i];
        }
        pd /= N; pf /= N; pb /= N;
        blocks++;
        boolean far = px > 1e-6; // podcast above about -60 dBFS
        if (!far) return;
        farBlocks++;

        // choose the better filter
        sF = 0.8 * sF + 0.2 * pf;
        sB = 0.8 * sB + 0.2 * pb;
        if (sB < 0.5 * sF) { // background at least 3 dB better: take it
            for (int p = 0; p < partitions; p++) { System.arraycopy(br[p], 0, fr[p], 0, M); System.arraycopy(bi[p], 0, fi[p], 0, M); }
            sF = sB; copies++;
        } else if (sB > 2 * sF) disturbedBlocks++; // background disturbed (usually the user talking)
        erleDb = 0.98 * erleDb + 0.02 * Math.max(-10, Math.min(60, 10 * Math.log10((pd + 1e-12) / (pf + 1e-12))));

        // divergence guard for the background filter
        if (pb > 4.0 * pd && pd > 1e-8) { if (++badBlocks > 20) resetBackground(); } else badBlocks = 0;

        // adapt the background filter: E = FFT([0, e_background])
        java.util.Arrays.fill(ar, 0f); java.util.Arrays.fill(ai, 0f);
        for (int i = 0; i < N; i++) ar[i + N] = eBack[i];
        fft.transform(ar, ai, false);
        float[] x0r = xr[0], x0i = xi[0];
        for (int k = 0; k < M; k++) pow[k] = 0.9f * pow[k] + 0.1f * (x0r[k] * x0r[k] + x0i[k] * x0i[k]);
        float floor = (float) (px * M * 1e-2 + 1e-8);
        for (int p = 0; p < partitions; p++) {
            float[] a = xr[p], b = xi[p];
            for (int k = 0; k < M; k++) {
                float s = mu / (pow[k] * partitions + floor);
                gr[k] = (a[k] * ar[k] + b[k] * ai[k]) * s;   // conj(X) * E
                gi[k] = (a[k] * ai[k] - b[k] * ar[k]) * s;
            }
            fft.transform(gr, gi, true);                      // constrain to a causal N-tap partition
            for (int i = N; i < M; i++) { gr[i] = 0f; gi[i] = 0f; }
            for (int i = 0; i < N; i++) gi[i] = 0f;
            fft.transform(gr, gi, false);
            float[] c = br[p], d = bi[p];
            for (int k = 0; k < M; k++) { c[k] += gr[k]; d[k] += gi[k]; }
        }
    }

    private void resetBackground() {
        for (int p = 0; p < partitions; p++) { java.util.Arrays.fill(br[p], 0f); java.util.Arrays.fill(bi[p], 0f); }
        badBlocks = 0; resets++;
    }

    public void reset() {
        resetBackground();
        for (int p = 0; p < partitions; p++) { java.util.Arrays.fill(fr[p], 0f); java.util.Arrays.fill(fi[p], 0f); }
        sF = 1e-12; sB = 1e-12; erleDb = 0;
    }

    /** Smoothed echo reduction in dB while the podcast plays. */
    public double erleDb() { return erleDb; }
    /** Fraction of podcast time the background filter was disturbed (usually the user speaking). */
    public double doubleTalkFraction() { return farBlocks == 0 ? 0 : (double) disturbedBlocks / farBlocks; }
    public int resets() { return resets; }
    public int copies() { return copies; }
    public int blocks() { return blocks; }

    /** Strongest echo-path tap of the output filter, in samples (acoustic + alignment delay). */
    public int peakTap() {
        int best = 0; float bestV = 0f;
        float[] r = new float[M], i = new float[M];
        for (int p = 0; p < partitions; p++) {
            System.arraycopy(fr[p], 0, r, 0, M); System.arraycopy(fi[p], 0, i, 0, M);
            fft.transform(r, i, true);
            for (int t = 0; t < N; t++) { float v = Math.abs(r[t]); if (v > bestV) { bestV = v; best = p * N + t; } }
        }
        return best;
    }
}
