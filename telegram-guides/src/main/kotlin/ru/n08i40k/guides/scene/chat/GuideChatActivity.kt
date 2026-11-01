package ru.n08i40k.guides.scene.chat

import android.content.Context
import android.os.Bundle
import android.view.View
import org.telegram.ui.ChatActivity
import ru.n08i40k.guides.util.invokeOnPause

open class GuideChatActivity(arguments: Bundle) : ChatActivity(arguments) {
    internal var historyCreated: () -> Unit = {}
    internal var historyViewCreated: () -> Unit = {}

    init {
        isInsideContainer = false
    }

    override fun firstLoadMessages() = Unit

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) = Unit

    override fun onFragmentCreate(): Boolean {
        if (!super.onFragmentCreate())
            return false

        historyCreated()

        return true
    }

    override fun createView(context: Context): View =
        super.createView(context).also {
            historyViewCreated()

            chatActivityEnterView?.apply {
                setDelegate(GuideComposerDelegate())
                setFieldText("")
            }
        }

    override fun onResume() {
        fragmentBeginToShow = true
        isPaused = false
        actionBar?.onResume()
        contentView?.onResume()
        chatActivityEnterView?.onResume()
    }

    override fun onPause() {
        fragmentBeginToShow = false
        isPaused = true
        actionBar?.invokeOnPause()
        chatActivityEnterView?.onPause()
        contentView?.onPause()
    }

    override fun saveDraft() = Unit

    override fun onTransitionAnimationStart(isOpen: Boolean, backward: Boolean) = Unit

    override fun onTransitionAnimationEnd(isOpen: Boolean, backward: Boolean) = Unit

    override fun onSearchLoadingUpdate(loading: Boolean) = Unit

    override fun setNavigationBarColor(color: Int) = Unit

    override fun checkAdjustResize() = Unit
}
