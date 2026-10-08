package com.ponie.dayov12.ui;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import rikka.shizuku.ShizukuProvider;

/**
 * Shizuku UserService side of the Freeze analog touch relay.
 *
 * The service runs under the Shizuku shell uid and uses the REDMAGIC/Nubia
 * virtualTouchEvent path that is already proven on the target device family.
 * FOX keeps the normal app process unprivileged; only this tiny Binder service
 * talks to IInputManager.
 */
public final class PrivilegedInputService extends Binder {
    static final String DESCRIPTOR = "com.ponie.dayov12.ui.PrivilegedInputService";
    static final int TRANSACTION_TOUCH = IBinder.FIRST_CALL_TRANSACTION;
    static final int TRANSACTION_PROBE = IBinder.FIRST_CALL_TRANSACTION + 1;

    private static final int SHELL_UID = 2000;
    private static final int NUBIA_TRANSACTION_VIRTUAL_TOUCH = 126;
    private static final int NUBIA_KEY_CODE = -4;
    private static final int NUBIA_MODE = 1;
    private static final int NUBIA_GAMEPAD_ID = -2;
    private static final String INPUT_DESCRIPTOR = "android.hardware.input.IInputManager";

    private volatile TouchInjector injector;

    /**
     * Android creates content providers before the first Activity. Shizuku's stock
     * provider also probes Sui from onCreate(), which is unnecessary for FOX and
     * can fail on vendor Android builds before our UI has a chance to recover.
     * Keep the normal Shizuku provider protocol, but disable Sui probing and make
     * provider startup non-fatal. Binder delivery through call() remains inherited.
     */
    public static final class SafeShizukuProvider extends ShizukuProvider {
        @Override public boolean onCreate() {
            try {
                ShizukuProvider.disableAutomaticSuiInitialization();
                return super.onCreate();
            } catch (Throwable error) {
                Log.e("FoxShizukuProvider", "Shizuku provider startup failed safely", error);
                return true;
            }
        }
    }

    public PrivilegedInputService() {
        injector = createInjector();
    }

    /** Shizuku versions that prefer a Context constructor can use this overload. */
    public PrivilegedInputService(Context ignored) {
        this();
    }

    private interface TouchInjector {
        boolean isAvailable();
        boolean inject(MotionEvent event);
        int kind();
    }

    private static final class NubiaVirtualTouchInjector implements TouchInjector {
        private final IBinder inputManager;

        NubiaVirtualTouchInjector() {
            inputManager = getInputManagerBinder();
        }

        @Override public boolean isAvailable() {
            return inputManager != null && inputManager.pingBinder();
        }

        @Override public int kind() { return 1; }

        @Override public boolean inject(MotionEvent event) {
            if (!isAvailable()) return false;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(INPUT_DESCRIPTOR);
                data.writeTypedObject(event, 0);
                data.writeInt(NUBIA_KEY_CODE);
                data.writeInt(NUBIA_MODE);
                data.writeInt(NUBIA_GAMEPAD_ID);
                boolean handled = inputManager.transact(
                        NUBIA_TRANSACTION_VIRTUAL_TOUCH,
                        data,
                        reply,
                        0);
                if (!handled) return false;
                reply.readException();
                return true;
            } catch (Throwable error) {
                Log.w("FoxShizukuInput", "Nubia virtual touch failed", error);
                return false;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }

    private static final class ReflectionInjector implements TouchInjector {
        private Object inputManager;
        private Method inject;

        ReflectionInjector() {
            try {
                Class<?> global = Class.forName("android.hardware.input.InputManagerGlobal");
                Method get = global.getDeclaredMethod("getInstance");
                get.setAccessible(true);
                inputManager = get.invoke(null);
                inject = global.getDeclaredMethod("injectInputEvent", android.view.InputEvent.class, int.class);
                inject.setAccessible(true);
            } catch (Throwable ignored) {
                inputManager = null;
                inject = null;
            }
        }

        @Override public boolean isAvailable() {
            return inputManager != null && inject != null;
        }

        @Override public int kind() { return 2; }

        @Override public boolean inject(MotionEvent event) {
            if (!isAvailable()) return false;
            try {
                Object result = inject.invoke(inputManager, event, 0);
                return !(result instanceof Boolean) || (Boolean) result;
            } catch (Throwable error) {
                Log.w("FoxShizukuInput", "Reflection injection failed", error);
                return false;
            }
        }
    }

    private static final class DirectBinderInjector implements TouchInjector {
        private final IBinder inputManager;
        private final int transaction;

        DirectBinderInjector() {
            inputManager = getInputManagerBinder();
            transaction = findInjectTransaction();
        }

        @Override public boolean isAvailable() {
            return inputManager != null && inputManager.pingBinder() && transaction > 0;
        }

        @Override public int kind() { return 3; }

        @Override public boolean inject(MotionEvent event) {
            if (!isAvailable()) return false;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(INPUT_DESCRIPTOR);
                data.writeTypedObject(event, 0);
                data.writeInt(0);
                boolean handled = inputManager.transact(transaction, data, reply, 0);
                if (!handled) return false;
                reply.readException();
                return true;
            } catch (Throwable error) {
                Log.w("FoxShizukuInput", "Direct binder injection failed", error);
                return false;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }

    private static IBinder getInputManagerBinder() {
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            Method getService = manager.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            return (IBinder) getService.invoke(null, "input");
        } catch (Throwable error) {
            Log.w("FoxShizukuInput", "Cannot obtain input service", error);
            return null;
        }
    }

    private static int findInjectTransaction() {
        try {
            Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
            java.lang.reflect.Field field = stub.getDeclaredField("TRANSACTION_injectInputEvent");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private TouchInjector createInjector() {
        TouchInjector nubia = new NubiaVirtualTouchInjector();
        if (nubia.isAvailable()) return nubia;

        TouchInjector direct = new DirectBinderInjector();
        if (direct.isAvailable()) return direct;

        TouchInjector reflection = new ReflectionInjector();
        if (reflection.isAvailable()) return reflection;

        return new TouchInjector() {
            @Override public boolean isAvailable() { return false; }
            @Override public boolean inject(MotionEvent event) { return false; }
            @Override public int kind() { return 0; }
        };
    }

    private boolean inject(int action, long downTime, long eventTime, float x, float y) {
        TouchInjector current = injector;
        if (current == null || !current.isAvailable()) {
            current = createInjector();
            injector = current;
        }
        if (!current.isAvailable()) return false;

        int normalizedAction = action == MotionEvent.ACTION_CANCEL
                ? MotionEvent.ACTION_UP : action;
        MotionEvent event = MotionEvent.obtain(
                downTime,
                eventTime,
                normalizedAction,
                x,
                y,
                0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {
            return current.inject(event);
        } finally {
            event.recycle();
        }
    }

    private int probe() {
        TouchInjector current = injector;
        if (current == null || !current.isAvailable()) {
            current = createInjector();
            injector = current;
        }
        return current.kind();
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == TRANSACTION_TOUCH) {
            data.enforceInterface(DESCRIPTOR);
            int action = data.readInt();
            long downTime = data.readLong();
            long eventTime = data.readLong();
            float x = data.readFloat();
            float y = data.readFloat();
            boolean ok = inject(action, downTime, eventTime, x, y);
            if (reply != null) reply.writeInt(ok ? 1 : 0);
            return true;
        }
        if (code == TRANSACTION_PROBE) {
            data.enforceInterface(DESCRIPTOR);
            if (reply != null) {
                reply.writeNoException();
                reply.writeInt(probe());
            }
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
