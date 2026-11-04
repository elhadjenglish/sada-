package app.sada.poc;

/**
 * Recognises a deliberate double press of Volume Up, without reacting to single presses or to a key held down
 * (Android repeats a held key quickly, which a pocket can cause). Plain Java, so it is unit-tested.
 *
 * A burst of presses ends after a short quiet period, then it is classified:
 *   exactly 2 presses, 100–450 ms apart, then 200 ms quiet → DOUBLE (Sada action; the volume is NOT changed)
 *   anything else (1 press, 3+ presses, or very fast presses)   → VOLUME steps (normal volume change)
 */
public final class VolumeGesture {
    public static final long MIN_GAP = 100, MAX_GAP = 450, CONFIRM = 200;

    public static final class Decision {
        public final boolean isDouble; public final int volumeSteps;
        Decision(boolean d, int s) { isDouble = d; volumeSteps = s; }
    }

    private int count = 0;
    private long first = 0, last = 0, minGap = Long.MAX_VALUE;

    /** A Volume Up press at time t (ms). Call poll() after nextPollDelay() ms. */
    public void press(long t) {
        if (count > 0) minGap = Math.min(minGap, t - last); else first = t;
        count++;
        last = t;
    }

    /** How long to wait after the latest press before poll() can decide. */
    public long nextPollDelay() { return count == 2 ? CONFIRM : MAX_GAP; }

    /** Returns a decision once the burst is over, else null. */
    public Decision poll(long t) {
        if (count == 0) return null;
        long quiet = t - last;
        boolean twoCandidate = count == 2 && minGap >= MIN_GAP && minGap <= MAX_GAP;
        if (twoCandidate && quiet >= CONFIRM) return finish(new Decision(true, 0));
        if (quiet >= MAX_GAP) return finish(new Decision(false, count));
        return null;
    }

    private Decision finish(Decision d) { count = 0; minGap = Long.MAX_VALUE; return d; }

    public boolean busy() { return count > 0; }

    /** Number of presses in the current burst (for diagnostics). */
    public int pressesInBurst() { return count; }
}
