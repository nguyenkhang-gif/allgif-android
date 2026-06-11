package com.allgif.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.activity.result.ActivityResult;
import androidx.core.content.FileProvider;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import android.database.Cursor;
import android.provider.OpenableColumns;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;

import java.io.ByteArrayOutputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Base64;

@CapacitorPlugin(name = "FileAccess")
public class FileAccessPlugin extends Plugin {

    @PluginMethod
    public void listFiles(PluginCall call) {
        String path = call.getString("path", "/sdcard");
        File dir = new File(path);

        if (!dir.exists() || !dir.isDirectory()) {
            call.reject("Not a directory: " + path);
            return;
        }

        File[] files = dir.listFiles();
        JSArray result = new JSArray();

        if (files != null) {
            for (File f : files) {
                JSObject item = new JSObject();
                item.put("name", f.getName());
                item.put("path", f.getAbsolutePath());
                item.put("isDirectory", f.isDirectory());
                item.put("size", f.length());
                item.put("lastModified", f.lastModified());
                result.put(item);
            }
        }

        JSObject ret = new JSObject();
        ret.put("files", result);
        call.resolve(ret);
    }

    @PluginMethod
    public void readFile(PluginCall call) {
        String path = call.getString("path");
        if (path == null) { call.reject("path is required"); return; }

        String encoding = call.getString("encoding", "base64");

        try {
            File file = new File(path);
            if (!file.exists() || file.isDirectory()) {
                call.reject("File not found: " + path);
                return;
            }

            FileInputStream fis = new FileInputStream(file);
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            fis.close();

            String data = ("utf8".equals(encoding) || "utf-8".equals(encoding))
                ? new String(bytes, "UTF-8")
                : Base64.getEncoder().encodeToString(bytes);

            JSObject ret = new JSObject();
            ret.put("data", data);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Read error: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getStorageRoots(PluginCall call) {
        JSArray roots = new JSArray();
        File primary = android.os.Environment.getExternalStorageDirectory();
        if (primary != null) {
            JSObject item = new JSObject();
            item.put("name", "Internal Storage");
            item.put("path", primary.getAbsolutePath());
            roots.put(item);
        }
        JSObject ret = new JSObject();
        ret.put("roots", roots);
        call.resolve(ret);
    }

    @PluginMethod
    public void syncFolder(PluginCall call) {
        String path = call.getString("path", "");
        getContext().getSharedPreferences("allgif", 0)
            .edit().putString("folder", path).apply();
        call.resolve();
    }

    @PluginMethod
    public void pickFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(call, intent, "folderPickerResult");
    }

    @ActivityCallback
    private void folderPickerResult(PluginCall call, ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("Cancelled");
            return;
        }
        Uri uri = result.getData().getData();
        String path = uriToPath(uri);
        // Persist so the popup WebView can read it without Capacitor
        getContext().getSharedPreferences("allgif", 0)
            .edit().putString("folder", path).apply();
        JSObject ret = new JSObject();
        ret.put("path", path);
        call.resolve(ret);
    }

