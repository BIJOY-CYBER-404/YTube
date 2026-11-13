-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keepclassmembers class com.example.MainActivity$TubeViewJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.example.MediaPlaybackService { *; }
-keep class com.example.BackgroundAudioWebView { *; }
