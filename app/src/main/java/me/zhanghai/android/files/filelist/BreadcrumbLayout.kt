/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.BreadcrumbItemBinding
import me.zhanghai.android.files.ui.BrightCalmTheme
import me.zhanghai.android.files.util.getDimensionPixelSize
import me.zhanghai.android.files.util.getResourceIdByAttr
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.withTheme

class BreadcrumbLayout : HorizontalScrollView {
    private val tabLayoutHeight = context.getDimensionPixelSize(R.dimen.tab_layout_height)
    private val popupContext = context.withTheme(
        context.getResourceIdByAttr(androidx.appcompat.R.attr.actionBarPopupTheme)
    )

    private val itemsLayout: LinearLayout

    private lateinit var listener: Listener
    private lateinit var data: BreadcrumbData

    private var isLayoutDirty = true
    private var isScrollToSelectedItemPending = false
    private var isFirstScroll = true

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(
        context, attrs
    )

    constructor(context: Context, attrs: AttributeSet?, @AttrRes defStyleAttr: Int) : super(
        context, attrs, defStyleAttr
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        @AttrRes defStyleAttr: Int,
        @StyleRes defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    init {
        isHorizontalScrollBarEnabled = false
        itemsLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        itemsLayout.setPaddingRelative(paddingStart, paddingTop, paddingEnd, paddingBottom)
        setPaddingRelative(0, 0, 0, 0)
        addView(itemsLayout, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    override fun jumpDrawablesToCurrentState() {
        if (isInLayout) {
            return
        }
        super.jumpDrawablesToCurrentState()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val newHeightMeasureSpec = if (heightMode != MeasureSpec.EXACTLY) {
            val maximumHeight = if (heightMode == MeasureSpec.AT_MOST) {
                MeasureSpec.getSize(heightMeasureSpec)
            } else {
                Int.MAX_VALUE
            }
            val height = tabLayoutHeight.coerceAtMost(maximumHeight)
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, newHeightMeasureSpec)
    }

    override fun requestLayout() {
        isLayoutDirty = true

        super.requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        isLayoutDirty = false
        if (isScrollToSelectedItemPending) {
            scrollToSelectedItem()
            isScrollToSelectedItemPending = false
        }
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun setData(data: BreadcrumbData) {
        if (this::data.isInitialized && this.data == data) {
            return
        }
        this.data = data
        inflateItemViews()
        bindItemViews()
        scrollToSelectedItem()
    }

    private fun scrollToSelectedItem() {
        if (isLayoutDirty) {
            isScrollToSelectedItemPending = true
            return
        }
        val selectedItemView = itemsLayout.getChildAt(data.selectedIndex) ?: return
        val scrollX = if (layoutDirection == View.LAYOUT_DIRECTION_LTR) {
            selectedItemView.left - itemsLayout.paddingStart
        } else {
            selectedItemView.right - width + itemsLayout.paddingStart
        }
        if (!isFirstScroll && isShown) {
            smoothScrollTo(scrollX, 0)
        } else {
            scrollTo(scrollX, 0)
        }
        isFirstScroll = false
    }

    private fun inflateItemViews() {
        for (index in data.paths.size..<itemsLayout.childCount) {
            itemsLayout.removeViewAt(0)
        }
        for (index in itemsLayout.childCount..<data.paths.size) {
            val binding = BreadcrumbItemBinding.inflate(context.layoutInflater, itemsLayout, false)
            val menu = PopupMenu(popupContext, binding.root)
                .apply { inflate(R.menu.file_list_breadcrumb) }
            binding.root.tag = binding to menu
            itemsLayout.addView(binding.root, 0)
        }
    }

    private fun bindItemViews() {
        val t = BrightCalmTheme.of(context)
        for (index in data.paths.indices) {
            @Suppress("UNCHECKED_CAST")
            val tag = itemsLayout.getChildAt(index).tag as Pair<BreadcrumbItemBinding, PopupMenu>
            val (binding, menu) = tag
            val isSelected = index == data.selectedIndex
            binding.text.text = data.nameProducers[index](binding.text.context)
            binding.text.setTextColor(if (isSelected) t.primary else t.mutedForeground)
            binding.text.setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
            binding.arrowImage.imageTintList = ColorStateList.valueOf(t.mutedForeground)
            binding.arrowImage.isVisible = index != data.paths.size - 1
            binding.root.isActivated = isSelected
            val path = data.paths[index]
            binding.root.setOnClickListener {
                if (data.selectedIndex == index) {
                    scrollToSelectedItem()
                } else {
                    listener.navigateTo(path)
                }
            }
            binding.root.setOnLongClickListener {
                listener.copyPath(path)
                Toast.makeText(context, "已复制完整路径", Toast.LENGTH_SHORT).show()
                menu.show()
                true
            }
            menu.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_copy_path -> {
                        listener.copyPath(path)
                        true
                    }
                    R.id.action_open_in_new_task -> {
                        listener.openInNewTask(path)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    interface Listener {
        fun navigateTo(path: Path)
        fun copyPath(path: Path)
        fun openInNewTask(path: Path)
    }
}
