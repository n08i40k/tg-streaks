package ru.n08i40k.streaks.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.exteragram.messenger.plugins.PluginsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.constants.Emoji
import ru.n08i40k.streaks.constants.TranslationKey
import ru.n08i40k.streaks.constants.TrustedSources
import ru.n08i40k.streaks.extension.RequestOutcome
import ru.n08i40k.streaks.extension.format
import ru.n08i40k.streaks.extension.sendRequestBlocking
import ru.n08i40k.streaks.util.AnimatedEmojiView
import ru.n08i40k.streaks.util.MessageSender
import ru.n08i40k.streaks.util.Translator
import ru.n08i40k.streaks.util.getClientName
import ru.n08i40k.streaks.util.getClientVersionFull
import ru.n08i40k.streaks.util.getClientVersionName

class CrashBottomSheet(
    context: Context,
    focusable: Boolean,
    p2: Boolean,
    resourcesProvider: Theme.ResourcesProvider?,
    private val message: String,
    private val exception: Throwable,
) : BottomSheet(context, focusable, p2, resourcesProvider) {

    companion object {
        fun show(message: String, exception: Throwable) {
            AndroidUtilities.runOnUIThread {
                val fragment = LaunchActivity.getSafeLastFragment() ?: return@runOnUIThread

                CrashBottomSheet(
                    fragment.context,
                    focusable = true,
                    p2 = true,
                    resourcesProvider = null,
                    message = message,
                    exception = exception,
                ).show()
            }
        }

        @Suppress("UNCHECKED_CAST")
        val plugins: Collection<com.exteragram.messenger.plugins.Plugin>
            get() {
                val klass = PluginsController::class.java

                val controller = klass
                    .getDeclaredMethod("getInstance")
                    .invoke(null) as PluginsController

                val getPlugins = klass
                    .declaredMethods
                    .find { it.name == "getPlugins" }

                val map = getPlugins?.invoke(controller)
                    ?: klass.getField("plugins").get(controller)

                return map.javaClass.getDeclaredMethod("values")
                    .invoke(map) as Collection<com.exteragram.messenger.plugins.Plugin>
            }
    }

    class PluginsListAdapter(
        private val dataSet: Collection<com.exteragram.messenger.plugins.Plugin>,
        private val resourcesProvider: Theme.ResourcesProvider?
    ) : RecyclerView.Adapter<PluginsListAdapter.ViewHolder>() {
        class ViewHolder(
            parent: ViewGroup,
            resourcesProvider: Theme.ResourcesProvider?
        ) :
            RecyclerView.ViewHolder(
                LinearLayout(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            ) {
            private val layout = itemView as LinearLayout

            val nameView = TextView(itemView.context).apply {
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            }

            val authorView = TextView(itemView.context).apply {
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            val idVersionView = TextView(itemView.context).apply {
                setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
            }

            init {
                layout.addView(LinearLayout(layout.context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    addView(nameView)
                    addView(authorView)
                })

                layout.addView(idVersionView)
            }

            @SuppressLint("SetTextI18n")
            fun bind(plugin: com.exteragram.messenger.plugins.Plugin) {
                nameView.text = plugin.getName()
                authorView.text = " by ${plugin.getAuthor()}"
                idVersionView.text = "${plugin.getId()} | ${plugin.getVersion()}"
            }
        }

        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(viewGroup, resourcesProvider)

        override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
            viewHolder.bind(dataSet.elementAt(position))
        }

        override fun getItemCount(): Int = dataSet.size
    }

    private val textColor = Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider)
    private val secondaryColor = Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider)
    private val errorColor = Theme.getColor(Theme.key_text_RedBold, resourcesProvider)
    private val cardColor = Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider)
    private val buttonColor = Theme.getColor(Theme.key_dialogButton, resourcesProvider)
    private val dividerColor = Theme.getColor(Theme.key_dialogGrayLine, resourcesProvider)

    init {
        setCanDismissWithTouchOutside(false)
        setCanDismissWithSwipe(false)
        setCustomView(buildContentView())
    }

    private fun dp(value: Float) = AndroidUtilities.dp(value)

    private fun buttonBackground() = RippleDrawable(
        ColorStateList.valueOf(
            Color.argb(
                31,
                Color.red(buttonColor),
                Color.green(buttonColor),
                Color.blue(buttonColor)
            )
        ),
        null,
        GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat()
            setColor(Color.WHITE)
        },
    )

    private fun cardBackground() = GradientDrawable().apply {
        cornerRadius = dp(8f).toFloat()
        setColor(cardColor)
    }

    private fun LinearLayout.addDivider() {
        addView(LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                MATCH_PARENT,
                dp(1f),
            ).also {
                it.topMargin = dp(8f)
                it.bottomMargin = dp(8f)
            }
            background = ColorDrawable(dividerColor)
        })
    }

    private fun LinearLayout.addCard(name: String, value: String) {
        addView(LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            background = cardBackground()

            addView(TextView(context).apply {
                setPadding(0, 0, 0, dp(4f))
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(secondaryColor)
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = true
            })

            addView(TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(textColor)
            })
        })
    }

    private fun LinearLayout.addButton(text: String, color: Int, onClick: () -> Unit) {
        addView(TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                WRAP_CONTENT,
                WRAP_CONTENT,
            )
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = buttonBackground()
            setOnClickListener { onClick() }
        })
    }

    private fun buildContentView(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(16f))

            addView(LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    MATCH_PARENT,
                    WRAP_CONTENT,
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(
                    AnimatedEmojiView.create(
                        context,
                        Emoji.CRASH_BOTTOM_SHEET,
                        resourcesProvider = resourcesProvider
                    ),
                    LinearLayout.LayoutParams(dp(48f), dp(48f)).also { it.marginEnd = dp(12f) },
                )

                addView(TextView(context).apply {
                    text = Translator.translate(TranslationKey.Sheet.Crash.TITLE)
                        .let {
                            GradientSpan.fromString(
                                it,
                                intArrayOf(
                                    0xFF50A6FD.toInt(),
                                    0xFFFD32D1.toInt(),
                                    0xFFFC8030.toInt()
                                )
                            )
                        }
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(textColor)
                    typeface = Typeface.DEFAULT_BOLD
                })
            })

            addDivider()

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8f), 0, dp(8f))

                addView(TextView(context).apply {
                    setPadding(0, 0, 0, dp(8f))
                    text = Translator.translate(TranslationKey.Sheet.Crash.PARAGRAPH_REASON)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(secondaryColor)
                    typeface = Typeface.DEFAULT_BOLD
                    isAllCaps = true
                })

                addView(TextView(context).apply {
                    text = message
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(errorColor)
                    typeface = Typeface.DEFAULT_BOLD
                })
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8f), 0, dp(8f))

                addView(TextView(context).apply {
                    setPadding(0, 0, 0, dp(8f))
                    text = Translator.translate(TranslationKey.Sheet.Crash.PARAGRAPH_STACK_TRACE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(secondaryColor)
                    typeface = Typeface.DEFAULT_BOLD
                    isAllCaps = true
                })

                addView(HorizontalScrollView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        MATCH_PARENT,
                        WRAP_CONTENT,
                    )
                    background = cardBackground()

                    addView(TextView(context).apply {
                        setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                        text = exception.format().split("\n").take(10).joinToString("\n")
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        setTextColor(secondaryColor)
                        typeface = Typeface.MONOSPACE
                        gravity = Gravity.START
                        setHorizontallyScrolling(true)
                    })
                })
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8f), 0, dp(16f))

                addView(TextView(context).apply {
                    setPadding(0, 0, 0, dp(8f))
                    text = Translator.translate(TranslationKey.Sheet.Crash.PARAGRAPH_PLUGINS)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(secondaryColor)
                    typeface = Typeface.DEFAULT_BOLD
                    isAllCaps = true
                })

                addView(object : RecyclerView(context) {
                    private val maxH = dp(128f)

                    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                        super.onMeasure(
                            widthSpec,
                            MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                        )
                    }
                }.apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = PluginsListAdapter(
                        plugins.filter { it.isEnabled() },
                        resourcesProvider
                    )
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    background = cardBackground()
                    setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                })
            })

            addView(LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    MATCH_PARENT,
                    WRAP_CONTENT,
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addCard(
                    Translator.translate(
                        TranslationKey.Sheet.Crash.INFO_CLIENT_VERSION,
                        mapOf("client_name" to getClientName())
                    ),
                    getClientVersionFull()
                )

                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(12f), MATCH_PARENT)
                })

                addCard(
                    Translator.translate(TranslationKey.Sheet.Crash.INFO_PLUGIN_VERSION),
                    Plugin.getVersion() ?: "unknown"
                )
            })

            addDivider()

            addView(LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    MATCH_PARENT,
                    WRAP_CONTENT,
                ).also { it.topMargin = dp(4f) }
                orientation = LinearLayout.HORIZONTAL

                addButton(
                    Translator.translate(TranslationKey.Sheet.Crash.BUTTON_DISMISS),
                    secondaryColor
                ) { dismiss() }

                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                })

                addButton(Translator.translate(TranslationKey.Sheet.Crash.BUTTON_COPY), textColor) {
                    AndroidUtilities.addToClipboard(buildReportText())
                }

                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(12f), MATCH_PARENT)
                })

                addButton(
                    Translator.translate(TranslationKey.Sheet.Crash.BUTTON_SEND),
                    buttonColor
                ) {
                    sendCrashReport()
                }
            })
        }
    }

    private fun sendCrashReport() {
        val connectionsManager = ConnectionsManager.getInstance(UserConfig.selectedAccount)

        CoroutineScope(Dispatchers.IO).launch {
            val req = TLRPC.TL_contacts_resolveUsername().apply {
                username = TrustedSources.CHAT.tag
            }

            val resolvedPeer = when (val res = connectionsManager.sendRequestBlocking(req)) {
                is RequestOutcome.Success -> try {
                    res.cast<TLRPC.TL_contacts_resolvedPeer>()
                } catch (_: ClassCastException) {
                    return@launch
                }

                else -> return@launch
            }

            val chat = resolvedPeer.chats.firstOrNull() ?: return@launch

            MessageSender.send(UserConfig.selectedAccount, -chat.id, buildReportText())

            AndroidUtilities.runOnUIThread {
                LaunchActivity.getSafeLastFragment()?.presentFragment(ChatActivity.of(-chat.id))
                dismiss()
            }
        }
    }

    private fun buildReportText(): String {
        val header = """
            #crash_report

            Reason: `$message`

            ${getClientName()} version: `${getClientVersionName()}`
            Plugin version: `${Plugin.getVersion() ?: "unknown"}`
            Plugin build date: `${Plugin.getBuildDate()}`
        """.trimIndent()

        val pluginsList = plugins
            .filter { it.isEnabled() }
            .map { "— ${it.getName()} by ${it.getAuthor()} (${it.getId()} | ${it.getVersion()})" }

        val plugins =
            "Plugins:\n```\n${pluginsList.joinToString("\n")}\n```"

        val trace = "```\n${exception.format()}\n```"

        return "$header\n\n${plugins}\n\nStack-trace:\n$trace"
    }
}
