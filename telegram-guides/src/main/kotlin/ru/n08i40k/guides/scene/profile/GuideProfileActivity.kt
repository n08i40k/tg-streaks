package ru.n08i40k.guides.scene.profile

import android.content.Context
import android.os.Bundle
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ProfileActivity

open class GuideProfileActivity(arguments: Bundle) : ProfileActivity(arguments) {
    override fun createActionBar(context: Context): ActionBar =
        super.createActionBar(context).apply { setOccupyStatusBar(false) }

    override fun setNavigationBarColor(color: Int) = Unit
}
