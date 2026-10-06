# GPT Drive for Android

GPT Drive is a native Android client that presents ChatGPT Library content as cloud-drive-like storage through Android `DocumentsProvider` and the app's own folder-first browser.

## v0.11.3 — Background watcher and app protection

- Added a persistent, user-controlled Library watcher based on the Windows GPT Drive watcher model.
- The watcher is independent from full synchronization: it polls only recent Library and image-generation metadata, compares stable remote IDs and never downloads file bodies.
- New images/files are merged into the local metadata index and can produce Android notifications without running a full sync.
- The first successful watcher pass establishes a baseline, so existing Library content does not trigger a notification storm.
- Watch interval is configurable: 15, 30 or 60 seconds.
- Added app locking with a 3×3 graphical pattern and fingerprint unlock.
- The graphical pattern is stored only as a salted PBKDF2-HMAC-SHA256 hash; no plaintext pattern is stored.
- Fingerprint unlock always retains the graphical pattern as a fallback.
- Auto-lock can be set to immediately, 30 seconds, 1 minute, 5 minutes or 15 minutes after leaving the app.
- Unlock and security screens use `FLAG_SECURE` to block screenshots and recent-app previews.

## Account login

1. Open GPT Drive.
2. Tap the account chip and choose **＋ Добавить аккаунт**.
3. Sign in to ChatGPT in the in-app login window.
4. GPT Drive stores account-specific authentication state separately.
5. Run a full synchronization once to establish the complete metadata index; the lightweight watcher then keeps recent objects current.

## Background watcher

The watcher is deliberately not a periodic full sync. It checks the first recent Library page plus the recent image-generation page, merges metadata by stable remote identity and sends a notification only for newly discovered objects. Full Library / Images / Projects / Archived synchronization remains a separate operation.

On modern Android versions the watcher runs as a foreground service, so Android can keep it alive while GPT Drive is not on screen. A low-priority persistent notification represents that active background process. Android 13+ notification permission is requested once when the feature is enabled.

## App protection

Open **Меню → Безопасность**. Create a graphical pattern first, then choose either **Отпечаток + графический ключ** or **Только графический ключ**. The pattern remains the recovery path if biometric authentication is unavailable. The application lifecycle enforces the selected auto-lock timeout when GPT Drive returns from the background.

## Android Files integration

Android does not let a normal third-party application register itself as a physical `StorageVolume` like an SD card or USB flash drive. GPT Drive therefore uses Android's official `DocumentsProvider` cloud-storage mechanism. File managers that integrate the Storage Access Framework can show GPT Drive as an external document source.

## Architecture

GPT Drive keeps a normalized SQLite metadata index. Regular sync and the lightweight watcher are separate engines. Remote file bodies are streamed/downloaded only on explicit user access; indexing and watcher passes are metadata-only.

## Build

Requires JDK 17, Android SDK 35 and Gradle 8.9.

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

GitHub Actions validates pull requests targeting `main` and publishes the debug APK artifact for `main` builds.
