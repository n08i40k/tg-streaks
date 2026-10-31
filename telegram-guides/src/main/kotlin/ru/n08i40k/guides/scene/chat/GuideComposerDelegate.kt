package ru.n08i40k.guides.scene.chat

import android.view.View
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
}
