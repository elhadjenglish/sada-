package app.sada.poc.dsp;

/**
 * Describes each second of raw microphone audio, so a report can tell apart: silence, background noise, quiet speech,
 * clipped (distorted) audio and usable speech. Works on 16 ms blocks; speech = blocks well above the noise floor.
 * Only counts and levels are kept, never audio.
 */
public final class SignalClassifier {
    public enum Kind { SILENCE, NOISE, QUIET_SPEECH, CLIPPED, USABLE_SPEECH }

    public static final double SILENCE_DB = -80;      // digital near-silence: a dead or wrongly routed input
    public static final double USABLE_SPEECH_DB = -42; // raw speech level the detector handles well after calibration
    private final int blocksPerSecond;
    private double noiseDb = Double.NaN;
    private int blocks = 0, speechBlocks = 0, clipped = 0, samples = 0;
    private double sumDb = 0, speechDb = 0;
    private final long[] counts = new long[Kind.values().length];
    private Kind last = Kind.SILENCE;
    private double lastSpeechDb = -120, lastLevelDb = -120;

    public SignalClassifier(int blockSize, int sampleRate) { blocksPerSecond = Math.max(1, sampleRate / blockSize); }

    /** Feeds one block of raw samples in [-1, 1]. Returns the kind when a second completes, else null. */
    public Kind process(float[] x, int n) {
        double s = 0;
        for (int i = 0; i < n; i++) { s += x[i] * x[i]; if (Math.abs(x[i]) >= 0.999f) clipped++; }
        samples += n;
        double db = 10 * Math.log10(s / Math.max(1, n) + 1e-14);
        if (Double.isNaN(noiseDb)) noiseDb = db;
        boolean speech = db > noiseDb + 10 && db > SILENCE_DB + 10;
        if (!speech) noiseDb = db < noiseDb ? 0.8 * noiseDb + 0.2 * db : noiseDb + 0.02;
        if (speech) { speechBlocks++; speechDb += db; }
        sumDb += db;
        if (++blocks < blocksPerSecond) return null;

        double level = sumDb / blocks;
        boolean hasSpeech = speechBlocks >= blocks / 5;
        double sp = hasSpeech ? speechDb / speechBlocks : -120;
        Kind k;
        if (level < SILENCE_DB) k = Kind.SILENCE;
        else if (clipped > samples / 1000) k = Kind.CLIPPED;
        else if (!hasSpeech) k = Kind.NOISE;
        else if (sp < USABLE_SPEECH_DB) k = Kind.QUIET_SPEECH;
        else k = Kind.USABLE_SPEECH;
        counts[k.ordinal()]++;
        last = k; lastSpeechDb = sp; lastLevelDb = level;
        blocks = 0; speechBlocks = 0; clipped = 0; samples = 0; sumDb = 0; speechDb = 0;
        return k;
    }

    public long count(Kind k) { return counts[k.ordinal()]; }
    public Kind last() { return last; }
    public double lastSpeechDb() { return lastSpeechDb; }
    public double lastLevelDb() { return lastLevelDb; }
    public double noiseFloorDb() { return noiseDb; }
    public void reset() { java.util.Arrays.fill(counts, 0); noiseDb = Double.NaN; blocks = 0; speechBlocks = 0; clipped = 0; samples = 0; sumDb = 0; speechDb = 0; }
}
