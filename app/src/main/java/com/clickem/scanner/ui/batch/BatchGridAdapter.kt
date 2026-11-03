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

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return if (position in pages.indices) pages[position].id.hashCode().toLong() else RecyclerView.NO_ID
    }

    var onRequestDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
    val selectedPositions = mutableSetOf<Int>()
    var ghostPositions: Set<Int> = emptySet()
    var bulkDraggedHiddenPositions: Set<Int>
        get() = ghostPositions
        set(value) { ghostPositions = value }
    var onSelectionChanged: ((Set<Int>) -> Unit)? = null

    fun isSelectionMode(): Boolean = selectedPositions.isNotEmpty()

    fun toggleSelection(pos: Int) {
        val wasMode = isSelectionMode()
        if (selectedPositions.contains(pos)) {
            selectedPositions.remove(pos)
        } else {
            selectedPositions.add(pos)
        }
        val isNowMode = isSelectionMode()
        if (wasMode != isNowMode) {
            notifyDataSetChanged()
        } else {
            notifyItemChanged(pos)
        }
        onSelectionChanged?.invoke(selectedPositions.toSet())
    }

    fun clearSelection() {
        selectedPositions.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedPositions.toSet())
    }

    fun selectAll() {
        selectedPositions.clear()
        for (i in pages.indices) {
            selectedPositions.add(i)
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedPositions.toSet())
    }

    class ViewHolder(
        val root: FrameLayout,
        val cascadeLayer4: FrameLayout,
        val cascadeLayer3: FrameLayout,
        val cascadeLayer2: FrameLayout,
        val cascadeLayer1: FrameLayout,
        val mainCard: FrameLayout,
        val ivThumb: ImageView,
        val tvBadge: TextView,
        val btnDelete: ImageView,
        val btnRotate: ImageView,
        val progressOutline: CardOutlineProgressView,
        val ivSelectionCheck: ImageView,
        val tvStackCountBadge: TextView
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
            clipChildren = false
            clipToPadding = false
        }

        // Cascading Stack Layer 4 (backmost card in bulk drag: 5th card in stack)
        val cascadeLayer4 = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(Color.parseColor("#000000"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#26FFFFFF"))
            }
            rotation = 7f
            translationX = 12f * density
            translationY = 12f * density
            visibility = View.GONE
        }
        root.addView(cascadeLayer4)

        // Cascading Stack Layer 3 (4th card in stack)
        val cascadeLayer3 = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(Color.parseColor("#000000"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#36FFFFFF"))
            }
            rotation = -5f
            translationX = 9f * density
            translationY = 9f * density
            visibility = View.GONE
        }
        root.addView(cascadeLayer3)

        // Cascading Stack Layer 2 (3rd card in stack)
        val cascadeLayer2 = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(Color.parseColor("#000000"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#48FFFFFF"))
            }
            rotation = 4f
            translationX = 6f * density
            translationY = 6f * density
            visibility = View.GONE
        }
        root.addView(cascadeLayer2)

        // Cascading Stack Layer 1 (2nd card in stack)
        val cascadeLayer1 = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(Color.parseColor("#000000"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#60FFFFFF"))
            }
            rotation = -2f
            translationX = 3f * density
            translationY = 3f * density
            visibility = View.GONE
        }
        root.addView(cascadeLayer1)

        // Front Main Card Container
        val mainCard = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(Color.parseColor("#000000"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
            }
            clipToOutline = true
        }

        val ivThumb = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        mainCard.addView(ivThumb)

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
        mainCard.addView(tvBadge)

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
        mainCard.addView(btnDelete)

        // Selection Checkmark / Outline Badge
        val ivSelectionCheck = ImageView(context).apply {
            val btnSz = (32 * density).toInt()
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            background = bg
            val p = (6 * density).toInt()
            setPadding(p, p, p, p)
            setImageResource(R.drawable.ic_check)
            setColorFilter(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(btnSz, btnSz).apply {
                gravity = Gravity.TOP or Gravity.END
                val m = (8 * density).toInt()
                setMargins(m, m, m, m)
            }
            visibility = View.GONE
        }
        mainCard.addView(ivSelectionCheck)

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
        mainCard.addView(btnRotate)

        // Perimeter progress outline view for hold feedback
        val progressOutline = CardOutlineProgressView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = false
            isFocusable = false
        }
        mainCard.addView(progressOutline)
        root.addView(mainCard)

        // Cascading Stack Count Pill Badge (shows count of dragged items in bulk drag)
        val tvStackCountBadge = TextView(context).apply {
            textSize = 11.5f
            setTextColor(Color.BLACK)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            val sz = (28 * density).toInt()
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke((1 * density).toInt(), Color.parseColor("#40000000"))
            }
            background = bg
            elevation = 16f * density
            layoutParams = FrameLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.TOP or Gravity.START
                val m = (4 * density).toInt()
                setMargins(m, m, m, m)
            }
            visibility = View.GONE
        }
        root.addView(tvStackCountBadge)

        return ViewHolder(
            root, cascadeLayer4, cascadeLayer3, cascadeLayer2, cascadeLayer1, mainCard,
            ivThumb, tvBadge, btnDelete, btnRotate, progressOutline, ivSelectionCheck, tvStackCountBadge
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val page = pages[position]
        holder.tvBadge.text = "PAGE ${position + 1}"
        holder.cancelHoldProgress()

        // Reset cascading layers
        holder.cascadeLayer1.visibility = View.GONE
        holder.cascadeLayer2.visibility = View.GONE
        holder.cascadeLayer3.visibility = View.GONE
        holder.cascadeLayer4.visibility = View.GONE
        holder.tvStackCountBadge.visibility = View.GONE
        holder.root.elevation = 0f

        if (ghostPositions.contains(position)) {
            holder.root.alpha = 0.35f
            holder.root.scaleX = 0.94f
            holder.root.scaleY = 0.94f
        } else {
            holder.root.alpha = 1f
            holder.root.scaleX = 1f
            holder.root.scaleY = 1f
        }

        val density = holder.root.context.resources.displayMetrics.density
        val isSelected = selectedPositions.contains(position)
        val isMode = isSelectionMode()
        val cardBg = holder.mainCard.background as? GradientDrawable

        if (isSelected) {
            cardBg?.setStroke((2.5f * density).toInt(), Color.parseColor("#FFFFFF"))
            holder.ivSelectionCheck.visibility = View.VISIBLE
            holder.ivSelectionCheck.isClickable = true
            holder.ivSelectionCheck.setImageResource(R.drawable.ic_check)
            holder.ivSelectionCheck.setColorFilter(Color.BLACK)
            (holder.ivSelectionCheck.background as? GradientDrawable)?.apply {
                setColor(Color.WHITE)
                setStroke(0, Color.TRANSPARENT)
            }
            holder.btnDelete.visibility = View.GONE
            holder.btnDelete.isClickable = false
            holder.btnRotate.visibility = View.GONE
            holder.btnRotate.isClickable = false
        } else if (isMode) {
            cardBg?.setStroke((1.5f * density).toInt(), Color.parseColor("#25FFFFFF"))
            holder.ivSelectionCheck.visibility = View.VISIBLE
            holder.ivSelectionCheck.isClickable = true
            holder.ivSelectionCheck.setImageDrawable(null)
            (holder.ivSelectionCheck.background as? GradientDrawable)?.apply {
                setColor(Color.TRANSPARENT)
                setStroke((1.5f * density).toInt(), Color.parseColor("#80FFFFFF"))
            }
            holder.btnDelete.visibility = View.GONE
            holder.btnDelete.isClickable = false
            holder.btnRotate.visibility = View.GONE
            holder.btnRotate.isClickable = false
        } else {
            cardBg?.setStroke((1.5f * density).toInt(), Color.parseColor("#38FFFFFF"))
            holder.ivSelectionCheck.visibility = View.GONE
            holder.ivSelectionCheck.isClickable = false
            holder.btnDelete.visibility = View.VISIBLE
            holder.btnDelete.isClickable = true
            holder.btnRotate.visibility = View.VISIBLE
            holder.btnRotate.isClickable = true
        }

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
        var isDragStarted = false

        holder.root.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = System.currentTimeMillis()
                    holdCompleted = false
                    isDragStarted = false
                    holder.isHolding = true

                    v.parent?.requestDisallowInterceptTouchEvent(true)

                    holder.holdAnimator?.cancel()
                    if (!isSelectionMode()) {
                        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 750L
                            interpolator = LinearInterpolator()
                            addUpdateListener { va ->
                                val frac = va.animatedValue as Float
                                holder.progressOutline.progress = frac
                                if (frac >= 1f && !holdCompleted && !isDragStarted) {
                                    holdCompleted = true
                                    holder.cancelHoldProgress()
                                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    val pos = holder.bindingAdapterPosition
                                    if (pos != RecyclerView.NO_POSITION) {
                                        toggleSelection(pos)
                                    }
                                }
                            }
                        }
                        holder.holdAnimator = animator
                        animator.start()
                    } else {
                        holder.progressOutline.progress = 0f
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragStarted) {
                        return@setOnTouchListener false
                    }
                    val dx = Math.abs(event.x - downX)
                    val dy = Math.abs(event.y - downY)
                    if (dx > touchSlop || dy > touchSlop) {
                        val elapsed = System.currentTimeMillis() - downTime
                        if (isSelectionMode() && isSelected) {
                            // In selection mode on a selected card: always initiate drag immediately!
                            isDragStarted = true
                            holder.cancelHoldProgress()
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            onRequestDrag?.invoke(holder)
                            return@setOnTouchListener false
                        } else if (dy > dx && elapsed < 160L) {
                            // Quick vertical flick without holding: let RecyclerView scroll smoothly
                            holder.cancelHoldProgress()
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                        } else if (elapsed >= 160L || dx > touchSlop * 1.5f) {
                            // Intentional hold or horizontal movement: start drag
                            isDragStarted = true
                            holder.cancelHoldProgress()
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            onRequestDrag?.invoke(holder)
                            return@setOnTouchListener false
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    val wasHolding = holder.isHolding
                    holder.cancelHoldProgress()
                    if (!isDragStarted && !holdCompleted && wasHolding) {
                        val duration = System.currentTimeMillis() - downTime
                        val dx = Math.abs(event.x - downX)
                        val dy = Math.abs(event.y - downY)
                        if (duration < 350 && dx < touchSlop && dy < touchSlop) {
                            val pos = holder.bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION) {
                                if (isSelectionMode()) {
                                    toggleSelection(pos)
                                } else {
                                    onPageClick(pos)
                                }
                            }
                        }
                    }
                    isDragStarted = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    holder.cancelHoldProgress()
                    isDragStarted = false
                    true
                }
                else -> false
            }
        }

        holder.tvBadge.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                holder.cancelHoldProgress()
                toggleSelection(currentPos)
            }
        }

        holder.ivSelectionCheck.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION && isSelectionMode()) {
                toggleSelection(currentPos)
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
        holder.root.animate().cancel()
        holder.root.alpha = 1f
        holder.root.scaleX = 1f
        holder.root.scaleY = 1f
        holder.root.translationX = 0f
        holder.root.translationY = 0f
        holder.root.rotation = 0f
        holder.root.elevation = 0f
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
    }
}
