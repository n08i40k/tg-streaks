@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.override

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.FireworksOverlay
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.Premium.GLIcon.GLIconTextureView
import org.telegram.ui.Components.Premium.PremiumPreviewBottomSheet
import org.telegram.ui.Components.Premium.StarParticlesView
import org.telegram.ui.PremiumPreviewFragment
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.constants.TranslationKey
import ru.n08i40k.streaks.data.StreakViewData
import ru.n08i40k.streaks.util.getField
import ru.n08i40k.streaks.util.getFieldValue
import ru.n08i40k.streaks.util.setFieldValue

class StreakInfoBottomSheet : PremiumPreviewBottomSheet {
    private class StreakFireworksOverlay : FireworksOverlay {
        private val accentColor: Color

        private val _colors: IntArray
        private val _paint: Array<Paint>

        constructor(context: Context, accentColor: Color) : super(context) {
            this.accentColor = accentColor

            val buffer = FloatArray(3)
            Color.colorToHSV(accentColor.toArgb(), buffer)

            val saturation = buffer[1]

            this._colors = IntArray(6)
            this._colors[0] = Color.HSVToColor(buffer.apply { this[1] = saturation * 0.5f })
            this._colors[1] = Color.HSVToColor(buffer.apply { this[1] = saturation * 0.7f })
            this._colors[2] = Color.HSVToColor(buffer.apply { this[1] = saturation * 0.9f })
            this._colors[3] = Color.HSVToColor(buffer.apply { this[1] = saturation * 1.1f })
            this._colors[4] = Color.HSVToColor(buffer.apply { this[1] = saturation * 1.3f })
            this._colors[5] = Color.HSVToColor(buffer.apply { this[1] = saturation * 1.5f })

            this._paint = getFieldValue<Array<Paint>>(FireworksOverlay::class.java, this, "paint")!!
        }

        override fun start(withStars: Boolean) {
            for (i in 0..5) {
                this._paint[i].setColor(this._colors[i])
            }

            super.start(withStars)
        }

        override fun onStop() {
            this._paint[0].setColor(0xff2CBCE8.toInt())
            this._paint[1].setColor(0xff9E04D0.toInt())
            this._paint[2].setColor(0xffFECB02.toInt())
            this._paint[3].setColor(0xffFD2357.toInt())
            this._paint[4].setColor(0xff278CFE.toInt())
            this._paint[5].setColor(0xff59B86C.toInt())

            super.onStop()
        }
    }

    private val streakViewData: StreakViewData

    private fun t(key: String): String = Plugin.getInstance().translator.translate(key)

    @Suppress("SameParameterValue")
    private fun tf(key: String, vararg replacements: Pair<String, Any?>): String {
        var text = t(key)
        replacements.forEach { (name, value) ->
            text = text.replace("{$name}", value?.toString() ?: "")
        }
        return text
    }

    constructor(
        base: PremiumPreviewBottomSheet,
        user: TLRPC.User,
        streakViewData: StreakViewData
    ) : super(
        getFieldValue<BaseFragment>(PremiumPreviewBottomSheet::class.java, base, "fragment")!!,
        base.currentAccount,
        user,
        base.resourcesProvider
    ) {
        this.statusStickerSet = base.statusStickerSet
        this.overrideTitleIcon = base.overrideTitleIcon
        this.isEmojiStatus = base.isEmojiStatus

        this.streakViewData = streakViewData

        this.premiumFeatures.clear()

        this.premiumFeatures.add(
            PremiumPreviewFragment.PremiumFeatureData(
                0,
                R.drawable.msg_filled_data_messages,
                t(TranslationKey.DEX_SHEET_FEATURE_HOW_TITLE),
                t(TranslationKey.DEX_SHEET_FEATURE_HOW_SUBTITLE)
            )
        )

        this.premiumFeatures.add(
            PremiumPreviewFragment.PremiumFeatureData(
                0,
                R.drawable.msg_filled_unlockedrecord,
                t(TranslationKey.DEX_SHEET_FEATURE_LEVELS_TITLE),
                t(TranslationKey.DEX_SHEET_FEATURE_LEVELS_SUBTITLE)
            )
        )

        this.premiumFeatures.add(
            PremiumPreviewFragment.PremiumFeatureData(
                0,
                R.drawable.msg_reactions_filled,
                t(TranslationKey.DEX_SHEET_FEATURE_KEEP_TITLE),
                t(TranslationKey.DEX_SHEET_FEATURE_KEEP_SUBTITLE)
            )
        )

        this.premiumFeatures.add(
            PremiumPreviewFragment.PremiumFeatureData(
                0,
                R.drawable.msg_filled_datausage,
                t(TranslationKey.DEX_SHEET_FEATURE_INCORRECT_TITLE),
                t(TranslationKey.DEX_SHEET_FEATURE_INCORRECT_SUBTITLE)
            )
        )

        super.rowCount = 0
        super.updateRows()

        if (streakViewData.isJubilee) {
            super.setAnimateConfetti(true)

            val fireworksOverlay = StreakFireworksOverlay(context, streakViewData.accentColor)

            container.addView(
                fireworksOverlay,
                LayoutHelper.createFrame(-1, -1f)
            )

            setFieldValue(
                PremiumPreviewBottomSheet::class.java,
                this,
                "fireworksOverlay",
                fireworksOverlay
            )
        }
    }

    override fun onContainerDraw(canvas: Canvas?) {
        super.onContainerDraw(canvas)

        val starParticlesView = getFieldValue<StarParticlesView>(
            PremiumPreviewBottomSheet::class.java,
            this,
            "starParticlesView"
        )
        starParticlesView?.drawable?.paint?.setColor(this.streakViewData.accentColor.toArgb())
        starParticlesView?.drawable?.init()
    }

    override fun afterCellCreated(viewType: Int, view: View) {
        if (this.titleView == null || this.subtitleView == null)
            return

        this.titleView[1].text =
            AndroidUtilities.replaceTags(
                tf(
                    TranslationKey.DEX_SHEET_TITLE,
                    "name" to user.first_name,
                    "days" to streakViewData.length
                )
            )
        this.subtitleView.text =
            AndroidUtilities.replaceTags(t(TranslationKey.DEX_SHEET_SUBTITLE))

        if (viewType != 0)
            return

        val parentView = view as? FrameLayout ?: return

        val starParticlesView = object : StarParticlesView(context) {
            override fun configure() {
                super.drawable = object : Drawable(super.drawable.count) {
                    override fun getPathColor(i: Int): Int {
                        return this@StreakInfoBottomSheet.streakViewData.accentColor.toArgb()
                    }
                }

                super.configure()
                super.drawable.useGradient = false
                super.drawable.useBlur = false
                super.drawable.forceMaxAlpha = true
                super.drawable.checkBounds = true
                super.drawable.init()
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                super.drawable.rect2.set(
                    0f,
                    0f,
                    this.measuredWidth.toFloat(),
                    this.measuredHeight.toFloat() - AndroidUtilities.dp(52f)
                )
            }
        }

        getField(PremiumPreviewBottomSheet::class.java, "starParticlesView").let { field ->
            val old = field.get(this)!! as StarParticlesView

            parentView.removeView(old)
            parentView.addView(starParticlesView, 0)

            getFieldValue<GLIconTextureView>(
                PremiumPreviewBottomSheet::class.java,
                this,
                "iconTextureView"
            )?.setStarParticlesView(starParticlesView)

            field.set(this, starParticlesView)
        }

        super.afterCellCreated(viewType, view)
    }

    // idk what is this
    @Suppress("EmptyMethod", "unused")
    override fun setLastVisible(p0: Boolean) {
    }
}
