package com.ponie.dayov12.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import rikka.shizuku.Shizuku;

/**
 * App-side owner of the narrow privileged input service. Permission is requested only
 * when the user explicitly enables Freeze Analog; opening FOX never touches Shizuku.
 */
final class ShizukuInputBridge {
    interface Listener {
        void onInputBridgeReadyChanged(boolean ready);
        void onInputBridgePermissionDenied();
    }

    static final int READY = 2;
    static final int WAITING = 1;
    static final int UNAVAILABLE = 0;
    static final int DENIED = -1;

    private static final int REQUEST_CODE = 19421;

    private final Context context;
    private final Listener listener;
    private final Shizuku.UserServiceArgs userServiceArgs;
    private volatile IBinder remote;
    private volatile boolean binding;
    private volatile boolean permissionRequested;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            remote = binder != null && binder.pingBinder() ? binder : null;
            notifyReady(remote != null);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            binding = false;
            remote = null;
            notifyReady(false);
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::handleBinderReceived;
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        binding = false;
        remote = null;
        notifyReady(false);
    };
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode != REQUEST_CODE) return;
        permissionRequested = false;
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            bindUserService();
        } else {
            remote = null;
            binding = false;
            if (listener != null) listener.onInputBridgePermissionDenied();
        }
    };

    ShizukuInputBridge(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.userServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(this.context.getPackageName(), PrivilegedInputService.class.getName()))
                .daemon(false)
                .processNameSuffix("freeze_input")
                .debuggable(false)
                .version(1);
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionListener);
    }

    boolean isReady() {
        IBinder binder = remote;
        return binder != null && binder.pingBinder();
    }

    /** Called only from the explicit dashboard enable action. */
    int ensureReady() {
        if (isReady()) return READY;
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return UNAVAILABLE;
            int permission = Shizuku.checkSelfPermission();
            if (permission == PackageManager.PERMISSION_GRANTED) {
                bindUserService();
                return isReady() ? READY : WAITING;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) return DENIED;
            if (!permissionRequested) {
                permissionRequested = true;
                Shizuku.requestPermission(REQUEST_CODE);
            }
            return WAITING;
        } catch (Throwable error) {
            android.util.Log.e("FreezeAnalogInput", "Unable to prepare Shizuku input bridge", error);
            return UNAVAILABLE;
        }
    }

    /**
     * Queue one event without waiting for a result. All timestamps and coordinates come
     * from the physical MotionEvent captured by the analog window.
     */
    boolean inject(int action, long downTime, long eventTime, float rawX, float rawY) {
        IBinder binder = remote;
        if (binder == null || !binder.pingBinder()) return false;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(PrivilegedInputService.DESCRIPTOR);
            data.writeInt(action);
            data.writeLong(downTime);
            data.writeLong(eventTime);
            data.writeFloat(rawX);
            data.writeFloat(rawY);
            return binder.transact(
                    PrivilegedInputService.TRANSACTION_INJECT,
                    data,
                    null,
                    IBinder.FLAG_ONEWAY);
        } catch (RemoteException error) {
            remote = null;
            binding = false;
            notifyReady(false);
            android.util.Log.e("FreezeAnalogInput", "Input relay binder died", error);
            return false;
        } finally {
            data.recycle();
        }
    }

    void shutdown() {
        remote = null;
        binding = false;
        permissionRequested = false;
        try { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true); }
        catch (Throwable ignored) {}
        try { Shizuku.removeBinderReceivedListener(binderReceivedListener); }
        catch (Throwable ignored) {}
        try { Shizuku.removeBinderDeadListener(binderDeadListener); }
        catch (Throwable ignored) {}
        try { Shizuku.removeRequestPermissionResultListener(permissionListener); }
        catch (Throwable ignored) {}
    }

    private void handleBinderReceived() {
        if (isReady()) return;
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindUserService();
        } catch (Throwable error) {
            android.util.Log.w("FreezeAnalogInput", "Shizuku binder received but is not ready", error);
        }
    }

    private synchronized void bindUserService() {
        if (isReady() || binding) return;
        try {
            if (!Shizuku.pingBinder()) return;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
            binding = true;
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
        } catch (Throwable error) {
            binding = false;
            remote = null;
            notifyReady(false);
            android.util.Log.e("FreezeAnalogInput", "Cannot bind privileged input service", error);
        }
    }

    private void notifyReady(boolean ready) {
        if (listener != null) listener.onInputBridgeReadyChanged(ready);
    }
}
