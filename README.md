# GPT Drive for Android

GPT Drive is a tiny native Android client that presents ChatGPT Library content as cloud-drive-like storage.

## v0.3.0 — Multi-Account Drives
- Multiple ChatGPT accounts in one APK
- Native account switcher on the GPT Drive home screen
- Add, switch, synchronize and remove account profiles
- Separate SQLite metadata namespace for every account
- Existing v0.2 indexed content migrates into the first account
- ChatGPT WebView session cookies are snapshotted per account and restored when switching
- Account identity is discovered from the signed-in ChatGPT session when available
- **Each account is exposed as its own Android DocumentsProvider root**
- Each root contains Library / Images / Projects / Archived
- Files can stream from a non-active account using its stored session
- Android file pickers can show multiple GPT Drive account sources at the same time
- Native file browser, search, image gallery, image viewer and downloads remain available
- No AndroidX, Compose, Glide, Picasso or other third-party runtime libraries

## Usage
1. Open GPT Drive.
2. Tap the account selector at the top.
3. Use **＋ Добавить ChatGPT аккаунт** to add another account and sign in in the synchronization WebView.
4. Switch accounts from the account screen; the native GPT Drive tree changes to that account.
5. Open Android's system file picker to see a separate `GPT Drive · <account>` root for every saved account.
6. Synchronize Library / Images / Projects / Archived as needed to refresh the local metadata index.

## Important platform note
Android does not let a normal third-party application register itself as a physical `StorageVolume` like an SD card or USB flash drive. GPT Drive therefore uses Android's official `DocumentsProvider` cloud-storage mechanism. File managers that integrate the Storage Access Framework can show the GPT Drive account roots as external document sources. A vendor file manager may choose not to surface third-party document providers in its physical-storage strip.

## Architecture
ChatGPT does not expose a stable public consumer API for the complete Library / Projects surface. GPT Drive therefore isolates its web-session adapter, indexes metadata visible to the authenticated user session, and streams remote content on demand. v0.3 stores session-cookie snapshots locally per account to support switching; this is an experimental compatibility layer and may require adaptation when ChatGPT's web authentication changes.

## Build
Requires JDK 17, Android SDK 35 and Gradle 8.9.

```bash
gradle :app:assembleDebug
```

GitHub Actions builds an APK artifact on every push to `main`.
