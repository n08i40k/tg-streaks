package ru.n08i40k.streaks

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.telegram.messenger.UserConfig
import ru.n08i40k.streaks.constants.SettingsActionButton
import ru.n08i40k.streaks.data.StreakLevelPack
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.ui.levelPack.select.StreakLevelPackSelectFragment
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.presentFragment
import ru.n08i40k.streaks.util.runOnMainThread
import java.util.UUID

class SettingsMenuActions(private val plugin: Plugin) {
    fun register() = with(plugin) {
        fun add(key: String, callback: () -> Unit) {
            settingsActionCallbackRegistry.register(key) {
                Logger.tryOrFatal("handle settings action touch") { callback() }
            }
        }


        add(SettingsActionButton.OPEN_GUIDES) {
            runOnMainThread { guideUiManager.show() }
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
                    "msg_download"
                )
            }
        }

        add(SettingsActionButton.OPEN_LEVEL_PACKS) {
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

                        BulletinHelper.show(
                            Strings.status_success_level_pack_shared(),
                            "msg_share"
                        )
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

        settingsActionCallbackRegistry.freeze()
    }
}
