package ru.n08i40k.streaks

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.telegram.messenger.UserConfig
import ru.n08i40k.streaks.constants.SettingsActionButton
import ru.n08i40k.streaks.data.StreakEmojiPack
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.ui.emojiPack.select.StreakEmojiPackSelectFragment
import ru.n08i40k.streaks.util.AccountTaskExecutor
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.Logger
import ru.n08i40k.streaks.util.presentFragment
import java.util.UUID

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
                    "msg_download"
                )
            }
        }

        add(SettingsActionButton.OPEN_EMOJI_PACKS) {
            val viewModel = object : StreakEmojiPackSelectFragment.ViewModel {
                private val stateFlow = MutableStateFlow(
                    StreakEmojiPackSelectFragment.ViewState(
                        emojiPacks = listOf(streakEmojiPacksController.getCurrent()),
                        activeEmojiPack = streakEmojiPacksController.getCurrent()
                    )
                )

                init {
                    enqueueTask("refresh view state for emoji pack select view model") {
                        refreshState()
                    }
                }

                private suspend fun refreshState() {
                    val emojiPacks = streakEmojiPacksController.getAll()
                    val activeEmojiPack = streakEmojiPacksController.getCurrent()

                    stateFlow.emit(
                        StreakEmojiPackSelectFragment.ViewState(
                            emojiPacks,
                            activeEmojiPack
                        )
                    )
                }

                override fun state(): Flow<StreakEmojiPackSelectFragment.ViewState> = stateFlow

                override fun setActiveEmojiPack(id: UUID) {
                    enqueueTask("set active emoji pack $id") {
                        streakEmojiPacksController.setCurrent(id)
                        refreshState()
                    }
                }

                override fun create(basedOn: UUID) {
                    enqueueTask("create new emoji pack based on $basedOn") {
                        streakEmojiPacksController.create(basedOn)
                        refreshState()
                    }
                }

                override fun share(id: UUID, peerId: Long) {
                    enqueueTask("share emoji pack $id with $peerId") {
                        val emojiPack = streakEmojiPacksController.getAll().find { it.id == id }
                            ?: return@enqueueTask

                        serviceMessagesController.sendEmojiPackImport(
                            UserConfig.selectedAccount,
                            peerId,
                            emojiPack
                        )

                        BulletinHelper.show(
                            Strings.status_success_emoji_pack_shared(),
                            "msg_share"
                        )
                    }
                }

                override fun update(emojiPack: StreakEmojiPack) {
                    enqueueTask("update emoji pack ${emojiPack.id}") {
                        streakEmojiPacksController.update(emojiPack)
                        refreshState()
                    }
                }

                override fun delete(id: UUID) {
                    enqueueTask("delete emoji pack $id") {
                        streakEmojiPacksController.delete(id)
                        refreshState()
                    }
                }
            }

            presentFragment(StreakEmojiPackSelectFragment(viewModel))
        }

        settingsActionCallbackRegistry.freeze()
    }
}
