package art.ahmads.eyescroll

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class EyeGazeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val colorSclera   = Color.parseColor("#2A2A27")
    private val colorIrisIdle = Color.parseColor("#6E6E68")
    private val colorIrisWink = Color.parseColor("#C4A97A")
    private val colorIrisFlash = Color.parseColor("#F0EFE8")
    private val colorProgress = Color.parseColor("#C4A97A")
    private val colorNoFace   = Color.parseColor("#1A1A17")

    private val scleraPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorSclera }
    private val irisPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorIrisIdle }
    private val pupilPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0E0E0D") }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorProgress
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val scleraRect = RectF()
    private val arcRect    = RectF()

    private var displayIrisX = 0f
    private var irisXAnimator: ValueAnimator? = null

    var faceDetected = false
        private set

    fun updateState(state: GazeState) {
        faceDetected = state.faceDetected

        if (!state.faceDetected) {
            animateIrisTo(0f)
            irisPaint.color = colorIrisIdle
            invalidate()
            return
        }

        val targetX = when {
            state.isMovingRight -> 0.6f
            state.isMovingLeft  -> -0.6f
            else                -> 0f
        }
        animateIrisTo(targetX)
        irisPaint.color = if (state.isMovingRight || state.isMovingLeft) colorIrisWink else colorIrisIdle
        invalidate()
    }

    fun flashDoubleTap() {
        irisPaint.color = colorIrisFlash
        invalidate()
        postDelayed({
            irisPaint.color = colorIrisIdle
            invalidate()
        }, 400)
    }

    private fun animateIrisTo(target: Float) {
        irisXAnimator?.cancel()
        irisXAnimator = ValueAnimator.ofFloat(displayIrisX, target).apply {
            duration = 120
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayIrisX = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val rx = w * 0.42f
        val ry = h * 0.28f
        scleraRect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        val arcPad = w * 0.06f
        arcRect.set(arcPad, arcPad, w - arcPad, h - arcPad)
        progressPaint.strokeWidth = w * 0.045f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val rx = scleraRect.width() / 2f
        val ry = scleraRect.height() / 2f
        val irisR = ry * 0.58f
        val pupilR = irisR * 0.42f

        scleraPaint.color = if (faceDetected) colorSclera else colorNoFace
        canvas.drawOval(scleraRect, scleraPaint)

        if (faceDetected) {
            val irisX = cx + displayIrisX * rx * 0.55f
            canvas.drawCircle(irisX, cy, irisR, irisPaint)
            canvas.drawCircle(irisX, cy, pupilR, pupilPaint)
        }
    }
}
