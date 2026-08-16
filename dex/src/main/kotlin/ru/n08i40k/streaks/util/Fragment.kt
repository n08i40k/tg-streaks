package ru.n08i40k.streaks.util

import androidx.annotation.AnyThread
import org.telegram.ui.ActionBar.ActionBarLayout
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.LaunchActivity

private val ACTION_BAR_LAYOUT = getField(LaunchActivity::class.java, "actionBarLayout")

@AnyThread
fun presentFragment(fragment: BaseFragment) {
    val layout = ACTION_BAR_LAYOUT.getAs<ActionBarLayout>(LaunchActivity.instance) ?: return

    runOnMainThread { layout.presentFragment(fragment) }
}