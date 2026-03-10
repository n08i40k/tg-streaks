@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.webkit.ValueCallback
import androidx.collection.LongSparseArray
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatActionCell
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.Cells.UserCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.ChatAvatarContainer
import org.telegram.ui.Components.Premium.PremiumPreviewBottomSheet
import org.telegram.ui.DialogsActivity
import org.telegram.ui.ProfileActivity
import org.telegram.ui.Stars.StarsReactionsSheet
import ru.n08i40k.streaks.data.StreakData
import ru.n08i40k.streaks.overrides.StreakAnimatedEmojiDrawable
import ru.n08i40k.streaks.overrides.StreakBottomSheet
import ru.n08i40k.streaks.overrides.StreakParticles
import java.lang.reflect.Member

private typealias Logger = ValueCallback<String>
private typealias StreakResolver = java.util.function.Function<Long, Array<Any>?>
private typealias TranslationResolver = java.util.function.Function<String, String?>
private typealias ReviveResolver = java.util.function.Function<Long, Boolean>

class Plugin {
    @Suppress("unused")
    companion object {
        private var INSTANCE: Plugin? = null

        @JvmStatic
        fun inject(
            logger: Logger,
            streakResolver: StreakResolver,
            translationResolver: TranslationResolver,
            reviveResolver: ReviveResolver
        ) {
            if (INSTANCE != null)
                return

            INSTANCE = Plugin(logger, streakResolver, translationResolver, reviveResolver)
            INSTANCE!!.onInject()
        }

        @JvmStatic
        fun eject() {
            INSTANCE?.onEject()
            INSTANCE = null
        }

        @JvmStatic
        fun getBuildDate(): String = Integer.toHexString(BuildConfig.BUILD_TIME.hashCode())

        @JvmStatic
        fun getInstance(): Plugin? = INSTANCE

        @JvmStatic
        fun clearCaches() {
            INSTANCE?.streakDrawableEjectData?.forEach { it.drawable.get()?.resetCache() }
        }

        @JvmStatic
        fun enableParticles(
            drawable: AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable,
            color: Int
        ) {
            drawable.setParticles(true, true)

            val field =
                getField(AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable::class.java, "particles")
            val particles = field.get(drawable)!! as StarsReactionsSheet.Particles

            field.set(drawable, StreakParticles(particles, color))
        }
    }

    private val logger: Logger
    private val streakResolver: StreakResolver
    private val translationResolver: TranslationResolver
    private val reviveResolver: ReviveResolver

    private val upgradeMessageRegex = Regex("^tg-streaks:upgrade:(\\d+)$")
    private val deathMessageText = "tg-streaks:death"
    private val restoreMessageText = "tg-streaks:restore"

    private var hooks: ArrayList<XC_MethodHook.Unhook> = arrayListOf()
    private var streakDrawableEjectData: ArrayList<StreakAnimatedEmojiDrawable.EjectData> =
        arrayListOf()

