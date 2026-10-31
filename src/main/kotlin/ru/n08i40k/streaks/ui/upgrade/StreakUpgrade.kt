package ru.n08i40k.streaks.ui.upgrade

import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.data.StreakEmojiInfo
import ru.n08i40k.streaks.data.StreakLevel

internal data class StreakUpgrade(
    val accountId: Int,
    val from: StreakLevel,
    val to: StreakLevel,
    val fromEmoji: StreakEmojiInfo,
    val toEmoji: StreakEmojiInfo,
    val peer: TLRPC.User?,
) {
    companion object {
        fun of(from: StreakLevel, to: StreakLevel, peer: TLRPC.User?, accountId: Int = UserConfig.selectedAccount) =
            Plugin.getInstance().streakEmojiPacksController.getCurrent().let { pack ->
                StreakUpgrade(accountId, from, to, pack.getEmoji(from), pack.getEmoji(to), peer)
            }
    }
}
