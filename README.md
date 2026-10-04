# GPT Drive for Android

GPT Drive is a tiny native Android client that presents ChatGPT Library content as cloud-drive-like storage.

## v0.4.8 — Browser Session Login
- ChatGPT account connection now starts in the user's regular browser / Custom Tab
- An already authenticated browser session can be reused without typing the password again
- GPT Drive imports the current `/api/auth/session` JSON after the user copies it and returns to the app
- Imported browser sessions are stored per GPT Drive account and kept isolated from WebView cookies
- The sync WebView receives the imported session through an internal `/api/auth/session` bridge
- File and document streaming can use the imported bearer token when cookies are unavailable
- The previous isolated WebView login remains available as a fallback
- Multiple ChatGPT account profiles and fast account switching remain supported
- Android `DocumentsProvider` continues to expose one persistent GPT Drive root

## Browser-session login
1. Open GPT Drive and choose **＋ Добавить аккаунт**.
2. GPT Drive opens `chatgpt.com/api/auth/session` in the regular browser / Custom Tab.
3. If the browser is already logged in to ChatGPT, the page shows the current session JSON.
4. Select all JSON text and copy it.
5. Return to GPT Drive. The app detects and imports the session automatically.
6. If no browser session is available, choose the fallback **Войти вручную во встроенном окне**.

The browser and Android WebView use separate cookie stores, so the browser cookies themselves are not copied into GPT Drive. Only the session JSON explicitly copied by the user is imported.

## Usage
1. Open GPT Drive.
2. Tap the account chip at the top.
3. Choose a saved account for an immediate switch, or add another account.
4. Synchronize Library / Images / Projects / Archived when you want to refresh the local metadata index.
5. Android's system file picker continues to show one `GPT Drive` cloud source backed by the currently selected account.

## Important platform note
Android does not let a normal third-party application register itself as a physical `StorageVolume` like an SD card or USB flash drive. GPT Drive therefore uses Android's official `DocumentsProvider` cloud-storage mechanism. File managers that integrate the Storage Access Framework can show GPT Drive as an external document source. A vendor file manager may choose not to surface third-party document providers in its physical-storage strip.

## Architecture
ChatGPT does not expose a stable public consumer API for the complete Library / Projects surface. GPT Drive therefore isolates its web-session adapter, indexes metadata visible to the authenticated user session, and streams remote content on demand. Account profiles keep separate metadata and session state while only one account is active at a time.

## Build
Requires JDK 17, Android SDK 35 and Gradle 8.9.

```bash
gradle :app:assembleDebug
```

GitHub Actions builds an APK on pushes to `main` and validates pull requests targeting `main`.
