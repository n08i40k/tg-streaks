-keep class ru.n08i40k.streaks.Plugin {
    *;
}

-keep class ru.n08i40k.streaks.Plugin$Companion {
    *;
}

-repackageclasses 'ru.n08i40k.streaks.shaded'
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
