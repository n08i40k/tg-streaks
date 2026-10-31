package ru.n08i40k.guides.scene.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.delay
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatActionCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.SizeNotifierFrameLayout
import ru.n08i40k.guides.api.GuidePresentation
import ru.n08i40k.guides.model.GuideAction
import ru.n08i40k.guides.model.GuideMessage
import ru.n08i40k.guides.scene.GuideFragmentHost
import ru.n08i40k.guides.scene.GuideViewportScene
import ru.n08i40k.guides.util.animateGuide
import ru.n08i40k.guides.util.await
import ru.n08i40k.guides.util.awaitGuideLayout
import ru.n08i40k.guides.util.`ActionBarMenuItem$Item$view`
import ru.n08i40k.guides.util.`ActionBarMenuItem$layoutLazyItems`
import ru.n08i40k.guides.util.`ActionBarMenuItem$xOffset`
import ru.n08i40k.guides.util.`ActionBarMenuItem$yOffset`
import ru.n08i40k.guides.util.`ChatActionCell$giftButtonRect`
import ru.n08i40k.guides.util.`ChatActivity$headerItem`
import ru.n08i40k.guides.util.`ChatActivity$pluginsMenuItem`

object GuideChatTargets {
    const val MENU = "chat.menu"

    const val PROFILE = "chat.profile"

    const val PLUGINS = "chat.plugins"

    const val BADGE = "chat.badge"
}

