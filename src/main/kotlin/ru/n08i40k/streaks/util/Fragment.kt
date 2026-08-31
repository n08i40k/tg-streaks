package ru.n08i40k.streaks.util

import androidx.annotation.AnyThread
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.LaunchActivity

@AnyThread
fun presentFragment(fragment: BaseFragment) {
    val parent = LaunchActivity.getSafeLastFragment() ?: return

    runOnMainThread { parent.presentFragment(fragment) }
}