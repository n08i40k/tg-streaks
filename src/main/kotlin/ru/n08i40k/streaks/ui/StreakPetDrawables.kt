package ru.n08i40k.streaks.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader.TileMode
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.TextView
import ru.n08i40k.streaks.util.dp
import kotlin.math.cos
import kotlin.math.sin

internal class PetProgressView(context: Context) : TextView(context) {
    var fraction = 0f
    var accent = Color.CYAN
    var track = Color.LTGRAY
    var outline = Color.WHITE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val stripe = Path()

    override fun onDraw(canvas: Canvas) {
        val inset = dp(1).toFloat()
        rect.set(inset, inset, width - inset, height - inset)

        val radius = rect.height() / 2
        paint.style = Paint.Style.FILL
        paint.color = track
        canvas.drawRoundRect(rect, radius, radius, paint)

        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clip)
        canvas.clipRect(0f, 0f, width * fraction.coerceIn(0f, 1f), height.toFloat())

        paint.color = accent
        canvas.drawRect(rect, paint)

        paint.color = Color.argb(80, 255, 255, 255)
        val step = dp(14).toFloat()
        var x = -height.toFloat()

        while (x < width + height) {
            stripe.reset()
            stripe.moveTo(x, height.toFloat())
            stripe.lineTo(x + step / 2, height.toFloat())
            stripe.lineTo(x + step / 2 + height / 2, 0f)
            stripe.lineTo(x + height / 2, 0f)
            stripe.close()
            canvas.drawPath(stripe, paint)
            x += step
        }

        canvas.restore()

        paint.color = outline
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = inset * 2
        canvas.drawRoundRect(rect, radius, radius, paint)

        paint.style = Paint.Style.FILL
        super.onDraw(canvas)
    }
}

@SuppressLint("ViewConstructor")
internal class PetTaskCheckView(context: Context, private val completed: Boolean) : View(context) {
    var accent = Color.CYAN
    var muted = Color.GRAY
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * .46f
        val color = if (completed) accent else muted

        path.reset()
        for (i in 0 until 24) {
            val angle = i * Math.PI / 12 - Math.PI / 2
            val r = if (i % 2 == 0) radius else radius * .8f
            val x = cx + cos(angle).toFloat() * r
            val y = cy + sin(angle).toFloat() * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        paint.color = (color and 0x00ffffff) or (0x28 shl 24)
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)

        path.reset()
        path.moveTo(cx - radius * .3f, cy)
        path.lineTo(cx - radius * .05f, cy + radius * .25f)
        path.lineTo(cx + radius * .32f, cy - radius * .28f)

        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2).toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, paint)
    }
}

internal class PetBackgroundDrawable(
    private val top: Int,
    private val middle: Int,
    private val bottom: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            bounds.height() * .88f,
            intArrayOf(top, middle, bottom),
            floatArrayOf(0f, .4f, 1f),
            TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
