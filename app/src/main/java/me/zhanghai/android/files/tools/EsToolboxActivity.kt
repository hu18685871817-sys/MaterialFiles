/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java8.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.ui.BrightCalmPanels
import me.zhanghai.android.files.ui.BrightCalmTheme
import me.zhanghai.android.files.ui.BrightCalmTheme.applyTabularNums
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.viewer.archive.ArchivePreviewActivity
import me.zhanghai.android.files.viewer.document.DocumentPreviewActivity
import me.zhanghai.android.files.viewer.image.ImageViewerActivity
import me.zhanghai.android.files.viewer.media.MediaPlayerActivity
import me.zhanghai.android.files.viewer.reader.ReaderActivity

class EsToolboxActivity : AppActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null

    private lateinit var rootLayout: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var subtitleTv: TextView
    private lateinit var headerRightContainer: LinearLayout
    private lateinit var contentFrame: FrameLayout

    private var currentMode: String = MODE_CATEGORY
    private var currentCategory: String = CAT_IMAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CATEGORY
        currentCategory = intent.getStringExtra(EXTRA_CATEGORY) ?: CAT_IMAGE

        val t = BrightCalmTheme.of(this)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.background)
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = systemBars.bottom
            )
            insets
        }

        setupHeader(t)

        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rootLayout.addView(contentFrame)

        setContentView(rootLayout)
        renderCurrentMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun setupHeader(t: BrightCalmTheme.Tokens) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(t.card)
            setPadding(dp(8), dp(8), dp(12), dp(8))
        }

        val backBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            addView(BrightCalmTheme.createLinearIcon(this@EsToolboxActivity, "back", t.foreground, 22).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            })
            setOnClickListener { finish() }
        }
        bar.addView(backBtn)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(4)
            }
        }
        titleTv = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(t.foreground)
        }
        subtitleTv = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(t.mutedForeground)
            applyTabularNums()
        }
        col.addView(titleTv)
        col.addView(subtitleTv)
        bar.addView(col)

        headerRightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(headerRightContainer)

        rootLayout.addView(bar)
        rootLayout.addView(View(this).apply {
            setBackgroundColor(t.border)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
    }

    private fun renderCurrentMode() {
        headerRightContainer.removeAllViews()
        when (currentMode) {
            MODE_APP_MANAGER -> renderAppManagerScreen()
            MODE_RECYCLE_BIN -> renderRecycleBinScreen()
            MODE_VAULT -> renderEncryptionVaultScreen()
            MODE_LARGE_FILES -> renderLargeFilesScreen()
            MODE_DUPLICATE_FILES -> renderDuplicateFilesScreen()
            else -> renderCategoryBrowserScreen()
        }
    }

    // ==================== 1. Instant MediaStore + Cached Category Browser ====================

    private fun renderCategoryBrowserScreen() {
        val t = BrightCalmTheme.of(this)
        titleTv.text = "分类文件库"
        subtitleTv.text = "高速索引缓存 · 按类型与文件夹聚合浏览"
        contentFrame.removeAllViews()

        var groupByFolder = false

        val refreshBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(4) }
            addView(BrightCalmTheme.createLinearIcon(this@EsToolboxActivity, "search", t.foreground, 20).apply {
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            })
            setOnClickListener {
                BrightCalmPanels.showGlobalSearchOverlay(this@EsToolboxActivity, null) { f ->
                    openRealFile(f)
                }
            }
        }
        val toggleGroupChip = makeChip("按文件夹", t.accent, t.primary) {
            groupByFolder = !groupByFolder
            loadCategoryFiles(groupByFolder, forceRefresh = false)
        }
        headerRightContainer.addView(refreshBtn)
        headerRightContainer.addView(toggleGroupChip)

        loadCategoryFiles(groupByFolder, forceRefresh = false)
    }

    private fun loadCategoryFiles(groupByFolder: Boolean, forceRefresh: Boolean) {
        loadJob?.cancel()
        contentFrame.removeAllViews()
        val t = BrightCalmTheme.of(this)

        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Category tabs with 14% type tint for active filter chip (§02 Rule 5)
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(t.card)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val cats = listOf(
            Triple(CAT_IMAGE, "图片", BrightCalmTheme.FileKind.IMAGE),
            Triple(CAT_VIDEO, "视频", BrightCalmTheme.FileKind.VIDEO),
            Triple(CAT_AUDIO, "音频", BrightCalmTheme.FileKind.AUDIO),
            Triple(CAT_DOC, "文档", BrightCalmTheme.FileKind.DOCUMENT),
            Triple(CAT_ARCHIVE, "压缩包", BrightCalmTheme.FileKind.ARCHIVE),
            Triple(CAT_APK, "安装包", BrightCalmTheme.FileKind.APK),
            Triple(CAT_FAVORITE, "收藏", BrightCalmTheme.FileKind.FOLDER)
        )
        cats.forEach { (key, label, kind) ->
            val active = currentCategory == key
            val typeColor = t.typeColor(kind)
            val chip = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) typeColor else t.foreground)
                background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(
                        if (active) BrightCalmTheme.withAlpha(typeColor, t.filterChipTintAlpha)
                        else t.muted
                    )
                }
                setPadding(dp(14), dp(7), dp(14), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                setOnClickListener {
                    currentCategory = key
                    loadCategoryFiles(groupByFolder, forceRefresh = false)
                }
            }
            tabRow.addView(chip)
        }
        tabScroll.addView(tabRow)
        mainCol.addView(tabScroll)
        mainCol.addView(View(this).apply {
            setBackgroundColor(t.border)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })

        val listScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val listCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        listScroll.addView(listCol)
        mainCol.addView(listScroll)
        contentFrame.addView(mainCol)

        fun renderFilesToUi(files: List<File>) {
            listCol.removeAllViews()
            subtitleTv.text = "共 ${files.size} 项"
            if (files.isEmpty()) {
                listCol.addView(
                    BrightCalmTheme.buildEmptyStateView(
                        context = this@EsToolboxActivity,
                        kind = "folder",
                        title = "这个分类还是空的",
                        subtitle = "此类别下还没有找到文件",
                        actionText = "刷新索引"
                    ) {
                        categoryCache.remove(currentCategory)
                        loadCategoryFiles(groupByFolder, forceRefresh = true)
                    }
                )
                return
            }

            val displayFiles = files.take(250)
            if (groupByFolder) {
                val grouped = displayFiles.groupBy { it.parentFile?.name ?: "根目录" }
                grouped.forEach { (folderName, folderFiles) ->
                    val folderHeader = TextView(this@EsToolboxActivity).apply {
                        text = "$folderName · ${folderFiles.size}"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(t.mutedForeground)
                        applyTabularNums()
                        setPadding(dp(4), dp(12), dp(4), dp(6))
                    }
                    listCol.addView(folderHeader)
                    val card = createCardContainer(t)
                    folderFiles.forEachIndexed { idx, f ->
                        card.addView(createFileItemRow(f, t) { loadCategoryFiles(groupByFolder, forceRefresh = true) })
                        if (idx < folderFiles.lastIndex) {
                            card.addView(createIndentedDivider(t))
                        }
                    }
                    listCol.addView(card)
                }
            } else {
                val card = createCardContainer(t)
                displayFiles.forEachIndexed { idx, f ->
                    card.addView(createFileItemRow(f, t) { loadCategoryFiles(groupByFolder, forceRefresh = true) })
                    if (idx < displayFiles.lastIndex) {
                        card.addView(createIndentedDivider(t))
                    }
                }
                listCol.addView(card)
            }
        }

        if (currentCategory == CAT_FAVORITE) {
            val favFiles = BrightCalmTheme.getFavorites(this)
                .map { File(it) }
                .filter { it.exists() }
            renderFilesToUi(favFiles)
            return
        }

        val cached = if (!forceRefresh) categoryCache[currentCategory] else null
        if (cached != null) {
            renderFilesToUi(cached)
            return
        }

        listCol.addView(TextView(this).apply {
            text = "正在快速加载..."
            setTextColor(t.mutedForeground)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(36), dp(16), dp(36))
        })

        loadJob = scope.launch {
            val files = withContext(Dispatchers.IO) {
                queryFilesFast(this@EsToolboxActivity, currentCategory, forceRefresh)
            }
            renderFilesToUi(files)
        }
    }

    // ==================== 2. Large Files & Duplicate Files ====================

    private fun renderLargeFilesScreen() {
        val t = BrightCalmTheme.of(this)
        val thresholdMb = BrightCalmTheme.getLargeFileThresholdMb(this)
        titleTv.text = "大文件清理"
        subtitleTv.text = "筛选体积 ≥ ${thresholdMb} MB 的文件"
        contentFrame.removeAllViews()

        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll.addView(col)
        contentFrame.addView(scroll)

        loadJob?.cancel()
        loadJob = scope.launch {
            val thresholdBytes = thresholdMb * 1024L * 1024L
            val largeFiles = withContext(Dispatchers.IO) {
                val all = listOf(CAT_VIDEO, CAT_ARCHIVE, CAT_APK, CAT_DOC, CAT_IMAGE, CAT_AUDIO)
                    .flatMap { queryFilesFast(this@EsToolboxActivity, it, forceRefresh = false) }
                    .distinctBy { it.absolutePath }
                all.filter { it.length() >= thresholdBytes }.sortedByDescending { it.length() }
            }
            col.removeAllViews()
            val totalBytes = largeFiles.sumOf { it.length() }
            subtitleTv.text = "${largeFiles.size} 个大文件 · 共 ${Formatter.formatFileSize(this@EsToolboxActivity, totalBytes)}"
            if (largeFiles.isEmpty()) {
                col.addView(
                    BrightCalmTheme.buildEmptyStateView(
                        context = this@EsToolboxActivity,
                        kind = "search",
                        title = "没有发现大文件",
                        subtitle = "当前存储中没有大于 ${thresholdMb} MB 的文件"
                    )
                )
            } else {
                val card = createCardContainer(t)
                largeFiles.take(150).forEachIndexed { idx, file ->
                    card.addView(createFileItemRow(file, t) { renderLargeFilesScreen() })
                    if (idx < largeFiles.take(150).lastIndex) {
                        card.addView(createIndentedDivider(t))
                    }
                }
                col.addView(card)
            }
        }
    }

    private fun renderDuplicateFilesScreen() {
        val t = BrightCalmTheme.of(this)
        titleTv.text = "重复文件识别"
        subtitleTv.text = "按相同体积与文件名特征智能归组"
        contentFrame.removeAllViews()

        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll.addView(col)
        contentFrame.addView(scroll)

        loadJob?.cancel()
        loadJob = scope.launch {
            val groups = withContext(Dispatchers.IO) {
                val all = listOf(CAT_IMAGE, CAT_VIDEO, CAT_AUDIO, CAT_DOC, CAT_ARCHIVE, CAT_APK)
                    .flatMap { queryFilesFast(this@EsToolboxActivity, it, forceRefresh = false) }
                    .distinctBy { it.absolutePath }
                    .filter { it.length() > 1024L }
                all.groupBy { "${it.length()}_${it.extension.lowercase(Locale.ROOT)}" }
                    .values
                    .filter { it.size > 1 }
                    .sortedByDescending { it.first().length() * (it.size - 1) }
            }
            col.removeAllViews()
            val redundantBytes = groups.sumOf { g -> g.first().length() * (g.size - 1) }
            subtitleTv.text = "${groups.size} 组重复 · 可释放 ${Formatter.formatFileSize(this@EsToolboxActivity, redundantBytes)}"
            if (groups.isEmpty()) {
                col.addView(
                    BrightCalmTheme.buildEmptyStateView(
                        context = this@EsToolboxActivity,
                        kind = "search",
                        title = "未发现重复文件",
                        subtitle = "您的存储空间非常整洁"
                    )
                )
            } else {
                groups.take(40).forEachIndexed { gIdx, group ->
                    col.addView(TextView(this@EsToolboxActivity).apply {
                        text = "重复组 #${gIdx + 1} · 单文件 ${Formatter.formatFileSize(this@EsToolboxActivity, group.first().length())} (${group.size} 份)"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(t.mutedForeground)
                        applyTabularNums()
                        setPadding(dp(4), dp(10), dp(4), dp(6))
                    })
                    val card = createCardContainer(t)
                    group.forEachIndexed { idx, file ->
                        card.addView(createFileItemRow(file, t) { renderDuplicateFilesScreen() })
                        if (idx < group.lastIndex) {
                            card.addView(createIndentedDivider(t))
                        }
                    }
                    col.addView(card)
                }
            }
        }
    }

    private fun createCardContainer(t: BrightCalmTheme.Tokens): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(t.card)
                setStroke(1, t.border)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

    private fun createIndentedDivider(t: BrightCalmTheme.Tokens): View =
        View(this).apply {
            setBackgroundColor(t.border)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = dp(56)
            }
        }

    private fun createFileItemRow(
        file: File,
        t: BrightCalmTheme.Tokens,
        onChanged: () -> Unit = {}
    ): View {
        val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val compact = BrightCalmTheme.isCompactList(this)
        val showExt = BrightCalmTheme.isShowExtensions(this)
        val isFav = BrightCalmTheme.isFavorite(this, file.absolutePath)

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (compact) 48 else 64)
            setPadding(dp(12), dp(if (compact) 6 else 10), dp(6), dp(if (compact) 6 else 10))

            addView(BrightCalmTheme.createFileTypeIconBox(this@EsToolboxActivity, file, 40, 12))

            val infoCol = LinearLayout(this@EsToolboxActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(12)
                }
            }
            val displayName = if (showExt || file.isDirectory) file.name else file.nameWithoutExtension.ifEmpty { file.name }
            infoCol.addView(TextView(this@EsToolboxActivity).apply {
                text = displayName
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(t.foreground)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            infoCol.addView(TextView(this@EsToolboxActivity).apply {
                val parentLabel = file.parentFile?.name ?: ""
                text = "${Formatter.formatFileSize(this@EsToolboxActivity, file.length())} · ${dateFormat.format(Date(file.lastModified()))} · $parentLabel"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(t.mutedForeground)
                applyTabularNums()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(infoCol)

            if (isFav) {
                addView(
                    BrightCalmTheme.createLinearIcon(
                        this@EsToolboxActivity,
                        "star_filled",
                        t.warning,
                        16
                    ).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                            rightMargin = dp(4)
                        }
                    }
                )
            }

            val moreBtn = FrameLayout(this@EsToolboxActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                addView(
                    BrightCalmTheme.createLinearIcon(this@EsToolboxActivity, "more", t.mutedForeground, 20).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
                    }
                )
                setOnClickListener {
                    BrightCalmPanels.showFileDetailSheet(
                        context = this@EsToolboxActivity,
                        file = file,
                        anchorForSnackbar = rootLayout,
                        onOpen = { openRealFile(it) },
                        onChanged = onChanged
                    )
                }
            }
            addView(moreBtn)

            setOnClickListener {
                openRealFile(file)
            }
        }
    }

    private fun openRealFile(file: File) {
        val path = Paths.get(file.absolutePath)
        val ext = file.extension.lowercase(Locale.ROOT)
        when {
            ext in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "ts") -> {
                startActivity(MediaPlayerActivity.createIntent(this, path, isAudio = false))
            }
            ext in setOf("mp3", "flac", "wav", "m4a", "ogg", "aac") -> {
                startActivity(MediaPlayerActivity.createIntent(this, path, isAudio = true))
            }
            ext in setOf("txt", "log", "ini", "prop", "epub") -> {
                startActivity(ReaderActivity.createIntent(this, path))
            }
            ext in setOf("pdf", "docx", "doc", "xlsx", "xls", "csv", "md", "markdown", "json", "xml") -> {
                startActivity(DocumentPreviewActivity.createIntent(this, path))
            }
            ext in setOf("zip", "rar", "7z", "tar", "apk", "jar") -> {
                startActivity(ArchivePreviewActivity.createIntent(this, path))
            }
            ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic") -> {
                startActivity(
                    ImageViewerActivity::class.createIntent().apply {
                        ImageViewerActivity.putExtras(this, listOf(path), 0)
                    }
                )
            }
            else -> {
                startActivity(DocumentPreviewActivity.createIntent(this, path))
            }
        }
    }

    // ==================== 3. App Manager ====================

    private data class AppEntry(
        val appName: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val sourceApkPath: String,
        val apkSize: Long,
        val installTime: Long,
        val isSystem: Boolean
    )

    private fun renderAppManagerScreen() {
        val t = BrightCalmTheme.of(this)
        titleTv.text = "应用管理器"
        subtitleTv.text = "APK 一键备份 · 作为 ZIP 浏览内部资源"
        contentFrame.removeAllViews()

        var activeTab = 0
        var searchQuery = ""

        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(t.card)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val searchInput = EditText(this).apply {
            hint = "搜索应用名称或包名..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(t.foreground)
            setHintTextColor(t.mutedForeground)
            setSingleLine(true)
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(t.muted)
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), dp(8), dp(16), dp(4)) }
        }

        val listScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        listScroll.addView(listContainer)

        mainCol.addView(tabRow)
        mainCol.addView(searchInput)
        mainCol.addView(listScroll)
        contentFrame.addView(mainCol)

        var allInstalledApps: List<AppEntry> = emptyList()

        fun refreshTabsAndList() {
            tabRow.removeAllViews()
            val backupDir = File(Environment.getExternalStorageDirectory(), "backups/apps")
            val backupApks = backupDir.listFiles()?.filter { it.extension.equals("apk", ignoreCase = true) } ?: emptyList()

            val userCount = allInstalledApps.count { !it.isSystem }
            val sysCount = allInstalledApps.count { it.isSystem }

            val tabs = listOf(
                0 to "用户应用 ($userCount)",
                1 to "系统应用 ($sysCount)",
                2 to "已备份 APK (${backupApks.size})"
            )

            tabs.forEach { (idx, label) ->
                val selected = activeTab == idx
                val btn = TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (selected) t.primary else t.foreground)
                    applyTabularNums()
                    background = GradientDrawable().apply {
                        cornerRadius = dp(999).toFloat()
                        setColor(if (selected) BrightCalmTheme.withAlpha(t.primary, 0.14f) else t.muted)
                    }
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(6)
                    }
                    setOnClickListener {
                        activeTab = idx
                        refreshTabsAndList()
                    }
                }
                tabRow.addView(btn)
            }

            listContainer.removeAllViews()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            if (activeTab == 2) {
                if (backupApks.isEmpty()) {
                    listContainer.addView(
                        BrightCalmTheme.buildEmptyStateView(
                            context = this,
                            kind = "folder",
                            title = "暂无已备份的 APK",
                            subtitle = "点击任意应用可一键备份至 /backups/apps/"
                        )
                    )
                } else {
                    val card = createCardContainer(t)
                    backupApks.forEachIndexed { idx, apkFile ->
                        card.addView(createFileItemRow(apkFile, t) { refreshTabsAndList() })
                        if (idx < backupApks.lastIndex) {
                            card.addView(createIndentedDivider(t))
                        }
                    }
                    listContainer.addView(card)
                }
                return
            }

            val filtered = allInstalledApps.filter { app ->
                val tabMatch = if (activeTab == 0) !app.isSystem else app.isSystem
                val qMatch = searchQuery.isBlank() ||
                    app.appName.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)
                tabMatch && qMatch
            }

            if (filtered.isEmpty()) {
                listContainer.addView(
                    BrightCalmTheme.buildEmptyStateView(
                        context = this,
                        kind = "search",
                        title = "没有找到相关应用",
                        subtitle = "尝试更换关键词"
                    )
                )
                return
            }

            val card = createCardContainer(t)
            filtered.forEachIndexed { idx, app ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(64)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setOnClickListener {
                        showAppActionSheet(app) { refreshTabsAndList() }
                    }
                }

                val iconView = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        rightMargin = dp(12)
                    }
                    try {
                        setImageDrawable(packageManager.getApplicationIcon(app.packageName))
                    } catch (_: Exception) {
                    }
                }
                row.addView(iconView)

                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                col.addView(TextView(this).apply {
                    text = app.appName
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(t.foreground)
                })
                col.addView(TextView(this).apply {
                    text = "${app.packageName} · v${app.versionName} · ${Formatter.formatFileSize(this@EsToolboxActivity, app.apkSize)} · ${dateFormat.format(Date(app.installTime))}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(t.mutedForeground)
                    applyTabularNums()
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                row.addView(col)

                card.addView(row)
                if (idx < filtered.lastIndex) {
                    card.addView(createIndentedDivider(t))
                }
            }
            listContainer.addView(card)
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                refreshTabsAndList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadJob?.cancel()
        loadJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val pm = packageManager
                val pkgs = pm.getInstalledPackages(0)
                pkgs.mapNotNull { pkg: PackageInfo ->
                    val ai = pkg.applicationInfo ?: return@mapNotNull null
                    val name = pm.getApplicationLabel(ai)?.toString() ?: pkg.packageName
                    val srcPath = ai.sourceDir ?: ""
                    val size = if (srcPath.isNotEmpty()) File(srcPath).length() else 0L
                    val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    @Suppress("DEPRECATION")
                    val vCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong()
                    AppEntry(
                        appName = name,
                        packageName = pkg.packageName,
                        versionName = pkg.versionName ?: "1.0",
                        versionCode = vCode,
                        sourceApkPath = srcPath,
                        apkSize = size,
                        installTime = pkg.firstInstallTime,
                        isSystem = isSys
                    )
                }.sortedBy { it.appName.lowercase(Locale.ROOT) }
            }
            allInstalledApps = loaded
            refreshTabsAndList()
        }
    }

    private fun showAppActionSheet(app: AppEntry, onBackupDone: () -> Unit) {
        val actions = arrayOf(
            "打开应用 (${app.appName})",
            "备份 APK 到 /backups/apps/${app.appName}_${app.versionName}.apk",
            "作为 ZIP 浏览 APK 内部资源",
            "打开系统应用信息页 / 卸载"
        )
        AlertDialog.Builder(this)
            .setTitle("${app.appName} (v${app.versionName})")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                        if (launchIntent != null) {
                            startActivity(launchIntent)
                        } else {
                            Toast.makeText(this, "该应用无主启动入口", Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> {
                        scope.launch {
                            val destFile = withContext(Dispatchers.IO) {
                                val backupDir = File(Environment.getExternalStorageDirectory(), "backups/apps")
                                backupDir.mkdirs()
                                val safeName = app.appName.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_")
                                val target = File(backupDir, "${safeName}_${app.versionName}.apk")
                                File(app.sourceApkPath).copyTo(target, overwrite = true)
                                target
                            }
                            Toast.makeText(this@EsToolboxActivity, "已备份至: ${destFile.absolutePath}", Toast.LENGTH_LONG).show()
                            onBackupDone()
                        }
                    }
                    2 -> {
                        if (app.sourceApkPath.isNotEmpty() && File(app.sourceApkPath).exists()) {
                            startActivity(ArchivePreviewActivity.createIntent(this, Paths.get(app.sourceApkPath)))
                        } else {
                            Toast.makeText(this, "无法读取 APK 路径", Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", app.packageName, null)
                        }
                        startActivity(intent)
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ==================== 4. Recycle Bin ====================

    private fun renderRecycleBinScreen() {
        val t = BrightCalmTheme.of(this)
        titleTv.text = "回收站"
        subtitleTv.text = "暂存已移入回收站的文件，支持还原或彻底清空"
        contentFrame.removeAllViews()

        val recycleDir = File(Environment.getExternalStorageDirectory(), ".es_recycle_bin").apply { mkdirs() }

        headerRightContainer.removeAllViews()
        headerRightContainer.addView(
            makeChip("清空回收站", BrightCalmTheme.withAlpha(t.destructive, 0.14f), t.destructive) {
                val items = recycleDir.listFiles()?.toList() ?: emptyList()
                if (items.isEmpty()) {
                    Toast.makeText(this, "回收站已为空", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("清空回收站")
                        .setMessage("确定要永久删除回收站中的 ${items.size} 个项目吗？此操作不可恢复。")
                        .setPositiveButton("永久删除") { _, _ ->
                            items.forEach { it.deleteRecursively() }
                            renderRecycleBinScreen()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        )

        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        val items = recycleDir.listFiles()?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (items.isEmpty()) {
            col.addView(
                BrightCalmTheme.buildEmptyStateView(
                    context = this,
                    kind = "folder",
                    title = "回收站当前为空",
                    subtitle = "被删除的文件将在此保留，可随时一键还原"
                )
            )
        } else {
            val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val card = createCardContainer(t)
            items.forEachIndexed { idx, f ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(64)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
                row.addView(BrightCalmTheme.createFileTypeIconBox(this, f, 40, 12))
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(12)
                    }
                }
                info.addView(TextView(this).apply {
                    text = f.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(t.foreground)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                info.addView(TextView(this).apply {
                    text = "${Formatter.formatFileSize(this@EsToolboxActivity, f.length())} · 删除于 ${df.format(Date(f.lastModified()))}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(t.mutedForeground)
                    applyTabularNums()
                })
                row.addView(info)

                row.addView(makeChip("还原", BrightCalmTheme.withAlpha(t.success, 0.14f), t.success) {
                    val restoreTarget = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), f.name)
                    f.renameTo(restoreTarget)
                    Toast.makeText(this, "已还原至 Download/${f.name}", Toast.LENGTH_SHORT).show()
                    renderRecycleBinScreen()
                })
                card.addView(row)
                if (idx < items.lastIndex) {
                    card.addView(createIndentedDivider(t))
                }
            }
            col.addView(card)
        }

        scroll.addView(col)
        contentFrame.addView(scroll)
    }

    // ==================== 5. Private Encryption Vault ====================

    private fun renderEncryptionVaultScreen() {
        val t = BrightCalmTheme.of(this)
        titleTv.text = "私密保险箱"
        subtitleTv.text = "AES 加密隔离 · 保护私密文档、照片与压缩包"
        contentFrame.removeAllViews()

        val vaultDir = File(filesDir, "es_encrypted_vault").apply { mkdirs() }
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(BrightCalmTheme.withAlpha(t.primary, 0.12f))
                setStroke(1, BrightCalmTheme.withAlpha(t.primary, 0.25f))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        banner.addView(TextView(this).apply {
            text = "私密保险箱已解锁"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(t.primary)
        })
        banner.addView(TextView(this).apply {
            text = "保险箱路径隔离于应用私有加密区 (${vaultDir.listFiles()?.size ?: 0} 个受保护文件)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(t.mutedForeground)
            applyTabularNums()
            setPadding(0, dp(4), 0, 0)
        })
        col.addView(banner)

        val vaultFiles = vaultDir.listFiles()?.toList() ?: emptyList()
        if (vaultFiles.isEmpty()) {
            col.addView(
                BrightCalmTheme.buildEmptyStateView(
                    context = this,
                    kind = "permission",
                    title = "保险箱中暂无加密文件",
                    subtitle = "您可在文件详情或压缩选项中将私密文件移入此处"
                )
            )
        } else {
            val card = createCardContainer(t)
            vaultFiles.forEachIndexed { idx, vf ->
                card.addView(createFileItemRow(vf, t) { renderEncryptionVaultScreen() })
                if (idx < vaultFiles.lastIndex) {
                    card.addView(createIndentedDivider(t))
                }
            }
            col.addView(card)
        }

        scroll.addView(col)
        contentFrame.addView(scroll)
    }

    private fun makeChip(label: String, bgColor: Int, fgColor: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(fgColor)
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(bgColor)
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_CATEGORY = "extra_category"

        const val MODE_CATEGORY = "mode_category"
        const val MODE_APP_MANAGER = "mode_app_manager"
        const val MODE_RECYCLE_BIN = "mode_recycle_bin"
        const val MODE_VAULT = "mode_vault"
        const val MODE_LARGE_FILES = "mode_large_files"
        const val MODE_DUPLICATE_FILES = "mode_duplicate_files"

        const val CAT_IMAGE = "cat_image"
        const val CAT_VIDEO = "cat_video"
        const val CAT_AUDIO = "cat_audio"
        const val CAT_DOC = "cat_doc"
        const val CAT_ARCHIVE = "cat_archive"
        const val CAT_APK = "cat_apk"
        const val CAT_FAVORITE = "cat_favorite"

        // In-memory category cache so opening Image / Video / Audio / Doc categories is instantaneous
        private val categoryCache = ConcurrentHashMap<String, List<File>>()

        fun invalidateCategoryCache() {
            categoryCache.clear()
        }

        fun prewarmCategoryCache(context: Context) {
            listOf(CAT_IMAGE, CAT_VIDEO, CAT_AUDIO, CAT_DOC, CAT_ARCHIVE, CAT_APK).forEach { cat ->
                if (!categoryCache.containsKey(cat)) {
                    runCatching { queryFilesFast(context, cat, forceRefresh = false) }
                }
            }
        }

        fun createCategoryIntent(context: Context, category: String): Intent =
            Intent(context, EsToolboxActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_CATEGORY)
                putExtra(EXTRA_CATEGORY, category)
            }

        fun createModeIntent(context: Context, mode: String): Intent =
            Intent(context, EsToolboxActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode)
            }

        private fun getCategoryExtensions(category: String): Set<String> =
            when (category) {
                CAT_IMAGE -> setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
                CAT_VIDEO -> setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "ts")
                CAT_AUDIO -> setOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "opus")
                CAT_DOC -> setOf("pdf", "docx", "doc", "xlsx", "xls", "csv", "md", "markdown", "txt", "epub", "pptx", "ppt")
                CAT_ARCHIVE -> setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
                CAT_APK -> setOf("apk", "apks", "xapk")
                else -> emptySet()
            }

        /**
         * Instant MediaStore query + targeted common user directories scan with memory caching.
         */
        fun queryFilesFast(context: Context, category: String, forceRefresh: Boolean = false): List<File> {
            if (category == CAT_FAVORITE) {
                return BrightCalmTheme.getFavorites(context).map { File(it) }.filter { it.exists() }
            }
            if (!forceRefresh) {
                categoryCache[category]?.let { return it }
            }

            val exts = getCategoryExtensions(category)
            val seenPaths = HashSet<String>()
            val results = ArrayList<File>()

            // 1. Millisecond-level MediaStore indexed query
            runCatching {
                val contentUri = when (category) {
                    CAT_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    CAT_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    CAT_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri("external")
                }
                val projection = arrayOf(
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.SIZE
                )
                val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                context.contentResolver.query(contentUri, projection, null, null, sortOrder)?.use { cursor ->
                    val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                        if (size <= 0L || dataIdx < 0) continue
                        val pathStr = cursor.getString(dataIdx) ?: continue
                        val dotIdx = pathStr.lastIndexOf('.')
                        val ext = if (dotIdx >= 0) pathStr.substring(dotIdx + 1).lowercase(Locale.ROOT) else ""
                        if (ext in exts && seenPaths.add(pathStr)) {
                            val f = File(pathStr)
                            if (f.exists() && f.isFile && !f.name.startsWith(".")) {
                                results.add(f)
                            }
                        }
                    }
                }
            }

            // 2. Fast targeted scan of standard user folders (depth <= 2) for newly added unindexed files
            val root = Environment.getExternalStorageDirectory()
            val targetFolders = listOfNotNull(
                root,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            ).distinctBy { it.absolutePath }

            fun quickScan(dir: File, maxDepth: Int, currentDepth: Int) {
                if (currentDepth > maxDepth) return
                val list = dir.listFiles() ?: return
                for (f in list) {
                    if (f.name.startsWith(".")) continue
                    if (f.isFile && f.length() > 0) {
                        if (f.extension.lowercase(Locale.ROOT) in exts && seenPaths.add(f.absolutePath)) {
                            results.add(f)
                        }
                    } else if (f.isDirectory && currentDepth < maxDepth) {
                        val lower = f.name.lowercase(Locale.ROOT)
                        if (lower != "android" && lower != "cache" && lower != ".thumbnails") {
                            quickScan(f, maxDepth, currentDepth + 1)
                        }
                    }
                }
            }

            runCatching {
                for (folder in targetFolders) {
                    if (folder.exists() && folder.isDirectory) {
                        val depthLimit = if (folder.absolutePath == root.absolutePath) 1 else 2
                        quickScan(folder, depthLimit, 0)
                    }
                }
            }

            val sorted = results.sortedByDescending { it.lastModified() }
            categoryCache[category] = sorted
            return sorted
        }

        fun scanFilesByCategory(category: String): List<File> {
            categoryCache[category]?.let { return it }
            val exts = getCategoryExtensions(category)
            val results = mutableListOf<File>()
            val root = Environment.getExternalStorageDirectory()

            fun walk(dir: File, depth: Int) {
                if (depth > 3 || results.size >= 150) return
                val list = dir.listFiles() ?: return
                for (f in list) {
                    if (f.name.startsWith(".")) continue
                    if (f.isFile && f.length() > 0) {
                        if (f.extension.lowercase(Locale.ROOT) in exts) {
                            results.add(f)
                        }
                    } else if (f.isDirectory && !f.name.equals("Android", ignoreCase = true)) {
                        walk(f, depth + 1)
                    }
                }
            }

            runCatching {
                if (root.exists()) {
                    walk(root, 0)
                }
            }
            val sorted = results.sortedByDescending { it.lastModified() }
            categoryCache[category] = sorted
            return sorted
        }
    }
}
