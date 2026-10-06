# вызывается из python через рефлексию
-keep class ru.n08i40k.streaks.Plugin {
    boolean isInjected();
    java.lang.String getBuildDate();
    java.lang.String getVersion();

    void inject(java.lang.String, android.webkit.ValueCallback, java.lang.String);
    void finalizeInject();
    void eject();

    void invokeChatContextMenuCallback(java.lang.String, long);
    void invokeSettingsActionCallback(java.lang.String);

    void setPetFabSizeDp(int);
    void setAutoStreakCreationEnabled(boolean);

    android.content.SharedPreferences getSharedPrefs();
}

# room
-keepnames class ru.n08i40k.streaks.database.PluginDatabase
-keep class ru.n08i40k.streaks.database.PluginDatabase_Impl {
    <init>();
}

# webview
-keepclassmembers class ru.n08i40k.streaks.** {
    @android.webkit.JavascriptInterface <methods>;
}

# kotlin у хоста
-keep,allowshrinking class androidx.** {
    *;
}

-dontobfuscate
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature

# есть в рантайме ART, но отсутствует в android.jar
-dontwarn sun.misc.Unsafe
