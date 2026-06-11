# AllGif Performance Plan

## Problems

| # | Issue | Impact |
|---|---|---|
| 1 | Popup WebView destroyed/recreated on every open | High — full HTML parse + image fetch every tap |
| 2 | Full-resolution images decoded in 100dp cells | High — 4–12 MB per image for a 100dp thumbnail |
| 3 | No lazy loading in popup | Medium — all images requested at once |
| 4 | Image list unbounded in popup | Medium — 500 images = 500 img elements |
| 5 | `listImages()` scans disk synchronously | Low — JS thread blocks until Java returns |

---

## Fix 1 — Keep WebView alive (VISIBLE/GONE toggle)

**File:** `FloatingBubbleService.java`

- Add `createPopup()` called once in `onCreate()` — builds the WebView, adds it to WindowManager with `GONE` visibility
- `showPopup()` → `setVisibility(VISIBLE)` + call `evaluateJavascript("reload()")` to refresh if folder changed
- `dismissPopup()` → `setVisibility(GONE)` — do NOT destroy
- `onDestroy()` → properly remove and destroy the WebView

```java
// onCreate:
createBubble();
createPopupWebView();   // new — builds once, hidden

// showPopup:
popupWebView.setVisibility(View.VISIBLE);
popupWebView.evaluateJavascript("if(window.reload)reload();", null);

// dismissPopup:
popupWebView.setVisibility(View.GONE);

// onDestroy:
if (popupWebView != null) { wm.removeView(popupWebView); popupWebView.destroy(); }
```

---

## Fix 2 — Thumbnail cache

**File:** `FloatingBubbleService.java` — add to `PopupBridge`

```java
@JavascriptInterface
public String getThumbnail(String path) {
    // cache key: thumb_<filename>
    File cache = new File(getCacheDir(), "thumb_" + new File(path).getName());
    if (!cache.exists()) {
        // decode at 1/4 size using inSampleSize=4
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 4;
        Bitmap bm = BitmapFactory.decodeFile(path, opts);
        if (bm == null) return "";
        FileOutputStream fos = new FileOutputStream(cache);
        bm.compress(Bitmap.CompressFormat.JPEG, 72, fos);
        fos.close();
        bm.recycle();
    }
    return "file://" + cache.getAbsolutePath();
}
```

---

## Fix 3 — Cap + lazy load in popup

**File:** `popup.html` inline JS

- Cap `listImages()` result at **60** (most recent, already sorted by `lastModified` desc)
- Use `data-src` / IntersectionObserver lazy loading
- Load thumbnail via `Android.getThumbnail(path)` per cell (cached after first call)

```js
// cap
var images = JSON.parse(Android.listImages(folder)).slice(0, 60);

// lazy
var observer = new IntersectionObserver(function(entries) {
  entries.forEach(function(e) {
    if (e.isIntersecting) {
      var img = e.target;
      img.src = Android.getThumbnail(img.dataset.src) || img.dataset.src;
      observer.unobserve(img);
    }
  });
}, { rootMargin: '200px' });

// per cell
img.dataset.src = file.path;   // full path stored for copy
observer.observe(img);
```

---

## Fix 4 — Cap in `listImages()` Java

**File:** `FloatingBubbleService.java` — `PopupBridge.listImages()`

Add early exit after 60 images in the JSON builder loop.

---

## Implementation order

1. Fix 1 (keep WebView alive) — `FloatingBubbleService.java`
2. Fix 2 (thumbnail method) — `FloatingBubbleService.java`
3. Fix 3 + 4 (cap + lazy load) — `popup.html`

No JS module changes needed. No Vite rebuild required for Java-only changes (but `popup.html` change needs a Vite build + `cap sync`).
