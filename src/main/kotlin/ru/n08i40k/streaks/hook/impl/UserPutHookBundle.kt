package ru.n08i40k.streaks.hook.impl

import org.telegram.messenger.BaseController
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.hook.HookBundle
import ru.n08i40k.streaks.hook.InstallHook
import ru.n08i40k.streaks.util.UserPatcher
import ru.n08i40k.streaks.util.UserPatcher.isPatched
import ru.n08i40k.streaks.util.getField

class UserPutHookBundle : HookBundle() {
    companion object Fields {
        val CURRENT_ACCOUNT = getField(BaseController::class.java, "currentAccount")
    }

    override fun inject(
        before: InstallHook,
        after: InstallHook
    ) {
        val streaksController = Plugin.getInstance().streaksController

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

            if (user.isPatched())
                return@before

            val accountId = CURRENT_ACCOUNT.getInt(messagesController)

            if (streaksController.getViewData(accountId, user.id) != null)
                UserPatcher.patchUser(accountId, user)
        }
    }
}