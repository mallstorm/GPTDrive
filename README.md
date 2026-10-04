# GPT Drive for Android

GPT Drive is a native Android client that presents ChatGPT Library content as cloud-drive-like storage.

## v0.4.9 — Authentication fix
- Restored the reliable in-app ChatGPT WebView login
- Removed the broken Custom Tabs/session-JSON bridge from the Library synchronization path
- WebView synchronization now uses the real authenticated ChatGPT cookies again
- Removed bearer/session JSON injection that caused unauthenticated pages and UI icons to be indexed as files
- Multiple ChatGPT accounts and fast account switching remain supported
- Android `DocumentsProvider` continues to expose one persistent GPT Drive root

## Account login
1. Open GPT Drive.
2. Tap the account chip and choose **＋ Добавить аккаунт**.
3. Sign in to ChatGPT in the in-app login window.
4. After ChatGPT confirms the session, GPT Drive stores that account's WebView cookies separately.
5. Synchronize Library / Images / Projects / Archived.

## Usage
1. Open GPT Drive.
2. Choose the active account from the account chip.
3. Synchronize the required section to refresh its local metadata index.
4. Android's system file picker continues to show one `GPT Drive` cloud source backed by the selected account.

## Important platform note
Android does not let a normal third-party application register itself as a physical `StorageVolume` like an SD card or USB flash drive. GPT Drive therefore uses Android's official `DocumentsProvider` cloud-storage mechanism. File managers that integrate the Storage Access Framework can show GPT Drive as an external document source.

## Architecture
ChatGPT does not expose a stable public consumer API for the complete Library / Projects surface. GPT Drive keeps account-specific WebView session cookies, indexes metadata visible to the authenticated session, and streams remote content on demand.

## Build
Requires JDK 17, Android SDK 35 and Gradle 8.9.

```bash
gradle :app:assembleDebug
```

GitHub Actions builds an APK on pushes to `main` and validates pull requests targeting `main`.
