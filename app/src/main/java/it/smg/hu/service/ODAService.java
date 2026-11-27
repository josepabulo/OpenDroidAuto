package it.smg.hu.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.SurfaceView;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.Keep;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import it.smg.hu.R;
import it.smg.hu.config.Settings;
import it.smg.hu.manager.HondaConnectManager;
import it.smg.hu.manager.ConnectionManager;
import it.smg.hu.manager.USBManager;
import it.smg.hu.manager.WIFIManager;
import it.smg.hu.projection.InputDevice;
import it.smg.hu.ui.notification.NotificationFactory;
import it.smg.libs.common.Log;
import it.smg.libs.aasdk.service.AndroidAutoEntity;
import it.smg.libs.aasdk.service.IAndroidAutoEntityEventHandler;
import it.smg.libs.aasdk.tcp.TCPEndpoint;
import it.smg.libs.aasdk.usb.LibUsbDevice;

public class ODAService extends Service implements IAndroidAutoEntityEventHandler {

    public static final String START_ACTION = "it.smg.hu.service.ODAService.START_ACTION";
    public static final String STOP_ACTION = "it.smg.hu.service.ODAService.STOP_ACTION";
    public static final String STOP_VIDEO_INDICATION = "it.smg.hu.service.ODAService.STOP_VIDEO_INDICATION";
    public static final String FORCE_CLOSE_ACTION = "it.smg.hu.service.ODAService.FORCE_CLOSE_ACTION";

    public static final String EXTRA_START_MODE = "startMode";
    public static final String MODE_USB = "modeUSB";
    public static final String MODE_WIFI = "modeWifi";

    private static final String TAG = "ODAService";
    private static final long SHUTDOWN_TIMEOUT_MS = 2000L;

    private final IBinder mBinder = new ServiceBinder();

    private LocalBroadcastManager localBroadcastManager_;
    private NotificationFactory notificationFactory_;
    /**
     * Written by the connection thread and read by the UI thread and by native
     * quit callbacks. It also gates whether a new session may start, so a stale
     * read here would either skip a teardown or lock connecting out entirely.
     */
    private volatile AndroidAutoEntity androidAutoEntity_;

    private  USBManager usbManager_;
    private  WIFIManager wifiManager_;

    private Handler mainHandler_;

    private volatile boolean isRunning_;
    private volatile boolean stopRequested_;
    private volatile boolean shutdownRequested_;
    private volatile boolean errorQuit_;
    private volatile boolean autoRecoverUsb_;
    private volatile String currentMode_;

    private final Runnable shutdownTimeout_ = new Runnable() {
        @Override
        public void run() {
            if (shutdownRequested_) {
                if (Log.isWarn()) Log.w(TAG, "graceful shutdown timed out; forcing teardown");
                ConnectionManager.instance().userExited(getString(R.string.connection_user_stopped));
                stop();
            }
        }
    };

    /**
     * Guards the session lifecycle. Tearing an AndroidAutoEntity down runs a
     * cascade of native destructors while io_service handlers are still in
     * flight; letting a new session start on top of that left two entities alive
     * at once, which showed up as SEGV in the asio strand and as a wedged main
     * thread. A flag rather than a lock on purpose: stop() is reached both from
     * the UI thread and from native callbacks, so blocking one on the other could
     * deadlock the very service that has to finish the teardown.
     */
    private final AtomicBoolean stopping_ = new AtomicBoolean(false);
    private final AtomicBoolean starting_ = new AtomicBoolean(false);
    private final Object entityLock_ = new Object();
    private boolean usbStartInProgress_;

    public ODAService() {}

    @Override
    public void onCreate() {
        if (Log.isInfo()) Log.i(TAG, "create");
        super.onCreate();

        notificationFactory_ = NotificationFactory.instance();

        usbManager_ = USBManager.instance();
        wifiManager_ = WIFIManager.instance();
        mainHandler_ = new Handler(Looper.getMainLooper());

        localBroadcastManager_ = LocalBroadcastManager.getInstance(this);
    }

