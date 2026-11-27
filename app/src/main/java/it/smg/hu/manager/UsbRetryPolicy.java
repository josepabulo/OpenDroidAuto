package it.smg.hu.manager;

/** Bounded timings for legacy USB negotiation and AOAP re-enumeration. */
final class UsbRetryPolicy {
    static final int NEGOTIATION_ATTEMPTS = 4;
    static final int REENUMERATION_ATTEMPTS = 9;
    static final long POST_RESET_AOAP_STABILITY_MS = 400L;
    static final long MTP_SETTLE_MS = 300L;
    static final long SESSION_RESET_WINDOW_MS = 3000L;

    private UsbRetryPolicy() {}

    static long negotiationDelayMs(int failedAttempt) {
        if (failedAttempt <= 0) {
            return 250L;
        }
        return failedAttempt == 1 ? 500L : 800L;
    }

    static long reenumerationDelayMs(int scanAttempt) {
        return 350L + Math.min(Math.max(scanAttempt, 0) * 250L, 1000L);
    }
}
