package com.ponie.dayov12.ui;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;

/**
 * Shizuku UserService that observes the physical pointer stream without becoming
 * the touch target. The original physical touch keeps its normal Android target;
 * only a compact copy of pointer coordinates is forwarded to the app process.
 */
public final class FreezeInputUserService extends Binder {
    static final int TRANSACTION_SET_CALLBACK = 1;
    static final int TRANSACTION_STOP = 2;
    static final int CALLBACK_MOTION = 1;
    static final int CALLBACK_STATUS = 2;
    private static final int SHIZUKU_DESTROY_TRANSACTION = 16777115;
    private static final String TAG = "FreezeInputService";

    private final Object lock = new Object();
    private final Context context;
    private IBinder callback;
    private HandlerThread inputThread;
    private Handler inputHandler;
    private InputMonitor inputMonitor;
    private InputEventReceiver inputReceiver;

    public FreezeInputUserService() {
        this(null);
    }

    /** Shizuku v13+ supplies a package Context here. */
    public FreezeInputUserService(Context context) {
        this.context = context;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == TRANSACTION_SET_CALLBACK) {
            IBinder next = data.readStrongBinder();
            synchronized (lock) {
                callback = next;
            }
            if (next != null) startMonitor();
            else stopMonitor();
            return true;
        }
        if (code == TRANSACTION_STOP) {
            synchronized (lock) {
                callback = null;
            }
            stopMonitor();
            return true;
        }
        if (code == SHIZUKU_DESTROY_TRANSACTION) {
            synchronized (lock) {
                callback = null;
            }
            stopMonitor();
            System.exit(0);
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    private void startMonitor() {
        synchronized (lock) {
            if (inputThread != null) return;
            inputThread = new HandlerThread("fox-freeze-input", -4);
            inputThread.start();
            inputHandler = new Handler(inputThread.getLooper());
            inputHandler.post(this::createMonitorOnInputThread);
        }
    }

    private void createMonitorOnInputThread() {
        try {
            synchronized (lock) {
                if (callback == null) return;
            }

            InputManager manager = null;
            if (context != null) {
                try {
                    manager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
                } catch (Throwable ignored) {
                }
            }
            if (manager == null) manager = InputManager.getInstance();
            if (manager == null) throw new IllegalStateException("InputManager unavailable");

            final InputMonitor monitor = manager.monitorGestureInput("FOX Freeze Analog", Display.DEFAULT_DISPLAY);
            final InputEventReceiver receiver = new InputEventReceiver(monitor.getInputChannel(), Looper.myLooper()) {
                @Override public void onInputEvent(InputEvent event) {
                    try {
                        if (event instanceof MotionEvent) sendMotion((MotionEvent) event);
                    } catch (Throwable error) {
                        Log.e(TAG, "Pointer forwarding failed", error);
                    } finally {
                        finishInputEvent(event, false);
                    }
                }
            };
            synchronized (lock) {
                if (callback == null) {
                    receiver.dispose();
                    monitor.dispose();
                    return;
                }
                inputMonitor = monitor;
                inputReceiver = receiver;
            }
            sendStatus(true);
        } catch (Throwable error) {
            Log.e(TAG, "Unable to create system input monitor", error);
            sendStatus(false);
            stopMonitor();
        }
    }

    private void sendMotion(MotionEvent event) {
        final IBinder target;
        synchronized (lock) {
            target = callback;
        }
        if (target == null) return;

        Parcel out = Parcel.obtain();
        try {
            int count = event.getPointerCount();
            out.writeInt(event.getActionMasked());
            out.writeInt(event.getActionIndex());
            out.writeInt(count);
            out.writeLong(event.getEventTime());
            for (int i = 0; i < count; i++) {
                out.writeInt(event.getPointerId(i));
                out.writeFloat(event.getX(i));
                out.writeFloat(event.getY(i));
            }
            target.transact(CALLBACK_MOTION, out, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException error) {
            synchronized (lock) {
                if (callback == target) callback = null;
            }
            stopMonitor();
        } finally {
            out.recycle();
        }
    }

    private void sendStatus(boolean ready) {
        final IBinder target;
        synchronized (lock) {
            target = callback;
        }
        if (target == null) return;
        Parcel out = Parcel.obtain();
        try {
            out.writeInt(ready ? 1 : 0);
            target.transact(CALLBACK_STATUS, out, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
        } finally {
            out.recycle();
        }
    }

    private void stopMonitor() {
        final Handler handler;
        final HandlerThread thread;
        final InputEventReceiver receiver;
        final InputMonitor monitor;
        synchronized (lock) {
            handler = inputHandler;
            thread = inputThread;
            receiver = inputReceiver;
            monitor = inputMonitor;
            inputHandler = null;
            inputThread = null;
            inputReceiver = null;
            inputMonitor = null;
        }
        if (thread == null) return;

        Runnable cleanup = () -> {
            try {
                if (receiver != null) receiver.dispose();
            } catch (Throwable ignored) {
            }
            try {
                if (monitor != null) monitor.dispose();
            } catch (Throwable ignored) {
            }
            try {
                thread.quitSafely();
            } catch (Throwable ignored) {
            }
        };
        if (handler != null && Looper.myLooper() != handler.getLooper()) {
            try {
                if (handler.post(cleanup)) return;
            } catch (Throwable ignored) {
            }
        }
        cleanup.run();
    }
}
