package ru.n08i40k.guides.scene.profile

import android.content.Context
import android.graphics.PointF
import android.view.View
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.ProfileActivity
import ru.n08i40k.guides.scene.GuideFragmentScene
import ru.n08i40k.guides.util.`ProfileActivity$nameTextView`

open class ProfileActivityGuideScene(
    context: Context,
    val profile: GuideProfileActivity,
    badgePoint: (View) -> PointF,
) : GuideFragmentScene(
    context,
    profile,
    target = { key ->
        check(key == BADGE) { "Unknown profile target: $key" }

        val names = `ProfileActivity$nameTextView`.invokeExact(profile as ProfileActivity) as Array<SimpleTextView?>

        checkNotNull(names[1]) { "Profile name is not laid out" }
    },
    targetPoint = { _, view -> badgePoint(view) },
    styleActionBar = false,
) {
    companion object {
        const val BADGE = "profile.badge"
    }
}
