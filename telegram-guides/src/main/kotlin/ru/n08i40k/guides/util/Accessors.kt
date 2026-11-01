@file:Suppress("ObjectPropertyName", "HasPlatformType")

package ru.n08i40k.guides.util

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.Cells.ChatActionCell
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.DialogsActivity
import org.telegram.ui.ProfileActivity

@JvmField
internal val `ChatActivity$paused` =
    getFieldGetter(ChatActivity::class.java, "paused")

@JvmField
internal val `ChatActivity$paused$$setter` =
    getFieldSetter(ChatActivity::class.java, "paused")

@JvmField
internal val `ChatActivity$loading$$setter` =
    getFieldSetter(ChatActivity::class.java, "loading")

@JvmField
internal val `ChatActivity$loadingForward$$setter` =
    getFieldSetter(ChatActivity::class.java, "loadingForward")

@JvmField
internal val `ChatActivity$firstLoading$$setter` =
    getFieldSetter(ChatActivity::class.java, "firstLoading")

@JvmField
internal val `ChatActivity$firstMessagesLoaded$$setter` =
    getFieldSetter(ChatActivity::class.java, "firstMessagesLoaded")

@JvmField
internal val `ChatActivity$endReached` =
    getFieldGetter(ChatActivity::class.java, "endReached")

@JvmField
internal val `ChatActivity$cacheEndReached` =
    getFieldGetter(ChatActivity::class.java, "cacheEndReached")

@JvmField
internal val `ChatActivity$forwardEndReached` =
    getFieldGetter(ChatActivity::class.java, "forwardEndReached")

@JvmField
internal val `ChatActivity$pinnedMessageIds` =
    getFieldGetter(ChatActivity::class.java, "pinnedMessageIds")

@JvmField
internal val `ChatActivity$pinnedMessageObjects` =
    getFieldGetter(ChatActivity::class.java, "pinnedMessageObjects")

@JvmField
internal val `ChatActivity$currentPinnedMessageId$$setter` =
    getFieldSetter(ChatActivity::class.java, "currentPinnedMessageId")

@JvmField
internal val `ChatActivity$pinnedEndReached$$setter` =
    getFieldSetter(ChatActivity::class.java, "pinnedEndReached")

@JvmField
internal val `ChatActivity$messagesDict` =
    getFieldGetter(ChatActivity::class.java, "messagesDict")

@JvmField
internal val `ChatActivity$messagesByDays` =
    getFieldGetter(ChatActivity::class.java, "messagesByDays")

@JvmField
internal val `ChatActivity$messagesByDaysSorted` =
    getFieldGetter(ChatActivity::class.java, "messagesByDaysSorted")

@JvmField
internal val `ChatActivity$chatAdapter` =
    getFieldGetter(ChatActivity::class.java, "chatAdapter")

@JvmField
internal val `ChatActivity$chatListView` =
    getFieldGetter(ChatActivity::class.java, "chatListView")
        .retype(RecyclerView::class.java, ChatActivity::class.java)

@JvmField
internal val `ChatActivity$headerItem` =
    getFieldGetter(ChatActivity::class.java, "headerItem")

@JvmField
internal val `ChatActivity$pluginsMenuItem` =
    getFieldGetter(ChatActivity::class.java, "pluginsMenuItem")

@JvmField
internal val `ChatActivity$processNewMessages` =
    getMethodHandle(ChatActivity::class.java, "processNewMessages", ArrayList::class.java)

@JvmField
internal val `ChatActivity$getStableIdForDateObject` =
    getMethodHandle(ChatActivity::class.java, "getStableIdForDateObject", Int::class.javaPrimitiveType!!)

@JvmField
internal val `ActionBarMenuItem$xOffset` =
    getFieldGetter(ActionBarMenuItem::class.java, "xOffset")

@JvmField
internal val `ActionBarMenuItem$yOffset` =
    getFieldGetter(ActionBarMenuItem::class.java, "yOffset")

@JvmField
internal val `ActionBarMenuItem$layoutLazyItems` =
    getMethodHandle(ActionBarMenuItem::class.java, "layoutLazyItems")