@SuppressLint("ViewConstructor")
internal class ChatActivityGuideScene(
    context: Context,
    private val dialogId: Long,
    messages: List<GuideMessage>,
    account: Int,
    presentation: GuidePresentation?,
) : GuideViewportScene(context) {
    private val host = GuideFragmentHost(context)
    private var menu: ActionBarPopupWindow.ActionBarPopupWindowLayout? = null

    private val chatActivity: GuideChatActivity
    private val history: GuideChatHistory

    init {
        require(dialogId != 0L && dialogId != Long.MIN_VALUE) { "OpenChat expects a Telegram dialog ID" }

        val arguments = Bundle().apply {
            if (dialogId > 0)
                putLong("user_id", dialogId)
            else
                putLong("chat_id", -dialogId)
        }

        chatActivity = (presentation?.chat(arguments) ?: GuideChatActivity(arguments)).apply {
            setCurrentAccount(account)
        }
        history = GuideChatHistory(chatActivity, dialogId, messages, presentation)
        chatActivity.historyCreated = history::install
        chatActivity.historyViewCreated = history::show
        addContent(host.view)
    }

    override fun onFirstShow() {
        host.show(chatActivity)
        chatActivity.avatarContainer?.setOccupyStatusBar(false)
        (chatActivity.fragmentView as? SizeNotifierFrameLayout)?.setOccupyStatusBar(false)
        chatActivity.chatActivityEnterView?.setFieldFocused(false)
        updateTheme()
    }

    override suspend fun perform(action: GuideAction) {
        when (action) {
            is GuideAction.TapAt -> tapAt(action.x, action.y)

            is GuideAction.SetTitle -> chatActivity.avatarContainer?.setTitle(action.title)

            is GuideAction.ShowMessages -> history.replace(action.messages)

            is GuideAction.SendMessage -> {
                val input = checkNotNull(chatActivity.chatActivityEnterView)

                input.setFieldText(action.message.text)
                input.checkSendButton(false)
                tap(input.sendButton, press = true)
                input.setFieldText("")
                input.checkSendButton(false)
                history.append(action.message)
                awaitGuideLayout(screen)
            }

            is GuideAction.ReceiveMessage -> history.append(action.message)

            is GuideAction.AppendMessage -> history.append(action.message)

            is GuideAction.TapMessage -> tapMessage(action.id, action.actionButton)

            GuideAction.OpenChatMenu -> openMenu()

            GuideAction.OpenPluginsMenu -> {
                pluginsItem().openSwipeBack()
                delay(SWIPE_BACK_DURATION)
            }

            is GuideAction.TapMenuItem -> tap(menuItem(action.text), press = true)

            is GuideAction.TapTarget -> tapTarget(action.target)

            else -> error("${action.javaClass.simpleName} is not a chat action; use ReturnToChats first")
        }
    }

    private suspend fun tapTarget(target: String) {
        when (target) {
            GuideChatTargets.MENU -> tap(headerItem(), press = true)

            GuideChatTargets.PROFILE -> tap(checkNotNull(chatActivity.avatarContainer), press = true)

            GuideChatTargets.PLUGINS -> tap(`ActionBarMenuItem$Item$view`.invokeExact(pluginsItem()) as View, press = true)

            GuideChatTargets.BADGE -> {
                val title = checkNotNull(chatActivity.avatarContainer?.titleTextView)
                val icon = checkNotNull(title.rightDrawable) { "The chat title has no badge" }

                tap(title, PointF(title.rightDrawableX + icon.intrinsicWidth / 2f, title.height / 2f))
            }

            else -> error("Unknown chat target: $target")
        }
    }

    private suspend fun tapMessage(id: Int, actionButton: Boolean) {
        val row = history.messageView(id)

        if (!actionButton)
            return tap(row, press = true)

        check(row is ChatActionCell) { "Guide message $id is not a service message" }

        val button = `ChatActionCell$giftButtonRect`.invokeExact(row as ChatActionCell) as RectF

        check(!button.isEmpty) { "Guide message $id has no visible action button" }
        tap(row, PointF(button.centerX(), button.centerY()))
    }

    private fun headerItem(): ActionBarMenuItem =
        `ChatActivity$headerItem`.invokeExact(chatActivity as ChatActivity) as ActionBarMenuItem

    private fun pluginsItem(): ActionBarMenuItem.Item =
        `ChatActivity$pluginsMenuItem`.invokeExact(chatActivity as ChatActivity) as ActionBarMenuItem.Item

    private suspend fun openMenu() {
        val header = headerItem()

        `ActionBarMenuItem$layoutLazyItems`.invokeExact(header)

        val popup = checkNotNull(header.popupLayout) { "The chat has no header menu" }

        (popup.parent as? ViewGroup)?.removeView(popup)
        popup.scrollToTop()
        popup.updateRadialSelectors()
        popup.swipeBack?.closeForeground(false)
        popup.measure(
            MeasureSpec.makeMeasureSpec(screen.width - AndroidUtilities.dp(40f), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(screen.height, MeasureSpec.AT_MOST),
        )

        val menu = header.parent as View
        val bar = checkNotNull(chatActivity.actionBar)
        val barBounds = Rect().also {
            bar.getDrawingRect(it)
            screen.offsetDescendantRectToMyCoords(bar, it)
        }
        val xOffset = `ActionBarMenuItem$xOffset`.invokeExact(header) as Int
        val yOffset = `ActionBarMenuItem$yOffset`.invokeExact(header) as Int

        addContent(
            popup,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                leftMargin = barBounds.left + menu.left + header.left + header.measuredWidth -
                    popup.measuredWidth + header.translationX.toInt() + xOffset
                topMargin = barBounds.bottom - bar.measuredHeight + menu.top + menu.paddingTop + yOffset
            }
        )
        this.menu = popup
        awaitGuideLayout(screen)
        ActionBarPopupWindow.startAnimation(popup).await()
    }

    private fun menuItem(text: String): View {
        val popup = checkNotNull(menu) { "Use OpenChatMenu first" }
        val visible = Rect()

        return checkNotNull(
            generateSequence(listOf<View>(popup)) { level ->
                level.flatMap { view -> (view as? ViewGroup)?.let { group -> List(group.childCount, group::getChildAt) }.orEmpty() }
                    .takeIf { it.isNotEmpty() }
            }
                .flatten()
                .filterIsInstance<ActionBarMenuSubItem>()
                .firstOrNull { it.textView.text.toString() == text && it.isShown && it.getGlobalVisibleRect(visible) }
        ) { "No visible menu item \"$text\"" }
    }

    suspend fun closeMenu() {
        val popup = menu ?: return

        menu = null
        animateGuide(MENU_DISMISS_DURATION, CubicBezierInterpolator.DEFAULT) { progress ->
            popup.alpha = 1f - progress
            popup.translationY = -AndroidUtilities.dp(5f) * progress
        }
        screen.removeView(popup)
    }

    override fun updateTheme() {
        if (!shown || disposed)
            return

        chatActivity.themeDescriptions?.forEach {
            it.setColor(Theme.getColor(it.currentKey), false, false)
        }

        menu?.invalidate()
        host.view.invalidate()
        invalidateTouch()
    }

    override fun onDispose() {
        chatActivity.historyCreated = {}
        chatActivity.historyViewCreated = {}
        host.dispose()
    }

    private companion object {
        const val SWIPE_BACK_DURATION = 240L

        const val MENU_DISMISS_DURATION = 150L
    }
}
