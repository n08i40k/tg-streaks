package ru.n08i40k.streaks.extension

import org.telegram.ui.Components.RecyclerListView

fun RecyclerListView.setSectionsCompat() {
    try {
        this.javaClass
            .getDeclaredMethod("setSections")
            .invoke(this)
    } catch (_: NoSuchMethodException) {
    }
}