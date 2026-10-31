package ru.n08i40k.streaks.guide.presentation

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import ru.n08i40k.guides.scene.GuideLayer
import ru.n08i40k.guides.scene.GuideOverlayScene
import ru.n08i40k.guides.scene.GuideScene
import ru.n08i40k.guides.scene.profile.GuideProfileActivity
import ru.n08i40k.guides.scene.profile.ProfileActivityGuideScene
import ru.n08i40k.guides.util.takeEmbeddedContent
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.data.StreakLevel
import ru.n08i40k.streaks.data.StreakViewData
import ru.n08i40k.streaks.guide.badge.StreakGuideBadges
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.override.StreakInfoBottomSheet
import ru.n08i40k.streaks.ui.upgrade.StreakUpgrade
import ru.n08i40k.streaks.ui.upgrade.StreakUpgradePalette
import ru.n08i40k.streaks.ui.upgrade.StreakUpgradeSceneView
import ru.n08i40k.streaks.ui.upgrade.UPGRADE_HINT_DELAY_MS
import ru.n08i40k.streaks.ui.upgrade.createUpgradeHint
import ru.n08i40k.streaks.ui.upgrade.revealUpgradeHint
import ru.n08i40k.streaks.ui.upgrade.upgradeHintLayoutParams

private const val INFO_LENGTH = 30

internal fun creationPopupPanel(context: Context, user: TLRPC.User): GuideScene {
    val palette = StreakUpgradePalette.fromTheme()
    val scene = StreakUpgradeSceneView(context, StreakUpgrade.of(StreakLevel.Cold, StreakLevel.Days3, user), palette)
    val hint = createUpgradeHint(context, palette, Strings.upgrade_scene_dismiss_hint())
    val root = FrameLayout(context).apply {
        setBackgroundColor(palette.scrim)
        addView(scene, FrameLayout.LayoutParams(-1, -1))
        addView(hint, upgradeHintLayoutParams())
    }

    scene.onFinished = { revealUpgradeHint(hint, UPGRADE_HINT_DELAY_MS) }
    scene.onFailed = { revealUpgradeHint(hint, 0L) }

    return GuideOverlayScene(
        context,
        GuideLayer.DIALOG,
        content = { root },
        target = { target ->
            check(target == StreakGuideTargets.UPGRADE_DISMISS) { "Unknown upgrade target: $target" }
            hint
        },
        cleanup = {
            hint.animate().cancel()
            scene.release()
        },
        contentHeight = ViewGroup.LayoutParams.MATCH_PARENT,

        scrimColor = Color.TRANSPARENT,
    )
}

internal fun profilePanel(context: Context, user: TLRPC.User, account: Int): GuideScene {
    val fragment = object : GuideProfileActivity(Bundle().apply { putLong("user_id", user.id) }) {}

    fragment.setCurrentAccount(account)

    return ProfileActivityGuideScene(context, fragment, badgePoint = StreakGuideBadges::point)
}

internal fun streakInfoPanel(context: Context, user: TLRPC.User, account: Int): GuideScene {
    val fragment = sheetHost(context, account)
    val config = Plugin.getInstance().streakEmojiPacksController.getCurrent()
        .getEmoji(StreakLevel.findByLengthApproximate(INFO_LENGTH))
    val data = StreakViewData(
        INFO_LENGTH,
        config.documentId,
        config.accentColor ?: Color.valueOf(Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon)),
        false
    )
    lateinit var sheet: StreakInfoBottomSheet

    return GuideOverlayScene(
        context,
        GuideLayer.SHEET,
        content = {
            sheet = StreakInfoBottomSheet(fragment, account, user, null, data)
            sheet.takeEmbeddedContent()
        },
        cleanup = { sheet.dismiss() },

        contentHeight = ViewGroup.LayoutParams.MATCH_PARENT,
        sheetHeight = { sheet.containerView.height },
    )
}
