package ru.n08i40k.streaks.guide.presentation

import android.content.Context
import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import ru.n08i40k.guides.util.createGuideNavigationLayout
import ru.n08i40k.guides.api.GuideBadgeSession
import ru.n08i40k.guides.api.GuideMessageContent
import ru.n08i40k.guides.api.GuidePresentation
import ru.n08i40k.guides.scene.GuideScene
import ru.n08i40k.streaks.guide.badge.StreakGuideBadges
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.override.FixupCalendarActivity
import ru.n08i40k.streaks.ui.StreakControlFragment
import ru.n08i40k.streaks.ui.TimeZoneSelectFragment

internal class StreakGuidePresentation : GuidePresentation {
    override fun message(account: Int, source: TLRPC.Message, content: GuideMessageContent): MessageObject {
        source.message = (content as StreakGuideMessage).marker

        return MessageObject(account, source, true, false)
    }

    override fun openBadges(root: View, account: Int): GuideBadgeSession =
        StreakGuideBadges(root, account)

    override fun panel(context: Context, key: String, peer: TLObject, account: Int): GuideScene {
        val user = peer as? TLRPC.User ?: error("Streak lessons require a private user")

        return when (key) {
            StreakGuidePanels.STREAK_CREATED -> creationPopupPanel(context, user)
            StreakGuidePanels.CONTROL -> controlPanel(context)
            StreakGuidePanels.TIME_ZONE -> timeZonePanel(context)
            StreakGuidePanels.CALENDAR -> calendarPanel(context, user, account)
            StreakGuidePanels.REBUILD -> rebuildPanel(context, user, account)
            StreakGuidePanels.PROFILE -> profilePanel(context, user, account)
            StreakGuidePanels.STREAK_INFO -> streakInfoPanel(context, user, account)

            StreakGuidePanels.CONFIRM_REBUILD -> dialogPanel(context) {
                StreakControlFragment.createConfirmDialog(
                    it,
                    Strings.dialog_control_rebuild_streak_title(),
                    Strings.dialog_control_rebuild_streak_message(),
                    Strings.dialog_control_rebuild_button(),
                )
            }

            StreakGuidePanels.CONFIRM_SYNC_OFFER -> dialogPanel(context) {
                StreakControlFragment.createConfirmDialog(
                    it,
                    Strings.dialog_control_sync_offer_title(),
                    Strings.dialog_control_sync_offer_message(),
                    Strings.dialog_control_sync_offer_button(),
                )
            }

            StreakGuidePanels.CONFIRM_TIME_ZONE -> dialogPanel(context) {
                TimeZoneSelectFragment.createChangeDialog(it, hasPet = false) {}
            }

            StreakGuidePanels.CONFIRM_CALENDAR_RESTORE -> dialogPanel(context) {
                FixupCalendarActivity.createManualRestoreDialog(it)
            }

            StreakGuidePanels.CONFIRM_CALENDAR_REBUILD -> dialogPanel(context) {
                FixupCalendarActivity.createRebuildOfferDialog(it)
            }

            else -> error("Unknown Streaks tutorial panel: $key")
        }
    }
}

internal fun sheetHost(context: Context, account: Int): BaseFragment =
    object : BaseFragment() {}.apply {
        setCurrentAccount(account)
        setParentLayout(createGuideNavigationLayout(requireNotNull(AndroidUtilities.findActivity(context))))
    }