    public void startUsb(SurfaceView surfaceView, InputDevice.OnKeyHolder keyHolder){
        if (!canStartSession()) {
            return;
        }
        currentMode_ = MODE_USB;
        synchronized (entityLock_) {
            stopRequested_ = false;
        }
        errorQuit_ = false;
        autoRecoverUsb_ = false;
        ConnectionManager.instance().connecting(MODE_USB, getString(R.string.connection_usb_connecting));
        Thread startThread = new Thread(() -> {
            Looper.prepare();
            LibUsbDevice openedDevice = null;
            AndroidAutoEntity builtEntity = null;
            boolean published = false;
            boolean startedUsb = false;
            boolean resumeDeferredStop = false;
            try {
                LibUsbDevice device = usbManager_.aoapDevice();
                if (device != null) {
                    if (Log.isVerbose()) Log.v(TAG, "aoap device available, start in usb mode");
                    synchronized (entityLock_) {
                        if (stopRequested_ || stopping_.get()) {
                            return;
                        }
                        usbStartInProgress_ = true;
                        startedUsb = true;
                    }
                    try {
                        if (device.open()) {
                            openedDevice = device;
                            if (Log.isInfo()) Log.i(TAG, "device opened");
                            if (stopRequested_ || stopping_.get()) {
                                try {
                                    openedDevice.close();
                                } catch (Throwable ignored) {}
                                resumeDeferredStop = true;
                                return;
                            }
                            builtEntity = AndroidAutoEntityFactory.create(this, device, surfaceView, keyHolder);
                            if (stopRequested_ || stopping_.get()) {
                                deleteEntityWithReset(builtEntity, device);
                                resumeDeferredStop = true;
                                return;
                            }
                            builtEntity.start(this);
                            boolean aborted;
                            synchronized (entityLock_) {
                                aborted = stopRequested_ || stopping_.get();
                                if (!aborted) {
                                    usbStartInProgress_ = false;
                                    androidAutoEntity_ = builtEntity;
                                    isRunning_ = true;
                                    published = true;
                                    ConnectionManager.instance().active(getString(R.string.connection_usb_active));
                                }
                            }
                            if (aborted) {
                                deleteEntityWithReset(builtEntity, device);
                                resumeDeferredStop = true;
                                return;
                            }
                        } else {
                            Log.e(TAG, "Error in open usb device");
                            if (stopRequested_ || shutdownRequested_ || stopping_.get()) {
                                resumeDeferredStop = true;
                            } else {
                                onAndroidAutoQuitOnError("USB OPEN DEVICE", -1);
                            }
                            return;
                        }
                    } catch (Exception e){
                        Log.e(TAG, "error", e);
                        if (builtEntity != null) {
                            boolean owned = !published;
                            if (published) {
                                synchronized (entityLock_) {
                                    if (androidAutoEntity_ == builtEntity) {
                                        androidAutoEntity_ = null;
                                        owned = true;
                                    }
                                }
                            }
                            if (owned) {
                                deleteEntityWithReset(builtEntity, device);
                            }
                        } else if (openedDevice != null) {
                            try { openedDevice.close(); } catch (Throwable ignored) {}
                        }
                        if (stopRequested_ || shutdownRequested_ || stopping_.get()) {
                            resumeDeferredStop = true;
                        } else {
                            onAndroidAutoQuitOnError("USB GENERIC ERROR", -1);
                        }
//                    onAndroidAutoQuit();
                        return;
                    }
                } else {
                    if (!stopRequested_ && !shutdownRequested_ && !stopping_.get()) {
                        ConnectionManager.instance().failed(MODE_USB, getString(R.string.connection_usb_unavailable));
                    }
                    runNativeQuitOnMainThread(this::stop);
                }
                if (Log.isInfo()) Log.i(TAG, "start usb thead completed");
            } finally {
                synchronized (entityLock_) {
                    usbStartInProgress_ = false;
                }
                starting_.set(false);
                if (resumeDeferredStop || (startedUsb && !published && stopRequested_)) {
                    runNativeQuitOnMainThread(this::stop);
                }
            }
        });
        startThread.start();
    }

