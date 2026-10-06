# GPT Drive for Android

GPT Drive is a native Android client that presents ChatGPT Library content as cloud-drive-like storage through Android `DocumentsProvider` and the app's own folder-first browser.

## v0.11.3 — Background watcher and app protection

- Added a persistent, user-controlled Library watcher inspired by the Windows GPT Drive implementation.
- The watcher is independent from full synchronization: it polls only recent Library and image-generation metadata, compares stable remote IDs and never downloads file bodies.
- New images/files are merged into the local metadata index and can produce Android notifications without running a full sync.
- The first successful watcher pass establishes a baseline, so existing Library content does not trigger a notification storm.
- Watch interval is configurable in **Настройки**: 15, 30 or 60 seconds.
- Added app locking with a 3×3 graphical pattern and fingerprint unlock.
- The graphical pattern is stored only as a salted PBKDF2-HMAC-SHA256 hash; no plaintext pattern is stored.
- Fingerprint unlock always keeps the graphical pattern as a fallback.
- Auto-lock can be set to immediately, 30 seconds, 1 minute or 5 minutes after leaving the app.

## Account login

1. Open GPT Drive.
2. Tap the account chip and choose **＋ Добавить аккаунт**.
3. Sign in to ChatGPT in the in-app login window.
4. GPT Drive stores account-specific authentication state separately.
5. Run a full synchronization once to establish the complete metadata index; the lightweight watcher can then keep recent objects current.

## Background watcher

The watcher is deliberately not a periodic full sync. It checks the first recent Library page plus the recent image-generation page, merges metadata by stable remote identity and sends a notification only for newly discovered objects. Full Library / Images / Projects / Archived synchronization remains a separate manual/background operation.

On modern Android versions the watcher runs as a foreground service, so Android can keep the process alive while GPT Drive is not on screen. The persistent service notification opens GPT Drive settings. Notification permission is requested on Android 13+.

## App protection

Open **Меню → Настройки → Безопасность**. Create a graphical pattern first, then optionally enable fingerprint unlock. The pattern is the fallback if biometric authentication is unavailable. The launcher and foreground-resume flow enforce the configured lock timeout.

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
