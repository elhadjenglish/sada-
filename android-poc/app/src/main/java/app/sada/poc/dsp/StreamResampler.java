package app.sada.poc.dsp;

/**
 * Converts a continuous stream at fromRate to 16 kHz-style toRate, chunk by chunk, with no seams between chunks.
 * Output sample k corresponds exactly to input time k * fromRate / toRate.
 */
public final class StreamResampler {
    private final int from, to;
    private long inCount = 0;    // input samples received so far
    private long outCount = 0;   // output samples produced so far
    private float last = 0f;     // last input sample of the previous chunk
    private final float[] avg;   // short moving average against aliasing when downsampling
    private int avgPos = 0; private float avgSum = 0f;

    public StreamResampler(int fromRate, int toRate) {
        from = fromRate; to = toRate;
        int w = fromRate > toRate ? (int) Math.ceil((double) fromRate / toRate) : 1;
        avg = new float[w];
    }

    public long outputCount() { return outCount; }

    /** Returns resampled output for this chunk (may be empty). */
    public float[] process(float[] in, int n) {
        if (n <= 0) return new float[0];
        float[] filt = new float[n];
        for (int i = 0; i < n; i++) {
            avgSum += in[i] - avg[avgPos]; avg[avgPos] = in[i]; avgPos = (avgPos + 1) % avg.length;
            filt[i] = avgSum / avg.length;
        }
        long total = inCount + n;
        // largest output index whose input position (k*from/to) has a right neighbour available
        long maxOut = (long) Math.floor(((double) (total - 1)) * to / from) + 1;
        int count = (int) Math.max(0, maxOut - outCount);
        float[] out = new float[count];
        for (int j = 0; j < count; j++) {
            double pos = (double) (outCount + j) * from / to;
            long i0 = (long) Math.floor(pos);
            double f = pos - i0;
            float a = sample(filt, i0), b = sample(filt, Math.min(i0 + 1, total - 1));
            out[j] = (float) (a + (b - a) * f);
        }
        outCount += count;
        last = filt[n - 1];
        inCount = total;
        return out;
    }

    private float sample(float[] chunk, long absIndex) {
        long rel = absIndex - inCount;
        if (rel < 0) return last;
        if (rel >= chunk.length) return chunk[chunk.length - 1];
        return chunk[(int) rel];
    }
}
