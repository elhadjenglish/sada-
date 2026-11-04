package app.sada.poc.dsp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import java.util.Random;

/**
 * SIMULATION of the question path as the app runs it: earbud/phone mic → gain control (for the recording) →
 * end-of-speech detection. A quiet question raises the gain; afterwards the background must still read as silence.
 */
public class QuestionEndpointTest {
    static final int B = 256, FS = 16000;

    static float[] question(long seed) {
        Random r = new Random(seed);
        int n = 9 * FS;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) (r.nextGaussian() * Math.pow(10, -62 / 20.0));        // background
        float[] speech = SignalSim.speechLike(3 * FS, seed + 1, Math.pow(10, -40 / 20.0));             // quiet question
        for (int i = 0; i < speech.length; i++) x[2 * FS + i] += speech[i];                             // 2 s silence first
        return x;
    }

    /** Old wiring: the endpointer saw the gain-controlled signal. */
    static Endpointer.Reason runOld(float[] x) {
        Agc agc = new Agc(); Endpointer ep = new Endpointer(6000, 12000);
        float[] in = new float[B], out = new float[B];
        for (int p = 0; p + B <= x.length && ep.state() != Endpointer.State.DONE; p += B) {
            System.arraycopy(x, p, in, 0, B); agc.process(in, out, B); ep.process(out, B);
        }
        return ep.state() == Endpointer.State.DONE ? ep.reason() : Endpointer.Reason.MAX_LENGTH;
    }

    /** New wiring: gain control only for the recording; the endpointer sees the signal before it. */
    static Endpointer runNew(float[] x) {
        Agc agc = new Agc(); Endpointer ep = new Endpointer(6000, 12000);
        float[] in = new float[B], out = new float[B];
        for (int p = 0; p + B <= x.length && ep.state() != Endpointer.State.DONE; p += B) {
            System.arraycopy(x, p, in, 0, B); agc.process(in, out, B); ep.process(in, B);
        }
        return ep;
    }

    @Test public void gainControlBeforeTheEndpointerNeverEnds() {
        Endpointer.Reason r = runOld(question(1));
        System.out.printf("    old wiring (gain control → endpointer): %s%n", r);
        assertEquals("reproduces the device report", Endpointer.Reason.MAX_LENGTH, r);
    }

    @Test public void endpointerBeforeGainControlEndsAfterSpeech() {
        Endpointer ep = runNew(question(1));
        System.out.printf("    new wiring: %s, speech %.2f–%.2f s (true 2.00–5.00, after 2 s of silence)%n",
            ep.reason(), ep.speechStartBlock() * 0.016, ep.speechEndBlock() * 0.016);
        assertEquals(Endpointer.Reason.END_OF_SPEECH, ep.reason());
        assertEquals(2.0, ep.speechStartBlock() * 0.016, 0.3);
        assertEquals(5.0, ep.speechEndBlock() * 0.016, 0.5);
    }
}
