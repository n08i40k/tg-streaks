package ru.n08i40k.guides.catalog

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import ru.n08i40k.guides.api.GuideLogger
import ru.n08i40k.guides.api.GuidePresentation
import ru.n08i40k.guides.profile.GuideProfiles
import ru.n08i40k.guides.ui.GuideDialog
import ru.n08i40k.guides.ui.GuideLabels

class GuideCatalogDialog(
    context: Context,
    private val entries: List<GuideCatalogEntry>,
    private val progress: GuideProgress,
    private val debug: () -> Boolean,
    private val logger: GuideLogger,
    private val presentation: GuidePresentation?,
    private val labels: GuideLabels = GuideLabels(),
    private val link: ((GuideCatalogEntry) -> String)? = null,
) : Dialog(context) {
    private val profiles = GuideProfiles(logger = logger)
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val root = FrameLayout(context)
    private var page: GuideDialog? = null

    private val primaryColor = Theme.key_windowBackgroundWhiteBlackText
    private val secondaryColor = Theme.key_windowBackgroundWhiteGrayText
    private val accentColor = Theme.key_windowBackgroundWhiteBlueText

    init {
        require(entries.isNotEmpty()) { "The catalog needs at least one entry" }
        require(entries.distinctBy { it.id }.size == entries.size) { "Catalog entry IDs must be unique" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.addView(
            ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(body)
            }
        )
        setContentView(root)
        render()
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(MATCH_PARENT, MATCH_PARENT)
    }

    override fun dismiss() {
        page?.setOnDismissListener(null)
        page?.dismiss()
        page = null
        super.dismiss()
    }

    fun updateTheme() {
        window?.setBackgroundDrawable(ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray)))
        page?.updateTheme()
    }

    fun refreshTheme() {
        if (isShowing)
            render()
    }

    private fun render() {
        val ids = entries.map { it.id }
        val completed = progress.completed()
        val completedCount = ids.count { it in completed }

        body.removeAllViews()
        body.setPadding(dp(20), dp(20), dp(20), dp(24))
        body.addView(header())
        body.addView(text(labels.catalogDescription, 16f, secondaryColor).apply { setPadding(0, dp(8), 0, dp(20)) })
        body.addView(text(labels.progress(completedCount, ids.size), 14f, accentColor, bold = true))
        body.addView(
            progressBar(completedCount, ids.size),
            LinearLayout.LayoutParams(MATCH_PARENT, dp(4)).apply {
                topMargin = dp(10)
                bottomMargin = dp(24)
            }
        )
        body.addView(text(labels.chooseLesson, 15f, secondaryColor, bold = true).apply { setPadding(dp(4), 0, 0, dp(12)) })

        entries.forEachIndexed { index, entry ->
            body.addView(
                card(
                    entry,
                    index,
                    available = GuideProgress.available(ids, completed, index, debug()),
                    completed = entry.id in completed
                ),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
            )
        }

        updateTheme()
    }

    private fun header() =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL

            addView(text(labels.catalogTitle, 28f, primaryColor, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(
                text("×", 30f, secondaryColor).apply {
                    gravity = Gravity.CENTER
                    contentDescription = labels.close
                    background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1)
                    setOnClickListener { dismiss() }
                },
                LinearLayout.LayoutParams(dp(44), dp(44))
            )
        }

    private fun progressBar(value: Int, total: Int) =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = total
            progress = value
            progressTintList = ColorStateList.valueOf(Theme.getColor(accentColor))
            progressBackgroundTintList = ColorStateList.valueOf(Theme.multAlpha(Theme.getColor(accentColor), .15f))
        }

    private fun card(entry: GuideCatalogEntry, index: Int, available: Boolean, completed: Boolean): View {
        val state = when {
            !available -> labels.locked
            completed -> labels.replayLesson
            else -> labels.startLesson
        }
        val stateColor = if (available) accentColor else secondaryColor

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = Theme.createSimpleSelectorRoundRectDrawable(
                dp(16),
                Theme.getColor(Theme.key_windowBackgroundWhite),
                Theme.getColor(Theme.key_listSelector)
            )

            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL

                    addView(
                        text(if (completed) "✓" else "${index + 1}", 18f, stateColor, bold = true).apply {
                            gravity = Gravity.CENTER
                            background = Theme.createRoundRectDrawable(dp(12), Theme.multAlpha(Theme.getColor(accentColor), .12f))
                        },
                        LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) }
                    )
                    addView(text(entry.title, 18f, primaryColor, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

                }
            )
            addView(text(entry.description, 15f, secondaryColor).apply { setPadding(0, dp(12), 0, dp(16)) })
            addView(text(if (available) "$state  ›" else state, 15f, stateColor, bold = available))

            alpha = if (available) 1f else .6f
            contentDescription = "${entry.title}. ${entry.description}. $state"

            if (available)
                setOnClickListener { open(entry) }

            link?.let { link ->
                setOnLongClickListener { card ->
                    showLinkMenu(card, link(entry))
                    true
                }
            }
        }
    }

    private fun showLinkMenu(card: View, url: String) {
        ItemOptions.makeOptions(root, null, card)
            .add(R.drawable.msg_link, labels.copyLink) {
                if (AndroidUtilities.addToClipboard(url))
                    BulletinFactory.of(root, null).createCopyLinkBulletin().show()
            }
            .show()
    }

    fun open(id: String): Boolean {
        open(entries.firstOrNull { it.id == id } ?: return false)

        return true
    }

    private fun open(entry: GuideCatalogEntry) {
        if (page != null)
            return

        val child = GuideDialog(
            context,
            entry.create(),
            logger,
            profiles,
            presentation,
            completed = { progress.complete(entry.id) },
            labels = labels
        )

        page = child
        child.setOnDismissListener {
            page = null
            render()
        }
        child.show()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(Theme.getColor(color))

            if (bold)
                typeface = AndroidUtilities.bold()
        }

    private fun dp(value: Int) =
        AndroidUtilities.dp(value.toFloat())
}
