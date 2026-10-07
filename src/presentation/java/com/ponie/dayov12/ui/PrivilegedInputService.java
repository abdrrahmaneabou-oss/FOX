package com.ponie.dayov12.ui;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/**
 * Shizuku UserService that runs with the user-authorized shell/root identity and
 * injects one touchscreen event at a time. It intentionally exposes only this
 * narrow operation to the FOX process.
 */
public final class PrivilegedInputService extends Binder {
    static final String DESCRIPTOR = "com.ponie.dayov12.ui.PrivilegedInputService";
    static final int TRANSACTION_INJECT = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_DESTROY = 16777115;
    private static final int INJECT_ASYNC = 0;

    private Object inputManager;
    private Method injectMethod;

    public PrivilegedInputService() {
        attachInterface(null, DESCRIPTOR);
    }

    // Shizuku v13 prefers a Context constructor when available.
    public PrivilegedInputService(Context ignored) {
        this();
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            if (reply != null) reply.writeString(DESCRIPTOR);
            return true;
        }
        if (code == TRANSACTION_INJECT) {
            data.enforceInterface(DESCRIPTOR);
            int action = data.readInt();
            long downTime = data.readLong();
            long eventTime = data.readLong();
            float rawX = data.readFloat();
            float rawY = data.readFloat();
            boolean result = inject(action, downTime, eventTime, rawX, rawY);
            if (reply != null) {
                reply.writeNoException();
                reply.writeInt(result ? 1 : 0);
            }
            return true;
        }
        if (code == TRANSACTION_DESTROY) {
            System.exit(0);
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    private synchronized boolean inject(int action, long downTime, long eventTime, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            ensureInputManager();
            Object result = injectMethod.invoke(inputManager, event, INJECT_ASYNC);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable error) {
            android.util.Log.e("FreezeAnalogInput", "Input injection failed", error);
            return false;
        } finally {
            event.recycle();
        }
    }

    private void ensureInputManager() throws Exception {
        if (inputManager != null && injectMethod != null) return;

        // Android 13+ uses InputManagerGlobal. Keep the older InputManager fallback
        // because FOX also supports devices whose framework still exposes that singleton.
        Throwable first = null;
        try {
            Class<?> type = Class.forName("android.hardware.input.InputManagerGlobal");
            Method getInstance = type.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object instance = getInstance.invoke(null);
            Method inject = type.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
            inject.setAccessible(true);
            inputManager = instance;
            injectMethod = inject;
            return;
        } catch (Throwable error) {
            first = error;
        }

        try {
            Class<?> type = Class.forName("android.hardware.input.InputManager");
            Method getInstance = type.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object instance = getInstance.invoke(null);
            Method inject = type.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
            inject.setAccessible(true);
            inputManager = instance;
            injectMethod = inject;
        } catch (Throwable second) {
            if (first != null) second.addSuppressed(first);
            if (second instanceof Exception) throw (Exception) second;
            throw new RuntimeException(second);
        }
    }
}