    public void startWifi(SurfaceView surfaceView, InputDevice.OnKeyHolder keyHolder){
        if (!canStartSession()) {
            return;
        }
        currentMode_ = MODE_WIFI;
        synchronized (entityLock_) {
            stopRequested_ = false;
        }
        errorQuit_ = false;
        autoRecoverUsb_ = false;
        ConnectionManager.instance().connecting(MODE_WIFI, getString(R.string.connection_wifi_connecting));
        Thread startThread = new Thread(() -> {
            Looper.prepare();

            String ipAddress = wifiManager_ != null ? wifiManager_.getIpAddress() : null;
            TCPEndpoint tcpEndpoint = null;
            AndroidAutoEntity builtEntity = null;
            boolean published = false;
            try {
                if (ipAddress != null) {
                    if (stopRequested_ || stopping_.get()) {
                        return;
                    }
                    tcpEndpoint = new TCPEndpoint(ipAddress);
                    builtEntity = AndroidAutoEntityFactory.create(this, tcpEndpoint, surfaceView, keyHolder);
                    if (stopRequested_ || stopping_.get()) {
                        builtEntity.stop();
                        builtEntity.delete();
                        return;
                    }
                    builtEntity.start(this);
                    boolean aborted;
                    synchronized (entityLock_) {
                        aborted = stopRequested_ || stopping_.get();
                        if (!aborted) {
                            androidAutoEntity_ = builtEntity;
                            isRunning_ = true;
                            published = true;
                            ConnectionManager.instance().active(getString(R.string.connection_wifi_active));
                        }
                    }
                    if (aborted) {
                        builtEntity.stop();
                        builtEntity.delete();
                        return;
                    }
                    if (Log.isInfo()) Log.i(TAG, "start wifi thead completed");
                } else {
                    if (!stopRequested_ && !shutdownRequested_ && !stopping_.get()) {
                        ConnectionManager.instance().failed(MODE_WIFI, getString(R.string.connection_wifi_gateway_error));
                    }
                    runNativeQuitOnMainThread(this::stop);
                }
            } catch (Exception e){
                Log.e(TAG, "Wi-Fi connection error", e);
                if (builtEntity != null) {
                    boolean owned = !published;
                    if (published) {
                        synchronized (entityLock_) {
                            if (androidAutoEntity_ == builtEntity) {
                                androidAutoEntity_ = null;
                                owned = true;
                            }
                        }
                    }
                    if (owned) {
                        try {
                            builtEntity.stop();
                        } catch (Throwable ignored) {}
                        try {
                            builtEntity.delete();
                        } catch (Throwable ignored) {}
                    }
                } else if (tcpEndpoint != null) {
                    tcpEndpoint.delete();
                }
                if (stopRequested_ || shutdownRequested_ || stopping_.get()) {
                    if (Log.isInfo()) Log.i(TAG, "ignoring Wi-Fi connect failure after stop was requested");
                    return;
                }
                // The phone refuses port 5277 unless Android Auto Wireless is
                // actually running on it. Say so on screen: the projection window
                // closes within a second either way, which otherwise looks
                // exactly like the app crashing.
                String target = ipAddress != null ? ipAddress : "";
                String reason = getString(R.string.connection_wifi_endpoint_error, target);
                ConnectionManager.instance().failed(MODE_WIFI, reason);
                notifyUser(reason);
                runNativeQuitOnMainThread(this::stop);
            } finally {
                starting_.set(false);
            }
        });
        startThread.start();
    }

    public void shutdown(){
        if (shutdownRequested_) {
            return;
        }
        shutdownRequested_ = true;
        AndroidAutoEntity entity;
        synchronized (entityLock_) {
            entity = androidAutoEntity_;
        }
        if (entity == null) {
            ConnectionManager.instance().userExited(getString(R.string.connection_user_stopped));
            stop();
            return;
        }

        mainHandler_.removeCallbacks(shutdownTimeout_);
        mainHandler_.postDelayed(shutdownTimeout_, SHUTDOWN_TIMEOUT_MS);
        try {
            entity.shutdown();
        } catch (Throwable error) {
            Log.e(TAG, "error requesting graceful Android Auto shutdown", error);
            ConnectionManager.instance().userExited(getString(R.string.connection_user_stopped));
            stop();
        }
    }

    public boolean isSessionActive() {
        return !stopRequested_ && !stopping_.get()
                && (starting_.get() || (isRunning_ && androidAutoEntity_ != null));
    }

    public String currentMode() {
        return currentMode_;
    }

