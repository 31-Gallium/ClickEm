package com.clickem.scanner.ui.camera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.clickem.scanner.model.ScannedPage
import java.util.Collections

class ThumbnailQueueAdapter(
    val pages: MutableList<ScannedPage>,
    private val onThumbnailClick: (Int) -> Unit,
    private val onPageDismissed: (Int) -> Unit
) : RecyclerView.Adapter<ThumbnailQueueAdapter.ViewHolder>() {

    var pendingSkeletonCount: Int = 0
        private set

    private val loadingPageIndices = mutableSetOf<Int>()

    class ViewHolder(
        val root: FrameLayout,
        val ivThumb: ImageView,
        val skeletonView: SkeletonShimmerView,
        val tvBadge: TextView
    ) : RecyclerView.ViewHolder(root)

    fun addPendingSkeleton() {
        pendingSkeletonCount++
        notifyItemInserted(pages.size + pendingSkeletonCount - 1)
    }

    fun removePendingSkeleton() {
        if (pendingSkeletonCount > 0) {
            pendingSkeletonCount--
            notifyItemRemoved(pages.size + pendingSkeletonCount)
        }
    }

    fun clearPendingSkeletons() {
        val hadPending = pendingSkeletonCount > 0 || loadingPageIndices.isNotEmpty()
        pendingSkeletonCount = 0
        loadingPageIndices.clear()
        if (hadPending) {
            notifyDataSetChanged()
        }
    }

    fun setPageLoading(index: Int, isLoading: Boolean) {
        if (isLoading) {
            loadingPageIndices.add(index)
        } else {
            loadingPageIndices.remove(index)
        }
        if (index in 0 until itemCount) {
            notifyItemChanged(index)
        }
    }

    fun onPageRemovedAt(removedIndex: Int) {
        if (loadingPageIndices.isEmpty()) return
        val updated = mutableSetOf<Int>()
        for (idx in loadingPageIndices) {
            if (idx < removedIndex) {
                updated.add(idx)
            } else if (idx > removedIndex) {
                updated.add(idx - 1)
            }
        }
        loadingPageIndices.clear()
        loadingPageIndices.addAll(updated)
    }

    fun isSkeletonPosition(position: Int): Boolean {
        return position >= pages.size || position in loadingPageIndices
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        val density = context.resources.displayMetrics.density

        val root = FrameLayout(context).apply {
            val w = (64 * density).toInt()
            val h = (82 * density).toInt()
            val margin = (4 * density).toInt()
            layoutParams = ViewGroup.MarginLayoutParams(w, h).apply {
                setMargins(margin, margin, margin, margin)
            }

            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(Color.parseColor("#12151D"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            clipToOutline = true
        }

        val ivThumb = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(ivThumb)

        val skeletonView = SkeletonShimmerView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        root.addView(skeletonView)

        // Page number badge on bottom-left (e.g. "p.1")
        val tvBadge = TextView(context).apply {
            val bg = GradientDrawable().apply {
                cornerRadius = 6f * density
                setColor(Color.parseColor("#D9090B10"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            background = bg
            val pX = (6 * density).toInt()
            val pY = (2 * density).toInt()
            setPadding(pX, pY, pX, pY)
            textSize = 10f
            setTextColor(Color.parseColor("#FFFFFF"))
            paint.isFakeBoldText = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                val m = (4 * density).toInt()
                setMargins(m, m, m, m)
            }
        }
        root.addView(tvBadge)

        return ViewHolder(root, ivThumb, skeletonView, tvBadge)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val density = holder.root.context.resources.displayMetrics.density
        holder.tvBadge.text = "p.${position + 1}"

        if (isSkeletonPosition(position)) {
            holder.ivThumb.setImageDrawable(null)
            holder.skeletonView.visibility = View.VISIBLE
            holder.skeletonView.startShimmer()
            holder.root.contentDescription = "Loading page ${position + 1}"
            holder.root.background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(Color.parseColor("#161922"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#55FFFFFF"))
            }
            holder.root.setOnClickListener(null)
            return
        }

        holder.skeletonView.stopShimmer()
        holder.skeletonView.visibility = View.GONE
        holder.root.contentDescription = "Scanned page ${position + 1}"
        holder.root.background = GradientDrawable().apply {
            cornerRadius = 12f * density
            setColor(Color.parseColor("#12151D"))
            setStroke((1.5f * density).toInt(), Color.parseColor("#30FFFFFF"))
        }

        val page = pages[position]
        try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 1
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeFile(page.thumbnailPath, opts)
            holder.ivThumb.setImageBitmap(bmp)
            holder.ivThumb.rotation = 0f
        } catch (_: Exception) {
            holder.ivThumb.setImageDrawable(null)
        }

        holder.root.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos in pages.indices && !isSkeletonPosition(pos)) {
                onThumbnailClick(pos)
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.skeletonView.stopShimmer()
    }

    override fun getItemCount(): Int = pages.size + pendingSkeletonCount

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in pages.indices || toPosition !in pages.indices) return
        if (isSkeletonPosition(fromPosition) || isSkeletonPosition(toPosition)) return
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

    fun onItemDismiss(position: Int) {
        if (position in pages.indices && !isSkeletonPosition(position)) {
            onPageDismissed(position)
        }
    }

    /**
     * Animated Monochrome Frost skeleton loader card rendered while a newly captured
     * or retaken page is being processed and saved to disk.
     */
    class SkeletonShimmerView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private var shimmerProgress = 0f
        private var animator: ValueAnimator? = null

        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#161922")
        }

        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#1EFFFFFF")
        }

        private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val barRect = RectF()

        fun startShimmer() {
            if (animator?.isRunning == true) return
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    shimmerProgress = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopShimmer() {
            animator?.cancel()
            animator = null
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (visibility == VISIBLE) {
                startShimmer()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            stopShimmer()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            canvas.drawRect(0f, 0f, w, h, basePaint)

            // Subtle skeleton document lines
            val padH = 10f * density
            val lineH = 5f * density
            val r = 2.5f * density

            barRect.set(padH, 14f * density, w - padH, 14f * density + lineH * 1.4f)
            canvas.drawRoundRect(barRect, r, r, barPaint)

            barRect.set(padH, 27f * density, w - padH * 1.4f, 27f * density + lineH)
            canvas.drawRoundRect(barRect, r, r, barPaint)

            barRect.set(padH, 37f * density, w - padH * 1.1f, 37f * density + lineH)
            canvas.drawRoundRect(barRect, r, r, barPaint)

            barRect.set(padH, 47f * density, w - padH * 1.8f, 47f * density + lineH)
            canvas.drawRoundRect(barRect, r, r, barPaint)

            // Sweeping frost shimmer band
            val bandW = w * 0.9f
            val startX = -bandW + (w + bandW * 2f) * shimmerProgress
            shimmerPaint.shader = LinearGradient(
                startX,
                0f,
                startX + bandW,
                h * 0.4f,
                intArrayOf(
                    Color.parseColor("#00FFFFFF"),
                    Color.parseColor("#2EFFFFFF"),
                    Color.parseColor("#00FFFFFF")
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, shimmerPaint)
        }
    }
}
