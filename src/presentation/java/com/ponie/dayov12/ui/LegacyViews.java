package com.ponie.dayov12.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Small, fail-fast adapter for V12's original controls. All original listeners stay attached. */
public final class LegacyViews {
    private final Activity activity;
    public LegacyViews(Activity activity) { this.activity = activity; }
    public <T> T field(String name, Class<T> type) {
        try {
            Field f = activity.getClass().getDeclaredField(name); f.setAccessible(true);
            Object value = f.get(activity);
            if (value == null) throw new IllegalStateException("Missing view: " + name);
            return type.cast(value);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("V12 view contract: " + name, e); }
    }
    public void pauseDecoration() {
        try {
            Method m = activity.getClass().getDeclaredMethod("pauseAmbientAnimations");
            m.setAccessible(true); m.invoke(activity);
        } catch (ReflectiveOperationException e) { android.util.Log.w("FoxUI", "Cannot pause decoration", e); }
    }
    public static void detach(View view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }
    public static boolean contains(View root, View target) {
        if (root == target) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i=0; i<group.getChildCount(); i++) if (contains(group.getChildAt(i), target)) return true;
        }
        return false;
    }
    public static View topChild(ViewGroup root, View target) {
        for (int i=0; i<root.getChildCount(); i++) if (contains(root.getChildAt(i), target)) return root.getChildAt(i);
        throw new IllegalStateException("V12 control not attached");
    }
}
