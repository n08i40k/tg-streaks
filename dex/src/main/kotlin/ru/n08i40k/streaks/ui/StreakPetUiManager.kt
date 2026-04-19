@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.ui

import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.UserConfig
import org.telegram.ui.ChatActivity
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.constants.TranslationKey

class StreakPetUiManager(private val plugin: Plugin) {
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

    fun openDialog(accountId: Int, peerUserId: Long) {
        plugin.uiScope.launch {
            val uiState = plugin.streakPetsController.getViewStateSnapshot(accountId, peerUserId)

            if (uiState == null) {
                plugin.bulletinHelper.showTranslated(TranslationKey.Status.Info.PET_NOT_CREATED_FOR_CHAT)
                return@launch
            }

            AndroidUtilities.runOnUIThread {
                val fragment = LaunchActivity.getSafeLastFragment()
                if (fragment == null) {
                    plugin.bulletinHelper.showTranslated(
                        TranslationKey.Status.Error.CHAT_OPEN_CONTEXT_FAILED
                    )
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

                val dialog = StreakPetDialog(
                    fragment,
                    accountId,
                    peerUserId,
                    uiState,
                    plugin.resourcesProvider,
                    plugin.translator,
                    onRenameRequested = { newName ->
                        plugin.accountTaskRunnerRegistry.enqueue(
                            accountId,
                            "rename pet for $accountId:$peerUserId"
                        ) {
                            if (!plugin.streakPetsController.rename(accountId, peerUserId, newName)) {
                                return@enqueue
                            }

                            plugin.serviceMessagesController.sendPetSetName(
                                accountId,
                                peerUserId,
                                newName
                            )
                            refreshOpenedDialog(accountId, peerUserId)
                        }
                    },
                    onDismissed = {
                        refreshFabForOpenChat()
                    }
                )

                trackDialog(dialog)
                fragment.showDialog(dialog)
            }
        }
    }

    fun refreshFabForOpenChat() {
        if (!fabEnabled) {
            AndroidUtilities.runOnUIThread { dismissFab() }
            return
        }

        val chatActivity = LaunchActivity.getSafeLastFragment() as? ChatActivity
            ?: run {
                AndroidUtilities.runOnUIThread { dismissFab() }
                return
            }

        val accountId = UserConfig.selectedAccount
        val peerUserId = chatActivity.dialogId

        if (peerUserId <= 0L) {
            AndroidUtilities.runOnUIThread { dismissFab() }
            return
        }

        plugin.uiScope.launch {
            val uiState = plugin.streakPetsController.getViewStateSnapshot(accountId, peerUserId)

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
                    plugin.resourcesProvider,
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

    private fun refreshOpenedDialog(accountId: Int, peerUserId: Long) {
        if (openedDialog?.matches(accountId, peerUserId) != true) {
            return
        }

        plugin.uiScope.launch {
            val refreshedState = plugin.streakPetsController.getViewStateSnapshot(accountId, peerUserId)

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
