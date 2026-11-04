package app.sada.poc.dsp;

/**
 * Microphone processing chain, one 256-sample (16 ms) block at a time:
 *   align with what the speaker is playing → echo canceller → residual echo suppressor → gain control.
 * Everything starts from the calibrated microphone (fixed gain, see InputGain).
 * Two outputs go to the wake-word detector, which accepts a keyword from either:
 *   podcast on the speaker: main = canceller + suppressor (strongest echo removal), alt = canceller only (most faithful voice);
 *   otherwise:              main = calibrated + adaptive gain, alt = calibrated only (fixed, predictable level).
 * Echo cancellation runs only when the podcast plays through the phone speaker (with earbuds there is no
 * acoustic echo to remove). Plain Java so the whole chain is tested on the build machine.
 */
public final class FrontEnd {
    public static final int BLOCK = EchoCanceller.N;
    /** The reference is read this far ahead of the predicted moment, so an early or late estimate stays inside the filter. */
    public static final int LEAD = 512; // 32 ms

    private final ReferenceBuffer reference;
    private final EchoCanceller aec = new EchoCanceller();
    private final ResidualEchoSuppressor res = new ResidualEchoSuppressor();
    private final Agc agc = new Agc();
    private final InputGain inputGain = new InputGain();
    private final float[] cal = new float[BLOCK];
    private double rawDb = -120, mainDb = -120, altDb = -120;
    private final float[] ref = new float[BLOCK], lin = new float[BLOCK], sup = new float[BLOCK], lastEchoCancelled = new float[BLOCK];
    private boolean podcastActive = false;

    /** Set by the player: true while the podcast is actually playing. */
    public void setPodcastActive(boolean on) { podcastActive = on; }
    private boolean aecOn = false;
    private long aecBlocks = 0, missingRef = 0, realigns = 0;
    private long cursor = Long.MIN_VALUE;
    private long harmBlocks = 0;
    private final float[] refTap = new float[BLOCK];

    /**
     * The podcast reference at the position Android's timestamps predict for the last microphone block (no lead),
     * zero when unknown. Recorded by the phrase test to measure the real echo delay; works with cancellation on or off.
     */
    public float[] lastReferenceTap() { return refTap; }
    private static final int REALIGN = 32; // 2 ms

    public FrontEnd(ReferenceBuffer reference) { this.reference = reference; }

    public void setEchoCancellation(boolean on) {
        // After a pause the alignment is re-measured from timestamps, which is never sample-exact; measured in
        // simulation, re-learning from zero recovers as fast as keeping the old filter (about 3 s). Keep it simple.
        if (on != aecOn && !on) aec.reset();
        if (on != aecOn) cursor = Long.MIN_VALUE;
        aecOn = on;
    }

    public boolean echoCancellationOn() { return aecOn; }

    /**
     * mic: one block of microphone samples; micIndex: absolute index of its first sample;
     * offset: reference index − mic index from ClockAlign (NaN if unknown). Writes the processed block to out.
     */
    public void setInputGainDb(double db) { inputGain.setGainDb(db); }
    public double inputGainDb() { return inputGain.gainDb(); }

    public void process(float[] rawMic, long micIndex, double offset, float[] out, float[] alt) {
        rawDb = db(rawMic);
        if (podcastActive && !Double.isNaN(offset)) reference.read(micIndex + Math.round(offset), refTap, BLOCK);
        else java.util.Arrays.fill(refTap, 0f);
        float[] src;
        float[] altSrc;
        if (aecOn && !Double.isNaN(offset)) {
            // The echo canceller works on the RAW microphone signal: the speaker echo is loud, and the calibration
            // gain's limiter would distort it into something a linear filter cannot cancel. Gain is applied after.
            // Read the reference as one continuous stream: the filter needs consecutive blocks. Re-align only when
            // the clocks have really drifted apart (> 2 ms), never for timestamp jitter.
            // Start LEAD samples *ahead* of the sample predicted to be playing: the echo then appears LEAD samples into
            // the filter (causal), with room for the prediction to be early or late. Those future samples exist because
            // the player writes audio well before it is heard.
            long target = micIndex + Math.round(offset) + LEAD;
            if (cursor == Long.MIN_VALUE || Math.abs(cursor - target) > REALIGN) {
                if (cursor != Long.MIN_VALUE) realigns++;
                cursor = target;
            }
            long start = cursor;
            cursor += BLOCK;
            if (reference.read(start, ref, BLOCK) < BLOCK) missingRef++;
            aec.process(rawMic, ref, lin);
            res.process(lin, aec.echoEstimate(), sup);
            // No-harm rule: a misaligned filter (e.g. just after the podcast resumes) can ADD echo. If the processed
            // block is louder than the microphone, give the detector the microphone instead for that block.
            double pm = power(rawMic), pl = power(lin), ps = power(sup);
            if (pl > pm) { System.arraycopy(rawMic, 0, lin, 0, BLOCK); harmBlocks++; }
            if (ps > pm) System.arraycopy(rawMic, 0, sup, 0, BLOCK);
            inputGain.process(sup, sup, BLOCK);
            inputGain.process(lin, lin, BLOCK);
            src = sup;
            altSrc = lin;
            aecBlocks++;
        } else {
            inputGain.process(rawMic, cal, BLOCK);
            src = cal;
            altSrc = cal;
        }
        // While the podcast plays through the speaker the signal is dominated by (residual) echo: gain control must
        // not learn from it, or it would amplify leftover narrator. It adapts again once playback is paused.
        agc.setAdapt(!(aecOn && podcastActive));
        System.arraycopy(src, 0, lastEchoCancelled, 0, BLOCK);
        agc.process(src, out, BLOCK);
        if (alt != null) System.arraycopy(altSrc, 0, alt, 0, BLOCK);
        mainDb = db(out);
        altDb = db(altSrc);
    }

    private static double power(float[] x) { double s = 0; for (float v : x) s += v * v; return s; }

    /** Blocks where the canceller output was louder than the microphone and the microphone was used instead. */
    public long harmBlocks() { return harmBlocks; }

    private static double db(float[] x) {
        double s = 0; for (float v : x) s += v * v;
        return 10 * Math.log10(s / x.length + 1e-14);
    }

    /** Levels of the last block (dBFS): raw microphone, main detector stream, alternative detector stream. */
    public double rawDb() { return rawDb; }
    public double mainDb() { return mainDb; }
    public double altDb() { return altDb; }

    /** Single-output form (alt discarded). */
    public void process(float[] mic, long micIndex, double offset, float[] out) { process(mic, micIndex, offset, out, null); }

    /** The echo-cancelled block before gain control (for diagnostics and tests). */
    public float[] lastEchoCancelled() { return lastEchoCancelled; }

    public EchoCanceller echoCanceller() { return aec; }
    public Agc agc() { return agc; }
    public long aecBlocks() { return aecBlocks; }
    public long missingReferenceBlocks() { return missingRef; }
    public long realigns() { return realigns; }
}
