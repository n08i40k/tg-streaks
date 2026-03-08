@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.ValueCallback
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.SimpleTextView
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

class Plugin {
    @Suppress("unused")
    companion object {
        private var INSTANCE: Plugin? = null

        @JvmStatic
        fun inject(
            logger: ValueCallback<String>,
            userResolver: java.util.function.Function<Long, Array<Any>?>,
            translationResolver: java.util.function.Function<String, String>
        ) {
            if (INSTANCE == null)
                INSTANCE = Plugin()
            else
                return

            INSTANCE!!.logger = logger
            INSTANCE!!.userResolver = userResolver
            INSTANCE!!.translationResolver = translationResolver
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
            if (INSTANCE == null)
                return

            INSTANCE!!.streakDrawableEjectData.forEach { it.drawable.get()?.resetCache() }
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

    private sealed class DialogHookAction {
        class Disabled : DialogHookAction()
        class PremiumPreviewBottomSheet : DialogHookAction()
    }

    private lateinit var logger: ValueCallback<String>
    private lateinit var userResolver: java.util.function.Function<Long, Array<Any>?>
    private lateinit var translationResolver: java.util.function.Function<String, String>

    private var hooks: ArrayList<XC_MethodHook.Unhook> = arrayListOf()
    private var streakDrawableEjectData: ArrayList<StreakAnimatedEmojiDrawable.EjectData> =
        arrayListOf()
    private var dialogHookAction: DialogHookAction =
        DialogHookAction.Disabled()

    // TODO: somehow unhook method from hook itself
    private var profileActivityLambdaHooked = false

    fun log(message: String) {
        logger.onReceiveValue(message)
    }

    fun logException(message: String, exception: Exception) {
        logger.onReceiveValue(message)
        logger.onReceiveValue(exception.toString())
        logger.onReceiveValue(exception.stackTrace.joinToString("\n"))
    }

    fun resolveStreakData(userId: Long): StreakData? =
        this.userResolver.apply(userId)
            ?.let { StreakData(it[0] as Int, it[1] as Long, it[2] as Color) }

    fun translate(key: String): String =
        if (::translationResolver.isInitialized) {
            translationResolver.apply(key) ?: key
        } else {
            key
        }

    fun addStreakDrawableEjectData(ejectData: StreakAnimatedEmojiDrawable.EjectData) {
        streakDrawableEjectData.add(ejectData)
    }

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

    @Suppress("LocalVariableName")
    private fun hookMethods() {
        // Чат в списке, нужно ещё увеличить bounds по x, иначе текста не будет
        hookMethod(
            DialogCell::class.java.getConstructor(
                DialogsActivity::class.java,
                Context::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Int::class.java,
                Theme.ResourcesProvider::class.java
            ),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val thisObject = param.thisObject as DialogCell
                        val thisClass = DialogCell::class.java

                        StreakAnimatedEmojiDrawable.encapsulate(
                            thisObject,
                            getField(thisClass, "emojiStatus"),
                            null,
                            0,
                            true
                        )
                    } catch (e: Exception) {
                        logException(
                            "An unknown exception occurred in DialogCell::constructor hook!",
                            e
                        )
                        onEject()
                    }
                }
            }
        )

