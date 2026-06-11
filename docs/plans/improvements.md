# AllGif Android — Improvement Plan

> Derived from `docs/ARCHITECTURE_AUDIT.md` (2026-06-10)  
> Ordered by priority: P1 = must fix, P2 = important, P3 = polish

---

## Overview

| # | Fix | Priority | Effort | Risk |
|---|---|---|---|---|
| 1 | Async thumbnail generation | P1 | Medium | Low |
| 2 | Cache key collision | P1 | Low | Low |
| 3 | Remove dead `src/popup.js` | P1 | Trivial | None |
| 4 | `volatile isRunning` | P2 | Trivial | None |
| 5 | Guard `wm.removeView` in `onDestroy` | P2 | Low | None |
| 6 | Thumbnail cache eviction | P2 | Low | Low |
| 7 | `bubbleStartAttempted` reset on service death | P2 | Medium | Low |
| 8 | Restrict FileProvider paths | P2 | Low | None |
| 9 | Remove `setAllowUniversalAccessFromFileURLs` | P2 | Low | Low |
| 10 | Replace deprecated `getDefaultDisplay()` | P3 | Low | None |
| 11 | SAF-native directory listing (no `uriToPath`) | P3 | High | Medium |

---

## P1 — Must Fix

### Fix 1: Async thumbnail generation

**Problem:** `PopupBridge.getThumbnail()` calls `BitmapFactory.decodeFile` synchronously on the WebView JS thread. With multiple images intersecting the viewport at once, the popup freezes for hundreds of milliseconds.

**Files:** `FloatingBubbleService.java`, `popup.html`

**Plan:**

1. Remove the synchronous `getThumbnail` method from `PopupBridge`.
2. Add an async version that takes a callback ID, dispatches to `bgThread`, then posts the result back via `evaluateJavascript`:

```java
// FloatingBubbleService.java — inside PopupBridge
@JavascriptInterface
public void requestThumbnail(final String path, final String callbackId) {
    bgThread.execute(() -> {
        String result = buildThumbnail(path);   // existing decode + cache logic
        String escaped = result.replace("'", "\\'");
        String js = "onThumbnailReady('" + callbackId + "','" + escaped + "')";
        mainHandler.post(() -> {
            if (popupWebView != null)
                popupWebView.evaluateJavascript(js, null);
        });
    });
}

// Extract the existing decode logic into a private helper:
private String buildThumbnail(String path) {
    try {
        File src = new File(path);
        if (!src.exists()) return "";
        File thumb = new File(getCacheDir(), "thumb_" + src.getName().hashCode() + "_" + src.getName());
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
    } catch (Exception e) { return ""; }
}
```

3. Update `popup.html` inline script to use a callback map instead of a synchronous call:

```js
// Replace the IntersectionObserver callback:
var pendingThumbnails = {};

function onThumbnailReady(callbackId, thumbPath) {
  var img = pendingThumbnails[callbackId];
  if (!img) return;
  delete pendingThumbnails[callbackId];
  img.src = thumbPath || ('file://' + img.dataset.src);
}

observer = new IntersectionObserver(function(entries) {
  entries.forEach(function(e) {
    if (!e.isIntersecting) return;
    var img = e.target;
    var path = img.dataset.src;
    if (!path) return;
    observer.unobserve(img);
    delete img.dataset.src;
    var id = 'cb_' + Math.random().toString(36).slice(2);
    pendingThumbnails[id] = img;
    Android.requestThumbnail(path, id);
  });
}, { rootMargin: '200px' });
```

4. Run `npm run sync` to rebuild and push to device. Test by opening the popup on a folder with 30+ images — scrolling should feel smooth.

---

### Fix 2: Cache key collision for clipboard copy and thumbnails

**Problem:** `"cb_" + src.getName()` and `"thumb_" + src.getName()` collide when two files in different folders share a filename.

