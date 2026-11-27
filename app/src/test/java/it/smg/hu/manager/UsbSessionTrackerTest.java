package it.smg.hu.manager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsbSessionTrackerTest {
    @Test
    public void inheritedAoapIsNotFreshUntilNegotiatedByThisProcess() {
        UsbSessionTracker tracker = new UsbSessionTracker();
        assertFalse(tracker.isFreshAoap(101));

        tracker.markAoapFresh(104);
        assertTrue(tracker.isFreshAoap(104));
        assertFalse(tracker.isFreshAoap(101));

        tracker.clearFreshAoap();
        assertFalse(tracker.isFreshAoap(104));
    }

    @Test
    public void resetWindowFiltersExpectedDetachAndTransientAoapOnly() {
        UsbSessionTracker tracker = new UsbSessionTracker();
        tracker.markAoapFresh(101);

        tracker.beginSessionReset(101, 10_000L, UsbRetryPolicy.SESSION_RESET_WINDOW_MS);
        assertFalse(tracker.isFreshAoap(101));

        // Expected reset detaches and brief 110ms transient AOAP nodes are absorbed.
        assertTrue(tracker.isExpectedResetDetach(101, true, 10_250L));
        assertTrue(tracker.isTransientResetAoap(102, true, 11_100L));
        assertTrue(tracker.isExpectedResetDetach(102, true, 11_210L));

        // MTP re-enumeration is never treated as transient AOAP, and an MTP unplug is real.
        assertFalse(tracker.isTransientResetAoap(103, false, 11_300L));
        assertFalse(tracker.isExpectedResetDetach(103, false, 11_400L));

        // Once the window expires, any detach is treated as a physical unplug.
        assertFalse(tracker.isWithinResetWindow(13_000L));
        assertFalse(tracker.isExpectedResetDetach(101, true, 13_000L));
        assertFalse(tracker.isTransientResetAoap(102, true, 13_000L));
    }

    @Test
    public void freshNegotiationClearsActiveResetWindow() {
        UsbSessionTracker tracker = new UsbSessionTracker();
        tracker.beginSessionReset(101, 20_000L, UsbRetryPolicy.SESSION_RESET_WINDOW_MS);
        assertTrue(tracker.isWithinResetWindow(20_500L));

        tracker.markAoapFresh(104);
        assertFalse(tracker.isWithinResetWindow(20_600L));
        assertFalse(tracker.isTransientResetAoap(104, true, 20_600L));
        assertFalse(tracker.isExpectedResetDetach(104, true, 20_600L));
    }

    @Test
    public void clearResetWindowStopsFilteringImmediately() {
        UsbSessionTracker tracker = new UsbSessionTracker();
        tracker.beginSessionReset(101, 20_000L, UsbRetryPolicy.SESSION_RESET_WINDOW_MS);
        assertTrue(tracker.isWithinResetWindow(20_100L));

        tracker.clearResetWindow();
        assertFalse(tracker.isWithinResetWindow(20_100L));
        assertFalse(tracker.isExpectedResetDetach(101, true, 20_100L));
        assertFalse(tracker.isTransientResetAoap(102, true, 20_100L));
    }
}
