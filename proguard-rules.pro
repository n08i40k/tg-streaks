# вызывается из python через рефлексию
-keep class ru.n08i40k.streaks.Plugin {
    boolean isInjected();
    java.lang.String getBuildDate();
    java.lang.String getVersion();

    boolean inject(java.lang.String, android.webkit.ValueCallback, java.lang.String, boolean, java.lang.Runnable);
    void eject();

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

-keep,allowobfuscation class ru.n08i40k.guides.scene.list.GuideMainTabs** { *; }
-keep,allowobfuscation class ru.n08i40k.guides.util.MainTabsAccessorsKt { *; }

-keepclassmembers class ru.n08i40k.guides.scene.chat.GuideComposerDelegate {
    public void needStartRecordVideo(int, boolean, int, int, long, long);
    public void onMessageSend(java.lang.CharSequence, boolean, int, long);
}
