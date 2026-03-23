@file:Suppress(
    "MISSING_DEPENDENCY_SUPERCLASS",
    "MISSING_DEPENDENCY_SUPERCLASS_WARNING",
    "PLATFORM_CLASS_MAPPED_TO_KOTLIN",
)

package ru.n08i40k.streaks.override

import android.graphics.Canvas
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.Components.AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable
import org.telegram.ui.Components.AnimatedFloat
import org.telegram.ui.Stars.StarsReactionsSheet
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.registry.SafeParticlesDrawableRegistry
import ru.n08i40k.streaks.util.cloneFields
import ru.n08i40k.streaks.util.getField
import java.lang.ref.WeakReference
import java.lang.reflect.Field

class SafeParticlesDrawable(base: SwapAnimatedEmojiDrawable) : SwapAnimatedEmojiDrawable(null, 0) {
    companion object {
        fun encapsulate(
            drawable: SwapAnimatedEmojiDrawable?,
            targetObject: Any,
            targetField: Field,
            nameTextView: SimpleTextView,
        ): SafeParticlesDrawable? {
            if (drawable == null) return null
            if (drawable is SafeParticlesDrawable) return drawable

            drawable.detach()

            return SafeParticlesDrawable(drawable).also {
                targetField.set(targetObject, it)
                it.attach()

                Plugin.getInstance().safeParticlesDrawableRegistry.add(
                    SafeParticlesDrawableRegistry.EjectData(
                        WeakReference(it),
                        WeakReference(targetObject),
                        targetField,
                        WeakReference(nameTextView)
                    )
                )
            }
        }
    }

    private val particlesField by lazy {
        getField(SwapAnimatedEmojiDrawable::class.java, "particles")
    }

    private val hasParticlesField by lazy {
        getField(SwapAnimatedEmojiDrawable::class.java, "hasParticles")
    }

    private val particlesAlphaField by lazy {
        getField(SwapAnimatedEmojiDrawable::class.java, "particlesAlpha")
    }

    private fun ensureParticlesSafety() {
        val particles = particlesField.get(this) as? StarsReactionsSheet.Particles
        if (particles != null) {
            return
        }

        val hasParticles = hasParticlesField.getBoolean(this)
        val particlesAlpha = particlesAlphaField.get(this) as? AnimatedFloat ?: return

        if (!hasParticles && particlesAlpha.get() <= 0f) {
            return
        }

        particlesField.set(this, StarsReactionsSheet.Particles(1, 8))
    }

    init {
        cloneFields(base as Object, this as Object, SwapAnimatedEmojiDrawable::class.java)
    }

    override fun draw(canvas: Canvas) {
        ensureParticlesSafety()
        super.draw(canvas)
    }

    override fun setParticles(p0: Boolean, p1: Boolean) {
        super.setParticles(p0, if (!p0) true else p1)

        if (p0) {
            ensureParticlesSafety()
        }
    }
}
