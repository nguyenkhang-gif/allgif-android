# AllGif Android — Dev Log

A Vite + Capacitor Android app built to test and develop a **system-level floating bubble** (like Facebook Messenger's chat heads). The popup UI is written in HTML/CSS/JS; only the bubble mechanics and overlay management are in native Java.

---

## Tech Stack

| Layer | Technology |
|---|---|
| UI (main app) | Vanilla JS + CSS |
| UI (popup window) | Vanilla JS + CSS (WebView overlay) |
| Bundler | Vite 8 |
| Native bridge | Capacitor 8 |
| Floating overlay | Android `WindowManager` (TYPE_APPLICATION_OVERLAY) |
| Background service | Android `ForegroundService` (specialUse type) |
| Android build | Gradle + ADB |

---

## File Structure

```
allgif-android/
├── src/
│   ├── main.js          # Main app entry — bubble drag logic (browser preview)
│   ├── style.css        # Main app styles
│   ├── popup.js         # Popup window JS — wires buttons to Android bridge
│   └── popup.css        # Popup window styles
├── public/
│   └── appImg.jpeg      # App icon — used as the bubble image
├── index.html           # Main app HTML shell
├── popup.html           # Popup window HTML shell (second Vite entry)
├── vite.config.js       # Vite config — multi-page build, base: './'
├── capacitor.config.json
├── package.json
└── android/
    └── app/src/main/
        ├── java/com/allgif/app/
        │   ├── MainActivity.java          # Checks overlay permission, starts service
        │   └── FloatingBubbleService.java # All floating bubble logic
        └── AndroidManifest.xml
```

---

## Architecture

```
┌─────────────────────────────────────────────┐
│              Android Device                  │
│                                              │
│  ┌──────────────────────────────────────┐   │
│  │         WindowManager Overlays        │   │
│  │                                       │   │
│  │  [Bubble ImageView]  ← always on top  │   │
│  │  [Popup WebView]     ← tap to toggle  │   │
│  │  [Trash zone]        ← drag to remove │   │
│  └──────────────────────────────────────┘   │
│                                              │
│  ┌──────────────────────────────────────┐   │
│  │     Capacitor WebView (main app)      │   │
│  │     index.html / src/main.js          │   │
│  └──────────────────────────────────────┘   │
│                                              │
│  FloatingBubbleService (ForegroundService)   │
│  MainActivity (BridgeActivity)               │
└─────────────────────────────────────────────┘
```

---

## Build Pipeline

```
src/ + popup.html + index.html
        │
        ▼ npm run build (Vite)
      dist/
        │
        ▼ cap sync android
  android/app/src/main/assets/public/
        │
        ▼ ./gradlew assembleDebug
  app-debug.apk
        │
        ▼ adb install -r
  Android device
```

---

## NPM Scripts

| Script | What it does |
|---|---|
| `npm run dev` | Local dev server at `http://localhost:3010` |
| `npm run build` | Vite production build → `dist/` |
| `npm run sync` | Build + `cap sync android` (web assets only, no Gradle) |
| `npm run apk` | Full build → `apk/AllGif.apk` |
| `npm run run` | Full build + install to connected device via ADB |
| `npm run log` | Stream logcat filtered to `com.allgif.app` |

---

## Android Permissions

```xml
INTERNET                        — WebView network access
SYSTEM_ALERT_WINDOW             — Draw overlay over other apps
FOREGROUND_SERVICE              — Run persistent background service
FOREGROUND_SERVICE_SPECIAL_USE  — Required on Android 14+ (API 34+) for specialUse FGS type
```

> **First launch:** Android will show a dialog asking for "Display over other apps" permission. You must grant it in Settings before the bubble appears.

---

## How the Floating Bubble Works

### 1. Permission check (`MainActivity.java`)

On `onCreate`, checks `Settings.canDrawOverlays()`:
- If **not granted** → shows an `AlertDialog` directing the user to the system settings page for AllGif
- If **granted** → calls `startForegroundService(FloatingBubbleService)`

Also re-checks on every `onResume` (so it auto-starts after the user comes back from granting permission).

### 2. Foreground service (`FloatingBubbleService.java`)

The service must run as a **foreground service** (Android 8+ requirement for persistent background services). It posts a silent notification on channel `allgif_bubble` using `NotificationManager.IMPORTANCE_LOW`.

On Android 14+ (`targetSdk 34+`) foreground services require a declared type. This app uses `foregroundServiceType="specialUse"` with a `<property>` tag explaining the use case.

### 3. Bubble view

A 64dp `ImageView` added to `WindowManager` with these flags:
- `TYPE_APPLICATION_OVERLAY` — draws over all other apps
- `FLAG_NOT_FOCUSABLE` — doesn't steal keyboard focus
- `FLAG_LAYOUT_NO_LIMITS` — can be dragged to screen edges without clipping

The bubble image (`public/appImg.jpeg`) is loaded **off the main thread** using a single-thread `ExecutorService` to avoid blocking the UI. Loading is done in two passes:
1. `inJustDecodeBounds = true` — reads dimensions without decoding pixels
2. Calculates `inSampleSize` to downsample to ~64dp before decoding
3. Applies a circular crop using `BitmapShader` on a `Canvas`
4. Posts the resulting `Bitmap` back to the main thread via `Handler(Looper.getMainLooper())`

**Why this matters:** Decoding a full-resolution JPEG on the main thread blocks Android's rendering pipeline entirely. Audio continues playing (separate thread) but the screen freezes. This caused a full device screen-freeze on the first version.

### 4. Drag behaviour

Uses `setOnTouchListener` with pointer events:
- `ACTION_DOWN` — records initial bubble position and touch coordinates, shows trash zone
- `ACTION_MOVE` — updates `WindowManager.LayoutParams.x/y` and calls `updateViewLayout()`. Sets `moved = true` if displacement exceeds 4dp threshold
- `ACTION_UP` — decides outcome:
  - `!moved` → tap → toggles popup
  - `isOverTrash()` → drag to remove → `stopSelf()`
  - otherwise → `snapToEdge()`

### 5. Snap to edge

On drag release (if not over trash), the bubble snaps to the nearest horizontal edge with an 8dp margin. The popup position is also recalculated if open.

### 6. Trash zone

When a drag starts (`ACTION_DOWN`):
- A "✕ Remove" pill `TextView` is added to `WindowManager` at the bottom-center with `FLAG_NOT_TOUCHABLE` (it only displays, doesn't intercept touches)
- As the bubble moves within 140dp of the bottom, the pill turns red
- On `ACTION_UP` over that zone, `stopSelf()` is called, triggering `onDestroy()` which removes all views from `WindowManager`

---

## Popup Window

### Java side (minimal)

`showPopup()` creates a single `WebView` added to `WindowManager` at 300×420dp, positioned above (or below if near top) the bubble. The `WebView`:
- Has `setBackgroundColor(Color.TRANSPARENT)` so the rounded card in CSS shows through
- Has JS enabled (`setJavaScriptEnabled(true)`)
- Has `allowFileAccess` and `allowFileAccessFromFileURLs` enabled so it can load local asset files
- Loads `file:///android_asset/public/popup.html`
- Has a `JavascriptInterface` registered as `window.Android`

### JS bridge (`PopupBridge` inner class)

Three methods callable from `popup.js` as `window.Android.*`:

| Method | What it does |
|---|---|
| `Android.closePopup()` | Dismisses the popup WebView (minimize) |
| `Android.removeBubble()` | Calls `stopSelf()` — removes bubble + popup entirely |
| `Android.openApp()` | Dismisses popup then starts `MainActivity` via `Intent` |

All bridge callbacks arrive on a background thread, so each one uses `mainHandler.post()` to run UI operations on the main thread.

### JS/CSS side (`popup.html`, `src/popup.js`, `src/popup.css`)

The popup is a standard dark-themed card:
- **Header** — app icon (`appImg.jpeg`), title "AllGif", minimize button (`—`), close/remove button (`✕`)
- **Body** — scrollable, contains Quick Actions rows (Browse GIFs, Favourites, Search) and a Recent section — both are placeholders for future development
- **Footer** — "Open AllGif →" button

The `Native` object in `popup.js` falls back gracefully to `console.log` when running in a desktop browser, so you can develop the popup UI at `http://localhost:3010/popup.html` without needing to deploy to the phone.

---

## Vite Multi-Page Build

`vite.config.js` declares two entry points via `rollupOptions.input`:

```js
input: {
  main:  resolve(__dirname, 'index.html'),
  popup: resolve(__dirname, 'popup.html'),
}
```

Both build to `dist/` with hashed asset filenames.

**Critical:** `base: './'` is required. Without it, Vite generates absolute asset paths (`/assets/popup-xxx.css`). On `file://` URLs (which is how Android WebView loads from assets), absolute paths resolve to the filesystem root — not the assets directory — so all CSS and JS silently fail to load and the popup renders with no styling.

---

## Bugs Fixed During Development

### Screen freeze on launch

**Symptom:** Phone screen froze completely on first install. Audio kept playing normally.

**Cause:** `BitmapFactory.decodeStream()` was called synchronously on the main thread during `FloatingBubbleService.onCreate()`. Decoding a large JPEG on the main thread blocks the Android rendering pipeline (the GPU/compositor thread). Audio runs independently so it was unaffected.

**Fix:** Moved all bitmap work to a background `ExecutorService` thread. Added two-pass downsampling with `BitmapFactory.Options.inSampleSize` to avoid loading the full-resolution image. Added `mainHandler.post()` to set the decoded bitmap on the `ImageView` from the main thread after decoding completes. Also cached the decoded bitmap so the popup reuses it without a second decode.

### Popup WebView rendering with no styles

**Symptom:** After switching the popup from programmatic Java views to a `WebView`, all CSS was missing on device.

**Cause:** Vite's default `base: '/'` setting generates absolute paths in the built HTML. `file:///android_asset/public/popup.html` loading `/assets/popup-xxx.css` would try to resolve it as `file:///assets/popup-xxx.css` — a path that doesn't exist.

**Fix:** Added `base: './'` to `vite.config.js`. Vite now generates `./assets/popup-xxx.css` (relative), which resolves correctly relative to the HTML file's location.

### `onResume` visibility modifier

**Symptom:** Build error — `onResume() in MainActivity cannot override onResume() in BridgeActivity: attempting to assign weaker access privileges; was public`.

**Cause:** `BridgeActivity.onResume()` is declared `public`, but the override was declared `protected`.

**Fix:** Changed `protected void onResume()` to `public void onResume()`.

### `popupView` symbol not found

**Symptom:** Build errors — `cannot find symbol: variable popupView`.

**Cause:** During the Java-to-WebView popup refactor, the field was renamed from `View popupView` to `WebView popupWebView`, but several usages still referenced the old name.

**Fix:** Removed the redundant `popupView` alias and updated all references to use `popupWebView`.

---

## Requirements

- Node.js 18+
- Java 21 (`/opt/homebrew/opt/openjdk@21`)
- Android SDK (`~/Library/Android/sdk`)
- Android device with USB debugging enabled
- "Display over other apps" permission granted to AllGif on first launch

---

## Future Development Notes

- **Popup UI** is fully in `src/popup.js` and `src/popup.css` — extend freely without touching Java
- To add new bridge methods (e.g. to pass data from native → JS), add a `@JavascriptInterface` method in `PopupBridge` and call `popupWebView.evaluateJavascript(...)` from the Java side
- The Quick Action rows and Recent section in the popup are placeholder stubs — wire them up in `popup.js`
- The main app (`index.html`, `src/main.js`) is a separate Capacitor WebView — build the full app UI there as a normal web app
