/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.document

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import android.text.format.Formatter
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.viewer.reader.ReaderActivity

class DocumentPreviewActivity : AppActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanJob: Job? = null

    private lateinit var rootContainer: LinearLayout
    private lateinit var headerBar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var headerActionsLayout: LinearLayout
    private lateinit var bodyContainer: FrameLayout

    private var currentDocTitle: String = ""
    private var currentDocExtension: String = ""
    private var currentDocBytes: ByteArray? = null
    private var currentLocalFile: File? = null
    private var isInBrowserMode: Boolean = false
    private var launchedWithDirectFile: Boolean = false

    // Markdown & Text editor states
    private var isMarkdownEditMode: Boolean = false
    private var currentTextEncoding: Charset = Charsets.UTF_8
    private var showLineNumbers: Boolean = true

    // Phone document browser state
    private var selectedCategoryFilter: String = "ALL" // ALL, WORD, EXCEL, MD, TXT
    private var scannedDocs: List<File> = emptyList()
    private var currentBrowseDir: File = Environment.getExternalStorageDirectory()
    private var isFolderBrowseMode: Boolean = false

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            loadFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
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

        bodyContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rootContainer.addView(bodyContainer)

        setContentView(rootContainer)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isInBrowserMode && !launchedWithDirectFile) {
                    showPhoneDocumentBrowser()
                } else if (isInBrowserMode && isFolderBrowseMode &&
                    currentBrowseDir.absolutePath != Environment.getExternalStorageDirectory().absolutePath &&
                    currentBrowseDir.parentFile != null
                ) {
                    currentBrowseDir = currentBrowseDir.parentFile!!
                    renderPhoneDocumentBrowser()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Check incoming file intent
        val inMemoryBytes = intent.getByteArrayExtra(EXTRA_IN_MEMORY_BYTES)
        val inMemoryName = intent.getStringExtra(EXTRA_IN_MEMORY_NAME)
        val extraPath = intent.extraPath
        val dataUri = intent.data

        when {
            inMemoryBytes != null && !inMemoryName.isNullOrBlank() -> {
                launchedWithDirectFile = true
                openDocumentFromBytes(inMemoryName, inMemoryBytes, null)
            }
            extraPath != null -> {
                launchedWithDirectFile = true
                loadFromPath(extraPath)
            }
            dataUri != null -> {
                launchedWithDirectFile = true
                loadFromUri(dataUri)
            }
            else -> {
                launchedWithDirectFile = false
                showPhoneDocumentBrowser()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun setupHeader() {
        headerBar = LinearLayout(this).apply {
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
            setPadding(dp(10), dp(6), dp(12), dp(6))
            setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
        }
        headerBar.addView(backBtn)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleText = TextView(this).apply {
            text = "Office 与 Markdown 文档阅读器"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleText = TextView(this).apply {
            text = "浏览并打开手机存储中的真实文档"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#64748B"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(titleText)
        titleCol.addView(subtitleText)
        headerBar.addView(titleCol)

        headerActionsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerBar.addView(headerActionsLayout)

        rootContainer.addView(headerBar)
    }

    private fun createHeaderActionChip(label: String, bgColor: String, textColor: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(textColor))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor(bgColor))
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(6)
            }
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    // ==================== Real Phone Document Browser ====================

    private fun showPhoneDocumentBrowser() {
        isInBrowserMode = true
        titleText.text = "手机文档与 Markdown 浏览器"
        subtitleText.text = "扫描手机本地 DOCX / XLSX / CSV / MD / TXT 文件"
        headerActionsLayout.removeAllViews()

        headerActionsLayout.addView(
            createHeaderActionChip(
                if (isFolderBrowseMode) "📑 按类型分类" else "📁 按文件夹浏览",
                "#EEF2FF",
                "#4338CA"
            ) {
                isFolderBrowseMode = !isFolderBrowseMode
                renderPhoneDocumentBrowser()
            }
        )
        headerActionsLayout.addView(
            createHeaderActionChip("📂 系统选文件", "#E0F2FE", "#0369A1") {
                openDocumentLauncher.launch(arrayOf("*/*"))
            }
        )

        if (scannedDocs.isEmpty()) {
            scanPhoneDocuments()
        } else {
            renderPhoneDocumentBrowser()
        }
    }

    private fun scanPhoneDocuments() {
        scanJob?.cancel()
        bodyContainer.removeAllViews()
        val loadingText = TextView(this).apply {
            text = "正在扫描手机存储中的 Office / Markdown / 文本文件..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(48))
        }
        bodyContainer.addView(loadingText)

        scanJob = scope.launch {
            val found = withContext(Dispatchers.IO) {
                val results = mutableListOf<File>()
                val root = Environment.getExternalStorageDirectory()
                val supportedExts = setOf("docx", "doc", "xlsx", "xls", "csv", "md", "markdown", "txt", "json", "xml", "log", "ini", "yaml", "yml")

                fun walk(dir: File, depth: Int) {
                    if (depth > 6) return
                    val children = dir.listFiles() ?: return
                    for (child in children) {
                        if (child.name.startsWith(".")) continue
                        if (child.isDirectory) {
                            val lower = child.name.lowercase(Locale.ROOT)
                            if (lower == "android" || lower == "cache" || lower == ".thumbnails") continue
                            walk(child, depth + 1)
                        } else if (child.isFile && child.length() > 0) {
                            val ext = child.extension.lowercase(Locale.ROOT)
                            if (ext in supportedExts) {
                                results.add(child)
                            }
                        }
                    }
                }
                try {
                    walk(root, 0)
                } catch (_: Exception) {
                }
                results.sortedByDescending { it.lastModified() }
            }
            scannedDocs = found
            renderPhoneDocumentBrowser()
        }
    }

    private fun renderPhoneDocumentBrowser() {
        bodyContainer.removeAllViews()
        headerActionsLayout.removeAllViews()
        headerActionsLayout.addView(
            createHeaderActionChip(
                if (isFolderBrowseMode) "📑 按类型分类" else "📁 目录树浏览",
                "#EEF2FF",
                "#4338CA"
            ) {
                isFolderBrowseMode = !isFolderBrowseMode
                renderPhoneDocumentBrowser()
            }
        )
        headerActionsLayout.addView(
            createHeaderActionChip("📂 系统选择器", "#E0F2FE", "#0369A1") {
                openDocumentLauncher.launch(arrayOf("*/*"))
            }
        )

        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        if (isFolderBrowseMode) {
            renderFolderExplorerMode(mainCol)
        } else {
            renderCategorizedDocListMode(mainCol)
        }

        bodyContainer.addView(mainCol)
    }

    private fun renderCategorizedDocListMode(mainCol: LinearLayout) {
        // Filter chip bar
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val filters = listOf(
            "ALL" to "全部文档 (${scannedDocs.size})",
            "WORD" to "📘 Word (${scannedDocs.count { it.extension.lowercase(Locale.ROOT) in setOf("docx", "doc") }})",
            "EXCEL" to "📗 Excel/CSV (${scannedDocs.count { it.extension.lowercase(Locale.ROOT) in setOf("xlsx", "xls", "csv") }})",
            "MD" to "🟣 Markdown (${scannedDocs.count { it.extension.lowercase(Locale.ROOT) in setOf("md", "markdown") }})",
            "TXT" to "📄 TXT/代码 (${scannedDocs.count { it.extension.lowercase(Locale.ROOT) !in setOf("docx", "doc", "xlsx", "xls", "csv", "md", "markdown") }})"
        )

        filters.forEach { (key, label) ->
            val selected = selectedCategoryFilter == key
            val chip = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#334155"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (selected) Color.parseColor("#2563EB") else Color.parseColor("#F1F5F9"))
                }
                setPadding(dp(12), dp(6), dp(12), dp(6))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                layoutParams = lp
                setOnClickListener {
                    selectedCategoryFilter = key
                    renderPhoneDocumentBrowser()
                }
            }
            filterRow.addView(chip)
        }

        val refreshChip = TextView(this).apply {
            text = "🔄 重新扫描"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#0284C7"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { scanPhoneDocuments() }
        }
        filterRow.addView(refreshChip)
        filterScroll.addView(filterRow)
        mainCol.addView(filterScroll)

        // Search input
        var searchQuery = ""
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(24))
        }

        val searchInput = EditText(this).apply {
            hint = "🔍 搜索手机中的文件名或路径..."
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
            ).apply {
                setMargins(dp(14), dp(8), dp(14), dp(4))
            }
            layoutParams = lp
        }
        mainCol.addView(searchInput)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        scroll.addView(listContainer)
        mainCol.addView(scroll)

        fun populateFilteredDocs() {
            listContainer.removeAllViews()
            val filtered = scannedDocs.filter { file ->
                val ext = file.extension.lowercase(Locale.ROOT)
                val catMatch = when (selectedCategoryFilter) {
                    "WORD" -> ext in setOf("docx", "doc")
                    "EXCEL" -> ext in setOf("xlsx", "xls", "csv")
                    "MD" -> ext in setOf("md", "markdown")
                    "TXT" -> ext !in setOf("docx", "doc", "xlsx", "xls", "csv", "md", "markdown")
                    else -> true
                }
                val queryMatch = searchQuery.isBlank() ||
                    file.name.contains(searchQuery, ignoreCase = true) ||
                    file.absolutePath.contains(searchQuery, ignoreCase = true)
                catMatch && queryMatch
            }

            if (filtered.isEmpty()) {
                val emptyBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(24), dp(40), dp(24), dp(40))
                }
                emptyBox.addView(TextView(this).apply {
                    text = "📂 当前分类下未找到本地文档文件"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#334155"))
                    gravity = Gravity.CENTER
                })
                emptyBox.addView(TextView(this).apply {
                    text = "您可以点击右上角「📁 目录树浏览」直接进入手机文件夹，或点击「📂 系统选择器」打开任意位置的文件。"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Color.parseColor("#64748B"))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(16))
                })
                val browseFolderBtn = createHeaderActionChip("📁 浏览手机存储目录", "#2563EB", "#FFFFFF") {
                    isFolderBrowseMode = true
                    renderPhoneDocumentBrowser()
                }
                emptyBox.addView(browseFolderBtn)
                listContainer.addView(emptyBox)
                return
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            filtered.forEach { file ->
                val ext = file.extension.lowercase(Locale.ROOT)
                val (badgeIcon, badgeColor) = when (ext) {
                    "docx", "doc" -> "W" to "#2563EB"
                    "xlsx", "xls", "csv" -> "X" to "#16A34A"
                    "md", "markdown" -> "M↓" to "#7C3AED"
                    else -> "TXT" to "#475569"
                }

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
                    setOnClickListener {
                        openLocalFile(file)
                    }
                }

                val badge = TextView(this).apply {
                    text = badgeIcon
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.parseColor(badgeColor))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                        rightMargin = dp(12)
                    }
                }
                card.addView(badge)

                val infoCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                infoCol.addView(TextView(this).apply {
                    text = file.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                })
                infoCol.addView(TextView(this).apply {
                    text = "${Formatter.formatFileSize(this@DocumentPreviewActivity, file.length())} · ${dateFormat.format(Date(file.lastModified()))}\n${file.parentFile?.absolutePath ?: ""}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(Color.parseColor("#64748B"))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.START
                })
                card.addView(infoCol)

                listContainer.addView(card)
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                populateFilteredDocs()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        populateFilteredDocs()
    }

    private fun renderFolderExplorerMode(mainCol: LinearLayout) {
        // Path bar
        val pathBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val upBtn = TextView(this).apply {
            text = "⬆️ 上一级"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#2563EB"))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#EFF6FF"))
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                val parent = currentBrowseDir.parentFile
                if (parent != null && parent.canRead()) {
                    currentBrowseDir = parent
                    renderPhoneDocumentBrowser()
                }
            }
        }
        pathBar.addView(upBtn)

        val pathTv = TextView(this).apply {
            text = currentBrowseDir.absolutePath
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#334155"))
            setPadding(dp(10), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
        }
        pathBar.addView(pathTv)
        mainCol.addView(pathBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(24))
        }

        val children = (currentBrowseDir.listFiles()?.toList() ?: emptyList())
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })

        if (children.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "当前文件夹为空"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.parseColor("#64748B"))
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(40), dp(20), dp(40))
            })
        } else {
            children.forEach { item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.WHITE)
                        setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    layoutParams = lp
                    setOnClickListener {
                        if (item.isDirectory) {
                            currentBrowseDir = item
                            renderPhoneDocumentBrowser()
                        } else {
                            openLocalFile(item)
                        }
                    }
                }

                val iconTv = TextView(this).apply {
                    text = if (item.isDirectory) "📁" else "📄"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setPadding(0, 0, dp(12), 0)
                }
                row.addView(iconTv)

                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                col.addView(TextView(this).apply {
                    text = item.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                col.addView(TextView(this).apply {
                    text = if (item.isDirectory) "文件夹" else Formatter.formatFileSize(this@DocumentPreviewActivity, item.length())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(Color.parseColor("#64748B"))
                })
                row.addView(col)
                listLayout.addView(row)
            }
        }

        scroll.addView(listLayout)
        mainCol.addView(scroll)
    }

    // ==================== Loading & Rendering Real Files ====================

    private fun openLocalFile(file: File) {
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                openDocumentFromBytes(file.name, bytes, file)
            } catch (e: Exception) {
                Toast.makeText(this@DocumentPreviewActivity, "无法读取文件: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadFromPath(path: Path) {
        val fileName = path.fileName?.toString() ?: "document"
        val localFile = try {
            File(path.toString()).takeIf { it.exists() }
        } catch (_: Exception) {
            null
        }
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    path.newInputStream().use { it.readBytes() }
                }
                openDocumentFromBytes(fileName, bytes, localFile)
            } catch (e: Exception) {
                Toast.makeText(this@DocumentPreviewActivity, "读取文件失败: ${e.message}", Toast.LENGTH_LONG).show()
                showPhoneDocumentBrowser()
            }
        }
    }

    private fun loadFromUri(uri: Uri) {
        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                }
                openDocumentFromBytes(fileName, bytes, null)
            } catch (e: Exception) {
                Toast.makeText(this@DocumentPreviewActivity, "打开文件失败: ${e.message}", Toast.LENGTH_LONG).show()
                showPhoneDocumentBrowser()
            }
        }
    }

    private fun openDocumentFromBytes(fileName: String, bytes: ByteArray, localFile: File?) {
        isInBrowserMode = false
        currentDocTitle = fileName
        currentDocBytes = bytes
        currentLocalFile = localFile
        currentDocExtension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

        titleText.text = fileName
        val sizeStr = Formatter.formatFileSize(this, bytes.size.toLong())
        subtitleText.text = localFile?.absolutePath ?: "$sizeStr · 真实文件阅读视图"

        headerActionsLayout.removeAllViews()
        if (!launchedWithDirectFile) {
            headerActionsLayout.addView(
                createHeaderActionChip("📂 换文件", "#F1F5F9", "#334155") {
                    showPhoneDocumentBrowser()
                }
            )
        }

        when (currentDocExtension) {
            "docx", "doc" -> renderWordDocument(fileName, bytes)
            "xlsx", "xls", "csv" -> renderExcelDocument(fileName, bytes, isCsv = currentDocExtension == "csv")
            "md", "markdown" -> {
                isMarkdownEditMode = false
                renderMarkdownDocument(fileName, bytes, localFile)
            }
            else -> renderTextEditorDocument(fileName, bytes, localFile)
        }
    }

    // ==================== 1. Word (.docx) Full-Screen Reader ====================

    private fun renderWordDocument(fileName: String, bytes: ByteArray) {
        bodyContainer.removeAllViews()
        scope.launch {
            val doc = withContext(Dispatchers.Default) {
                OfficeDocumentParser.parseDocx(ByteArrayInputStream(bytes), fileName)
            }

            // Header action: Document outline & stats dialog
            headerActionsLayout.addView(
                createHeaderActionChip("📑 目录与统计", "#EFF6FF", "#1D4ED8") {
                    val headings = doc.blocks.filterIsInstance<DocxBlock.Paragraph>().filter { it.headingLevel > 0 }
                    val outline = if (headings.isEmpty()) {
                        "当前文档无章节标题"
                    } else {
                        headings.joinToString("\n") { h ->
                            "${"  ".repeat((h.headingLevel - 1).coerceAtLeast(0))}• ${h.text}"
                        }
                    }
                    AlertDialog.Builder(this@DocumentPreviewActivity)
                        .setTitle(doc.title.ifBlank { fileName })
                        .setMessage("总字数：${doc.characterCount} 字\n段落数：${doc.paragraphCount} 段\n表格数：${doc.tableCount} 个\n\n【章节大纲】\n$outline")
                        .setPositiveButton("确定", null)
                        .show()
                }
            )

            val mainCol = LinearLayout(this@DocumentPreviewActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            // Clean search bar (toggleable)
            val searchBar = EditText(this@DocumentPreviewActivity).apply {
                hint = "🔍 在 Word 文档中查找关键词..."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setSingleLine(true)
                visibility = View.GONE
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
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
            mainCol.addView(searchBar)

            headerActionsLayout.addView(
                createHeaderActionChip("🔍 查找", "#F1F5F9", "#334155") {
                    searchBar.visibility = if (searchBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            )

            val scroll = ScrollView(this@DocumentPreviewActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            val paperCard = LinearLayout(this@DocumentPreviewActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(1), Color.parseColor("#E2E8F0"))
                }
                setPadding(dp(20), dp(22), dp(20), dp(28))
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(12), dp(10), dp(12), dp(24))
                }
                layoutParams = lp
            }

            fun populateDocxParagraphs(keyword: String) {
                paperCard.removeAllViews()
                doc.blocks.forEach { block ->
                    when (block) {
                        is DocxBlock.Paragraph -> {
                            if (keyword.isNotBlank() && !block.text.contains(keyword, ignoreCase = true)) {
                                return@forEach
                            }
                            val tv = TextView(this@DocumentPreviewActivity).apply {
                                text = if (block.isBullet) "•  ${block.text}" else block.text
                                setTextIsSelectable(true)
                                when (block.headingLevel) {
                                    1 -> {
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                                        setTypeface(null, Typeface.BOLD)
                                        setTextColor(Color.parseColor("#0F172A"))
                                        setPadding(0, dp(12), 0, dp(8))
                                    }
                                    2 -> {
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                                        setTypeface(null, Typeface.BOLD)
                                        setTextColor(Color.parseColor("#1E3A8A"))
                                        setPadding(0, dp(10), 0, dp(6))
                                    }
                                    3 -> {
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                                        setTypeface(null, Typeface.BOLD)
                                        setTextColor(Color.parseColor("#1E293B"))
                                        setPadding(0, dp(8), 0, dp(4))
                                    }
                                    else -> {
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                                        setTypeface(null, if (block.isBold) Typeface.BOLD else Typeface.NORMAL)
                                        setTextColor(Color.parseColor("#334155"))
                                        setLineSpacing(dp(5).toFloat(), 1.15f)
                                        setPadding(if (block.isBullet) dp(10) else 0, dp(4), 0, dp(6))
                                    }
                                }
                                if (keyword.isNotBlank() && block.text.contains(keyword, ignoreCase = true)) {
                                    setBackgroundColor(Color.parseColor("#FEF9C3"))
                                }
                            }
                            paperCard.addView(tv)
                        }
                        is DocxBlock.Table -> {
                            val hScroll = HorizontalScrollView(this@DocumentPreviewActivity).apply {
                                setPadding(0, dp(10), 0, dp(12))
                            }
                            val tableContainer = LinearLayout(this@DocumentPreviewActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                background = GradientDrawable().apply {
                                    setColor(Color.WHITE)
                                    setStroke(dp(1), Color.parseColor("#CBD5E1"))
                                }
                            }
                            block.rows.forEachIndexed { rowIndex, row ->
                                val tr = LinearLayout(this@DocumentPreviewActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    setBackgroundColor(
                                        if (rowIndex == 0) Color.parseColor("#EFF6FF")
                                        else if (rowIndex % 2 == 0) Color.WHITE
                                        else Color.parseColor("#F8FAFC")
                                    )
                                }
                                row.forEach { cellText ->
                                    val cell = TextView(this@DocumentPreviewActivity).apply {
                                        text = cellText
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                                        setTypeface(null, if (rowIndex == 0) Typeface.BOLD else Typeface.NORMAL)
                                        setTextColor(Color.parseColor("#1E293B"))
                                        setPadding(dp(12), dp(8), dp(12), dp(8))
                                        minWidth = dp(100)
                                        background = GradientDrawable().apply {
                                            setStroke(1, Color.parseColor("#E2E8F0"))
                                        }
                                    }
                                    tr.addView(cell)
                                }
                                tableContainer.addView(tr)
                            }
                            hScroll.addView(tableContainer)
                            paperCard.addView(hScroll)
                        }
                    }
                }
            }

            populateDocxParagraphs("")

            searchBar.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    populateDocxParagraphs(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            scroll.addView(paperCard)
            mainCol.addView(scroll)
            bodyContainer.addView(mainCol)
        }
    }

    // ==================== 2. Excel (.xlsx / .csv) Full-Screen Grid ====================

    private fun renderExcelDocument(fileName: String, bytes: ByteArray, isCsv: Boolean) {
        bodyContainer.removeAllViews()
        scope.launch {
            val workbook = withContext(Dispatchers.Default) {
                if (isCsv) {
                    OfficeDocumentParser.parseCsv(bytes.toString(Charsets.UTF_8), fileName)
                } else {
                    OfficeDocumentParser.parseXlsx(ByteArrayInputStream(bytes), fileName)
                }
            }
            val sheets = workbook.sheets

            val mainCol = LinearLayout(this@DocumentPreviewActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            // Formula / selected cell bar
            val cellInspectorBar = TextView(this@DocumentPreviewActivity).apply {
                text = "fx  点击任意单元格查看完整内容与公式信息"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#334155"))
                setBackgroundColor(Color.WHITE)
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
            mainCol.addView(cellInspectorBar)

            val sheetBodyFrame = FrameLayout(this@DocumentPreviewActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            mainCol.addView(sheetBodyFrame)

            // Bottom sheet tabs bar (like Excel)
            val sheetTabScroll = HorizontalScrollView(this@DocumentPreviewActivity).apply {
                setBackgroundColor(Color.parseColor("#E2E8F0"))
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            val sheetTabRow = LinearLayout(this@DocumentPreviewActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            sheetTabScroll.addView(sheetTabRow)
            mainCol.addView(sheetTabScroll)

            var activeSheetIdx = 0

            fun renderActiveSheet() {
                sheetBodyFrame.removeAllViews()
                sheetTabRow.removeAllViews()

                sheets.forEachIndexed { idx, sheet ->
                    val selected = idx == activeSheetIdx
                    val tabBtn = TextView(this@DocumentPreviewActivity).apply {
                        text = "📗 ${sheet.name} (${sheet.rows.size}行)"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                        setTextColor(if (selected) Color.parseColor("#15803D") else Color.parseColor("#475569"))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(8).toFloat()
                            setColor(if (selected) Color.WHITE else Color.TRANSPARENT)
                        }
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        setOnClickListener {
                            activeSheetIdx = idx
                            renderActiveSheet()
                        }
                    }
                    sheetTabRow.addView(tabBtn)
                }

                val currentSheet = sheets.getOrNull(activeSheetIdx) ?: return
                val vScroll = ScrollView(this@DocumentPreviewActivity)
                val hScroll = HorizontalScrollView(this@DocumentPreviewActivity)

                val gridTable = LinearLayout(this@DocumentPreviewActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.WHITE)
                }

                val colCount = (currentSheet.rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)

                // Column letters header (A, B, C...)
                val colHeaderRow = LinearLayout(this@DocumentPreviewActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(Color.parseColor("#F1F5F9"))
                }
                colHeaderRow.addView(TextView(this@DocumentPreviewActivity).apply {
                    text = "#"
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#64748B"))
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(32))
                })
                for (c in 0 until colCount) {
                    val colLetter = ('A' + (c % 26)).toString()
                    colHeaderRow.addView(TextView(this@DocumentPreviewActivity).apply {
                        text = colLetter
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#334155"))
                        layoutParams = LinearLayout.LayoutParams(dp(120), dp(32))
                        background = GradientDrawable().apply {
                            setStroke(1, Color.parseColor("#CBD5E1"))
                        }
                    })
                }
                gridTable.addView(colHeaderRow)

                currentSheet.rows.forEachIndexed { rIdx, rowCells ->
                    val tr = LinearLayout(this@DocumentPreviewActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setBackgroundColor(
                            if (rIdx == 0) Color.parseColor("#ECFDF5")
                            else if (rIdx % 2 == 0) Color.WHITE
                            else Color.parseColor("#F8FAFC")
                        )
                    }

                    // Row number cell
                    tr.addView(TextView(this@DocumentPreviewActivity).apply {
                        text = "${rIdx + 1}"
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setTextColor(Color.parseColor("#64748B"))
                        setBackgroundColor(Color.parseColor("#F1F5F9"))
                        layoutParams = LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT)
                    })

                    for (cIdx in 0 until colCount) {
                        val cellObj = rowCells.getOrNull(cIdx)
                        val valStr = cellObj?.value.orEmpty()
                        val colLetter = cellObj?.reference ?: "${('A' + (cIdx % 26))}${rIdx + 1}"
                        val cellTv = TextView(this@DocumentPreviewActivity).apply {
                            text = valStr
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setTypeface(null, if (rIdx == 0) Typeface.BOLD else Typeface.NORMAL)
                            setTextColor(
                                if (rIdx == 0) Color.parseColor("#065F46")
                                else Color.parseColor("#1E293B")
                            )
                            setPadding(dp(8), dp(8), dp(8), dp(8))
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT)
                            background = GradientDrawable().apply {
                                setStroke(1, Color.parseColor("#E2E8F0"))
                            }
                            setOnClickListener {
                                val formulaPart = cellObj?.formula?.let { "  (公式: =$it)" }.orEmpty()
                                cellInspectorBar.text = "fx  [$colLetter]  $valStr$formulaPart"
                            }
                        }
                        tr.addView(cellTv)
                    }
                    gridTable.addView(tr)
                }

                hScroll.addView(gridTable)
                vScroll.addView(hScroll)
                sheetBodyFrame.addView(vScroll)
            }

            renderActiveSheet()
            bodyContainer.addView(mainCol)
        }
    }

    // ==================== 3. Markdown (.md) Clean Reader + Optional Editor ====================

    private fun renderMarkdownDocument(fileName: String, bytes: ByteArray, localFile: File?) {
        bodyContainer.removeAllViews()
        var markdownSource = bytes.toString(Charsets.UTF_8)

        val previewScroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            setPadding(dp(20), dp(20), dp(20), dp(28))
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), dp(10), dp(12), dp(24))
            }
            layoutParams = lp
        }
        MarkdownRenderer.renderToContainer(this@DocumentPreviewActivity, previewCard, markdownSource)
        previewScroll.addView(previewCard)

        // Edit container (hidden by default so reading is 100% clean and unobstructed!)
        val editContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val editorInput = EditText(this).apply {
            setText(markdownSource)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.TOP or Gravity.START
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    markdownSource = s?.toString().orEmpty()
                    MarkdownRenderer.renderToContainer(this@DocumentPreviewActivity, previewCard, markdownSource)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        editContainer.addView(editorInput)

        val toggleEditChip = createHeaderActionChip("✏️ 编辑源码", "#F3E8FF", "#6D28D9") {
            isMarkdownEditMode = !isMarkdownEditMode
            if (isMarkdownEditMode) {
                previewScroll.visibility = View.GONE
                editContainer.visibility = View.VISIBLE
            } else {
                editContainer.visibility = View.GONE
                previewScroll.visibility = View.VISIBLE
            }
            updateMarkdownToolbar(localFile) { editorInput.text.toString() }
        }
        headerActionsLayout.addView(toggleEditChip)

        if (localFile != null && localFile.canWrite()) {
            headerActionsLayout.addView(
                createHeaderActionChip("💾 保存", "#DCFCE7", "#15803D") {
                    try {
                        localFile.writeText(editorInput.text.toString(), Charsets.UTF_8)
                        Toast.makeText(this, "已保存至 ${localFile.name}", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        bodyContainer.addView(previewScroll)
        bodyContainer.addView(editContainer)
    }

    private fun updateMarkdownToolbar(localFile: File?, getContent: () -> String) {
        headerActionsLayout.removeAllViews()
        if (!launchedWithDirectFile) {
            headerActionsLayout.addView(
                createHeaderActionChip("📂 换文件", "#F1F5F9", "#334155") {
                    showPhoneDocumentBrowser()
                }
            )
        }
        headerActionsLayout.addView(
            createHeaderActionChip(
                if (isMarkdownEditMode) "👁️ 阅读视图" else "✏️ 编辑源码",
                "#F3E8FF",
                "#6D28D9"
            ) {
                isMarkdownEditMode = !isMarkdownEditMode
                val previewView = bodyContainer.getChildAt(0)
                val editView = bodyContainer.getChildAt(1)
                previewView?.visibility = if (isMarkdownEditMode) View.GONE else View.VISIBLE
                editView?.visibility = if (isMarkdownEditMode) View.VISIBLE else View.GONE
                updateMarkdownToolbar(localFile, getContent)
            }
        )
        if (localFile != null && localFile.canWrite()) {
            headerActionsLayout.addView(
                createHeaderActionChip("💾 保存", "#DCFCE7", "#15803D") {
                    try {
                        localFile.writeText(getContent(), Charsets.UTF_8)
                        Toast.makeText(this, "已保存至 ${localFile.name}", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    // ==================== 4. Text / Code Viewer & Editor (PRD 2.6.2) ====================

    private fun renderTextEditorDocument(fileName: String, bytes: ByteArray, localFile: File?) {
        bodyContainer.removeAllViews()

        val decodedText = try {
            bytes.toString(currentTextEncoding)
        } catch (_: Exception) {
            bytes.toString(Charsets.UTF_8)
        }

        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Toolbar options: Open in Moon+ Book Reader, Encoding switch, Line numbers, Search & Replace, Save
        if (localFile != null) {
            headerActionsLayout.addView(
                createHeaderActionChip("📖 静读模式", "#FEF3C7", "#B45309") {
                    startActivity(ReaderActivity.createIntent(this, java8.nio.file.Paths.get(localFile.absolutePath)))
                }
            )
        }

        val encodingName = if (currentTextEncoding == Charsets.UTF_8) "UTF-8" else "GBK"
        headerActionsLayout.addView(
            createHeaderActionChip("🔤 $encodingName", "#E0F2FE", "#0369A1") {
                currentTextEncoding = if (currentTextEncoding == Charsets.UTF_8) {
                    Charset.forName("GBK")
                } else {
                    Charsets.UTF_8
                }
                openDocumentFromBytes(fileName, bytes, localFile)
            }
        )

        val editorScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val editorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(12), dp(12), dp(24))
        }

        val lineNumberTv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.END or Gravity.TOP
            setPadding(dp(6), dp(2), dp(10), 0)
            setLineSpacing(dp(4).toFloat(), 1.15f)
            visibility = if (showLineNumbers) View.VISIBLE else View.GONE
        }

        val codeEditText = EditText(this).apply {
            setText(decodedText)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.START or Gravity.TOP
            background = null
            setPadding(dp(6), 0, dp(6), 0)
            setLineSpacing(dp(4).toFloat(), 1.15f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        fun updateLineNumbers(text: CharSequence?) {
            val lineCount = (text?.count { it == '\n' } ?: 0) + 1
            lineNumberTv.text = (1..lineCount).joinToString("\n")
        }
        updateLineNumbers(decodedText)

        codeEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateLineNumbers(s)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Search & Replace bar (toggleable)
        val searchReplaceBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        val findInput = EditText(this).apply {
            hint = "查找..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val replaceInput = EditText(this).apply {
            hint = "替换为..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val replaceAllBtn = createHeaderActionChip("全部替换", "#2563EB", "#FFFFFF") {
            val findStr = findInput.text?.toString().orEmpty()
            val repStr = replaceInput.text?.toString().orEmpty()
            if (findStr.isNotEmpty()) {
                val updated = codeEditText.text.toString().replace(findStr, repStr)
                codeEditText.setText(updated)
                Toast.makeText(this, "已完成替换", Toast.LENGTH_SHORT).show()
            }
        }
        searchReplaceBar.addView(findInput)
        searchReplaceBar.addView(replaceInput)
        searchReplaceBar.addView(replaceAllBtn)
        mainCol.addView(searchReplaceBar)

        headerActionsLayout.addView(
            createHeaderActionChip("🔍 替换", "#F1F5F9", "#334155") {
                searchReplaceBar.visibility =
                    if (searchReplaceBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        )

        if (localFile != null && localFile.canWrite()) {
            headerActionsLayout.addView(
                createHeaderActionChip("💾 保存", "#DCFCE7", "#15803D") {
                    try {
                        localFile.writeText(codeEditText.text.toString(), currentTextEncoding)
                        Toast.makeText(this, "已保存 ${localFile.name}", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        editorRow.addView(lineNumberTv)
        editorRow.addView(codeEditText)
        editorScroll.addView(editorRow)
        mainCol.addView(editorScroll)

        bodyContainer.addView(mainCol)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    companion object {
        private const val EXTRA_IN_MEMORY_BYTES = "extra_in_memory_bytes"
        private const val EXTRA_IN_MEMORY_NAME = "extra_in_memory_name"

        fun createIntent(
            context: Context? = null,
            path: Path? = null,
            filePath: String? = null,
            inMemoryName: String? = null,
            inMemoryBytes: ByteArray? = null
        ): Intent =
            DocumentPreviewActivity::class.createIntent().apply {
                when {
                    path != null -> extraPath = path
                    !filePath.isNullOrBlank() -> extraPath = Paths.get(filePath)
                }
                if (!inMemoryName.isNullOrBlank()) {
                    putExtra(EXTRA_IN_MEMORY_NAME, inMemoryName)
                }
                if (inMemoryBytes != null) {
                    putExtra(EXTRA_IN_MEMORY_BYTES, inMemoryBytes)
                }
            }

        fun createInMemoryIntent(context: Context, fileName: String, bytes: ByteArray): Intent =
            Intent(context, DocumentPreviewActivity::class.java).apply {
                putExtra(EXTRA_IN_MEMORY_NAME, fileName)
                putExtra(EXTRA_IN_MEMORY_BYTES, bytes)
            }
    }
}
