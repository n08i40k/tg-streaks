@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.hook.impl

import kotlinx.coroutines.launch
import org.telegram.messenger.BaseController
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.util.getFieldValue

class UserPutHookBundle : HookBundle() {
    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        // Патч пользователя со стриком
        before(
            MessagesController::class.java.getDeclaredMethod(
                "putUser",
                TLRPC.User::class.java,
                Boolean::class.java,
                Boolean::class.java,
            )
        ) { param ->
            val messagesController = param.thisObject as MessagesController

            val user = param.args[0] as? TLRPC.User
                ?: return@before

            val accountId =
                getFieldValue<Int>(BaseController::class.java, messagesController, "currentAccount")
                    ?: return@before

            Plugin.getInstance().apply {
                backgroundScope.launch {
                    if (streaksController.patchUser(accountId, user))
                        messagesController.putUser(user, false, true)
                }
            }
        }
    }
}