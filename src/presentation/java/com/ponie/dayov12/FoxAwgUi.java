package com.ponie.dayov12;

import android.app.Activity;
import android.content.Intent;
import com.ponie.dayov12.ui.ConfigImportController;
import com.ponie.dayov12.ui.FoxDashboard;

/** Stable entry points called by MainActivity's audited Smali wrappers. */
public final class FoxAwgUi {
    private FoxAwgUi() {}
    public static void attach(Activity activity) {
        new FoxDashboard(activity).install();
    }
    public static boolean result(Activity activity, int request, int result, Intent data) {
        return ConfigImportController.result(activity, request, result, data);
    }
}