    private String uriToPath(Uri uri) {
        // content://com.android.externalstorage.documents/tree/primary%3ADCIM
        // → /storage/emulated/0/DCIM
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            if (docId.startsWith("primary:")) {
                String rel = docId.substring("primary:".length());
                return "/storage/emulated/0" + (rel.isEmpty() ? "" : "/" + rel);
            }
            // External SD card: "XXXX-XXXX:path"
            int colon = docId.indexOf(':');
            if (colon >= 0) {
                String vol = docId.substring(0, colon);
                String rel = docId.substring(colon + 1);
                return "/storage/" + vol + (rel.isEmpty() ? "" : "/" + rel);
            }
        } catch (Exception ignored) {}
        return uri.toString();
    }

    @PluginMethod
    public void pickBubbleIcon(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(call, intent, "bubbleIconPickerResult");
    }

    @ActivityCallback
    private void bubbleIconPickerResult(PluginCall call, ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("Cancelled");
            return;
        }
        Uri uri = result.getData().getData();
        try {
            File dest = new File(getContext().getFilesDir(), "bubble_icon.jpg");
            try (InputStream in = getContext().getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
            String path = dest.getAbsolutePath();
            getContext().getSharedPreferences("allgif", 0)
                .edit().putString("bubble_icon", path).apply();
            Intent broadcast = new Intent("com.allgif.UPDATE_BUBBLE_ICON");
            broadcast.setPackage(getContext().getPackageName());
            getContext().sendBroadcast(broadcast);
            JSObject ret = new JSObject();
            ret.put("path", path);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Error: " + e.getMessage());
        }
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        String path = call.getString("path");
        if (path == null) { call.reject("path required"); return; }
        File f = new File(path);
        if (!f.exists()) { call.reject("File not found: " + path); return; }
        if (f.delete()) {
            call.resolve();
        } else {
            call.reject("Delete failed: " + path);
        }
    }

    @PluginMethod
    public void importImages(PluginCall call) {
        String destFolder = call.getString("destFolder");
        if (destFolder == null) { call.reject("destFolder required"); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(call, intent, "importImagesResult");
    }

    @ActivityCallback
    private void importImagesResult(PluginCall call, ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("Cancelled");
            return;
        }
        String destFolder = call.getString("destFolder");
        Intent data = result.getData();

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            android.content.ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        JSArray imported = new JSArray();
        for (Uri uri : uris) {
            try {
                String filename = getFilenameFromUri(uri);
                File dest = new File(destFolder, filename);
                String base = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
                String ext  = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')) : "";
                int n = 1;
                while (dest.exists()) { dest = new File(destFolder, base + "_" + n + ext); n++; }
                try (InputStream in = getContext().getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
                JSObject item = new JSObject();
                item.put("name", dest.getName());
                item.put("path", dest.getAbsolutePath());
                imported.put(item);
            } catch (Exception ignored) {}
        }
        JSObject ret = new JSObject();
        ret.put("imported", imported);
        call.resolve(ret);
    }

    @PluginMethod
    public void removeBackground(PluginCall call) {
        String path = call.getString("path");
        if (path == null) { call.reject("path required"); return; }

        Bitmap bitmap = BitmapFactory.decodeFile(path);
        if (bitmap == null) { call.reject("Could not decode image"); return; }

        SubjectSegmenter segmenter = SubjectSegmentation.getClient(
            new SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap()
                .build()
        );

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        segmenter.process(image)
            .addOnSuccessListener(result -> {
                bitmap.recycle();
                Bitmap fg = result.getForegroundBitmap();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                fg.compress(Bitmap.CompressFormat.PNG, 100, baos);
                fg.recycle();
                String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
                JSObject ret = new JSObject();
                ret.put("data", b64);
                call.resolve(ret);
            })
            .addOnFailureListener(e -> {
                bitmap.recycle();
                call.reject("Segmentation error: " + e.getMessage());
            });
    }

    @PluginMethod
    public void saveImage(PluginCall call) {
        String path = call.getString("path");
        String data = call.getString("data");
        if (path == null) { call.reject("path required"); return; }
        if (data == null) { call.reject("data required"); return; }
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            try (FileOutputStream out = new FileOutputStream(new File(path))) {
                out.write(bytes);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Save error: " + e.getMessage());
        }
    }

    @PluginMethod
    public void copyImageDataToClipboard(PluginCall call) {
        String data = call.getString("data");
        if (data == null) { call.reject("data required"); return; }
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            File cacheFile = new File(getContext().getCacheDir(), "cb_edited.png");
            try (FileOutputStream out = new FileOutputStream(cacheFile)) {
                out.write(bytes);
            }
            Uri uri = FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                cacheFile
            );
            ClipboardManager clipboard = (ClipboardManager)
                getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newUri(
                getContext().getContentResolver(), "image", uri
            ));
            call.resolve();
        } catch (Exception e) {
            call.reject("Clipboard error: " + e.getMessage());
        }
    }

    private String getFilenameFromUri(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContext().getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) return c.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "image_" + System.currentTimeMillis() + ".jpg";
    }

    @PluginMethod
    public void copyImageToClipboard(PluginCall call) {
        String path = call.getString("path");
        if (path == null) { call.reject("path required"); return; }

        int cropW = call.getInt("cropWidth", 0);
        int cropH = call.getInt("cropHeight", 0);

        try {
            File src = new File(path);
            if (!src.exists()) { call.reject("File not found: " + path); return; }

            File cacheFile = new File(getContext().getCacheDir(), "cb_" + src.getName());

            if (cropW > 0 && cropH > 0) {
                Bitmap original = BitmapFactory.decodeFile(src.getAbsolutePath());
                if (original == null) { call.reject("Could not decode image"); return; }

                int srcW = original.getWidth();
                int srcH = original.getHeight();
                int x = Math.min(cropW, srcW / 2);
                int y = Math.min(cropH, srcH / 2);
                int targetW = srcW - x * 2;
                int targetH = srcH - y * 2;

                Bitmap cropped = Bitmap.createBitmap(original, x, y, targetW, targetH);
                original.recycle();

                try (FileOutputStream out = new FileOutputStream(cacheFile)) {
                    cropped.compress(Bitmap.CompressFormat.JPEG, 95, out);
                }
                cropped.recycle();
            } else {
                try (FileInputStream in = new FileInputStream(src);
                     FileOutputStream out = new FileOutputStream(cacheFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
            }

            Uri uri = FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                cacheFile
            );

            ClipboardManager clipboard = (ClipboardManager)
                getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newUri(
                getContext().getContentResolver(), "image", uri
            );
            clipboard.setPrimaryClip(clip);

            call.resolve();
        } catch (Exception e) {
            call.reject("Clipboard error: " + e.getMessage());
        }
    }
}
