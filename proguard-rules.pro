# disallow shrinking and obfuscation
-keep,allowoptimization class ru.n08i40k.streaks.** {
    *;
}

# disallow optimization and obfuscation of runtime refs
-keep,allowshrinking class kotlin.**,kotlinx.**,androidx.** {
    *;
}

# repackage shaded classes
-repackageclasses 'SRs9' # random chars to avoid conflicts at runtime
-keeppackagenames !ru.n08i40k.streaks_shaded.**,"**" # repackage only shaded

-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
