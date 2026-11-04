package app.sada.poc.dsp;

/**
 * Decides when a spoken question starts and ends, on 16 ms blocks, from energy above an adaptive noise floor
 * (measured during the first 128 ms unless seeded).
 * Start: speech in 5 of the last 6 blocks. End: 0.8 s without speech. Limits: no speech within noSpeechMs → give up;
 * longer than maxMs → stop.
 */
public final class Endpointer {
    public enum State { WAITING, SPEAKING, DONE }
    public enum Reason { NONE, END_OF_SPEECH, NO_SPEECH, MAX_LENGTH }

    private static final int BLOCK_MS = 16;
    private final int noSpeechBlocks, maxBlocks, endBlocks;
    /** Silence that ends a question: long enough for thinking pauses, short enough to feel responsive. */
    public static final int DEFAULT_END_SILENCE_MS = 1400;
    private double noiseDb = -60;
    private int blocks = 0, speechStart = -1, lastSpeech = -1, recent = 0;
    private State state = State.WAITING;
    private Reason reason = Reason.NONE;
    private double speechSum = 0; private int speechN = 0;
    private static final int CALIBRATION = 8;
    private double calSum = 0;
    private boolean seeded = false;

    /**
     * noSpeechMs: give up if nobody starts speaking within this time. maxMs: longest question, counted from when the
     * user STARTS speaking (the silent wait before it does not shorten the question). endSilenceMs: silence that ends it.
     */
    public Endpointer(int noSpeechMs, int maxMs, int endSilenceMs) {
        noSpeechBlocks = noSpeechMs / BLOCK_MS; maxBlocks = maxMs / BLOCK_MS; endBlocks = endSilenceMs / BLOCK_MS;
    }
    public Endpointer(int noSpeechMs, int maxMs) { this(noSpeechMs, maxMs, DEFAULT_END_SILENCE_MS); }
    public Endpointer() { this(6000, 12000); }

    /** Seeds the noise floor from the level measured just before capture started. */
    public void setNoiseFloorDb(double db) { noiseDb = db; seeded = true; }

    public State state() { return state; }
    /** Blocks processed so far (16 ms each). */
    public int blocks() { return blocks; }
    public Reason reason() { return reason; }
    public int speechStartBlock() { return speechStart; }
    public int speechEndBlock() { return lastSpeech; }
    public double noiseFloorDb() { return noiseDb; }
    public double speechLevelDb() { return speechN == 0 ? -120 : speechSum / speechN; }

    public State process(float[] block, int n) {
        if (state == State.DONE) return state;
        double s = 0; for (int i = 0; i < n; i++) s += block[i] * block[i];
        double db = 10 * Math.log10(s / Math.max(1, n) + 1e-12);
        // The first 128 ms measure the background (street, fabric, wind) before any speech is looked for.
        if (blocks < CALIBRATION) {
            calSum += db;
            blocks++;
            if (blocks == CALIBRATION && !seeded) noiseDb = calSum / CALIBRATION;
            return state;
        }
        boolean speech = db > noiseDb + 10;
        if (!speech) noiseDb = db < noiseDb ? 0.8 * noiseDb + 0.2 * db : noiseDb + 0.01;
        recent = ((recent << 1) | (speech ? 1 : 0)) & 0x3f;
        if (speech) { lastSpeech = blocks; speechSum += db; speechN++; }
        if (state == State.WAITING && Integer.bitCount(recent) >= 5) { state = State.SPEAKING; speechStart = Math.max(0, blocks - 5); }
        blocks++;
        if (state == State.WAITING && blocks >= noSpeechBlocks) { state = State.DONE; reason = Reason.NO_SPEECH; }
        else if (state == State.SPEAKING && blocks - lastSpeech > endBlocks) { state = State.DONE; reason = Reason.END_OF_SPEECH; }
        else if (state == State.SPEAKING && blocks - speechStart >= maxBlocks) { state = State.DONE; reason = Reason.MAX_LENGTH; }
        return state;
    }
}
