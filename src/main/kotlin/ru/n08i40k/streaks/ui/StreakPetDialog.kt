package ru.n08i40k.streaks.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.browser.Browser
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.EditTextBoldCursor
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.constants.Url
import ru.n08i40k.streaks.controller.StreakPetsController
import ru.n08i40k.streaks.data.StreakPetLevel.Companion.levels
import ru.n08i40k.streaks.data.StreakLevel
import ru.n08i40k.streaks.data.StreakPetTaskPayload
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.resource.ResourcesProvider
import ru.n08i40k.streaks.util.AnimatedEmojiView
import ru.n08i40k.streaks.util.colorBlend
import ru.n08i40k.streaks.util.dp
import kotlin.math.abs

class StreakPetDialog(
    private val fragment: BaseFragment,
    val accountId: Int,
    val peerUserId: Long,
    initialState: StreakPetsController.ViewStateSnapshot,
    private val resourcesProvider: ResourcesProvider,
    private val onRenameRequested: (String) -> Unit,
    private val onDismissed: (() -> Unit)? = null,
) : Dialog(
    fragment.context ?: fragment.parentActivity
    ?: error("Cannot create streak pet dialog without a context"),
    android.R.style.Theme_Translucent_NoTitleBar,
), NotificationCenter.NotificationCenterDelegate {
    private var state = initialState
    private val lastSelectableStage: Int
        get() = (levels.indexOf(state.pet.level) + 1)
            .coerceAtMost(levels.lastIndex)

    private var selected = levels.indexOf(state.pet.level)
    private var destroyed = false
    private val themeUpdates = mutableListOf<() -> Unit>()
    private val taskThemeUpdates = mutableListOf<() -> Unit>()
    private var childDialog: AlertDialog? = null
    private var renameInput: EditTextBoldCursor? = null
    private lateinit var root: FrameLayout
    private lateinit var header: Header
    private lateinit var petSection: PetSection
    private lateinit var tasksCard: LinearLayout
    private val badgeViews = mutableListOf<Pair<View, Int>>()

    private fun color(key: Int) = Theme.getColor(key, fragment.resourceProvider)

    private fun rounded(fill: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }

    fun matches(accountId: Int, peerUserId: Long) =
        this.accountId == accountId && this.peerUserId == peerUserId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                applyLegacyKeyboardResize(this)
            }
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }

        root = FrameLayout(context)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(20), dp(10), dp(28))
        }

        ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false

            addView(content, FrameLayout.LayoutParams(-1, -2))

            root.addView(this, FrameLayout.LayoutParams(-1, -1))

            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val padding = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                            WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
                )
                setPadding(padding.left, padding.top, padding.right, padding.bottom)
                insets
            }
        }

        header = Header(content)
        petSection = PetSection(content)

        tasksCard = card(content)
        buildBadges(content)

        val help = text(
            Strings.sheet_pet_learn_more(),
            13,
            Theme.key_windowBackgroundWhiteBlueText
        ).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setOnClickListener { openHelp() }
        }
        content.addView(help, row(-1, -2, top = 8))

        setContentView(root)

        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme)

        renderState()
        root.requestApplyInsets()
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(-1, -1)
    }

    private fun row(width: Int, height: Int, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(
            if (width < 0) width else dp(width),
            if (height < 0) height else dp(height)
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun text(
        value: CharSequence,
        size: Int,
        key: Int = Theme.key_windowBackgroundWhiteBlackText,
        updates: MutableList<() -> Unit> = themeUpdates,
    ): TextView =
        TextView(context).apply {
            text = value
            textSize = size.toFloat()
            typeface = Typeface.DEFAULT
            includeFontPadding = false

            updates += { setTextColor(color(key)) }
        }

    private fun button(icon: Int, description: String, action: () -> Unit): ImageView =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setImageResource(icon)
            minimumWidth = dp(48)
            minimumHeight = dp(48)

            contentDescription = description
            isFocusable = true
            setOnClickListener { action() }

            themeUpdates += {
                setColorFilter(color(Theme.key_windowBackgroundWhiteBlackText))
                background = Theme.createSelectorDrawable(color(Theme.key_listSelector), 1)
                setPadding(dp(13), dp(13), dp(13), dp(13))
            }
        }

    private inner class Header(content: LinearLayout) {
        private val nameView: TextView
        private val daysView: TextView
        private val ownerAvatar: BackupImageView
        private val peerAvatar: BackupImageView

        init {
            val toolbar = FrameLayout(context)
            content.addView(toolbar, row(-1, 48))

            val close = button(R.drawable.msg_close, Strings.sheet_pet_close()) { dismiss() }
            toolbar.addView(close, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START))

            val more = button(R.drawable.ic_ab_other, Strings.sheet_pet_more()) { showMenu() }
            toolbar.addView(more, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END))

            themeUpdates += {
                for (view in listOf(close, more)) {
                    view.background = InsetDrawable(
                        Theme.createSimpleSelectorRoundRectDrawable(
                            dp(14),
                            Theme.multAlpha(color(Theme.key_windowBackgroundWhiteGrayText), .2f),
                            color(Theme.key_listSelector)
                        ), dp(11)
                    )
                    view.setPadding(dp(15), dp(15), dp(15), dp(15))
                }
            }

            nameView = text("", 15).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { showRename() }
                contentDescription = Strings.sheet_pet_rename_title()
            }
            toolbar.addView(nameView, FrameLayout.LayoutParams(-2, dp(48), Gravity.CENTER).apply {
                leftMargin = dp(54)
                rightMargin = dp(54)
            })

            themeUpdates += {
                val pencil = context.getDrawable(R.drawable.msg_edit)?.mutate()?.apply {
                    setBounds(0, 0, dp(16), dp(16))
                    setTint(color(Theme.key_windowBackgroundWhiteBlackText))
                }
                nameView.setCompoundDrawablesRelative(null, null, pencil, null)
                nameView.compoundDrawablePadding = dp(5)
                nameView.background = InsetDrawable(
                    Theme.createSimpleSelectorRoundRectDrawable(
                        dp(24),
                        Theme.multAlpha(color(Theme.key_windowBackgroundWhite), .35f),
                        color(Theme.key_listSelector)
                    ),
                    0, dp(11), 0, dp(11)
                )
                nameView.setPadding(dp(12), 0, dp(12), 0)
            }

            val summary = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            content.addView(summary, row(-1, -2, top = 2))

            val days = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), 0, 0, 0)
            }
            summary.addView(days, LinearLayout.LayoutParams(0, -2, 1f))
            days.addView(text(Strings.sheet_pet_streak_days(), 15))
            daysView = text("", 44)
            days.addView(daysView)

            val avatars = FrameLayout(context)
            summary.addView(avatars, row(84, 48))
            ownerAvatar = avatar()
            peerAvatar = avatar()
            avatars.addView(
                ownerAvatar,
                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or Gravity.START)
            )
            avatars.addView(
                peerAvatar,
                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or Gravity.END)
            )
        }

        fun render() {
            nameView.text = state.pet.name
            daysView.text = java.text.NumberFormat.getIntegerInstance().apply {
                isGroupingUsed = false
            }.format(state.streak?.length ?: 0)

            bindAvatar(ownerAvatar, state.ownerUser)
            bindAvatar(peerAvatar, state.peerUser)
        }
    }

    private fun buildBadges(content: LinearLayout) {
        val badges = card(content)
        badges.addView(text(Strings.sheet_pet_badges_title(), 16), row(-1, -2, bottom = 12))

        val badgeRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        badges.addView(badgeRow, row(-1, -2))

        val pack = Plugin.getInstance().streakEmojiPacksController.getCurrent()
        StreakLevel.levels.filter { it.length > 0 }.forEachIndexed { index, level ->
            if (index > 0) badgeRow.addView(
                text(
                    "–",
                    18,
                    Theme.key_windowBackgroundWhiteBlueText
                ).apply {
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(0, -2, .5f)
            )

            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            badgeRow.addView(column, LinearLayout.LayoutParams(0, -2, 1f))

            val emoji = pack.getEmoji(level)
            val image = BackupImageView(context)
            image.imageReceiver.currentAccount = accountId
            AnimatedEmojiView.apply(
                image,
                emoji.documentId,
                28,
                emoji.accentColor?.toArgb(),
                accountId = accountId
            )
            column.addView(image, row(28, 28))
            column.addView(
                text(
                    Strings.sheet_pet_badge_days(level.length), 11,
                    Theme.key_windowBackgroundWhiteGrayText
                ).apply { gravity = Gravity.CENTER }, row(-1, -2, top = 5)
            )

            badgeViews += column to level.length
        }
    }

    private fun avatar() = BackupImageView(context).apply {
        setRoundRadius(dp(22))
        imageReceiver.currentAccount = accountId
    }

    private fun bindAvatar(view: BackupImageView, user: TLRPC.User?) {
        val placeholder = AvatarDrawable()
        if (user != null) placeholder.setInfo(accountId, user)
        view.imageReceiver.setForUserOrChat(user, placeholder)
    }

    private inner class PetSection(content: LinearLayout) {
        private val petView: StreakPetAnimationView
        private val previousButton: ImageView
        private val nextButton: ImageView
        private val lockedView: TextView
        private val progressView: PetProgressView
        private val progressCaption: TextView

        init {
            val stage = FrameLayout(context)
            var startX = 0f
            var startY = 0f

            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
            stage.setOnTouchListener { view, event ->
                val dx = event.x - startX
                val dy = event.y - startY
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.x
                        startY = event.y
                        view.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> if (abs(dy) > touchSlop && abs(dy) >= abs(dx)) {
                        view.parent.requestDisallowInterceptTouchEvent(false)
                    }
                    MotionEvent.ACTION_UP -> {
                        view.parent.requestDisallowInterceptTouchEvent(false)
                        if (abs(dx) >= dp(40) && abs(dx) > abs(dy)) {
                            selectStage(selected + if (dx < 0) 1 else -1)
                        } else {
                            view.performClick()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> view.parent.requestDisallowInterceptTouchEvent(false)
                }
                true
            }

            // At the reference width (~412 dp), the media occupies roughly 206 dp.
            val petSize = minOf(
                240, (context.resources.displayMetrics.widthPixels /
                        context.resources.displayMetrics.density * .5f).toInt()
            )
            content.addView(stage, row(-1, petSize + 32))

            petView = StreakPetAnimationView(context, resourcesProvider, accountId)
            stage.addView(
                petView,
                FrameLayout.LayoutParams(dp(petSize), dp(petSize), Gravity.CENTER)
            )

            previousButton = button(R.drawable.msg_arrow_back, Strings.sheet_pet_previous_stage()) {
                selectStage(selected - 1)
            }
            nextButton = button(R.drawable.msg_arrow_forward, Strings.sheet_pet_next_stage()) {
                selectStage(selected + 1)
            }
            stage.addView(
                previousButton,
                FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.START)
            )
            stage.addView(
                nextButton,
                FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.END)
            )

            lockedView = text(Strings.sheet_pet_locked(), 14).apply { gravity = Gravity.CENTER }
            stage.addView(lockedView, FrameLayout.LayoutParams(-1, dp(32), Gravity.BOTTOM))

            progressView = PetProgressView(context).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            }
            content.addView(
                progressView,
                row(180, 20).apply { gravity = Gravity.CENTER_HORIZONTAL })

            progressCaption = text("", 12, Theme.key_windowBackgroundWhiteGrayText).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(12))
            }
            content.addView(progressCaption, row(-1, -2, bottom = 4))
        }

        fun render() {
            val progress = StreakPetPresentation.progress(state.pet.points, selected)

            petView.bind(levels[selected])
            petView.alpha = if (progress.locked) .35f else 1f
            petView.contentDescription =
                if (progress.locked) Strings.sheet_pet_locked() else state.pet.name

            previousButton.visibility = if (selected > 0) View.VISIBLE else View.INVISIBLE
            nextButton.visibility =
                if (selected < levels.lastIndex && !progress.locked) View.VISIBLE else View.INVISIBLE

            lockedView.visibility = if (progress.locked) View.VISIBLE else View.INVISIBLE

            progressView.visibility = if (progress.locked) View.GONE else View.VISIBLE
            progressView.fraction = progress.fraction
            progressView.text = when {
                progress.max -> Strings.sheet_pet_max_level()
                else -> "${progress.points}/${progress.target}"
            }

            progressCaption.text = when {
                progress.locked -> Strings.sheet_pet_points_to_unlock(
                    (levels[selected - 1].maxPoints - state.pet.points).toString()
                )

                progress.max -> Strings.sheet_pet_max_level()
                else -> Strings.sheet_pet_points_to_next_stage((progress.target - progress.points).toString())
            }
            progressView.invalidate()
        }

        fun applyTheme() {
            progressView.accent = color(Theme.key_windowBackgroundWhiteBlueText)
            progressView.track = color(Theme.key_windowBackgroundWhite)
            progressView.outline = color(Theme.key_windowBackgroundWhite)

            // The label stays legible across both filled and empty portions of the track.
            progressView.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText))
            progressView.setShadowLayer(
                dp(1).toFloat(),
                0f,
                0f,
                color(Theme.key_windowBackgroundWhite)
            )
            progressView.invalidate()
        }

        fun release() = petView.release()
    }

    private fun card(content: LinearLayout): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        content.addView(this, row(-1, -2, top = 10))

        themeUpdates += { background = rounded(color(Theme.key_windowBackgroundWhite)) }
    }

    private fun renderTasks() {
        tasksCard.removeAllViews()
        taskThemeUpdates.clear()

        // Task views use their own theme list to avoid retaining old snapshots.
        fun taskText(value: String, size: Int, key: Int) =
            text(value, size, key, updates = taskThemeUpdates)

        tasksCard.addView(
            taskText(Strings.sheet_pet_tasks_title(), 16, Theme.key_windowBackgroundWhiteBlackText),
            row(-1, -2, bottom = 6)
        )

        state.tasks.forEach { task ->
            val line = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            tasksCard.addView(line, row(-1, -2))

            val check = PetTaskCheckView(context, task.isCompleted)
            line.addView(check, row(30, 30))
            taskThemeUpdates += {
                check.accent = color(Theme.key_windowBackgroundWhiteBlueText)
                check.muted = color(Theme.key_windowBackgroundWhiteGrayText)
                check.invalidate()
            }

            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, 0)
            }
            line.addView(body, LinearLayout.LayoutParams(0, -2, 1f))

            val title = when (task.payload) {
                is StreakPetTaskPayload.ExchangeOneMessage -> Strings.sheet_pet_tasks_exchange_one_message()
                is StreakPetTaskPayload.SendFourMessagesEach -> Strings.sheet_pet_tasks_send_four_messages_each()
                is StreakPetTaskPayload.SendTenMessagesEach -> Strings.sheet_pet_tasks_send_ten_messages_each()
            }
            body.addView(taskText(title, 15, Theme.key_windowBackgroundWhiteBlackText))
            body.addView(
                taskText(
                    Strings.sheet_pet_growth_points(task.type.points), 12,
                    Theme.key_windowBackgroundWhiteBlueText
                ), row(-1, -2, top = 3)
            )

            line.contentDescription =
                "$title. ${Strings.sheet_pet_growth_points(task.type.points)}. " +
                        if (task.isCompleted) Strings.sheet_pet_task_completed() else Strings.sheet_pet_task_pending()
            line.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }

    private fun selectStage(index: Int) {
        if (index !in 0..lastSelectableStage) return
        selected = index
        petSection.render()
        applyTheme()
    }

    fun updateState(newState: StreakPetsController.ViewStateSnapshot) {
        if (destroyed) return
        state = newState
        selected = selected.coerceAtMost(lastSelectableStage)
        if (::root.isInitialized) renderState()
    }

    private fun renderState() {
        header.render()

        badgeViews.forEach { (view, days) ->
            view.alpha = if ((state.streak?.length ?: 0) >= days) 1f else .35f
        }

        renderTasks()
        petSection.render()
        applyTheme()
    }

    private fun applyTheme() {
        if (!::root.isInitialized || destroyed) return

        val background = color(Theme.key_windowBackgroundGray)
        val luminance =
            Color.red(background) * .299 + Color.green(background) * .587 + Color.blue(background) * .114

        val stage = levels[selected]
        val locked = selected > levels.indexOf(state.pet.level)
        val top =
            if (locked) colorBlend(background, color(Theme.key_windowBackgroundWhiteGrayText), .25f)
            else colorBlend(
                Color.parseColor(stage.gradientStart),
                background,
                if (luminance < 128) .35f else 0f
            )
        root.background = PetBackgroundDrawable(top, colorBlend(top, background, .22f), background)

        themeUpdates.forEach { it() }
        taskThemeUpdates.forEach { it() }
        petSection.applyTheme()

        renameInput?.apply {
            setTextColor(color(Theme.key_dialogTextBlack))
            setHintTextColor(color(Theme.key_windowBackgroundWhiteGrayText))
            setCursorColor(color(Theme.key_windowBackgroundWhiteBlueText))
        }
        childDialog?.themeDescriptions?.forEach { it.setColor(color(it.currentKey), false, false) }

        window?.let { window ->
            WindowCompat.getInsetsController(window, root).apply {
                isAppearanceLightStatusBars = luminance >= 128
                setAppearanceLightNavigationBars(luminance >= 128)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                applyLegacySystemBarColors(window, background)
            }
        }
    }

    // Android 8–10 need resize mode to dispatch keyboard insets through AndroidX.
    @Suppress("DEPRECATION")
    private fun applyLegacyKeyboardResize(window: Window) {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    // Before Android 15, edge-to-edge layout still needs explicit system bar colors.
    @Suppress("DEPRECATION")
    private fun applyLegacySystemBarColors(window: Window, background: Int) = with(window) {
        addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        statusBarColor = Color.TRANSPARENT
        navigationBarColor = background
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.didSetNewTheme) applyTheme()
    }

    private fun openHelp() {
        Browser.openUrl(context, Url.Docs.STREAK_PET)
    }

    private fun showMenu() {
        childDialog?.dismiss()
        childDialog = AlertDialog.Builder(context, fragment.resourceProvider)
            .setItems(
                arrayOf(
                    Strings.sheet_pet_rename_title(),
                    Strings.sheet_pet_about()
                )
            ) { _, which ->
                if (which == 0) showRename() else openHelp()
            }.show()
    }

    private fun showRename() {
        childDialog?.dismiss()

        val input = EditTextBoldCursor(context).apply {
            setText(state.pet.name)
            hint = Strings.sheet_pet_rename_placeholder()
            setTextSize(18f)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(20))
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setSelectAllOnFocus(true)
        }
        renameInput = input

        val dialog = AlertDialog.Builder(context, fragment.resourceProvider)
            .setTitle(Strings.sheet_pet_rename_title())
            .setView(input)
            .setPositiveButton(Strings.sheet_pet_rename_save()) { _, _ -> }
            .setNegativeButton(Strings.sheet_pet_rename_cancel()) { _, _ -> }
            .create()
        childDialog = dialog
        dialog.setOnDismissListener {
            if (renameInput === input) renameInput = null
            if (childDialog === dialog) childDialog = null
        }

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text?.toString()?.trim()?.take(20).orEmpty()
            if (value.isNotEmpty()) {
                onRenameRequested(value)
                dialog.dismiss()
            }
        }
        applyTheme()
        input.requestFocus()
        AndroidUtilities.showKeyboard(input)
    }

    override fun dismiss() {
        if (destroyed) return
        destroyed = true
        childDialog?.dismiss()
        childDialog = null
        NotificationCenter.getGlobalInstance()
            .removeObserver(this, NotificationCenter.didSetNewTheme)
        if (::petSection.isInitialized) petSection.release()
        super.dismiss()
        onDismissed?.invoke()
    }
}
