@file:Suppress("ObjectPropertyName", "HasPlatformType")

package ru.n08i40k.guides.util

import android.view.View
import org.telegram.ui.MainTabsActivity
import org.telegram.ui.ViewPagerActivity

@JvmField
internal val `MainTabsActivity$dialogsActivity$$setter` =
    getFieldSetter(MainTabsActivity::class.java, "dialogsActivity")

@JvmField
internal val `MainTabsActivity$updateLayoutWrapper` =
    getFieldGetter(MainTabsActivity::class.java, "updateLayoutWrapper")
        .retype(View::class.java, MainTabsActivity::class.java)

@JvmField
internal val `ViewPagerActivity$isResumed$$setter` =
    getFieldSetter(ViewPagerActivity::class.java, "isResumed")

@JvmField
internal val `ViewPagerActivity$isFullyVisible$$setter` =
    getFieldSetter(ViewPagerActivity::class.java, "isFullyVisible")

@JvmField
internal val `ViewPagerActivity$visibilityByParent$$setter` =
    getFieldSetter(ViewPagerActivity::class.java, "visibilityByParent")

@JvmField
internal val `ViewPagerActivity$checkFragmentsVisibility` =
    getMethodHandle(ViewPagerActivity::class.java, "checkFragmentsVisibility")
