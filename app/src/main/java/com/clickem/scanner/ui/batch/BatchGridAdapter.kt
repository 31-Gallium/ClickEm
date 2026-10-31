package com.clickem.scanner.ui.batch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.clickem.scanner.R
import com.clickem.scanner.model.ScannedPage
import java.util.Collections

/**
 * Animated outline progress bar that sweeps around a card's perimeter
 * during a deliberate hold gesture before opening the context menu.
 */
class CardOutlineProgressView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density

    var cornerRadius: Float = 16f * density
        set(value) {
            field = value
            updatePath()
            invalidate()
        }

    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    private val strokeWidthPx = 3f * density

    // Core frost white progress beam
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#FFFFFF")
    }

    // Specular frost outer glow halo
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx + (2.5f * density)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#40FFFFFF")
    }

    // Subtle guide track visible while charging hold
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#20FFFFFF")
    }

    private val fullPath = Path()
    private val segmentPath = Path()
    private val pathMeasure = PathMeasure()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePath()
    }

    private fun updatePath() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val halfStroke = strokeWidthPx / 2f
        fullPath.reset()
        val rect = RectF(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        fullPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        pathMeasure.setPath(fullPath, false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f) return

        val totalLength = pathMeasure.length
        if (totalLength <= 0f) return

        // 1. Draw subtle guide track
        canvas.drawPath(fullPath, trackPaint)

        // 2. Draw animated progress beam along card perimeter
        segmentPath.reset()
        val stop = totalLength * progress
        pathMeasure.getSegment(0f, stop, segmentPath, true)
        canvas.drawPath(segmentPath, glowPaint)
        canvas.drawPath(segmentPath, progressPaint)
    }
}

