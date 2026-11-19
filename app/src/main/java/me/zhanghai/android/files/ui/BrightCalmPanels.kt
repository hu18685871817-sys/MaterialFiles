/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Environment
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.filelist.FileSortOptions
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.tools.EsToolboxActivity
import me.zhanghai.android.files.util.valueCompat

/**
 * Implements the three secondary overlays/panels from the UI & Interaction Design Spec:
 * 1) §5.6 文件详情 Bottom Sheet (expands to 90% height, large preview, tabular-nums attribute table,
 *    long-press copy, collapsible MD5, primary gradient [打开方式], secondary actions, destructive [移至回收站] + Snackbar undo)
 * 2) §6.1 排序与筛选 Bottom Sheet (400ms open / 350ms close, sort single-select + check icon,
 *    filter type multi-select chips + time/size range + switches, bottom [重置] / [显示 N 个结果])
 * 3) §5.5 全局搜索覆盖层 (250ms slide up + dissolve, auto-focus full-rounded input, search history with
 *    single delete & clear all, hot suggestions, quick filter chips, primary-highlighted match fragments,
 *    results grouped by type with 8dp type-color dots, and Section E empty search illustration).
 */
object BrightCalmPanels {

    data class FilterCriteria(
        val selectedCategories: Set<BrightCalmTheme.FileCategory> = emptySet(),
        val timeRangeDays: Int = 0, // 0 = all, 1 = today, 7 = 7 days, 30 = 30 days
        val minSizeBytes: Long = 0L, // 0 = all, 10MB, 100MB
        val foldersOnly: Boolean = false
    ) {
        val isActive: Boolean
            get() = selectedCategories.isNotEmpty() || timeRangeDays > 0 || minSizeBytes > 0L || foldersOnly

        fun matches(file: File): Boolean {
            if (foldersOnly && !file.isDirectory) return false
            if (selectedCategories.isNotEmpty()) {
                val cat = BrightCalmTheme.categoryForFile(file)
                val normCat = if (cat == BrightCalmTheme.FileCategory.PDF) BrightCalmTheme.FileCategory.DOC else cat
                if (cat !in selectedCategories && normCat !in selectedCategories) return false
            }
            if (timeRangeDays > 0) {
                val cutoff = System.currentTimeMillis() - timeRangeDays * 24L * 3600_000L
                if (file.lastModified() < cutoff) return false
            }
            if (minSizeBytes > 0L) {
                if (file.isDirectory || file.length() < minSizeBytes) return false
            }
            return true
        }
    }

    // ==================== 1. §5.6 文件详情 Bottom Sheet (90% Height) ====================

