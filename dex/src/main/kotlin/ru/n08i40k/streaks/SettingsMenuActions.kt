package ru.n08i40k.streaks

import org.telegram.messenger.UserConfig
import ru.n08i40k.streaks.constants.SettingsActionButton
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.Logger

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
                BulletinHelper.show(Strings.status_info_rebuild_already_running())
                return@add
            }

            AccountTaskExecutor.enqueue(accountId, "rebuild all streaks for $accountId") {
                streaksController.rebuildAll(accountId)
            }
        }

        add(SettingsActionButton.EXPORT_BACKUP_NOW) {
            enqueueTask("export database backup") {
                val backup = databaseBackupManager.exportNow()

                BulletinHelper.show(
                    Strings.status_success_backup_exported(backup.name),
                    "msg_save"
                )
            }
        }

        settingsActionCallbackRegistry.freeze()
    }
}
