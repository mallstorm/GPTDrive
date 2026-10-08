-keepclassmembers class com.gptdrive.app.LibraryBridge { @android.webkit.JavascriptInterface <methods>; }

# pdfbox-android can optionally use Gemalto's JPEG 2000 decoder. It is not bundled
# and is only needed for JPX/JPEG2000 images embedded in some PDFs.
-dontwarn com.gemalto.jp2.**
