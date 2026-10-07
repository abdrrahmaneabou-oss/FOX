package com.ponie.dayov12;

public final class FoxNative {
    static { System.loadLibrary("fox-awg"); }
    public static native int turnOn(int fd, int mtu, String settings);
    public static native void turnOff();
    public static native int socket(int family);
    public static native long handshake();
}
