package ru.n08i40k.guides.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import ru.n08i40k.guides.api.GuideLogger
import ru.n08i40k.guides.api.GuidePresentation
import ru.n08i40k.guides.demo.ChatListGuideScript
import ru.n08i40k.guides.model.GuideScript
import ru.n08i40k.guides.player.GuidePlayer
import ru.n08i40k.guides.profile.GuideProfileLoadException
import ru.n08i40k.guides.profile.GuideProfiles

class GuideDialog(
    context: Context,
    private val script: GuideScript = ChatListGuideScript.create(),
    private val logger: GuideLogger = GuideLogger.DEFAULT,
    private val profiles: GuideProfiles = GuideProfiles(logger = logger),
    private val presentation: GuidePresentation? = null,
    private val completed: () -> Unit = {},
    private val labels: GuideLabels = GuideLabels(),
) : Dialog(context) {
    private var player: GuidePlayer? = null

    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }
    private val titleView = label(20f)
    private val headingView = label(22f)
    private val descriptionView = label(16f, bold = false)
    private val nextButton = label(16f).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { player?.next() }
    }
    private val preview = FrameLayout(context)
    private val previewFrame = FrameLayout(context).apply {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
        }
        addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        body.addView(titleView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(20) })
        body.addView(previewFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        body.addView(headingView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })
        body.addView(
            descriptionView,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(24)
            }
        )
        body.addView(nextButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        setContentView(body)

        player = GuidePlayer(
            script,
            preview,
            profiles,
            presentation,
            labels,
            onStep = { step, index, count ->
                titleView.text = "${script.title} · ${index + 1}/$count"
                headingView.text = step.heading
                descriptionView.text = step.description
                nextButton.text = if (index == count - 1) labels.done else labels.next
            },
            onBusy = { busy ->
                nextButton.isEnabled = !busy
                nextButton.alpha = if (busy) .5f else 1f
            },
            onFinished = {
                completed()
                dismiss()
            },
            onError = ::showError,
        )
        updateTheme()
    }

    override fun onStart() {
        super.onStart()
        window?.apply {
            decorView.setPadding(0, 0, 0, 0)
            setLayout(MATCH_PARENT, MATCH_PARENT)
        }
        player?.start()
    }

    override fun dismiss() {
        player?.dispose()
        player = null
        super.dismiss()
    }

    fun updateTheme() {
        val background = Theme.getColor(Theme.key_windowBackgroundGray)

        body.setBackgroundColor(background)
        window?.setBackgroundDrawable(ColorDrawable(background))
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        headingView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        nextButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
        nextButton.background = Theme.createSimpleSelectorRoundRectDrawable(
            dp(8),
            Theme.getColor(Theme.key_featuredStickers_addButton),
            Theme.getColor(Theme.key_featuredStickers_addButtonPressed)
        )
        previewFrame.invalidate()
        player?.updateTheme()
    }

    private fun showError(error: Throwable) {
        dismiss()
        logger.error("play guide script", error)
        AlertDialog.Builder(context)
            .setTitle(labels.errorTitle)
            .setMessage(
                if (error is GuideProfileLoadException) labels.profileError(error.profile)
                else labels.errorDescription
            )
            .setPositiveButton(LocaleController.getString(R.string.OK), null)
            .show()
    }

    private fun label(size: Float, bold: Boolean = true) =
        TextView(context).apply {
            textSize = size
            gravity = Gravity.CENTER_HORIZONTAL

            if (bold)
                typeface = AndroidUtilities.bold()
        }

    private fun dp(value: Int) =
        AndroidUtilities.dp(value.toFloat())
}