**Files:** `FileAccessPlugin.java` (line 206), `FloatingBubbleService.java` (lines 378, inside `buildThumbnail` after Fix 1)

**Plan:**

Replace the name-only key with a hash of the full path + name:

```java
// FileAccessPlugin.java:206
private File clipboardCacheFile(File src) {
    String key = String.format("%08x_%s", src.getAbsolutePath().hashCode(), src.getName());
    return new File(getContext().getCacheDir(), "cb_" + key);
}
// Use in copyImageToClipboard():
File cacheFile = clipboardCacheFile(src);
```

```java
// FloatingBubbleService.java — buildThumbnail helper (from Fix 1)
String key = String.format("%08x_%s", path.hashCode(), src.getName());
File thumb = new File(getCacheDir(), "thumb_" + key);
```

No rebuild of the web assets needed — Java only.

---

### Fix 3: Delete dead `src/popup.js`

**Problem:** `src/popup.js` is never imported anywhere. It is an earlier iteration of popup logic, entirely superseded by the inline `<script>` block in `popup.html`. It causes confusion about where popup behavior lives.

**Files:** `src/popup.js`

**Plan:**

1. Delete `src/popup.js`.
2. Add a one-line comment at the top of the `<script>` block in `popup.html`:
   ```html
   <script>
   // Popup logic lives here (not src/popup.js — that file is removed).
   ```
3. Confirm `vite build` still succeeds — it was never in the build graph so output is identical.

---

## P2 — Important

### Fix 4: Mark `isRunning` as `volatile`

**Problem:** `FloatingBubbleService.isRunning` is read by `MainActivity` (main thread) and written by the service lifecycle (service thread) without a memory barrier.

**File:** `FloatingBubbleService.java` (line 56)

```java
// Before:
public static boolean isRunning = false;

// After:
public static volatile boolean isRunning = false;
```

One-line change. No logic impact.

---

### Fix 5: Guard `wm.removeView` in `onDestroy`

**Problem:** If `bubbleView` or `popupWebView` is removed before `onDestroy` is called (e.g., by the trash handler racing with an OS-forced stop), `wm.removeView` throws `IllegalArgumentException`.

**File:** `FloatingBubbleService.java` — `onDestroy` (line 479)

Add a utility and use it for every `wm.removeView` call:

```java
private void safeRemoveView(View v) {
    if (v == null) return;
    try { wm.removeView(v); } catch (IllegalArgumentException ignored) {}
}

@Override
public void onDestroy() {
    super.onDestroy();
    isRunning = false;
    unregisterReceiver(iconReceiver);
    bgThread.shutdownNow();
    safeRemoveView(trashView);   trashView = null;
    if (popupWebView != null) {
        safeRemoveView(popupWebView);
        popupWebView.destroy();
        popupWebView = null;
    }
    safeRemoveView(bubbleView);
    if (cachedCircularBitmap != null) { cachedCircularBitmap.recycle(); cachedCircularBitmap = null; }
}
```

---

### Fix 6: Thumbnail cache eviction

**Problem:** `getCacheDir()` thumbnail files accumulate indefinitely. Large image folders can create hundreds of multi-KB cache files with no cleanup.

**File:** `FloatingBubbleService.java` — add after `buildThumbnail` writes the thumb file

```java
private static final long THUMB_CACHE_LIMIT = 40 * 1024 * 1024L;  // 40 MB

private void evictThumbnailCache() {
    File cacheDir = getCacheDir();
    File[] thumbs = cacheDir.listFiles(f -> f.getName().startsWith("thumb_"));
    if (thumbs == null) return;
    long total = 0;
    for (File f : thumbs) total += f.length();
    if (total <= THUMB_CACHE_LIMIT) return;
    // Sort oldest-first and delete until under limit
    Arrays.sort(thumbs, Comparator.comparingLong(File::lastModified));
    for (File f : thumbs) {
        if (total <= THUMB_CACHE_LIMIT) break;
        total -= f.length();
        f.delete();
    }
}
```

