package app.sada.poc.dsp;

/**
 * Tells whether the microphone audio is narrowband (Bluetooth 8 kHz link, nothing above ~4 kHz) or wideband
 * (16 kHz link): compares energy at 4.2–7.8 kHz with energy at 0.3–3.4 kHz, during louder (speech-like) blocks only.
 * Android does not report the Bluetooth voice codec; this measures its effect on the actual audio.
 */
public final class BandwidthMeter {
    public static final int N = 256;
    private final Fft fft = new Fft(N);
    private final float[] re = new float[N], im = new float[N], win = new float[N];
    private double noiseDb = Double.NaN, low = 0, high = 0;
    private int speechBlocks = 0;

    public BandwidthMeter() {
        for (int i = 0; i < N; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
    }

    /** One 256-sample block at 16 kHz. */
    public void process(float[] x) {
        double s = 0;
        for (int i = 0; i < N; i++) { s += x[i] * x[i]; re[i] = x[i] * win[i]; im[i] = 0f; }
        double db = 10 * Math.log10(s / N + 1e-14);
        if (Double.isNaN(noiseDb)) noiseDb = db;
        if (db < noiseDb + 10) { noiseDb = db < noiseDb ? 0.9 * noiseDb + 0.1 * db : noiseDb + 0.02; return; }
        fft.transform(re, im, false);
        // bin k = k * 62.5 Hz
        for (int k = 5; k <= 54; k++) low += re[k] * re[k] + im[k] * im[k];      // 0.31–3.4 kHz
        for (int k = 67; k <= 124; k++) high += re[k] * re[k] + im[k] * im[k];   // 4.2–7.8 kHz
        speechBlocks++;
    }

    /** High-band energy relative to the speech band, in dB (very negative = narrowband). NaN until enough speech. */
    public double ratioDb() { return speechBlocks < 40 ? Double.NaN : 10 * Math.log10((high + 1e-20) / (low + 1e-20)); }
    public int speechBlocks() { return speechBlocks; }

    public String verdict() {
        double r = ratioDb();
        if (Double.isNaN(r)) return "not enough speech yet";
        return (r < -35 ? "NARROWBAND (8 kHz link: nothing above 4 kHz)" : "wideband (16 kHz link)") + String.format(java.util.Locale.US, ", high/low %.0f dB", r);
    }

    public void reset() { noiseDb = Double.NaN; low = 0; high = 0; speechBlocks = 0; }
}
