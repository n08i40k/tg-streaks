package ru.n08i40k.streaks.settings

import org.telegram.messenger.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.telegram.messenger.UserConfig
import org.telegram.messenger.browser.Browser
import org.telegram.ui.LaunchActivity
import ru.n08i40k.shim.settings.SettingsScope
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.constants.Url
import ru.n08i40k.streaks.data.StreakLevelPack
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.ui.levelPack.select.StreakLevelPackSelectFragment
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.presentFragment
import ru.n08i40k.streaks.util.runOnMainThread
import java.util.UUID

class PluginSettingsScreen(private val plugin: Plugin) {
    private inline fun handle(name: String, block: () -> Unit) {
        Logger.tryOrFatal("handle settings action \"$name\"", block)
    }

    fun applyStored() {
        applyUpdateCheck(PluginSettings.updateCheck.value)
        applyPetFabSize(PluginSettings.petFabSize.value)
        applyAutoStreakCreation(PluginSettings.autoStreakCreation.value)
    }

    private fun applyUpdateCheck(enabled: Boolean) {
        if (enabled)
            plugin.updateChecker.start()
        else
            plugin.updateChecker.stop()
    }

    private fun applyPetFabSize(sizeDp: Int) =
        plugin.petUiManager.setFabSizeDp(sizeDp)

    private fun applyAutoStreakCreation(enabled: Boolean) =
        plugin.streaksController.setAutoCreationEnabled(enabled)

    fun SettingsScope.build() {
        header(Strings.settings_updates_title())

        switch(PluginSettings.updateCheck) {
            text = Strings.settings_updates_auto_check_title()
            subtext = Strings.settings_updates_auto_check_description()
            icon = "msg_retry"
            linkAlias = "update-check"

            onChange { handle("toggle update check") { applyUpdateCheck(it) } }
        }

        header(Strings.settings_pet_button_title())

        selector(
            PluginSettings.petFabSize,
            label = { "$it dp" },
            onSelect = { handle("change pet button size") { applyPetFabSize(it) } },
        ) {
            text = Strings.settings_pet_button_size_title()
            icon = "msg_customize"
            linkAlias = "pet-button-size"
        }

        divider(Strings.settings_pet_button_size_description())

        header(Strings.settings_streak_tools_title())

        switch(PluginSettings.autoStreakCreation) {
            text = Strings.settings_streak_tools_auto_create_title()
            subtext = Strings.settings_streak_tools_auto_create_description()
            icon = "msg_add"
            linkAlias = "auto-create"

            onChange { handle("toggle auto streak creation") { applyAutoStreakCreation(it) } }
        }

        text {
            text = Strings.settings_streak_tools_level_packs_title()
            icon = "msg_emoji_smiles"
            linkAlias = "emoji-packs"

            onClick { handle("open streak level packs") { openLevelPacks() } }
        }

        text {
            text = Strings.settings_streak_tools_rebuild_all_chats_title()
            icon = "msg_retry"
            linkAlias = "rebuild-all"

            onClick { handle("rebuild all private chats") { rebuildAll() } }
        }

        divider(Strings.settings_streak_tools_rebuild_all_chats_description())

        header(Strings.settings_backups_title())

        text {
            text = Strings.settings_backups_export_title()
            icon = "msg_download"
            linkAlias = "backup-create"

            onClick { handle("export database backup") { exportBackup() } }
        }

        text {
            text = Strings.settings_backups_restore_title()
            icon = "msg_reset"
            linkAlias = "backup-restore"

            onClick {
                handle("restore database backup") {
                    plugin.databaseMaintenance.showRestoreDialog()
                }
            }
        }

        text {
            text = Strings.settings_backups_reset_database_title()
            icon = "msg_delete"
            linkAlias = "backup-reset"

            onClick {
                handle("reset database") { plugin.databaseMaintenance.reset() }
            }
        }

        divider(Strings.settings_backups_description())

        header(Strings.settings_help_title())

        text {
            text = Strings.settings_help_guides_title()
            icon = "msg_help"
            linkAlias = "guides"

            onClick {
                handle("open guides") { runOnMainThread { plugin.guideUiManager.show() } }
            }
        }

        text {
            text = Strings.settings_help_docs_title()
            icon = "msg_info"
            linkAlias = "docs"

            onClick { view ->
                handle("open documentation") {
                    Browser.openUrl(LaunchActivity.instance ?: view.context, Url.Docs.ROOT)
                }
            }
        }

        divider(Strings.settings_help_docs_description())
    }

    private fun rebuildAll(): Unit = with(plugin) {
        val accountId = UserConfig.selectedAccount

        if (streaksController.isRebuildRunning()) {
            BulletinHelper.info(Strings.status_info_rebuild_already_running())
            return
        }

        AccountTaskExecutor.enqueue(accountId, "rebuild all streaks for $accountId") {
            streaksController.rebuildAll(accountId)
        }
    }

    private fun exportBackup() = with(plugin) {
        enqueueTask("export database backup") {
            val backup = databaseBackupManager.exportNow()

            BulletinHelper.show(R.raw.ic_download, Strings.status_success_backup_exported(backup.name))
        }
    }

    private fun openLevelPacks() = with(plugin) {
        val viewModel = object : StreakLevelPackSelectFragment.ViewModel {
            private val stateFlow = MutableStateFlow(
                StreakLevelPackSelectFragment.ViewState(
                    levelPacks = listOf(streakLevelPacksController.getCurrent()),
                    activeLevelPack = streakLevelPacksController.getCurrent()
                )
            )

            init {
                enqueueTask("refresh view state for streak level pack select view model") {
                    refreshState()
                }
            }

            private suspend fun refreshState() {
                val levelPacks = streakLevelPacksController.getAll()
                val activeLevelPack = streakLevelPacksController.getCurrent()

                stateFlow.emit(
                    StreakLevelPackSelectFragment.ViewState(
                        levelPacks,
                        activeLevelPack
                    )
                )
            }

            override fun state(): Flow<StreakLevelPackSelectFragment.ViewState> = stateFlow

            override fun setActiveLevelPack(id: UUID) {
                enqueueTask("set active streak level pack $id") {
                    streakLevelPacksController.setCurrent(id)
                    refreshState()
                }
            }

            override fun create(basedOn: UUID) {
                enqueueTask("create new streak level pack based on $basedOn") {
                    streakLevelPacksController.create(basedOn)
                    refreshState()
                }
            }

            override fun share(id: UUID, peerId: Long) {
                enqueueTask("share streak level pack $id with $peerId") {
                    val levelPack = streakLevelPacksController.getAll().find { it.id == id }
                        ?: return@enqueueTask

                    serviceMessagesController.sendLevelPackImport(
                        UserConfig.selectedAccount,
                        peerId,
                        levelPack
                    )

                    BulletinHelper.show(R.raw.forward, Strings.status_success_level_pack_shared())
                }
            }

            override fun update(levelPack: StreakLevelPack) {
                enqueueTask("update streak level pack ${levelPack.id}") {
                    streakLevelPacksController.update(levelPack)
                    refreshState()
                }
            }

            override fun delete(id: UUID) {
                enqueueTask("delete streak level pack $id") {
                    streakLevelPacksController.delete(id)
                    refreshState()
                }
            }
        }

        presentFragment(StreakLevelPackSelectFragment(viewModel))
    }
}
