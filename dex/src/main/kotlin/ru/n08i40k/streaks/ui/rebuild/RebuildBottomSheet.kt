package ru.n08i40k.streaks.ui.rebuild

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.constants.Emoji
import ru.n08i40k.streaks.data.Streak
import ru.n08i40k.streaks.data.StreakPet
import ru.n08i40k.streaks.event.eject.EjectNotifier
import ru.n08i40k.streaks.extension.name
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.util.AnimatedEmojiView
import ru.n08i40k.streaks.util.LinearProgressView
import kotlin.coroutines.resume

private fun withAlpha(color: Int, alpha: Int): Int =
    (color and 0x00FFFFFF) or (alpha shl 24)

class RebuildBottomSheet(
    baseFragment: BaseFragment,
    private val type: Int,
    private val userRebuildStates: List<UserRebuildState>
) : BottomSheet(baseFragment.parentActivity, false), EjectNotifier.Delegate {
    companion object {
        const val TYPE_STREAK = 0
        const val TYPE_STREAK_PET = 1

        suspend fun launch(
            type: Int,
            userRebuildStates: List<UserRebuildState>
        ): RebuildBottomSheet =
            suspendCancellableCoroutine { cont ->
                AndroidUtilities.runOnUIThread {
                    val fragment = LaunchActivity.getSafeLastFragment()

                    val sheet = RebuildBottomSheet(fragment, type, userRebuildStates)
                        .apply {
                            showProgress()
                            show()
                        }

                    cont.resume(sheet)
                }
            }
    }

    private val unsubscribeFromEject = EjectNotifier.subscribe(this)

    override fun onStop() {
        unsubscribeFromEject()
        super.onStop()
    }

    override fun onEject() = dismiss()

    private class AvatarListView(
        context: Context,
        userRebuildStates: Collection<UserRebuildState>
    ) : RecyclerView(context) {

        private class ListAdapter(
            private val userRebuildStates: Collection<UserRebuildState>
        ) : Adapter<ListAdapter.ViewHolder>() {

            var onItemClickListener: ((userRebuildState: UserRebuildState) -> Unit)? = null

            private class ViewHolder(
                parent: ViewGroup
            ) : RecyclerView.ViewHolder(FrameLayout(parent.context)) {
                companion object {
                    const val AVATAR_SIZE = 48f

                    val GRAYSCALE_COLOR_FILTER = ColorMatrixColorFilter(
                        ColorMatrix().apply { setSaturation(0f) }
                    )
                }

                private val avatarDrawable = AvatarDrawable()

                private val avatarImageView = BackupImageView(itemView.context).apply {
                    setRoundRadius(dp(AVATAR_SIZE / 2))
                }

                init {
                    with(itemView as FrameLayout) {
                        setPadding(dp(8f), 0, dp(8f), 0)

                        addView(
                            avatarImageView,
                            LayoutHelper.createFrame(AVATAR_SIZE.toInt(), AVATAR_SIZE)
                        )
                    }
                }

                fun bind(userRebuildState: UserRebuildState) {
                    val user = userRebuildState.user

                    avatarDrawable.setInfo(UserConfig.selectedAccount, user)
                    avatarImageView.imageReceiver.setForUserOrChat(user, avatarDrawable)

                    when (userRebuildState) {
                        is UserRebuildState.Done<*> if userRebuildState.record == null -> {
                            avatarImageView.setColorFilter(GRAYSCALE_COLOR_FILTER)
                            avatarImageView.alpha = 0.75f
                        }

                        is UserRebuildState.Done<*> -> {
                            avatarImageView.setColorFilter(null)
                            avatarImageView.alpha = 1f
                        }

                        is UserRebuildState.InProcess -> {
                            avatarImageView.setColorFilter(null)
                            avatarImageView.alpha = 0.8f
                        }

                        is UserRebuildState.Pending -> {
                            avatarImageView.setColorFilter(null)
                            avatarImageView.alpha = 0.5f
                        }
                    }
                }
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
                ViewHolder(parent)

            override fun onBindViewHolder(holder: ViewHolder, position: Int) {
                holder.itemView.setOnClickListener {
                    onItemClick(
                        userRebuildStates.elementAt(
                            position
                        )
                    )
                }
                holder.bind(userRebuildStates.elementAt(position))
            }

            override fun getItemCount(): Int =
                userRebuildStates.size

            fun onItemClick(userRebuildState: UserRebuildState) =
                onItemClickListener?.invoke(userRebuildState)
        }

        init {
            adapter = ListAdapter(userRebuildStates)

            layoutManager = LinearLayoutManager(context, HORIZONTAL, false)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        fun setOnItemClickListener(listener: (userRebuildState: UserRebuildState) -> Unit) {
            (adapter as ListAdapter).onItemClickListener = listener
        }
    }

    private class ProgressView(
        context: Context,
        private val type: Int,
        private val userRebuildStates: Collection<UserRebuildState>,
    ) : LinearLayout(context) {
        private val headerCell = object : LinearLayout(context) {
            private val imageView = BackupImageView(context)

            private val titleTextView = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                typeface = Typeface.DEFAULT_BOLD
            }

            private val captionTextView = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3))
            }

            init {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                layoutParams = LayoutParams(
                    MATCH_PARENT,
                    WRAP_CONTENT,
                )

                AnimatedEmojiView.apply(imageView, Emoji.REBUILD_PROGRESS, 64)

                titleTextView.apply {
                    text = when (type) {
                        TYPE_STREAK -> Strings.sheet_rebuild_progress_title_streak(userRebuildStates.size)
                        TYPE_STREAK_PET -> Strings.sheet_rebuild_progress_title_pet(
                            userRebuildStates.size
                        )

                        else -> throw NotImplementedError()
                    }
                }

                addView(
                    imageView,
                    LayoutHelper.createFrame(64, 64f, Gravity.START, 0f, 0f, 12f, 0f)
                )

                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    layoutParams = LayoutParams(
                        MATCH_PARENT,
                        WRAP_CONTENT
                    )

                    addView(titleTextView)
                    addView(captionTextView)
                })
            }

            fun setUser(index: Int, name: String) {
                captionTextView.text = buildString {
                    append(name)

                    if (userRebuildStates.size > 1) {
                        append(" · ")
                        append(
                            Strings.sheet_rebuild_progress_counter(
                                index + 1,
                                userRebuildStates.size
                            )
                        )
                    }
                }
            }
        }

        private val cardCell = object : LinearLayout(context) {
            private val avatarDrawable = AvatarDrawable()

            private val avatarImageView = BackupImageView(context).apply {
                setRoundRadius(dp(24f))
            }

            private val nameTextView = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                typeface = Typeface.DEFAULT_BOLD
            }

            private val captionTextView = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3))
            }

            private val progressLineView = LinearProgressView(context).apply {
                isIndeterminate = true
                indicatorColor = Theme.getColor(Theme.key_dialogButton)
                trackColor = Theme.getColor(Theme.key_dialog_inlineProgressBackground)
                trackThickness = dp(4f)
            }

            init {
                orientation = VERTICAL
                setPadding(
                    dp(12f),
                    dp(12f),
                    dp(12f),
                    dp(12f)
                )

                background = GradientDrawable().apply {
                    setColor(Theme.getColor(Theme.key_dialogBackgroundGray))
                    cornerRadius = dp(12f).toFloat()
                }

                addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(12f))

                    addView(avatarImageView, LayoutHelper.createFrame(48, 48f, 0, 0f, 0f, 8f, 0f))

                    addView(LinearLayout(context).apply {
                        orientation = VERTICAL

                        addView(nameTextView)
                        addView(captionTextView)
                    })
                })

                addView(progressLineView)
            }

            fun setUser(user: TLRPC.User) {
                avatarDrawable.setInfo(UserConfig.selectedAccount, user)
                avatarImageView.imageReceiver.setForUserOrChat(user, avatarDrawable)
                nameTextView.text = user.name
            }

            fun setDaysIndexed(count: Int) {
                captionTextView.text = Strings.sheet_rebuild_card_days_indexed(count)
            }

            fun setThrottling(throttlingClock: Pair<Int, Int>?) {
                if (throttlingClock == null) {
                    progressLineView.isIndeterminate = true
                } else {
                    val (elapsed, total) = throttlingClock

                    progressLineView.max = total
                    progressLineView.setProgressCompat(elapsed, true)

                    captionTextView.text = Strings.sheet_rebuild_card_throttling(total - elapsed)
                }
            }
        }

        private val infoTextView = TextView(context).apply {
            text = Strings.sheet_rebuild_progress_hint()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3))
        }

        init {
            orientation = VERTICAL

            addView(
                headerCell,
                LayoutHelper.createFrame(MATCH_PARENT, WRAP_CONTENT.toFloat(), 0, 0f, 8f, 0f, 8f)
            )

            addView(
                cardCell,
                LayoutHelper.createFrame(MATCH_PARENT, WRAP_CONTENT.toFloat(), 0, 0f, 4f, 0f, 4f)
            )

            addView(
                infoTextView,
                LayoutHelper.createFrame(MATCH_PARENT, WRAP_CONTENT.toFloat(), 0, 0f, 8f, 0f, 0f)
            )
        }

        private var currentUserIndex: Int = -1

        fun setCurrentUser(index: Int) {
            if (this.currentUserIndex == index)
                return

            this.currentUserIndex = index

            val user = userRebuildStates.elementAt(index).user

            headerCell.setUser(index, user.name)
            cardCell.setUser(user)
        }

        fun notifyUserChanged(index: Int) {
            if (this.currentUserIndex != index)
                return

            when (val state = this.userRebuildStates.elementAt(index)) {
                is UserRebuildState.Pending -> {
                    cardCell.setDaysIndexed(0)
                    cardCell.setThrottling(null)
                }

                is UserRebuildState.InProcess -> {
                    cardCell.setDaysIndexed(state.daysIndexed)
                    cardCell.setThrottling(state.throttlingClock)
                }

                is UserRebuildState.Done<*> -> {
                    cardCell.setThrottling(null)
                }
            }
        }
    }

    private data class InfoRow(
        val iconDocumentId: Long,
        val iconBg: Int,
        val iconColor: Int,
        val label: String,
        val value: String,
        val valueColor: Int
    )

    private class InfoCardView(context: Context) : LinearLayout(context) {
        companion object {
            private const val ICON_SIZE = 32
        }

        init {
            orientation = VERTICAL

            background = GradientDrawable().apply {
                setColor(Theme.getColor(Theme.key_dialogBackgroundGray))
                cornerRadius = dp(12f).toFloat()
            }
        }

        private fun buildRow(row: InfoRow): LinearLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(13f), dp(16f), dp(13f))

            addView(LinearLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(row.iconBg)
                    cornerRadius = dp(9f).toFloat()
                }

                addView(BackupImageView(context).apply {
                    AnimatedEmojiView.apply(this, row.iconDocumentId, ICON_SIZE, row.iconColor)
                }, LayoutHelper.createFrame(ICON_SIZE, ICON_SIZE.toFloat(), 0, 4f, 4f, 4f, 4f))
            }, LayoutHelper.createFrame(WRAP_CONTENT, WRAP_CONTENT.toFloat(), 0, 0f, 0f, 12f, 0f))

            addView(TextView(context).apply {
                text = row.label
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }, LayoutHelper.createLinear(0, WRAP_CONTENT, 1f))

            addView(TextView(context).apply {
                text = row.value
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(row.valueColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }, LayoutHelper.createLinear(WRAP_CONTENT, WRAP_CONTENT))
        }

        fun setRows(rows: List<InfoRow>) {
            removeAllViews()

            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    addView(
                        View(context).apply {
                            setBackgroundColor(Theme.getColor(Theme.key_divider))
                        },
                        LayoutHelper.createLinear(MATCH_PARENT, 1, 60f, 0f, 0f, 0f)
                    )
                }

                addView(buildRow(row), LayoutHelper.createLinear(MATCH_PARENT, WRAP_CONTENT))
            }
        }
    }

    private class ResultsView(
        context: Context,
        private val type: Int,
        private val userRebuildStates: Collection<UserRebuildState>
    ) : LinearLayout(context) {
        private val buttonTextView = TextView(context)

        init {
            if (userRebuildStates.isEmpty())
                throw IllegalArgumentException("UserState list can't be empty!")

            val succeededCount =
                userRebuildStates.count { (it as? UserRebuildState.Done<*>)?.record != null }

            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            addView(BackupImageView(context).apply {
                if (userRebuildStates.size == 1) {
                    val user = userRebuildStates.first().user

                    val avatarDrawable = AvatarDrawable()
                        .apply { setInfo(UserConfig.selectedAccount, user) }

                    imageReceiver.setForUserOrChat(user, avatarDrawable)
                } else {
                    AnimatedEmojiView.apply(this, Emoji.REBUILD_RESULTS_GROUP, 64)
                }

                setRoundRadius(dp(32f))
            }, LayoutHelper.createFrame(64, 64f, 0, 0f, 8f, 0f, 8f))

            addView(TextView(context).apply {
                text = if (userRebuildStates.size == 1) {
                    userRebuildStates.elementAt(0).user.name
                } else {
                    when (type) {
                        TYPE_STREAK -> Strings.sheet_rebuild_result_group_streak(succeededCount)
                        TYPE_STREAK_PET -> Strings.sheet_rebuild_result_group_pet(succeededCount)
                        else -> throw NotImplementedError()
                    }
                }

                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                typeface = Typeface.DEFAULT_BOLD
            }, LayoutHelper.createFrame(WRAP_CONTENT, WRAP_CONTENT.toFloat(), 0, 0f, 0f, 0f, 2f))

            addView(TextView(context).apply {
                text = if (userRebuildStates.size == 1) {
                    when (type) {
                        TYPE_STREAK -> Strings.sheet_rebuild_result_single_streak()
                        TYPE_STREAK_PET -> Strings.sheet_rebuild_result_single_pet()
                        else -> throw NotImplementedError()
                    }
                } else {
                    Strings.sheet_rebuild_result_group_subtitle(
                        userRebuildStates.size,
                        succeededCount
                    )
                }

                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3))
                textAlignment = TEXT_ALIGNMENT_CENTER
            }, LayoutHelper.createFrame(WRAP_CONTENT, WRAP_CONTENT.toFloat(), 0, 0f, 2f, 0f, 16f))

            if (userRebuildStates.size == 1) {
                val record = (userRebuildStates.first() as? UserRebuildState.Done<*>)?.record

                val rows = when (record) {
                    is Streak -> listOf(
                        InfoRow(
                            iconDocumentId = record.level.documentId,
                            iconBg = withAlpha(record.level.colorInt, 0x29),
                            iconColor = record.level.colorInt,
                            label = Strings.sheet_rebuild_result_card_days(),
                            value = record.length.toString(),
                            valueColor = record.level.colorInt
                        ),
                        InfoRow(
                            iconDocumentId = Emoji.REBUILD_RESULT_RESTORES,
                            iconBg = withAlpha(Theme.getColor(Theme.key_dialogButton), 0x29),
                            iconColor = Theme.getColor(Theme.key_dialogButton),
                            label = Strings.sheet_rebuild_result_card_restores(),
                            value = record.restoresCount.toString(),
                            valueColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)
                        )
                    )

                    is StreakPet -> {
                        val color = Color.parseColor(record.level.accent)

                        listOf(
                            InfoRow(
                                iconDocumentId = Emoji.REBUILD_RESULT_POINTS,
                                iconBg = withAlpha(color, 0x29),
                                iconColor = color,
                                label = Strings.sheet_rebuild_result_card_points(),
                                value = record.points.toString(),
                                valueColor = color
                            )
                        )
                    }

                    else -> emptyList()
                }

                if (rows.isNotEmpty()) {
                    addView(
                        InfoCardView(context).apply { setRows(rows) },
                        LayoutHelper.createFrame(
                            MATCH_PARENT,
                            WRAP_CONTENT.toFloat(),
                            0,
                            0f,
                            0f,
                            0f,
                            8f
                        )
                    )
                }
            }

            if (userRebuildStates.size > 1) {
                addView(
                    AvatarListView(context, userRebuildStates).apply {
                        setOnItemClickListener {
                            if ((it as? UserRebuildState.Done<*>)?.record == null)
                                return@setOnItemClickListener

                            val fragment = LaunchActivity.getSafeLastFragment()
                                ?: return@setOnItemClickListener

                            RebuildBottomSheet(fragment, type, listOf(it)).apply {
                                showResults()
                                show()
                            }
                        }
                    },
                    LayoutHelper.createFrame(
                        WRAP_CONTENT,
                        WRAP_CONTENT.toFloat(),
                        0,
                        0f,
                        0f,
                        0f,
                        8f
                    )
                )
            }

            addView(buttonTextView.apply {
                text = Strings.sheet_rebuild_result_done()
                gravity = Gravity.CENTER

                setPadding(0, dp(12f), 0, dp(12f))

                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                typeface = Typeface.DEFAULT_BOLD

                background = GradientDrawable().apply {
                    setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueButton))
                    cornerRadius = dp(12f).toFloat()
                }
            }, LayoutHelper.createFrame(MATCH_PARENT, WRAP_CONTENT.toFloat(), 0, 0f, 4f, 0f, 0f))
        }

        fun setOnButtonClickListener(listener: () -> Unit) =
            buttonTextView.setOnClickListener { listener() }
    }

    private val containerLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(16f),
            dp(0f),
            dp(16f),
            dp(16f)
        )
    }

    private var progressView: ProgressView? = null
    private var resultsView: ResultsView? = null

    init {
        setCanDismissWithSwipe(false)
        setCanDismissWithTouchOutside(false)

        setCustomView(containerLayout)
    }

    @UiThread
    fun showProgress() {
        if (progressView != null)
            return

        progressView = ProgressView(context, type, userRebuildStates)

        containerLayout.addView(
            progressView,
            LayoutHelper.createFrame(MATCH_PARENT, WRAP_CONTENT.toFloat())
        )
    }

    @UiThread
    fun showResults() {
        if (progressView != null) {
            containerLayout.removeView(progressView)
            progressView = null
        }

        resultsView = ResultsView(context, type, userRebuildStates).apply {
            setOnButtonClickListener { dismiss() }
        }

        containerLayout.addView(
            resultsView,
            LayoutHelper.createFrame(MATCH_PARENT, WRAP_CONTENT.toFloat())
        )
    }

    @AnyThread
    fun notifyUserStateChanged(index: Int) {
        AndroidUtilities.runOnUIThread {
            val progressView = progressView ?: return@runOnUIThread

            if (userRebuildStates[index] is UserRebuildState.InProcess)
                progressView.setCurrentUser(index)

            progressView.notifyUserChanged(index)
        }
    }
}