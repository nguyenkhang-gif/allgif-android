package com.allgif.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private static final int REQ_OVERLAY     = 1001;
    private static final int REQ_ALL_FILES   = 1002;
    private boolean bubbleStartAttempted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(FileAccessPlugin.class);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            float density = getResources().getDisplayMetrics().density;
            int top    = Math.round(bars.top    / density);
            int bottom = Math.round(bars.bottom / density);
            String js = String.format(
                "document.documentElement.style.setProperty('--safe-top','%dpx');" +
                "document.documentElement.style.setProperty('--safe-bottom','%dpx');",
                top, bottom
            );
            runOnUiThread(() -> {
                if (getBridge() != null && getBridge().getWebView() != null) {
                    getBridge().getWebView().evaluateJavascript(js, null);
                }
            });
            return windowInsets;
        });

        checkPermissionsAndStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Only re-check if the permission flow is still in progress (not yet attempted)
        if (!bubbleStartAttempted && Settings.canDrawOverlays(this) && hasAllFilesPermission()) {
            startBubbleService();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // After returning from any settings screen, re-run the check chain
        checkPermissionsAndStart();
    }

    // ── Permission chain ──────────────────────────────────────────────────────

    private void checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            showOverlayDialog();
            return;
        }
        if (!hasAllFilesPermission()) {
            showAllFilesDialog();
            return;
        }
        startBubbleService();
    }

    private boolean hasAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        // Below Android 11 READ_EXTERNAL_STORAGE is enough (granted via manifest)
        return true;
    }

    private void showOverlayDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Permission needed (1/2)")
            .setMessage("AllGif needs \"Display over other apps\" to show the floating bubble.")
            .setPositiveButton("Grant", (d, w) -> {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_OVERLAY);
            })
            .setNegativeButton("Not now", null)
            .show();
    }

    private void showAllFilesDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Permission needed (2/2)")
            .setMessage("AllGif needs \"All files access\" to browse files on your device.")
            .setPositiveButton("Grant", (d, w) -> {
                Intent i;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                } else {
                    i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                }
                startActivityForResult(i, REQ_ALL_FILES);
            })
            .setNegativeButton("Not now", null)
            .show();
    }

    private void startBubbleService() {
        if (bubbleStartAttempted) return;
        bubbleStartAttempted = true;
        if (!FloatingBubbleService.isRunning) {
            startForegroundService(new Intent(this, FloatingBubbleService.class));
        }
    }
}