@JvmField
internal val `ActionBarMenuItem$Item$view` =
    getFieldGetter(ActionBarMenuItem.Item::class.java, "view")

@JvmField
internal val `ChatActionCell$giftButtonRect` =
    getFieldGetter(ChatActionCell::class.java, "giftButtonRect")

@JvmField
internal val `ProfileActivity$nameTextView` =
    getFieldGetter(ProfileActivity::class.java, "nameTextView")

@JvmField
internal val `DialogsActivity$viewPages` =
    getFieldGetter(DialogsActivity::class.java, "viewPages")

@JvmField
internal val `DialogsActivity$canShowFilterTabsView$$setter` =
    getFieldSetter(DialogsActivity::class.java, "canShowFilterTabsView")

@JvmField
internal val `DialogsActivity$animatorFilterTabsVisible` =
    getFieldGetterOrNull(DialogsActivity::class.java, "animatorFilterTabsVisible")

@JvmField
internal val `DialogsActivity$hasMainTabs$$setter` =
    getFieldSetterOrNull(DialogsActivity::class.java, "hasMainTabs")

@JvmField
internal val `DialogsActivity$accountPanels` = listOf(
    "filterTabsView",
    "authHintCell",
    "activeGiftAuctionsHintCell",
    "dialogsHintCell",
    "databaseMigrationHint",
    "animatedStatusView",
    "storyHint",
    "storyPremiumHint",
).mapNotNull {
    getFieldGetterOrNull(DialogsActivity::class.java, it)
        ?.retype(View::class.java, DialogsActivity::class.java)
}

@JvmField
internal val `DialogsActivity$ViewPage$itemTouchhelper` =
    getFieldGetter(DialogsActivity.ViewPage::class.java, "itemTouchhelper")

@JvmField
internal val `DialogsActivity$ViewPage$dialogsAdapter$$setter` =
    getFieldSetter(DialogsActivity.ViewPage::class.java, "dialogsAdapter")

@JvmField
internal val `DialogsActivity$ViewPage$progressView` =
    getFieldGetter(DialogsActivity.ViewPage::class.java, "progressView")
        .retype(View::class.java, DialogsActivity.ViewPage::class.java)

@JvmField
internal val `DialogCell$customDialog$$setter` =
    getFieldSetter(DialogCell::class.java, "customDialog")

@JvmField
internal val `DialogCell$currentDialogId$$setter` =
    getFieldSetter(DialogCell::class.java, "currentDialogId")

@JvmField
internal val `DialogCell$unreadCount$$setter` =
    getFieldSetter(DialogCell::class.java, "unreadCount")

@JvmField
internal val `DialogCell$drawPin$$setter` =
    getFieldSetter(DialogCell::class.java, "drawPin")

@JvmField
internal val `DialogCell$dialogMuted$$setter` =
    getFieldSetter(DialogCell::class.java, "dialogMuted")

@JvmField
internal val `RecyclerView$mScrollListeners` =
    getFieldGetter(RecyclerView::class.java, "mScrollListeners")

internal fun RecyclerView.removeAllScrollListeners() {
    (`RecyclerView$mScrollListeners`.invokeExact(this) as List<*>?)
        ?.toList()
        ?.forEach { removeOnScrollListener(it as RecyclerView.OnScrollListener) }
}

@JvmField
internal val `ActionBar$onPause` =
    getMethodHandle(ActionBar::class.java, "onPause")

internal fun ActionBar.invokeOnPause() {
    `ActionBar$onPause`.invokeExact(this)
}

@JvmField
internal val `DialogsActivity$getListView` =
    getMethodHandle(DialogsActivity::class.java, "getListView")

internal val DialogsActivity.dialogsListView: RecyclerListView
    get() = `DialogsActivity$getListView`.invokeExact(this) as RecyclerListView

@JvmField
internal val `BottomSheet$cancelSheetAnimation` =
    getMethodHandle(BottomSheet::class.java, "cancelSheetAnimation")

internal fun BottomSheet.invokeCancelSheetAnimation() {
    `BottomSheet$cancelSheetAnimation`.invokeExact(this)
}
