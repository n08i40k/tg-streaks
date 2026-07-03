package ru.n08i40k.streaks

import org.telegram.messenger.UserConfig
import ru.n08i40k.streaks.constants.SettingsActionButton
import ru.n08i40k.streaks.constants.TranslationKey
import ru.n08i40k.streaks.extension.label
import ru.n08i40k.streaks.util.Logger

class SettingsMenuActions(private val plugin: Plugin) {
    fun register() = with(plugin) {
        fun add(key: String, callback: () -> Unit) {
            settingsActionCallbackRegistry.register(key) {
                try {
                    callback()
                } catch (e: Throwable) {
                    Logger.fatal("An error occurred while handling settings action", e)
                }
            }
        }


        add(SettingsActionButton.REBUILD_ALL) {
            val accountId = UserConfig.selectedAccount

            if (streaksController.isRebuildRunning()) {
                bulletinHelper.showTranslated(TranslationKey.Status.Info.REBUILD_ALREADY_RUNNING)
                return@add
            }

            accountTaskRunnerRegistry.enqueue(accountId, "rebuild all streaks for $accountId") {
                try {
                    val result =
                        streaksController.rebuildAll(accountId) { index, total, _, progress ->
                            rebuildNotificationHelper.updateAllStreakProgress(
                                index,
                                total,
                                progress.peerUser.label,
                                progress.daysChecked,
                            )
                        }

                    syncPeersUi(result.uiSyncTargets)
                    rebuildNotificationHelper.completeAllStreaks(result.totalChats)
                } catch (e: Throwable) {
                    Logger.fatal("Failed to rebuild all private chats for account $accountId", e)
                    rebuildNotificationHelper.cancelAllProgress()
                    bulletinHelper.showTranslated(TranslationKey.Status.Error.REBUILD_FAILED_CHECK_LOGS)
                }
            }
        }

        add(SettingsActionButton.EXPORT_BACKUP_NOW) {
            enqueueTask("export database backup") {
                try {
                    val backup = databaseBackupManager.exportNow()
                    bulletinHelper.showTranslated(
                        TranslationKey.Status.Success.BACKUP_EXPORTED,
                        mapOf("name" to backup.name),
                        "msg_save"
                    )
                } catch (e: Throwable) {
                    Logger.fatal("Failed to export database backup", e)
                    bulletinHelper.showTranslated(TranslationKey.Status.Error.BACKUP_EXPORT_FAILED)
                }
            }
        }

        settingsActionCallbackRegistry.freeze()
    }
}
