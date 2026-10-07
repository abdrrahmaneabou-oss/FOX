package com.ponie.dayov12.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.ponie.dayov12.FoxAwgConfig;
import com.ponie.dayov12.FoxConfigStore;
import com.ponie.dayov12.MyVpnService;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Document picker and bounded encrypted import, independent of the dashboard layout. */
public final class ConfigImportController {
    public static final int REQUEST = 17439; // Preserve the existing Activity result contract.
    private static final AtomicBoolean IMPORTING = new AtomicBoolean();
    private ConfigImportController() {}
    public static void choose(Activity activity) {
        if (MyVpnService.isVpnRunning || IMPORTING.get()) {
            toast(activity, "Stop the VPN and wait for any import to finish first"); return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE);
        try { activity.startActivityForResult(intent, REQUEST); }
        catch (android.content.ActivityNotFoundException e) { toast(activity, "No document picker is available"); }
    }
    public static boolean result(Activity activity, int request, int result, Intent data) {
        if (request != REQUEST) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        if (MyVpnService.isVpnRunning) { toast(activity, "Stop the VPN before importing"); return true; }
        if (!IMPORTING.compareAndSet(false, true)) { toast(activity, "An import is already in progress"); return true; }
        final Context context = activity.getApplicationContext();
        final android.net.Uri uri = data.getData();
        final WeakReference<Activity> owner = new WeakReference<>(activity);
        new Thread(() -> {
            byte[] plaintext = null;
            String message;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("FILE_NOT_OPENED");
                plaintext = FoxAwgConfig.readLimited(in);
                if (MyVpnService.isVpnRunning) throw new IOException("VPN_STARTED");
                FoxConfigStore.save(context, plaintext);
                message = "AmneziaWG configuration imported securely";
            } catch (Exception e) {
                message = "Import failed. Check that the file is a supported AmneziaWG configuration and stop the VPN.";
            } finally {
                if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
                IMPORTING.set(false);
            }
            final String outcome = message;
            new Handler(Looper.getMainLooper()).post(() -> {
                Activity current = owner.get();
                if (current != null && !current.isFinishing() && !current.isDestroyed()) toast(current, outcome);
            });
        }, "AWG-Import").start();
        return true;
    }
    public static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
