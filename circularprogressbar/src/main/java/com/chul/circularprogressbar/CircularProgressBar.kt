package com.chul.circularprogressbar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import androidx.core.util.Pools
import com.chul.circularprogressbar.util.MathUtils.constrain
import kotlinx.coroutines.Runnable

class CircularProgressBar constructor(
    context: Context,
    attrs: AttributeSet? = null
): View(context, attrs) {

    private val boundRect = Rect()
    private val progressRectF = RectF()
    private val percentageTextRect = Rect()

    private var centerX = 0
    private var centerY = 0

    private val progressPaint = Paint()
    private val progressBackgroundPaint = Paint()
    private val percentagePaint = Paint()

    private val progressStrokeWidth: Float
    private val progressColor: Int
    private val progressBackgroundColor: Int
    private val percentageTextSize: Float
    private val percentageTextColor: Int

    private val startDegree = 270f

    private var maxProgress: Int
    private var minProgress: Int = 0
    private var progress: Int
    private val sweepAngle get() = MAX_DEGREE * progress / maxProgress
    private val percentage get() = (progress.toFloat() / maxProgress * 100).toInt()

    private val uiThreadId = Thread.currentThread().id
    private var attached = false
    private var refreshIsPosted = false
    private val refreshData = mutableListOf<RefreshData>()
    private var refreshProgressRunnable: RefreshProgressRunnable? = null

    init {
        context.obtainStyledAttributes(attrs, R.styleable.CircularProgressBar).use { typedArray ->
            maxProgress = typedArray.getInteger(R.styleable.CircularProgressBar_maxProgress, 100)
            progress = typedArray.getInteger(R.styleable.CircularProgressBar_progress, 0)
            progressStrokeWidth = typedArray.getFloat(R.styleable.CircularProgressBar_progressStrokeWidth, 10f)
            progressColor = typedArray.getColor(R.styleable.CircularProgressBar_progressColor, Color.parseColor("#3461FF"))
            progressBackgroundColor = typedArray.getColor(R.styleable.CircularProgressBar_progressBackgroundColor, Color.parseColor("#F6F8FF"))
            percentageTextSize = typedArray.getDimension(R.styleable.CircularProgressBar_percentageTextSize, 11f)
            percentageTextColor = typedArray.getColor(R.styleable.CircularProgressBar_percentageTextColor, Color.parseColor("#3461FF"))
        }
        setupProgressPaint()
        setupProgressBackgroundPaint()
        setupPercentageTextPaint()
        isAttachedToWindow
    }

    private fun setupProgressPaint() {
        progressPaint.style = Paint.Style.STROKE
        progressPaint.strokeWidth = progressStrokeWidth
        progressPaint.color = progressColor
        progressPaint.strokeCap = Paint.Cap.ROUND
    }

    private fun setupProgressBackgroundPaint() {
        progressBackgroundPaint.style = Paint.Style.STROKE
        progressBackgroundPaint.strokeWidth = progressStrokeWidth
        progressBackgroundPaint.color = progressBackgroundColor
    }

    private fun setupPercentageTextPaint() {
        percentagePaint.textSize = percentageTextSize
        percentagePaint.color = percentageTextColor
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        drawProgress(canvas)
        drawProgressText(canvas)
    }

    private fun drawProgress(canvas: Canvas) {
        canvas.drawArc(progressRectF, startDegree, MAX_DEGREE, false, progressBackgroundPaint)
        canvas.drawArc(progressRectF, startDegree, sweepAngle, false, progressPaint)
    }

    private fun drawProgressText(canvas: Canvas) {
        val percentageText = PERCENTAGE_FORMAT.format(percentage)
        percentagePaint.getTextBounds(percentageText, 0, percentageText.length, percentageTextRect)
        canvas.drawText(
            percentageText,
            0,
            percentageText.length,
            centerX.toFloat() - percentageTextRect.width() / 2,
            centerY.toFloat() + percentageTextRect.height() / 2,
            percentagePaint
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        boundRect.left = paddingLeft
        boundRect.top = paddingTop
        boundRect.right = w - paddingRight
        boundRect.bottom = h - paddingBottom

        progressRectF.set(boundRect)
        progressRectF.inset(progressStrokeWidth / 2, progressStrokeWidth / 2)

        centerX = boundRect.centerX()
        centerY = boundRect.centerY()
    }

    @Synchronized
    private fun setProgressInternal(progress: Int) {
        val updateProgress = constrain(progress, minProgress, maxProgress)
        if(updateProgress == this.progress) return
        this.progress = updateProgress
        refreshProgress(progress)

    }

    @Synchronized
    private fun refreshProgress(progress: Int) {
        if(uiThreadId == Thread.currentThread().id) {
            doRefreshProgress(progress)
        } else {
            val runnable = refreshProgressRunnable ?: RefreshProgressRunnable().also { refreshProgressRunnable = it }
            val rd = RefreshData.obtain(progress)
            refreshData.add(rd)
            if(attached && !refreshIsPosted) {
                post(runnable)
                refreshIsPosted = true
            }
        }
    }

    @Synchronized
    private fun doRefreshProgress(progress: Int) {
        invalidate()
    }

    fun setProgress(progress: Int) {
        setProgressInternal(progress)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        synchronized(this) {
            refreshData.forEach { rd ->
                doRefreshProgress(rd.progress)
                rd.recycle()
            }
            refreshData.clear()
        }
        attached = true
    }

    override fun onDetachedFromWindow() {
        if(refreshProgressRunnable != null) {
            removeCallbacks(refreshProgressRunnable)
            refreshIsPosted = false
        }
        super.onDetachedFromWindow()
        attached = false
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        if(superState != null) {
            val ss = SavedState(superState)
            ss.progress = progress
            return ss
        }
        return null
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        val ss = state as? SavedState
        super.onRestoreInstanceState(ss?.superState)
        if(ss != null) {
            setProgress(ss.progress)
        }
    }

    private inner class RefreshProgressRunnable: Runnable {
        override fun run() {
            synchronized(this@CircularProgressBar) {
                refreshData.forEach { rd ->
                    doRefreshProgress(rd.progress)
                    rd.recycle()
                }
                refreshData.clear()
                refreshIsPosted = false
            }
        }

    }

    companion object {
        private const val MAX_DEGREE = 360f
        private const val PERCENTAGE_FORMAT = "%d%%"

        class SavedState: BaseSavedState {
            var progress: Int = 0

            constructor(superState: Parcelable): super(superState)
            private constructor(input: Parcel): super(input) {
                progress = input.readInt()
            }

            override fun writeToParcel(out: Parcel, flags: Int) {
                super.writeToParcel(out, flags)
                out.writeInt(progress)
            }

            companion object {
                @JvmField
                val CREATOR = object : Parcelable.Creator<SavedState> {
                    override fun createFromParcel(source: Parcel): SavedState {
                        return SavedState(source)
                    }

                    override fun newArray(size: Int): Array<SavedState?> {
                        return arrayOfNulls(size)
                    }

                }
            }
        }

        private class RefreshData {

            var progress: Int = 0

            fun recycle() {
                pool.release(this)
            }

            companion object {
                private const val POOL_MAX = 24
                private val pool = Pools.SynchronizedPool<RefreshData>(POOL_MAX)

                fun obtain(progress: Int): RefreshData {
                    val rd = pool.acquire() ?: RefreshData()
                    rd.progress = progress
                    return rd
                }
            }
        }
    }
}