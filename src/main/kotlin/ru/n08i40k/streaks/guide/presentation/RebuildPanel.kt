package ru.n08i40k.streaks.guide.presentation

import android.content.Context
import android.widget.FrameLayout
import kotlinx.datetime.TimeZone
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import ru.n08i40k.guides.scene.GuideLayer
import ru.n08i40k.guides.scene.GuideOverlayScene
import ru.n08i40k.guides.scene.GuideScene
import ru.n08i40k.guides.util.takeEmbeddedContent
import ru.n08i40k.streaks.data.Streak
import ru.n08i40k.streaks.ui.rebuild.RebuildBottomSheet
import ru.n08i40k.streaks.ui.rebuild.UserRebuildState
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

internal fun rebuildPanel(context: Context, user: TLRPC.User, account: Int): GuideScene {
    val fragment = sheetHost(context, account)
    val states = mutableListOf<UserRebuildState>(UserRebuildState.InProcess(user, 0, null))

    val holder = FrameLayout(context)
    lateinit var sheet: RebuildBottomSheet

    fun render() {
        holder.removeAllViews()
        sheet = RebuildBottomSheet(fragment, RebuildBottomSheet.TYPE_STREAK, states)

        if (states[0] is UserRebuildState.Done<*>)
            sheet.showResults()
        else
            sheet.showProgress()

        holder.addView(sheet.takeEmbeddedContent())
    }

    return GuideOverlayScene(
        context,
        GuideLayer.SHEET,
        content = {
            render()
            holder
        },
        command = { extension ->
            when (val command = extension as StreakGuideCommand) {
                is StreakGuideCommand.Indexed -> {
                    states[0] = UserRebuildState.InProcess(user, command.days, null)
                    sheet.notifyUserStateChanged(0)
                }

                is StreakGuideCommand.RebuildResult -> {
                    val now = Clock.System.now()
                    val streak = Streak(
                        UserConfig.getInstance(account).clientUserId,
                        user.id,
                        now - (command.days - 1).days,
                        now,
                        now,
                        0,
                        timeZone = TimeZone.currentSystemDefault()
                    )

                    states[0] = UserRebuildState.Done(user, streak)
                    sheet.showResults()
                }

                else -> error("The rebuild sheet does not handle $command")
            }
        },
        refreshTheme = {
            sheet.dismiss()
            render()
        },
        cleanup = { sheet.dismiss() },
    )
}
