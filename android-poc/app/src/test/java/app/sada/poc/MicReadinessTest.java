package app.sada.poc;

import static app.sada.poc.MicReadiness.Outcome.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MicReadinessTest {
    /** Feeds block levels until ready; returns the number of blocks needed, or -1. */
    static int blocksUntilReady(MicReadiness r, double... levels) {
        for (int i = 0; i < levels.length; i++) if (r.push(levels[i])) return i + 1;
        return -1;
    }

    static double[] repeat(double v, int n) { double[] a = new double[n]; java.util.Arrays.fill(a, v); return a; }
    static double[] concat(double[] a, double[] b) { double[] c = new double[a.length + b.length]; System.arraycopy(a, 0, c, 0, a.length); System.arraycopy(b, 0, c, a.length, b.length); return c; }

    @Test public void routeReportedButDigitalSilenceIsNotReady() {
        // Android said the route is active, but the recorder delivers zeros (-140 dBFS): not ready
        assertEquals(-1, blocksUntilReady(new MicReadiness(), repeat(-140, 60)));
    }

    @Test public void readyOnceLiveAudioFlows() {
        // ~0.5 s of silence (delayed link), then background noise: ready after 6 live blocks (~100 ms)
        int n = blocksUntilReady(new MicReadiness(), concat(repeat(-140, 30), repeat(-62, 20)));
        assertEquals(30 + MicReadiness.NEED_LIVE, n);
    }

    @Test public void stutteringLinkRestartsTheCount() {
        MicReadiness r = new MicReadiness();
        int n = blocksUntilReady(r, -60, -60, -60, -140, -60, -60, -60, -60, -60, -60);
        assertEquals("needs 6 consecutive live blocks", 10, n);
    }

    @Test public void backgroundMeasuredWhileGettingReady() {
        MicReadiness r = new MicReadiness();
        blocksUntilReady(r, repeat(-62, 6));
        assertEquals(-62, r.backgroundDb(), 1e-9);
        assertFalse(r.userAlreadySpeaking());
    }

    @Test public void detectsUserAlreadySpeaking() {
        MicReadiness r = new MicReadiness();
        blocksUntilReady(r, -60, -30, -28, -32, -60, -60);
        assertTrue(r.userAlreadySpeaking());
        assertEquals("background ignores speech blocks", -60, r.backgroundDb(), 1e-9);
    }

    // ---- decisions: delayed initialisation, disconnection, timeout and fallback ----

    @Test public void earbudReady() { assertEquals(EARBUD_READY, MicReadiness.decide(true, 1500, true)); }

    @Test public void noEarbudsUsesPhone() { assertEquals(PHONE, MicReadiness.decide(false, -1, false)); }

    @Test public void routeNeverConfirmedOrBluetoothDroppedFallsBack() {
        assertEquals(FALLBACK_NOT_CONNECTED, MicReadiness.decide(true, -1, false));
    }

    @Test public void routeConfirmedButNoAudioFallsBack() {
        assertEquals(FALLBACK_NO_AUDIO, MicReadiness.decide(true, 1600, false));
    }

    @Test public void promptsTellTheUserWhichMicAndWhenToSpeak() {
        assertEquals("Go ahead, I'm listening.", MicReadiness.prompt(EARBUD_READY, false));
        assertEquals("Go ahead, I'm listening.", MicReadiness.prompt(PHONE, false));
        assertTrue(MicReadiness.prompt(FALLBACK_NO_AUDIO, false).contains("on the phone"));
        assertTrue(MicReadiness.prompt(FALLBACK_NOT_CONNECTED, false).contains("on the phone"));
        assertTrue(MicReadiness.prompt(EARBUD_READY, true).startsWith("Sorry, I missed the start."));
        assertEquals("earbud mic", MicReadiness.label(EARBUD_READY));
        assertTrue(MicReadiness.label(FALLBACK_NO_AUDIO).startsWith("phone mic"));
    }
}
