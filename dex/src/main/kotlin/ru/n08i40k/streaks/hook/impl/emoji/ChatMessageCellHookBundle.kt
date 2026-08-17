package ru.n08i40k.streaks.hook.impl.emoji

import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.MessageObject
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Cells.DialogCell
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.addInt
import ru.n08i40k.streaks.util.getAs
import ru.n08i40k.streaks.util.getAsUnchecked
import ru.n08i40k.streaks.util.getField
import java.lang.ref.WeakReference
import kotlin.math.ceil

class ChatMessageCellHookBundle : HookBundle() {
    companion object Fields {
        private val CLASS = ChatMessageCell::class.java

        // ChatMessageCell
        val CURRENT_NAME_STATUS_DRAWABLE = getField(CLASS, "currentNameStatusDrawable")
        val VIA_WIDTH = getField(CLASS, "viaWidth")
        val VIA_NAME_WIDTH = getField(CLASS, "viaNameWidth")
        val NAME_WIDTH = getField(CLASS, "nameWidth")
        val NAME_LAYOUT = getField(CLASS, "nameLayout")
        val NAME_LAYOUT_WIDTH = getField(CLASS, "nameLayoutWidth")

        // FixedWidthSpan
        val WIDTH = getField(DialogCell.FixedWidthSpan::class.java, "width")

        // Theme
        val CHAT_NAME_PAINT = getField(Theme::class.java, "chat_namePaint")
    }

    private var savedInitialisationData: Pair<Int, WeakReference<ChatMessageCell>>? = null

    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        val streaksController = Plugin.getInstance().streaksController

        // Сообщение в группе
        before(
            ChatMessageCell::class.java.getDeclaredMethod(
                "setMessageObjectInternal",
                MessageObject::class.java
            )
        ) { param ->
            val messageObject = param.args[0] as? MessageObject
                ?: return@before

            if (messageObject.isOut || !messageObject.isFromUser)
                return@before

            savedInitialisationData = Pair(
                System.identityHashCode(messageObject),
                WeakReference(param.thisObject as ChatMessageCell)
            )
        }

        before(
            MessageObject::class.java.getDeclaredMethod(
                "isForwarded"
            )
        ) { param ->
            val (savedId, savedCellRef) = savedInitialisationData ?: return@before
            val messageObject = param.thisObject as? MessageObject ?: return@before

            if (System.identityHashCode(messageObject) != savedId)
                return@before

            savedInitialisationData = null

            // here
            val peerUserId =
                if (messageObject.isFromUser)
                    messageObject.messageOwner.from_id.user_id
                else
                    return@before

            val thisObject = savedCellRef.get()
                ?: return@before

            val emoji = StreakEmoji.encapsulate(
                thisObject,
                CURRENT_NAME_STATUS_DRAWABLE,
                null,
                peerUserId,
                badgeSlot = StreakEmoji.BadgeSlot.STATUS_OR_NAME,
                simpleTextView = null,
            ) ?: return@before

            if (VIA_NAME_WIDTH.getInt(thisObject) == 0) {
                NAME_WIDTH.addInt(thisObject, emoji.getAdditionalWidth())

                thisObject.invalidate()
                return@before
            }

            val nameLayout = NAME_LAYOUT.getAs<StaticLayout>(thisObject)
                ?: return@before

            val spannedText = nameLayout.text as? Spanned ?: return@before
            val extraPx = emoji.getAdditionalWidth()

            spannedText.getSpans(0, spannedText.length, DialogCell.FixedWidthSpan::class.java)
                .lastOrNull()
                ?.let { WIDTH.addInt(it, extraPx) }
                ?: return@before

            val nameLayoutWidth = NAME_LAYOUT_WIDTH.getInt(thisObject)

            val newLayout = StaticLayout(
                spannedText,
                CHAT_NAME_PAINT.getAsUnchecked<TextPaint>(null),
                nameLayoutWidth + extraPx + dp(2f),
                Layout.Alignment.ALIGN_NORMAL,
                1.0f,
                0.0f,
                false
            )

            val newNameLayoutWidth = ceil(newLayout.getLineWidth(0)).toInt()

            NAME_LAYOUT.set(thisObject, newLayout)
            NAME_LAYOUT_WIDTH.set(thisObject, newNameLayoutWidth)
            VIA_WIDTH.set(thisObject, extraPx)

            thisObject.invalidate()
        }

        before(
            Class.forName($$"org.telegram.ui.ChatActivity$ChatMessageCellDelegate")
                .getDeclaredMethod(
                    "didPressUserStatus",
                    ChatMessageCell::class.java,
                    TLRPC.User::class.java,
                    TLRPC.Document::class.java,
                    String::class.java,
                )
        ) { param ->
            streaksController.getViewData(
                UserConfig.selectedAccount,
                (param.args[1] as TLRPC.User).id
            ) ?: return@before

            param.args[3] = ""
        }
    }
}
