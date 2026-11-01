package ru.n08i40k.guides.util

import android.content.Context
import org.telegram.ui.ActionBar.INavigationLayout
import java.lang.reflect.Method

private val newLayout: Method by lazy {
    val types = arrayOf(Context::class.java, Boolean::class.javaPrimitiveType)

    try {
        INavigationLayout::class.java.getMethod("newLayout", *types)
    } catch (_: NoSuchMethodException) {
        Class.forName("${INavigationLayout::class.java.name}\$-CC", false, INavigationLayout::class.java.classLoader)
            .getMethod("newLayout", *types)
    }
}

fun createGuideNavigationLayout(context: Context): INavigationLayout =
    newLayout.invoke(null, context, false) as INavigationLayout
