package it.smg.hu.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsbRetryPolicyTest {
    @Test
    public void negotiationRetriesAreShortAndBounded() {
        assertEquals(4, UsbRetryPolicy.NEGOTIATION_ATTEMPTS);
        assertEquals(250L, UsbRetryPolicy.negotiationDelayMs(0));
        assertEquals(500L, UsbRetryPolicy.negotiationDelayMs(1));
        assertEquals(800L, UsbRetryPolicy.negotiationDelayMs(2));
        assertEquals(400L, UsbRetryPolicy.POST_RESET_AOAP_STABILITY_MS);
        assertEquals(300L, UsbRetryPolicy.MTP_SETTLE_MS);
        assertEquals(3000L, UsbRetryPolicy.SESSION_RESET_WINDOW_MS);
    }

    @Test
    public void reenumerationWatchdogUsesBoundedBackoff() {
        long totalDelay = 0L;
        long previousDelay = 0L;
        for (int attempt = 0; attempt < UsbRetryPolicy.REENUMERATION_ATTEMPTS; attempt++) {
            long delay = UsbRetryPolicy.reenumerationDelayMs(attempt);
            assertTrue(delay >= previousDelay);
            assertTrue(delay <= 1350L);
            previousDelay = delay;
            totalDelay += delay;
        }
        assertTrue(totalDelay < 12000L);
    }
}
