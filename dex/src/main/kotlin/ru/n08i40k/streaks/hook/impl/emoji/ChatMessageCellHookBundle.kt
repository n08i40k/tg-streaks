@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.hook.impl.emoji

import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Cells.DialogCell
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.addIntFieldValue
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.getFieldValue
import ru.n08i40k.streaks.util.setFieldValue
import java.lang.ref.WeakReference
import kotlin.math.ceil

class ChatMessageCellHookBundle : HookBundle() {
    private var savedInitialisationData: Pair<Int, WeakReference<ChatMessageCell>>? = null

    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
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

            val thisClass = ChatMessageCell::class.java

            val emoji = StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "currentNameStatusDrawable"),
                null,
                peerUserId,
                true,
                null,
            ) ?: return@before

            if (getFieldValue<Int>(thisClass, thisObject, "viaNameWidth") == 0) {
                addIntFieldValue(thisClass, thisObject, "nameWidth", emoji.getAdditionalWidth())

                thisObject.invalidate()
                return@before
            }

            val nameLayout = getFieldValue<StaticLayout>(thisClass, thisObject, "nameLayout")
                ?: return@before

            val spannedText = nameLayout.text as? Spanned ?: return@before
            val extraPx = emoji.getAdditionalWidth()

            spannedText.getSpans(
                0,
                spannedText.length,
                DialogCell.FixedWidthSpan::class.java
            ).lastOrNull()?.let {
                addIntFieldValue(it, "width", extraPx)
            } ?: return@before

            val nameLayoutWidth = getFieldValue<Int>(thisClass, thisObject, "nameLayoutWidth")!!

            val newLayout = StaticLayout(
                spannedText,
                Theme.chat_namePaint,
                nameLayoutWidth + extraPx + AndroidUtilities.dp(2f),
                Layout.Alignment.ALIGN_NORMAL,
                1.0f,
                0.0f,
                false
            )

            val newNameLayoutWidth = ceil(newLayout.getLineWidth(0)).toInt()

            setFieldValue(thisClass, thisObject, "nameLayout", newLayout)
            setFieldValue(thisClass, thisObject, "nameLayoutWidth", newNameLayoutWidth)
            addIntFieldValue(thisClass, thisObject, "viaWidth", extraPx)

            thisObject.invalidate()
        }
    }
}
