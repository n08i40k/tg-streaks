package ru.n08i40k.streaks.ui

import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.UserConfig
import org.telegram.ui.ChatActivity
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.BulletinHelper

class StreakPetUiManager {
    companion object {
        private const val DEFAULT_PET_FAB_SIZE_DP = 80
        private const val PET_FAB_OPEN_DELAY_MS = 220L
    }

    private var openedDialog: StreakPetDialog? = null
    private var fabDialog: StreakPetFabDialog? = null
    private var fabEnabled: Boolean = true
    private var fabSizeDp: Int = DEFAULT_PET_FAB_SIZE_DP
    private var pendingFabRefresh: Runnable? = null

    fun dismissAll() {
        dismissDialog()
        dismissFab()
    }

    fun dismissFab() {
        pendingFabRefresh?.let(AndroidUtilities::cancelRunOnUIThread)
        pendingFabRefresh = null
        fabDialog?.dismiss()
        fabDialog = null
    }

    fun scheduleFabRefreshForOpenChat(delayMs: Long = PET_FAB_OPEN_DELAY_MS) {
        pendingFabRefresh?.let(AndroidUtilities::cancelRunOnUIThread)

        val runnable = Runnable {
            pendingFabRefresh = null
            refreshFabForOpenChat()
        }

        pendingFabRefresh = runnable
        AndroidUtilities.runOnUIThread(runnable, delayMs)
    }

    fun setFabSizeDp(sizeDp: Int) {
        if (this.fabSizeDp == sizeDp) {
            return
        }

        this.fabSizeDp = sizeDp

        AndroidUtilities.runOnUIThread {
            fabDialog?.updateSizeDp(sizeDp)
            fabDialog?.configureWindow()
        }
    }

    fun toggleFabEnabled(): Boolean {
        fabEnabled = !fabEnabled

        if (fabEnabled) {
            refreshFabForOpenChat()
        } else {
            AndroidUtilities.runOnUIThread { dismissFab() }
        }

        return fabEnabled
    }

    fun openDialog(accountId: Int, peerUserId: Long) = with(Plugin.getInstance()) {
        backgroundScope.launch {
            val uiState = streakPetsController.getViewStateSnapshot(accountId, peerUserId)
                ?: run {
                    BulletinHelper.show(Strings.status_info_pet_not_created_for_chat())
                    return@launch
                }

            AndroidUtilities.runOnUIThread {
                val fragment = LaunchActivity.getSafeLastFragment()
                if (fragment == null) {
                    BulletinHelper.show(Strings.status_error_chat_open_context_failed())
                    return@runOnUIThread
                }

                if (
                    openedDialog?.isShowing == true
                    && openedDialog?.matches(accountId, peerUserId) == true
                ) {
                    openedDialog?.updateState(uiState)
                    return@runOnUIThread
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

    fun refreshFabForOpenChat() = with(Plugin.getInstance()) {
        if (!fabEnabled) {
            AndroidUtilities.runOnUIThread { dismissFab() }
            return@with
        }

        val chatActivity = LaunchActivity.getSafeLastFragment() as? ChatActivity
            ?: run {
                AndroidUtilities.runOnUIThread { dismissFab() }
                return@with
            }

        val accountId = UserConfig.selectedAccount
        val peerUserId = chatActivity.dialogId

        if (peerUserId <= 0L) {
            AndroidUtilities.runOnUIThread { dismissFab() }
            return
        }

        backgroundScope.launch {
            val uiState = streakPetsController.getViewStateSnapshot(accountId, peerUserId)

            AndroidUtilities.runOnUIThread {
                if (!fabEnabled) {
                    dismissFab()
                    return@runOnUIThread
                }

                val currentChat = LaunchActivity.getSafeLastFragment() as? ChatActivity
                if (currentChat == null || currentChat.dialogId != peerUserId) {
                    dismissFab()
                    return@runOnUIThread
                }

                if (uiState == null) {
                    dismissFab()
                    return@runOnUIThread
                }

                if (
                    fabDialog?.isShowing == true
                    && fabDialog?.matches(accountId, peerUserId) == true
                ) {
                    fabDialog?.updateState(uiState)
                    return@runOnUIThread
                }

                dismissFab()

                val context = currentChat.parentActivity
                    ?: currentChat.context
                    ?: return@runOnUIThread

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

            AndroidUtilities.runOnUIThread {
                val dialog = openedDialog
                    ?: return@runOnUIThread

                if (!dialog.matches(accountId, peerUserId) || !dialog.isShowing) {
                    dismissDialog(dialog)
                    return@runOnUIThread
                }

                if (refreshedState == null) {
                    dismissDialog(dialog)
                    return@runOnUIThread
                }

                dialog.updateState(refreshedState)
            }
        }
    }
}
