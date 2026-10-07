package com.ponie.dayov12.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import rikka.shizuku.Shizuku;

/** App-side owner of the Shizuku input-monitor UserService. */
final class FreezeInputBridge {
    private static final String TAG = "FreezeInputBridge";
    private static final int PERMISSION_REQUEST = 47131;

    private final FreezeAnalogManager owner;
    private final Shizuku.UserServiceArgs serviceArgs;
    private boolean wanted;
    private boolean binding;
    private boolean permissionPending;
    private boolean ready;
    private IBinder remote;

    private final Binder callback = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == FreezeInputUserService.CALLBACK_STATUS) {
                boolean next = data.readInt() != 0;
                synchronized (FreezeInputBridge.this) {
                    ready = wanted && next;
                }
                owner.onInputBridgeReady(wanted && next);
                return true;
            }
            if (code == FreezeInputUserService.CALLBACK_MOTION) {
                int action = data.readInt();
                int actionIndex = data.readInt();
                int count = data.readInt();
                long eventTime = data.readLong();
                int[] ids = new int[count];
                float[] xs = new float[count];
                float[] ys = new float[count];
                for (int i = 0; i < count; i++) {
                    ids[i] = data.readInt();
                    xs[i] = data.readFloat();
                    ys[i] = data.readFloat();
                }
                if (wanted && ready) owner.onGlobalMotion(action, actionIndex, ids, xs, ys, eventTime);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (FreezeInputBridge.this) {
                binding = false;
                remote = service;
            }
            if (!wanted) {
                stopRemoteAndUnbind();
                return;
            }
            Parcel data = Parcel.obtain();
            try {
                data.writeStrongBinder(callback);
                service.transact(FreezeInputUserService.TRANSACTION_SET_CALLBACK,
                        data, null, IBinder.FLAG_ONEWAY);
            } catch (Throwable error) {
                Log.e(TAG, "Unable to register pointer callback", error);
                synchronized (FreezeInputBridge.this) {
                    remote = null;
                    ready = false;
                }
                owner.onInputBridgeReady(false);
            } finally {
                data.recycle();
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (FreezeInputBridge.this) {
                binding = false;
                remote = null;
                ready = false;
            }
            owner.onInputBridgeReady(false);
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceived = this::ensureBinding;
    private final Shizuku.OnBinderDeadListener binderDead = () -> {
        synchronized (FreezeInputBridge.this) {
            binding = false;
            remote = null;
            ready = false;
            permissionPending = false;
        }
        owner.onInputBridgeReady(false);
    };
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (requestCode, grantResult) -> {
        if (requestCode != PERMISSION_REQUEST) return;
        synchronized (FreezeInputBridge.this) {
            permissionPending = false;
        }
        if (grantResult == PackageManager.PERMISSION_GRANTED) ensureBinding();
        else owner.onInputBridgeReady(false);
    };

    FreezeInputBridge(Context context, FreezeAnalogManager owner) {
        this.owner = owner;
        this.serviceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(context.getPackageName(), FreezeInputUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("freeze_input")
                .tag("fox-freeze-input-v1")
                .version(1);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
    }

    synchronized void start() {
        wanted = true;
        ensureBinding();
    }

    synchronized void stop() {
        wanted = false;
        permissionPending = false;
        ready = false;
        owner.onInputBridgeReady(false);
        stopRemoteAndUnbind();
    }

    synchronized boolean isReady() {
        return ready;
    }

    synchronized void shutdown() {
        wanted = false;
        permissionPending = false;
        ready = false;
        stopRemoteAndUnbind();
        try { Shizuku.removeBinderReceivedListener(binderReceived); } catch (Throwable ignored) {}
        try { Shizuku.removeBinderDeadListener(binderDead); } catch (Throwable ignored) {}
        try { Shizuku.removeRequestPermissionResultListener(permissionResult); } catch (Throwable ignored) {}
    }

    private synchronized void ensureBinding() {
        if (!wanted || binding || remote != null) return;
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                owner.onInputBridgeReady(false);
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (!permissionPending) {
                    permissionPending = true;
                    Shizuku.requestPermission(PERMISSION_REQUEST);
                }
                return;
            }
            binding = true;
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Throwable error) {
            binding = false;
            ready = false;
            Log.e(TAG, "Unable to start input monitor", error);
            owner.onInputBridgeReady(false);
        }
    }

    private synchronized void stopRemoteAndUnbind() {
        IBinder service = remote;
        remote = null;
        binding = false;
        if (service != null) {
            Parcel data = Parcel.obtain();
            try {
                service.transact(FreezeInputUserService.TRANSACTION_STOP,
                        data, null, IBinder.FLAG_ONEWAY);
            } catch (RemoteException ignored) {
            } finally {
                data.recycle();
            }
        }
        try {
            if (Shizuku.pingBinder()) Shizuku.unbindUserService(serviceArgs, connection, true);
        } catch (Throwable ignored) {
        }
    }
}
