package ru.n08i40k.streaks.ui

import androidx.annotation.AnyThread
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.telegram.messenger.UserConfig
import org.telegram.ui.ChatActivity
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.runOnMainThread

class StreakPetUiManager {
    companion object {
        private const val DEFAULT_PET_FAB_SIZE_DP = 80
        private const val PET_FAB_OPEN_DELAY_MS = 220L
    }

    private var openedDialog: StreakPetDialog? = null
    private var fabDialog: StreakPetFabDialog? = null
    private var fabSizeDp: Int = DEFAULT_PET_FAB_SIZE_DP
    private var pendingFabRefresh: Job? = null

    @AnyThread
    fun dismissAll() {
        dismissDialog()
        dismissFab()
    }

    @AnyThread
    fun dismissFab() {
        pendingFabRefresh?.cancel()
        pendingFabRefresh = null
        fabDialog?.dismiss()
        fabDialog = null
    }

    fun scheduleFabRefreshForOpenChat(delayMs: Long = PET_FAB_OPEN_DELAY_MS): Unit =
        with(Plugin.getInstance()) {
            pendingFabRefresh?.cancel()
            pendingFabRefresh = backgroundScope.launch {
                delay(delayMs)
                refreshFabForOpenChat()
            }
        }

    fun setFabSizeDp(sizeDp: Int) {
        if (this.fabSizeDp == sizeDp) {
            return
        }

        this.fabSizeDp = sizeDp

        runOnMainThread {
            fabDialog?.apply {
                updateSizeDp(sizeDp)
                configureWindow()
            }
        }
    }

    fun openDialog(accountId: Int, peerUserId: Long): Unit = with(Plugin.getInstance()) {
        backgroundScope.launch {
            val uiState = streakPetsController.getViewStateSnapshot(accountId, peerUserId)
                ?: run {
                    BulletinHelper.show(Strings.status_info_pet_not_created_for_chat())
                    return@launch
                }

            runOnMainThread {
                val fragment = LaunchActivity.getSafeLastFragment()
                if (fragment == null) {
                    BulletinHelper.show(Strings.status_error_chat_open_context_failed())
                    return@runOnMainThread
                }

                if (openedDialog?.isShowing == true
                    && openedDialog?.matches(accountId, peerUserId) == true
                ) {
                    openedDialog?.updateState(uiState)
                    return@runOnMainThread
                }

                dismissDialog()
                dismissFab()

                val onRenameRequested: (String) -> Unit = { newName ->
                    AccountTaskExecutor.enqueue(
                        accountId,
                        "rename pet for $accountId:$peerUserId"
                    ) {
                        streakPetsController.rename(
                            accountId,
                            peerUserId,
                            newName,
                            byPlugin = true,
                            byPeer = false
                        )
                    }
                }

                val dialog = StreakPetDialog(
                    fragment,
                    accountId,
                    peerUserId,
                    uiState,
                    resourcesProvider,
                    onRenameRequested = onRenameRequested,
                    onDismissed = ::refreshFabForOpenChat
                )

                trackDialog(dialog)
                fragment.showDialog(dialog)
            }
        }
    }

    fun refreshFabForOpenChat(): Unit = with(Plugin.getInstance()) {
        val chatActivity = LaunchActivity.getSafeLastFragment() as? ChatActivity
            ?: run {
                runOnMainThread(::dismissFab)
                return@with
            }

        val accountId = UserConfig.selectedAccount
        val peerUserId = chatActivity.dialogId

        if (peerUserId <= 0L) {
            runOnMainThread(::dismissFab)
            return
        }

        backgroundScope.launch {
            val uiState = streakPetsController.getViewStateSnapshot(accountId, peerUserId)


            runOnMainThread {
                val currentChat = LaunchActivity.getSafeLastFragment() as? ChatActivity

                if (currentChat == null
                    || currentChat.dialogId != peerUserId
                    || uiState == null
                    || !uiState.pet.fabEnabled
                ) {
                    dismissFab()
                    return@runOnMainThread
                }

                fabDialog?.apply {
                    if (!isShowing || !matches(accountId, peerUserId))
                        return@apply

                    updateState(uiState)
                    return@runOnMainThread
                }

                dismissFab()

                val context = currentChat.parentActivity
                    ?: currentChat.context
                    ?: return@runOnMainThread

                val newDialog = StreakPetFabDialog(
                    context,
                    accountId,
                    peerUserId,
                    uiState,
                    resourcesProvider,
                    fabSizeDp,
                ) {
                    dismissFab()
                    openDialog(accountId, peerUserId)
                }

                newDialog.show()
                newDialog.configureWindow()
                fabDialog = newDialog
            }
        }
    }

    @AnyThread
    private fun dismissDialog(dialog: StreakPetDialog? = null) {
        if (dialog != null && openedDialog !== dialog) {
            return
        }

        openedDialog?.dismiss()
        openedDialog = null
    }

    private fun trackDialog(dialog: StreakPetDialog) {
        openedDialog = dialog
        dialog.setOnDismissListener {
            dismissDialog(dialog)
        }
    }

    fun refreshOpenedDialog(accountId: Int, peerUserId: Long) = with(Plugin.getInstance()) {
        if (openedDialog?.matches(accountId, peerUserId) != true)
            return@with

        backgroundScope.launch {
            val refreshedState = streakPetsController.getViewStateSnapshot(accountId, peerUserId)

            runOnMainThread {
                val dialog = openedDialog
                    ?: return@runOnMainThread

                if (!dialog.matches(accountId, peerUserId) || !dialog.isShowing) {
                    dismissDialog(dialog)
                    return@runOnMainThread
                }

                if (refreshedState == null) {
                    dismissDialog(dialog)
                    return@runOnMainThread
                }

                dialog.updateState(refreshedState)
            }
        }
    }
}
