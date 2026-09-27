package ru.n08i40k.streaks.util

import org.telegram.ui.Components.RecyclerListView

fun RecyclerListView.postSafe(callback: Runnable) {
    post {
        if (isComputingLayout)
            postSafe(callback)
        else {
            stopScroll()
            stopNestedScroll()

            callback.run()
        }
    }
}