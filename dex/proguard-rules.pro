-keep class ru.n08i40k.streaks.** {
    *;
}

-keepnames class android.util.LongSparseArray
-keepnames class androidx.collection.LongSparseArray
-keepnames class java.**
-keeppackagenames java.**

-repackageclasses 'ru.n08i40k.streaks_shaded'
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
