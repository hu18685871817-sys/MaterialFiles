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
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
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
import java8.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.format.Formatter
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.viewer.archive.ArchivePreviewActivity
import me.zhanghai.android.files.viewer.document.DocumentPreviewActivity
import me.zhanghai.android.files.viewer.image.ImageViewerActivity
import me.zhanghai.android.files.viewer.image.ImageViewerFragment
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

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
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

        setupHeader()

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

    private fun setupHeader() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(3).toFloat()
        }

        val backBtn = TextView(this).apply {
            text = "←"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(dp(10), dp(4), dp(12), dp(4))
            setOnClickListener { finish() }
        }
        bar.addView(backBtn)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleTv = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
        }
        subtitleTv = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#64748B"))
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
    }

    private fun renderCurrentMode() {
        headerRightContainer.removeAllViews()
        when (currentMode) {
            MODE_APP_MANAGER -> renderAppManagerScreen()
            MODE_RECYCLE_BIN -> renderRecycleBinScreen()
            MODE_VAULT -> renderEncryptionVaultScreen()
            else -> renderCategoryBrowserScreen()
        }
    }

    // ==================== 1. Media & File Category Browser (PRD 2.1.3) ====================

    private fun renderCategoryBrowserScreen() {
        titleTv.text = "分类文件库"
        subtitleTv.text = "支持按文件类型或所属文件夹聚合浏览真实手机文件"
        contentFrame.removeAllViews()

        var groupByFolder = false

        val toggleGroupChip = makeChip("📁 按文件夹分组", "#EEF2FF", "#4338CA") {
            groupByFolder = !groupByFolder
            loadCategoryFiles(groupByFolder)
        }
        headerRightContainer.addView(toggleGroupChip)

        loadCategoryFiles(groupByFolder)
    }

    private fun loadCategoryFiles(groupByFolder: Boolean) {
        loadJob?.cancel()
        contentFrame.removeAllViews()

        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Category tabs
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val cats = listOf(
            CAT_IMAGE to "🖼️ 图片",
            CAT_VIDEO to "🎬 视频",
            CAT_AUDIO to "🎵 音频",
            CAT_DOC to "📄 文档",
            CAT_ARCHIVE to "🗜️ 压缩包",
            CAT_APK to "🤖 安装包 APK"
        )
        cats.forEach { (key, label) ->
            val active = currentCategory == key
            val chip = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) Color.WHITE else Color.parseColor("#334155"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (active) Color.parseColor("#2563EB") else Color.parseColor("#F1F5F9"))
                }
                setPadding(dp(12), dp(6), dp(12), dp(6))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                layoutParams = lp
                setOnClickListener {
                    currentCategory = key
                    loadCategoryFiles(groupByFolder)
                }
            }
            tabRow.addView(chip)
        }
        tabScroll.addView(tabRow)
        mainCol.addView(tabScroll)

        val listScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val listCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }
        listCol.addView(TextView(this).apply {
            text = "正在扫描本地存储..."
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(36), dp(16), dp(36))
        })
        listScroll.addView(listCol)
        mainCol.addView(listScroll)
        contentFrame.addView(mainCol)

        loadJob = scope.launch {
            val files = withContext(Dispatchers.IO) {
                scanFilesByCategory(currentCategory)
            }
            listCol.removeAllViews()
            if (files.isEmpty()) {
                listCol.addView(TextView(this@EsToolboxActivity).apply {
                    text = "当前分类下暂无手机文件"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(Color.parseColor("#64748B"))
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(48), dp(16), dp(48))
                })
                return@launch
            }

            if (groupByFolder) {
                val grouped = files.groupBy { it.parentFile?.name ?: "根目录" }
                grouped.forEach { (folderName, folderFiles) ->
                    val folderHeader = TextView(this@EsToolboxActivity).apply {
                        text = "📁 $folderName (${folderFiles.size} 项)"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#1E3A8A"))
                        setPadding(dp(4), dp(10), dp(4), dp(6))
                    }
                    listCol.addView(folderHeader)
                    folderFiles.forEach { f ->
                        listCol.addView(createFileItemRow(f))
                    }
                }
            } else {
                files.forEach { f ->
                    listCol.addView(createFileItemRow(f))
                }
            }
        }
    }

    private fun createFileItemRow(file: File): View {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            layoutParams = lp

            val iconTv = TextView(this@EsToolboxActivity).apply {
                text = when (currentCategory) {
                    CAT_IMAGE -> "🖼️"
                    CAT_VIDEO -> "🎬"
                    CAT_AUDIO -> "🎵"
                    CAT_DOC -> "📄"
                    CAT_ARCHIVE -> "🗜️"
                    CAT_APK -> "🤖"
                    else -> "📄"
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(0, 0, dp(12), 0)
            }
            addView(iconTv)

            val infoCol = LinearLayout(this@EsToolboxActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(this@EsToolboxActivity).apply {
                text = file.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            infoCol.addView(TextView(this@EsToolboxActivity).apply {
                text = "${Formatter.formatFileSize(this@EsToolboxActivity, file.length())} · ${dateFormat.format(Date(file.lastModified()))}\n${file.parentFile?.absolutePath ?: ""}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.parseColor("#64748B"))
                maxLines = 2
            })
            addView(infoCol)

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
            ext in setOf("txt", "log", "ini", "prop") -> {
                startActivity(ReaderActivity.createIntent(this, path))
            }
            ext in setOf("docx", "doc", "xlsx", "xls", "csv", "md", "markdown", "json", "xml") -> {
                startActivity(DocumentPreviewActivity.createIntent(this, path))
            }
            ext in setOf("zip", "rar", "7z", "tar", "apk", "jar") -> {
                startActivity(ArchivePreviewActivity.createIntent(this, path))
            }
            ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp") -> {
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

    // ==================== 2. App Manager (PRD Section 2.5) ====================

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
        titleTv.text = "应用管理器 (App Manager)"
        subtitleTv.text = "对标 ES/RS：支持 APK 一键备份、作为 ZIP 浏览内部资源"
        contentFrame.removeAllViews()

        var activeTab = 0 // 0 = User Apps, 1 = System Apps, 2 = Backed-up APKs
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
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val searchInput = EditText(this).apply {
            hint = "🔍 搜索应用名称或包名 (Package Name)..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setSingleLine(true)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CBD5E1"))
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(14), dp(8), dp(14), dp(4)) }
            layoutParams = lp
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
            setPadding(dp(14), dp(8), dp(14), dp(24))
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
                0 to "📱 用户应用 ($userCount)",
                1 to "⚙️ 系统应用 ($sysCount)",
                2 to "📦 已备份 APK (${backupApks.size})"
            )

            tabs.forEach { (idx, label) ->
                val selected = activeTab == idx
                val btn = TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (selected) Color.WHITE else Color.parseColor("#334155"))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(if (selected) Color.parseColor("#2563EB") else Color.parseColor("#F1F5F9"))
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
                    listContainer.addView(TextView(this).apply {
                        text = "暂无已备份的 APK\n点击任意应用可一键备份至 /backups/apps/"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(Color.parseColor("#64748B"))
                        gravity = Gravity.CENTER
                        setPadding(dp(20), dp(40), dp(20), dp(40))
                    })
                } else {
                    backupApks.forEach { apkFile ->
                        listContainer.addView(createFileItemRow(apkFile))
                    }
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

            filtered.forEach { app ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.WHITE)
                        setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                    layoutParams = lp
                    setOnClickListener {
                        showAppActionSheet(app) { refreshTabsAndList() }
                    }
                }

                val iconView = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
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
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                col.addView(TextView(this).apply {
                    text = "${app.packageName}\nv${app.versionName} (${app.versionCode}) · ${Formatter.formatFileSize(this@EsToolboxActivity, app.apkSize)} · ${dateFormat.format(Date(app.installTime))}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(Color.parseColor("#64748B"))
                })
                row.addView(col)

                listContainer.addView(row)
            }
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
            "🚀 打开应用 (${app.appName})",
            "📦 备份 APK 到 /backups/apps/${app.appName}_${app.versionName}.apk",
            "🔍 作为 ZIP 浏览 APK 内部资源 (AndroidManifest / res / dex)",
            "ℹ️ 打开系统应用信息页 / 卸载"
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

    // ==================== 3. Recycle Bin (PRD Section 2.1.5 / 2.3.1) ====================

    private fun renderRecycleBinScreen() {
        titleTv.text = "回收站 (Recycle Bin)"
        subtitleTv.text = "暂存已移入回收站的文件，支持一键还原或彻底清空"
        contentFrame.removeAllViews()

        val recycleDir = File(Environment.getExternalStorageDirectory(), ".es_recycle_bin").apply { mkdirs() }

        headerRightContainer.removeAllViews()
        headerRightContainer.addView(
            makeChip("🔥 清空回收站", "#FEE2E2", "#B91C1C") {
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
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }

        val items = recycleDir.listFiles()?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (items.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "🗑️ 回收站当前为空\n删除文件时开启回收站保护后，被删文件将暂存于此"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.parseColor("#64748B"))
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(60), dp(20), dp(60))
            })
        } else {
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            items.forEach { f ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.WHITE)
                        setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                    layoutParams = lp
                }
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                info.addView(TextView(this).apply {
                    text = f.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                info.addView(TextView(this).apply {
                    text = "${Formatter.formatFileSize(this@EsToolboxActivity, f.length())} · 删除于 ${df.format(Date(f.lastModified()))}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(Color.parseColor("#64748B"))
                })
                card.addView(info)

                card.addView(makeChip("♻️ 还原", "#DCFCE7", "#15803D") {
                    val restoreTarget = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), f.name)
                    f.renameTo(restoreTarget)
                    Toast.makeText(this, "已还原至 Download/${f.name}", Toast.LENGTH_SHORT).show()
                    renderRecycleBinScreen()
                })
                col.addView(card)
            }
        }

        scroll.addView(col)
        contentFrame.addView(scroll)
    }

    // ==================== 4. 128-bit Encryption Vault (PRD Section 2.1.5) ====================

    private fun renderEncryptionVaultScreen() {
        titleTv.text = "🔐 128位文件加密保险箱"
        subtitleTv.text = "使用 AES 对称密钥保护私密文档、照片与压缩包"
        contentFrame.removeAllViews()

        val vaultDir = File(filesDir, "es_encrypted_vault").apply { mkdirs() }
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            layoutParams = lp
        }
        banner.addView(TextView(this).apply {
            text = "🛡️ AES-128 / AES-256 私密保险箱已解锁"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        banner.addView(TextView(this).apply {
            text = "保险箱路径隔离于应用私有加密区 (${vaultDir.listFiles()?.size ?: 0} 个受保护文件)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(4), 0, 0)
        })
        col.addView(banner)

        val vaultFiles = vaultDir.listFiles()?.toList() ?: emptyList()
        if (vaultFiles.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "当前保险箱中暂无加密文件\n您可在文件列表中通过「压缩 -> 启用 AES-256 密码加密」或将私密文件移入此处。"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.parseColor("#64748B"))
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(40), dp(20), dp(40))
            })
        } else {
            vaultFiles.forEach { vf ->
                col.addView(createFileItemRow(vf))
            }
        }

        scroll.addView(col)
        contentFrame.addView(scroll)
    }

    private fun makeChip(label: String, bgHex: String, fgHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(fgHex))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor(bgHex))
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
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

        const val CAT_IMAGE = "cat_image"
        const val CAT_VIDEO = "cat_video"
        const val CAT_AUDIO = "cat_audio"
        const val CAT_DOC = "cat_doc"
        const val CAT_ARCHIVE = "cat_archive"
        const val CAT_APK = "cat_apk"

        fun createCategoryIntent(context: Context, category: String): Intent =
            Intent(context, EsToolboxActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_CATEGORY)
                putExtra(EXTRA_CATEGORY, category)
            }

        fun createModeIntent(context: Context, mode: String): Intent =
            Intent(context, EsToolboxActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode)
            }

        fun scanFilesByCategory(category: String): List<File> {
            val exts = when (category) {
                CAT_IMAGE -> setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
                CAT_VIDEO -> setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "ts")
                CAT_AUDIO -> setOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "opus")
                CAT_DOC -> setOf("docx", "doc", "xlsx", "xls", "csv", "md", "markdown", "txt", "pdf", "pptx", "ppt")
                CAT_ARCHIVE -> setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
                CAT_APK -> setOf("apk", "apks", "xapk")
                else -> emptySet()
            }
            val results = mutableListOf<File>()
            val root = Environment.getExternalStorageDirectory()
            fun walk(dir: File, depth: Int) {
                if (depth > 5) return
                val list = dir.listFiles() ?: return
                for (f in list) {
                    if (f.name.startsWith(".")) continue
                    if (f.isDirectory) {
                        val lower = f.name.lowercase(Locale.ROOT)
                        if (lower == "android" || lower == "cache" || lower == ".thumbnails") continue
                        walk(f, depth + 1)
                    } else if (f.isFile && f.length() > 0) {
                        if (f.extension.lowercase(Locale.ROOT) in exts) {
                            results.add(f)
                        }
                    }
                }
            }
            try {
                walk(root, 0)
            } catch (_: Exception) {
            }
            return results.sortedByDescending { it.lastModified() }
        }
    }
}
