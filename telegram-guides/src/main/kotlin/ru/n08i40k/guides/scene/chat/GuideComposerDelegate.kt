package ru.n08i40k.guides.scene.chat

import android.view.View
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_stories
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ChatActivityEnterView

internal class GuideComposerDelegate : ChatActivityEnterView.ChatActivityEnterViewDelegate {
    override fun didPressAttachButton() = Unit

    override fun isVideoRecordingPaused() =
        false

    override fun needChangeVideoPreviewState(state: Int, value: Float) = Unit

    override fun needSendTyping() = Unit

    override fun needShowMediaBanHint() = Unit

    override fun needStartRecordAudio(state: Int) = Unit

    override fun needStartRecordVideo(
        state: Int,
        notify: Boolean,
        scheduleDate: Int,
        ttl: Int,
        effectId: Int,
        stars: Long,
        extra: Long
    ) = Unit

    override fun onAttachButtonHidden() = Unit

    override fun onAttachButtonShow() = Unit

    override fun onAudioVideoInterfaceUpdated() = Unit

    override fun onMessageEditEnd(loading: Boolean) = Unit

    override fun onMessageSend(
        text: CharSequence?,
        notify: Boolean,
        scheduleDate: Int,
        effectId: Int,
        stars: Long
    ) = Unit

    override fun onPreAudioVideoRecord() = Unit

    override fun onSendLongClick() = Unit

    override fun onStickersExpandedChange() = Unit

    override fun onStickersTab(opened: Boolean) = Unit

    override fun onSwitchRecordMode(video: Boolean) = Unit

    override fun onTextChanged(text: CharSequence?, bigChange: Boolean, fromDraft: Boolean) = Unit

    override fun onTextSelectionChanged(start: Int, end: Int) = Unit

    override fun onTextSpansChanged(text: CharSequence?) = Unit

    override fun onUpdateSlowModeButton(button: View?, show: Boolean, text: CharSequence?) = Unit

    override fun onWindowSizeChanged(size: Int) = Unit

    override fun toggleVideoRecordingPause() = Unit

    override fun bottomPanelTranslationYChanged(translation: Float) = Unit

    override fun checkCanRemoveRestrictionsByBoosts() =
        false

    override fun didPressSuggestionButton() = Unit

    override fun getContentViewHeight() =
        0

    override fun getReplyQuote(): ChatActivity.ReplyQuote? =
        null

    override fun getReplyToStory(): TL_stories.StoryItem? =
        null

    override fun getSendAsPeers(): TLRPC.TL_channels_sendAsPeers? =
        null

    override fun hasForwardingMessages() =
        false

    override fun hasScheduledMessages() =
        false

    override fun measureKeyboardHeight() =
        0

    override fun onContextMenuClose() = Unit

    override fun onContextMenuOpen() = Unit

    override fun onEditTextScroll() = Unit

    override fun onKeyboardRequested() = Unit

    override fun onTrendingStickersShowed(show: Boolean) = Unit

    override fun onceVoiceAvailable() =
        false

    override fun openScheduledMessages() = Unit

    override fun prepareMessageSending() = Unit

    override fun scrollToSendingMessage() = Unit

    override fun setFrontface(front: Boolean) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun needStartRecordVideo(state: Int, notify: Boolean, scheduleDate: Int, ttl: Int, effectId: Long, stars: Long) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onMessageSend(text: CharSequence?, notify: Boolean, scheduleDate: Int, effectId: Long) = Unit
}
