package app.sada.poc.dsp;

import java.util.Random;

/** Synthetic signals for SIMULATION tests. Not real speech or real acoustics. */
final class SignalSim {
    static final int FS = 16000;

    /** Speech-like: low-pass-shaped noise with pitch harmonics and a syllable-rate on/off envelope. */
    static float[] speechLike(int n, long seed, double rms) {
        Random r = new Random(seed);
        float[] x = new float[n];
        double lp = 0, phase = 0, f0 = 110 + r.nextInt(120), env = 0;
        int seg = 0; boolean on = true;
        for (int i = 0; i < n; i++) {
            if (seg-- <= 0) { on = !on || r.nextDouble() < 0.2; seg = on ? 2400 + r.nextInt(3200) : 800 + r.nextInt(1600); f0 = 100 + r.nextInt(150); }
            env += ((on ? 1.0 : 0.0) - env) * 0.002;
            lp += (r.nextGaussian() - lp) * 0.3;
            phase += 2 * Math.PI * f0 / FS;
            double v = lp * 0.6 + 0.5 * Math.sin(phase) + 0.25 * Math.sin(2 * phase + 1) + 0.15 * Math.sin(3 * phase + 2);
            x[i] = (float) (v * env);
        }
        return scale(x, rms);
    }

    static float[] scale(float[] x, double rms) {
        double s = 0; for (float v : x) s += v * v;
        double k = rms / Math.sqrt(s / x.length + 1e-20);
        float[] y = new float[x.length];
        for (int i = 0; i < x.length; i++) y[i] = (float) (x[i] * k);
        return y;
    }

    /** Room-like echo path: a delay, then a decaying random impulse response. */
    static float[] echoPath(int delay, int length, long seed) {
        Random r = new Random(seed);
        float[] h = new float[delay + length];
        for (int i = 1; i < length; i++) h[delay + i] = (float) (0.3 * r.nextGaussian() * Math.exp(-i / (0.02 * FS)));
        h[delay] = 1.0f; // direct speaker-to-microphone path dominates inside a phone
        return h;
    }

    static float[] convolve(float[] x, float[] h, double gain) {
        float[] y = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            double s = 0;
            int kmax = Math.min(h.length - 1, i);
            for (int k = 0; k <= kmax; k++) if (h[k] != 0f) s += h[k] * x[i - k];
            y[i] = (float) (s * gain);
        }
        return y;
    }

    static double power(float[] x, int from, int to) {
        double s = 0; for (int i = from; i < to; i++) s += x[i] * x[i];
        return s / Math.max(1, to - from);
    }

    static double db(double ratio) { return 10 * Math.log10(ratio + 1e-20); }

    static double rms(float[] x) { return Math.sqrt(power(x, 0, x.length)); }

    /** Runs mic/ref through the canceller (optionally + residual suppressor); returns the output signal. */
    static float[] run(EchoCanceller aec, ResidualEchoSuppressor res, float[] mic, float[] ref) {
        int N = EchoCanceller.N;
        int len = (mic.length / EchoCanceller.N) * EchoCanceller.N; // whole blocks only
        float[] out = new float[len];
        float[] m = new float[N], r = new float[N], e = new float[N], o = new float[N];
        for (int b = 0; b + N <= len; b += N) {
            System.arraycopy(mic, b, m, 0, N); System.arraycopy(ref, b, r, 0, N);
            aec.process(m, r, e);
            if (res != null) { res.process(e, aec.echoEstimate(), o); System.arraycopy(o, 0, out, b, N); }
            else System.arraycopy(e, 0, out, b, N);
        }
        return out;
    }

    static double correlation(float[] a, int aFrom, float[] b, int bFrom, int n) {
        double ab = 0, aa = 0, bb = 0;
        for (int i = 0; i < n; i++) { double x = a[aFrom + i], y = b[bFrom + i]; ab += x * y; aa += x * x; bb += y * y; }
        return ab / Math.sqrt(aa * bb + 1e-20);
    }
}
