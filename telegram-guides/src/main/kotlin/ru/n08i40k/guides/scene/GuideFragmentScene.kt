package ru.n08i40k.guides.scene

import android.content.Context
import android.graphics.PointF
import android.view.View
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import ru.n08i40k.guides.api.GuideExtension
import ru.n08i40k.guides.model.GuideAction
import ru.n08i40k.guides.util.GuideCleanup
import ru.n08i40k.guides.util.centerPoint

open class GuideFragmentScene(
    context: Context,
    val fragment: BaseFragment,
    private val target: suspend (String) -> View,
    private val targetPoint: (String, View) -> PointF = { _, view -> view.centerPoint() },
    private val scrollTarget: suspend (String) -> Unit = { error("No scroll targets in this scene: $it") },
    private val command: suspend (GuideExtension) -> Unit = { error("Unsupported command: $it") },
    private val refreshTheme: () -> Unit = {},
    private val cleanup: () -> Unit = {},
    private val styleActionBar: Boolean = true,
) : GuideViewportScene(context) {
    private val host = GuideFragmentHost(context)

    init {
        addContent(host.view)
    }

    override fun onFirstShow() =
        host.show(fragment)

    override suspend fun perform(action: GuideAction) {
        when (action) {
            is GuideAction.TapTarget -> {
                val view = target(action.target)

                tap(view, targetPoint(action.target, view), press = true)
            }

            is GuideAction.ScrollTarget -> scrollTarget(action.target)

            is GuideAction.Extension -> command(action.command)

            else -> error("Unsupported page action: $action")
        }
    }

    override fun updateTheme() {
        fragment.themeDescriptions?.forEach { it.setColor(Theme.getColor(it.currentKey), false, false) }

        if (styleActionBar)
            fragment.actionBar?.apply {
                backgroundColor = Theme.getColor(Theme.key_actionBarDefault)
                setTitleColor(Theme.getColor(Theme.key_actionBarDefaultTitle))
                setItemsColor(Theme.getColor(Theme.key_actionBarDefaultIcon), false)
            }

        if (fragment.fragmentView != null)
            refreshTheme()

        screen.invalidate()
    }

    override fun onDispose() =
        GuideCleanup.run(host::dispose, cleanup)
}