class BatchGridAdapter(
    val pages: MutableList<ScannedPage>,
    private val onPageClick: (Int) -> Unit,
    private val onDeleteClick: (Int) -> Unit,
    private val onRotateClick: (Int) -> Unit,
    private val onPageLongClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<BatchGridAdapter.ViewHolder>() {

    var onRequestDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    class ViewHolder(
        val root: FrameLayout,
        val ivThumb: ImageView,
        val tvBadge: TextView,
        val btnDelete: ImageView,
        val btnRotate: ImageView,
        val progressOutline: CardOutlineProgressView
    ) : RecyclerView.ViewHolder(root) {
        var holdAnimator: ValueAnimator? = null
        var isHolding = false

        fun cancelHoldProgress() {
            holdAnimator?.cancel()
            holdAnimator = null
            isHolding = false
            progressOutline.progress = 0f
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        val density = context.resources.displayMetrics.density

        val root = FrameLayout(context).apply {
            val h = (210 * density).toInt()
            val m = (8 * density).toInt()
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply {
                setMargins(m, m, m, m)
            }
            background = GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(Color.parseColor("#12151D"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#35FFFFFF"))
            }
            clipToOutline = true
        }

        val ivThumb = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(ivThumb)

        // Top-left Page Badge Tag
        val tvBadge = TextView(context).apply {
            val bg = GradientDrawable().apply {
                cornerRadius = 10f * density
                setColor(Color.parseColor("#E60A0D15"))
                setStroke((1 * density).toInt(), Color.parseColor("#45FFFFFF"))
            }
            background = bg
            val pX = (10 * density).toInt()
            val pY = (4 * density).toInt()
            setPadding(pX, pY, pX, pY)
            textSize = 10.5f
            letterSpacing = 0.05f
            setTextColor(Color.parseColor("#FFFFFF"))
            paint.isFakeBoldText = true
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                val m = (8 * density).toInt()
                setMargins(m, m, m, m)
            }
        }
        root.addView(tvBadge)

        // Top-right Delete Trash Button
        val btnDelete = ImageView(context).apply {
            setImageResource(R.drawable.ic_delete)
            contentDescription = "Delete page"
            val btnSz = (32 * density).toInt()
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#25F43F5E"))
                setStroke((1 * density).toInt(), Color.parseColor("#60F43F5E"))
            }
            background = bg
            val p = (7 * density).toInt()
            setPadding(p, p, p, p)
            setColorFilter(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(btnSz, btnSz).apply {
                gravity = Gravity.TOP or Gravity.END
                val m = (8 * density).toInt()
                setMargins(m, m, m, m)
            }
        }
        root.addView(btnDelete)

        // Bottom-right Rotate Button
        val btnRotate = ImageView(context).apply {
            setImageResource(R.drawable.ic_rotate)
            contentDescription = "Rotate page 90 degrees"
            val btnSz = (32 * density).toInt()
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E60A0D15"))
                setStroke((1 * density).toInt(), Color.parseColor("#35FFFFFF"))
            }
            background = bg
            val p = (7 * density).toInt()
            setPadding(p, p, p, p)
            setColorFilter(Color.parseColor("#FFFFFF"))
            layoutParams = FrameLayout.LayoutParams(btnSz, btnSz).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                val m = (8 * density).toInt()
                setMargins(m, m, m, m)
            }
        }
        root.addView(btnRotate)

        // Perimeter progress outline view for hold feedback
        val progressOutline = CardOutlineProgressView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = false
            isFocusable = false
        }
        root.addView(progressOutline)

        return ViewHolder(root, ivThumb, tvBadge, btnDelete, btnRotate, progressOutline)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val page = pages[position]
        holder.tvBadge.text = "PAGE ${position + 1}"
        holder.cancelHoldProgress()

        try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeFile(page.thumbnailPath, options)
            holder.ivThumb.setImageBitmap(bmp)
            holder.ivThumb.rotation = 0f
        } catch (e: Exception) {
            holder.ivThumb.setImageDrawable(null)
        }

        val touchSlop = ViewConfiguration.get(holder.root.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var holdCompleted = false

        holder.root.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = System.currentTimeMillis()
                    holdCompleted = false
                    holder.isHolding = true

                    holder.holdAnimator?.cancel()
                    val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 750L // Increased hold threshold for context menu
                        interpolator = LinearInterpolator()
                        addUpdateListener { va ->
                            val frac = va.animatedValue as Float
                            holder.progressOutline.progress = frac
                            if (frac >= 1f && !holdCompleted) {
                                holdCompleted = true
                                holder.cancelHoldProgress()
                                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                val pos = holder.bindingAdapterPosition
                                if (pos != RecyclerView.NO_POSITION) {
                                    onPageLongClick?.invoke(pos)
                                }
                            }
                        }
                    }
                    holder.holdAnimator = animator
                    animator.start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(event.x - downX)
                    val dy = Math.abs(event.y - downY)
                    if (dx > touchSlop || dy > touchSlop) {
                        if (!holdCompleted) {
                            val elapsed = System.currentTimeMillis() - downTime
                            holder.cancelHoldProgress()
                            // If user held long enough before dragging (>= 220ms), trigger ItemTouchHelper drag!
                            if (elapsed >= 220 && onRequestDrag != null) {
                                onRequestDrag?.invoke(holder)
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val wasHolding = holder.isHolding
                    holder.cancelHoldProgress()
                    if (!holdCompleted && wasHolding) {
                        val duration = System.currentTimeMillis() - downTime
                        val dx = Math.abs(event.x - downX)
                        val dy = Math.abs(event.y - downY)
                        if (duration < 450 && dx < touchSlop && dy < touchSlop) {
                            val pos = holder.bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION) {
                                onPageClick(pos)
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    holder.cancelHoldProgress()
                    true
                }
                else -> false
            }
        }

        holder.btnDelete.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) onDeleteClick(currentPos)
        }

        holder.btnRotate.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) onRotateClick(currentPos)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelHoldProgress()
    }

    override fun getItemCount(): Int = pages.size

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(pages, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(pages, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        val start = minOf(fromPosition, toPosition)
        val count = kotlin.math.abs(toPosition - fromPosition) + 1
        notifyItemRangeChanged(start, count)
    }
}