    fun showFileDetailSheet(
        context: Context,
        anchorViewForSnackbar: View,
        file: File,
        scope: CoroutineScope,
        onOpenFile: (File) -> Unit,
        onNavigateToParent: (Path) -> Unit,
        onShareFile: (File) -> Unit,
        onCopyOrMoveFile: (File, Boolean) -> Unit,
        onRenameFile: (File) -> Unit,
        onCompressFile: (File) -> Unit,
        onFileChanged: () -> Unit
    ) {
        val tokens = BrightCalmTheme.of(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val dialog = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = BrightCalmTheme.roundedBg(tokens.card, dp(16).toFloat())
            minimumHeight = (context.resources.displayMetrics.heightPixels * 0.88f).toInt()
        }

        // Top Drag Handle
        val handle = View(context).apply {
            background = BrightCalmTheme.roundedBg(tokens.border, dp(3).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            }
        }
        root.addView(handle)

        // AppBar: [X] | 详情 | [⋮]
        val appBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        }
        appBar.addView(
            BrightCalmTheme.createIconButton(context, BrightCalmTheme.UiIcon.CLOSE, tokens.foreground, "关闭") {
                dialog.dismiss()
            }
        )
        appBar.addView(TextView(context).apply {
            text = "详情"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(tokens.foreground)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        appBar.addView(
            BrightCalmTheme.createIconButton(context, BrightCalmTheme.UiIcon.MORE_VERTICAL, tokens.foreground, "更多") {
                copyToClipboard(context, "文件路径", file.absolutePath)
            }
        )
        root.addView(appBar)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val contentCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }

        // 1) Large Preview Area
        val category = BrightCalmTheme.categoryForFile(file)
        val previewBox = FrameLayout(context).apply {
            background = BrightCalmTheme.roundedBg(tokens.muted, dp(12).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(156)
            ).apply { bottomMargin = dp(16) }
        }
        if (category == BrightCalmTheme.FileCategory.IMAGE && file.exists() && file.length() < 15 * 1024 * 1024) {
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            previewBox.addView(iv)
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                        BitmapFactory.decodeFile(file.absolutePath, opts)
                    }.getOrNull()
                }
                if (bmp != null) iv.setImageBitmap(bmp)
            }
        } else {
            val centerCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }
            centerCol.addView(
                BrightCalmTheme.createFileTypeIconBox(context, tokens, category, boxSizeDp = 64, cornerRadiusDp = 14)
            )
            val extText = if (file.isDirectory) "FOLDER" else file.extension.uppercase(Locale.ROOT).ifEmpty { "FILE" }
            centerCol.addView(TextView(context).apply {
                text = extText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.mutedForeground)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
            previewBox.addView(centerCol)
        }
        contentCol.addView(previewBox)

        // 2) Attribute Table Card (left label 12sp muted / right value 14sp right-aligned tabular-nums, row height 44dp, 1px divider)
        val attrCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = BrightCalmTheme.cardBackground(tokens, dp(12).toFloat())
            setPadding(dp(16), dp(4), dp(16), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        }

        val df = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault())
        val sizeStr = if (file.isDirectory) {
            val count = file.listFiles()?.size ?: 0
            "$count 项"
        } else {
            "${Formatter.formatFileSize(context, file.length())} (${String.format(Locale.getDefault(), "%,d", file.length())} B)"
        }
        val typeLabel = when (category) {
            BrightCalmTheme.FileCategory.FOLDER -> "文件夹 (目录)"
            BrightCalmTheme.FileCategory.IMAGE -> "图片 (${file.extension.uppercase(Locale.ROOT)})"
            BrightCalmTheme.FileCategory.VIDEO -> "视频 (${file.extension.uppercase(Locale.ROOT)})"
            BrightCalmTheme.FileCategory.AUDIO -> "音频 (${file.extension.uppercase(Locale.ROOT)})"
            BrightCalmTheme.FileCategory.PDF -> "PDF 文档"
            BrightCalmTheme.FileCategory.DOC -> "文档 (${file.extension.uppercase(Locale.ROOT)})"
            BrightCalmTheme.FileCategory.SHEET -> "表格 (${file.extension.uppercase(Locale.ROOT)})"
            BrightCalmTheme.FileCategory.SLIDE -> "演示文稿 (${file.extension.uppercase(Locale.ROOT)})"
            BrightCalmTheme.FileCategory.ZIP -> "归档压缩包 (${file.extension.uppercase(Locale.ROOT)})"
            BrightCalmTheme.FileCategory.APK -> "安卓安装包 (APK)"
            BrightCalmTheme.FileCategory.CODE -> "代码源文件 (${file.extension.uppercase(Locale.ROOT)})"
            BrightCalmTheme.FileCategory.OTHER -> "文件 (${file.extension.uppercase(Locale.ROOT).ifEmpty { "未知" }})"
        }
        val permStr = buildString {
            append(if (file.isDirectory) "d" else "-")
            append(if (file.canRead()) "r" else "-")
            append(if (file.canWrite()) "w" else "-")
            append(if (file.canExecute()) "x" else "-")
        }
        val modTimeStr = df.format(Date(file.lastModified()))
        val parentPathStr = file.parentFile?.absolutePath ?: "/"

        fun addAttrRow(
            label: String,
            value: String,
            isLast: Boolean = false,
            valueColor: Int = tokens.foreground,
            onClick: (() -> Unit)? = null
        ): TextView {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                setPadding(0, dp(8), 0, dp(8))
                setOnLongClickListener {
                    copyToClipboard(context, label, value)
                    true
                }
                if (onClick != null) {
                    setOnClickListener { onClick() }
                }
            }
            val labelTv = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(tokens.mutedForeground)
                layoutParams = LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val valTv = TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(valueColor)
                gravity = Gravity.END
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
                BrightCalmTheme.applyTabularNums(this)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(labelTv)
            row.addView(valTv)
            attrCard.addView(row)
            if (!isLast) {
                attrCard.addView(View(context).apply {
                    setBackgroundColor(tokens.border)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                })
            }
            return valTv
        }

        addAttrRow("名称", file.name)
        addAttrRow("类型", typeLabel)
        addAttrRow("大小", sizeStr)
        addAttrRow("所在路径", "$parentPathStr →", valueColor = tokens.primary) {
            dialog.dismiss()
            file.parentFile?.let { onNavigateToParent(Paths.get(it.absolutePath)) }
        }
        addAttrRow("修改时间", modTimeStr)
        addAttrRow("创建时间", modTimeStr)
        addAttrRow("权限", permStr, isLast = file.isDirectory)

        if (!file.isDirectory) {
            lateinit var md5Tv: TextView
            md5Tv = addAttrRow("MD5 校验", "点击展开计算 ▾", isLast = true, valueColor = tokens.primary) {
                md5Tv.text = "计算中..."
                scope.launch {
                    val md5 = withContext(Dispatchers.IO) { computeMd5(file) }
                    md5Tv.text = md5
                    md5Tv.setTextColor(tokens.foreground)
                }
            }
        }
        contentCol.addView(attrCard)

        // 3) Action Area: Primary [打开方式] gradient button
        val primaryOpenBtn = TextView(context).apply {
            text = if (file.isDirectory) "打开此文件夹" else "打开方式"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(tokens.primaryForeground)
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            background = BrightCalmTheme.brandGradientBg(tokens, dp(10).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { bottomMargin = dp(12) }
            setOnClickListener {
                dialog.dismiss()
                onOpenFile(file)
            }
        }
        contentCol.addView(primaryOpenBtn)

        // Secondary Action Row: 分享 · 复制 · 移动 · 重命名 · 收藏 · 压缩
        var isFav = BrightCalmTheme.isFavorite(context, file.absolutePath)
        val secRow1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val secRow2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        fun makeSecBtn(label: String, onClick: (TextView) -> Unit): TextView {
            return TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.foreground)
                gravity = Gravity.CENTER
                minimumHeight = dp(44)
                background = BrightCalmTheme.roundedBg(tokens.secondary, dp(10).toFloat(), tokens.border, 1)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener { onClick(this) }
            }
        }

        secRow1.addView(makeSecBtn("分享") {
            dialog.dismiss()
            onShareFile(file)
        })
        secRow1.addView(makeSecBtn("复制") {
            dialog.dismiss()
            onCopyOrMoveFile(file, true)
        })
        secRow1.addView(makeSecBtn("移动") {
            dialog.dismiss()
            onCopyOrMoveFile(file, false)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        })

        secRow2.addView(makeSecBtn("重命名") {
            dialog.dismiss()
            onRenameFile(file)
        })
        secRow2.addView(makeSecBtn(if (isFav) "★ 已收藏" else "☆ 收藏") { btn ->
            isFav = BrightCalmTheme.toggleFavorite(context, file.absolutePath)
            btn.text = if (isFav) "★ 已收藏" else "☆ 收藏"
            Toast.makeText(context, if (isFav) "已添加至收藏夹" else "已取消收藏", Toast.LENGTH_SHORT).show()
            onFileChanged()
        })
        secRow2.addView(makeSecBtn("压缩") {
            dialog.dismiss()
            onCompressFile(file)
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        })

        contentCol.addView(secRow1)
        contentCol.addView(secRow2)

        // Danger Zone: [移至回收站] (destructive text + 1px border + secondary confirmation + Snackbar undo)
        val dangerBtn = TextView(context).apply {
            text = "移至回收站"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(tokens.destructive)
            gravity = Gravity.CENTER
            minimumHeight = dp(46)
            background = BrightCalmTheme.roundedBg(
                BrightCalmTheme.withAlpha(tokens.destructive, 0.08f),
                dp(10).toFloat(),
                BrightCalmTheme.withAlpha(tokens.destructive, 0.35f),
                1
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            )
            setOnClickListener {
                dialog.dismiss()
                confirmAndMoveToRecycleBin(context, anchorViewForSnackbar, file, onFileChanged)
            }
        }
        contentCol.addView(dangerBtn)

        scroll.addView(contentCol)
        root.addView(scroll)

        dialog.setContentView(root)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        dialog.show()
    }

    /**
     * Moves a file to the Recycle Bin (or deletes it if disabled in Settings),
     * showing a confirmation Dialog and a 5s Snackbar with「撤销」action (§07.2).
     */
    fun confirmAndMoveToRecycleBin(
        context: Context,
        anchorView: View,
        file: File,
        onCompleted: () -> Unit
    ) {
        val prefs = context.getSharedPreferences(BrightCalmTheme.PREFS_NAME, Context.MODE_PRIVATE)
        val needConfirm = prefs.getBoolean(BrightCalmTheme.KEY_CONFIRM_DELETE, true)
        val useRecycleBin = prefs.getBoolean(BrightCalmTheme.KEY_MOVE_TO_RECYCLE_BIN, true)

        val executeAction = {
            if (useRecycleBin) {
                val recycleDir = File(Environment.getExternalStorageDirectory(), ".es_recycle_bin").apply { mkdirs() }
                val originalParent = file.parentFile
                val backupTarget = File(recycleDir, file.name)
                val moved = file.renameTo(backupTarget)
                onCompleted()
                if (moved) {
                    val tokens = BrightCalmTheme.of(context)
                    Snackbar.make(anchorView, "已移至回收站：${file.name}", 5000)
                        .setAction("撤销") {
                            if (originalParent != null) {
                                backupTarget.renameTo(File(originalParent, file.name))
                                onCompleted()
                                Toast.makeText(context, "已撤销删除", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setActionTextColor(tokens.primary2)
                        .show()
                } else {
                    Toast.makeText(context, "请通过文件列表执行删除操作", Toast.LENGTH_SHORT).show()
                }
            } else {
                val deleted = file.deleteRecursively()
                onCompleted()
                Toast.makeText(context, if (deleted) "已永久删除 ${file.name}" else "删除失败", Toast.LENGTH_SHORT).show()
            }
        }

        if (needConfirm) {
            AlertDialog.Builder(context)
                .setTitle(if (useRecycleBin) "移至回收站" else "永久删除")
                .setMessage(
                    if (useRecycleBin) "确定要将「${file.name}」移至回收站吗？可在 30 天内随时还原。"
                    else "确定要永久删除「${file.name}」吗？此操作无法恢复。"
                )
                .setPositiveButton(if (useRecycleBin) "移至回收站" else "永久删除") { _, _ ->
                    executeAction()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            executeAction()
        }
    }

    // ==================== 2. §6.1 排序与筛选 Bottom Sheet ====================

    fun showSortAndFilterSheet(
        context: Context,
        currentSort: FileSortOptions,
        currentFilter: FilterCriteria,
        totalFilesInDir: List<File>,
        onApplySortAndFilter: (FileSortOptions.By, FileSortOptions.Order, Boolean, FilterCriteria) -> Unit
    ) {
        val tokens = BrightCalmTheme.of(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        var selectedBy = currentSort.by
        var selectedOrder = currentSort.order
        var dirsFirst = currentSort.isDirectoriesFirst
        val selectedCats = currentFilter.selectedCategories.toMutableSet()
        var selectedTimeDays = currentFilter.timeRangeDays
        var selectedMinSize = currentFilter.minSizeBytes
        var foldersOnly = currentFilter.foldersOnly

        val dialog = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = BrightCalmTheme.roundedBg(tokens.card, dp(16).toFloat())
            setPadding(dp(16), dp(10), dp(16), dp(20))
        }

        // Handle
        root.addView(View(context).apply {
            background = BrightCalmTheme.roundedBg(tokens.border, dp(3).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        })

        // Title Row
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        headerRow.addView(TextView(context).apply {
            text = "排序与条件筛选"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(tokens.foreground)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(
            BrightCalmTheme.createIconButton(context, BrightCalmTheme.UiIcon.CLOSE, tokens.mutedForeground, "关闭") {
                dialog.dismiss()
            }
        )
        root.addView(headerRow)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val bodyCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(bodyCol)
        root.addView(scroll)

        // Sticky Bottom Bar: [重置] / [显示 N 个结果]
        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val resetBtn = TextView(context).apply {
            text = "重置"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(tokens.foreground)
            gravity = Gravity.CENTER
            background = BrightCalmTheme.roundedBg(tokens.secondary, dp(10).toFloat(), tokens.border, 1)
            layoutParams = LinearLayout.LayoutParams(dp(100), dp(46)).apply {
                rightMargin = dp(10)
            }
        }
        val applyBtn = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(tokens.primaryForeground)
            gravity = Gravity.CENTER
            background = BrightCalmTheme.brandGradientBg(tokens, dp(10).toFloat())
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
        }
        bottomBar.addView(resetBtn)
        bottomBar.addView(applyBtn)
        root.addView(bottomBar)

        fun computeMatchingCount(): Int {
            val crit = FilterCriteria(selectedCats.toSet(), selectedTimeDays, selectedMinSize, foldersOnly)
            if (totalFilesInDir.isEmpty()) return 0
            return totalFilesInDir.count { crit.matches(it) }
        }

        fun renderSheetContent() {
            bodyCol.removeAllViews()

            // Section 1: 排序维度 (名称 / 修改时间 / 大小 / 类型)
            bodyCol.addView(TextView(context).apply {
                text = "排序规则"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.mutedForeground)
                setPadding(0, dp(4), 0, dp(8))
            })

            val sortItems = listOf(
                FileSortOptions.By.LAST_MODIFIED to "按修改时间",
                FileSortOptions.By.NAME to "按文件名称",
                FileSortOptions.By.SIZE to "按文件大小",
                FileSortOptions.By.TYPE to "按文件类型"
            )
            sortItems.forEach { (byVal, label) ->
                val active = selectedBy == byVal
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(46)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = if (active) {
                        BrightCalmTheme.roundedBg(BrightCalmTheme.withAlpha(tokens.primary, 0.08f), dp(10).toFloat())
                    } else null
                    setOnClickListener {
                        if (selectedBy == byVal) {
                            selectedOrder = if (selectedOrder == FileSortOptions.Order.ASCENDING)
                                FileSortOptions.Order.DESCENDING else FileSortOptions.Order.ASCENDING
                        } else {
                            selectedBy = byVal
                        }
                        renderSheetContent()
                    }
                }
                row.addView(TextView(context).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (active) tokens.primary else tokens.foreground)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (active) {
                    val dirText = if (selectedOrder == FileSortOptions.Order.ASCENDING) "升序 ↑" else "降序 ↓"
                    row.addView(TextView(context).apply {
                        text = "$dirText  ✓"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(tokens.primary)
                    })
                }
                bodyCol.addView(row)
            }

            // Section 2: 类型筛选 (Multi-select chips with 14% type color tint when selected)
            bodyCol.addView(TextView(context).apply {
                text = "文件类型（可多选）"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.mutedForeground)
                setPadding(0, dp(16), 0, dp(8))
            })

            val chipTypes = listOf(
                null to "全部",
                BrightCalmTheme.FileCategory.IMAGE to "图片",
                BrightCalmTheme.FileCategory.VIDEO to "视频",
                BrightCalmTheme.FileCategory.AUDIO to "音频",
                BrightCalmTheme.FileCategory.DOC to "文档",
                BrightCalmTheme.FileCategory.SHEET to "表格",
                BrightCalmTheme.FileCategory.ZIP to "压缩包",
                BrightCalmTheme.FileCategory.APK to "安装包"
            )
            val chipScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
            }
            val chipRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            chipTypes.forEach { (cat, label) ->
                val isSelected = if (cat == null) selectedCats.isEmpty() else cat in selectedCats
                val fgColor = when {
                    !isSelected -> tokens.mutedForeground
                    cat == null -> tokens.primary
                    else -> tokens.typeColor(cat)
                }
                val bgColor = when {
                    !isSelected -> tokens.secondary
                    else -> tokens.chipActiveBg(cat)
                }
                val strokeColor = if (isSelected && cat == null) tokens.primary else 0
                val chip = TextView(context).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(fgColor)
                    background = BrightCalmTheme.roundedBg(bgColor, dp(8).toFloat(), strokeColor, if (strokeColor != 0) 1 else 0)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(8) }
                    setOnClickListener {
                        if (cat == null) {
                            selectedCats.clear()
                        } else {
                            if (cat in selectedCats) selectedCats.remove(cat) else selectedCats.add(cat)
                        }
                        renderSheetContent()
                    }
                }
                chipRow.addView(chip)
            }
            chipScroll.addView(chipRow)
            bodyCol.addView(chipScroll)

            // Section 3: 时间范围 & 大小区间
            bodyCol.addView(TextView(context).apply {
                text = "修改时间范围"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.mutedForeground)
                setPadding(0, dp(16), 0, dp(8))
            })
            val timeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(0 to "不限", 1 to "今天", 7 to "近 7 天", 30 to "近 30 天").forEach { (days, label) ->
                val active = selectedTimeDays == days
                timeRow.addView(TextView(context).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(if (active) tokens.primary else tokens.mutedForeground)
                    background = BrightCalmTheme.roundedBg(
                        if (active) BrightCalmTheme.withAlpha(tokens.primary, 0.12f) else tokens.secondary,
                        dp(8).toFloat(),
                        if (active) tokens.primary else 0,
                        if (active) 1 else 0
                    )
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(8) }
                    setOnClickListener {
                        selectedTimeDays = days
                        renderSheetContent()
                    }
                })
            }
            bodyCol.addView(timeRow)

            bodyCol.addView(TextView(context).apply {
                text = "文件大小区间"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.mutedForeground)
                setPadding(0, dp(16), 0, dp(8))
            })
            val sizeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(0L to "不限", 10L * 1024 * 1024 to "> 10 MB", 100L * 1024 * 1024 to "> 100 MB").forEach { (bytes, label) ->
                val active = selectedMinSize == bytes
                sizeRow.addView(TextView(context).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(if (active) tokens.primary else tokens.mutedForeground)
                    background = BrightCalmTheme.roundedBg(
                        if (active) BrightCalmTheme.withAlpha(tokens.primary, 0.12f) else tokens.secondary,
                        dp(8).toFloat(),
                        if (active) tokens.primary else 0,
                        if (active) 1 else 0
                    )
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(8) }
                    setOnClickListener {
                        selectedMinSize = bytes
                        renderSheetContent()
                    }
                })
            }
            bodyCol.addView(sizeRow)

            // Switches: 文件夹优先 / 仅显示文件夹 / 显示隐藏文件
            fun addSwitchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
                val r = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    setPadding(0, dp(6), 0, dp(6))
                }
                r.addView(TextView(context).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(tokens.foreground)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                r.addView(Switch(context).apply {
                    isChecked = checked
                    setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
                })
                bodyCol.addView(r)
            }

            addSwitchRow("文件夹恒排在前面", dirsFirst) {
                dirsFirst = it
            }
            addSwitchRow("仅显示文件夹", foldersOnly) {
                foldersOnly = it
                renderSheetContent()
            }
            addSwitchRow("显示隐藏文件 (.开头)", Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat) {
                Settings.FILE_LIST_SHOW_HIDDEN_FILES.putValue(it)
            }

            val matchCount = computeMatchingCount()
            applyBtn.text = if (totalFilesInDir.isNotEmpty()) "显示 $matchCount 个结果" else "应用排序与筛选"
        }

        resetBtn.setOnClickListener {
            selectedBy = FileSortOptions.By.LAST_MODIFIED
            selectedOrder = FileSortOptions.Order.DESCENDING
            dirsFirst = true
            selectedCats.clear()
            selectedTimeDays = 0
            selectedMinSize = 0L
            foldersOnly = false
            renderSheetContent()
        }

        applyBtn.setOnClickListener {
            dialog.dismiss()
            onApplySortAndFilter(
                selectedBy,
                selectedOrder,
                dirsFirst,
                FilterCriteria(selectedCats.toSet(), selectedTimeDays, selectedMinSize, foldersOnly)
            )
        }

        renderSheetContent()
        dialog.setContentView(root)
        dialog.show()
    }

    // ==================== 3. §5.5 全局搜索覆盖层 (Search Overlay) ====================

    fun showGlobalSearchOverlay(
        context: Context,
        currentDir: File?,
        scope: CoroutineScope,
        onOpenFile: (File) -> Unit,
        onShowFileDetail: (File) -> Unit
    ) {
        val tokens = BrightCalmTheme.of(context)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        var searchScopeGlobal = true
        var filterCategory: BrightCalmTheme.FileCategory? = null
        var searchJob: Job? = null

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tokens.background)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top Search AppBar (56dp): [←] | Full-rounded input | [×]
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(tokens.background)
            setPadding(dp(8), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        }
        topBar.addView(
            BrightCalmTheme.createIconButton(context, BrightCalmTheme.UiIcon.ARROW_LEFT, tokens.foreground, "返回") {
                dialog.dismiss()
            }
        )

        val inputContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = BrightCalmTheme.roundedBg(tokens.muted, dp(22).toFloat(), tokens.border, 1)
            setPadding(dp(14), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        }
        val searchEdit = EditText(context).apply {
            hint = "搜索文件名、扩展名或关键词…"
            setHintTextColor(tokens.mutedForeground)
            setTextColor(tokens.foreground)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine(true)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val clearBtn = BrightCalmTheme.createIconButton(context, BrightCalmTheme.UiIcon.CLOSE, tokens.mutedForeground, "清空") {
            // 400ms dissolve on clear (§12.2)
            searchEdit.animate().alpha(0f).setDuration(150).withEndAction {
                searchEdit.setText("")
                searchEdit.animate().alpha(1f).setDuration(250).start()
            }.start()
        }.apply {
            visibility = View.GONE
        }
        inputContainer.addView(searchEdit)
        inputContainer.addView(clearBtn)
        topBar.addView(inputContainer)
        root.addView(topBar)

        // 1px bottom border
        root.addView(View(context).apply {
            setBackgroundColor(tokens.border)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })

        // Content ScrollView
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val contentCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(48))
        }
        scroll.addView(contentCol)
        root.addView(scroll)

        fun renderSearchState(query: String) {
            searchJob?.cancel()
            contentCol.removeAllViews()

            // Quick Scope & Type Filter Row
            val filterScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
            }
            val filterRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

            // Scope chip: 全局 vs 当前目录
            val scopeChip = TextView(context).apply {
                text = if (searchScopeGlobal || currentDir == null) "范围：全部存储" else "范围：当前目录 (${currentDir.name})"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.primary)
                background = BrightCalmTheme.roundedBg(
                    BrightCalmTheme.withAlpha(tokens.primary, 0.12f),
                    dp(8).toFloat(),
                    tokens.primary,
                    1
                )
                setPadding(dp(12), dp(7), dp(12), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                setOnClickListener {
                    if (currentDir != null) {
                        searchScopeGlobal = !searchScopeGlobal
                        renderSearchState(searchEdit.text.toString())
                    }
                }
            }
            filterRow.addView(scopeChip)

            val typeChips = listOf(
                null to "全部类型",
                BrightCalmTheme.FileCategory.IMAGE to "图片",
                BrightCalmTheme.FileCategory.VIDEO to "视频",
                BrightCalmTheme.FileCategory.AUDIO to "音频",
                BrightCalmTheme.FileCategory.DOC to "文档/PDF",
                BrightCalmTheme.FileCategory.ZIP to "压缩包",
                BrightCalmTheme.FileCategory.APK to "安装包"
            )
            typeChips.forEach { (cat, label) ->
                val active = filterCategory == cat
                val fg = when {
                    !active -> tokens.mutedForeground
                    cat == null -> tokens.primary
                    else -> tokens.typeColor(cat)
                }
                val bg = if (active) tokens.chipActiveBg(cat) else tokens.secondary
                filterRow.addView(TextView(context).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(fg)
                    background = BrightCalmTheme.roundedBg(bg, dp(8).toFloat())
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(8) }
                    setOnClickListener {
                        filterCategory = cat
                        renderSearchState(searchEdit.text.toString())
                    }
                })
            }
            filterScroll.addView(filterRow)
            contentCol.addView(filterScroll)

            if (query.isBlank() && filterCategory == null) {
                // 1) Search History
                val history = BrightCalmTheme.getSearchHistory(context)
                if (history.isNotEmpty()) {
                    val histHead = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(4), 0, dp(8))
                    }
                    histHead.addView(TextView(context).apply {
                        text = "搜索历史"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(tokens.mutedForeground)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    histHead.addView(TextView(context).apply {
                        text = "清空历史"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(tokens.destructive)
                        setPadding(dp(8), dp(4), dp(4), dp(4))
                        setOnClickListener {
                            BrightCalmTheme.clearSearchHistory(context)
                            renderSearchState("")
                        }
                    })
                    contentCol.addView(histHead)

                    history.forEach { hItem ->
                        val hRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            minimumHeight = dp(44)
                            setOnClickListener {
                                searchEdit.setText(hItem)
                                searchEdit.setSelection(hItem.length)
                            }
                        }
                        hRow.addView(
                            BrightCalmTheme.createUiIconView(context, BrightCalmTheme.UiIcon.CLOCK, tokens.mutedForeground, 16)
                        )
                        hRow.addView(TextView(context).apply {
                            text = hItem
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            setTextColor(tokens.foreground)
                            setPadding(dp(10), 0, dp(10), 0)
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        hRow.addView(TextView(context).apply {
                            text = "×"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                            setTextColor(tokens.mutedForeground)
                            setPadding(dp(12), dp(6), dp(12), dp(6))
                            setOnClickListener {
                                BrightCalmTheme.removeSearchHistoryItem(context, hItem)
                                renderSearchState("")
                            }
                        })
                        contentCol.addView(hRow)
                    }
                }

                // 2) Hot Suggestions
                contentCol.addView(TextView(context).apply {
                    text = "热门检索建议"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(tokens.mutedForeground)
                    setPadding(0, dp(16), 0, dp(10))
                })
                val hotWrap = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                listOf("pdf", "jpg", "mp4", "zip", "apk", "Download").forEach { tag ->
                    hotWrap.addView(TextView(context).apply {
                        text = tag
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(tokens.foreground)
                        background = BrightCalmTheme.roundedBg(tokens.secondary, dp(8).toFloat(), tokens.border, 1)
                        setPadding(dp(12), dp(7), dp(12), dp(7))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { rightMargin = dp(8) }
                        setOnClickListener {
                            searchEdit.setText(tag)
                            searchEdit.setSelection(tag.length)
                        }
                    })
                }
                contentCol.addView(hotWrap)
                return
            }

            // Execute real-time search
            val startMs = System.currentTimeMillis()
            searchJob = scope.launch {
                val matchedFiles = withContext(Dispatchers.IO) {
                    val pool = if (!searchScopeGlobal && currentDir != null && currentDir.exists()) {
                        currentDir.walkTopDown().maxDepth(3).filter { it.isFile && !it.name.startsWith(".") }.toList()
                    } else {
                        val all = mutableListOf<File>()
                        listOf(
                            EsToolboxActivity.CAT_IMAGE,
                            EsToolboxActivity.CAT_VIDEO,
                            EsToolboxActivity.CAT_AUDIO,
                            EsToolboxActivity.CAT_DOC,
                            EsToolboxActivity.CAT_ARCHIVE,
                            EsToolboxActivity.CAT_APK
                        ).forEach { cat ->
                            all.addAll(EsToolboxActivity.queryFilesFast(context, cat, forceRefresh = false))
                        }
                        all.distinctBy { it.absolutePath }
                    }
                    pool.filter { f ->
                        val qMatch = query.isBlank() || f.name.contains(query, ignoreCase = true)
                        val cMatch = filterCategory == null || run {
                            val c = BrightCalmTheme.categoryForFile(f)
                            c == filterCategory || (filterCategory == BrightCalmTheme.FileCategory.DOC && c == BrightCalmTheme.FileCategory.PDF)
                        }
                        qMatch && cMatch
                    }.take(120)
                }
                val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)

                if (query.length >= 2) {
                    BrightCalmTheme.addSearchHistory(context, query)
                }

                if (matchedFiles.isEmpty()) {
                    val qTitle = if (query.isNotBlank()) "没找到「$query」" else "没找到符合筛选条件的文件"
                    contentCol.addView(
                        BrightCalmTheme.buildEmptyStateView(
                            context = context,
                            tokens = tokens,
                            type = BrightCalmTheme.EmptyStateType.SEARCH_NO_RESULT,
                            titleText = qTitle,
                            subtitleText = "换个关键词，或放宽筛选条件",
                            actionLabel = "清除筛选"
                        ) {
                            filterCategory = null
                            searchEdit.setText("")
                        }
                    )
                    return@launch
                }

                // Summary header: 共 N 项 · 用时 xx ms (tabular-nums)
                contentCol.addView(TextView(context).apply {
                    text = "共 ${matchedFiles.size} 项 · 用时 ${elapsedMs} ms"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(tokens.mutedForeground)
                    BrightCalmTheme.applyTabularNums(this)
                    setPadding(0, dp(2), 0, dp(10))
                })

                // Group results by FileCategory with 8dp type-color dot on the left (§5.5)
                val grouped = matchedFiles.groupBy { BrightCalmTheme.categoryForFile(it) }
                val df = SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault())

                grouped.forEach { (cat, filesInGroup) ->
                    val groupHead = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(12), 0, dp(6))
                    }
                    // 8dp type-color dot
                    groupHead.addView(View(context).apply {
                        background = BrightCalmTheme.roundedBg(tokens.typeColor(cat), dp(4).toFloat())
                        layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                            rightMargin = dp(8)
                        }
                    })
                    val catName = when (cat) {
                        BrightCalmTheme.FileCategory.FOLDER -> "文件夹"
                        BrightCalmTheme.FileCategory.IMAGE -> "图片"
                        BrightCalmTheme.FileCategory.VIDEO -> "视频"
                        BrightCalmTheme.FileCategory.AUDIO -> "音频"
                        BrightCalmTheme.FileCategory.DOC, BrightCalmTheme.FileCategory.PDF -> "文档"
                        BrightCalmTheme.FileCategory.SHEET -> "表格"
                        BrightCalmTheme.FileCategory.SLIDE -> "演示"
                        else -> "归档与其他"
                    }
                    groupHead.addView(TextView(context).apply {
                        text = "$catName (${filesInGroup.size})"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(tokens.mutedForeground)
                        BrightCalmTheme.applyTabularNums(this)
                    })
                    contentCol.addView(groupHead)

                    filesInGroup.forEach { f ->
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            minimumHeight = dp(64)
                            setPadding(0, dp(8), 0, dp(8))
                            setOnClickListener {
                                dialog.dismiss()
                                onOpenFile(f)
                            }
                        }
                        row.addView(BrightCalmTheme.createFileTypeIconBox(context, tokens, cat, 40, 12))

                        val textCol = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                leftMargin = dp(12)
                            }
                        }
                        val displayName = BrightCalmTheme.formatDisplayFileName(context, f.name, f.isDirectory)
                        val nameTv = TextView(context).apply {
                            text = highlightQuery(displayName, query, tokens.primary)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(tokens.foreground)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.MIDDLE
                        }
                        val metaTv = TextView(context).apply {
                            text = "${Formatter.formatFileSize(context, f.length())} · ${df.format(Date(f.lastModified()))} · ${f.parentFile?.name ?: ""}"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setTextColor(tokens.mutedForeground)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            BrightCalmTheme.applyTabularNums(this)
                            setPadding(0, dp(2), 0, 0)
                        }
                        textCol.addView(nameTv)
                        textCol.addView(metaTv)
                        row.addView(textCol)

                        row.addView(
                            BrightCalmTheme.createIconButton(
                                context,
                                BrightCalmTheme.UiIcon.MORE_VERTICAL,
                                tokens.mutedForeground,
                                "更多操作"
                            ) {
                                dialog.dismiss()
                                onShowFileDetail(f)
                            }
                        )
                        contentCol.addView(row)

                        // 1px divider indented 56dp from left (§5.4)
                        contentCol.addView(View(context).apply {
                            setBackgroundColor(tokens.border)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                1
                            ).apply { leftMargin = dp(52) }
                        })
                    }
                }
            }
        }

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty()
                clearBtn.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                renderSearchState(q)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        renderSearchState("")

        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(tokens.background))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
        searchEdit.requestFocus()
    }

    private fun highlightQuery(text: String, query: String, primaryColor: Int): CharSequence {
        if (query.isBlank()) return text
        val idx = text.lowercase(Locale.ROOT).indexOf(query.lowercase(Locale.ROOT))
        if (idx < 0) return text
        return SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(primaryColor),
                idx,
                (idx + query.length).coerceAtMost(text.length),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                StyleSpan(Typeface.BOLD),
                idx,
                (idx + query.length).coerceAtMost(text.length),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "已复制 $label：$text", Toast.LENGTH_SHORT).show()
    }

    private fun computeMd5(file: File): String {
        return runCatching {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(16 * 1024)
                var read: Int
                var totalRead = 0L
                val limit = 64L * 1024 * 1024 // Cap at 64MB for fast UI response
                while (fis.read(buf).also { read = it } != -1 && totalRead < limit) {
                    md.update(buf, 0, read)
                    totalRead += read
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("无法读取")
    }
}