    public void releaseFocus(){
        AndroidAutoEntity entity;
        synchronized (entityLock_) {
            entity = androidAutoEntity_;
        }
        if (entity != null) {
            entity.releaseFocus();
        }
    }

    public void gainFocus(){
        AndroidAutoEntity entity;
        synchronized (entityLock_) {
            entity = androidAutoEntity_;
        }
        if (entity != null) {
            entity.gainFocus();
        }
    }

    /**
     * Whether a new projection session may be started right now. A session that
     * is still running, or one whose native teardown has not finished yet, must
     * be left alone.
     */
    private boolean canStartSession(){
        if (!starting_.compareAndSet(false, true)) {
            if (Log.isWarn()) Log.w(TAG, "start ignored: session start already in progress");
            return false;
        }
        if (isRunning_ || androidAutoEntity_ != null || stopping_.get()) {
            starting_.set(false);
            if (Log.isWarn()) Log.w(TAG, "start ignored: previous session still active or shutting down");
            return false;
        }
        return true;
    }

    public void stop(){
        synchronized (entityLock_) {
            stopRequested_ = true;
        }
        if (mainHandler_ != null) {
            mainHandler_.removeCallbacks(shutdownTimeout_);
        }

        // The native teardown is not reentrant, and stop() arrives from the UI
        // thread, from the connection thread and from native quit callbacks.
        if (!stopping_.compareAndSet(false, true)) {
            if (Log.isInfo()) Log.i(TAG, "stop already in progress");
            return;
        }

        boolean handoffToUsbStart = false;
        try {
            LibUsbDevice sessionUsbDevice = MODE_USB.equals(currentMode_)
                    ? usbManager_.aoapDevice() : null;
            AndroidAutoEntity entity;
            boolean wasRunning;
            synchronized (entityLock_) {
                wasRunning = isRunning_;
                isRunning_ = false;
                entity = androidAutoEntity_;
                androidAutoEntity_ = null;
                handoffToUsbStart = (!wasRunning && entity == null && usbStartInProgress_);
            }
            if (handoffToUsbStart) {
                if (Log.isInfo()) Log.i(TAG, "deferring stop cleanup while USB session startup is aborting");
                return;
            }
            boolean autoRecoverUsb = autoRecoverUsb_;
            autoRecoverUsb_ = false;
            if (!wasRunning && entity == null) {
                if (Log.isInfo()) Log.i(TAG, "service not running, already stopped?");
                finishUsbSessionAfterStop(sessionUsbDevice, errorQuit_, autoRecoverUsb);
                localBroadcastManager_.sendBroadcast(new Intent(ODAService.STOP_ACTION));
//                stopService(new Intent(this, ODAService.class));
                stopForeground(true);
                stopSelf();
                return;
            }

            if (Log.isInfo()) Log.i(TAG, "Stop");

            // Every step below is individually guarded: anything that escapes here
            // would leave androidAutoEntity_ set, and since that field gates
            // canStartSession() the app could never connect again without being
            // force stopped.
            if (Settings.instance().advanced.hondaIntegrationEnabled()){
                // init() only runs at boot when the setting was already on, so
                // enabling it later leaves the singleton null.
                HondaConnectManager hondaManager = HondaConnectManager.instance();
                if (hondaManager != null) {
                    try {
                        hondaManager.endAudioBinding();
                    } catch (Throwable t) {
                        Log.e(TAG, "error ending Honda audio binding", t);
                    }
                }
            }

            deleteEntityWithReset(entity, sessionUsbDevice);

            finishUsbSessionAfterStop(sessionUsbDevice, errorQuit_, autoRecoverUsb);

            Intent stopIntent = new Intent(ODAService.STOP_ACTION);
            localBroadcastManager_.sendBroadcast(stopIntent);

//        		Intent service = new Intent(this, ODAService.class);
//        		stopService(service);
            stopForeground(true);
            stopSelf();
        } finally {
            // Cleared unconditionally so a failed teardown cannot wedge the
            // service into a state where no further session can be started, unless
            // startup still owns the USB handle and will re-invoke stop() after closing it.
            if (!handoffToUsbStart) {
                androidAutoEntity_ = null;
                currentMode_ = null;
                shutdownRequested_ = false;
                errorQuit_ = false;
                autoRecoverUsb_ = false;
            }
            stopping_.set(false);
        }

    }