Call `evictThumbnailCache()` at the end of `buildThumbnail`, after the new file is written.

---

### Fix 7: Reset `bubbleStartAttempted` when service dies

**Problem:** Once `bubbleStartAttempted = true` is set, the bubble cannot auto-restart if the service is killed (OOM, crash) while the app is backgrounded. The user must force-kill and reopen the app.

**File:** `MainActivity.java`

Bind to the service to receive disconnect notifications:

```java
private final ServiceConnection serviceConn = new ServiceConnection() {
    @Override public void onServiceConnected(ComponentName n, IBinder b) {}
    @Override public void onServiceDisconnected(ComponentName n) {
        bubbleStartAttempted = false;
        // Service died — re-check on next resume
    }
};

// In startBubbleService(), after startForegroundService():
bindService(new Intent(this, FloatingBubbleService.class),
    serviceConn, BIND_AUTO_CREATE);

// In onDestroy():
unbindService(serviceConn);
```

---

### Fix 8: Restrict FileProvider exposed paths

**Problem:** `path="."` in both `<external-path>` and `<cache-path>` entries exposes the entire external storage root and the entire cache directory through the FileProvider.

**File:** `android/app/src/main/res/xml/file_paths.xml`

Move clipboard and thumbnail files to named subdirectories, then restrict the FileProvider declaration:

```java
// In FileAccessPlugin and FloatingBubbleService, use subdirs:
File clipDir  = new File(getContext().getCacheDir(), "clipboard");
File thumbDir = new File(getCacheDir(), "thumbnails");
clipDir.mkdirs();
thumbDir.mkdirs();
```

```xml
<!-- file_paths.xml -->
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <external-path name="external_images" path="." />
    <cache-path name="clipboard_cache"   path="clipboard/" />
    <cache-path name="thumbnail_cache"   path="thumbnails/" />
</paths>
```

Coordinate with Fix 2 and Fix 6 — all three touch cache file paths.

---

### Fix 9: Remove `setAllowUniversalAccessFromFileURLs`

**Problem:** `setAllowUniversalAccessFromFileURLs(true)` allows JS in the popup WebView to fetch any `file://` path on the device. This is wider than needed — the popup only loads a bundled HTML asset and calls `JavascriptInterface` methods.

**File:** `FloatingBubbleService.java` (line 242)

```java
// Remove this line:
s.setAllowUniversalAccessFromFileURLs(true);

// Keep (needed for relative asset paths within the popup HTML):
s.setAllowFileAccess(true);
s.setAllowFileAccessFromFileURLs(true);
```

Test that popup CSS and images still load after removing. If they do (they should, since the CSS is a relative path loaded by the HTML itself, not a cross-origin XHR), delete the line permanently.

---

## P3 — Polish

### Fix 10: Replace deprecated `getDefaultDisplay()`

**Problem:** `wm.getDefaultDisplay().getMetrics(m)` is deprecated since API 30.

**File:** `FloatingBubbleService.java` (lines 213, 225, 284, 404)

Extract a helper:

```java
private android.util.DisplayMetrics getDisplayMetrics() {
    android.util.DisplayMetrics m = new android.util.DisplayMetrics();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        getDisplay().getRealMetrics(m);
    } else {
        wm.getDefaultDisplay().getMetrics(m);
    }
    return m;
}
```

Replace all four call sites with `getDisplayMetrics()`.

---

### Fix 11: Replace `uriToPath` with SAF-native listing

**Problem:** `uriToPath` manually reconstructs a filesystem path from a SAF document URI. It works for primary internal storage but silently breaks for cloud providers (Google Drive, Dropbox) and some OEM storage volumes.

**Files:** `FileAccessPlugin.java` — `pickFolder`, `folderPickerResult`, `listFiles`, `syncFolder`

