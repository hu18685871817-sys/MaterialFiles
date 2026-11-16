package me.zhanghai.android.files.diskanalysis

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import java8.nio.file.DirectoryStream
import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.JavaFile
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.MimeTypeIcon
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.file.icon
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.file.isAudio
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.viewer.media.MediaPlayerActivity
import java.io.File
import java.util.Locale

class DiskAnalysisActivity : AppActivity() {

    enum class FileCategory(
        val titleRes: Int,
        val colorHex: String,
        val iconRes: Int
    ) {
        IMAGE(R.string.disk_analysis_category_image, "#4285F4", R.drawable.file_image_icon),
        VIDEO(R.string.disk_analysis_category_video, "#E91E63", R.drawable.file_video_icon),
        AUDIO(R.string.disk_analysis_category_audio, "#9C27B0", R.drawable.file_audio_icon),
        DOCUMENT(R.string.disk_analysis_category_document, "#00ACC1", R.drawable.file_document_icon),
        APK(R.string.disk_analysis_category_apk, "#43A047", R.drawable.file_apk_icon),
        OTHER(R.string.disk_analysis_category_other, "#FB8C00", R.drawable.file_archive_icon)
    }

    data class ScannedFileEntry(
        val path: Path,
        val name: String,
        val size: Long,
        val mimeType: MimeType,
        val category: FileCategory
    )

    data class FolderSizeEntry(
        val path: Path,
        val name: String,
        val totalBytes: Long,
        val fileCount: Int,
        val subFolderCount: Int
    )

    data class DiskAnalysisResult(
        val rootPath: Path,
        val totalCapacityBytes: Long,
        val usedSpaceBytes: Long,
        val freeSpaceBytes: Long,
        val scannedBytes: Long,
        val totalFilesCount: Int,
        val totalFoldersCount: Int,
        val categoryBytes: Map<FileCategory, Long>,
        val categoryCounts: Map<FileCategory, Int>,
        val categoryTopFiles: Map<FileCategory, List<ScannedFileEntry>>,
        val folderEntries: List<FolderSizeEntry>
    )

    private val navigationHistory = ArrayDeque<Path>()
    private lateinit var currentPath: Path
    private var sortAscending = false
    private var selectedCategory: FileCategory? = null
    private var latestResult: DiskAnalysisResult? = null
    private var scanJob: Job? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var pathLabel: TextView
    private lateinit var scanStatusContainer: LinearLayout
    private lateinit var scanStatusText: TextView
    private lateinit var donutChart: StorageDonutChartView
    private lateinit var totalSpaceValueText: TextView
    private lateinit var usedSpaceValueText: TextView
    private lateinit var freeSpaceValueText: TextView
    private lateinit var scannedSummaryText: TextView
    private lateinit var stackedBarView: CategoryStackedBarView
    private lateinit var categoryContainer: LinearLayout
    private lateinit var categoryTopFilesContainer: LinearLayout
    private lateinit var folderSortButton: MaterialButton
    private lateinit var folderListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialPath = intent.extraPath
            ?: Paths.get(Environment.getExternalStorageDirectory().absolutePath)
        currentPath = initialPath

