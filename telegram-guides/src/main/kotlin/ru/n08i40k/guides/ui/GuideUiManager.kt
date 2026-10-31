package ru.n08i40k.guides.ui

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.NotificationCenter
import org.telegram.ui.LaunchActivity
import ru.n08i40k.guides.api.GuideLogger
import ru.n08i40k.guides.api.GuidePresentation
import ru.n08i40k.guides.catalog.GuideCatalogDialog
import ru.n08i40k.guides.catalog.GuideCatalogEntry
import ru.n08i40k.guides.catalog.GuideProgress
import ru.n08i40k.guides.demo.ChatListGuideScript
import ru.n08i40k.guides.model.GuideScript

class GuideUiManager(
    private val logger: GuideLogger = GuideLogger.DEFAULT,
    private val scriptFactory: () -> GuideScript = ChatListGuideScript::create,
    private val catalogFactory: ((Context) -> List<GuideCatalogEntry>)? = null,
    private val presentation: GuidePresentation? = null,
    private val debug: () -> Boolean = { false },
    private val progressFactory: ((Context) -> GuideProgress)? = null,
    private val labels: () -> GuideLabels = { GuideLabels() },
    private val link: ((GuideCatalogEntry) -> String)? = null,
) : Application.ActivityLifecycleCallbacks {
    private var activity: Activity? = null
    private var dialog: Dialog? = null
    private var started = false
    private val application
        get() = ApplicationLoader.applicationContext as Application

    private val themeObserver = NotificationCenter.NotificationCenterDelegate { _, _, _ ->
        when (val page = dialog) {
            is GuideDialog -> page.updateTheme()
            is GuideCatalogDialog -> page.refreshTheme()
        }
    }

    fun start() {
        if (started)
            return

        started = true
        application.registerActivityLifecycleCallbacks(this)
        NotificationCenter.getGlobalInstance().addObserver(themeObserver, NotificationCenter.didSetNewTheme)
        refresh()
    }

    fun refresh() {
        if (!started)
            return

        LaunchActivity.instance?.let(::attach)
    }

    fun pause() =
        detach()

    fun destroy() {
        if (!started)
            return

        started = false
        application.unregisterActivityLifecycleCallbacks(this)
        NotificationCenter.getGlobalInstance().removeObserver(themeObserver, NotificationCenter.didSetNewTheme)
        detach()
    }

    private fun attach(host: Activity) {
        if (!started || host.isFinishing || host.isDestroyed)
            return

        if (activity !== host) {
            detach()
            activity = host
        }
    }

    fun show(entryId: String? = null): Boolean {
        val host = activity ?: LaunchActivity.instance ?: return false

        if (!started || host.isFinishing || host.isDestroyed)
            return false

        dialog?.apply {
            setOnDismissListener(null)
            dismiss()
        }

        val page = createPage(host)

        dialog = page
        page.setOnDismissListener {
            dialog = null
        }
        page.show()

        return entryId == null || (page as? GuideCatalogDialog)?.open(entryId) == true
    }

    private fun createPage(host: Activity): Dialog {
        val factory = catalogFactory
            ?: return GuideDialog(host, scriptFactory(), logger, presentation = presentation, labels = labels())

        return GuideCatalogDialog(
            host,
            factory(host),
            progressFactory?.invoke(host) ?: GuideProgress(host.getSharedPreferences("telegram_guides", 0), "completed"),
            debug,
            logger,
            presentation,
            labels(),
            link,
        )
    }

    private fun detach() {
        activity = null
        val page = dialog
        dialog = null
        page?.dismiss()
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is LaunchActivity)
            attach(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (this.activity === activity)
            detach()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity === activity)
            detach()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
