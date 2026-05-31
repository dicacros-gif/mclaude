package com.mclaude.app

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/** 핀치 줌 + 더블탭 확대 + 단일탭(닫기 콜백)을 지원하는 간단한 이미지 뷰 */
class ZoomImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val m = Matrix()
    private val base = Matrix()
    private val minScale = 1f
    private val maxScale = 5f
    private var curScale = 1f
    private val last = PointF()

    /** 확대되지 않은 상태에서 한 번 탭하면 호출 */
    var onSingleTap: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                var factor = d.scaleFactor
                val proposed = curScale * factor
                if (proposed < minScale) factor = minScale / curScale
                if (proposed > maxScale) factor = maxScale / curScale
                m.postScale(factor, factor, d.focusX, d.focusY)
                curScale *= factor
                imageMatrix = m
                return true
            }
        })

    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (curScale > minScale * 1.05f) reset() else onSingleTap?.invoke()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (curScale > minScale * 1.05f) {
                    reset()
                } else {
                    val f = 3f / curScale
                    m.postScale(f, f, e.x, e.y)
                    curScale = 3f
                    imageMatrix = m
                }
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { setupBase() }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        setupBase()
    }

    private fun setupBase() {
        val d = drawable ?: return
        val vw = width.toFloat();  val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat();  val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) return
        val scale = min(vw / dw, vh / dh)
        val dx = (vw - dw * scale) / 2f
        val dy = (vh - dh * scale) / 2f
        base.reset()
        base.postScale(scale, scale)
        base.postTranslate(dx, dy)
        m.set(base)
        curScale = 1f
        imageMatrix = m
    }

    private fun reset() {
        m.set(base)
        curScale = 1f
        imageMatrix = m
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> last.set(event.x, event.y)
            MotionEvent.ACTION_MOVE ->
                if (curScale > minScale * 1.05f && !scaleDetector.isInProgress) {
                    m.postTranslate(event.x - last.x, event.y - last.y)
                    imageMatrix = m
                    last.set(event.x, event.y)
                }
        }
        return true
    }
}