        // Конструктор чата в списке не имеет его в качестве аргумента, он задаётся после
        hookMethod(
            DialogCell::class.java.getDeclaredMethod(
                "buildLayout",
            ),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val thisObject = param.thisObject as DialogCell
                        val thisClass = DialogCell::class.java

                        val currentDialogId =
                            getFieldValue<Long>(thisClass, thisObject, "currentDialogId")!!

                        getFieldValue<StreakAnimatedEmojiDrawable>(
                            thisClass,
                            thisObject,
                            "emojiStatus"
                        )?.setUserId(currentDialogId)
                    } catch (e: Exception) {
                        logException(
                            "An unknown exception occurred in DialogCell::onLayout hook!",
                            e
                        )
                        onEject()
                    }
                }
            }
        )

        // Фикс отрисовки текста в местах где размер view ограничен по x
        hookMethod(
            DialogCell::class.java.getDeclaredMethod(
                "onLayout",
                Boolean::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
            ),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val thisObject = param.thisObject as DialogCell
                        val thisClass = DialogCell::class.java

                        val emojiStatusView =
                            getFieldValue<View>(thisClass, thisObject, "emojiStatusView")!!

                        val height = AndroidUtilities.dp(22f)
                        emojiStatusView.layout(0, 0, height * 3, height)
                    } catch (e: Exception) {
                        logException(
                            "An unknown exception occurred in DialogCell::onLayout hook!",
                            e
                        )
                        onEject()
                    }
                }
            }
        )

        // Сообщение в группе
        hookMethod(
            ChatMessageCell::class.java.getDeclaredMethod(
                "setMessageObjectInternal",
                MessageObject::class.java
            ),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val thisObject = param.thisObject as ChatMessageCell
                        val thisClass = ChatMessageCell::class.java

                        val currentUser =
                            getFieldValue<TLRPC.User>(thisClass, thisObject, "currentUser")
                                ?: return

                        StreakAnimatedEmojiDrawable.encapsulate(
                            thisObject,
                            getField(thisClass, "currentNameStatusDrawable"),
                            null,
                            currentUser.id,
                            true
                        )
                    } catch (e: Exception) {
                        logException(
                            "An unknown exception occurred in ChatMessageCell::setMessageObjectInternal hook!",
                            e
                        )
                        onEject()
                    }
                }
            }
        )

        // Пользователь в списке участников группы
        hookMethod(
            UserCell::class.java.getDeclaredMethod(
                "update",
                Int::class.java
            ),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val thisObject = param.thisObject as UserCell
                        val thisClass = UserCell::class.java

                        val dialogId = getFieldValue<Long>(thisClass, thisObject, "dialogId")!!

                        if (dialogId < 0)
                            return

                        StreakAnimatedEmojiDrawable.encapsulate(
                            thisObject,
                            getField(thisClass, "emojiStatus"),
                            null,
                            dialogId
                        )
                    } catch (e: Exception) {
                        logException("An unknown exception occurred in UserCell::update hook!", e)
                        onEject()
                    }
                }
            }
        )

        // Профиль пользователя
        hookMethod(
            ProfileActivity::class.java.getDeclaredMethod(
                "getEmojiStatusDrawable",
                TLRPC.EmojiStatus::class.java,
                Boolean::class.java,
                Boolean::class.java,
                Int::class.java
            ),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val thisObject = param.thisObject as ProfileActivity
                        val thisClass = ProfileActivity::class.java

                        val userId = getFieldValue<Long>(thisClass, thisObject, "userId")!!

                        if (userId < 0)
                            return

                        StreakAnimatedEmojiDrawable.encapsulate(
                            thisObject,
                            getField(thisClass, "emojiStatusDrawable"),
                            param.args[3] as Int,
                            userId
                        )
                    } catch (e: Exception) {
                        logException(
                            "An unknown exception occurred in ProfileCell::getEmojiStatusDrawable hook!",
                            e
                        )
                        onEject()
                    }
                }
            }
        )

        // Заголовок открытого лс с пользователем
        hookMethod(
            ChatAvatarContainer::class.java
                .getDeclaredMethods()
                .filter { it.name == "setTitle" }
                .maxByOrNull { it.parameterCount }!!,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val thisObject = param.thisObject as ChatAvatarContainer
                        val thisClass = ChatAvatarContainer::class.java

                        val dialogId = getFieldValue<ChatActivity>(
                            thisClass,
                            thisObject,
                            "parentFragment"
                        )?.dialogId ?: return

                        if (dialogId < 0)
                            return

                        StreakAnimatedEmojiDrawable.encapsulate(
                            thisObject,
                            getField(thisClass, "emojiStatusDrawable"),
                            null,
                            dialogId
                        )
                    } catch (e: Exception) {
                        logException(
                            "An unknown exception occurred in ChatAvatarActivity::setTitle hook!",
                            e
                        )
                        onEject()
                    }
                }
            }
        )

        // Callback при клике на эмодзи в профиле пользователя
        // Из-за крайней сложности восстановить родителя (ProfileActivity) обработка диалога произойдёт постфактум
        val ProfileActivity_unknownLambdaHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    this@Plugin.dialogHookAction = DialogHookAction.PremiumPreviewBottomSheet()
                } catch (e: Exception) {
                    logException(
                        "An unknown exception occurred in ${param.thisObject.javaClass.simpleName}::apply hook!",
                        e
                    )
                    onEject()
                }
            }
        }

        // Хук для поиска лямбы для хука выше,
        // так как она не имеет чёткого называния, не меняющегося из билда в билд.
        // P.S. При таком кол-ве лямбд у класса, оно 100% может поменяться.
        hookMethod(
            ProfileActivity::class.java.getDeclaredMethod("updateProfileData", Boolean::class.java),
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (this@Plugin.profileActivityLambdaHooked)
                            return

                        val profileActivity = param.thisObject as? ProfileActivity ?: return

                        val nameTextView = getFieldValue<Array<SimpleTextView>>(
                            ProfileActivity::class.java,
                            profileActivity,
                            "nameTextView"
                        ) ?: return

                        val userId = getFieldValue<Long>(
                            ProfileActivity::class.java,
                            profileActivity,
                            "userId"
                        )

                        val user = MessagesController.getInstance(UserConfig.selectedAccount)
                            .getUser(userId)

                        if (user.self || !user.premium)
                            return

                        for (i in 0..1) {
                            val view = nameTextView[i]

                            val callback = getFieldValue<View.OnClickListener>(
                                SimpleTextView::class.java,
                                view,
                                "rightDrawableOnClickListener"
                            ) ?: continue

                            log(callback.javaClass.declaredMethods.joinToString("\n") { it -> it.toString() })

                            hookMethod(
                                callback.javaClass.getDeclaredMethod("onClick", View::class.java),
                                ProfileActivity_unknownLambdaHook
                            )

                            this@Plugin.profileActivityLambdaHooked = true
                        }
                    } catch (e: Exception) {
                        logException(
                            "An unknown exception occurred in ProfileActivity::updateProfileData hook!",
                            e
                        )
                        onEject()
                    }
                }
            }
        )

        // Хук отображения диалоговых окон, отрабатывает только при надобности
        hookMethod(
            BaseFragment::class.java.getDeclaredMethod("showDialog", Dialog::class.java),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val action = this@Plugin.dialogHookAction
                    this@Plugin.dialogHookAction = DialogHookAction.Disabled()

                    when (action) {
                        is DialogHookAction.PremiumPreviewBottomSheet -> {
                            val dialog = param.args[0] as? PremiumPreviewBottomSheet ?: return
                            val user = getFieldValue<TLRPC.User>(
                                PremiumPreviewBottomSheet::class.java,
                                dialog,
                                "user"
                            )!!

                            val streakData = this@Plugin.resolveStreakData(user.id) ?: return

                            param.args[0] = StreakBottomSheet(dialog, user, streakData)
                        }

                        is DialogHookAction.Disabled -> return
                    }
                }
            }
        )
    }
}
