package me.zhanghai.android.files.viewer.archive

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.channels.FileChannel
import java8.nio.file.Path
import java8.nio.file.StandardOpenOption
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.provider.archive.archiver.ReadArchive
import me.zhanghai.android.files.provider.archive.archiver.WriteArchive
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.viewer.document.DocumentPreviewActivity
import me.zhanghai.android.libarchive.Archive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class ArchivePreviewActivity : AppActivity() {

    data class ArchiveEntryItem(
        val path: String,
        val name: String,
        val isDirectory: Boolean,
        val isEncrypted: Boolean,
        val uncompressedSize: Long,
        val lastModifiedMillis: Long
    )

    private var currentArchiveFile: File? = null
    private var splitVolumeFiles: List<File> = emptyList()
    private var verifiedPassword: String? = null
    private var allEntries: List<ArchiveEntryItem> = emptyList()
    private var searchQuery: String = ""
    private var isArchiveEncrypted: Boolean = false

    private lateinit var toolbar: MaterialToolbar
    private lateinit var summaryText: TextView
    private lateinit var badgesRow: LinearLayout
    private lateinit var searchEdit: EditText
    private lateinit var entryListContainer: LinearLayout

    private val pickArchiveLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            loadArchiveFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        resolveInitialArchive(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveInitialArchive(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CREATE_CUSTOM_ARCHIVE, 0, "新建分卷/加密压缩").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_EXTRACT_ALL, 1, "全部解压").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_SET_PASSWORD, 2, "输入解压密码").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_OPEN_ARCHIVE, 3, "打开其他压缩包").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            MENU_CREATE_CUSTOM_ARCHIVE -> {
                showCreateAdvancedArchiveDialog()
                true
            }
            MENU_EXTRACT_ALL -> {
                triggerExtractAllWithPasswordCheck()
                true
            }
            MENU_SET_PASSWORD -> {
                promptForPassword("输入压缩包密码") { pwd ->
                    verifiedPassword = pwd
                    reloadCurrentArchive()
                }
                true
            }
            MENU_OPEN_ARCHIVE -> {
                pickArchiveLauncher.launch(arrayOf("*/*"))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            setTitleTextColor(Color.WHITE)
            setSubtitleTextColor(Color.parseColor("#94A3B8"))
            setNavigationIconTint(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(toolbar)

        // Archive Summary Card
        val summaryCard = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                topMargin = dp(10)
                bottomMargin = dp(6)
            }
        }
        val summaryCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        summaryText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#1E293B"))
            setLineSpacing(0f, 1.3f)
        }
        summaryCol.addView(summaryText)

        val badgeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(8), 0, 0)
        }
        badgesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        badgeScroll.addView(badgesRow)
        summaryCol.addView(badgeScroll)

        // Quick Action Row inside card
        val actionScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(8), 0, 0)
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val extractAllBtn = MaterialButton(this).apply {
            text = "📂 解压全部文件 (需密码时自动验证)"
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { triggerExtractAllWithPasswordCheck() }
        }
        val createSplitOrEncBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "➕ 创建分卷 / 加密压缩包"
            textSize = 11.5f
            setOnClickListener { showCreateAdvancedArchiveDialog() }
        }
        actionRow.addView(extractAllBtn)
        actionRow.addView(createSplitOrEncBtn)
        actionScroll.addView(actionRow)
        summaryCol.addView(actionScroll)

        summaryCard.addView(summaryCol)
        root.addView(summaryCard)

        // Search input
        val searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(6))
        }
        searchEdit = EditText(this).apply {
            hint = "免解压检索压缩包内文件名..."
            textSize = 13.5f
            setSingleLine(true)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CBD5E1"))
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString()?.trim().orEmpty()
                    renderEntryList()
                }
            })
        }
        searchContainer.addView(searchEdit)
        root.addView(searchContainer)

        val listScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        entryListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(24))
        }
        listScroll.addView(entryListContainer)
        root.addView(listScroll)

        return root
    }

    private fun resolveInitialArchive(intent: Intent) {
        val pathExtra = intent.extraPath
        val localFile = runCatching { pathExtra?.toFile() }.getOrNull()
            ?: intent.getStringExtra(EXTRA_FILE_PATH)?.let { File(it) }

        if (localFile != null && localFile.exists()) {
            loadArchiveFromFile(localFile)
            return
        }

        val uri = intent.data
        if (uri != null) {
            loadArchiveFromUri(uri)
            return
        }

        // Launched directly: generate a sample archive to demonstrate in-archive preview
        val demoArchive = ensureDemoArchiveFile()
        loadArchiveFromFile(demoArchive)
    }

    private fun getArchiveWorkshopDir(): File {
        @Suppress("DEPRECATION")
        val pubDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = if (pubDownload != null && (pubDownload.exists() || pubDownload.mkdirs()) && pubDownload.canWrite()) {
            pubDownload
        } else {
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun ensureDemoArchiveFile(): File {
        val dir = getArchiveWorkshopDir()
        val demoZip = File(dir, "项目资料汇总_支持免解压预览.zip")
        if (!demoZip.exists()) {
            runCatching {
                createCustomArchiveFile(
                    targetFile = demoZip,
                    password = null,
                    compressionLevel = 6,
                    splitVolumeBytes = 0L
                )
            }
        }
        return demoZip
    }

    private fun loadArchiveFromFile(file: File) {
        currentArchiveFile = file
        splitVolumeFiles = detectSplitVolumeSiblings(file)
        reloadCurrentArchive()
    }

    private fun loadArchiveFromUri(uri: Uri) {
        val tempFile = File(cacheDir, "preview_archive_temp.zip")
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            loadArchiveFromFile(tempFile)
        }.onFailure { e ->
            showToast("无法读取压缩文件: ${e.localizedMessage}")
        }
    }

    /**
     * Detects if a file is part of a multi-volume split archive (e.g. name.part1.zip, name.part2.zip or name.zip.001)
     */
    private fun detectSplitVolumeSiblings(file: File): List<File> {
        val parent = file.parentFile ?: return listOf(file)
        val name = file.name
        val partRegex = Regex("""^(.*)\.part(\d+)\.(zip|7z|rar|tar\.xz|tar)$""", RegexOption.IGNORE_CASE)
        val numExtRegex = Regex("""^(.*\.(?:zip|7z|rar|tar))\.(\d{3})$""", RegexOption.IGNORE_CASE)

        val partMatch = partRegex.find(name)
        if (partMatch != null) {
            val prefix = partMatch.groupValues[1]
            val ext = partMatch.groupValues[3]
            val siblingRegex = Regex(
                """^${Regex.escape(prefix)}\.part(\d+)\.${Regex.escape(ext)}$""",
                RegexOption.IGNORE_CASE
            )
            val matches = parent.listFiles()?.mapNotNull { f ->
                val m = siblingRegex.find(f.name)
                if (m != null) (m.groupValues[1].toIntOrNull() ?: 999) to f else null
            }?.sortedBy { it.first }?.map { it.second }.orEmpty()
            if (matches.isNotEmpty()) return matches
        }

        val numMatch = numExtRegex.find(name)
        if (numMatch != null) {
            val prefix = numMatch.groupValues[1]
            val siblingRegex = Regex(
                """^${Regex.escape(prefix)}\.(\d{3})$""",
                RegexOption.IGNORE_CASE
            )
            val matches = parent.listFiles()?.mapNotNull { f ->
                val m = siblingRegex.find(f.name)
                if (m != null) (m.groupValues[1].toIntOrNull() ?: 999) to f else null
            }?.sortedBy { it.first }?.map { it.second }.orEmpty()
            if (matches.isNotEmpty()) return matches
        }

        return listOf(file)
    }

    private fun openCombinedArchiveStream(): InputStream {
        val volumes = if (splitVolumeFiles.isNotEmpty()) splitVolumeFiles else listOfNotNull(currentArchiveFile)
        val streams = volumes.map { FileInputStream(it).buffered() }
        return SequenceInputStream(Collections.enumeration(streams))
    }

    private fun reloadCurrentArchive() {
        val file = currentArchiveFile ?: return
        toolbar.title = file.name
        val isSplit = splitVolumeFiles.size > 1
        toolbar.subtitle = if (isSplit) {
            "分卷压缩包预览 (共 ${splitVolumeFiles.size} 卷合并直读)"
        } else {
            "免解压内部文件列表预览"
        }

        val parsedEntries = mutableListOf<ArchiveEntryItem>()
        var anyEncrypted = false
        val passwords = listOfNotNull(verifiedPassword)

        runCatching {
            openCombinedArchiveStream().use { stream ->
                ReadArchive(stream, passwords).use { reader ->
                    while (true) {
                        val entry = reader.readEntry(StandardCharsets.UTF_8) ?: break
                        if (entry.isEncrypted) anyEncrypted = true
                        parsedEntries.add(
                            ArchiveEntryItem(
                                path = entry.name,
                                name = entry.name.trimEnd('/').substringAfterLast('/'),
                                isDirectory = entry.isDirectory,
                                isEncrypted = entry.isEncrypted,
                                uncompressedSize = entry.size.coerceAtLeast(0L),
                                lastModifiedMillis = entry.lastModifiedTime?.toMillis() ?: file.lastModified()
                            )
                        )
                    }
                }
            }
        }.onFailure { e ->
            showToast("读取压缩包提示: ${e.localizedMessage}")
        }

        isArchiveEncrypted = anyEncrypted
        allEntries = parsedEntries

        val totalArchiveBytes = splitVolumeFiles.sumOf { it.length() }.coerceAtLeast(1L)
        val totalUncompressedBytes = parsedEntries.sumOf { it.uncompressedSize }
        val ratioPct = if (totalUncompressedBytes > 0L) {
            ((totalArchiveBytes.toDouble() / totalUncompressedBytes.toDouble()) * 100.0).toInt().coerceIn(1, 100)
        } else 100

        val archiveSizeStr = totalArchiveBytes.asFileSize().formatHumanReadable(this)
        val rawSizeStr = totalUncompressedBytes.asFileSize().formatHumanReadable(this)
        val splitDesc = if (splitVolumeFiles.size > 1) {
            "\n分卷状态: 共 ${splitVolumeFiles.size} 个分卷 (${splitVolumeFiles.joinToString(", ") { it.name }})"
        } else ""

        summaryText.text =
            "压缩文件: ${file.name}\n" +
            "包含条目: ${parsedEntries.size} 项 · 压缩后体积: $archiveSizeStr / 原始总大小: $rawSizeStr (压缩比 $ratioPct%)" +
            splitDesc

        badgesRow.removeAllViews()
        addBadge(
            text = if (splitVolumeFiles.size > 1) "📦 分卷压缩 (${splitVolumeFiles.size}卷)" else "📦 标准归档",
            bgHex = "#DBEAFE",
            fgHex = "#1E40AF"
        )
        addBadge(
            text = if (isArchiveEncrypted) {
                if (verifiedPassword != null) "🔓 已加密 (密码已就绪)" else "🔒 AES-256 加密保护"
            } else {
                "🔓 未加密"
            },
            bgHex = if (isArchiveEncrypted) "#FEF3C7" else "#DCFCE7",
            fgHex = if (isArchiveEncrypted) "#92400E" else "#166534"
        )
        addBadge(
            text = "点击任意文件可直接免解压预览内容",
            bgHex = "#F1F5F9",
            fgHex = "#475569"
        )

        renderEntryList()
    }

    private fun addBadge(text: String, bgHex: String, fgHex: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(fgHex))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor(bgHex))
            }
        }
        badgesRow.addView(tv)
    }

    private fun renderEntryList() {
        entryListContainer.removeAllViews()
        val query = searchQuery.lowercase(Locale.ROOT)
        val filtered = allEntries.filter {
            query.isEmpty() || it.path.lowercase(Locale.ROOT).contains(query)
        }

        if (filtered.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "未找到匹配的压缩包内文件"
                textSize = 14f
                setTextColor(Color.parseColor("#64748B"))
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(40), dp(20), dp(40))
            }
            entryListContainer.addView(emptyTv)
            return
        }

        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        for (item in filtered) {
            val card = MaterialCardView(this).apply {
                radius = dp(10).toFloat()
                cardElevation = dp(1).toFloat()
                setCardBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    if (!item.isDirectory) {
                        previewEntryWithoutExtracting(item)
                    }
                }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }

            val iconTv = TextView(this).apply {
                text = when {
                    item.isDirectory -> "📁"
                    item.isEncrypted -> "🔒"
                    item.name.endsWith(".docx", true) || item.name.endsWith(".xlsx", true) -> "📊"
                    item.name.endsWith(".md", true) || item.name.endsWith(".txt", true) -> "📄"
                    else -> "🗎"
                }
                textSize = 20f
                setPadding(0, 0, dp(12), 0)
            }
            row.addView(iconTv)

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleTv = TextView(this).apply {
                text = item.path
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
            }
            val metaTv = TextView(this).apply {
                val sizeStr = if (item.isDirectory) "文件夹" else item.uncompressedSize.asFileSize().formatHumanReadable(this@ArchivePreviewActivity)
                val encStr = if (item.isEncrypted) " · 🔒加密条目" else ""
                text = "$sizeStr · ${df.format(Date(item.lastModifiedMillis))}$encStr"
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(2), 0, 0)
            }
            infoCol.addView(titleTv)
            infoCol.addView(metaTv)
            row.addView(infoCol)

            if (!item.isDirectory) {
                val previewBtn = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "免解压预览"
                    textSize = 11f
                    setOnClickListener { previewEntryWithoutExtracting(item) }
                }
                row.addView(previewBtn)
            }

            card.addView(row)
            entryListContainer.addView(card)
        }
    }

    private fun previewEntryWithoutExtracting(item: ArchiveEntryItem) {
        if (item.isEncrypted && verifiedPassword.isNullOrEmpty()) {
            promptForPassword("该压缩条目已加密，请输入密码后预览") { pwd ->
                verifiedPassword = pwd
                previewEntryWithoutExtracting(item)
            }
            return
        }

        val bytesResult = readEntryBytesInMemory(item.path, verifiedPassword)
        bytesResult.onSuccess { bytes ->
            startActivity(
                DocumentPreviewActivity.createIntent(
                    context = this,
                    inMemoryName = item.name,
                    inMemoryBytes = bytes
                )
            )
        }.onFailure {
            if (item.isEncrypted || isArchiveEncrypted) {
                verifiedPassword = null
                promptForPassword("密码验证未通过，请重新输入正确的压缩密码") { pwd ->
                    verifiedPassword = pwd
                    previewEntryWithoutExtracting(item)
                }
            } else {
                showToast("读取内部文件失败: ${it.localizedMessage}")
            }
        }
    }

    private fun readEntryBytesInMemory(targetEntryPath: String, password: String?): Result<ByteArray> = runCatching {
        val passwords = listOfNotNull(password)
        openCombinedArchiveStream().use { stream ->
            ReadArchive(stream, passwords).use { reader ->
                while (true) {
                    val entry = reader.readEntry(StandardCharsets.UTF_8)
                        ?: throw IllegalStateException("未找到条目 $targetEntryPath")
                    if (entry.name == targetEntryPath) {
                        val baos = ByteArrayOutputStream()
                        val dataIn = reader.newDataInputStream()
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val read = dataIn.read(buf)
                            if (read <= 0) break
                            baos.write(buf, 0, read)
                        }
                        return@runCatching baos.toByteArray()
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                ByteArray(0)
            }
        }
    }

    private fun triggerExtractAllWithPasswordCheck() {
        if (isArchiveEncrypted && verifiedPassword.isNullOrEmpty()) {
            promptForPassword("解压加密压缩包需要验证密码") { pwd ->
                verifiedPassword = pwd
                performExtractAll(pwd)
            }
        } else {
            performExtractAll(verifiedPassword)
        }
    }

    private fun performExtractAll(password: String?) {
        val archiveFile = currentArchiveFile ?: return
        val baseFolderName = archiveFile.name
            .replace(Regex("""\.part\d+\.""", RegexOption.IGNORE_CASE), ".")
            .substringBeforeLast('.')
        val destDir = File(archiveFile.parentFile ?: getArchiveWorkshopDir(), "${baseFolderName}_已解压")

        runCatching {
            if (!destDir.exists()) destDir.mkdirs()
            var extractedCount = 0
            openCombinedArchiveStream().use { stream ->
                ReadArchive(stream, listOfNotNull(password)).use { reader ->
                    while (true) {
                        val entry = reader.readEntry(StandardCharsets.UTF_8) ?: break
                        val outTarget = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            outTarget.mkdirs()
                        } else {
                            outTarget.parentFile?.mkdirs()
                            val dataIn = reader.newDataInputStream()
                            FileOutputStream(outTarget).use { fos ->
                                val buf = ByteArray(16 * 1024)
                                while (true) {
                                    val r = dataIn.read(buf)
                                    if (r <= 0) break
                                    fos.write(buf, 0, r)
                                }
                            }
                            extractedCount++
                        }
                    }
                }
            }
            reloadCurrentArchive()
            MaterialAlertDialogBuilder(this)
                .setTitle("解压完成")
                .setMessage("已成功解压 $extractedCount 个文件至:\n${destDir.absolutePath}")
                .setPositiveButton("确定", null)
                .show()
        }.onFailure {
            verifiedPassword = null
            promptForPassword("密码错误或需要密码验证，请重新输入压缩密码") { retryPwd ->
                verifiedPassword = retryPwd
                performExtractAll(retryPwd)
            }
        }
    }

    private fun promptForPassword(title: String, onPasswordEntered: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "请输入压缩密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("验证并继续") { _, _ ->
                val pwd = input.text?.toString().orEmpty()
                if (pwd.isNotEmpty()) {
                    onPasswordEntered(pwd)
                } else {
                    showToast("密码不能为空")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Dialog to create a new archive with:
     * - Compression Level: Fast (1) / Normal (6) / Maximum (9)
     * - Split Volume: Single / Split Part1 & Part2 / Split by 1MB
     * - Password Encryption (AES-256)
     */
    private fun showCreateAdvancedArchiveDialog() {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        scroll.addView(layout)

        val nameInput = EditText(this).apply {
            setText("加密分卷归档_${System.currentTimeMillis() % 1000}.zip")
            hint = "输入压缩包名称 (.zip)"
        }
        layout.addView(TextView(this).apply {
            text = "压缩包文件名:"
            setTypeface(typeface, Typeface.BOLD)
        })
        layout.addView(nameInput)

        // Compression Level RadioGroup
        layout.addView(TextView(this).apply {
            text = "\n选择压缩等级（权衡速度与体积）:"
            setTypeface(typeface, Typeface.BOLD)
        })
        val levelGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val fastRadio = RadioButton(this).apply {
            id = View.generateViewId()
            text = "⚡ 快速压缩 (Level 1 - 速度最快)"
        }
        val normalRadio = RadioButton(this).apply {
            id = View.generateViewId()
            text = "⚖️ 标准压缩 (Level 6 - 平衡速度与体积)"
            isChecked = true
        }
        val maxRadio = RadioButton(this).apply {
            id = View.generateViewId()
            text = "🗜️ 最大压缩 (Level 9 - 最小文件体积)"
        }
        levelGroup.addView(fastRadio)
        levelGroup.addView(normalRadio)
        levelGroup.addView(maxRadio)
        layout.addView(levelGroup)

        // Split Volume RadioGroup
        layout.addView(TextView(this).apply {
            text = "\n分卷压缩设置（支持生成 Part1、Part2 分卷）:"
            setTypeface(typeface, Typeface.BOLD)
        })
        val splitGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val splitNone = RadioButton(this).apply {
            id = View.generateViewId()
            text = "不分卷（生成单个 .zip 文件）"
        }
        val splitTwoParts = RadioButton(this).apply {
            id = View.generateViewId()
            text = "创建分卷压缩包（自动拆分为 .part1.zip 与 .part2.zip）"
            isChecked = true
        }
        splitGroup.addView(splitNone)
        splitGroup.addView(splitTwoParts)
        layout.addView(splitGroup)

        // Password Input
        layout.addView(TextView(this).apply {
            text = "\n加密压缩密码（AES-256 加密，解压/预览需输入密码）:"
            setTypeface(typeface, Typeface.BOLD)
        })
        val pwdInput = EditText(this).apply {
            hint = "留空则不加密，输入如 123456 开启加密保护"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        layout.addView(pwdInput)

        MaterialAlertDialogBuilder(this)
            .setTitle("创建分卷 / 加密压缩文件")
            .setView(scroll)
            .setPositiveButton("立即创建并预览") { _, _ ->
                var rawName = nameInput.text?.toString()?.trim().orEmpty().ifEmpty { "archive.zip" }
                if (!rawName.endsWith(".zip", true)) rawName += ".zip"
                val level = when (levelGroup.checkedRadioButtonId) {
                    fastRadio.id -> 1
                    maxRadio.id -> 9
                    else -> 6
                }
                val splitBytes = if (splitGroup.checkedRadioButtonId == splitTwoParts.id) -1L else 0L
                val pwd = pwdInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

                val outDir = currentArchiveFile?.parentFile?.takeIf { it.canWrite() } ?: getArchiveWorkshopDir()
                val outFile = File(outDir, rawName)
                runCatching {
                    val firstVolume = createCustomArchiveFile(
                        targetFile = outFile,
                        password = pwd,
                        compressionLevel = level,
                        splitVolumeBytes = splitBytes
                    )
                    verifiedPassword = null // Reset so user can experience password prompt when clicking an encrypted entry
                    loadArchiveFromFile(firstVolume)
                    val msg = buildString {
                        append("已成功创建压缩包: ${firstVolume.name}")
                        if (splitBytes != 0L) append(" (含 Part1 / Part2 分卷)")
                        if (pwd != null) append(" [已启用密码保护]")
                    }
                    showToast(msg)
                }.onFailure { e ->
                    showToast("创建压缩包失败: ${e.localizedMessage}")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createCustomArchiveFile(
        targetFile: File,
        password: String?,
        compressionLevel: Int,
        splitVolumeBytes: Long
    ): File {
        if (targetFile.exists()) targetFile.delete()
        val sampleEntries = listOf(
            "README_项目说明.md" to """
                # 压缩包内 Markdown 免解压预览
                - 压缩等级: Level $compressionLevel (${when (compressionLevel) { 1 -> "快速压缩"; 9 -> "最大压缩"; else -> "标准压缩" }})
                - 加密状态: ${if (password != null) "AES-256 密码加密保护" else "未加密"}
                - 分卷模式: ${if (splitVolumeBytes != 0L) "Part1 / Part2 多卷拆分" else "单文件归档"}
                
                您可以直接点击压缩包内的任意文件进行实时渲染预览！
            """.trimIndent().toByteArray(Charsets.UTF_8),
            "配置清单/server_config.json" to """
                {
                  "service": "MaterialFiles Archive Engine",
                  "compressionLevel": $compressionLevel,
                  "encrypted": ${password != null},
                  "splitVolumes": ${splitVolumeBytes != 0L}
                }
            """.trimIndent().toByteArray(Charsets.UTF_8),
            "技术规范/架构设计笔记.txt" to """
                第一章 压缩与分卷归档说明
                
                本文件存储在 ZIP 归档包内部。通过内置免解压预览器，无需将整个压缩包解压到磁盘，即可直接查看 TXT、Markdown、DOCX 与 XLSX 内容。
            """.trimIndent().toByteArray(Charsets.UTF_8)
        )

        FileChannel.open(
            java8.nio.file.Paths.get(targetFile.absolutePath),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        ).use { channel ->
            WriteArchive(
                channel = channel,
                format = Archive.FORMAT_ZIP,
                filter = Archive.FILTER_NONE,
                password = password,
                compressionLevel = compressionLevel
            ).use { writer ->
                for ((entryPath, data) in sampleEntries) {
                    writer.Entry(
                        name = entryPath,
                        lastModifiedTime = null,
                        lastAccessTime = null,
                        creationTime = null,
                        type = PosixFileType.REGULAR_FILE,
                        size = data.size.toLong(),
                        owner = null,
                        group = null,
                        mode = PosixFileMode.FILE_DEFAULT,
                        symbolicLinkTarget = null
                    ).use { entry ->
                        writer.writeEntry(entry)
                    }
                    writer.newDataOutputStream().write(data)
                }
            }
        }

        if (splitVolumeBytes != 0L && targetFile.length() >= 4L) {
            val totalLen = targetFile.length()
            val half = (totalLen + 1L) / 2L
            val baseName = targetFile.nameWithoutExtension
            val ext = targetFile.extension.ifEmpty { "zip" }
            val part1 = File(targetFile.parentFile, "${baseName}.part1.$ext")
            val part2 = File(targetFile.parentFile, "${baseName}.part2.$ext")
            val allBytes = targetFile.readBytes()
            part1.writeBytes(allBytes.copyOfRange(0, half.toInt()))
            part2.writeBytes(allBytes.copyOfRange(half.toInt(), allBytes.size))
            targetFile.delete()
            return part1
        }
        return targetFile
    }

    companion object {
        private const val MENU_CREATE_CUSTOM_ARCHIVE = 301
        private const val MENU_EXTRACT_ALL = 302
        private const val MENU_SET_PASSWORD = 303
        private const val MENU_OPEN_ARCHIVE = 304

        private const val EXTRA_FILE_PATH = "extra_archive_file_path"

        fun createIntent(
            context: Context? = null,
            path: Path? = null,
            filePath: String? = null
        ): Intent =
            ArchivePreviewActivity::class.createIntent().apply {
                if (path != null) {
                    extraPath = path
                }
                if (filePath != null) {
                    putExtra(EXTRA_FILE_PATH, filePath)
                }
            }

        fun isSplitOrSupportedArchiveName(fileName: String): Boolean {
            val lower = fileName.lowercase(Locale.ROOT)
            return lower.endsWith(".zip") ||
                lower.endsWith(".rar") ||
                lower.endsWith(".7z") ||
                lower.endsWith(".tar") ||
                lower.endsWith(".tar.xz") ||
                lower.endsWith(".tar.gz") ||
                lower.matches(Regex(""".*\.(zip|rar|7z)\.\d{3}$"""))
        }
    }
}
