package app.sada.poc.dsp;

/** In-place radix-2 complex FFT for a fixed power-of-two size. Plain Java, no Android classes. */
public final class Fft {
    private final int n;
    private final int[] rev;
    private final float[] cos, sin;

    public Fft(int n) {
        if (Integer.bitCount(n) != 1) throw new IllegalArgumentException("size must be a power of two");
        this.n = n;
        rev = new int[n];
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        cos = new float[n / 2];
        sin = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            cos[i] = (float) Math.cos(2 * Math.PI * i / n);
            sin[i] = (float) Math.sin(2 * Math.PI * i / n);
        }
    }

    public int size() { return n; }

    /** Forward transform (inverse = false) or inverse transform scaled by 1/n (inverse = true). */
    public void transform(float[] re, float[] im, boolean inverse) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1, step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < half; k++) {
                    float wr = cos[k * step], wi = inverse ? sin[k * step] : -sin[k * step];
                    int a = i + k, b = a + half;
                    float xr = re[b] * wr - im[b] * wi;
                    float xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                }
            }
        }
        if (inverse) {
            float s = 1f / n;
            for (int i = 0; i < n; i++) { re[i] *= s; im[i] *= s; }
        }
    }
}
