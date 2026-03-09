@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks

import android.app.Dialog
import android.content.Context
import android.view.View
import android.webkit.ValueCallback
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
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
private typealias UserResolver = java.util.function.Function<Long, Array<Any>?>
private typealias TranslationResolver = java.util.function.Function<String, String?>

class Plugin {
    @Suppress("unused")
    companion object {
        private var INSTANCE: Plugin? = null

        @JvmStatic
        fun inject(
            logger: Logger,
            userResolver: UserResolver,
            translationResolver: TranslationResolver
        ) {
            if (INSTANCE != null)
                return

            INSTANCE = Plugin(logger, userResolver, translationResolver)
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
    private val userResolver: UserResolver
    private val translationResolver: TranslationResolver

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
        userResolver: UserResolver,
        translationResolver: TranslationResolver
    ) {
        this.logger = logger
        this.userResolver = userResolver
        this.translationResolver = translationResolver
    }

    fun log(message: String) =
        logger.onReceiveValue(message)

    fun logException(message: String, exception: Exception) {
        logger.onReceiveValue(message)
        logger.onReceiveValue(exception.toString())
        logger.onReceiveValue(exception.stackTrace.joinToString("\n"))
    }

    fun resolveStreakData(userId: Long): StreakData? =
        this.userResolver.apply(userId)
            ?.let { StreakData.fromArray(it) }

    fun translate(key: String): String =
        translationResolver.apply(key) ?: key

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
                    } catch (e: Exception) {
                        logException("An exception occurred in $method before-call hook!", e)
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
                    } catch (e: Exception) {
                        logException("An exception occurred in $method after-call hook!", e)
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
