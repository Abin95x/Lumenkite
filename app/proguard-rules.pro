-keep class org.drinkless.tdlib.** { *; }
-keep class ai.onnxruntime.** { *; }

# Strip debug/info/warning logs from release builds (errors are kept).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
