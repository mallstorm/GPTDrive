# GPT Drive (Android)

Lightweight native Android shell that exposes a user's ChatGPT Library as a cloud-drive-like experience.

## v0.1.0
- Native Java/XML-free UI (platform widgets only; no Compose, no AndroidX dependencies)
- ChatGPT sign-in via Android System WebView and persistent cookies
- Library and Images entry points
- Projects / Archived entry points using ChatGPT UI discovery
- DOM bridge that indexes visible ChatGPT file/image links into local SQLite metadata
- Android `DocumentsProvider` root named **GPT Drive**
- Remote file streaming with authenticated WebView cookies when a discovered URL is directly downloadable
- Android DownloadManager integration

## Important
ChatGPT currently exposes the full Library primarily through its web UI. GPT Drive therefore uses the user's authenticated web session and a DOM indexing adapter rather than pretending there is a stable public Library API. The adapter is isolated so it can later be replaced by an official API if OpenAI publishes one.

## Build
Requires JDK 17+, Android SDK 35 and Gradle 8.9.

```bash
gradle :app:assembleDebug
```

The GitHub Actions workflow builds an APK artifact automatically.
