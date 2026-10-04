# GPT Drive for Android

GPT Drive is a tiny native Android client that presents ChatGPT Library content as cloud-drive-like storage.

## v0.3.1 — Hot Account Switching
- Multiple ChatGPT account profiles can be saved in one APK
- **Only one account is active at a time**
- Fast account switching from the GPT Drive header
- Saved WebView cookies are snapshotted per account and restored on switch
- Separate SQLite metadata namespace for every account
- Existing v0.2/v0.3 indexed content remains account-scoped
- Android `DocumentsProvider` exposes **one persistent GPT Drive root**
- Switching the active account updates the content behind that same root
- No simultaneous multi-account roots and no aggregated "all accounts" cloud
- Library / Images / Projects / Archived remain separated inside the active drive
- Native file browser, search, image gallery, image viewer and downloads remain available
- No AndroidX, Compose, Glide, Picasso or other third-party runtime libraries

## Usage
1. Open GPT Drive.
2. Tap the account chip at the top.
3. Choose a saved account for an immediate switch, or use **＋ Добавить аккаунт**.
4. GPT Drive restores that account's saved ChatGPT web session and changes the visible file tree.
5. Android's system file picker continues to show one `GPT Drive` cloud source, now backed by the selected account.
6. Synchronize Library / Images / Projects / Archived when you want to refresh the local metadata index.

## Important platform note
Android does not let a normal third-party application register itself as a physical `StorageVolume` like an SD card or USB flash drive. GPT Drive therefore uses Android's official `DocumentsProvider` cloud-storage mechanism. File managers that integrate the Storage Access Framework can show GPT Drive as an external document source. A vendor file manager may choose not to surface third-party document providers in its physical-storage strip.

## Architecture
ChatGPT does not expose a stable public consumer API for the complete Library / Projects surface. GPT Drive therefore isolates its web-session adapter, indexes metadata visible to the authenticated user session, and streams remote content on demand. Account profiles keep separate metadata and session snapshots, while `CookieManager` carries only the currently selected account's active session.

## Build
Requires JDK 17, Android SDK 35 and Gradle 8.9.

```bash
gradle :app:assembleDebug
```

GitHub Actions builds an APK artifact on every push to `main`.
