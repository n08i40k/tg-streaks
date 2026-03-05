@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks

import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.ValueCallback
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Cells.DialogCell
import org.telegram.ui.Cells.UserCell
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ChatAvatarContainer
import org.telegram.ui.DialogsActivity
import org.telegram.ui.ProfileActivity
import ru.n08i40k.streaks.data.StreakData
import ru.n08i40k.streaks.overrides.StreakAnimatedEmojiDrawable

class Plugin {
    @Suppress("unused")
    companion object {
        private var INSTANCE: Plugin? = null

        @JvmStatic
        fun inject(
            logger: ValueCallback<String>,
            userResolver: java.util.function.Function<Long, Array<Any>?>
        ) {
            if (INSTANCE == null)
                INSTANCE = Plugin()
            else
                return

            INSTANCE!!.logger = logger
            INSTANCE!!.userResolver = userResolver
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
    }

    private lateinit var logger: ValueCallback<String>
    private lateinit var userResolver: java.util.function.Function<Long, Array<Any>?>

    private var hooks: ArrayList<XC_MethodHook.Unhook> = arrayListOf()
    private var streakDrawableEjectData: ArrayList<StreakAnimatedEmojiDrawable.EjectData> =
        arrayListOf()

    fun log(message: String) {
        logger.onReceiveValue(message)
    }

    fun resolveStreakData(userId: Long): StreakData? =
        this.userResolver.apply(userId)
            ?.let { StreakData(it[0] as Int, it[1] as Long, it[2] as Color) }

    fun addStreakDrawableEjectData(ejectData: StreakAnimatedEmojiDrawable.EjectData) {
        streakDrawableEjectData.add(ejectData)
    }

    private fun onInject() {
        try {
            hookMethods()
        } catch (e: Exception) {
            log("Failed to hook methods!")
            log(e.toString())
            onEject()
        }

        log("Injected!")
    }

    private fun onEject() {
        try {
            hooks.forEach { it.unhook() }
            hooks.clear()
        } catch (e: Exception) {
            log("Failed to unhook methods!")
            log(e.toString())
        }

        try {
            streakDrawableEjectData.forEach { it.restore() }
            streakDrawableEjectData.clear()
        } catch (e: Exception) {
            log("Failed to restore original SwapAnimatedEmojiDrawable!")
            log(e.toString())
        }

        log("Ejected!")
    }

    @Suppress("LocalVariableName")
    private fun hookMethods() {
        val DialogCell_constructor = XposedBridge.hookMethod(
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
                            0
                        )
                    } catch (e: Exception) {
                        log("An unknown exception occurred in DialogCell::constructor hook!")
                        log(e.toString())
                        log(e.stackTrace.toString())
                        log("xd")
                        onEject()
                    }
                }
            }
        )

        hooks.add(DialogCell_constructor)

        val DialogCell_buildLayout = XposedBridge.hookMethod(
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
                        log("An unknown exception occurred in DialogCell::onLayout hook!")
                        log(e.toString())
                        onEject()
                    }
                }
            }
        )

        hooks.add(DialogCell_buildLayout)

        val DialogCell_onLayout = XposedBridge.hookMethod(
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
                        log("An unknown exception occurred in DialogCell::onLayout hook!")
                        log(e.toString())
                        onEject()
                    }
                }
            }
        )

        hooks.add(DialogCell_onLayout)

        val ChatMessageCell_setMessageObjectInternal = XposedBridge.hookMethod(
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
                            currentUser.id
                        )
                    } catch (e: Exception) {
                        log("An unknown exception occurred in ChatMessageCell::setMessageObjectInternal hook!")
                        log(e.toString())
                        onEject()
                    }
                }
            }
        )

        hooks.add(ChatMessageCell_setMessageObjectInternal)

        val UserCell_update = XposedBridge.hookMethod(
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
                        log("An unknown exception occurred in UserCell::update hook!")
                        log(e.toString())
                        onEject()
                    }
                }
            }
        )

        hooks.add(UserCell_update)

        val ProfileActivity_getEmojiStatusDrawable = XposedBridge.hookMethod(
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
                        log("An unknown exception occurred in ProfileCell::getEmojiStatusDrawable hook!")
                        log(e.toString())
                        onEject()
                    }
                }
            }
        )

        hooks.add(ProfileActivity_getEmojiStatusDrawable)

        val ChatAvatarContainer_setTitle = XposedBridge.hookMethod(
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
                        log("An unknown exception occurred in ChatAvatarActivity::setTitle hook!")
                        log(e.toString())
                        onEject()
                    }
                }
            }
        )

        hooks.add(ChatAvatarContainer_setTitle)
    }
}
