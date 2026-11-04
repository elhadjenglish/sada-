package app.sada.poc.dsp;

/**
 * What the podcast player actually sent to the speaker, as 16 kHz mono, indexed by absolute sample number.
 * Written by the playback thread, read by the microphone thread. Unknown samples read as silence.
 */
public final class ReferenceBuffer {
    private final float[] ring;
    private long written = 0; // absolute index of the next sample to be written

    public ReferenceBuffer(int capacity) { ring = new float[capacity]; }

    public synchronized void write(long startIndex, float[] samples, int n) {
        if (startIndex > written) { // gap: fill with silence
            long gap = Math.min(startIndex - written, ring.length);
            for (long i = startIndex - gap; i < startIndex; i++) ring[(int) (i % ring.length)] = 0f;
        }
        for (int i = 0; i < n; i++) ring[(int) ((startIndex + i) % ring.length)] = samples[i];
        written = Math.max(written, startIndex + n);
    }

    /** Copies n samples starting at absolute index start; returns how many were actually available. */
    public synchronized int read(long start, float[] out, int n) {
        int got = 0;
        long oldest = written - ring.length;
        for (int i = 0; i < n; i++) {
            long idx = start + i;
            if (idx >= 0 && idx < written && idx >= oldest) { out[i] = ring[(int) (idx % ring.length)]; got++; }
            else out[i] = 0f;
        }
        return got;
    }

    public synchronized long written() { return written; }
    public synchronized void clear() { written = 0; java.util.Arrays.fill(ring, 0f); }
}
