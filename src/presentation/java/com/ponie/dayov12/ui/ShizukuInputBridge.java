package com.ponie.dayov12.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import rikka.shizuku.Shizuku;

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

    private final Listener listener;
    private final Shizuku.UserServiceArgs args;
    private volatile IBinder remote;
    private volatile boolean binding;
    private volatile boolean permissionRequested;

    private final ServiceConnection connection = new ServiceConnection() {
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

    private final Shizuku.OnBinderReceivedListener binderListener = this::onBinderReceived;
    private final Shizuku.OnBinderDeadListener deadListener = () -> {
        binding = false;
        remote = null;
        notifyReady(false);
    };
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (code, result) -> {
        if (code != REQUEST_CODE) return;
        permissionRequested = false;
        if (result == PackageManager.PERMISSION_GRANTED) bind();
        else if (listener != null) listener.onInputBridgePermissionDenied();
    };

    ShizukuInputBridge(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        this.listener = listener;
        args = new Shizuku.UserServiceArgs(
                new ComponentName(app.getPackageName(), PrivilegedInputService.class.getName()))
                .daemon(false)
                .processNameSuffix("freeze_input")
                .debuggable(false)
                .version(1);
        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addBinderDeadListener(deadListener);
        Shizuku.addRequestPermissionResultListener(permissionListener);
    }

    boolean isReady() {
        IBinder b = remote;
        return b != null && b.pingBinder();
    }

    int ensureReady() {
        if (isReady()) return READY;
        try {
            if (!Shizuku.pingBinder()) return UNAVAILABLE;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bind();
                return isReady() ? READY : WAITING;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) return DENIED;
            if (!permissionRequested) {
                permissionRequested = true;
                Shizuku.requestPermission(REQUEST_CODE);
            }
            return WAITING;
        } catch (Throwable error) {
            android.util.Log.e("FreezeAnalogInput", "Shizuku setup failed", error);
            return UNAVAILABLE;
        }
    }

    boolean sendTouch(int action, long downTime, long eventTime, float x, float y) {
        IBinder b = remote;
        if (b == null || !b.pingBinder()) return false;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(PrivilegedInputService.DESCRIPTOR);
            data.writeInt(action);
            data.writeLong(downTime);
            data.writeLong(eventTime);
            data.writeFloat(x);
            data.writeFloat(y);
            return b.transact(PrivilegedInputService.TRANSACTION_TOUCH, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException e) {
            remote = null;
            binding = false;
            notifyReady(false);
            return false;
        } finally {
            data.recycle();
        }
    }

    void shutdown() {
        try { Shizuku.unbindUserService(args, connection, true); } catch (Throwable ignored) {}
        try { Shizuku.removeBinderReceivedListener(binderListener); } catch (Throwable ignored) {}
        try { Shizuku.removeBinderDeadListener(deadListener); } catch (Throwable ignored) {}
        try { Shizuku.removeRequestPermissionResultListener(permissionListener); } catch (Throwable ignored) {}
        remote = null;
        binding = false;
    }

    private void onBinderReceived() {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind();
        } catch (Throwable ignored) {}
    }

    private synchronized void bind() {
        if (binding || isReady()) return;
        try {
            if (!Shizuku.pingBinder()) return;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
            binding = true;
            Shizuku.bindUserService(args, connection);
        } catch (Throwable error) {
            binding = false;
            remote = null;
            notifyReady(false);
        }
    }

    private void notifyReady(boolean ready) {
        if (listener != null) listener.onInputBridgeReadyChanged(ready);
    }
}
