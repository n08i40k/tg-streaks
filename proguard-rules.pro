# disallow shrinking
-keep,allowoptimization,allowobfuscation class ru.n08i40k.streaks.** {
    *;
}

-keepnames class ru.n08i40k.streaks.database.** {
    *;
}

-keepnames class ru.n08i40k.streaks.Plugin {
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

# disallow optimization and obfuscation of runtime refs
-keep,allowshrinking class kotlin.**,kotlinx.**,androidx.** {
    *;
}

# repackage shaded classes
-repackageclasses 'SRs9' # random chars to avoid conflicts at runtime
-keeppackagenames !ru.n08i40k.streaks_shaded.**,"**" # repackage only shaded

-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature

# есть в рантайме ART, но отсутствует в android.jar
-dontwarn sun.misc.Unsafe
