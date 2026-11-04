package app.sada.poc.dsp;

/**
 * Removes echo the linear filter misses (speaker distortion, a moving phone): per-frequency gain that turns down
 * bins where the predicted echo dominates. sqrt-Hann 512/256 overlap-add, so it adds 16 ms of delay.
 */
public final class ResidualEchoSuppressor {
    private static final int N = EchoCanceller.N, M = 2 * N;
    private final Fft fft = new Fft(M);
    private final float[] win = new float[M];
    private final float[] eHist = new float[M], yHist = new float[M];
    private final float[] er = new float[M], ei = new float[M], yr = new float[M], yi = new float[M];
    private final float[] gain = new float[M], overlap = new float[N];
    private final float overSubtract, minGain;

    public ResidualEchoSuppressor(float overSubtract, float minGain) {
        this.overSubtract = overSubtract; this.minGain = minGain;
        for (int i = 0; i < M; i++) win[i] = (float) Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / M));
        java.util.Arrays.fill(gain, 1f);
    }

    public ResidualEchoSuppressor() { this(2.0f, 0.1f); }

    /** e: echo-cancelled block, y: echo estimate block (both N). Writes N output samples (delayed by N). */
    public void process(float[] e, float[] y, float[] out) {
        System.arraycopy(eHist, N, eHist, 0, N); System.arraycopy(e, 0, eHist, N, N);
        System.arraycopy(yHist, N, yHist, 0, N); System.arraycopy(y, 0, yHist, N, N);
        for (int i = 0; i < M; i++) { er[i] = eHist[i] * win[i]; ei[i] = 0f; yr[i] = yHist[i] * win[i]; yi[i] = 0f; }
        fft.transform(er, ei, false);
        fft.transform(yr, yi, false);
        for (int k = 0; k < M; k++) {
            float pe = er[k] * er[k] + ei[k] * ei[k] + 1e-12f;
            float py = yr[k] * yr[k] + yi[k] * yi[k];
            float g = Math.max(minGain, 1f - overSubtract * py / pe);
            gain[k] = g < gain[k] ? g : 0.7f * gain[k] + 0.3f * g; // fast down, slower up
            er[k] *= gain[k]; ei[k] *= gain[k];
        }
        fft.transform(er, ei, true);
        for (int i = 0; i < N; i++) {
            out[i] = overlap[i] + er[i] * win[i];
            overlap[i] = er[i + N] * win[i + N];
        }
    }
}