This is a larger refactor. The SAF URI should be stored and used directly rather than converting it to a path:

**Step 1 — Store URI, not path, in SharedPreferences:**

```java
// folderPickerResult: store the tree URI string instead of the converted path
String uriStr = uri.toString();
getContext().getSharedPreferences("allgif", 0)
    .edit().putString("folder_uri", uriStr).apply();

// Also take persistable permission so it survives reboots:
getContext().getContentResolver()
    .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
```

**Step 2 — Rewrite `listFiles` to use `DocumentFile`:**

```java
@PluginMethod
public void listFiles(PluginCall call) {
    String uriStr = call.getString("uri");
    if (uriStr == null) { call.reject("uri required"); return; }

    DocumentFile dir = DocumentFile.fromTreeUri(getContext(), Uri.parse(uriStr));
    if (dir == null || !dir.isDirectory()) { call.reject("Not a directory"); return; }

    JSArray result = new JSArray();
    for (DocumentFile f : dir.listFiles()) {
        JSObject item = new JSObject();
        item.put("name", f.getName());
        item.put("uri",  f.getUri().toString());
        item.put("isDirectory", f.isDirectory());
        item.put("size", f.length());
        item.put("lastModified", f.lastModified());
        result.put(item);
    }
    JSObject ret = new JSObject();
    ret.put("files", result);
    call.resolve(ret);
}
```

**Step 3 — Update `filesystem.js` and `main.js`** to pass `uri` instead of `path`, and use `Capacitor.convertFileSrc(uri)` for image display.

**Step 4 — Update `PopupBridge.getFolder`** to return the URI string, and rewrite `listImages` to use `DocumentFile` instead of `new File(path)`.

> **Note:** This fix has medium complexity and touches both web and native layers. It should be done in a dedicated branch. The other P1/P2 fixes can be applied independently before this one.

---

## Suggested Implementation Order

```
Week 1 (low risk, high value):
  Fix 3 — delete src/popup.js          (5 min)
  Fix 4 — volatile isRunning           (2 min)
  Fix 2 — cache key collision          (15 min)
  Fix 5 — guard wm.removeView          (15 min)
  Fix 8 — restrict FileProvider paths  (20 min)

Week 2 (medium effort):
  Fix 1 — async thumbnail              (2–3 hours, needs popup.html + Java + device test)
  Fix 6 — cache eviction               (30 min)
  Fix 9 — remove UniversalAccessFromFileURLs (20 min + device test)

Week 3 (optional polish):
  Fix 7 — reset bubbleStartAttempted   (1 hour)
  Fix 10 — replace getDefaultDisplay   (30 min)

Future sprint:
  Fix 11 — SAF-native listing          (1–2 days, separate branch, full regression test)
```

---

## Testing Checklist

After each fix:

- [ ] `npm run build` succeeds (no Vite errors)
- [ ] `npm run sync` completes (cap sync)
- [ ] `./gradlew assembleDebug` compiles (Java only for native fixes)
- [ ] App installs and launches on device
- [ ] Overlay permission dialog appears on fresh install
- [ ] Bubble appears and is draggable
- [ ] Snap-to-edge works after drag release
- [ ] Trash zone turns red when bubble dragged near bottom; `stopSelf()` fires on drop
- [ ] Popup opens on tap; shows images from selected folder
- [ ] Images in popup are lazy-loaded (thumbnails appear as you scroll, not all at once)
- [ ] Tapping an image in popup copies to clipboard — paste into another app confirms correct image
- [ ] Tapping an image in main gallery copies to clipboard
- [ ] "Change folder" picker works; gallery reloads with new folder
- [ ] "Change bubble icon" picker works; bubble icon updates live without restart
- [ ] App can be backgrounded and returned to; bubble is still present
- [ ] Dragging bubble to trash removes bubble and notification
- [ ] Opening app after bubble is removed shows config/gallery correctly
