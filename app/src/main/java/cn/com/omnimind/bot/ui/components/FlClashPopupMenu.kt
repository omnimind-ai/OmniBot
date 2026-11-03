// Motion adapted from chen08209/FlClash lib/ui/popup.dart and lib/ui/motion.dart
// at 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7. Android Canvas presentation for
// the engine-free HTML preview. See docs/third-party/flclash-NOTICE.md.
// SPDX-License-Identifier: GPL-3.0-only
package cn.com.omnimind.bot.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** A native owner for the same source-control -> menu -> source motion. */
class FlClashPopupMenu(
    private val anchor: View,
    private val dark: Boolean,
    private val items: List<Item>,
) {
    data class Item(val label: String, val action: () -> Unit)

    private val context: Context = anchor.context
    private val density = context.resources.displayMetrics.density
    private val surface = if (dark) Color.rgb(33, 33, 33) else Color.WHITE
    private val textColor = if (dark) Color.rgb(243, 243, 243) else Color.rgb(22, 22, 22)
    private val originalAlpha = anchor.alpha
    private var closing = false
    private var disposed = false
    private var animator: ValueAnimator? = null
    private var selected: (() -> Unit)? = null
    private var sourceImage: Bitmap? = null
    private var menuImage: Bitmap? = null
    private val root = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
        setOnClickListener { dismiss() }
    }
    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private val list = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        addView(rows, ViewGroup.LayoutParams(-1, -2))
        background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(24).toFloat()
        }
        clipToOutline = true
        elevation = dp(8).toFloat()
    }
    private val motion = MotionView(context)
    private val window = object : PopupWindow(root, -1, -1, true) {
        override fun dismiss() = this@FlClashPopupMenu.dismiss()
        fun removeWindow() = super.dismiss()
    }.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
        isClippingEnabled = false
        animationStyle = 0
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        elevation = 0f
    }

    fun show() {
        if (disposed || window.isShowing || !anchor.isAttachedToWindow || anchor.width == 0) return
        sourceImage = Bitmap.createBitmap(anchor.width, anchor.height, Bitmap.Config.ARGB_8888)
            .also { anchor.draw(Canvas(it)) }
        items.forEach { item ->
            rows.addView(
                TextView(context).apply {
                    text = item.label
                    textSize = 16f
                    setTextColor(textColor)
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(48)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    isFocusable = true
                    background = RippleDrawable(
                        android.content.res.ColorStateList.valueOf(
                            if (dark) 0x20FFFFFF else 0x14000000,
                        ),
                        null,
                        GradientDrawable().apply {
                            setColor(Color.WHITE)
                            cornerRadius = dp(18).toFloat()
                        },
                    )
                    setOnClickListener {
                        if (!closing) {
                            selected = item.action
                            dismiss()
                        }
                    }
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
        root.addView(list)
        root.addView(motion, FrameLayout.LayoutParams(-1, -1))
        list.visibility = View.INVISIBLE
        window.showAtLocation(anchor, Gravity.NO_GRAVITY, 0, 0)
        root.post {
            if (disposed || closing || !window.isShowing || !anchor.isAttachedToWindow) {
                dispose()
                return@post
            }
            val rootLocation = IntArray(2).also(root::getLocationOnScreen)
            val location = IntArray(2).also(anchor::getLocationOnScreen)
            val source = RectF(
                (location[0] - rootLocation[0]) / density,
                (location[1] - rootLocation[1]) / density,
                (location[0] - rootLocation[0] + anchor.width) / density,
                (location[1] - rootLocation[1] + anchor.height) / density,
            )
            val bars = ViewCompat.getRootWindowInsets(anchor)?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val left = max(16f, (bars?.left ?: 0) / density + 16f)
            val top = max(16f, (bars?.top ?: 0) / density + 16f - rootLocation[1] / density)
            val right = root.width / density - (bars?.right ?: 0) / density - 16f
            val bottom = root.height / density - (bars?.bottom ?: 0) / density - 16f
            val width = min(260f, right - left).coerceAtLeast(1f)
            list.measure(
                View.MeasureSpec.makeMeasureSpec(
                    (width * density).toInt(),
                    View.MeasureSpec.EXACTLY,
                ),
                View.MeasureSpec.makeMeasureSpec(
                    ((bottom - top) * density).toInt().coerceAtLeast(1),
                    View.MeasureSpec.AT_MOST,
                ),
            )
            val height = list.measuredHeight / density
            val x = (source.right + 8f - width).coerceIn(left, max(left, right - width))
            val y = (source.top - 8f).coerceIn(top, max(top, bottom - height))
            list.layout(0, 0, list.measuredWidth, list.measuredHeight)
            menuImage = Bitmap.createBitmap(
                list.measuredWidth,
                list.measuredHeight,
                Bitmap.Config.ARGB_8888,
            ).also { list.draw(Canvas(it)) }
            list.layoutParams = FrameLayout.LayoutParams(
                list.measuredWidth,
                list.measuredHeight,
            ).apply {
                leftMargin = (x * density).toInt()
                topMargin = (y * density).toInt()
            }
            motion.prepare(source, RectF(x, y, x + width, y + height))
            anchor.alpha = 0f
            if (!ValueAnimator.areAnimatorsEnabled()) {
                motion.visibility = View.GONE
                list.visibility = View.VISIBLE
            } else {
                animate(450L) { value -> motion.open(value) }
            }
        }
    }

    fun dismiss() {
        if (disposed || closing) return
        closing = true
        animator?.cancel()
        if (!window.isShowing || !motion.ready || !ValueAnimator.areAnimatorsEnabled()) {
            finishSelection()
            return
        }
        // Capture a scrolled menu before handing its live rows back to the painter.
        if (list.visibility == View.VISIBLE) {
            menuImage?.let { list.draw(Canvas(it)) }
        }
        list.visibility = View.INVISIBLE
        motion.visibility = View.VISIBLE
        motion.beginClose()
        animate(500L) { value -> motion.close(value) }
    }

    /** Lifecycle cleanup never executes a pending export or other menu action. */
    fun dispose() {
        if (disposed) return
        disposed = true
        selected = null
        animator?.cancel()
        animator = null
        anchor.alpha = originalAlpha
        window.removeWindow()
        motion.releaseImages()
        sourceImage?.recycle()
        menuImage?.recycle()
        sourceImage = null
        menuImage = null
    }

    private fun finishSelection() {
        val action = selected
        val canRun = anchor.isAttachedToWindow
        dispose()
        if (canRun) action?.invoke()
    }

    private fun animate(duration: Long, update: (Float) -> Unit) {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { update(it.animatedValue as Float) }
            addListener(
                object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled || disposed) return
                        if (closing) finishSelection() else {
                            motion.visibility = View.GONE
                            list.visibility = View.VISIBLE
                        }
                    }
                },
            )
            start()
        }
    }

    private fun dp(value: Int) = (value * density + .5f).toInt()

    private data class Frame(
        val platter: RectF,
        val radius: Float,
        val droplet: RectF,
        val reveal: Float,
        val glyph: Float,
        val blur: Float,
        val blend: Float,
    )

    private inner class MotionView(context: Context) : View(context) {
        var ready = false
            private set
        private lateinit var source: RectF
        private lateinit var card: RectF
        private lateinit var frame: Frame
        private lateinit var closeFrom: Frame
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val contentNode = RenderNode("FlClash menu content")
        private val sourceNode = RenderNode("FlClash menu source")

        fun releaseImages() {
            contentNode.discardDisplayList()
            sourceNode.discardDisplayList()
        }

        fun prepare(source: RectF, card: RectF) {
            this.source = source
            this.card = card
            ready = true
            open(0f)
        }

        fun open(value: Float) {
            val t = value * .45f
            val progress = spring(t, 400f, .754f)
            val scale = mix(.41f * min(source.width(), source.height()) / card.width(), 1f, progress)
            val height = mix(card.width(), card.height(), progress)
            val distance = card.centerY() - source.centerY()
            val dip = sign(distance) *
                (min(1f, abs(distance) / 30f) * 23f + .124f * abs(distance)) *
                bump(t, .098f, 3.3f)
            val platter = centered(
                mix(source.centerX(), card.centerX(), progress),
                mix(source.centerY(), card.centerY(), progress) + dip,
                max(0f, card.width() * scale),
                max(0f, height * scale),
            )
            val radius = max(
                0f,
                min(
                    height / 2f,
                    24f + card.width() * .48f * (1f - spring(max(0f, t - .07f), 324f, .75f)),
                ) * scale,
            )
            val inner = RectF(card).apply {
                inset(
                    max(source.width(), source.height()) * .25f / 2f + 36f,
                    max(source.width(), source.height()) * .25f / 2f + 36f,
                )
            }
            val targetX = if (inner.isEmpty) card.centerX()
                else mix(source.centerX(), card.centerX(), .25f).coerceIn(inner.left, inner.right)
            val targetY = if (inner.isEmpty) card.centerY()
                else mix(source.centerY(), card.centerY(), .25f).coerceIn(inner.top, inner.bottom)
            val droplet = centered(
                mix(source.centerX(), targetX, progress),
                mix(source.centerY(), targetY, progress),
                source.width() * mix(1f, .25f, progress),
                source.height() * mix(1f, .25f, progress),
            )
            val settled = progress.coerceIn(0f, 1f)
            frame = Frame(
                platter,
                radius,
                droplet,
                settled,
                1f - min(1f, settled / .25f),
                sqrt((4f * (1f - settled)).pow(2) + (1.9f * bump(t, .069f, 2.1f)).pow(2)),
                28f,
            )
            if (value == 1f) frame = Frame(RectF(card), 24f, droplet, 1f, 0f, 0f, 0f)
            invalidate()
        }

        fun beginClose() {
            closeFrom = frame
        }

        fun close(value: Float) {
            val t = value * .5f
            val from = closeFrom
            val ringing = 1f - EASE_IN.getInterpolation(((t - .4f) / .1f).coerceIn(0f, 1f))
            val progress = 1f + (spring(t, 165f, .8f) - 1f) * ringing
            val distance = from.platter.centerY() - source.centerY()
            val dip = min(.21f * abs(distance), 22f) * bump(t, .072f, 2.4f)
            val lift = min(.25f * abs(distance), 19f) * bump(t, .176f, 4.9f)
            val scale = mix(
                1f,
                .41f * min(source.width(), source.height()) /
                    max(from.platter.width(), from.platter.height()),
                progress,
            )
            val platter = centered(
                mix(from.platter.centerX(), source.centerX(), progress),
                mix(from.platter.centerY(), source.centerY(), progress) + sign(distance) * dip * ringing,
                max(0f, from.platter.width() * scale),
                max(0f, mix(from.platter.height(), from.platter.width(), progress) * scale),
            )
            val droplet = centered(
                mix(from.droplet.centerX(), source.centerX(), progress),
                mix(from.droplet.centerY(), source.centerY(), progress) - sign(distance) * lift * ringing,
                mix(from.droplet.width(), source.width(), progress),
                mix(from.droplet.height(), source.height(), progress),
            )
            val reveal = from.reveal * (1f - EASE_OUT.getInterpolation((t / .1f).coerceIn(0f, 1f)))
            frame = Frame(
                platter,
                max(0f, mix(from.radius, from.platter.width() / 2f, progress) * scale),
                droplet,
                reveal,
                mix(from.glyph, 1f, GLYPH.getInterpolation((t / .47f).coerceIn(0f, 1f))),
                8f * (1f - reveal),
                28f * min(1f, t / .05f) *
                    (1f - EASE_IN_OUT.getInterpolation(((value - .6f) / .3f).coerceIn(0f, 1f))),
            )
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) dismiss()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            if (!ready) return
            val f = frame
            canvas.save()
            canvas.scale(density, density)
            val outline = joinedOutline(f.platter, f.radius, f.droplet, f.blend)
            paint.color = surface
            canvas.drawPath(outline, paint)
            sourceImage?.let { drawImage(canvas, it, f.droplet, f.glyph, 0f, sourceNode) }
            if (f.reveal > 0f) {
                canvas.save()
                if (!closing) canvas.clipPath(
                    Path().apply {
                        addRoundRect(f.platter, f.radius, f.radius, Path.Direction.CW)
                    },
                )
                val scale = if (closing)
                    min(f.platter.width() / card.width(), f.platter.height() / card.height())
                else f.platter.width() / card.width()
                val destination = centered(
                    f.platter.centerX(),
                    f.platter.centerY(),
                    card.width() * scale,
                    card.height() * scale,
                )
                menuImage?.let { drawImage(canvas, it, destination, f.reveal, f.blur, contentNode) }
                canvas.restore()
            }
            canvas.restore()
        }

        private fun drawImage(
            canvas: Canvas,
            bitmap: Bitmap,
            bounds: RectF,
            opacity: Float,
            blur: Float,
            node: RenderNode,
        ) {
            if (opacity <= 0f || bounds.isEmpty) return
            paint.alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
            if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated && blur > .1f) {
                node.setPosition(0, 0, bitmap.width, bitmap.height)
                val recording = node.beginRecording()
                recording.drawBitmap(bitmap, 0f, 0f, null)
                node.endRecording()
                node.setRenderEffect(
                    RenderEffect.createBlurEffect(
                        blur * density,
                        blur * density,
                        Shader.TileMode.CLAMP,
                    ),
                )
                node.alpha = opacity.coerceIn(0f, 1f)
                canvas.save()
                canvas.translate(bounds.left, bounds.top)
                canvas.scale(bounds.width() / bitmap.width, bounds.height() / bitmap.height)
                canvas.drawRenderNode(node)
                canvas.restore()
            } else canvas.drawBitmap(bitmap, null, bounds, paint)
            paint.alpha = 255
        }
    }

    companion object {
        private val EASE_IN = PathInterpolator(.42f, 0f, 1f, 1f)
        private val EASE_OUT = PathInterpolator(0f, 0f, .58f, 1f)
        private val EASE_IN_OUT = PathInterpolator(.42f, 0f, .58f, 1f)
        private val GLYPH = PathInterpolator(.25f, .1f, .25f, 1f)
        private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun centered(x: Float, y: Float, width: Float, height: Float) =
            RectF(x - width / 2, y - height / 2, x + width / 2, y + height / 2)

        private fun bump(time: Float, peak: Float, sharpness: Float): Float =
            if (time <= 0f) 0f else (time / peak).pow(sharpness) * exp(sharpness * (1f - time / peak))

        private fun spring(time: Float, stiffness: Float, ratio: Float): Float {
            val omega = sqrt(stiffness)
            val damped = omega * sqrt(1f - ratio * ratio)
            return 1f - exp(-ratio * omega * time) *
                (cos(damped * time) + ratio * omega / damped * sin(damped * time))
        }

        private fun distance(rect: RectF, radius: Float, x: Float, y: Float): Float {
            val round = min(radius, min(rect.width(), rect.height()) / 2f)
            val dx = abs(x - rect.centerX()) - rect.width() / 2f + round
            val dy = abs(y - rect.centerY()) - rect.height() / 2f + round
            return sqrt(max(dx, 0f).pow(2) + max(dy, 0f).pow(2)) + min(max(dx, dy), 0f) - round
        }

        /** Same smooth minimum field as FlClash's gooPairOutline. */
        private fun joinedOutline(a: RectF, radius: Float, b: RectF, blend: Float): Path {
            val path = Path().apply { addRoundRect(a, radius, radius, Path.Direction.CW) }
            val pill = Path().apply {
                addRoundRect(
                    b,
                    min(b.width(), b.height()) / 2,
                    min(b.width(), b.height()) / 2,
                    Path.Direction.CW,
                )
            }
            path.op(pill, Path.Op.UNION)
            if (blend <= 0f || b.isEmpty) return path
            fun field(x: Float, y: Float): Float {
                val da = distance(a, radius, x, y)
                val db = distance(b, min(b.width(), b.height()) / 2, x, y)
                val h = max(blend - abs(da - db), 0f) / blend
                return min(da, db) - h * h * blend * .25f
            }
            val bridge = Path()
            val limit = max(b.width(), b.height()) / 2 + max(a.width(), a.height()) + blend
            for (i in 0 until 128) {
                val angle = i * Math.PI.toFloat() * 2 / 128
                val dx = cos(angle)
                val dy = sin(angle)
                var inside = 0f
                var reach = 0f
                while (reach < limit) {
                    val d = field(b.centerX() + dx * reach, b.centerY() + dy * reach)
                    if (d >= 0f) {
                        var outside = reach
                        repeat(4) {
                            val middle = (inside + outside) / 2f
                            if (field(b.centerX() + dx * middle, b.centerY() + dy * middle) < 0f)
                                inside = middle
                            else outside = middle
                        }
                        break
                    }
                    inside = reach
                    reach += max(.5f, -d)
                }
                val x = b.centerX() + dx * inside
                val y = b.centerY() + dy * inside
                if (i == 0) bridge.moveTo(x, y) else bridge.lineTo(x, y)
            }
            bridge.close()
            path.op(bridge, Path.Op.UNION)
            return path
        }
    }
}
