package com.clickem.scanner.ui.loadout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.clickem.scanner.R
import com.clickem.scanner.model.DocumentProject
import com.clickem.scanner.model.ScannedPage
import com.clickem.scanner.ui.components.FrostCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LoadoutAdapter(
    val projects: MutableList<DocumentProject>,
    private val onProjectClick: (DocumentProject) -> Unit,
    private val onPageThumbnailClick: (DocumentProject, Int) -> Unit,
    private val onPageThumbnailLongClick: (DocumentProject, ScannedPage, Int) -> Unit,
    private val onShareClick: (DocumentProject) -> Unit,
    private val onDeleteClick: (DocumentProject, Int) -> Unit,
    private val onEditProjectClick: (DocumentProject) -> Unit
) : RecyclerView.Adapter<LoadoutAdapter.ViewHolder>() {

    class ViewHolder(
        val card: FrostCardView,
        val tvTitle: TextView,
        val tvDateTime: TextView,
        val tvCategory: TextView,
        val tvPageBadge: TextView,
        val thumbsContainer: LinearLayout,
        val btnEdit: ImageView,
        val btnShare: ImageView,
        val btnDelete: ImageView
    ) : RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        val density = context.resources.displayMetrics.density

        val card = FrostCardView(context).apply {
            val mH = (12 * density).toInt()
            val mV = (8 * density).toInt()
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(mH, mV, mH, mV)
            }
            val p = (14 * density).toInt()
            setPadding(p, p, p, p)
        }

        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Top Meta Row: Category Chip, Date/Time, Edit, Share & Delete
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvCategory = TextView(context).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#CBD5E1"))
            paint.isFakeBoldText = true
            val bg = GradientDrawable().apply {
                cornerRadius = 6f * density
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            background = bg
            val pX = (8 * density).toInt()
            val pY = (3 * density).toInt()
            setPadding(pX, pY, pX, pY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (8 * density).toInt()
            }
        }
        topRow.addView(tvCategory)

        val tvDateTime = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#8C96A5"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(tvDateTime)

        val btnEdit = ImageView(context).apply {
            setImageResource(R.drawable.ic_edit)
            contentDescription = "Edit project details"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (2 * density).toInt()
            }
            setColorFilter(Color.parseColor("#CBD5E1"))
        }
        topRow.addView(btnEdit)

        val btnShare = ImageView(context).apply {
            setImageResource(R.drawable.ic_share)
            contentDescription = "Share project"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginEnd = (2 * density).toInt()
            }
            setColorFilter(Color.parseColor("#FFFFFF"))
        }
        topRow.addView(btnShare)

        val btnDelete = ImageView(context).apply {
            setImageResource(R.drawable.ic_delete)
            contentDescription = "Delete project"
            val sz = (48 * density).toInt()
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setColorFilter(Color.parseColor("#F43F5E"))
        }
        topRow.addView(btnDelete)

        cardLayout.addView(topRow)

        // 2. Title & Page Count Badge Row
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (10 * density).toInt())
        }

        val tvTitle = TextView(context).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#f8fafc"))
            paint.isFakeBoldText = true
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(tvTitle)

        val tvPageBadge = TextView(context).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#F8FAFC"))
            paint.isFakeBoldText = true
            val bg = GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(Color.parseColor("#15FFFFFF"))
                setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
            }
            background = bg
            val pX = (8 * density).toInt()
            val pY = (2 * density).toInt()
            setPadding(pX, pY, pX, pY)
        }
        titleRow.addView(tvPageBadge)

        cardLayout.addView(titleRow)

        // 3. Multi-Thumbnail Strip (Requirement 6: at least 4 or 5 images visible)
        val thumbsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (74 * density).toInt()
            )
        }
        cardLayout.addView(thumbsContainer)

        card.addView(cardLayout)
        return ViewHolder(card, tvTitle, tvDateTime, tvCategory, tvPageBadge, thumbsContainer, btnEdit, btnShare, btnDelete)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val proj = projects[position]
        val context = holder.card.context
        val density = context.resources.displayMetrics.density

        holder.tvTitle.text = proj.title
        val dateFmt = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
        holder.tvDateTime.text = dateFmt.format(Date(proj.createdAt))
        holder.tvCategory.text = proj.category.uppercase(Locale.getDefault())
        holder.tvPageBadge.text = "${proj.pages.size} ${if (proj.pages.size == 1) "PAGE" else "PAGES"}"

        // Populate up to 5 multi-thumbnail preview cards
        holder.thumbsContainer.removeAllViews()
        val maxPreviews = 5
        val previewCount = minOf(proj.pages.size, maxPreviews)
        val overflowCount = proj.pages.size - maxPreviews

        val thumbWidth = (54 * density).toInt()
        val thumbHeight = (72 * density).toInt()

        for (i in 0 until previewCount) {
            val page = proj.pages[i]
            val isLastSlot = (i == maxPreviews - 1) && overflowCount > 0

            val frame = FrameLayout(context).apply {
                contentDescription = "Page ${i + 1} of ${proj.pages.size}, long press for page options"
                layoutParams = LinearLayout.LayoutParams(thumbWidth, thumbHeight).apply {
                    marginEnd = (8 * density).toInt()
                }
                background = GradientDrawable().apply {
                    cornerRadius = 8f * density
                    setColor(Color.parseColor("#090B10"))
                    setStroke((1 * density).toInt(), Color.parseColor("#30FFFFFF"))
                }
                clipToOutline = true
            }

            val iv = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }

            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 2
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bmp = BitmapFactory.decodeFile(page.thumbnailPath, opts)
                iv.setImageBitmap(bmp)
                iv.rotation = 0f
            } catch (_: Exception) {
                iv.setImageDrawable(null)
            }
            frame.addView(iv)

            if (isLastSlot) {
                // Dim overlay with "+N" badge
                val overlay = TextView(context).apply {
                    text = "+$overflowCount"
                    textSize = 14f
                    setTextColor(Color.parseColor("#FFFFFF"))
                    paint.isFakeBoldText = true
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#CC090B10"))
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                frame.addView(overlay)
            }

            val pageIdx = i
            frame.setOnClickListener {
                onPageThumbnailClick(proj, pageIdx)
            }
            frame.setOnLongClickListener {
                onPageThumbnailLongClick(proj, page, pageIdx)
                true
            }

            holder.thumbsContainer.addView(frame)
        }

        // Card Clicks
        holder.card.setOnClickListener {
            onProjectClick(proj)
        }

        holder.card.setOnLongClickListener {
            onEditProjectClick(proj)
            true
        }

        holder.btnEdit.setOnClickListener {
            onEditProjectClick(proj)
        }

        holder.btnShare.setOnClickListener {
            onShareClick(proj)
        }

        holder.btnDelete.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                onDeleteClick(proj, currentPos)
            }
        }
    }

    override fun getItemCount(): Int = projects.size
}
