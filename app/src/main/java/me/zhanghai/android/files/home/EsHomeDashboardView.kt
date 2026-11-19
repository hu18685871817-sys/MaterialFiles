/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.home

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.text.TextUtils
import android.text.format.Formatter
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.about.AboutActivity
import me.zhanghai.android.files.cloud.CloudDriveActivity
import me.zhanghai.android.files.diskanalysis.DiskAnalysisActivity
import me.zhanghai.android.files.filelist.FileSortOptions
import me.zhanghai.android.files.filelist.FileViewType
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.tools.EsToolboxActivity
import me.zhanghai.android.files.ui.BrightCalmPanels
import me.zhanghai.android.files.ui.BrightCalmTheme
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.viewer.archive.ArchivePreviewActivity
import me.zhanghai.android.files.viewer.document.DocumentPreviewActivity
import me.zhanghai.android.files.viewer.image.ImageViewerActivity
import me.zhanghai.android.files.viewer.media.MediaPlayerActivity
import me.zhanghai.android.files.viewer.reader.ReaderActivity

/**
 * Implements the 4 primary tabs of v1.1「晴彩 Bright Calm」UI & Interaction Design Spec:
 * - Tab 0 (首页 §5.1): Large title + Storage usage card with multi-color segmented progress bar +
 *   2x4 Category grid (each cell 18% type color tint) + Recent files rows + Common locations rows.
 * - Tab 1 (最近 §5.2): Type filter chips (14% type color active tint) + Cleanup suggestion card +
 *   Time-grouped file rows (今天 / 昨天 / 本周 / 更早) + Section E empty state illustration.
 * - Tab 2 (分类 §5.3): 2-column Type grid (18% type color tint, thumbnail collage or linear icon +
 *   count + size) + Storage locations rows + Tools rows (大文件 / 重复文件 / 回收站 / 下载目录 / 隐藏文件夹).
 * - Tab 3 (设置 §5.7 & §09): Top brand info card + 6 setting groups with 8dp type-color dots and
 *   56dp rows (外观与显示 / 文件与浏览 / 存储与清理 / 安全与隐私 / 通知与同步 / 关于与帮助).
 */
class EsHomeDashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        const val TAB_HOME = 0
        const val TAB_RECENT = 1
        const val TAB_CATEGORY = 2
        const val TAB_SETTINGS = 3
    }

    var onNavigateToFolder: ((Path) -> Unit)? = null
    var onRequestSearchOverlay: (() -> Unit)? = null
    var onRequestSortFilterSheet: (() -> Unit)? = null
    var onThemeOrPreferenceChanged: (() -> Unit)? = null
    var onSwitchTabRequested: ((Int) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentTab: Int = TAB_HOME
    private var recentFilterCategory: BrightCalmTheme.FileCategory? = null

    private val topAppBarContainer: LinearLayout
    private val pageScrollView: ScrollView
    private val pageContentContainer: LinearLayout

    private data class StorageBreakdown(
        val totalBytes: Long,
        val usedBytes: Long,
        val imageBytes: Long,
        val videoBytes: Long,
        val docBytes: Long,
        val otherBytes: Long,
        val countsByCat: Map<String, Int>,
        val sizesByCat: Map<String, Long>,
        val previewFilesByCat: Map<String, List<File>>,
        val recentFiles: List<File>,
        val largeFilesCount: Int,
        val largeFilesBytes: Long
    )

    private var cachedMetrics: StorageBreakdown? = null

    init {
        val tokens = BrightCalmTheme.of(context)
        setBackgroundColor(tokens.background)

        val rootCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Fixed 56dp AppBar at top of primary Tab pages (§04.2)
        topAppBarContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(tokens.background)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootCol.addView(topAppBarContainer)

        pageScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // Safe area & grid padding: left/right 16dp, bottom >= 104dp (§04.3 & §10.3)
        pageContentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(112))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        pageScrollView.addView(pageContentContainer)
        rootCol.addView(pageScrollView)
        addView(rootCol)

        renderActiveTab(animate = false)
        refreshDashboardData()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }

    fun getCurrentTab(): Int = currentTab

    fun selectTab(tabIndex: Int, animate: Boolean = true) {
        if (currentTab == tabIndex) {
            // Double-tap / re-tap current tab: scroll to top (§04.1 & §6.3)
            pageScrollView.smoothScrollTo(0, 0)
            return
        }
        currentTab = tabIndex
        renderActiveTab(animate = animate)
    }

    fun scrollToTop() {
        pageScrollView.smoothScrollTo(0, 0)
    }

    fun refreshDashboardData() {
        val tokens = BrightCalmTheme.of(context)
        setBackgroundColor(tokens.background)
        topAppBarContainer.setBackgroundColor(tokens.background)

        scope.launch {
            val metrics = withContext(Dispatchers.IO) {
                computeStorageAndCategoryMetrics()
            }
            cachedMetrics = metrics
            renderActiveTab(animate = false)
        }
    }

    private fun renderActiveTab(animate: Boolean) {
        val tokens = BrightCalmTheme.of(context)
        setBackgroundColor(tokens.background)
        topAppBarContainer.setBackgroundColor(tokens.background)

        buildPrimaryTabAppBar(tokens)

        val populateBlock = {
            pageContentContainer.removeAllViews()
            when (currentTab) {
                TAB_HOME -> buildHomeTabContent(tokens)
                TAB_RECENT -> buildRecentTabContent(tokens)
                TAB_CATEGORY -> buildCategoryTabContent(tokens)
                TAB_SETTINGS -> buildSettingsTabContent(tokens)
            }
        }

        if (animate) {
            // §04.1 & §12.2: 250ms 8px translation + cross-dissolve
            val shiftPx = dp(8).toFloat()
            pageContentContainer.animate().cancel()
            pageContentContainer.alpha = 0f
            pageContentContainer.translationY = shiftPx
            populateBlock()
            pageScrollView.scrollTo(0, 0)
            pageContentContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .start()
        } else {
            populateBlock()
        }
    }

    // ==================== §04.2 Top AppBar for Primary Tabs (<= 4 icons) ====================

    private fun buildPrimaryTabAppBar(tokens: BrightCalmTheme.Tokens) {
        topAppBarContainer.removeAllViews()

        val barRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(8), dp(6))
            minimumHeight = dp(56)
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        if (currentTab == TAB_HOME) {
            // §04.2 & §05.1: 首页为 26/600 大标题「文件」+ 副标题「内部存储 · 已用 XX GB / XX GB」
            titleCol.addView(TextView(context).apply {
                text = "文件"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.foreground)
            })
            val m = cachedMetrics
            val subText = if (m != null) {
                "内部存储 · 已用 ${Formatter.formatFileSize(context, m.usedBytes)} / ${Formatter.formatFileSize(context, m.totalBytes)}"
            } else {
                "内部存储 · 正在统计空间用量…"
            }
            titleCol.addView(TextView(context).apply {
                text = subText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(tokens.mutedForeground)
                BrightCalmTheme.applyTabularNums(this)
            })
        } else {
            val pageTitle = when (currentTab) {
                TAB_RECENT -> "最近"
                TAB_CATEGORY -> "分类"
                TAB_SETTINGS -> "设置"
                else -> "文件"
            }
            titleCol.addView(TextView(context).apply {
                text = pageTitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.foreground)
            })
        }
        barRow.addView(titleCol)

        // Right icon group (fixed order: search · list-filter · more-vertical, <= 4 icons)
        barRow.addView(
            BrightCalmTheme.createIconButton(context, BrightCalmTheme.UiIcon.SEARCH, tokens.foreground, "搜索") {
                onRequestSearchOverlay?.invoke()
            }
        )
        if (currentTab != TAB_SETTINGS) {
            barRow.addView(
                BrightCalmTheme.createIconButton(context, BrightCalmTheme.UiIcon.LIST_FILTER, tokens.foreground, "排序与筛选") {
                    onRequestSortFilterSheet?.invoke()
                }
            )
        }
        barRow.addView(
            BrightCalmTheme.createIconButton(context, BrightCalmTheme.UiIcon.MORE_VERTICAL, tokens.foreground, "更多") {
                showTopOverflowMenu(it, tokens)
            }
        )

        topAppBarContainer.addView(barRow)
        // 1px bottom hairline border
        topAppBarContainer.addView(View(context).apply {
            setBackgroundColor(tokens.border)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
    }

    private fun showTopOverflowMenu(anchor: View, tokens: BrightCalmTheme.Tokens) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 1, 0, "刷新数据")
        popup.menu.add(0, 2, 1, "存储空间分析")
        popup.menu.add(0, 3, 2, if (tokens.isDark) "切换浅色模式" else "切换深色模式")
        popup.menu.add(0, 4, 3, "浏览内部存储根目录")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    EsToolboxActivity.invalidateCategoryCache()
                    refreshDashboardData()
                    Toast.makeText(context, "已刷新文件索引", Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> {
                    context.startActivity(Intent(context, DiskAnalysisActivity::class.java))
                    true
                }
                3 -> {
                    val prefs = context.getSharedPreferences(BrightCalmTheme.PREFS_NAME, Context.MODE_PRIVATE)
                    val nextMode = if (tokens.isDark) 1 else 2
                    prefs.edit().putInt(BrightCalmTheme.KEY_THEME_MODE, nextMode).apply()
                    BrightCalmTheme.applyNightMode(context)
                    refreshDashboardData()
                    onThemeOrPreferenceChanged?.invoke()
                    true
                }
                4 -> {
                    onNavigateToFolder?.invoke(Paths.get(Environment.getExternalStorageDirectory().absolutePath))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    // ==================== §05.1 Tab 1: 首页 (Home) ====================

    private fun buildHomeTabContent(tokens: BrightCalmTheme.Tokens) {
        val hasPerm = hasStoragePermission()
        val m = cachedMetrics

        // 1) 存储用量卡 (Card 1 of max 3 cards)
        val storageCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = BrightCalmTheme.cardBackground(tokens, dp(12).toFloat())
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(24)
            }
        }

        if (!hasPerm) {
            // §5.1 无权限降级态：「授权后可查看用量」+ [去授权]
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(context).apply {
                text = "授权后可查看存储空间用量与分类明细"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(tokens.foreground)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(context).apply {
                text = "去授权"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.primaryForeground)
                background = BrightCalmTheme.brandGradientBg(tokens, dp(10).toFloat())
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { openStoragePermissionSettings() }
            })
            storageCard.addView(row)
        } else {
            val headRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headRow.addView(TextView(context).apply {
                text = "存储用量"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.foreground)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val totalB = (m?.totalBytes ?: 1L).coerceAtLeast(1L)
            val usedB = (m?.usedBytes ?: 0L).coerceAtLeast(0L)
            val pct = ((usedB * 100L) / totalB).toInt().coerceIn(0, 100)
            headRow.addView(TextView(context).apply {
                text = "$pct% · 空间分析 →"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.primary)
                BrightCalmTheme.applyTabularNums(this)
                setOnClickListener {
                    context.startActivity(Intent(context, DiskAnalysisActivity::class.java))
                }
            })
            storageCard.addView(headRow)

            // 8dp high multi-color segmented progress bar (图片/视频/文档/其他, 2dp white gaps between segments)
            val barContainer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = BrightCalmTheme.roundedBg(tokens.muted, dp(5).toFloat())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(8)
                ).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(10)
                }
            }

            val imgWeight = (m?.imageBytes ?: 0L).toFloat().coerceAtLeast(totalB * 0.06f)
            val vidWeight = (m?.videoBytes ?: 0L).toFloat().coerceAtLeast(totalB * 0.05f)
            val docWeight = (m?.docBytes ?: 0L).toFloat().coerceAtLeast(totalB * 0.04f)
            val othWeight = (m?.otherBytes ?: 0L).toFloat().coerceAtLeast(totalB * 0.06f)
            val usedFraction = (usedB.toFloat() / totalB.toFloat()).coerceIn(0.12f, 0.96f)
            val freeWeight = ((imgWeight + vidWeight + docWeight + othWeight) * ((1f - usedFraction) / usedFraction))
                .coerceAtLeast(0.05f)

            fun addSegment(weight: Float, color: Int, hasRightGap: Boolean) {
                barContainer.addView(View(context).apply {
                    background = BrightCalmTheme.roundedBg(color, dp(3).toFloat())
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                        if (hasRightGap) rightMargin = dp(2)
                    }
                })
            }

            addSegment(imgWeight, tokens.typeColor(BrightCalmTheme.FileCategory.IMAGE), true)
            addSegment(vidWeight, tokens.typeColor(BrightCalmTheme.FileCategory.VIDEO), true)
            addSegment(docWeight, tokens.typeColor(BrightCalmTheme.FileCategory.DOC), true)
            addSegment(othWeight, tokens.typeColor(BrightCalmTheme.FileCategory.OTHER), false)
            if (freeWeight > 0f) {
                barContainer.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, freeWeight)
                })
            }
            storageCard.addView(barContainer)

            // Bottom labels + legend dots
            val labelRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            labelRow.addView(TextView(context).apply {
                text = "已用 ${Formatter.formatFileSize(context, usedB)}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(tokens.mutedForeground)
                BrightCalmTheme.applyTabularNums(this)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            labelRow.addView(TextView(context).apply {
                text = "共 ${Formatter.formatFileSize(context, totalB)}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(tokens.mutedForeground)
                BrightCalmTheme.applyTabularNums(this)
            })
            storageCard.addView(labelRow)
        }
        pageContentContainer.addView(storageCard)

        // 2) 快捷分类宫格 2×4 (每格自己的类型色 18% 浅底 + 40dp 图标方格 + 名称 + 数量)
        pageContentContainer.addView(buildGroupHeader(tokens, "快捷分类", null, null))

        data class HomeCatCell(
            val cat: BrightCalmTheme.FileCategory,
            val title: String,
            val count: Int,
            val onClick: () -> Unit
        )

        val counts = m?.countsByCat ?: emptyMap()
        val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dlCount = dlDir.listFiles()?.count { !it.name.startsWith(".") } ?: 0
        val favCount = BrightCalmTheme.getFavoriteFiles(context).size

        val gridCells = listOf(
            HomeCatCell(BrightCalmTheme.FileCategory.IMAGE, "图片", counts[EsToolboxActivity.CAT_IMAGE] ?: 0) {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_IMAGE))
            },
            HomeCatCell(BrightCalmTheme.FileCategory.VIDEO, "视频", counts[EsToolboxActivity.CAT_VIDEO] ?: 0) {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_VIDEO))
            },
            HomeCatCell(BrightCalmTheme.FileCategory.AUDIO, "音频", counts[EsToolboxActivity.CAT_AUDIO] ?: 0) {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_AUDIO))
            },
            HomeCatCell(BrightCalmTheme.FileCategory.DOC, "文档", counts[EsToolboxActivity.CAT_DOC] ?: 0) {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_DOC))
            },
            HomeCatCell(BrightCalmTheme.FileCategory.FOLDER, "下载", dlCount) {
                onNavigateToFolder?.invoke(Paths.get(dlDir.absolutePath))
            },
            HomeCatCell(BrightCalmTheme.FileCategory.ZIP, "压缩包", counts[EsToolboxActivity.CAT_ARCHIVE] ?: 0) {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_ARCHIVE))
            },
            HomeCatCell(BrightCalmTheme.FileCategory.APK, "安装包", counts[EsToolboxActivity.CAT_APK] ?: 0) {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_APK))
            },
            HomeCatCell(BrightCalmTheme.FileCategory.SLIDE, "收藏", favCount) {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_FAVORITE))
            }
        )

        for (rowIdx in 0 until 2) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = if (rowIdx == 0) dp(10) else dp(24)
                }
            }
            for (colIdx in 0 until 4) {
                val cellData = gridCells[rowIdx * 4 + colIdx]
                // Each cell uses its own type color 18% tint background (14% in dark mode), radius-lg 12dp, NO border, NO shadow (§5.1 & §10.6)
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    background = BrightCalmTheme.roundedBg(tokens.categoryCellBg(cellData.cat), dp(12).toFloat())
                    setPadding(dp(6), dp(12), dp(6), dp(12))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (colIdx < 3) rightMargin = dp(10)
                    }
                    setOnClickListener { cellData.onClick() }
                }

                val iconBox = BrightCalmTheme.createFileTypeIconBox(
                    context = context,
                    tokens = tokens,
                    category = cellData.cat,
                    boxSizeDp = 36,
                    cornerRadiusDp = 10
                )
                cell.addView(iconBox)

                cell.addView(TextView(context).apply {
                    text = cellData.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(tokens.foreground)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, dp(7), 0, dp(2))
                })

                cell.addView(TextView(context).apply {
                    text = String.format(Locale.getDefault(), "%,d", cellData.count)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(tokens.mutedForeground)
                    gravity = Gravity.CENTER
                    BrightCalmTheme.applyTabularNums(this)
                })

                row.addView(cell)
            }
            pageContentContainer.addView(row)
        }

        // 3) 最近文件 (3~5 compact dual-line rows + 「查看全部 →」, NO card wrapper!)
        pageContentContainer.addView(
            buildGroupHeader(tokens, "最近文件", "查看全部 →") {
                selectTab(TAB_RECENT, animate = true)
                onSwitchTabRequested?.invoke(TAB_RECENT)
            }
        )

        val recentPreview = m?.recentFiles?.take(4) ?: emptyList()
        if (recentPreview.isEmpty()) {
            pageContentContainer.addView(TextView(context).apply {
                text = "暂无最近修改的文件"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(tokens.mutedForeground)
                setPadding(0, dp(12), 0, dp(20))
            })
        } else {
            recentPreview.forEachIndexed { idx, file ->
                pageContentContainer.addView(
                    buildStandardFileRow(
                        tokens = tokens,
                        file = file,
                        showRelativeTimeOnRight = false,
                        isLastInSection = idx == recentPreview.lastIndex
                    )
                )
            }
        }

        // 24dp section spacing
        pageContentContainer.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24))
        })

        // 4) 常用位置 (内部存储 / 下载与扩展存储 / 云盘，带细进度条，NO card wrapper!)
        pageContentContainer.addView(buildGroupHeader(tokens, "常用位置", null, null))
        buildStorageLocationsRows(tokens, m).forEach { pageContentContainer.addView(it) }
    }

    // ==================== §05.2 Tab 2: 最近文件 (Recent) ====================

    private fun buildRecentTabContent(tokens: BrightCalmTheme.Tokens) {
        val m = cachedMetrics
        val allRecent = m?.recentFiles ?: emptyList()

        // 1) Horizontal Type Filter Chips (§5.2: 「全部」选中用 primary 12% 底；具体类型选中用该类型色 14% 浅底 + 同色文字)
        val chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(14)
            }
        }
        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val chipDefs = listOf(
            null to "全部",
            BrightCalmTheme.FileCategory.IMAGE to "图片",
            BrightCalmTheme.FileCategory.VIDEO to "视频",
            BrightCalmTheme.FileCategory.AUDIO to "音频",
            BrightCalmTheme.FileCategory.DOC to "文档",
            BrightCalmTheme.FileCategory.SHEET to "表格",
            BrightCalmTheme.FileCategory.ZIP to "压缩包",
            BrightCalmTheme.FileCategory.APK to "安装包"
        )

        chipDefs.forEach { (cat, label) ->
            val selected = recentFilterCategory == cat
            val fgColor = when {
                !selected -> tokens.mutedForeground
                cat == null -> tokens.primary
                else -> tokens.typeColor(cat)
            }
            val bgColor = if (selected) tokens.chipActiveBg(cat) else tokens.secondary
            val strokeColor = if (selected && cat == null) tokens.primary else 0
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
                    recentFilterCategory = cat
                    renderActiveTab(animate = false)
                }
            }
            chipRow.addView(chip)
        }
        chipScroll.addView(chipRow)
        pageContentContainer.addView(chipScroll)

        // 2) Optional Cleanup Suggestion Card (§5.2: 「发现 X MB 可清理的大文件」+ [查看] / [忽略]，左侧 warning 色小图标)
        val prefs = context.getSharedPreferences(BrightCalmTheme.PREFS_NAME, Context.MODE_PRIVATE)
        val dismissed = prefs.getBoolean(BrightCalmTheme.KEY_CLEANUP_DISMISSED, false)
        val largeBytes = m?.largeFilesBytes ?: 0L
        val largeCount = m?.largeFilesCount ?: 0
        if (!dismissed && largeCount > 0) {
            val suggestCard = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = BrightCalmTheme.cardBackground(tokens, dp(12).toFloat())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(16) }
            }
            // Warning icon box on left
            suggestCard.addView(
                BrightCalmTheme.createFileTypeIconBox(
                    context,
                    tokens,
                    BrightCalmTheme.FileCategory.FOLDER,
                    boxSizeDp = 36,
                    cornerRadiusDp = 10
                )
            )
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(12)
                }
            }
            textCol.addView(TextView(context).apply {
                text = "发现 ${Formatter.formatFileSize(context, largeBytes)} 可清理的大文件 ($largeCount 项)"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.foreground)
                BrightCalmTheme.applyTabularNums(this)
            })
            textCol.addView(TextView(context).apply {
                text = "及时清理超过阈值的安装包或视频可释放空间"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(tokens.mutedForeground)
                setPadding(0, dp(2), 0, 0)
            })
            suggestCard.addView(textCol)

            suggestCard.addView(TextView(context).apply {
                text = "查看"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.primary)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener {
                    context.startActivity(EsToolboxActivity.createModeIntent(context, EsToolboxActivity.MODE_LARGE_FILES))
                }
            })
            suggestCard.addView(TextView(context).apply {
                text = "忽略"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(tokens.mutedForeground)
                setPadding(dp(8), dp(8), dp(4), dp(8))
                setOnClickListener {
                    prefs.edit().putBoolean(BrightCalmTheme.KEY_CLEANUP_DISMISSED, true).apply()
                    renderActiveTab(animate = false)
                }
            })
            pageContentContainer.addView(suggestCard)
        }

        // Filter recent files by selected chip
        val filtered = allRecent.filter { f ->
            val cat = BrightCalmTheme.categoryForFile(f)
            recentFilterCategory == null ||
                cat == recentFilterCategory ||
                (recentFilterCategory == BrightCalmTheme.FileCategory.DOC && cat == BrightCalmTheme.FileCategory.PDF)
        }

        if (filtered.isEmpty()) {
            val catName = when (recentFilterCategory) {
                BrightCalmTheme.FileCategory.IMAGE -> "图片"
                BrightCalmTheme.FileCategory.VIDEO -> "视频"
                BrightCalmTheme.FileCategory.AUDIO -> "音频"
                BrightCalmTheme.FileCategory.DOC -> "文档"
                BrightCalmTheme.FileCategory.SHEET -> "表格"
                BrightCalmTheme.FileCategory.ZIP -> "压缩包"
                BrightCalmTheme.FileCategory.APK -> "安装包"
                else -> "最近文件"
            }
            pageContentContainer.addView(
                BrightCalmTheme.buildEmptyStateView(
                    context = context,
                    tokens = tokens,
                    type = BrightCalmTheme.EmptyStateType.CATEGORY_EMPTY,
                    titleText = "还没有$catName",
                    subtitleText = "拍张照片或从其他位置导入后会出现在这里",
                    actionLabel = "去浏览"
                ) {
                    onNavigateToFolder?.invoke(Paths.get(Environment.getExternalStorageDirectory().absolutePath))
                }
            )
            return
        }

        // 3) Time Grouping: 今天 / 昨天 / 本周 / 更早 (sticky-style 12sp/500, muted-foreground, bottom 1px border)
        val calNow = Calendar.getInstance()
        calNow.set(Calendar.HOUR_OF_DAY, 0)
        calNow.set(Calendar.MINUTE, 0)
        calNow.set(Calendar.SECOND, 0)
        calNow.set(Calendar.MILLISECOND, 0)
        val startOfToday = calNow.timeInMillis
        val startOfYesterday = startOfToday - 24L * 3600_000L
        val startOfWeek = startOfToday - 7L * 24L * 3600_000L

        val buckets = linkedMapOf(
            "今天" to mutableListOf<File>(),
            "昨天" to mutableListOf<File>(),
            "本周" to mutableListOf<File>(),
            "更早" to mutableListOf<File>()
        )
        filtered.forEach { f ->
            val ts = f.lastModified()
            when {
                ts >= startOfToday -> buckets["今天"]!!.add(f)
                ts >= startOfYesterday -> buckets["昨天"]!!.add(f)
                ts >= startOfWeek -> buckets["本周"]!!.add(f)
                else -> buckets["更早"]!!.add(f)
            }
        }

        buckets.forEach { (groupTitle, groupFiles) ->
            if (groupFiles.isNotEmpty()) {
                val header = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(12), 0, dp(4))
                }
                header.addView(TextView(context).apply {
                    text = groupTitle
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(tokens.mutedForeground)
                    setPadding(0, 0, 0, dp(6))
                })
                header.addView(View(context).apply {
                    setBackgroundColor(tokens.border)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                })
                pageContentContainer.addView(header)

                groupFiles.forEachIndexed { idx, file ->
                    pageContentContainer.addView(
                        buildStandardFileRow(
                            tokens = tokens,
                            file = file,
                            showRelativeTimeOnRight = true,
                            isLastInSection = idx == groupFiles.lastIndex
                        )
                    )
                }
            }
        }
    }

    // ==================== §05.3 Tab 3: 分类浏览 (Category) ====================

    private fun buildCategoryTabContent(tokens: BrightCalmTheme.Tokens) {
        val m = cachedMetrics
        val counts = m?.countsByCat ?: emptyMap()
        val sizes = m?.sizesByCat ?: emptyMap()
        val previews = m?.previewFilesByCat ?: emptyMap()

        val favFiles = BrightCalmTheme.getFavoriteFiles(context)
        val favSize = favFiles.sumOf { if (it.isFile) it.length() else 0L }

        data class CategoryCardDef(
            val cat: BrightCalmTheme.FileCategory,
            val title: String,
            val count: Int,
            val bytes: Long,
            val previewFiles: List<File>,
            val onClick: () -> Unit
        )

        val cards = listOf(
            CategoryCardDef(
                BrightCalmTheme.FileCategory.IMAGE,
                "图片",
                counts[EsToolboxActivity.CAT_IMAGE] ?: 0,
                sizes[EsToolboxActivity.CAT_IMAGE] ?: 0L,
                previews[EsToolboxActivity.CAT_IMAGE] ?: emptyList()
            ) {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_IMAGE))
            },
            CategoryCardDef(
                BrightCalmTheme.FileCategory.VIDEO,
                "视频",
                counts[EsToolboxActivity.CAT_VIDEO] ?: 0,
                sizes[EsToolboxActivity.CAT_VIDEO] ?: 0L,
                previews[EsToolboxActivity.CAT_VIDEO] ?: emptyList()
            ) {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_VIDEO))
            },
            CategoryCardDef(
                BrightCalmTheme.FileCategory.AUDIO,
                "音频",
                counts[EsToolboxActivity.CAT_AUDIO] ?: 0,
                sizes[EsToolboxActivity.CAT_AUDIO] ?: 0L,
                emptyList()
            ) {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_AUDIO))
            },
            CategoryCardDef(
                BrightCalmTheme.FileCategory.DOC,
                "文档",
                counts[EsToolboxActivity.CAT_DOC] ?: 0,
                sizes[EsToolboxActivity.CAT_DOC] ?: 0L,
                emptyList()
            ) {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_DOC))
            },
            CategoryCardDef(
                BrightCalmTheme.FileCategory.ZIP,
                "压缩包",
                counts[EsToolboxActivity.CAT_ARCHIVE] ?: 0,
                sizes[EsToolboxActivity.CAT_ARCHIVE] ?: 0L,
                emptyList()
            ) {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_ARCHIVE))
            },
            CategoryCardDef(
                BrightCalmTheme.FileCategory.APK,
                "安装包",
                counts[EsToolboxActivity.CAT_APK] ?: 0,
                sizes[EsToolboxActivity.CAT_APK] ?: 0L,
                emptyList()
            ) {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_APK))
            },
            CategoryCardDef(
                BrightCalmTheme.FileCategory.CODE,
                "应用管理 APK",
                counts["installed_apps"] ?: 0,
                0L,
                emptyList()
            ) {
                context.startActivity(EsToolboxActivity.createModeIntent(context, EsToolboxActivity.MODE_APP_MANAGER))
            },
            CategoryCardDef(
                BrightCalmTheme.FileCategory.SLIDE,
                "收藏夹",
                favFiles.size,
                favSize,
                emptyList()
            ) {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_FAVORITE))
            }
        )

        // 1) 类型宫格 (2 columns, gap 12dp, each cell uses its own type color 18% tint, no border, no shadow)
        for (r in 0 until 4) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (r == 0) dp(6) else 0
                    bottomMargin = dp(12)
                }
            }
            for (c in 0 until 2) {
                val item = cards[r * 2 + c]
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = BrightCalmTheme.roundedBg(tokens.categoryCellBg(item.cat), dp(12).toFloat())
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (c == 0) rightMargin = dp(12)
                    }
                    setOnClickListener { item.onClick() }
                }

                // Top Row: 40x40 type icon box + optional 3-thumbnail collage (rounded 8dp)
                val topRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                }
                topRow.addView(
                    BrightCalmTheme.createFileTypeIconBox(context, tokens, item.cat, boxSizeDp = 40, cornerRadiusDp = 12)
                )
                topRow.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })

                if (item.previewFiles.isNotEmpty() && item.cat == BrightCalmTheme.FileCategory.IMAGE) {
                    val collageRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    item.previewFiles.take(3).forEach { imgFile ->
                        val thumbIv = ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            background = BrightCalmTheme.roundedBg(tokens.card, dp(8).toFloat())
                            clipToOutline = true
                            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                                leftMargin = dp(4)
                            }
                        }
                        collageRow.addView(thumbIv)
                        scope.launch {
                            val bmp = withContext(Dispatchers.IO) {
                                runCatching {
                                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                                    BitmapFactory.decodeFile(imgFile.absolutePath, opts)
                                }.getOrNull()
                            }
                            if (bmp != null) thumbIv.setImageBitmap(bmp)
                        }
                    }
                    topRow.addView(collageRow)
                }
                cell.addView(topRow)

                cell.addView(TextView(context).apply {
                    text = item.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(tokens.foreground)
                })

                val metaStr = if (item.bytes > 0L) {
                    "${String.format(Locale.getDefault(), "%,d", item.count)} 项 · ${Formatter.formatFileSize(context, item.bytes)}"
                } else {
                    "${String.format(Locale.getDefault(), "%,d", item.count)} 项"
                }
                cell.addView(TextView(context).apply {
                    text = metaStr
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(tokens.mutedForeground)
                    BrightCalmTheme.applyTabularNums(this)
                    setPadding(0, dp(2), 0, 0)
                })

                row.addView(cell)
            }
            pageContentContainer.addView(row)
        }

        // §5.3: 分区之间只用 24dp 留白 + 1px 分隔线，不加卡片背景
        addSectionSpacerAndDivider(tokens)

        // 2) 存储位置区 (内部存储 / SD 卡 / 云盘账户，每行：图标 + 名称 + 已用/总量细进度条 + chevron-right，进度条用 primary 渐变)
        pageContentContainer.addView(buildGroupHeader(tokens, "存储位置", null, null))
        buildStorageLocationsRows(tokens, m).forEach { pageContentContainer.addView(it) }

        addSectionSpacerAndDivider(tokens)

        // 3) 工具区：大文件（>100MB）、重复文件、回收站、下载目录、隐藏文件夹，用行而非卡片
        pageContentContainer.addView(buildGroupHeader(tokens, "清理与工具", null, null))

        val prefs = context.getSharedPreferences(BrightCalmTheme.PREFS_NAME, Context.MODE_PRIVATE)
        val thresholdMb = prefs.getInt(BrightCalmTheme.KEY_LARGE_FILE_THRESHOLD_MB, 100)
        val recycleDir = File(Environment.getExternalStorageDirectory(), ".es_recycle_bin")
        val recycleCount = recycleDir.listFiles()?.size ?: 0
        val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        data class ToolRowDef(
            val cat: BrightCalmTheme.FileCategory,
            val title: String,
            val subtitle: String,
            val onClick: () -> Unit
        )

        val tools = listOf(
            ToolRowDef(
                BrightCalmTheme.FileCategory.VIDEO,
                "大文件 (>${thresholdMb} MB)",
                if ((m?.largeFilesCount ?: 0) > 0) "${m?.largeFilesCount} 项 · 共 ${Formatter.formatFileSize(context, m?.largeFilesBytes ?: 0L)}" else "扫描高占用大体积文件"
            ) {
                context.startActivity(EsToolboxActivity.createModeIntent(context, EsToolboxActivity.MODE_LARGE_FILES))
            },
            ToolRowDef(
                BrightCalmTheme.FileCategory.DOC,
                "重复文件",
                "按文件大小与内容指纹查找重复副本"
            ) {
                context.startActivity(EsToolboxActivity.createModeIntent(context, EsToolboxActivity.MODE_DUPLICATE_FILES))
            },
            ToolRowDef(
                BrightCalmTheme.FileCategory.OTHER,
                "回收站",
                if (recycleCount > 0) "$recycleCount 项待清理 · 保留 30 天" else "当前为空 · 删除文件在此保留 30 天"
            ) {
                context.startActivity(EsToolboxActivity.createModeIntent(context, EsToolboxActivity.MODE_RECYCLE_BIN))
            },
            ToolRowDef(
                BrightCalmTheme.FileCategory.FOLDER,
                "下载目录",
                dlDir.absolutePath
            ) {
                onNavigateToFolder?.invoke(Paths.get(dlDir.absolutePath))
            },
            ToolRowDef(
                BrightCalmTheme.FileCategory.ZIP,
                "隐藏文件夹与加密保险箱",
                "管理 .开头隐藏目录与 AES 私密文件"
            ) {
                context.startActivity(EsToolboxActivity.createModeIntent(context, EsToolboxActivity.MODE_VAULT))
            }
        )

        tools.forEachIndexed { idx, tool ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { tool.onClick() }
            }
            row.addView(BrightCalmTheme.createFileTypeIconBox(context, tokens, tool.cat, 40, 12))

            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(12)
                }
            }
            col.addView(TextView(context).apply {
                text = tool.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.foreground)
            })
            col.addView(TextView(context).apply {
                text = tool.subtitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(tokens.mutedForeground)
                BrightCalmTheme.applyTabularNums(this)
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(col)
            row.addView(BrightCalmTheme.createUiIconView(context, BrightCalmTheme.UiIcon.CHEVRON_RIGHT, tokens.mutedForeground, 20))
            pageContentContainer.addView(row)

            if (idx < tools.lastIndex) {
                pageContentContainer.addView(buildIndentedDivider(tokens))
            }
        }
    }

    // ==================== §05.7 & §09 Tab 4: 设置 (Settings) ====================

    private fun buildSettingsTabContent(tokens: BrightCalmTheme.Tokens) {
        val prefs = context.getSharedPreferences(BrightCalmTheme.PREFS_NAME, Context.MODE_PRIVATE)

        // Top Brand Info Small Card (The ONLY card on the Settings page — §05.7)
        val brandCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = BrightCalmTheme.cardBackground(tokens, dp(12).toFloat())
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(20)
            }
        }
        brandCard.addView(BrightCalmTheme.createBrandAppIconView(context, tokens, sizeDp = 52))
        val brandCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(14)
            }
        }
        brandCol.addView(TextView(context).apply {
            text = "晴彩文件浏览器"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(tokens.foreground)
        })
        brandCol.addView(TextView(context).apply {
            text = "v1.1「晴彩 Bright Calm」· shadcn/ui + transitions.dev"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(tokens.mutedForeground)
            BrightCalmTheme.applyTabularNums(this)
            setPadding(0, dp(3), 0, 0)
        })
        brandCard.addView(brandCol)
        pageContentContainer.addView(brandCard)

        // Helper for Group Title with 8dp type-color dot on left (§05.7: 每分组一色，不重复)
        fun addSettingsGroupHeader(title: String, dotCategory: BrightCalmTheme.FileCategory, isFirst: Boolean = false) {
            if (!isFirst) {
                addSectionSpacerAndDivider(tokens)
            }
            val headRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(8))
            }
            headRow.addView(View(context).apply {
                background = BrightCalmTheme.roundedBg(tokens.typeColor(dotCategory), dp(4).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    rightMargin = dp(8)
                }
            })
            headRow.addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.foreground)
            })
            pageContentContainer.addView(headRow)
        }

        fun addValueRow(
            icon: BrightCalmTheme.UiIcon,
            title: String,
            valueText: String,
            isDestructive: Boolean = false,
            onClick: () -> Unit
        ) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { onClick() }
            }
            row.addView(
                BrightCalmTheme.createUiIconView(
                    context,
                    icon,
                    if (isDestructive) tokens.destructive else tokens.mutedForeground,
                    20
                )
            )
            row.addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (isDestructive) tokens.destructive else tokens.foreground)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(12)
                }
            })
            if (valueText.isNotEmpty()) {
                row.addView(TextView(context).apply {
                    text = valueText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(tokens.mutedForeground)
                    BrightCalmTheme.applyTabularNums(this)
                    setPadding(0, 0, dp(6), 0)
                })
            }
            row.addView(
                BrightCalmTheme.createUiIconView(
                    context,
                    BrightCalmTheme.UiIcon.CHEVRON_RIGHT,
                    tokens.mutedForeground,
                    18
                )
            )
            pageContentContainer.addView(row)
        }

        fun addSwitchRow(
            icon: BrightCalmTheme.UiIcon,
            title: String,
            checked: Boolean,
            onChanged: (Boolean) -> Unit
        ) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(BrightCalmTheme.createUiIconView(context, icon, tokens.mutedForeground, 20))
            row.addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(tokens.foreground)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(12)
                }
            })
            val sw = Switch(context).apply {
                isChecked = checked
                setOnCheckedChangeListener { buttonView, isChecked ->
                    // 350ms double-bounce overshoot animation (§09 & §12.2)
                    buttonView.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).withEndAction {
                        buttonView.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                    }.start()
                    onChanged(isChecked)
                }
            }
            row.addView(sw)
            row.setOnClickListener { sw.isChecked = !sw.isChecked }
            pageContentContainer.addView(row)
        }

        // ==================== Group 1: 外观与显示 ====================
        addSettingsGroupHeader("外观与显示", BrightCalmTheme.FileCategory.AUDIO, isFirst = true)

        val themeMode = prefs.getInt(BrightCalmTheme.KEY_THEME_MODE, 0)
        val themeLabels = arrayOf("跟随系统", "浅色", "深色")
        addValueRow(BrightCalmTheme.UiIcon.MOON, "主题", themeLabels[themeMode.coerceIn(0, 2)]) {
            showSingleChoiceDialog("主题模式", themeLabels, themeMode) { idx ->
                prefs.edit().putInt(BrightCalmTheme.KEY_THEME_MODE, idx).apply()
                BrightCalmTheme.applyNightMode(context)
                renderActiveTab(animate = false)
                onThemeOrPreferenceChanged?.invoke()
            }
        }

        // 强调色（晴彩紫 / 晴空蓝 / 蜜柚橙）单选色点 (§09)
        val accentIdx = prefs.getInt(BrightCalmTheme.KEY_ACCENT_COLOR, 0)
        val accentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(6), 0, dp(6))
        }
        accentRow.addView(BrightCalmTheme.createUiIconView(context, BrightCalmTheme.UiIcon.PALETTE, tokens.mutedForeground, 20))
        accentRow.addView(TextView(context).apply {
            text = "强调色"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(tokens.foreground)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
            }
        })
        val dotsData = listOf(
            Triple(0, "晴彩紫", "#6C5CE7"),
            Triple(1, "晴空蓝", "#3B9BFF"),
            Triple(2, "蜜柚橙", "#FF7A59")
        )
        dotsData.forEach { (idx, name, hex) ->
            val selected = accentIdx == idx
            val dotChip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = BrightCalmTheme.roundedBg(
                    if (selected) BrightCalmTheme.withAlpha(android.graphics.Color.parseColor(hex), 0.16f) else tokens.secondary,
                    dp(14).toFloat(),
                    if (selected) android.graphics.Color.parseColor(hex) else 0,
                    if (selected) 2 else 0
                )
                setPadding(dp(8), dp(5), dp(10), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(6) }
                setOnClickListener {
                    prefs.edit().putInt(BrightCalmTheme.KEY_ACCENT_COLOR, idx).apply()
                    renderActiveTab(animate = false)
                    onThemeOrPreferenceChanged?.invoke()
                }
            }
            dotChip.addView(View(context).apply {
                background = BrightCalmTheme.roundedBg(android.graphics.Color.parseColor(hex), dp(6).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(4) }
            })
            dotChip.addView(TextView(context).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(tokens.foreground)
            })
            accentRow.addView(dotChip)
        }
        pageContentContainer.addView(accentRow)

        val intensityIdx = prefs.getInt(BrightCalmTheme.KEY_COLOR_INTENSITY, 0)
        val intensityLabels = arrayOf("标准", "柔和", "关闭彩色")
        addValueRow(BrightCalmTheme.UiIcon.PALETTE, "彩色强度", intensityLabels[intensityIdx.coerceIn(0, 2)]) {
            showSingleChoiceDialog("彩色强度", intensityLabels, intensityIdx) { idx ->
                prefs.edit().putInt(BrightCalmTheme.KEY_COLOR_INTENSITY, idx).apply()
                renderActiveTab(animate = false)
                onThemeOrPreferenceChanged?.invoke()
            }
        }

        val defView = prefs.getString(BrightCalmTheme.KEY_DEFAULT_VIEW, "list") ?: "list"
        addValueRow(BrightCalmTheme.UiIcon.GRID, "默认视图", if (defView == "grid") "网格" else "列表") {
            val opts = arrayOf("列表", "网格")
            showSingleChoiceDialog("默认视图", opts, if (defView == "grid") 1 else 0) { idx ->
                val v = if (idx == 1) "grid" else "list"
                prefs.edit().putString(BrightCalmTheme.KEY_DEFAULT_VIEW, v).apply()
                Settings.FILE_LIST_VIEW_TYPE.putValue(if (idx == 1) FileViewType.GRID else FileViewType.LIST)
                renderActiveTab(animate = false)
                onThemeOrPreferenceChanged?.invoke()
            }
        }

        val defSort = prefs.getString(BrightCalmTheme.KEY_DEFAULT_SORT, "time_desc") ?: "time_desc"
        val sortMap = listOf(
            "time_desc" to "修改时间 ↓",
            "name_asc" to "名称 ↑",
            "size_desc" to "大小 ↓",
            "type_asc" to "类型 ↑"
        )
        val curSortLabel = sortMap.firstOrNull { it.first == defSort }?.second ?: "修改时间 ↓"
        addValueRow(BrightCalmTheme.UiIcon.LIST_FILTER, "默认排序", curSortLabel) {
            val labels = sortMap.map { it.second }.toTypedArray()
            val curIdx = sortMap.indexOfFirst { it.first == defSort }.coerceAtLeast(0)
            showSingleChoiceDialog("默认排序", labels, curIdx) { idx ->
                prefs.edit().putString(BrightCalmTheme.KEY_DEFAULT_SORT, sortMap[idx].first).apply()
                renderActiveTab(animate = false)
            }
        }

        addSwitchRow(
            BrightCalmTheme.UiIcon.LIST,
            "显示隐藏文件",
            Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        ) {
            Settings.FILE_LIST_SHOW_HIDDEN_FILES.putValue(it)
            onThemeOrPreferenceChanged?.invoke()
        }

        addSwitchRow(
            BrightCalmTheme.UiIcon.LIST,
            "显示文件扩展名",
            prefs.getBoolean(BrightCalmTheme.KEY_SHOW_EXTENSIONS, true)
        ) {
            prefs.edit().putBoolean(BrightCalmTheme.KEY_SHOW_EXTENSIONS, it).apply()
            onThemeOrPreferenceChanged?.invoke()
        }

        val thumbMode = prefs.getString(BrightCalmTheme.KEY_THUMBNAIL_LOAD, "wifi") ?: "wifi"
        val thumbLabels = arrayOf("始终", "仅 WiFi", "从不")
        val thumbValues = arrayOf("always", "wifi", "never")
        val curThumbIdx = thumbValues.indexOf(thumbMode).coerceAtLeast(0)
        addValueRow(BrightCalmTheme.UiIcon.GRID, "缩略图加载", thumbLabels[curThumbIdx]) {
            showSingleChoiceDialog("缩略图加载", thumbLabels, curThumbIdx) { idx ->
                prefs.edit().putString(BrightCalmTheme.KEY_THUMBNAIL_LOAD, thumbValues[idx]).apply()
                renderActiveTab(animate = false)
            }
        }

        addSwitchRow(
            BrightCalmTheme.UiIcon.LIST,
            "紧凑列表模式 (48dp 行高)",
            prefs.getBoolean(BrightCalmTheme.KEY_COMPACT_LIST, false)
        ) {
            prefs.edit().putBoolean(BrightCalmTheme.KEY_COMPACT_LIST, it).apply()
            onThemeOrPreferenceChanged?.invoke()
        }

        // ==================== Group 2: 文件与浏览 ====================
        addSettingsGroupHeader("文件与浏览", BrightCalmTheme.FileCategory.FOLDER)

        val defOpenDir = prefs.getString(BrightCalmTheme.KEY_DEFAULT_OPEN_DIR, "内部存储") ?: "内部存储"
        addValueRow(BrightCalmTheme.UiIcon.HARD_DRIVE, "默认打开位置", defOpenDir) {
            val opts = arrayOf("内部存储", "Download 下载目录", "Documents 文档目录")
            val cur = opts.indexOf(defOpenDir).coerceAtLeast(0)
            showSingleChoiceDialog("默认打开位置", opts, cur) { idx ->
                prefs.edit().putString(BrightCalmTheme.KEY_DEFAULT_OPEN_DIR, opts[idx]).apply()
                renderActiveTab(animate = false)
            }
        }

        addSwitchRow(
            BrightCalmTheme.UiIcon.LIST_FILTER,
            "记住每个目录的排序与视图",
            prefs.getBoolean(BrightCalmTheme.KEY_REMEMBER_DIR_PREF, true)
        ) {
            prefs.edit().putBoolean(BrightCalmTheme.KEY_REMEMBER_DIR_PREF, it).apply()
        }

        addSwitchRow(
            BrightCalmTheme.UiIcon.TRASH,
            "删除前移至回收站",
            prefs.getBoolean(BrightCalmTheme.KEY_MOVE_TO_RECYCLE_BIN, true)
        ) {
            prefs.edit().putBoolean(BrightCalmTheme.KEY_MOVE_TO_RECYCLE_BIN, it).apply()
        }

        addSwitchRow(
            BrightCalmTheme.UiIcon.SHIELD,
            "删除操作二次确认",
            prefs.getBoolean(BrightCalmTheme.KEY_CONFIRM_DELETE, true)
        ) {
            prefs.edit().putBoolean(BrightCalmTheme.KEY_CONFIRM_DELETE, it).apply()
        }

        val autoCleanDays = prefs.getInt(BrightCalmTheme.KEY_RECYCLE_AUTO_CLEAN, 30)
        val cleanOpts = listOf(7 to "7 天", 30 to "30 天", 90 to "90 天", 0 to "从不")
        val curCleanLabel = cleanOpts.firstOrNull { it.first == autoCleanDays }?.second ?: "30 天"
        addValueRow(BrightCalmTheme.UiIcon.CLOCK, "回收站自动清理", curCleanLabel) {
            val labels = cleanOpts.map { it.second }.toTypedArray()
            val curIdx = cleanOpts.indexOfFirst { it.first == autoCleanDays }.coerceAtLeast(0)
            showSingleChoiceDialog("回收站自动清理周期", labels, curIdx) { idx ->
                prefs.edit().putInt(BrightCalmTheme.KEY_RECYCLE_AUTO_CLEAN, cleanOpts[idx].first).apply()
                renderActiveTab(animate = false)
            }
        }

        addSwitchRow(
            BrightCalmTheme.UiIcon.COMPRESS,
            "压缩包内预览",
            prefs.getBoolean(BrightCalmTheme.KEY_ARCHIVE_INNER_PREVIEW, true)
        ) {
            prefs.edit().putBoolean(BrightCalmTheme.KEY_ARCHIVE_INNER_PREVIEW, it).apply()
        }

        val conflictPol = prefs.getString(BrightCalmTheme.KEY_CONFLICT_POLICY, "询问") ?: "询问"
        addValueRow(BrightCalmTheme.UiIcon.COPY, "同名文件处理", conflictPol) {
            val opts = arrayOf("询问", "自动重命名", "覆盖")
            val cur = opts.indexOf(conflictPol).coerceAtLeast(0)
            showSingleChoiceDialog("同名文件处理方式", opts, cur) { idx ->
                prefs.edit().putString(BrightCalmTheme.KEY_CONFLICT_POLICY, opts[idx]).apply()
                renderActiveTab(animate = false)
            }
        }

        // ==================== Group 3: 存储与清理 ====================
        addSettingsGroupHeader("存储与清理", BrightCalmTheme.FileCategory.SHEET)

        addValueRow(BrightCalmTheme.UiIcon.DATABASE, "存储分析", "进入可视化分析") {
            context.startActivity(Intent(context, DiskAnalysisActivity::class.java))
        }

        val cacheBytes = context.cacheDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        addValueRow(
            BrightCalmTheme.UiIcon.REFRESH,
            "应用缓存占用",
            "${Formatter.formatFileSize(context, cacheBytes)} · 点击清理"
        ) {
            context.cacheDir?.deleteRecursively()
            EsToolboxActivity.invalidateCategoryCache()
            Toast.makeText(context, "已清理应用缓存", Toast.LENGTH_SHORT).show()
            renderActiveTab(animate = false)
        }

        val thresholdMb = prefs.getInt(BrightCalmTheme.KEY_LARGE_FILE_THRESHOLD_MB, 100)
        addValueRow(BrightCalmTheme.UiIcon.LIST_FILTER, "大文件扫描阈值", "$thresholdMb MB") {
            val opts = arrayOf("50 MB", "100 MB", "200 MB", "500 MB")
            val vals = arrayOf(50, 100, 200, 500)
            val cur = vals.indexOf(thresholdMb).coerceAtLeast(0)
            showSingleChoiceDialog("大文件扫描阈值", opts, cur) { idx ->
                prefs.edit().putInt(BrightCalmTheme.KEY_LARGE_FILE_THRESHOLD_MB, vals[idx]).apply()
                refreshDashboardData()
            }
        }

        addValueRow(BrightCalmTheme.UiIcon.COPY, "重复文件检测", "立即扫描") {
            context.startActivity(EsToolboxActivity.createModeIntent(context, EsToolboxActivity.MODE_DUPLICATE_FILES))
        }

        addValueRow(BrightCalmTheme.UiIcon.HARD_DRIVE, "下载目录位置", "/Download") {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            onNavigateToFolder?.invoke(Paths.get(dl.absolutePath))
        }

        addValueRow(BrightCalmTheme.UiIcon.CLOUD, "云盘账户管理", "Google Drive / OneDrive") {
            context.startActivity(Intent(context, CloudDriveActivity::class.java))
        }

        // ==================== Group 4: 安全与隐私 ====================
        addSettingsGroupHeader("安全与隐私", BrightCalmTheme.FileCategory.IMAGE)

        val appLock = prefs.getString(BrightCalmTheme.KEY_APP_LOCK, "关") ?: "关"
        addValueRow(BrightCalmTheme.UiIcon.SHIELD, "应用锁", appLock) {
            val opts = arrayOf("关", "生物识别", "PIN 密码")
            val cur = opts.indexOf(appLock).coerceAtLeast(0)
            showSingleChoiceDialog("应用锁保护", opts, cur) { idx ->
                prefs.edit().putString(BrightCalmTheme.KEY_APP_LOCK, opts[idx]).apply()
                renderActiveTab(animate = false)
            }
        }

        addValueRow(BrightCalmTheme.UiIcon.SHIELD, "隐藏文件夹与保险箱管理", "AES-256 加密区") {
            context.startActivity(EsToolboxActivity.createModeIntent(context, EsToolboxActivity.MODE_VAULT))
        }

        addValueRow(
            BrightCalmTheme.UiIcon.TRASH,
            "清除最近记录与搜索历史",
            "",
            isDestructive = true
        ) {
            AlertDialog.Builder(context)
                .setTitle("清除记录与搜索历史")
                .setMessage("确定要清除所有搜索历史与最近浏览缓存记录吗？")
                .setPositiveButton("确认清除") { _, _ ->
                    BrightCalmTheme.clearSearchHistory(context)
                    prefs.edit().putBoolean(BrightCalmTheme.KEY_CLEANUP_DISMISSED, false).apply()
                    Toast.makeText(context, "已清除最近记录与搜索历史", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        addValueRow(BrightCalmTheme.UiIcon.SETTINGS, "权限管理", if (hasStoragePermission()) "已授权" else "去授权") {
            openStoragePermissionSettings()
        }

        addSwitchRow(
            BrightCalmTheme.UiIcon.SHIELD,
            "安全删除（零字节覆写）",
            prefs.getBoolean(BrightCalmTheme.KEY_SECURE_DELETE, false)
        ) {
            prefs.edit().putBoolean(BrightCalmTheme.KEY_SECURE_DELETE, it).apply()
        }

        // ==================== Group 5: 通知与同步 ====================
        addSettingsGroupHeader("通知与同步", BrightCalmTheme.FileCategory.DOC)

        addSwitchRow(
            BrightCalmTheme.UiIcon.BELL,
            "下载完成通知",
            prefs.getBoolean(BrightCalmTheme.KEY_NOTIFY_DOWNLOAD, true)
        ) {
            prefs.edit().putBoolean(BrightCalmTheme.KEY_NOTIFY_DOWNLOAD, it).apply()
        }

        addSwitchRow(
            BrightCalmTheme.UiIcon.BELL,
            "存储空间不足提醒",
            prefs.getBoolean(BrightCalmTheme.KEY_NOTIFY_STORAGE, true)
        ) {
            prefs.edit().putBoolean(BrightCalmTheme.KEY_NOTIFY_STORAGE, it).apply()
        }

        val autoBackup = prefs.getBoolean(BrightCalmTheme.KEY_AUTO_BACKUP, false)
        addValueRow(BrightCalmTheme.UiIcon.CLOUD, "自动备份", if (autoBackup) "已开启 · 每日备份至 /backups" else "关") {
            prefs.edit().putBoolean(BrightCalmTheme.KEY_AUTO_BACKUP, !autoBackup).apply()
            renderActiveTab(animate = false)
        }

        // ==================== Group 6: 关于与帮助 ====================
        addSettingsGroupHeader("关于与帮助", BrightCalmTheme.FileCategory.OTHER)

        addValueRow(BrightCalmTheme.UiIcon.INFO, "版本号 / 更新日志", "v1.1 晴彩 Bright Calm") {
            AlertDialog.Builder(context)
                .setTitle("v1.1「晴彩 Bright Calm」设计规范更新")
                .setMessage(
                    "• 唯一主色亮紫罗兰 #6C5CE7 + 渐变搭档 #9B8BFF\n" +
                        "• 8 组同饱和度带文件类型色（16% 浅底图标方格 + 18% 浅底分类宫格）\n" +
                        "• 底部固定 4 Tab 导航（首页 / 最近 / 分类 / 设置）\n" +
                        "• 90% 高度文件详情面板、全局搜索覆盖层、条件筛选面板\n" +
                        "• 支持亮暗双模式、强调色切换与彩色强度三级调节"
                )
                .setPositiveButton("确定", null)
                .show()
        }

        addValueRow(BrightCalmTheme.UiIcon.HELP, "帮助与反馈", "手势与操作说明") {
            AlertDialog.Builder(context)
                .setTitle("快捷手势与交互指南")
                .setMessage(
                    "• 短按行：打开文件或进入文件夹\n" +
                        "• 长按行：进入多选管理模式\n" +
                        "• 点按行右侧 ⋮：打开锚定菜单与文件详情\n" +
                        "• 面包屑路径：点击跨级回跳，长按复制完整路径\n" +
                        "• 双击底部当前 Tab：平滑回到顶部"
                )
                .setPositiveButton("知道了", null)
                .show()
        }

        addValueRow(BrightCalmTheme.UiIcon.INFO, "隐私政策 / 开源许可", "") {
            context.startActivity(Intent(context, AboutActivity::class.java))
        }

        addValueRow(
            BrightCalmTheme.UiIcon.REFRESH,
            "重置为默认设置",
            "",
            isDestructive = true
        ) {
            AlertDialog.Builder(context)
                .setTitle("重置为默认设置")
                .setMessage("确定要将所有外观、彩色强度、排序与浏览偏好恢复为默认值吗？")
                .setPositiveButton("确认重置") { _, _ ->
                    prefs.edit().clear().apply()
                    BrightCalmTheme.applyNightMode(context)
                    renderActiveTab(animate = false)
                    onThemeOrPreferenceChanged?.invoke()
                    Toast.makeText(context, "已恢复默认设置", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ==================== Shared Row & Section Builders ====================

    private fun buildGroupHeader(
        tokens: BrightCalmTheme.Tokens,
        title: String,
        actionText: String?,
        onAction: (() -> Unit)?
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(10))
            addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.foreground)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!actionText.isNullOrEmpty() && onAction != null) {
                addView(TextView(context).apply {
                    text = actionText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(tokens.primary)
                    setPadding(dp(8), dp(4), dp(4), dp(4))
                    setOnClickListener { onAction() }
                })
            }
        }
    }

    private fun buildStorageLocationsRows(
        tokens: BrightCalmTheme.Tokens,
        m: StorageBreakdown?
    ): List<View> {
        val views = mutableListOf<View>()
        val rootDir = Environment.getExternalStorageDirectory()
        val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val totalB = (m?.totalBytes ?: 1L).coerceAtLeast(1L)
        val usedB = (m?.usedBytes ?: 0L).coerceAtLeast(0L)
        val usedPct = ((usedB * 100L) / totalB).toInt().coerceIn(5, 100)

        data class LocItem(
            val cat: BrightCalmTheme.FileCategory,
            val title: String,
            val subtitle: String,
            val progressPct: Int,
            val onClick: () -> Unit
        )

        val locs = listOf(
            LocItem(
                BrightCalmTheme.FileCategory.DOC,
                "内部存储",
                "已用 ${Formatter.formatFileSize(context, usedB)} / ${Formatter.formatFileSize(context, totalB)}",
                usedPct
            ) {
                onNavigateToFolder?.invoke(Paths.get(rootDir.absolutePath))
            },
            LocItem(
                BrightCalmTheme.FileCategory.FOLDER,
                "下载与常用目录 (/Download)",
                "快速访问浏览器与社交软件下载文件",
                (usedPct * 2 / 3).coerceIn(8, 95)
            ) {
                onNavigateToFolder?.invoke(Paths.get(dlDir.absolutePath))
            },
            LocItem(
                BrightCalmTheme.FileCategory.AUDIO,
                "云盘账户 (Google Drive / OneDrive)",
                "支持多账号登录浏览与高速下载",
                25
            ) {
                context.startActivity(Intent(context, CloudDriveActivity::class.java))
            }
        )

        locs.forEachIndexed { idx, loc ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { loc.onClick() }
            }
            row.addView(BrightCalmTheme.createFileTypeIconBox(context, tokens, loc.cat, 40, 12))

            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(12)
                    rightMargin = dp(12)
                }
            }
            col.addView(TextView(context).apply {
                text = loc.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.foreground)
            })
            col.addView(TextView(context).apply {
                text = loc.subtitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(tokens.mutedForeground)
                BrightCalmTheme.applyTabularNums(this)
                setPadding(0, dp(2), 0, dp(6))
            })

            // Thin primary -> primary-2 gradient progress bar (§5.3)
            val barTrack = FrameLayout(context).apply {
                background = BrightCalmTheme.roundedBg(tokens.muted, dp(2).toFloat())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(4)
                )
            }
            val barFill = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            barFill.addView(View(context).apply {
                background = BrightCalmTheme.brandGradientBg(tokens, dp(2).toFloat())
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    loc.progressPct.toFloat()
                )
            })
            barFill.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (100 - loc.progressPct).coerceAtLeast(0).toFloat()
                )
            })
            barTrack.addView(barFill)
            col.addView(barTrack)

            row.addView(col)
            row.addView(BrightCalmTheme.createUiIconView(context, BrightCalmTheme.UiIcon.CHEVRON_RIGHT, tokens.mutedForeground, 20))
            views.add(row)

            if (idx < locs.lastIndex) {
                views.add(buildIndentedDivider(tokens))
            }
        }
        return views
    }

    /**
     * Builds the standard §5.4 Dual-Line Row (height 64dp or 48dp compact):
     * Left: 40x40 type icon box (16% tint + 1.75px linear glyph),
     * Middle: Line 1 filename 16/600 (1-line ellipsize preserving extension), Line 2 size · date (12sp tabular-nums),
     * Right: Star (if favorited) + more-vertical 44x44dp hot zone,
     * Bottom: 1px border indented 56dp from left.
     */
    private fun buildStandardFileRow(
        tokens: BrightCalmTheme.Tokens,
        file: File,
        showRelativeTimeOnRight: Boolean,
        isLastInSection: Boolean
    ): View {
        val prefs = context.getSharedPreferences(BrightCalmTheme.PREFS_NAME, Context.MODE_PRIVATE)
        val isCompact = prefs.getBoolean(BrightCalmTheme.KEY_COMPACT_LIST, false)
        val rowHeightDp = if (isCompact) 48 else 64

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(rowHeightDp)
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { openRealFile(file) }
            setOnLongClickListener {
                showFileRowDetailSheet(file)
                true
            }
        }

        val cat = BrightCalmTheme.categoryForFile(file)
        row.addView(BrightCalmTheme.createFileTypeIconBox(context, tokens, cat, 40, 12))

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
                rightMargin = dp(8)
            }
        }
        val displayName = BrightCalmTheme.formatDisplayFileName(context, file.name, file.isDirectory)
        textCol.addView(TextView(context).apply {
            text = displayName
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isCompact) 14f else 16f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(tokens.foreground)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })

        val relTime = formatChineseRelativeTime(file.lastModified())
        val sizeStr = Formatter.formatFileSize(context, file.length())
        textCol.addView(TextView(context).apply {
            text = if (showRelativeTimeOnRight) "$sizeStr · ${file.parentFile?.name ?: "内部存储"}"
            else "$sizeStr · $relTime"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(tokens.mutedForeground)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            BrightCalmTheme.applyTabularNums(this)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textCol)

        if (showRelativeTimeOnRight) {
            row.addView(TextView(context).apply {
                text = relTime
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(tokens.mutedForeground)
                BrightCalmTheme.applyTabularNums(this)
                setPadding(0, 0, dp(4), 0)
            })
        }

        // Favorite star (shown only if favorited, §5.4)
        if (BrightCalmTheme.isFavorite(context, file.absolutePath)) {
            row.addView(
                BrightCalmTheme.createUiIconView(
                    context,
                    BrightCalmTheme.UiIcon.STAR,
                    tokens.typeSlide,
                    16,
                    filled = true
                )
            )
        }

        // Right 44x44dp more-vertical button
        row.addView(
            BrightCalmTheme.createIconButton(
                context,
                BrightCalmTheme.UiIcon.MORE_VERTICAL,
                tokens.mutedForeground,
                "更多操作"
            ) {
                showFileRowActionMenu(it, file)
            }
        )

        wrapper.addView(row)
        if (!isLastInSection) {
            wrapper.addView(buildIndentedDivider(tokens))
        }
        return wrapper
    }

    private fun buildIndentedDivider(tokens: BrightCalmTheme.Tokens): View {
        return View(context).apply {
            setBackgroundColor(tokens.border)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                // Indented 56dp from left (40dp icon + 12dp gap + 4dp alignment, §5.4 & §10.3)
                leftMargin = dp(52)
            }
        }
    }

    private fun addSectionSpacerAndDivider(tokens: BrightCalmTheme.Tokens) {
        pageContentContainer.addView(View(context).apply {
            setBackgroundColor(tokens.border)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(16)
            }
        })
    }

    private fun showFileRowActionMenu(anchor: View, file: File) {
        val isFav = BrightCalmTheme.isFavorite(context, file.absolutePath)
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 1, 0, "打开文件")
        popup.menu.add(0, 2, 1, if (isFav) "取消收藏" else "添加收藏")
        popup.menu.add(0, 3, 2, "所在目录")
        popup.menu.add(0, 4, 3, "文件详情")
        popup.menu.add(0, 5, 4, "移至回收站")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    openRealFile(file)
                    true
                }
                2 -> {
                    val nowFav = BrightCalmTheme.toggleFavorite(context, file.absolutePath)
                    Toast.makeText(context, if (nowFav) "已收藏 ${file.name}" else "已取消收藏", Toast.LENGTH_SHORT).show()
                    renderActiveTab(animate = false)
                    true
                }
                3 -> {
                    file.parentFile?.let { onNavigateToFolder?.invoke(Paths.get(it.absolutePath)) }
                    true
                }
                4 -> {
                    showFileRowDetailSheet(file)
                    true
                }
                5 -> {
                    BrightCalmPanels.confirmAndMoveToRecycleBin(context, this, file) {
                        EsToolboxActivity.invalidateCategoryCache()
                        refreshDashboardData()
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    fun showFileRowDetailSheet(file: File) {
        BrightCalmPanels.showFileDetailSheet(
            context = context,
            anchorViewForSnackbar = this,
            file = file,
            scope = scope,
            onOpenFile = { openRealFile(it) },
            onNavigateToParent = { path -> onNavigateToFolder?.invoke(path) },
            onShareFile = { f ->
                Toast.makeText(context, "准备分享：${f.name}", Toast.LENGTH_SHORT).show()
            },
            onCopyOrMoveFile = { f, _ ->
                f.parentFile?.let { onNavigateToFolder?.invoke(Paths.get(it.absolutePath)) }
            },
            onRenameFile = { f ->
                f.parentFile?.let { onNavigateToFolder?.invoke(Paths.get(it.absolutePath)) }
            },
            onCompressFile = { f ->
                f.parentFile?.let { onNavigateToFolder?.invoke(Paths.get(it.absolutePath)) }
            },
            onFileChanged = {
                EsToolboxActivity.invalidateCategoryCache()
                refreshDashboardData()
            }
        )
    }

    private fun showSingleChoiceDialog(
        title: String,
        options: Array<String>,
        checkedItem: Int,
        onSelect: (Int) -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                dialog.dismiss()
                onSelect(which)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun openStoragePermissionSettings() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            } else {
                val intent = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
        }
    }

    private fun computeStorageAndCategoryMetrics(): StorageBreakdown {
        runCatching {
            val pubDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val legacyDemo = File(pubDownload, "项目资料汇总_支持免解压预览.zip")
            if (legacyDemo.exists()) legacyDemo.delete()
        }

        val rootFile = Environment.getExternalStorageDirectory()
        val statFs = runCatching { StatFs(rootFile.absolutePath) }.getOrNull()
        val totalBytes = (statFs?.totalBytes ?: 1L).coerceAtLeast(1L)
        val freeBytes = (statFs?.availableBytes ?: 0L).coerceAtLeast(0L)
        val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)

        EsToolboxActivity.prewarmCategoryCache(context)

        val imgFiles = EsToolboxActivity.queryFilesFast(context, EsToolboxActivity.CAT_IMAGE)
        val vidFiles = EsToolboxActivity.queryFilesFast(context, EsToolboxActivity.CAT_VIDEO)
        val audFiles = EsToolboxActivity.queryFilesFast(context, EsToolboxActivity.CAT_AUDIO)
        val docFiles = EsToolboxActivity.queryFilesFast(context, EsToolboxActivity.CAT_DOC)
        val zipFiles = EsToolboxActivity.queryFilesFast(context, EsToolboxActivity.CAT_ARCHIVE)
        val apkFiles = EsToolboxActivity.queryFilesFast(context, EsToolboxActivity.CAT_APK)

        val imgBytes = imgFiles.sumOf { it.length() }
        val vidBytes = vidFiles.sumOf { it.length() }
        val audBytes = audFiles.sumOf { it.length() }
        val docBytes = docFiles.sumOf { it.length() }
        val zipBytes = zipFiles.sumOf { it.length() }
        val apkBytes = apkFiles.sumOf { it.length() }
        val otherBytes = (usedBytes - imgBytes - vidBytes - docBytes).coerceAtLeast(0L)

        val installedAppsCount = runCatching {
            context.packageManager.getInstalledPackages(0).size
        }.getOrDefault(0)

        val countsMap = mapOf(
            EsToolboxActivity.CAT_IMAGE to imgFiles.size,
            EsToolboxActivity.CAT_VIDEO to vidFiles.size,
            EsToolboxActivity.CAT_AUDIO to audFiles.size,
            EsToolboxActivity.CAT_DOC to docFiles.size,
            EsToolboxActivity.CAT_ARCHIVE to zipFiles.size,
            EsToolboxActivity.CAT_APK to apkFiles.size,
            "installed_apps" to installedAppsCount
        )

        val sizesMap = mapOf(
            EsToolboxActivity.CAT_IMAGE to imgBytes,
            EsToolboxActivity.CAT_VIDEO to vidBytes,
            EsToolboxActivity.CAT_AUDIO to audBytes,
            EsToolboxActivity.CAT_DOC to docBytes,
            EsToolboxActivity.CAT_ARCHIVE to zipBytes,
            EsToolboxActivity.CAT_APK to apkBytes
        )

        val previewsMap = mapOf(
            EsToolboxActivity.CAT_IMAGE to imgFiles.take(3),
            EsToolboxActivity.CAT_VIDEO to vidFiles.take(3)
        )

        val allFiles = (imgFiles + vidFiles + audFiles + docFiles + zipFiles + apkFiles)
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }

        val recent = allFiles.take(40)

        val prefs = context.getSharedPreferences(BrightCalmTheme.PREFS_NAME, Context.MODE_PRIVATE)
        val thresholdBytes = prefs.getInt(BrightCalmTheme.KEY_LARGE_FILE_THRESHOLD_MB, 100) * 1024L * 1024L
        val largeFiles = allFiles.filter { it.length() >= thresholdBytes }

        return StorageBreakdown(
            totalBytes = totalBytes,
            usedBytes = usedBytes,
            imageBytes = imgBytes,
            videoBytes = vidBytes,
            docBytes = docBytes,
            otherBytes = otherBytes,
            countsByCat = countsMap,
            sizesByCat = sizesMap,
            previewFilesByCat = previewsMap,
            recentFiles = recent,
            largeFilesCount = largeFiles.size,
            largeFilesBytes = largeFiles.sumOf { it.length() }
        )
    }

    private fun formatChineseRelativeTime(timestampMs: Long): String {
        val calNow = Calendar.getInstance()
        calNow.set(Calendar.HOUR_OF_DAY, 0)
        calNow.set(Calendar.MINUTE, 0)
        calNow.set(Calendar.SECOND, 0)
        calNow.set(Calendar.MILLISECOND, 0)
        val startOfToday = calNow.timeInMillis
        val startOfYesterday = startOfToday - 24L * 3600_000L
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFmt = SimpleDateFormat("M月d日", Locale.getDefault())
        return when {
            timestampMs >= startOfToday -> "今天 ${timeFmt.format(Date(timestampMs))}"
            timestampMs >= startOfYesterday -> "昨天 ${timeFmt.format(Date(timestampMs))}"
            else -> dateFmt.format(Date(timestampMs))
        }
    }

    fun openRealFile(file: File) {
        if (file.isDirectory) {
            onNavigateToFolder?.invoke(Paths.get(file.absolutePath))
            return
        }
        val path = Paths.get(file.absolutePath)
        val ext = file.extension.lowercase(Locale.ROOT)
        when {
            ext in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "ts") -> {
                context.startActivity(MediaPlayerActivity.createIntent(context, path, isAudio = false))
            }
            ext in setOf("mp3", "flac", "wav", "m4a", "ogg", "aac") -> {
                context.startActivity(MediaPlayerActivity.createIntent(context, path, isAudio = true))
            }
            ext in setOf("txt", "log", "ini", "prop", "epub") -> {
                context.startActivity(ReaderActivity.createIntent(context, path))
            }
            ext in setOf("pdf", "docx", "doc", "xlsx", "xls", "csv", "md", "markdown", "json", "xml") -> {
                context.startActivity(DocumentPreviewActivity.createIntent(context, path))
            }
            ext in setOf("zip", "rar", "7z", "tar", "apk", "jar") -> {
                context.startActivity(ArchivePreviewActivity.createIntent(context, path))
            }
            ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic") -> {
                context.startActivity(
                    ImageViewerActivity::class.createIntent().apply {
                        ImageViewerActivity.putExtras(this, listOf(path), 0)
                    }
                )
            }
            else -> {
                context.startActivity(DocumentPreviewActivity.createIntent(context, path))
            }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
