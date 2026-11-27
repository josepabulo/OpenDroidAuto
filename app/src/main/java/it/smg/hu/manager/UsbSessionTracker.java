package it.smg.hu.manager;

/**
 * Tracks whether the currently attached AOAP node was negotiated by this process
 * and filters transient reset/re-enumeration events without Android dependencies.
 */
final class UsbSessionTracker {
    private int verifiedFreshAoapDeviceId = -1;
    private int recentResetDeviceId = -1;
    private long recentResetUntilMs = 0L;

    boolean isFreshAoap(int deviceId) {
        return deviceId != -1 && deviceId == verifiedFreshAoapDeviceId;
    }

    void markAoapFresh(int deviceId) {
        verifiedFreshAoapDeviceId = deviceId;
        recentResetDeviceId = -1;
        recentResetUntilMs = 0L;
    }

    void clearFreshAoap() {
        verifiedFreshAoapDeviceId = -1;
    }

    void beginSessionReset(int deviceId, long nowMs, long windowMs) {
        verifiedFreshAoapDeviceId = -1;
        recentResetDeviceId = deviceId;
        recentResetUntilMs = nowMs + windowMs;
    }

    boolean isWithinResetWindow(long nowMs) {
        return recentResetUntilMs > 0L && nowMs < recentResetUntilMs;
    }

    boolean isExpectedResetDetach(int deviceId, boolean isAoap, long nowMs) {
        return isWithinResetWindow(nowMs) && (deviceId == recentResetDeviceId || isAoap);
    }

    boolean isTransientResetAoap(int deviceId, boolean isAoap, long nowMs) {
        return isAoap && isWithinResetWindow(nowMs) && deviceId != verifiedFreshAoapDeviceId;
    }

    void clearResetWindow() {
        recentResetDeviceId = -1;
        recentResetUntilMs = 0L;
    }
}
