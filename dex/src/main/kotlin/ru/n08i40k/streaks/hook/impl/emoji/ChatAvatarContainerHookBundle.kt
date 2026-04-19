@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.hook.impl.emoji

import kotlinx.coroutines.launch
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.ChatAvatarContainer
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.override.StreakEmoji
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.getFieldValue

class ChatAvatarContainerHookBundle : HookBundle() {
    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        // Заголовок открытого лс с пользователем
        after(
            ChatAvatarContainer::class.java
                .getDeclaredMethods()
                .filter { it.name == "setTitle" }
                .maxByOrNull { it.parameterCount }!!
        ) { param ->
            val thisObject = param.thisObject as ChatAvatarContainer
            val thisClass = ChatAvatarContainer::class.java

            val dialogId =
                getFieldValue<ChatActivity>(thisClass, thisObject, "parentFragment")?.dialogId
                    ?: return@after

            if (dialogId < 0)
                return@after

            val titleTextView =
                getFieldValue<SimpleTextView>(thisClass, thisObject, "titleTextView")
                    ?: return@after

            val newDrawable = StreakEmoji.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatusDrawable"),
                null,
                dialogId
            ) ?: return@after

            if (titleTextView.rightDrawable !== newDrawable && titleTextView.rightDrawable is AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable)
                titleTextView.rightDrawable = newDrawable

            Plugin.getInstance().apply {
                backgroundScope.launch { streaksController.flushCurrentChatPopup() }
            }
        }
    }
}