package ru.n08i40k.streaks

import org.telegram.messenger.UserConfig
import ru.n08i40k.streaks.constants.SettingsActionButton
import ru.n08i40k.streaks.constants.TranslationKey
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.RebuildNotificationHelper

class SettingsMenuActions(private val plugin: Plugin) {
    fun register() = with(plugin) {
        fun add(key: String, callback: () -> Unit) {
            settingsActionCallbackRegistry.register(key) {
                Logger.tryOrFatal("handle settings action touch") { callback() }
            }
        }


        add(SettingsActionButton.REBUILD_ALL) {
            val accountId = UserConfig.selectedAccount

            if (streaksController.isRebuildRunning()) {
                bulletinHelper.showTranslated(TranslationKey.Status.Info.REBUILD_ALREADY_RUNNING)
                return@add
            }

            AccountTaskExecutor.enqueue(accountId, "rebuild all streaks for $accountId") {
                val result =
                    streaksController.rebuildAll(accountId) { index, total, _, progress ->
                        RebuildNotificationHelper.updateAllStreakProgress(
                            index,
                            total,
                            progress.peerUser.label,
                            progress.daysChecked,
                        )
                    }

                syncPeersUi(result.uiSyncTargets)
                RebuildNotificationHelper.completeAllStreaks(result.totalChats)
            }
        }

        add(SettingsActionButton.EXPORT_BACKUP_NOW) {
            enqueueTask("export database backup") {
                val backup = databaseBackupManager.exportNow()

                bulletinHelper.showTranslated(
                    TranslationKey.Status.Success.BACKUP_EXPORTED,
                    mapOf("name" to backup.name),
                    "msg_save"
                )
            }
        }

        settingsActionCallbackRegistry.freeze()
    }
}