    private val chatMessageCellWidthCache = object : LinkedHashMap<Int, Int>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, Int>): Boolean {
            return size > 32
        }

        fun sameWidth(hash: Int, width: Int): Boolean {
            return this[hash] == width
        }

        fun push(hash: Int, width: Int) {
            this[hash] = width
        }
    }

    constructor(
        logger: Logger,
        streakResolver: StreakResolver,
        translationResolver: TranslationResolver,
        reviveResolver: ReviveResolver
    ) {
        this.logger = logger
        this.streakResolver = streakResolver
        this.translationResolver = translationResolver
        this.reviveResolver = reviveResolver
    }

    fun log(message: String) =
        logger.onReceiveValue(message)

    fun logException(message: String, exception: Exception) {
        logger.onReceiveValue(message)
        logger.onReceiveValue(exception.toString())
        logger.onReceiveValue(exception.stackTrace.joinToString("\n"))
    }

    fun resolveStreakData(userId: Long): StreakData? =
        this.streakResolver.apply(userId)
            ?.let { StreakData.fromArray(it) }

    fun translate(key: String): String =
        translationResolver.apply(key) ?: key

    fun reviveStreak(dialogId: Long): Boolean =
        reviveResolver.apply(dialogId)

    fun addStreakDrawableEjectData(ejectData: StreakAnimatedEmojiDrawable.EjectData) =
        streakDrawableEjectData.add(ejectData)

    private fun onInject() {
        try {
            hookMethods()
        } catch (e: Exception) {
            logException("Failed to hook methods!", e)
            onEject()
        }

        log("Injected!")
    }

    private fun onEject() {
        try {
            hooks.forEach { it.unhook() }
            hooks.clear()
        } catch (e: Exception) {
            logException("Failed to unhook methods!", e)
        }

        try {
            streakDrawableEjectData.forEach { it.restore() }
            streakDrawableEjectData.clear()
        } catch (e: Exception) {
            logException("Failed to restore original SwapAnimatedEmojiDrawable!", e)
        }

        log("Ejected!")
    }

    private fun hookMethod(method: Member, hook: XC_MethodHook) {
        hooks.add(XposedBridge.hookMethod(method, hook))
    }

    private fun hookBefore(method: Member, callback: (XC_MethodHook.MethodHookParam) -> Unit) {
        hookMethod(
            method,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        callback(param)
                    } catch (e: Throwable) {
                        logException(
                            "An exception occurred in $method before-call hook!",
                            e as? Exception ?: Exception(e)
                        )
                        eject()
                    }
                }
            }
        )
    }

    private fun hookAfter(method: Member, callback: (XC_MethodHook.MethodHookParam) -> Unit) {
        hookMethod(
            method,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        callback(param)
                    } catch (e: Throwable) {
                        logException(
                            "An exception occurred in $method after-call hook!",
                            e as? Exception ?: Exception(e)
                        )
                        eject()
                    }
                }
            }
        )
    }

    private fun hookMethods() {
        // Чат в списке, нужно ещё увеличить bounds по x, иначе текста не будет
        hookAfter(
            DialogCell::class.java.getConstructor(
                DialogsActivity::class.java,
                Context::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Int::class.java,
                Theme.ResourcesProvider::class.java
            )
        )
        { param ->
            val thisObject = param.thisObject as DialogCell
            val thisClass = DialogCell::class.java

            StreakAnimatedEmojiDrawable.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatus"),
                null,
                0,
                true
            )
        }


        // Конструктор чата в списке не имеет его в качестве аргумента, он задаётся после
        hookAfter(
            DialogCell::class.java.getDeclaredMethod(
                "buildLayout",
            )
        ) { param ->
            val thisObject = param.thisObject as DialogCell
            val thisClass = DialogCell::class.java

            val currentDialogId =
                getFieldValue<Long>(thisClass, thisObject, "currentDialogId")!!

            getFieldValue<StreakAnimatedEmojiDrawable>(
                thisClass,
                thisObject,
                "emojiStatus"
            )?.setUserId(currentDialogId)
        }

        // Фикс отрисовки текста в местах, где размер view ограничен по x.
        // Например, в списке чатов, где у SwapAnimatedEmojiDrawable есть обёртка в виде View,
        // который жёстко ограничен по x.
        hookAfter(
            DialogCell::class.java.getDeclaredMethod(
                "onLayout",
                Boolean::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
            )
        ) { param ->
            val thisObject = param.thisObject as DialogCell
            val thisClass = DialogCell::class.java

            val emojiStatusView =
                getFieldValue<View>(thisClass, thisObject, "emojiStatusView")!!

            val height = AndroidUtilities.dp(22f)
            emojiStatusView.layout(0, 0, height * 3, height)
        }

        // Сообщение в группе
        hookAfter(
            ChatMessageCell::class.java.getDeclaredMethod(
                "setMessageObjectInternal",
                MessageObject::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as ChatMessageCell
            val thisClass = ChatMessageCell::class.java

            val currentUser =
                getFieldValue<TLRPC.User>(thisClass, thisObject, "currentUser")
                    ?: return@hookAfter

            StreakAnimatedEmojiDrawable.encapsulate(
                thisObject,
                getField(thisClass, "currentNameStatusDrawable"),
                null,
                currentUser.id,
                true
            )
        }

        // каким блять хуем я не могу кастануть child в parent?
        @Suppress("CAST_NEVER_SUCCEEDS")
        hookBefore(
            MessageObject::class.java.getDeclaredConstructor(
                Int::class.java,
                TLRPC.Message::class.java,
                MessageObject::class.java,
                java.util.AbstractMap::class.java,
                java.util.AbstractMap::class.java,
                LongSparseArray::class.java,
                LongSparseArray::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Long::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Int::class.java
            )

        ) { param ->
            val message = param.args[1] as? TLRPC.Message ?: return@hookBefore
            val currentAccount = param.args[0] as? Int ?: 0

            if (message.message == null)
                return@hookBefore

            val tryStreakUpgrade = streakUpgrade@{
                val days = upgradeMessageRegex
                    .matchEntire(message.message)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 } ?: return@streakUpgrade null

                TLRPC.TL_messageActionCustomAction().apply {
                    val messageText = translate("service_message_upgrade_text")
                        .replace("{days}", days.toString())

                    (this as TLRPC.MessageAction).message = messageText
                }
            }

            val tryStreakDeath = streakDeath@{
                if (message.message != deathMessageText)
                    return@streakDeath null

                TLRPC.TL_messageActionPrizeStars().apply {
                    boost_peer = message.peer_id
                    flags = 0
                    giveaway_msg_id = 0
                    stars = 0
                    transaction_id = deathMessageText
                    unclaimed = false
                }
            }

            val tryStreakRestore = streakRestore@{
                if (message.message != restoreMessageText)
                    return@streakRestore null

                val peerId = message.peer_id?.user_id
                val fromId = message.from_id?.user_id

                val restoredByPeer =
                    peerId != null && fromId != null && peerId > 0 && fromId == peerId

                val messageText =
                    if (!restoredByPeer) {
                        translate("service_message_restore_text_self")
                    } else {
                        val peerName =
                            peerId
                                .takeIf { it > 0 }
                                ?.let { MessagesController.getInstance(currentAccount).getUser(it) }
                                ?.let { UserObject.getUserName(it) }
                                ?.takeIf { it.isNotBlank() }
                                ?: "Unknown"

                        translate("service_message_restore_text_peer")
                            .replace("{name}", peerName)
                    }

                TLRPC.TL_messageActionCustomAction().apply {
                    (this as TLRPC.MessageAction).message = messageText
                }
            }

            val action: TLRPC.MessageAction =
                (tryStreakUpgrade() as? TLRPC.MessageAction)
                    ?: (tryStreakDeath() as? TLRPC.MessageAction)
                    ?: (tryStreakRestore() as? TLRPC.MessageAction)
                    ?: return@hookBefore

            param.args[1] = TLRPC.TL_messageService().apply {
                cloneFields(message as Object, this as Object, TLRPC.Message::class.java)

                (this as TLRPC.Message).action = action
                (this as TLRPC.Message).message = null
            }
        }

        @Suppress("CAST_NEVER_SUCCEEDS")
        hookBefore(
            ChatActionCell::class.java.getDeclaredMethod(
                "openStarsGiftTransaction",
            )
        ) { param ->
            val messageObject = getFieldValue<MessageObject>(
                ChatActionCell::class.java,
                param.thisObject,
                "currentMessageObject"
            ) ?: return@hookBefore

            val prizeStars =
                messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                    ?: return@hookBefore

            if (prizeStars.transaction_id != deathMessageText)
                return@hookBefore

            this@Plugin.reviveStreak(messageObject.dialogId)

            param.result = null
        }

        @Suppress("CAST_NEVER_SUCCEEDS")
        hookBefore(
            ChatActionCell::class.java.getDeclaredMethod(
                "createGiftPremiumLayouts",
                CharSequence::class.java,
                CharSequence::class.java,
                CharSequence::class.java,
                CharSequence::class.java,
                Boolean::class.java,
                CharSequence::class.java,
                Int::class.java,
                CharSequence::class.java,
                Int::class.java,
                Boolean::class.java,
                Boolean::class.java
            )
        ) { param ->
            val messageObject = getFieldValue<MessageObject>(
                ChatActionCell::class.java,
                param.thisObject,
                "currentMessageObject"
            ) ?: return@hookBefore

            val prizeStars =
                messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                    ?: return@hookBefore

            if (prizeStars.transaction_id != deathMessageText)
                return@hookBefore

            param.args[0] = translate("service_message_death_title")
            param.args[1] = translate("service_message_death_subtitle")
            param.args[3] = translate("service_message_death_hint")
            param.args[5] = translate("service_message_death_button")
            param.args[9] = false
            param.args[10] = true
        }

        @Suppress("CAST_NEVER_SUCCEEDS")
        hookAfter(
            ChatActionCell::class.java.getDeclaredMethod(
                "isNewStyleButtonLayout",
            )
        ) { param ->
            val messageObject = getFieldValue<MessageObject>(
                ChatActionCell::class.java,
                param.thisObject,
                "currentMessageObject"
            ) ?: return@hookAfter

            val prizeStars =
                messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                    ?: return@hookAfter

            if (prizeStars.transaction_id != deathMessageText)
                return@hookAfter

            param.result = true
        }

        @Suppress("CAST_NEVER_SUCCEEDS")
        hookAfter(
            ChatActionCell::class.java.getDeclaredMethod(
                "getImageSize",
                MessageObject::class.java
            )
        ) { param ->
            val messageObject = getFieldValue<MessageObject>(
                ChatActionCell::class.java,
                param.thisObject,
                "currentMessageObject"
            ) ?: return@hookAfter

            val prizeStars =
                messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                    ?: return@hookAfter

            if (prizeStars.transaction_id != deathMessageText)
                return@hookAfter

            param.result = -AndroidUtilities.dp(19.5f)
        }

        @Suppress("CAST_NEVER_SUCCEEDS")
        hookAfter(
            ChatActionCell::class.java.getDeclaredMethod(
                "setMessageObject",
                MessageObject::class.java,
                Boolean::class.java,
            )
        ) { param ->
            val messageObject = param.args[0] as? MessageObject ?: return@hookAfter

            val prizeStars =
                messageObject.messageOwner?.action as? TLRPC.TL_messageActionPrizeStars
                    ?: return@hookAfter

            if (prizeStars.transaction_id != deathMessageText)
                return@hookAfter

            val thisObject = param.thisObject as ChatActionCell
            val thisClass = ChatActionCell::class.java

            val imageReceiver =
                getFieldValue<ImageReceiver>(thisClass, thisObject, "imageReceiver")!!
            imageReceiver.setAllowStartLottieAnimation(false)
            imageReceiver.setDelegate(null)
            imageReceiver.setImageBitmap(null as Bitmap?)
            imageReceiver.clearImage()
            imageReceiver.clearDecorators()
            imageReceiver.setVisible(false, true)
        }

        hookAfter(
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

            val streakEmoji = getFieldValue<StreakAnimatedEmojiDrawable>(
                thisClass,
                thisObject,
                "currentNameStatusDrawable"
            ) ?: return@hookAfter

            val hash = System.identityHashCode(thisObject)

            if (!chatMessageCellWidthCache.sameWidth(hash, thisObject.backgroundWidth)) {
                thisObject.backgroundWidth += streakEmoji.getAdditionalWidth()
                chatMessageCellWidthCache.push(hash, thisObject.backgroundWidth)
            }
        }

        // Пользователь в списке участников группы
        hookAfter(
            UserCell::class.java.getDeclaredMethod(
                "update",
                Int::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as UserCell
            val thisClass = UserCell::class.java

            val dialogId = getFieldValue<Long>(thisClass, thisObject, "dialogId")!!

            if (dialogId < 0)
                return@hookAfter

            StreakAnimatedEmojiDrawable.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatus"),
                null,
                dialogId,
                hideParticlesOnCollectibles = true
            )
        }

        // Профиль пользователя
        hookAfter(
            ProfileActivity::class.java.getDeclaredMethod(
                "getEmojiStatusDrawable",
                TLRPC.EmojiStatus::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Int::class.java
            )
        ) { param ->
            val thisObject = param.thisObject as ProfileActivity
            val thisClass = ProfileActivity::class.java

            val userId = getFieldValue<Long>(thisClass, thisObject, "userId")!!

            if (userId < 0)
                return@hookAfter

            StreakAnimatedEmojiDrawable.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatusDrawable"),
                param.args[3] as Int,
                userId
            )
        }

        // Заголовок открытого лс с пользователем
        hookAfter(
            ChatAvatarContainer::class.java
                .getDeclaredMethods()
                .filter { it.name == "setTitle" }
                .maxByOrNull { it.parameterCount }!!
        ) { param ->
            val thisObject = param.thisObject as ChatAvatarContainer
            val thisClass = ChatAvatarContainer::class.java

            val dialogId = getFieldValue<ChatActivity>(
                thisClass,
                thisObject,
                "parentFragment"
            )?.dialogId ?: return@hookAfter

            if (dialogId < 0)
                return@hookAfter

            StreakAnimatedEmojiDrawable.encapsulate(
                thisObject,
                getField(thisClass, "emojiStatusDrawable"),
                null,
                dialogId
            )
        }

        // Хук отображения диалоговых окон для замены PremiumPreviewBottomSheet
        hookBefore(
            BaseFragment::class.java.getDeclaredMethod("showDialog", Dialog::class.java)
        ) { param ->
            val dialog = param.args[0] as? PremiumPreviewBottomSheet ?: return@hookBefore
            val user = getFieldValue<TLRPC.User>(
                PremiumPreviewBottomSheet::class.java,
                dialog,
                "user"
            )!!

            val streakData = this@Plugin.resolveStreakData(user.id) ?: return@hookBefore

            param.args[0] = StreakBottomSheet(dialog, user, streakData)
        }
    }
}
