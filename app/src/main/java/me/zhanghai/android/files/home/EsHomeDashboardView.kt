/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.os.StatFs
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
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
import android.text.format.Formatter
import me.zhanghai.android.files.cloud.CloudDriveActivity
import me.zhanghai.android.files.diskanalysis.DiskAnalysisActivity
import me.zhanghai.android.files.ftpserver.FtpServerActivity
import me.zhanghai.android.files.tools.EsToolboxActivity
import me.zhanghai.android.files.viewer.archive.ArchivePreviewActivity
import me.zhanghai.android.files.viewer.document.DocumentPreviewActivity
import me.zhanghai.android.files.viewer.media.MediaPlayerActivity
import me.zhanghai.android.files.viewer.reader.ReaderActivity

class EsHomeDashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onNavigateToFolder: ((Path) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val deviceStatsTv: TextView
    private val categoryGridContainer: LinearLayout
    private val recentFilesRow: LinearLayout

    private data class DashboardMetrics(
        val totalFiles: Int,
        val installedApps: Int,
        val imageCount: Int,
        val videoCount: Int,
        val audioCount: Int,
        val docCount: Int,
        val archiveCount: Int,
        val apkCount: Int,
        val downloadCount: Int,
        val recentFiles: List<File>
    )

    init {
        setBackgroundColor(Color.parseColor("#F1F5F9"))

        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val mainCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(80))
        }

        // 1. Device Status Overview Bar (PRD 2.1.1)
        val statusCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            layoutParams = lp
        }
        deviceStatsTv = TextView(context).apply {
            text = "📱 设备概览：正在统计本地文件与已安装应用..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val refreshBtn = TextView(context).apply {
            text = "🔄 刷新"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#2563EB"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { refreshDashboardData() }
        }
        statusCard.addView(deviceStatsTv)
        statusCard.addView(refreshBtn)
        mainCol.addView(statusCard)

        // 2. Storage Mount Point Card (PRD 2.1.2)
        mainCol.addView(buildStorageCard())

        // 3. 8-Grid Media & File Category Matrix (PRD 2.1.3)
        mainCol.addView(buildSectionTitle("📂 分类文件库 (点击浏览手机真实文件)"))
        categoryGridContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(8), dp(12), dp(8), dp(12))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            layoutParams = lp
        }
        mainCol.addView(categoryGridContainer)
        populateCategoryGrid(null)

        // 4. Recent Files Horizontal Stream (PRD 2.1.4)
        mainCol.addView(buildSectionTitle("🕒 最近文件流 (Recent Files)"))
        val recentScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            layoutParams = lp
        }
        recentFilesRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        recentScroll.addView(recentFilesRow)
        mainCol.addView(recentScroll)

        // 5. Bookmarks & Quick Tools (PRD 2.1.5)
        mainCol.addView(buildSectionTitle("⭐ 常用目录书签 (点击直达文件夹)"))
        mainCol.addView(buildBookmarksRow())

        mainCol.addView(buildSectionTitle("🛠️ 快捷工具箱 (对标 ES / RS / MX / 静读天下)"))
        mainCol.addView(buildQuickToolsGrid())

        scrollView.addView(mainCol)
        addView(scrollView)

        refreshDashboardData()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }

    private fun buildSectionTitle(title: String): TextView {
        return TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
    }

    private fun buildStorageCard(): LinearLayout {
        val rootFile = Environment.getExternalStorageDirectory()
        val statFs = try {
            StatFs(rootFile.absolutePath)
        } catch (_: Exception) {
            null
        }
        val totalBytes = statFs?.totalBytes ?: 1L
        val freeBytes = statFs?.availableBytes ?: 0L
        val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)
        val usedPercent = ((usedBytes * 100L) / totalBytes.coerceAtLeast(1L)).toInt().coerceIn(0, 100)

        val barColor = when {
            usedPercent >= 95 -> "#DC2626" // Error Red
            usedPercent >= 85 -> "#F59E0B" // Warning Amber
            else -> "#2563EB" // Primary Blue
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            layoutParams = lp
            setOnClickListener {
                onNavigateToFolder?.invoke(Paths.get(rootFile.absolutePath))
            }
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(context).apply {
            text = "💾 内部共享存储 (/storage/emulated/0)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
        })
        titleCol.addView(TextView(context).apply {
            text = "已用 ${Formatter.formatFileSize(context, usedBytes)} / 共 ${Formatter.formatFileSize(context, totalBytes)} ($usedPercent%)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(2), 0, 0)
        })
        topRow.addView(titleCol)

        val analyzeBtn = TextView(context).apply {
            text = "空间分析 >"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor(barColor))
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                context.startActivity(Intent(context, DiskAnalysisActivity::class.java))
            }
        }
        topRow.addView(analyzeBtn)
        card.addView(topRow)

        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = usedPercent
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(barColor))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8)
            ).apply { topMargin = dp(10) }
            layoutParams = lp
        }
        card.addView(progress)

        return card
    }

    private fun populateCategoryGrid(metrics: DashboardMetrics?) {
        categoryGridContainer.removeAllViews()

        data class CatItem(
            val icon: String,
            val title: String,
            val countText: String,
            val badgeBg: String,
            val onClick: () -> Unit
        )

        val items = listOf(
            CatItem("🖼️", "图片", "${metrics?.imageCount ?: 0} 项", "#EFF6FF") {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_IMAGE))
            },
            CatItem("🎬", "视频", "${metrics?.videoCount ?: 0} 项", "#FEF2F2") {
                context.startActivity(MediaPlayerActivity.createIntent(context))
            },
            CatItem("🎵", "音频", "${metrics?.audioCount ?: 0} 项", "#F0FDF4") {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_AUDIO))
            },
            CatItem("📄", "文档", "${metrics?.docCount ?: 0} 项", "#F5F3FF") {
                context.startActivity(DocumentPreviewActivity.createIntent(context))
            },
            CatItem("🗜️", "压缩包", "${metrics?.archiveCount ?: 0} 项", "#FFFBEB") {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_ARCHIVE))
            },
            CatItem("🤖", "安装包", "${metrics?.apkCount ?: 0} 项", "#ECFDF5") {
                context.startActivity(EsToolboxActivity.createCategoryIntent(context, EsToolboxActivity.CAT_APK))
            },
            CatItem("📥", "下载", "${metrics?.downloadCount ?: 0} 项", "#E0F2FE") {
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                onNavigateToFolder?.invoke(Paths.get(dl.absolutePath))
            },
            CatItem("📱", "应用管理", "${metrics?.installedApps ?: 0} 个", "#F3E8FF") {
                context.startActivity(EsToolboxActivity.createModeIntent(context, EsToolboxActivity.MODE_APP_MANAGER))
            }
        )

        for (rowIdx in 0 until 2) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIdx == 0) bottomMargin = dp(8)
                }
            }
            for (colIdx in 0 until 4) {
                val item = items[rowIdx * 4 + colIdx]
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { item.onClick() }
                }

                val iconCircle = TextView(context).apply {
                    text = item.icon
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(Color.parseColor(item.badgeBg))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
                }
                cell.addView(iconCircle)

                cell.addView(TextView(context).apply {
                    text = item.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#1E293B"))
                    setPadding(0, dp(4), 0, 0)
                })
                cell.addView(TextView(context).apply {
                    text = item.countText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTextColor(Color.parseColor("#64748B"))
                })
                row.addView(cell)
            }
            categoryGridContainer.addView(row)
        }
    }

    private fun buildBookmarksRow(): HorizontalScrollView {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            layoutParams = lp
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val root = Environment.getExternalStorageDirectory()
        val bookmarks = listOf(
            "📁 内部存储根目录" to root,
            "📥 Download 下载" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "📷 DCIM 相册" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "📄 Documents 文档" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "🎬 Movies 视频" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "🎵 Music 音乐" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        )

        bookmarks.forEach { (label, dir) ->
            val chip = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#1E40AF"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(1), Color.parseColor("#BFDBFE"))
                }
                setPadding(dp(12), dp(8), dp(12), dp(8))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                layoutParams = lp
                setOnClickListener {
                    dir.mkdirs()
                    onNavigateToFolder?.invoke(Paths.get(dir.absolutePath))
                }
            }
            row.addView(chip)
        }

        scroll.addView(row)
        return scroll
    }

    private fun buildQuickToolsGrid(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }

        val tools = listOf(
            Triple("🎬 MX视频播", "沉浸手势/字幕", "#FEF2F2") to {
                context.startActivity(MediaPlayerActivity.createIntent(context))
            },
            Triple("📖 静读阅读器", "沉浸翻页/书架", "#FFFBEB") to {
                context.startActivity(ReaderActivity.createIntent(context))
            },
            Triple("📘 Office/MD", "真实手机文档", "#EFF6FF") to {
                context.startActivity(DocumentPreviewActivity.createIntent(context))
            },
            Triple("📊 空间分析", "大文件/冗余", "#ECFDF5") to {
                context.startActivity(Intent(context, DiskAnalysisActivity::class.java))
            },
            Triple("☁️ 云盘直连", "Drive/OneDrive", "#E0F2FE") to {
                context.startActivity(Intent(context, CloudDriveActivity::class.java))
            },
            Triple("📡 远程管理", "FTP局域网共享", "#F3E8FF") to {
                context.startActivity(Intent(context, FtpServerActivity::class.java))
            },
            Triple("🗑️ 回收站", "防误删还原", "#FEF3C7") to {
                context.startActivity(EsToolboxActivity.createModeIntent(context, EsToolboxActivity.MODE_RECYCLE_BIN))
            },
            Triple("🔐 加密保险箱", "128位AES保护", "#F1F5F9") to {
                context.startActivity(EsToolboxActivity.createModeIntent(context, EsToolboxActivity.MODE_VAULT))
            }
        )

        for (r in 0 until 2) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (r == 0) bottomMargin = dp(8)
                }
            }
            for (c in 0 until 4) {
                val (info, action) = tools[r * 4 + c]
                val (title, sub, bg) = info
                val box = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.parseColor(bg))
                    }
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), 0, dp(4), 0)
                    }
                    setOnClickListener { action() }
                }
                box.addView(TextView(context).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
                box.addView(TextView(context).apply {
                    text = sub
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTextColor(Color.parseColor("#64748B"))
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
                row.addView(box)
            }
            container.addView(row)
        }

        return container
    }

    fun refreshDashboardData() {
        scope.launch {
            val metrics = withContext(Dispatchers.IO) {
                var totalFiles = 0
                var img = 0
                var vid = 0
                var aud = 0
                var doc = 0
                var arc = 0
                var apk = 0
                val recent = mutableListOf<File>()

                val root = Environment.getExternalStorageDirectory()
                fun scan(dir: File, depth: Int) {
                    if (depth > 5) return
                    val children = dir.listFiles() ?: return
                    for (f in children) {
                        if (f.name.startsWith(".")) continue
                        if (f.isDirectory) {
                            val lower = f.name.lowercase(Locale.ROOT)
                            if (lower == "android" || lower == "cache" || lower == ".thumbnails") continue
                            scan(f, depth + 1)
                        } else if (f.isFile && f.length() > 0) {
                            totalFiles++
                            recent.add(f)
                            when (f.extension.lowercase(Locale.ROOT)) {
                                "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> img++
                                "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "ts" -> vid++
                                "mp3", "flac", "wav", "m4a", "ogg", "aac", "opus" -> aud++
                                "docx", "doc", "xlsx", "xls", "csv", "md", "markdown", "txt", "pdf", "pptx" -> doc++
                                "zip", "rar", "7z", "tar", "gz" -> arc++
                                "apk", "apks", "xapk" -> apk++
                            }
                        }
                    }
                }
                try {
                    scan(root, 0)
                } catch (_: Exception) {
                }

                val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dlCount = dlDir.listFiles()?.count { it.isFile } ?: 0
                val installedCount = try {
                    context.packageManager.getInstalledPackages(0).size
                } catch (_: Exception) {
                    0
                }

                DashboardMetrics(
                    totalFiles = totalFiles,
                    installedApps = installedCount,
                    imageCount = img,
                    videoCount = vid,
                    audioCount = aud,
                    docCount = doc,
                    archiveCount = arc,
                    apkCount = apk,
                    downloadCount = dlCount,
                    recentFiles = recent.sortedByDescending { it.lastModified() }.take(15)
                )
            }

            deviceStatsTv.text = "📊 本地文件总数：${metrics.totalFiles} 项   |   📱 已安装应用：${metrics.installedApps} 个"
            populateCategoryGrid(metrics)
            populateRecentFiles(metrics.recentFiles)
        }
    }

    private fun populateRecentFiles(files: List<File>) {
        recentFilesRow.removeAllViews()
        if (files.isEmpty()) {
            recentFilesRow.addView(TextView(context).apply {
                text = "暂无最近修改的手机文件"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#64748B"))
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
            return
        }

        val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        files.forEach { file ->
            val ext = file.extension.lowercase(Locale.ROOT)
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(1), Color.parseColor("#E2E8F0"))
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8)
                }
                setOnClickListener {
                    openRecentFile(file)
                }
            }

            card.addView(TextView(context).apply {
                text = file.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            card.addView(TextView(context).apply {
                text = "${ext.uppercase(Locale.ROOT).ifBlank { "FILE" }} · ${Formatter.formatFileSize(context, file.length())}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(Color.parseColor("#2563EB"))
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(TextView(context).apply {
                text = df.format(Date(file.lastModified()))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(Color.parseColor("#64748B"))
            })
            recentFilesRow.addView(card)
        }
    }

    private fun openRecentFile(file: File) {
        val path = Paths.get(file.absolutePath)
        val ext = file.extension.lowercase(Locale.ROOT)
        when {
            ext in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "ts") ->
                context.startActivity(MediaPlayerActivity.createIntent(context, path, isAudio = false))
            ext in setOf("mp3", "flac", "wav", "m4a", "ogg", "aac") ->
                context.startActivity(MediaPlayerActivity.createIntent(context, path, isAudio = true))
            ext in setOf("txt", "log", "ini") ->
                context.startActivity(ReaderActivity.createIntent(context, path))
            ext in setOf("zip", "rar", "7z", "tar", "apk") ->
                context.startActivity(ArchivePreviewActivity.createIntent(context, path))
            else ->
                context.startActivity(DocumentPreviewActivity.createIntent(context, path))
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
