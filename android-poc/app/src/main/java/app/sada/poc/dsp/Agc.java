package app.sada.poc.dsp;

/**
 * Automatic gain control for a quiet, muffled pocket microphone. Raises speech toward a target level,
 * never amplifies background noise beyond a gate, never boosts by more than maxGainDb, and soft-limits peaks.
 */
public final class Agc {
    private final double targetDb, maxGainDb;
    private double gainDb = 0, noiseDb = -70;
    private boolean first = true;
    private boolean adapt = true;

    /** When false, the current gain is applied but not changed (used while the podcast plays through the speaker). */
    public void setAdapt(boolean on) { adapt = on; }

    public Agc(double targetDb, double maxGainDb) { this.targetDb = targetDb; this.maxGainDb = maxGainDb; }
    public Agc() { this(-24, 24); }

    public double gainDb() { return gainDb; }

    /** Starting gain (e.g. what an earlier session learned), instead of 0 dB. It keeps adapting from there. */
    public void setGainDb(double db) { gainDb = db; }
    public double noiseFloorDb() { return noiseDb; }

    public void process(float[] in, float[] out, int n) {
        double s = 0; for (int i = 0; i < n; i++) s += in[i] * in[i];
        double db = 10 * Math.log10(s / Math.max(1, n) + 1e-12);
        // noise floor: starts at the first block, follows quieter blocks quickly, rises about 3 dB per second
        if (first) { noiseDb = db; first = false; }
        noiseDb = db < noiseDb ? 0.9 * noiseDb + 0.1 * db : noiseDb + 0.05;
        boolean speechLike = db > noiseDb + 8;
        if (adapt && speechLike) {
            double want = Math.max(0, Math.min(maxGainDb, targetDb - db));
            // fast enough to help a short phrase: up to 1 dB per 16 ms block (about 60 dB/s), down 3 dB per block
            gainDb += want < gainDb ? Math.max(-3.0, want - gainDb) : Math.min(1.0, want - gainDb);
        }
        double g = Math.pow(10, gainDb / 20);
        for (int i = 0; i < n; i++) {
            double v = in[i] * g;
            out[i] = (float) (Math.abs(v) > 0.8 ? Math.signum(v) * (0.8 + 0.2 * Math.tanh((Math.abs(v) - 0.8) / 0.2)) : v);
        }
    }
}
