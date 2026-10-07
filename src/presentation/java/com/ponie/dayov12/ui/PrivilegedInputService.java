package com.ponie.dayov12.ui;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.view.MotionEvent;
import java.lang.reflect.Method;

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

    public PrivilegedInputService() {
        injector = createInjector();
    }

    /** Shizuku versions that prefer a Context constructor can use this overload. */
    public PrivilegedInputService(Context ignored) {
        this();
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            if (reply != null) reply.writeString(DESCRIPTOR);
            return true;
        }
        if (code == TRANSACTION_PROBE) {
            data.enforceInterface(DESCRIPTOR);
            int capability = probeCapability();
            if (reply != null) {
                reply.writeNoException();
                reply.writeInt(capability);
            }
            return true;
        }
        if (code == TRANSACTION_TOUCH) {
            data.enforceInterface(DESCRIPTOR);
            int action = data.readInt();
            data.readLong(); // downTime is kept in the app state machine; Nubia path uses action/x/y.
            data.readLong(); // eventTime
            float x = data.readFloat();
            float y = data.readFloat();
            inject(action, x, y);
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    private int probeCapability() {
        if (Process.myUid() != SHELL_UID) return 0;
        TouchInjector current = injector;
        if (current == null) {
            current = createInjector();
            injector = current;
        }
        return current == null ? 0 : current.kind();
    }

    private void inject(int androidAction, float x, float y) {
        if (Process.myUid() != SHELL_UID) {
            injector = null;
            return;
        }
        int vendorAction = toNubiaAction(androidAction);
        if (vendorAction < 0 || !Float.isFinite(x) || !Float.isFinite(y)) return;

        TouchInjector current = injector;
        if (current == null) current = createInjector();
        if (current == null) return;

        try {
            current.send(vendorAction, Math.round(x), Math.round(y));
            injector = current;
            return;
        } catch (Throwable directFailure) {
            android.util.Log.w("FoxShizukuInput", "Primary Nubia input path failed", directFailure);
        }

        // Direct transaction numbers are vendor implementation details. If a ROM build
        // changes tx=126, retry the same event through the vendor reflection method.
        TouchInjector fallback = ReflectionNubiaInjector.create();
        if (fallback != null && fallback.getClass() != current.getClass()) {
            try {
                fallback.send(vendorAction, Math.round(x), Math.round(y));
                injector = fallback;
                return;
            } catch (Throwable reflectionFailure) {
                android.util.Log.e("FoxShizukuInput", "Nubia reflection path failed", reflectionFailure);
            }
        }
        injector = null;
    }

    private static int toNubiaAction(int androidAction) {
        switch (androidAction) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                return 0;
            case MotionEvent.ACTION_MOVE:
                return 1;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                return 2;
            default:
                return -1;
        }
    }

    private static TouchInjector createInjector() {
        if (Process.myUid() != SHELL_UID) {
            android.util.Log.e("FoxShizukuInput", "UserService uid is not shell: " + Process.myUid());
            return null;
        }
        TouchInjector direct = DirectBinderInjector.create();
        if (direct != null) return direct;
        return ReflectionNubiaInjector.create();
    }

    private interface TouchInjector {
        /** 2 = direct vendor Binder, 1 = reflected vendor method. */
        int kind();
        void send(int vendorAction, int x, int y) throws Exception;
    }

    /** Fast REDMAGIC/Nubia path: IInputManager transaction 126. */
    private static final class DirectBinderInjector implements TouchInjector {
        private final IBinder input;

        private DirectBinderInjector(IBinder input) {
            this.input = input;
        }

        static TouchInjector create() {
            try {
                Class<?> serviceManager = Class.forName("android.os.ServiceManager");
                Method getService = serviceManager.getDeclaredMethod("getService", String.class);
                getService.setAccessible(true);
                Object value = getService.invoke(null, "input");
                if (!(value instanceof IBinder)) return null;
                IBinder binder = (IBinder) value;
                if (!INPUT_DESCRIPTOR.equals(binder.getInterfaceDescriptor())) return null;
                return new DirectBinderInjector(binder);
            } catch (Throwable error) {
                android.util.Log.w("FoxShizukuInput", "Direct IInputManager path unavailable", error);
                return null;
            }
        }

        @Override public int kind() { return 2; }

        @Override public void send(int vendorAction, int x, int y) throws Exception {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(INPUT_DESCRIPTOR);
                data.writeInt(NUBIA_KEY_CODE);
                data.writeInt(vendorAction);
                data.writeInt(NUBIA_MODE);
                data.writeInt(NUBIA_GAMEPAD_ID);
                data.writeInt(x);
                data.writeInt(y);
                boolean handled = input.transact(
                        NUBIA_TRANSACTION_VIRTUAL_TOUCH, data, reply, 0);
                if (!handled) throw new UnsupportedOperationException(
                        "IInputManager transaction 126 not handled");
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }

    /** Cached fallback for Nubia's hidden InputManager.virtualTouchEvent method. */
    private static final class ReflectionNubiaInjector implements TouchInjector {
        private final Object inputManager;
        private final Method method;

        private ReflectionNubiaInjector(Object inputManager, Method method) {
            this.inputManager = inputManager;
            this.method = method;
        }

        static TouchInjector create() {
            try {
                Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
                Method getInstance = inputManagerClass.getDeclaredMethod("getInstance");
                getInstance.setAccessible(true);
                Object inputManager = getInstance.invoke(null);
                if (inputManager == null) return null;
                Method eventMethod = inputManagerClass.getDeclaredMethod(
                        "virtualTouchEvent",
                        int.class, int.class, int.class,
                        int.class, int.class, int.class);
                eventMethod.setAccessible(true);
                return new ReflectionNubiaInjector(inputManager, eventMethod);
            } catch (Throwable error) {
                android.util.Log.w("FoxShizukuInput", "Nubia virtualTouchEvent unavailable", error);
                return null;
            }
        }

        @Override public int kind() { return 1; }

        @Override public void send(int vendorAction, int x, int y) throws Exception {
            method.invoke(inputManager,
                    NUBIA_KEY_CODE,
                    vendorAction,
                    NUBIA_MODE,
                    NUBIA_GAMEPAD_ID,
                    x,
                    y);
        }
    }
}
