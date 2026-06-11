package com.allgif.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.IntentFilter;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class FloatingBubbleService extends Service {

    private WindowManager wm;
    private View bubbleView;
    private WebView popupWebView;
    private View trashView;
    private WindowManager.LayoutParams bubbleParams;
    public static boolean isRunning = false;
    private boolean popupVisible = false;
    private ImageView bubbleImageView;
    private final BroadcastReceiver iconReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, android.content.Intent intent) {
            reloadBubbleIcon();
        }
    };
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgThread = Executors.newSingleThreadExecutor();
    private Bitmap cachedCircularBitmap;

    private int initX, initY;
    private float initTouchX, initTouchY;
    private boolean moved;
    private boolean wasPopupVisible;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        startForegroundNotification();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        createBubble();
        createPopupWebView();
        registerReceiver(iconReceiver, new IntentFilter("com.allgif.UPDATE_BUBBLE_ICON"),
            Context.RECEIVER_NOT_EXPORTED);
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void startForegroundNotification() {
        String ch = "allgif_bubble";
        NotificationChannel channel = new NotificationChannel(
            ch, "Floating Bubble", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, ch)
            .setContentTitle("AllGif").setContentText("Floating bubble active")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentIntent(pi).setSilent(true).build();

        startForeground(1, n);
    }

    // ── Bubble ────────────────────────────────────────────────────────────────

    private void createBubble() {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setElevation(dp(8));

        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(dp(3), Color.WHITE);
        ring.setColor(Color.TRANSPARENT);
        iv.setForeground(ring);
        bubbleView = iv;
        bubbleImageView = iv;

        bgThread.execute(() -> {
            Bitmap bm = loadBubbleBitmap();
            cachedCircularBitmap = bm;
            mainHandler.post(() -> iv.setImageBitmap(bm));
        });

        bubbleParams = new WindowManager.LayoutParams(
            dp(64), dp(64),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = dp(8);
        bubbleParams.y = dp(200);

        bubbleView.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    wasPopupVisible = popupVisible;
                    initX = bubbleParams.x; initY = bubbleParams.y;
                    initTouchX = e.getRawX(); initTouchY = e.getRawY();
                    moved = false;
                    showTrash();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int)(e.getRawX() - initTouchX);
                    int dy = (int)(e.getRawY() - initTouchY);
                    if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                    bubbleParams.x = initX + dx;
                    bubbleParams.y = initY + dy;
                    wm.updateViewLayout(bubbleView, bubbleParams);
                    if (popupVisible) updatePopupPosition();
                    highlightTrashIfNear(e.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                    hideTrash();
                    if (!moved) {
                        if (!wasPopupVisible) showPopup();
                        // popup was already dismissed by showTrash() — do nothing
                    } else if (isOverTrash(e.getRawY())) removeBubble();
                    else snapToEdge();
                    return true;
            }
            return false;
        });

        wm.addView(bubbleView, bubbleParams);
    }

    // ── Trash zone ────────────────────────────────────────────────────────────

    private void showTrash() {
        if (trashView != null) return;
        dismissPopup();

        TextView tv = new TextView(this);
        tv.setText("✕  Remove");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(28), dp(14), dp(28), dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(32));
        bg.setColor(0xCC333333);
        tv.setBackground(bg);
        trashView = tv;

        WindowManager.LayoutParams tp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        tp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        tp.y = dp(40);
        wm.addView(trashView, tp);
    }

    private void hideTrash() {
        if (trashView != null) { wm.removeView(trashView); trashView = null; }
    }

    private void highlightTrashIfNear(float rawY) {
        if (trashView == null) return;
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(m);
        boolean near = rawY > m.heightPixels - dp(140);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(32));
        bg.setColor(near ? 0xCCCC3333 : 0xCC333333);
        if (near) bg.setStroke(dp(2), Color.WHITE);
        trashView.setBackground(bg);
    }

    private boolean isOverTrash(float rawY) {
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(m);
        return rawY > m.heightPixels - dp(140);
    }

    private void removeBubble() { stopSelf(); }

    // ── Popup WebView ─────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void createPopupWebView() {
        WebView wv = new WebView(this);
        wv.setBackgroundColor(Color.TRANSPARENT);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);

        wv.addJavascriptInterface(new PopupBridge(), "Android");
        wv.loadUrl("file:///android_asset/public/popup.html");
        wv.setVisibility(View.GONE);

        popupWebView = wv;

        WindowManager.LayoutParams pp = new WindowManager.LayoutParams(
            dp(300), dp(480),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        pp.gravity = Gravity.TOP | Gravity.START;
        setPopupXY(pp);
        wm.addView(popupWebView, pp);
    }

    private void showPopup() {
        if (popupWebView == null) return;
        setPopupXY((WindowManager.LayoutParams) popupWebView.getLayoutParams());
        wm.updateViewLayout(popupWebView, popupWebView.getLayoutParams());
        popupWebView.setVisibility(View.VISIBLE);
        popupWebView.evaluateJavascript("if(window.reload)reload();", null);
        popupVisible = true;
    }

    private void dismissPopup() {
        if (popupWebView != null) popupWebView.setVisibility(View.GONE);
        popupVisible = false;
    }

    private void updatePopupPosition() {
        if (popupWebView == null || !popupVisible) return;
        WindowManager.LayoutParams pp = (WindowManager.LayoutParams) popupWebView.getLayoutParams();
        setPopupXY(pp);
        wm.updateViewLayout(popupWebView, pp);
    }

    private void setPopupXY(WindowManager.LayoutParams pp) {
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(m);
        int screenW = m.widthPixels;
        int screenH = m.heightPixels;
        int popW = dp(300), popH = dp(480), margin = dp(8);
        int idealY = bubbleParams.y - popH - dp(8);
        pp.y = Math.min((idealY < margin) ? bubbleParams.y + dp(72) : idealY, screenH - popH - margin);
        int idealX = bubbleParams.x + dp(32) - popW / 2;
        pp.x = Math.max(margin, Math.min(idealX, screenW - popW - margin));
    }

    // ── JS bridge ─────────────────────────────────────────────────────────────

    private static final Pattern IMAGE_EXT =
        Pattern.compile(".*\\.(gif|jpg|jpeg|png|webp|bmp)$", Pattern.CASE_INSENSITIVE);

    private class PopupBridge {
        @JavascriptInterface
        public void closePopup() {
            mainHandler.post(() -> dismissPopup());
        }

        @JavascriptInterface
        public void removeBubble() {
            mainHandler.post(() -> FloatingBubbleService.this.removeBubble());
        }

        @JavascriptInterface
        public void openApp() {
            mainHandler.post(() -> {
                dismissPopup();
                Intent intent = new Intent(FloatingBubbleService.this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            });
        }

        @JavascriptInterface
        public String getFolder() {
            return getSharedPreferences("allgif", 0).getString("folder", "");
        }

        @JavascriptInterface
        public String listImages(String folderPath) {
            try {
                File dir = new File(folderPath);
                File[] files = dir.listFiles();
                if (files == null) return "[]";
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                int count = 0;
                for (File f : files) {
                    if (count >= 60) break;
                    if (f.isDirectory()) continue;
                    if (!IMAGE_EXT.matcher(f.getName()).matches()) continue;
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"path\":\"").append(f.getAbsolutePath().replace("\"", "\\\""))
                      .append("\",\"name\":\"").append(f.getName().replace("\"", "\\\""))
                      .append("\"}");
                    count++;
                }
                sb.append("]");
                return sb.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String getThumbnail(String path) {
            try {
                File src = new File(path);
                if (!src.exists()) return "";
                File thumb = new File(getCacheDir(), "thumb_" + src.getName());
                if (!thumb.exists()) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 4;
                    Bitmap bm = BitmapFactory.decodeFile(path, opts);
                    if (bm == null) return "";
                    try (FileOutputStream fos = new FileOutputStream(thumb)) {
                        bm.compress(Bitmap.CompressFormat.JPEG, 72, fos);
                    }
                    bm.recycle();
                }
                return "file://" + thumb.getAbsolutePath();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String copyImage(String path) {
            try {
                File src = new File(path);
                if (!src.exists()) return "error:not found";
                File cacheFile = new File(getCacheDir(), "cb_" + src.getName());
                try (FileInputStream in = new FileInputStream(src);
                     FileOutputStream out = new FileOutputStream(cacheFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
                Uri uri = FileProvider.getUriForFile(
                    FloatingBubbleService.this,
                    getPackageName() + ".fileprovider",
                    cacheFile);
                ClipboardManager cb = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newUri(getContentResolver(), "image", uri));
                return "ok";
            } catch (Exception e) {
                return "error:" + e.getMessage();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void snapToEdge() {
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(m);
        int screenW = m.widthPixels, size = dp(64), margin = dp(8);
        bubbleParams.x = (bubbleParams.x + size / 2 < screenW / 2) ? margin : screenW - size - margin;
        wm.updateViewLayout(bubbleView, bubbleParams);
        if (popupVisible) updatePopupPosition();
    }

    private Bitmap loadAssetBitmap(String path, int targetPx) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            InputStream is = getAssets().open(path);
            BitmapFactory.decodeStream(is, null, opts);
            is.close();
            int sampleSize = 1;
            int raw = Math.max(opts.outWidth, opts.outHeight);
            while (raw / (sampleSize * 2) >= targetPx) sampleSize *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sampleSize;
            is = getAssets().open(path);
            Bitmap bm = BitmapFactory.decodeStream(is, null, opts);
            is.close();
            return bm;
        } catch (Exception e) {
            Bitmap bm = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFF6C63FF);
            c.drawCircle(targetPx / 2f, targetPx / 2f, targetPx / 2f, p);
            return bm;
        }
    }

    private Bitmap loadBubbleBitmap() {
        String customPath = getSharedPreferences("allgif", 0).getString("bubble_icon", null);
        if (customPath != null) {
            java.io.File f = new java.io.File(customPath);
            if (f.exists()) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                Bitmap raw = BitmapFactory.decodeFile(customPath, opts);
                if (raw != null) return circularBitmap(raw);
            }
        }
        return circularBitmap(loadAssetBitmap("public/appImg.jpeg", dp(64)));
    }

    private void reloadBubbleIcon() {
        bgThread.execute(() -> {
            Bitmap bm = loadBubbleBitmap();
            mainHandler.post(() -> {
                if (cachedCircularBitmap != null) cachedCircularBitmap.recycle();
                cachedCircularBitmap = bm;
                if (bubbleImageView != null) bubbleImageView.setImageBitmap(bm);
            });
        });
    }

    private Bitmap circularBitmap(Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(
            Bitmap.createScaledBitmap(src, size, size, true),
            Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        c.drawOval(new RectF(0, 0, size, size), p);
        return out;
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        unregisterReceiver(iconReceiver);
        bgThread.shutdownNow();
        hideTrash();
        if (popupWebView != null) {
            wm.removeView(popupWebView);
            popupWebView.destroy();
            popupWebView = null;
        }
        if (bubbleView != null) wm.removeView(bubbleView);
        if (cachedCircularBitmap != null) cachedCircularBitmap.recycle();
    }
}
