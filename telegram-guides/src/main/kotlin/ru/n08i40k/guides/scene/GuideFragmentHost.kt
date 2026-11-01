package ru.n08i40k.guides.scene

import android.content.Context
import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.BaseFragment
import ru.n08i40k.guides.util.createGuideNavigationLayout

internal class GuideFragmentHost(context: Context) {
    private val layout = createGuideNavigationLayout(requireNotNull(AndroidUtilities.findActivity(context))).apply {
        setRemoveActionBarExtraHeight(true)
        setFragmentStack(arrayListOf())
    }

    val view: View
        get() = layout.view

    fun show(fragment: BaseFragment) {
        check(layout.addFragmentToStack(fragment)) {
            "Telegram refused to open ${fragment.javaClass.name}; its peer must be available in the current account"
        }
        layout.showLastFragment()
        fragment.actionBar?.setOccupyStatusBar(false)
    }

    fun dispose() =
        layout.removeAllFragments()
}
