# J.A.V.I. production hardening
# R8 removes unused code and obfuscates release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep Android WebView callbacks and framework-created components safe.
-keepclassmembers class * extends android.webkit.WebViewClient { *; }
