# FloatingBrowser

A tiny, lightweight floating browser for Android. It opens a resizable web window over any other app, with almost no UI chrome so the page gets the space.

## Features

- Floating window over other apps (Display over other apps)
- Uses the system WebView (Chrome's engine), so the APK stays small and RAM use stays low
- Ultra-thin top strip, drag it to move the window
- Top left: zoom out, zoom in, and menu (back, forward, reload, URL, open in browser, desktop mode)
- Top right: minimize to a small bubble, and close
- Resizable from the bottom-right corner handle
- No ads, no trackers, no extra libraries

## Download

Get the latest APK from the [Releases](../../releases) page.

1. Download `app-debug.apk`.
2. Open it on your phone and allow **Install unknown apps** if asked.
3. If Play Protect warns, tap **Install anyway** (self-built debug app).
4. Open FloatingBrowser and grant **Display over other apps**.

## Usage

| Action | How |
|---|---|
| Move the window | Drag the top strip |
| Resize | Drag the bottom-right corner handle |
| Zoom | Zoom +/- buttons at the top left |
| Menu | Menu button at the top left |
| Minimize | Button at the top right, becomes a draggable bubble |
| Close | X button at the top right |

## Requirements

- Android 8.0 (API 26) or newer
- Android System WebView installed and up to date (preinstalled on most phones)

## Build from source

Built with Kotlin and plain Android Views. No Compose, no extra dependencies.

**With GitHub Actions:** push to `main`. The workflow builds the APK and uploads it as an artifact. Pushing a tag like `v1.0` also creates a release.

**Locally:** needs JDK 17 and the Android SDK (platform 36):

```bash
gradle :app:assembleDebug
```

The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

## Permissions

- `SYSTEM_ALERT_WINDOW`: show the floating window over other apps
- `FOREGROUND_SERVICE`: keep the window alive in the background
- `INTERNET`: load web pages

## Notes

- This app does not control your installed browsers. Android does not allow embedding Chrome or Firefox in a floating window, so it uses the built-in WebView instead.
- Some sites may behave slightly differently from your regular browser.

## License

MIT
