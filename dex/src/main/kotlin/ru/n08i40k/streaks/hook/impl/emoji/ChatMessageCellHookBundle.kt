@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.hook.impl.emoji

import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Cells.ChatMessageCell
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.getFieldValue
import java.lang.ref.WeakReference

class ChatMessageCellHookBundle : HookBundle() {
    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        // Сообщение в группе
        after(
            ChatMessageCell::class.java.getDeclaredMethod(
                "setMessageObjectInternal",
                MessageObject::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as ChatMessageCell
            val thisClass = ChatMessageCell::class.java

            val currentUser = getFieldValue<TLRPC.User>(thisClass, thisObject, "currentUser")
                ?: return@after

            StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "currentNameStatusDrawable"),
                null,
                currentUser.id,
                true,
                null,
                StreakEmoji.Parent.MessageCell(WeakReference(thisObject))
            )
        }

        // Исправление размера сообщения в чате
        after(
            ChatMessageCell::class.java.getDeclaredMethod(
                "setMessageContent",
                MessageObject::class.java,
                MessageObject.GroupedMessages::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Boolean::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as ChatMessageCell
            val thisClass = ChatMessageCell::class.java

            val streakEmoji = getFieldValue<StreakEmoji>(
                thisClass,
                thisObject,
                "currentNameStatusDrawable"
            ) ?: return@after

            Plugin.getInstance().chatMessageCellWidthCache
                .changeIfNeeded(thisObject, streakEmoji.getAdditionalWidth())
        }
    }
}