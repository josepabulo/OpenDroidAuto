package it.smg.libs.aasdk.usb;

import androidx.annotation.Keep;

import it.smg.libs.common.Log;

public class AOAPDevice {

    private static final String TAG = "AOAPDevice";

    private LibUsbDevice usbDevice_;

    private static native void nativeInit();
    private native void nativeDelete();
    private native long nativeSetup(LibUsbDevice usbDevice);

    private AOAPDevice(LibUsbDevice usbDevice){
        usbDevice_ = usbDevice;

        handle_ = nativeSetup(usbDevice);
    }

    public void delete(){
        nativeDelete();
        try {
            usbDevice_.reset();
        } catch (Throwable error) {
            Log.w(TAG, "Error resetting AOAP USB device during delete: " + error);
        }
        usbDevice_.close();
    }

    public static AOAPDevice create(LibUsbDevice usbDevice) {
        return new AOAPDevice(usbDevice);
    }

    static {
        nativeInit();
    }

    @Keep
    private long handle_;
}