    private void deleteEntityWithReset(AndroidAutoEntity entity, LibUsbDevice sessionUsbDevice) {
        if (entity == null) {
            return;
        }
        try {
            entity.stop();
        } catch (Throwable t) {
            Log.e(TAG, "error stopping Android Auto entity", t);
        }
        if (sessionUsbDevice != null) {
            usbManager_.beginSessionReset(sessionUsbDevice);
        }
        try {
            entity.delete();
        } catch (Throwable t) {
            Log.e(TAG, "error deleting Android Auto entity", t);
        }
    }

    private void finishUsbSessionAfterStop(LibUsbDevice sessionUsbDevice, boolean keepErrorState, boolean autoRecover) {
        if (sessionUsbDevice != null) {
            usbManager_.finishSession(sessionUsbDevice, keepErrorState, autoRecover);
        } else if (autoRecover && MODE_USB.equals(currentMode_)) {
            usbManager_.recoverConnection();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void onDestroy() {
        if (Log.isDebug()) Log.d(TAG, "onDestroy");
        // stopService() from the home screen or the exit widget destroys this
        // service without going through stop(), which used to leave the session,
        // its io_service threads and the USB handle alive. The replacement service
        // then starts with androidAutoEntity_ == null and happily builds a second
        // session on top of the first one. stop() is idempotent via stopping_.
        stop();
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (Log.isDebug()) Log.d(TAG, "onUnbind");
        return super.onUnbind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Log.isDebug()) Log.d(TAG, "Received start id " + startId + ": " + intent);

        Notification notification = notificationFactory_.create();
        startForeground(123456, notification);

        return START_NOT_STICKY;
    }

    @Keep
    @Override
    public void onAndroidAutoQuit() {
        runNativeQuitOnMainThread(() -> {
            if (shutdownRequested_) {
                ConnectionManager.instance().userExited(getString(R.string.connection_user_stopped));
            } else {
                ConnectionManager.instance().detached(getString(R.string.connection_session_ended));
            }
            stop();
        });
    }

    @Keep
    @Override
    public void onAndroidAutoQuitOnError(String error, int nativeErrorCode){
        runNativeQuitOnMainThread(() -> {
            if (shutdownRequested_) {
                if (Log.isInfo()) Log.i(TAG, "transport closed while graceful shutdown was pending");
                ConnectionManager.instance().userExited(getString(R.string.connection_user_stopped));
                stop();
                return;
            }
            if ("SSL_NOT_ACTIVE".equals(error) && MODE_USB.equals(currentMode_)) {
                if (Log.isWarn()) Log.w(TAG, "stale USB AOAP session detected before TLS handshake; recovering USB automatically");
                autoRecoverUsb_ = true;
                errorQuit_ = false;
                stop();
                return;
            }
            Log.e(TAG, "closing with error " + error + "(" + nativeErrorCode + ")");
            errorQuit_ = true;
            String message = getString(R.string.connection_native_error_code, error, nativeErrorCode);
            ConnectionManager.instance().failed(currentMode_, message);
            notifyUser(message);

            stop();
        });
    }

    /**
     * Native errors are delivered from an aasdk io_service worker. Destroying the
     * entity on that same worker leaves its currently executing asio handler
     * running against a destroyed strand, producing the SEGV seen on disconnect.
     * Queue the lifecycle work on Android's main thread so the worker can return
     * before Runtime.stopIOServiceWorkers() joins it.
     */
    private void runNativeQuitOnMainThread(Runnable action) {
        if (mainHandler_ == null || Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler_.post(action);
        }
    }

    /** Shows a message to the user from any thread. */
    private void notifyUser(String message){
        mainHandler_.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Keep
    @Override
    public void onAVChannelStopIndication() {
        if (Log.isInfo()) Log.i(TAG, "stop video indication");
        Intent stopIntent = new Intent(ODAService.STOP_VIDEO_INDICATION);
        localBroadcastManager_.sendBroadcast(stopIntent);
    }

    public class ServiceBinder extends Binder {
        public ODAService getService() {
            return ODAService.this;
        }
    }

}