        setContentView(buildContentView())
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.disk_analysis_title)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navigationHistory.isNotEmpty()) {
                    currentPath = navigationHistory.removeLast()
                    startDiskScan(currentPath)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        startDiskScan(currentPath)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (navigationHistory.isNotEmpty()) {
                currentPath = navigationHistory.removeLast()
                startDiskScan(currentPath)
            } else {
                finish()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildContentView(): View {
        val colorPrimary = getColorByAttr(androidx.appcompat.R.attr.colorPrimary)
        val colorOnSurface = getColorByAttr(com.google.android.material.R.attr.colorOnSurface)
        val colorSurface = getColorByAttr(com.google.android.material.R.attr.colorSurface)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorSurface)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val appBar = AppBarLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        toolbar = MaterialToolbar(this).apply {
            layoutParams = AppBarLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        appBar.addView(toolbar)
        rootLayout.addView(appBar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }

        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }

        // Quick Path Chips + Up / Open in FileList bar
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val chipGroup = ChipGroup(this).apply {
            isSingleSelection = true
        }
        val internalPath = Paths.get(Environment.getExternalStorageDirectory().absolutePath)
        val downloadPath = Paths.get(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        )
        val dcimPath = Paths.get(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath
        )
        val rootFsPath = Paths.get("/")

        val presets = listOf(
            "内部存储" to internalPath,
            "Download" to downloadPath,
            "DCIM 相册" to dcimPath,
            "根目录 /" to rootFsPath
        )
        for ((label, targetPath) in presets) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                setOnClickListener {
                    if (currentPath != targetPath) {
                        navigationHistory.clear()
                        currentPath = targetPath
                        startDiskScan(currentPath)
                    }
                }
            }
            chipGroup.addView(chip)
        }
        chipScroll.addView(chipGroup)
        contentColumn.addView(chipScroll)

        // Current Path & Actions row
        val pathRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        pathLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val upBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "上一级"
            textSize = 12f
            setOnClickListener {
                val parent = currentPath.parent
                if (parent != null) {
                    navigationHistory.addLast(currentPath)
                    currentPath = parent
                    startDiskScan(currentPath)
                }
            }
        }
        val openDirBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "打开目录"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            }
            setOnClickListener {
                startActivitySafe(FileListActivity.createViewIntent(currentPath))
            }
        }
        pathRow.addView(pathLabel)
        pathRow.addView(upBtn)
        pathRow.addView(openDirBtn)
        contentColumn.addView(pathRow)

        // Scan status bar
        scanStatusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        val progressSpinner = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        }
        scanStatusText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
            textSize = 13f
            setTextColor(colorOnSurface)
        }
        scanStatusContainer.addView(progressSpinner)
        scanStatusContainer.addView(scanStatusText)
        contentColumn.addView(scanStatusContainer)

        // Card 1: Total / Used / Free Space Overview
        val capacityCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        val capacityCardBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        val capacityHeader = TextView(this).apply {
            text = "存储空间概览（总容量 / 已用 / 可用）"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface)
        }
        capacityCardBody.addView(capacityHeader)

        val chartAndMetricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        donutChart = StorageDonutChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(124), dp(124))
            primaryColor = colorPrimary
            textColor = colorOnSurface
        }
        chartAndMetricsRow.addView(donutChart)

        val metricsCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(16) }
        }

        totalSpaceValueText = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface)
        }
        usedSpaceValueText = TextView(this).apply {
            textSize = 14f
            setTextColor(colorPrimary)
            setPadding(0, dp(6), 0, dp(6))
        }
        freeSpaceValueText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#2E7D32"))
        }
        scannedSummaryText = TextView(this).apply {
            textSize = 12f
            setTextColor(colorOnSurface)
            alpha = 0.75f
            setPadding(0, dp(8), 0, 0)
        }

        metricsCol.addView(totalSpaceValueText)
        metricsCol.addView(usedSpaceValueText)
        metricsCol.addView(freeSpaceValueText)
        metricsCol.addView(scannedSummaryText)
        chartAndMetricsRow.addView(metricsCol)
        capacityCardBody.addView(chartAndMetricsRow)
        capacityCard.addView(capacityCardBody)
        contentColumn.addView(capacityCard)

        // Card 2: File Type Breakdown (Images / Videos / Audio / Documents / APKs / Other)
        val typeCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        val typeCardBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        val typeHeader = TextView(this).apply {
            text = getString(R.string.disk_analysis_type_distribution) + "（点击分类查看大文件）"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface)
        }
        typeCardBody.addView(typeHeader)

        stackedBarView = CategoryStackedBarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(16)
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(14)
            }
        }
        typeCardBody.addView(stackedBarView)

        categoryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        typeCardBody.addView(categoryContainer)

        categoryTopFilesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        typeCardBody.addView(categoryTopFilesContainer)
        typeCard.addView(typeCardBody)
        contentColumn.addView(typeCard)

        // Card 3: Folders Sorted by Size
        val folderCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val folderCardBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        val folderHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val folderTitle = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = getString(R.string.disk_analysis_folder_ranking)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface)
        }
        folderSortButton = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "从大到小 ↓"
            textSize = 12f
            setOnClickListener {
                sortAscending = !sortAscending
                text = if (sortAscending) "从小到大 ↑" else "从大到小 ↓"
                latestResult?.let { renderResult(it) }
            }
        }
        folderHeaderRow.addView(folderTitle)
        folderHeaderRow.addView(folderSortButton)
        folderCardBody.addView(folderHeaderRow)

        folderListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        folderCardBody.addView(folderListContainer)
        folderCard.addView(folderCardBody)
        contentColumn.addView(folderCard)

        scrollView.addView(contentColumn)
        rootLayout.addView(scrollView)
        return rootLayout
    }

    private fun startDiskScan(targetPath: Path) {
        scanJob?.cancel()
        pathLabel.text = "当前分析路径: ${targetPath.toUri().path ?: targetPath.toString()}"
        scanStatusContainer.visibility = View.VISIBLE
        scanStatusText.text = "正在扫描文件夹与统计各类型占比…"

        scanJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                performScan(targetPath)
            }
            latestResult = result
            scanStatusContainer.visibility = View.GONE
            renderResult(result)
        }
    }

    private suspend fun performScan(rootPath: Path): DiskAnalysisResult = withContext(Dispatchers.IO) {
        val linuxPathStr = rootPath.toFile().absolutePath
        var totalSpace = runCatching { JavaFile.getTotalSpace(linuxPathStr) }.getOrDefault(0L)
        var freeSpace = runCatching { JavaFile.getFreeSpace(linuxPathStr) }.getOrDefault(0L)
        if (totalSpace <= 0L) {
            val extPath = Environment.getExternalStorageDirectory().absolutePath
            totalSpace = runCatching { JavaFile.getTotalSpace(extPath) }.getOrDefault(0L)
            freeSpace = runCatching { JavaFile.getFreeSpace(extPath) }.getOrDefault(0L)
        }
        val usedSpace = (totalSpace - freeSpace).coerceAtLeast(0L)

        val categoryBytes = EnumMapCategoryLong()
        val categoryCounts = EnumMapCategoryInt()
        val categoryTopFiles = FileCategory.values().associateWith { mutableListOf<ScannedFileEntry>() }
        val folderEntries = mutableListOf<FolderSizeEntry>()

        var scannedBytes = 0L
        var totalFiles = 0
        var totalFolders = 0

        // Scan direct children of rootPath so we can compute exact recursive size of each child folder
        val rootDirFile = File(linuxPathStr)
        val children = rootDirFile.listFiles().orEmpty()
        for (child in children) {
            coroutineContext.ensureActive()
            if (child.isDirectory) {
                totalFolders++
                var folderBytes = 0L
                var folderFiles = 0
                var folderSubDirs = 0
                val stack = ArrayDeque<File>()
                stack.addLast(child)
                while (stack.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    val curr = stack.removeLast()
                    val subChildren = curr.listFiles() ?: continue
                    for (sub in subChildren) {
                        // Avoid following symlinks into loops
                        if (sub.isDirectory) {
                            val isSymlink = runCatching {
                                Files.isSymbolicLink(Paths.get(sub.absolutePath))
                            }.getOrDefault(false)
                            if (!isSymlink) {
                                folderSubDirs++
                                totalFolders++
                                stack.addLast(sub)
                            }
                        } else if (sub.isFile) {
                            val len = sub.length().coerceAtLeast(0L)
                            folderBytes += len
                            scannedBytes += len
                            folderFiles++
                            totalFiles++
                            val subPath = Paths.get(sub.absolutePath)
                            val mime = classifyMime(sub.name)
                            val cat = classifyCategory(mime, sub.name)
                            categoryBytes[cat] = (categoryBytes[cat] ?: 0L) + len
                            categoryCounts[cat] = (categoryCounts[cat] ?: 0) + 1
                            addTopFile(categoryTopFiles.getValue(cat), ScannedFileEntry(subPath, sub.name, len, mime, cat))
                        }
                    }
                }
                folderEntries.add(
                    FolderSizeEntry(
                        path = Paths.get(child.absolutePath),
                        name = child.name,
                        totalBytes = folderBytes,
                        fileCount = folderFiles,
                        subFolderCount = folderSubDirs
                    )
                )
            } else if (child.isFile) {
                val len = child.length().coerceAtLeast(0L)
                scannedBytes += len
                totalFiles++
                val childPath = Paths.get(child.absolutePath)
                val mime = classifyMime(child.name)
                val cat = classifyCategory(mime, child.name)
                categoryBytes[cat] = (categoryBytes[cat] ?: 0L) + len
                categoryCounts[cat] = (categoryCounts[cat] ?: 0) + 1
                addTopFile(categoryTopFiles.getValue(cat), ScannedFileEntry(childPath, child.name, len, mime, cat))
            }
        }

        DiskAnalysisResult(
            rootPath = rootPath,
            totalCapacityBytes = totalSpace,
            usedSpaceBytes = usedSpace,
            freeSpaceBytes = freeSpace,
            scannedBytes = scannedBytes,
            totalFilesCount = totalFiles,
            totalFoldersCount = totalFolders,
            categoryBytes = categoryBytes,
            categoryCounts = categoryCounts,
            categoryTopFiles = categoryTopFiles.mapValues { it.value.sortedByDescending { f -> f.size } },
            folderEntries = folderEntries
        )
    }

    private fun EnumMapCategoryLong(): MutableMap<FileCategory, Long> =
        FileCategory.values().associateWith { 0L }.toMutableMap()

    private fun EnumMapCategoryInt(): MutableMap<FileCategory, Int> =
        FileCategory.values().associateWith { 0 }.toMutableMap()

    private fun addTopFile(list: MutableList<ScannedFileEntry>, entry: ScannedFileEntry) {
        if (list.size < 12) {
            list.add(entry)
            list.sortByDescending { it.size }
        } else if (entry.size > list.last().size) {
            list[list.lastIndex] = entry
            list.sortByDescending { it.size }
        }
    }

    private fun classifyMime(fileName: String): MimeType =
        MimeType.guessFromPath(fileName)

    private fun classifyCategory(mimeType: MimeType, fileName: String): FileCategory {
        val lower = fileName.lowercase(Locale.ROOT)
        if (mimeType.isApk || lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk")) {
            return FileCategory.APK
        }
        if (mimeType.isImage || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") ||
            lower.endsWith(".bmp") || lower.endsWith(".heic") || lower.endsWith(".svg")
        ) {
            return FileCategory.IMAGE
        }
        if (mimeType.isVideo || lower.endsWith(".mp4") || lower.endsWith(".mkv") ||
            lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".webm") ||
            lower.endsWith(".flv") || lower.endsWith(".ts")
        ) {
            return FileCategory.VIDEO
        }
        if (mimeType.isAudio || lower.endsWith(".mp3") || lower.endsWith(".flac") ||
            lower.endsWith(".wav") || lower.endsWith(".aac") || lower.endsWith(".ogg") ||
            lower.endsWith(".m4a") || lower.endsWith(".opus")
        ) {
            return FileCategory.AUDIO
        }
        val icon = mimeType.icon
        if (icon == MimeTypeIcon.DOCUMENT || icon == MimeTypeIcon.PDF ||
            icon == MimeTypeIcon.WORD || icon == MimeTypeIcon.EXCEL ||
            icon == MimeTypeIcon.POWERPOINT || icon == MimeTypeIcon.PRESENTATION ||
            icon == MimeTypeIcon.SPREADSHEET || icon == MimeTypeIcon.TEXT ||
            icon == MimeTypeIcon.EBOOK || icon == MimeTypeIcon.CODE ||
            lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") ||
            lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt") ||
            lower.endsWith(".pptx") || lower.endsWith(".txt") || lower.endsWith(".md")
        ) {
            return FileCategory.DOCUMENT
        }
        return FileCategory.OTHER
    }

    private fun renderResult(result: DiskAnalysisResult) {
        val colorOnSurface = getColorByAttr(com.google.android.material.R.attr.colorOnSurface)
        val colorPrimary = getColorByAttr(androidx.appcompat.R.attr.colorPrimary)

        val totalStr = result.totalCapacityBytes.asFileSize().formatHumanReadable(this)
        val usedStr = result.usedSpaceBytes.asFileSize().formatHumanReadable(this)
        val freeStr = result.freeSpaceBytes.asFileSize().formatHumanReadable(this)
        val scannedStr = result.scannedBytes.asFileSize().formatHumanReadable(this)

        val usedFraction = if (result.totalCapacityBytes > 0L) {
            result.usedSpaceBytes.toFloat() / result.totalCapacityBytes.toFloat()
        } else 0f
        val usedPercent = String.format(Locale.US, "%.1f%%", usedFraction * 100f)

        donutChart.usedFraction = usedFraction
        donutChart.centerTitle = usedPercent
        donutChart.centerSubtitle = getString(R.string.disk_analysis_used_space)

        totalSpaceValueText.text = "${getString(R.string.disk_analysis_total_space)}: $totalStr"
        usedSpaceValueText.text = "${getString(R.string.disk_analysis_used_space)}: $usedStr ($usedPercent)"
        freeSpaceValueText.text = "${getString(R.string.disk_analysis_free_space)}: $freeStr"
        scannedSummaryText.text =
            "当前目录占用: $scannedStr · 共 ${result.totalFilesCount} 个文件 / ${result.totalFoldersCount} 个文件夹"

        // Render Category Breakdown
        val segments = FileCategory.values().map { cat ->
            val bytes = result.categoryBytes[cat] ?: 0L
            val weight = if (result.scannedBytes > 0L) bytes.toFloat() else 0f
            CategoryStackedBarView.Segment(Color.parseColor(cat.colorHex), weight)
        }
        stackedBarView.segments = segments

        categoryContainer.removeAllViews()
        for (cat in FileCategory.values()) {
            val bytes = result.categoryBytes[cat] ?: 0L
            val count = result.categoryCounts[cat] ?: 0
            val pct = if (result.scannedBytes > 0L) {
                (bytes.toDouble() * 100.0 / result.scannedBytes.toDouble())
            } else 0.0
            val pctStr = String.format(Locale.US, "%.1f%%", pct)
            val sizeStr = bytes.asFileSize().formatHumanReadable(this)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                val isSelected = selectedCategory == cat
                if (isSelected) {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.parseColor("#1F4285F4"))
                    }
                }
                setOnClickListener {
                    selectedCategory = if (selectedCategory == cat) null else cat
                    renderResult(result)
                }
            }

            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                    marginEnd = dp(10)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(cat.colorHex))
                }
            }
            val iconView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    marginEnd = dp(10)
                }
                setImageResource(cat.iconRes)
            }
            val nameCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val catTitle = TextView(this).apply {
                text = "${getString(cat.titleRes)} ($count 项)"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorOnSurface)
            }
            val catBar = LinearProgressIndicator(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                trackThickness = dp(5)
                trackCornerRadius = dp(3)
                setIndicatorColor(Color.parseColor(cat.colorHex))
                max = 1000
                progress = (pct * 10).toInt().coerceIn(0, 1000)
            }
            nameCol.addView(catTitle)
            nameCol.addView(catBar)

            val sizeAndPct = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(12) }
                gravity = Gravity.END
                text = "$sizeStr\n$pctStr"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorOnSurface)
            }

            row.addView(dot)
            row.addView(iconView)
            row.addView(nameCol)
            row.addView(sizeAndPct)
            categoryContainer.addView(row)
        }

        // Render top files for selected category
        categoryTopFilesContainer.removeAllViews()
        val activeCategory = selectedCategory
        if (activeCategory != null) {
            val topFiles = result.categoryTopFiles[activeCategory].orEmpty()
            val header = TextView(this).apply {
                text = "${getString(activeCategory.titleRes)} - 占用最大的文件 (${topFiles.size})"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(activeCategory.colorHex))
                setPadding(dp(4), dp(8), dp(4), dp(6))
            }
            categoryTopFilesContainer.addView(header)
            if (topFiles.isEmpty()) {
                val emptyText = TextView(this).apply {
                    text = "当前目录下暂无此类型文件"
                    textSize = 13f
                    setTextColor(colorOnSurface)
                    alpha = 0.7f
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }
                categoryTopFilesContainer.addView(emptyText)
            } else {
                for (fileEntry in topFiles) {
                    val fileRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        setOnClickListener {
                            openScannedFile(fileEntry)
                        }
                    }
                    val fileIcon = ImageView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                            marginEnd = dp(10)
                        }
                        setImageResource(fileEntry.mimeType.iconRes)
                    }
                    val fileNameText = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        text = fileEntry.name
                        textSize = 13f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                        setTextColor(colorOnSurface)
                    }
                    val fileSizeText = TextView(this).apply {
                        text = fileEntry.size.asFileSize().formatHumanReadable(this@DiskAnalysisActivity)
                        textSize = 12f
                        setTextColor(colorPrimary)
                        setPadding(dp(8), 0, 0, 0)
                    }
                    fileRow.addView(fileIcon)
                    fileRow.addView(fileNameText)
                    fileRow.addView(fileSizeText)
                    categoryTopFilesContainer.addView(fileRow)
                }
            }
        }

        // Render Folders Sorted by Size
        folderListContainer.removeAllViews()
        val sortedFolders = if (sortAscending) {
            result.folderEntries.sortedBy { it.totalBytes }
        } else {
            result.folderEntries.sortedByDescending { it.totalBytes }
        }

        if (sortedFolders.isEmpty()) {
            val emptyFolderView = TextView(this).apply {
                text = "当前目录下没有子文件夹"
                textSize = 14f
                setTextColor(colorOnSurface)
                alpha = 0.7f
                setPadding(dp(8), dp(16), dp(8), dp(16))
            }
            folderListContainer.addView(emptyFolderView)
        } else {
            val maxFolderBytes = result.folderEntries.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1L) ?: 1L
            for ((index, folder) in sortedFolders.withIndex()) {
                val pctOfScanned = if (result.scannedBytes > 0L) {
                    (folder.totalBytes.toDouble() * 100.0 / result.scannedBytes.toDouble())
                } else 0.0
                val relProgress = ((folder.totalBytes.toDouble() / maxFolderBytes.toDouble()) * 1000.0)
                    .toInt().coerceIn(0, 1000)

                val folderRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    setOnClickListener {
                        navigationHistory.addLast(currentPath)
                        currentPath = folder.path
                        startDiskScan(currentPath)
                    }
                }

                val rankBadge = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                        marginEnd = dp(10)
                    }
                    gravity = Gravity.CENTER
                    text = "${index + 1}"
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(colorPrimary)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#1F4285F4"))
                    }
                }

                val folderIcon = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                        marginEnd = dp(10)
                    }
                    setImageResource(R.drawable.file_directory_icon)
                }

                val infoCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val titleLine = TextView(this).apply {
                    text = folder.name
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(colorOnSurface)
                }
                val subLine = TextView(this).apply {
                    text = "${folder.fileCount} 个文件 · ${folder.subFolderCount} 个子文件夹 · 占比 ${
                        String.format(Locale.US, "%.1f%%", pctOfScanned)
                    }"
                    textSize = 12f
                    setTextColor(colorOnSurface)
                    alpha = 0.72f
                }
                val progressBar = LinearProgressIndicator(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) }
                    trackThickness = dp(6)
                    trackCornerRadius = dp(3)
                    setIndicatorColor(colorPrimary)
                    max = 1000
                    progress = relProgress
                }
                infoCol.addView(titleLine)
                infoCol.addView(subLine)
                infoCol.addView(progressBar)

                val rightSizeText = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(10) }
                    text = folder.totalBytes.asFileSize().formatHumanReadable(this@DiskAnalysisActivity)
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(colorPrimary)
                }

                folderRow.addView(rankBadge)
                folderRow.addView(folderIcon)
                folderRow.addView(infoCol)
                folderRow.addView(rightSizeText)
                folderListContainer.addView(folderRow)
            }
        }
    }

    private fun openScannedFile(entry: ScannedFileEntry) {
        if (entry.mimeType.isAudio || entry.mimeType.isVideo) {
            startActivitySafe(
                MediaPlayerActivity.createIntent(
                    this,
                    entry.path.fileProviderUri,
                    entry.name,
                    entry.mimeType.isVideo
                ).apply {
                    extraPath = entry.path
                }
            )
        } else {
            val viewIntent = entry.path.fileProviderUri.createViewIntent(entry.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .apply { extraPath = entry.path }
            startActivitySafe(viewIntent)
        }
    }

    companion object {
        fun createIntent(context: Context, path: Path? = null): Intent =
            DiskAnalysisActivity::class.createIntent().apply {
                if (path != null) {
                    extraPath = path
                }
            }
    }
}
