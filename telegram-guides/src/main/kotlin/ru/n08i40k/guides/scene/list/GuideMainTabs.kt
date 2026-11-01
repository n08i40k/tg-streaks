package ru.n08i40k.guides.scene.list

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.DialogsActivity
import org.telegram.ui.MainTabsActivity
import org.telegram.ui.MainTabsActivityController
import org.telegram.ui.ViewPagerActivity
import ru.n08i40k.guides.util.`MainTabsActivity$dialogsActivity$$setter`
import ru.n08i40k.guides.util.`MainTabsActivity$updateLayoutWrapper`
import ru.n08i40k.guides.util.`ViewPagerActivity$checkFragmentsVisibility`
import ru.n08i40k.guides.util.`ViewPagerActivity$isFullyVisible$$setter`
import ru.n08i40k.guides.util.`ViewPagerActivity$isResumed$$setter`
import ru.n08i40k.guides.util.`ViewPagerActivity$visibilityByParent$$setter`

internal class GuideMainTabs(private val dialogs: DialogsActivity, account: Int) {
    private val tabAvatars = mutableMapOf<BackupImageView, AvatarDrawable>()
    private var unreadCount = 0
    val fragment: BaseFragment
        get() = main

    private val main: MainTabsActivity = object : MainTabsActivity() {
        override fun onFragmentCreate() =
            true

        override fun getDialogsActivity() =
            dialogs

        override fun createBaseFragmentAt(position: Int): BaseFragment =
            if (position == startPosition) dialogs else object : BaseFragment() {
                override fun createView(context: Context): View =
                    FrameLayout(context).also { fragmentView = it }
            }

        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) = Unit

        override fun onBecomeFullyVisible() = Unit

        override fun onTransitionAnimationStart(isOpen: Boolean, backward: Boolean) = Unit

        override fun onTransitionAnimationEnd(isOpen: Boolean, backward: Boolean) = Unit

        override fun onResume() {
            isPaused = false
            resumePages(true)
        }

        override fun onPause() {
            isPaused = true
            resumePages(false)
        }

        override fun setNavigationBarColor(color: Int) = Unit

        override fun createView(context: Context): View =
            super.createView(context).also { sanitizeTabs() }
    }.apply { setCurrentAccount(account) }

    init {
        `MainTabsActivity$dialogsActivity$$setter`.invokeExact(main as MainTabsActivity, dialogs)
        dialogs.setMainTabsActivityController(object : MainTabsActivityController {
            override fun openAccountSelector(anchor: View, view: View) =
                false

            override fun setTabsVisible(visible: Boolean) = Unit
        })
    }

    private fun sanitizeTabs() {
        main.tabs?.forEachIndexed { index, tab ->
            if (tab != null) {
                val unread = if (index == 0) unreadCount else 0
                tab.setCounter(if (unread > 0) unread.toString() else null, false, false)
                tab.setPremiumBadge(false)
                tab.backupImageView?.let { image ->
                    val placeholder = tabAvatars.getOrPut(image) {
                        AvatarDrawable().apply { setInfo(0L, "Гайд", null) }
                    }

                    if (image.imageReceiver.staticThumb !== placeholder)
                        image.setImageDrawable(placeholder)
                }
            }
        }
        (`MainTabsActivity$updateLayoutWrapper`.invokeExact(main as MainTabsActivity) as View?)?.visibility = View.GONE
    }

    private fun resumePages(resume: Boolean) {
        val pager = main as ViewPagerActivity

        `ViewPagerActivity$isResumed$$setter`.invokeExact(pager, resume)
        `ViewPagerActivity$isFullyVisible$$setter`.invokeExact(pager, resume)
        `ViewPagerActivity$visibilityByParent$$setter`.invokeExact(pager, if (resume) 1f else 0f)
        `ViewPagerActivity$checkFragmentsVisibility`.invokeExact(pager)
    }

    fun sanitize(unread: Int) {
        unreadCount = unread
        sanitizeTabs()
    }
}
