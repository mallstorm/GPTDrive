# GPT Drive for Android

GPT Drive is a very small native Android client that presents the user's ChatGPT Library as a cloud-drive-like file manager.

## v0.2.0
- Native home screen and native category browser
- Library / Images / Projects / Archived sections
- Image gallery with authenticated thumbnails
- Full-screen native image viewer
- Search inside indexed categories
- Open and download indexed files
- WebView is now used only for ChatGPT sign-in and synchronization
- Improved DOM + WebView resource indexing
- Project/archive ChatGPT links can be indexed separately from downloadable files
- SQLite v2 keeps the same resource in more than one category
- Android DocumentsProvider exposes GPT Drive in the system file picker
- Image thumbnails are exposed to Android DocumentsProvider
- No AndroidX, Compose, Glide, Picasso or other third-party runtime libraries

## How it works
1. Open GPT Drive.
2. Tap **Синхронизировать Library** and sign in to ChatGPT if needed.
3. Let the ChatGPT Library/Images page load, then tap **Готово**.
4. Browse the indexed content in GPT Drive's native UI.
5. GPT Drive also appears as a document source in Android's system file picker.

## Architecture
The app deliberately isolates the ChatGPT web-session adapter. ChatGPT does not expose a stable public API for the entire consumer Library/Projects surface, so v0.2 indexes content visible to the authenticated web session and stores only metadata locally. Remote content is streamed or downloaded on demand using the user's own WebView cookies.

## Build
Requires JDK 17, Android SDK 35 and Gradle 8.9.

```bash
gradle :app:assembleDebug
```

GitHub Actions builds an APK artifact on every push to `main`.
