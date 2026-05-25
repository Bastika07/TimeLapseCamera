/*
 * Spartan Time Lapse Recorder - Minimalistic android time lapse recording app
 * Copyright (C) 2014  Andreas Rohner
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package at.andreasrohner.spartantimelapserec;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Handles runtime storage permission requests for all Android versions:
 *
 *   API 30+ (Android 11+) — MANAGE_EXTERNAL_STORAGE via system settings screen
 *   API 23–29             — WRITE_EXTERNAL_STORAGE via standard requestPermissions()
 *   API < 23              — permission granted at install time, nothing to do
 *
 * Usage in your Activity:
 *
 *   1. Before starting a recording:
 *        if (!StoragePermissionHelper.isGranted()) {
 *            StoragePermissionHelper.requestIfNeeded(this);
 *            return; // wait for result, then retry
 *        }
 *
 *   2. In onRequestPermissionsResult():
 *        StoragePermissionHelper.onRequestPermissionsResult(requestCode, grantResults, this);
 *
 *   3. In onActivityResult()  (for Android 11+ settings redirect):
 *        StoragePermissionHelper.onActivityResult(requestCode);
 */
public class StoragePermissionHelper {

    private static final String TAG = "StoragePermHelper";

    /** Request code for WRITE_EXTERNAL_STORAGE (API 23–29). */
    public static final int REQUEST_WRITE_STORAGE = 1001;

    /** Request code used when returning from the MANAGE_ALL_FILES settings screen (API 30+). */
    public static final int REQUEST_MANAGE_STORAGE = 1002;

    /**
     * Returns true if the app currently has sufficient storage access to write
     * to shared external storage (e.g. /storage/emulated/0/Pictures/).
     * Can be called from any Context — Activity, Service, or Application.
     */
    public static boolean isGranted(android.content.Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** Convenience overload — falls back to cached app context if available. */
    public static boolean isGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return isGranted(getAppContext());
    }

    /**
     * Checks whether storage permission is needed and, if so, shows a rationale
     * dialog before redirecting the user to the appropriate permission UI.
     *
     * Call this from your Activity before starting a recording.
     */
    public static void requestIfNeeded(final Activity activity) {
        sAppContext = activity.getApplicationContext();
        if (isGranted()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: can't request at runtime — must send user to Settings
            new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.perm_storage_title))
                    .setMessage(activity.getString(R.string.perm_storage_message_r))
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        try {
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                        } catch (Exception e) {
                            // Fallback: open the general all-files-access list
                            Log.w(TAG, "Specific intent failed, opening general settings", e);
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            activity.startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();

        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6–10: standard runtime permission request
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                new AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.perm_storage_title))
                        .setMessage(activity.getString(R.string.perm_storage_message))
                        .setPositiveButton(android.R.string.ok, (dialog, which) ->
                                ActivityCompat.requestPermissions(
                                        activity,
                                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                        REQUEST_WRITE_STORAGE))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } else {
                ActivityCompat.requestPermissions(
                        activity,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_WRITE_STORAGE);
            }
        }
    }

    /**
     * Call this from your Activity's onRequestPermissionsResult().
     * Returns true if permission was granted.
     */
    public static boolean onRequestPermissionsResult(int requestCode,
                                                     int[] grantResults,
                                                     Activity activity) {
        if (requestCode != REQUEST_WRITE_STORAGE) return false;

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (!granted) {
            Log.w(TAG, "WRITE_EXTERNAL_STORAGE denied — recordings will use app-private storage");
            new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.perm_storage_title))
                    .setMessage(activity.getString(R.string.perm_storage_denied_message))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
        return granted;
    }

    /**
     * Call this from your Activity's onActivityResult() to log the outcome
     * when returning from the MANAGE_ALL_FILES settings screen (Android 11+).
     * Returns true if permission is now granted.
     */
    public static boolean onActivityResult(int requestCode) {
        if (requestCode != REQUEST_MANAGE_STORAGE) return false;

        boolean granted = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            granted = Environment.isExternalStorageManager();
        }
        Log.i(TAG, "MANAGE_EXTERNAL_STORAGE after settings: " + (granted ? "granted" : "denied"));
        return granted;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    // Lazily resolved application context — set once via init() or grabbed from
    // the first Activity that calls requestIfNeeded().
    private static android.content.Context sAppContext;

    /** Optional explicit init — call from Application.onCreate() if you like. */
    public static void init(android.content.Context context) {
        sAppContext = context.getApplicationContext();
    }

    private static android.content.Context getAppContext() {
        if (sAppContext == null) {
            throw new IllegalStateException(
                    "StoragePermissionHelper: call init(context) first, "
                            + "or use requestIfNeeded(activity) which sets the context automatically.");
        }
        return sAppContext;
    }
}
