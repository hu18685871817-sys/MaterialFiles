package me.zhanghai.android.files.viewer.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import java8.nio.file.Path
import kotlin.math.abs
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.viewer.document.DocumentPreviewActivity

/**
 * Port & Adaptation of Readext (https://github.com/thoughtram/read-ext)
 *
 * Implements the `ReadextStreamEngine` (`readIntoVec` / `readIntoString` stream extension trait)
 * along with native `.epub` archive stream extraction, Readext Bionic Focus Reading, Full-Text
 * Keyword Search, TTS Voice Read-Aloud, and persistent reading progress memory cache.
 */
class ReaderActivity : AppActivity(), TextToSpeech.OnInitListener {

    /**
     * Port of `thoughtram/read-ext` (`ReadExt::read_into_vec` & `ReadExt::read_into_string`)
     * High-throughput chunked stream reader with automatic charset detection and EPUB stream decoding.
     */
    object ReadextStreamEngine {
        private const val CHUNK_SIZE = 16 * 1024

        fun readIntoVec(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }

        fun readIntoString(input: InputStream, fileNameHint: String = ""): String {
            val bytes = readIntoVec(input)
            if (bytes.isEmpty()) return "（空文件）"

            val lowerName = fileNameHint.lowercase(Locale.ROOT)
            val isZipHeader = bytes.size >= 4 &&
                bytes[0] == 0x50.toByte() &&
                bytes[1] == 0x4B.toByte() &&
                bytes[2] == 0x03.toByte() &&
                bytes[3] == 0x04.toByte()
            if (lowerName.endsWith(".epub") || isZipHeader) {
                val epubText = runCatching { decodeEpubBytes(bytes) }.getOrNull()
                if (!epubText.isNullOrBlank()) {
                    return epubText
                }
            }

            return decodeTextBytes(bytes)
        }

        fun decodeTextBytes(bytes: ByteArray): String {
            if (bytes.isEmpty()) return "（空文件）"
            // Check BOM
            if (bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
            ) {
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            }
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }

            val utf8 = String(bytes, Charsets.UTF_8)
            if (utf8.count { it == '\uFFFD' } > 3) {
                return runCatching { String(bytes, Charset.forName("GB18030")) }
                    .recoverCatching { String(bytes, Charset.forName("GBK")) }
                    .getOrDefault(utf8)
            }
            return utf8
        }

        private fun decodeEpubBytes(bytes: ByteArray): String {
            val chapters = mutableListOf<Pair<String, String>>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name.lowercase(Locale.ROOT)
                    if (!entry.isDirectory &&
                        (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) &&
                        !name.contains("toc") && !name.contains("nav")
                    ) {
                        val entryBytes = readIntoVec(zis)
                        val rawHtml = decodeTextBytes(entryBytes)
                        val cleaned = htmlToPlainText(rawHtml)
                        if (cleaned.length > 20) {
                            chapters.add(entry.name to cleaned)
                        }
                    }
                    zis.closeEntry()
                }
            }
            if (chapters.isEmpty()) return ""
            return chapters.sortedBy { it.first }
                .mapIndexed { idx, (_, body) ->
                    "第 ${idx + 1} 节\n$body"
                }
                .joinToString("\n\n")
        }

        private fun htmlToPlainText(html: String): String {
            val withoutScripts = html
                .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), "")
                .replace(Regex("(?i)</(p|div|h1|h2|h3|h4|li|tr|br\\s*/?)>"), "\n")
                .replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
            return withoutScripts.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
        }
    }

    data class ChapterItem(
        val title: String,
        val charOffset: Int
    )

    data class PageSlice(
        val index: Int,
        val startOffset: Int,
        val endOffset: Int,
        val text: String,
        val chapterTitle: String
    )

    private lateinit var preferences: ReaderPreferences
    private var currentFilePath: String = ""
    private var currentTitle: String = ""
    private var currentBookKey: String = ""
    private var fullText: String = ""
    private var chapters: List<ChapterItem> = emptyList()
    private var pages: List<PageSlice> = emptyList()
    private var currentPageIndex: Int = 0
    private var currentCharOffset: Int = 0
    private var bookmarks: MutableList<ReaderBookmark> = mutableListOf()

    // Readext specific states
    private var isOverlayMenuVisible: Boolean = false
    private var currentBrightness: Float = 0.75f
    private var isBionicFocusMode: Boolean = false
    private var lastSearchKeyword: String = ""

    // Readext TTS Read-Aloud Engine
    private var ttsEngine: TextToSpeech? = null
    private var isTtsReady: Boolean = false
    private var isTtsSpeaking: Boolean = false

    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var rootFrame: FrameLayout
    private lateinit var readingCanvasLayout: LinearLayout
    private lateinit var topMiniHeaderText: TextView
    private lateinit var readerStageFrame: FrameLayout
    private lateinit var pagedTextView: TextView
    private lateinit var verticalScrollView: ScrollView
    private lateinit var verticalScrollTextView: TextView
    private lateinit var bottomMiniStatusRow: LinearLayout
    private lateinit var miniChapterText: TextView
    private lateinit var miniProgressText: TextView

    // Readext Floating Top & Bottom Overlay Menus (Hidden by default)
    private lateinit var topOverlayBar: LinearLayout
    private lateinit var overlayTitleText: TextView
    private lateinit var overlaySubtitleText: TextView
    private lateinit var ttsPillBtn: TextView
    private lateinit var bionicPillBtn: TextView

    private lateinit var bottomOverlayBar: LinearLayout
    private lateinit var pageSeekBar: SeekBar
    private lateinit var seekProgressLabel: TextView

    // Toast/Banner for Readext HUD
    private lateinit var floatingToastCard: MaterialCardView
    private lateinit var floatingToastText: TextView

    // Readext Settings & Progress History Management Screen (shown when launched from Home/Drawer)
    private lateinit var phoneBookshelfOverlay: LinearLayout
    private lateinit var bookshelfListContainer: LinearLayout

    private val hideToastRunnable = Runnable {
        floatingToastCard.animate().alpha(0f).setDuration(220L).withEndAction {
            floatingToastCard.visibility = View.GONE
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = ReaderProgressStore.loadPreferences(this)
        applyKeepScreenOnPreference()

        runCatching {
            ttsEngine = TextToSpeech(this, this)
        }

        setContentView(buildReadextLayout())
        applyThemeAndTypography()
        resolveInitialBook(intent)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = ttsEngine?.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsEngine?.language = Locale.getDefault()
            }
            ttsEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    uiHandler.post {
                        if (isTtsSpeaking && currentPageIndex < pages.lastIndex) {
                            turnToNextPage()
                            speakCurrentPage()
                        } else {
                            stopReadextTts()
                        }
                    }
                }
                override fun onError(utteranceId: String?) {
                    uiHandler.post { stopReadextTts() }
                }
            })
            isTtsReady = true
        }
    }

    private fun applyKeepScreenOnPreference() {
        if (preferences.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveInitialBook(intent)
    }

    override fun onPause() {
        super.onPause()
        persistReadingProgress()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        runCatching {
            ttsEngine?.stop()
            ttsEngine?.shutdown()
        }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (phoneBookshelfOverlay.visibility == View.VISIBLE || !preferences.volumeKeyPageTurn) {
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            setOverlayMenuVisibility(false)
            turnToNextPage()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            setOverlayMenuVisibility(false)
            turnToPreviousPage()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildReadextLayout(): View {
        rootFrame = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Fullscreen Immersive Readext Canvas (100% dedicated to reading)
        readingCanvasLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Subtle Readext Top Mini Header
        topMiniHeaderText = TextView(this).apply {
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(20), dp(6), dp(20), dp(2))
        }
        readingCanvasLayout.addView(topMiniHeaderText)

        // Main Text Area (Paged or Vertical Scroll)
        readerStageFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        pagedTextView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(20), dp(8), dp(20), dp(8))
            gravity = Gravity.TOP or Gravity.START
        }
        setupReadextTouchGestures(pagedTextView)
        readerStageFrame.addView(pagedTextView)

        verticalScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                    if (abs(scrollY - oldScrollY) > dp(8) && isOverlayMenuVisible) {
                        setOverlayMenuVisibility(false)
                    }
                    if (preferences.pageTurnMode == PageTurnMode.VERTICAL_SCROLL && fullText.isNotEmpty()) {
                        val contentHeight = (verticalScrollTextView.height - height).coerceAtLeast(1)
                        val ratio = (scrollY.toFloat() / contentHeight.toFloat()).coerceIn(0f, 1f)
                        currentCharOffset = (ratio * fullText.length).toInt().coerceIn(0, fullText.length.coerceAtLeast(1) - 1)
                        currentPageIndex = findPageIndexForOffset(currentCharOffset)
                        updateMiniStatusAndSeekBar(fromScroll = true, scrollRatio = ratio)
                        persistReadingProgress()
                    }
                }
            }
        }
        verticalScrollTextView = TextView(this).apply {
            setPadding(dp(20), dp(8), dp(20), dp(28))
            setOnClickListener {
                setOverlayMenuVisibility(!isOverlayMenuVisible)
            }
        }
        verticalScrollView.addView(verticalScrollTextView)
        readerStageFrame.addView(verticalScrollView)
        readingCanvasLayout.addView(readerStageFrame)

        // Subtle Readext Bottom Mini Status Bar
        bottomMiniStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(2), dp(20), dp(6))
        }
        miniChapterText = TextView(this).apply {
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        miniProgressText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        bottomMiniStatusRow.addView(miniChapterText)
        bottomMiniStatusRow.addView(miniProgressText)
        readingCanvasLayout.addView(bottomMiniStatusRow)

        rootFrame.addView(readingCanvasLayout)

        // 2. Floating Readext HUD Card
        floatingToastCard = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            setCardBackgroundColor(Color.parseColor("#E6064E3B"))
            cardElevation = dp(6).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#10B981")
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
        }
        floatingToastText = TextView(this).apply {
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        floatingToastCard.addView(floatingToastText)
        rootFrame.addView(floatingToastCard)

        // 3. Readext Floating Top Bar (Hidden by default; shown only when center screen tapped)
        topOverlayBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#EE0F172A"))
            setPadding(dp(10), dp(10), dp(12), dp(10))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }
        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                persistReadingProgress()
                finish()
            }
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        overlayTitleText = TextView(this).apply {
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        overlaySubtitleText = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#6EE7B7"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(overlayTitleText)
        titleCol.addView(overlaySubtitleText)

        val searchBtn = makeTopPill("🔍") {
            setOverlayMenuVisibility(false)
            showReadextSearchDialog()
        }
        bionicPillBtn = makeTopPill("👁️") {
            toggleBionicFocusMode()
        }
        ttsPillBtn = makeTopPill("🔊") {
            toggleReadextTts()
        }
        val addBookmarkBtn = makeTopPill("🔖") {
            addBookmarkAtCurrentPosition()
        }

        topOverlayBar.addView(backBtn)
        topOverlayBar.addView(titleCol)
        topOverlayBar.addView(searchBtn)
        topOverlayBar.addView(bionicPillBtn)
        topOverlayBar.addView(ttsPillBtn)
        topOverlayBar.addView(addBookmarkBtn)
        rootFrame.addView(topOverlayBar)

        // 4. Readext Floating Bottom Control Panel (Hidden by default)
        bottomOverlayBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F00F172A"))
            setPadding(dp(14), dp(10), dp(14), dp(14))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        // Row 1: Chapter / Page Progress Slider
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val prevChapBtn = makeTopPill("⏮") {
            jumpToAdjacentChapter(-1)
        }
        pageSeekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && pages.isNotEmpty()) {
                        val targetIdx = progress.coerceIn(0, pages.lastIndex)
                        jumpToPageIndex(targetIdx, animate = false)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    persistReadingProgress()
                }
            })
        }
        val nextChapBtn = makeTopPill("⏭") {
            jumpToAdjacentChapter(1)
        }
        seekRow.addView(prevChapBtn)
        seekRow.addView(pageSeekBar)
        seekRow.addView(nextChapBtn)
        bottomOverlayBar.addView(seekRow)

        seekProgressLabel = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#A7F3D0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        bottomOverlayBar.addView(seekProgressLabel)

        // Row 2: Readext 5 Bottom Menu Icon Tabs
        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabsRow.addView(makeReadextBottomTab("📑") {
            setOverlayMenuVisibility(false)
            showTocAndMemoryDialog()
        })
        tabsRow.addView(makeReadextBottomTab("Aa") {
            setOverlayMenuVisibility(false)
            showVisualTypographySheet()
        })
        tabsRow.addView(makeReadextBottomTab("📖") {
            setOverlayMenuVisibility(false)
            showPageTurnModeSheet()
        })
        tabsRow.addView(makeReadextBottomTab("🌗") {
            cycleThemeQuickly()
        })
        tabsRow.addView(makeReadextBottomTab("⚙️") {
            setOverlayMenuVisibility(false)
            showPhoneBookshelfOverlay()
        })
        bottomOverlayBar.addView(tabsRow)
        rootFrame.addView(bottomOverlayBar)

        // 5. Readext Settings & Progress History Management Screen (shown when launched from Home/Drawer)
        phoneBookshelfOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val shelfHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#064E3B"))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val shelfBack = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(6), 0, dp(14), 0)
            setOnClickListener {
                if (fullText.isNotEmpty()) {
                    phoneBookshelfOverlay.visibility = View.GONE
                } else {
                    finish()
                }
            }
        }
        val shelfTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val shelfTitle = TextView(this).apply {
            text = "📗 Readext 阅读器设置 · 记录与进度管理"
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val shelfSub = TextView(this).apply {
            text = "基于 thoughtram/read-ext 流式解析引擎 · 支持 TXT / MD / EPUB / LOG"
            textSize = 11f
            setTextColor(Color.parseColor("#A7F3D0"))
        }
        shelfTitleCol.addView(shelfTitle)
        shelfTitleCol.addView(shelfSub)
        shelfHeader.addView(shelfBack)
        shelfHeader.addView(shelfTitleCol)
        phoneBookshelfOverlay.addView(shelfHeader)

        val shelfScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        bookshelfListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(28))
        }
        shelfScroll.addView(bookshelfListContainer)
        phoneBookshelfOverlay.addView(shelfScroll)
        rootFrame.addView(phoneBookshelfOverlay)

        // Apply status bar & navigation bar safe insets so top/bottom controls are never obscured
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val topSafe = sysBars.top.coerceAtLeast(dp(24))
            val bottomSafe = sysBars.bottom
            readingCanvasLayout.setPadding(sysBars.left, topSafe, sysBars.right, bottomSafe)
            topOverlayBar.setPadding(sysBars.left + dp(10), topSafe + dp(8), sysBars.right + dp(12), dp(10))
            bottomOverlayBar.setPadding(sysBars.left + dp(14), dp(10), sysBars.right + dp(14), bottomSafe + dp(14))
            shelfHeader.setPadding(sysBars.left + dp(14), topSafe + dp(10), sysBars.right + dp(14), dp(12))
            bookshelfListContainer.setPadding(dp(14), dp(12), dp(14), bottomSafe + dp(28))
            insets
        }

        return rootFrame
    }

    private fun makeTopPill(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(5) }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#10B981"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeReadextBottomTab(icon: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = icon
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#10B981"))
                gravity = Gravity.CENTER
            })
            setOnClickListener { onClick() }
        }
    }

    private fun setOverlayMenuVisibility(visible: Boolean) {
        isOverlayMenuVisible = visible
        topOverlayBar.visibility = if (visible) View.VISIBLE else View.GONE
        bottomOverlayBar.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showFloatingHud(message: String, durationMs: Long = 1600L) {
        floatingToastText.text = message
        floatingToastCard.visibility = View.VISIBLE
        floatingToastCard.alpha = 1f
        uiHandler.removeCallbacks(hideToastRunnable)
        uiHandler.postDelayed(hideToastRunnable, durationMs)
    }

    /**
     * Readext 3-Zone Touch + Left-Edge Brightness Slide + Horizontal Swipe Page Turn
     */
    private fun setupReadextTouchGestures(view: View) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val w = view.width.coerceAtLeast(1)
                when {
                    e.x < w * 0.33f -> {
                        if (isOverlayMenuVisible) {
                            setOverlayMenuVisibility(false)
                        } else {
                            turnToPreviousPage()
                        }
                    }
                    e.x > w * 0.67f -> {
                        if (isOverlayMenuVisible) {
                            setOverlayMenuVisibility(false)
                        } else {
                            turnToNextPage()
                        }
                    }
                    else -> {
                        setOverlayMenuVisibility(!isOverlayMenuVisible)
                    }
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                val start = e1 ?: return false
                val w = view.width.coerceAtLeast(1)
                val h = view.height.coerceAtLeast(1)
                if (start.x < w * 0.16f && abs(distanceY) > abs(distanceX)) {
                    val delta = distanceY / (h * 0.75f)
                    currentBrightness = (currentBrightness + delta).coerceIn(0.05f, 1.0f)
                    val lp = window.attributes
                    lp.screenBrightness = currentBrightness
                    window.attributes = lp
                    val pct = (currentBrightness * 100f).toInt()
                    showFloatingHud("☀️ Readext 阅读亮度 $pct%", 900L)
                    return true
                }
                return false
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dx) > abs(dy) && abs(dx) > dp(36)) {
                    if (isOverlayMenuVisible) setOverlayMenuVisibility(false)
                    if (dx < 0) {
                        turnToNextPage()
                    } else {
                        turnToPreviousPage()
                    }
                    return true
                }
                return false
            }
        })

        view.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    private fun toggleBionicFocusMode() {
        isBionicFocusMode = !isBionicFocusMode
        bionicPillBtn.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor(if (isBionicFocusMode) "#059669" else "#1E293B"))
            setStroke(dp(1), Color.parseColor("#10B981"))
        }
        renderCurrentPageText()
        showFloatingHud(
            if (isBionicFocusMode) "👁️ Readext 仿生专注速读已开启（词首高亮加速阅读）"
            else "👁️ 已恢复标准排版显示"
        )
    }

    private fun toggleReadextTts() {
        if (isTtsSpeaking) {
            stopReadextTts()
            showFloatingHud("🔇 已停止 Readext 语音朗读")
            return
        }
        if (!isTtsReady || ttsEngine == null) {
            showFloatingHud("语音引擎正在初始化，请稍候重试")
            return
        }
        isTtsSpeaking = true
        ttsPillBtn.text = "⏹"
        ttsPillBtn.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#059669"))
            setStroke(dp(1), Color.parseColor("#A7F3D0"))
        }
        speakCurrentPage()
        showFloatingHud("🔊 Readext 语音听书已开启（读完本页自动翻页）")
    }

    private fun speakCurrentPage() {
        if (!isTtsSpeaking || pages.isEmpty()) return
        val slice = pages[currentPageIndex.coerceIn(0, pages.lastIndex)]
        val textToRead = slice.text.take(3500)
        ttsEngine?.speak(textToRead, TextToSpeech.QUEUE_FLUSH, null, "READEXT_PAGE_$currentPageIndex")
    }

    private fun stopReadextTts() {
        isTtsSpeaking = false
        runCatching { ttsEngine?.stop() }
        if (::ttsPillBtn.isInitialized) {
            ttsPillBtn.text = "🔊"
            ttsPillBtn.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#10B981"))
            }
        }
    }

    private fun showReadextSearchDialog() {
        if (fullText.isEmpty() || pages.isEmpty()) return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val input = EditText(this).apply {
            hint = "输入要搜索的关键词或句子..."
            setText(lastSearchKeyword)
            textSize = 14f
        }
        val resultSummary = TextView(this).apply {
            text = "输入关键词后点击「查找」，可快速定位到对应页码"
            textSize = 12f
            setTextColor(Color.parseColor("#059669"))
            setPadding(0, dp(6), 0, dp(6))
        }
        val resultScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(240)
            )
        }
        val resultList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        resultScroll.addView(resultList)
        layout.addView(input)
        layout.addView(resultSummary)
        layout.addView(resultScroll)

        var dialogRef: androidx.appcompat.app.AlertDialog? = null

        fun performSearch(kw: String) {
            val query = kw.trim()
            lastSearchKeyword = query
            resultList.removeAllViews()
            if (query.isEmpty()) {
                resultSummary.text = "请输入非空关键词"
                return
            }
            val matches = mutableListOf<Pair<PageSlice, String>>()
            for (page in pages) {
                val pos = page.text.indexOf(query, ignoreCase = true)
                if (pos >= 0) {
                    val start = (pos - 16).coerceAtLeast(0)
                    val end = (pos + query.length + 28).coerceAtMost(page.text.length)
                    val snippet = page.text.substring(start, end).replace('\n', ' ')
                    matches.add(page to snippet)
                    if (matches.size >= 50) break
                }
            }
            resultSummary.text = "🔍 共找到 ${matches.size}${if (matches.size >= 50) "+" else ""} 处匹配结果（点击直达对应页）："
            for ((slice, snippet) in matches) {
                val itemView = TextView(this).apply {
                    text = "第 ${slice.index + 1} 页 · [${slice.chapterTitle}]\n…$snippet…"
                    textSize = 12.5f
                    setTextColor(Color.parseColor("#1E293B"))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.parseColor("#F1F5F9"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    setOnClickListener {
                        jumpToPageIndex(slice.index, animate = true)
                        dialogRef?.dismiss()
                        showFloatingHud("🔍 已定位到第 ${slice.index + 1} 页「$query」")
                    }
                }
                resultList.addView(itemView)
            }
        }

        if (lastSearchKeyword.isNotEmpty()) {
            performSearch(lastSearchKeyword)
        }

        dialogRef = MaterialAlertDialogBuilder(this)
            .setTitle("🔍 Readext 全文流式检索")
            .setView(layout)
            .setPositiveButton("查找", null)
            .setNeutralButton("清除高亮") { _, _ ->
                lastSearchKeyword = ""
                renderCurrentPageText()
            }
            .setNegativeButton(R.string.close, null)
            .create()

        dialogRef.show()
        dialogRef.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            performSearch(input.text?.toString().orEmpty())
            renderCurrentPageText()
        }
    }

    private fun formatReadextSpannable(raw: String): CharSequence {
        if (!isBionicFocusMode && lastSearchKeyword.isBlank()) {
            return raw
        }
        val sb = SpannableStringBuilder(raw)
        if (isBionicFocusMode) {
            // Highlight prefix of English words and initial characters of clauses for Bionic Focus reading
            val wordRegex = Regex("""([A-Za-z]{3,}|[\u4e00-\u9fa5]{2,})""")
            for (match in wordRegex.findAll(raw)) {
                val start = match.range.first
                val len = match.value.length
                val boldLen = (len / 2).coerceIn(1, 3)
                sb.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    (start + boldLen).coerceAtMost(raw.length),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        if (lastSearchKeyword.isNotBlank()) {
            val kw = lastSearchKeyword
            var idx = raw.indexOf(kw, ignoreCase = true)
            while (idx >= 0) {
                sb.setSpan(
                    ForegroundColorSpan(Color.parseColor("#10B981")),
                    idx,
                    (idx + kw.length).coerceAtMost(raw.length),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    StyleSpan(Typeface.BOLD),
                    idx,
                    (idx + kw.length).coerceAtMost(raw.length),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                idx = raw.indexOf(kw, idx + kw.length, ignoreCase = true)
            }
        }
        return sb
    }

    private fun renderCurrentPageText() {
        if (pages.isEmpty()) return
        val slice = pages[currentPageIndex.coerceIn(0, pages.lastIndex)]
        pagedTextView.text = formatReadextSpannable(slice.text)
    }

    private fun applyThemeAndTypography() {
        val theme = preferences.theme
        val bgColor = Color.parseColor(theme.backgroundColorHex)
        val txtColor = Color.parseColor(theme.textColorHex)
        val subColor = Color.parseColor(theme.secondaryTextHex)

        readingCanvasLayout.setBackgroundColor(bgColor)
        readerStageFrame.setBackgroundColor(bgColor)
        topMiniHeaderText.setTextColor(subColor)
        miniChapterText.setTextColor(subColor)
        miniProgressText.setTextColor(subColor)

        pagedTextView.setTextColor(txtColor)
        pagedTextView.textSize = preferences.fontSizeSp
        pagedTextView.setLineSpacing(0f, preferences.lineSpacingMultiplier)

        verticalScrollTextView.setTextColor(txtColor)
        verticalScrollTextView.textSize = preferences.fontSizeSp
        verticalScrollTextView.setLineSpacing(0f, preferences.lineSpacingMultiplier)
        bottomMiniStatusRow.visibility = if (preferences.showBottomMiniStatusBar) View.VISIBLE else View.GONE
    }

    private fun cycleThemeQuickly() {
        val all = ReaderTheme.entries
        val next = all[(preferences.theme.ordinal + 1) % all.size]
        preferences = preferences.copy(theme = next)
        ReaderProgressStore.savePreferences(this, preferences)
        applyThemeAndTypography()
        showFloatingHud("🎨 Readext 主题：${next.displayName}")
    }

    private fun updateFontSize(newSizeSp: Float) {
        val savedOffset = currentCharOffset
        preferences = preferences.copy(fontSizeSp = newSizeSp.coerceIn(12f, 36f))
        ReaderProgressStore.savePreferences(this, preferences)
        applyThemeAndTypography()

        if (fullText.isNotEmpty()) {
            repaginateBook()
            val targetIdx = findPageIndexForOffset(savedOffset)
            jumpToPageIndex(targetIdx, animate = false)
            persistReadingProgress()
        }
    }

    private fun updateLineSpacing(newMultiplier: Float) {
        val savedOffset = currentCharOffset
        preferences = preferences.copy(lineSpacingMultiplier = newMultiplier.coerceIn(1.1f, 2.2f))
        ReaderProgressStore.savePreferences(this, preferences)
        applyThemeAndTypography()

        if (fullText.isNotEmpty()) {
            repaginateBook()
            val targetIdx = findPageIndexForOffset(savedOffset)
            jumpToPageIndex(targetIdx, animate = false)
            persistReadingProgress()
        }
    }

    private fun updatePageTurnMode(newMode: PageTurnMode) {
        val savedOffset = currentCharOffset
        preferences = preferences.copy(pageTurnMode = newMode)
        ReaderProgressStore.savePreferences(this, preferences)

        if (newMode == PageTurnMode.VERTICAL_SCROLL) {
            pagedTextView.visibility = View.GONE
            verticalScrollView.visibility = View.VISIBLE
            verticalScrollTextView.text = fullText
            verticalScrollView.post {
                val totalHeight = (verticalScrollTextView.height - verticalScrollView.height).coerceAtLeast(1)
                val ratio = if (fullText.isNotEmpty()) {
                    savedOffset.toFloat() / fullText.length.toFloat()
                } else 0f
                verticalScrollView.scrollTo(0, (ratio * totalHeight).toInt())
                updateMiniStatusAndSeekBar(fromScroll = true, scrollRatio = ratio)
            }
        } else {
            verticalScrollView.visibility = View.GONE
            pagedTextView.visibility = View.VISIBLE
            val targetIdx = findPageIndexForOffset(savedOffset)
            jumpToPageIndex(targetIdx, animate = false)
        }
        persistReadingProgress()
    }

    private fun resolveInitialBook(intent: Intent) {
        val pathExtra = intent.extraPath
        val fileFromPath = runCatching { pathExtra?.toFile() }.getOrNull()
            ?: intent.getStringExtra(EXTRA_FILE_PATH)?.let { File(it) }

        if (fileFromPath != null && fileFromPath.exists()) {
            phoneBookshelfOverlay.visibility = View.GONE
            loadBookFromFile(fileFromPath)
            return
        }

        val uri = intent.data
        if (uri != null) {
            phoneBookshelfOverlay.visibility = View.GONE
            loadBookFromUri(uri)
            return
        }

        // Opened from Home Dashboard / Navigation Drawer without a file: show Readext Settings & History Management!
        showPhoneBookshelfOverlay()
    }

    private fun showPhoneBookshelfOverlay() {
        phoneBookshelfOverlay.visibility = View.VISIBLE
        bookshelfListContainer.removeAllViews()

        // 1. Default Typography & Visual Theme Settings Card
        val typographyCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(Color.WHITE)
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val typoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        typoCol.addView(TextView(this).apply {
            text = "📗 Readext 流式阅读引擎 · 默认排版与字号设置"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#064E3B"))
            setPadding(0, 0, 0, dp(4))
        })
        typoCol.addView(TextView(this).apply {
            text = "基于 thoughtram/read-ext 移植适配，支持 TXT / MD / EPUB / LOG 流式解码、全文搜索、仿生速读与 TTS 听书："
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(12))
        })

        // Live Typography Preview Box
        val sampleBox = TextView(this).apply {
            text = "Readext 排版预览：当前字号 ${preferences.fontSizeSp.toInt()}sp · 行距 ${preferences.lineSpacingMultiplier}x · 主题「${preferences.theme.displayName}」 · 翻页「${preferences.pageTurnMode.displayName}」"
            textSize = preferences.fontSizeSp.coerceIn(13f, 24f)
            setLineSpacing(0f, preferences.lineSpacingMultiplier)
            setTextColor(Color.parseColor(preferences.theme.textColorHex))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(preferences.theme.backgroundColorHex))
                setStroke(dp(1), Color.parseColor("#10B981"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        typoCol.addView(sampleBox)

        // Font size controls
        val fontRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        fontRow.addView(TextView(this).apply {
            text = "默认字号 (${preferences.fontSizeSp.toInt()} sp)"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        fontRow.addView(MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "A- 缩小"
            textSize = 12f
            setOnClickListener {
                updateFontSize(preferences.fontSizeSp - 2f)
                showPhoneBookshelfOverlay()
            }
        })
        fontRow.addView(MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "A+ 放大"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            setOnClickListener {
                updateFontSize(preferences.fontSizeSp + 2f)
                showPhoneBookshelfOverlay()
            }
        })
        typoCol.addView(fontRow)

        // Line Spacing selector
        typoCol.addView(TextView(this).apply {
            text = "段落行距倍速:"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, dp(4), 0, dp(6))
        })
        val spacingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        listOf(1.25f to "紧凑 1.25x", 1.5f to "标准 1.5x", 1.85f to "宽松 1.85x").forEach { (mult, label) ->
            val selected = abs(preferences.lineSpacingMultiplier - mult) < 0.08f
            val chip = TextView(this).apply {
                text = if (selected) "✓ $label" else label
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#334155"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (selected) Color.parseColor("#059669") else Color.parseColor("#F1F5F9"))
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    updateLineSpacing(mult)
                    showPhoneBookshelfOverlay()
                }
            }
            spacingRow.addView(chip)
        }
        typoCol.addView(spacingRow)

        // Theme selector
        typoCol.addView(TextView(this).apply {
            text = "默认阅读背景主题:"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, dp(4), 0, dp(6))
        })
        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        for (t in ReaderTheme.entries) {
            val selected = preferences.theme == t
            val btn = TextView(this).apply {
                text = if (selected) "✓ ${t.displayName}" else t.displayName
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(t.textColorHex))
                setPadding(dp(6), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor(t.backgroundColorHex))
                    setStroke(dp(if (selected) 2 else 1), Color.parseColor(if (selected) "#059669" else "#94A3B8"))
                }
                setOnClickListener {
                    preferences = preferences.copy(theme = t)
                    ReaderProgressStore.savePreferences(this@ReaderActivity, preferences)
                    applyThemeAndTypography()
                    showPhoneBookshelfOverlay()
                }
            }
            themeRow.addView(btn)
        }
        typoCol.addView(themeRow)

        // Page Turn Mode selector
        typoCol.addView(TextView(this).apply {
            text = "默认翻页模式:"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, dp(4), 0, dp(6))
        })
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (m in PageTurnMode.entries) {
            val selected = preferences.pageTurnMode == m
            val chip = TextView(this).apply {
                text = if (selected) "✓ ${m.displayName}" else m.displayName
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#334155"))
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (selected) Color.parseColor("#059669") else Color.parseColor("#F1F5F9"))
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    updatePageTurnMode(m)
                    showPhoneBookshelfOverlay()
                }
            }
            modeRow.addView(chip)
        }
        typoCol.addView(modeRow)
        typographyCard.addView(typoCol)
        bookshelfListContainer.addView(typographyCard)

        // 2. Reading Behavior Switches Card
        val behaviorCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(Color.WHITE)
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val behaviorCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        behaviorCol.addView(TextView(this).apply {
            text = "⚙️ Readext 阅读交互与流式特性开关"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, 0, 0, dp(6))
        })

        fun addSwitchRow(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
            val cb = CheckBox(this).apply {
                text = title
                textSize = 13.5f
                setTextColor(Color.parseColor("#1E293B"))
                isChecked = checked
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnCheckedChangeListener { _, isChecked ->
                    onToggle(isChecked)
                }
            }
            behaviorCol.addView(cb)
        }

        addSwitchRow("自动记忆并在下次打开时恢复阅读进度", preferences.autoRestoreProgress) { enabled ->
            preferences = preferences.copy(autoRestoreProgress = enabled)
            ReaderProgressStore.savePreferences(this, preferences)
        }
        addSwitchRow("启用 Readext 仿生专注速读模式 (词首加粗引导视线)", isBionicFocusMode) { enabled ->
            isBionicFocusMode = enabled
            renderCurrentPageText()
        }
        addSwitchRow("使用手机音量加/减键上下翻页", preferences.volumeKeyPageTurn) { enabled ->
            preferences = preferences.copy(volumeKeyPageTurn = enabled)
            ReaderProgressStore.savePreferences(this, preferences)
        }
        addSwitchRow("阅读时保持屏幕常亮不熄屏", preferences.keepScreenOn) { enabled ->
            preferences = preferences.copy(keepScreenOn = enabled)
            ReaderProgressStore.savePreferences(this, preferences)
            applyKeepScreenOnPreference()
        }
        addSwitchRow("显示底部极简章节与页码状态栏", preferences.showBottomMiniStatusBar) { enabled ->
            preferences = preferences.copy(showBottomMiniStatusBar = enabled)
            ReaderProgressStore.savePreferences(this, preferences)
            applyThemeAndTypography()
        }
        behaviorCard.addView(behaviorCol)
        bookshelfListContainer.addView(behaviorCard)

        // 3. Reading History & Progress Cache Management Card
        val historyCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(Color.WHITE)
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val historyCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val cachedHistory = ReaderProgressStore.getAllBookProgress(this)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        headerRow.addView(TextView(this).apply {
            text = "🔖 阅读记录与进度缓存 (${cachedHistory.size} 条)"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (cachedHistory.isNotEmpty()) {
            val clearAllBtn = TextView(this).apply {
                text = "🧹 清除全部记录与进度"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#B91C1C"))
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor("#FEE2E2"))
                }
                setOnClickListener {
                    MaterialAlertDialogBuilder(this@ReaderActivity)
                        .setTitle("清除全部阅读记录与进度")
                        .setMessage("确定要清空所有书籍的阅读历史记录、阅读进度百分比和书签缓存吗？")
                        .setPositiveButton("确认清空") { _, _ ->
                            ReaderProgressStore.clearAllBookProgress(this@ReaderActivity)
                            if (fullText.isNotEmpty()) {
                                bookmarks.clear()
                                currentCharOffset = 0
                                currentPageIndex = 0
                                jumpToPageIndex(0, animate = false)
                            }
                            showToast("已清除全部阅读记录与进度缓存")
                            showPhoneBookshelfOverlay()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            headerRow.addView(clearAllBtn)
        }
        historyCol.addView(headerRow)

        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        if (cachedHistory.isEmpty()) {
            historyCol.addView(TextView(this).apply {
                text = "暂无保存的阅读记录与进度缓存。\n当您在文件列表中点击打开 TXT / EPUB / MD 电子书阅读时，Readext 将自动在此记录进度。"
                textSize = 12.5f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            for (record in cachedHistory) {
                val itemBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.parseColor("#F8FAFC"))
                        setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }
                val pctStr = String.format(Locale.US, "%.1f%%", record.progressPercent)
                itemBox.addView(TextView(this).apply {
                    text = "📗 ${record.title}"
                    textSize = 13.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                itemBox.addView(TextView(this).apply {
                    text = "进度: 第 ${record.pageIndex + 1}/${record.totalPages} 页 ($pctStr) · ${record.chapterTitle} · 书签 ${record.bookmarks.size} 个\n更新于 ${df.format(Date(record.updatedAtMillis))}"
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#64748B"))
                    setPadding(0, dp(2), 0, dp(8))
                })

                val actionRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
                val resetBtn = TextView(this).apply {
                    text = "🔄 重置进度为 0%"
                    textSize = 11.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#047857"))
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.parseColor("#ECFDF5"))
                    }
                    setOnClickListener {
                        ReaderProgressStore.resetBookProgressToZero(this@ReaderActivity, record.bookKey)
                        if (currentBookKey == record.bookKey && fullText.isNotEmpty()) {
                            bookmarks.clear()
                            currentCharOffset = 0
                            currentPageIndex = 0
                            jumpToPageIndex(0, animate = false)
                        }
                        showToast("已将《${record.title}》阅读进度重置为 0%")
                        showPhoneBookshelfOverlay()
                    }
                }
                val deleteBtn = TextView(this).apply {
                    text = "🗑️ 删除该条记录"
                    textSize = 11.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#B91C1C"))
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(8) }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.parseColor("#FEF2F2"))
                    }
                    setOnClickListener {
                        ReaderProgressStore.removeBookProgress(this@ReaderActivity, record.bookKey)
                        showToast("已删除《${record.title}》的阅读记录")
                        showPhoneBookshelfOverlay()
                    }
                }
                actionRow.addView(resetBtn)
                actionRow.addView(deleteBtn)
                itemBox.addView(actionRow)
                historyCol.addView(itemBox)
            }
        }
        historyCard.addView(historyCol)
        bookshelfListContainer.addView(historyCard)
    }

    private fun loadBookFromFile(file: File) {
        currentFilePath = file.absolutePath
        currentTitle = file.name
        val text = runCatching {
            FileInputStream(file).use { fis ->
                ReadextStreamEngine.readIntoString(fis, file.name)
            }
        }.getOrDefault("（读取失败或空文件）")
        setupBookContent(
            filePath = file.absolutePath,
            title = file.name,
            content = text
        )
    }

    private fun loadBookFromUri(uri: Uri) {
        var displayName = uri.lastPathSegment ?: "阅读文档.txt"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                displayName = cursor.getString(idx) ?: displayName
            }
        }
        val text = contentResolver.openInputStream(uri)?.use { stream ->
            ReadextStreamEngine.readIntoString(stream, displayName)
        } ?: "（无法打开文档流）"
        setupBookContent(
            filePath = uri.toString(),
            title = displayName,
            content = text
        )
    }

    private fun setupBookContent(filePath: String, title: String, content: String) {
        currentFilePath = filePath
        currentTitle = title
        currentBookKey = ReaderProgressStore.computeBookKey(filePath, title)
        fullText = content.replace("\r\n", "\n").replace("\r", "\n").ifBlank { "（空文档）" }

        chapters = extractChapters(fullText)
        repaginateBook()

        topMiniHeaderText.text = "📗 Readext · $title"
        overlayTitleText.text = title
        setOverlayMenuVisibility(false)

        val savedProgress = if (preferences.autoRestoreProgress) {
            ReaderProgressStore.getBookProgress(this, currentBookKey)
        } else null
        if (savedProgress != null) {
            bookmarks = savedProgress.bookmarks.toMutableList()
            val restoredOffset = savedProgress.charOffset.coerceIn(0, (fullText.length - 1).coerceAtLeast(0))
            val restoredPage = findPageIndexForOffset(restoredOffset)
            currentCharOffset = restoredOffset
            currentPageIndex = restoredPage
            updatePageTurnMode(preferences.pageTurnMode)

            if (restoredOffset > 0 || restoredPage > 0) {
                val pctStr = String.format(Locale.US, "%.1f%%", savedProgress.progressPercent)
                showFloatingHud(
                    "📗 Readext 已自动恢复上次阅读进度\n第 ${restoredPage + 1}/${pages.size} 页 ($pctStr · ${savedProgress.chapterTitle})",
                    2200L
                )
            }
        } else {
            bookmarks = mutableListOf()
            currentCharOffset = 0
            currentPageIndex = 0
            updatePageTurnMode(preferences.pageTurnMode)
        }

        persistReadingProgress()
    }

    private fun extractChapters(text: String): List<ChapterItem> {
        val result = mutableListOf<ChapterItem>()
        val chapterRegex = Regex(
            """(?m)^[ \t]*(第[零一二三四五六七八九十百千万0-9]+[章节回卷集部篇][^\n]{0,35}|Chapter\s+\d+[^\n]{0,35}|#{1,3}\s+[^\n]{1,40})$"""
        )
        for (match in chapterRegex.findAll(text)) {
            val heading = match.value.trim().removePrefix("#").trim()
            if (heading.isNotEmpty()) {
                result.add(ChapterItem(title = heading, charOffset = match.range.first))
            }
        }
        if (result.isEmpty()) {
            result.add(ChapterItem(title = "正文", charOffset = 0))
        }
        return result
    }

    private fun repaginateBook() {
        val screenHeightDp = resources.configuration.screenHeightDp.coerceAtLeast(480)
        val screenWidthDp = resources.configuration.screenWidthDp.coerceAtLeast(320)
        val fontFactor = (18f / preferences.fontSizeSp).coerceIn(0.45f, 1.6f)
        val charsPerLine = ((screenWidthDp - 40) / (preferences.fontSizeSp * 0.95f)).toInt().coerceIn(14, 55)
        val linesPerPage = ((screenHeightDp - 96) / (preferences.fontSizeSp * preferences.lineSpacingMultiplier * 1.15f))
            .toInt().coerceIn(10, 42)
        val targetCharsPerPage = (charsPerLine * linesPerPage * fontFactor).toInt().coerceIn(260, 1600)

        val slices = mutableListOf<PageSlice>()
        var cursor = 0
        val totalLen = fullText.length
        var pageIdx = 0

        while (cursor < totalLen) {
            var end = (cursor + targetCharsPerPage).coerceAtMost(totalLen)
            if (end < totalLen) {
                val lastNewline = fullText.lastIndexOf('\n', end)
                if (lastNewline > cursor + (targetCharsPerPage / 2)) {
                    end = lastNewline + 1
                }
            }
            val pageText = fullText.substring(cursor, end).trimStart('\n')
            val chapterTitle = findChapterForOffset(cursor)
            slices.add(
                PageSlice(
                    index = pageIdx++,
                    startOffset = cursor,
                    endOffset = end,
                    text = pageText.ifEmpty { " " },
                    chapterTitle = chapterTitle
                )
            )
            cursor = end
        }

        if (slices.isEmpty()) {
            slices.add(PageSlice(0, 0, fullText.length, fullText, "正文"))
        }
        pages = slices
        pageSeekBar.max = (pages.size - 1).coerceAtLeast(1)
    }

    private fun findChapterForOffset(offset: Int): String {
        var current = chapters.firstOrNull()?.title ?: "正文"
        for (ch in chapters) {
            if (ch.charOffset <= offset) {
                current = ch.title
            } else {
                break
            }
        }
        return current
    }

    private fun findPageIndexForOffset(offset: Int): Int {
        if (pages.isEmpty()) return 0
        val found = pages.indexOfFirst { offset in it.startOffset until it.endOffset }
        return if (found >= 0) found else pages.lastIndex
    }

    private fun jumpToAdjacentChapter(delta: Int) {
        if (chapters.size <= 1) {
            if (delta > 0) turnToNextPage() else turnToPreviousPage()
            return
        }
        val curChapIdx = chapters.indexOfLast { it.charOffset <= currentCharOffset }.coerceAtLeast(0)
        val nextChapIdx = (curChapIdx + delta).coerceIn(0, chapters.lastIndex)
        val targetChap = chapters[nextChapIdx]
        val pageIdx = findPageIndexForOffset(targetChap.charOffset)
        jumpToPageIndex(pageIdx, animate = true)
        showFloatingHud("📗 ${targetChap.title}")
    }

    private fun jumpToPageIndex(index: Int, animate: Boolean) {
        if (pages.isEmpty()) return
        val clamped = index.coerceIn(0, pages.lastIndex)
        val oldIndex = currentPageIndex
        currentPageIndex = clamped
        val slice = pages[clamped]
        currentCharOffset = slice.startOffset

        if (preferences.pageTurnMode == PageTurnMode.VERTICAL_SCROLL) {
            verticalScrollView.post {
                val totalHeight = (verticalScrollTextView.height - verticalScrollView.height).coerceAtLeast(1)
                val ratio = slice.startOffset.toFloat() / fullText.length.coerceAtLeast(1).toFloat()
                verticalScrollView.smoothScrollTo(0, (ratio * totalHeight).toInt())
            }
        } else {
            val formattedText = formatReadextSpannable(slice.text)
            if (animate && preferences.pageTurnMode == PageTurnMode.SWIPE_PAGER && oldIndex != clamped) {
                val direction = if (clamped > oldIndex) 1 else -1
                pagedTextView.translationX = (direction * dp(28)).toFloat()
                pagedTextView.alpha = 0.4f
                pagedTextView.text = formattedText
                pagedTextView.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(160L)
                    .start()
            } else {
                pagedTextView.translationX = 0f
                pagedTextView.alpha = 1f
                pagedTextView.text = formattedText
            }
        }

        updateMiniStatusAndSeekBar(fromScroll = false, scrollRatio = 0f)
        persistReadingProgress()
    }

    private fun turnToNextPage() {
        if (preferences.pageTurnMode == PageTurnMode.VERTICAL_SCROLL) {
            verticalScrollView.smoothScrollBy(0, (verticalScrollView.height * 0.85f).toInt())
            return
        }
        if (currentPageIndex < pages.lastIndex) {
            jumpToPageIndex(currentPageIndex + 1, animate = preferences.pageTurnMode == PageTurnMode.SWIPE_PAGER)
        } else {
            showFloatingHud("已是最后一页")
        }
    }

    private fun turnToPreviousPage() {
        if (preferences.pageTurnMode == PageTurnMode.VERTICAL_SCROLL) {
            verticalScrollView.smoothScrollBy(0, -(verticalScrollView.height * 0.85f).toInt())
            return
        }
        if (currentPageIndex > 0) {
            jumpToPageIndex(currentPageIndex - 1, animate = preferences.pageTurnMode == PageTurnMode.SWIPE_PAGER)
        } else {
            showFloatingHud("已是第一页")
        }
    }

    private fun updateMiniStatusAndSeekBar(fromScroll: Boolean, scrollRatio: Float) {
        val total = pages.size.coerceAtLeast(1)
        val idx = currentPageIndex.coerceIn(0, total - 1)
        val chapterName = findChapterForOffset(currentCharOffset)
        val pct = if (fromScroll) {
            (scrollRatio * 100f).coerceIn(0f, 100f)
        } else if (total <= 1) {
            100f
        } else {
            ((idx.toFloat() / (total - 1).toFloat()) * 100f).coerceIn(0f, 100f)
        }

        miniChapterText.text = chapterName
        miniProgressText.text = String.format(Locale.US, "%d/%d · %.1f%%", idx + 1, total, pct)
        overlaySubtitleText.text = "Readext Engine · $chapterName · ${String.format(Locale.US, "%.1f%%", pct)}"
        seekProgressLabel.text = "$chapterName · 第 ${idx + 1} / $total 页 (${String.format(Locale.US, "%.1f%%", pct)})"

        if (!fromScroll) {
            pageSeekBar.progress = idx
        }
    }

    private fun persistReadingProgress() {
        if (currentBookKey.isEmpty() || fullText.isEmpty()) return
        val total = pages.size.coerceAtLeast(1)
        val pct = if (total <= 1) 100f else ((currentPageIndex.toFloat() / (total - 1).toFloat()) * 100f).coerceIn(0f, 100f)
        val progress = BookReadingProgress(
            bookKey = currentBookKey,
            filePath = currentFilePath,
            title = currentTitle,
            charOffset = currentCharOffset,
            pageIndex = currentPageIndex,
            totalPages = total,
            progressPercent = pct,
            chapterTitle = findChapterForOffset(currentCharOffset),
            updatedAtMillis = System.currentTimeMillis(),
            bookmarks = bookmarks
        )
        ReaderProgressStore.saveBookProgress(this, progress)
    }

    private fun addBookmarkAtCurrentPosition() {
        val snippetEnd = (currentCharOffset + 42).coerceAtMost(fullText.length)
        val snippet = fullText.substring(currentCharOffset.coerceAtLeast(0), snippetEnd)
            .replace('\n', ' ')
            .trim()
        val chapter = findChapterForOffset(currentCharOffset)
        bookmarks.add(
            0,
            ReaderBookmark(
                charOffset = currentCharOffset,
                snippet = "$snippet...",
                chapterTitle = chapter,
                createdAtMillis = System.currentTimeMillis()
            )
        )
        persistReadingProgress()
        showFloatingHud("🔖 已添加 Readext 书签：[$chapter] 第 ${currentPageIndex + 1} 页")
    }

    private fun showVisualTypographySheet() {
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        scroll.addView(content)

        val sizeLabel = TextView(this).apply {
            text = "字体大小调节: ${preferences.fontSizeSp.toInt()}sp"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        }
        content.addView(sizeLabel)

        val fontRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(12))
        }
        val minusBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "A-"
            setOnClickListener {
                updateFontSize(preferences.fontSizeSp - 2f)
                sizeLabel.text = "字体大小调节: ${preferences.fontSizeSp.toInt()}sp"
            }
        }
        val sizeBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            max = 24
            progress = (preferences.fontSizeSp.toInt() - 12).coerceIn(0, 24)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val sp = (12 + progress).toFloat()
                        updateFontSize(sp)
                        sizeLabel.text = "字体大小调节: ${sp.toInt()}sp"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        val plusBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "A+"
            setOnClickListener {
                updateFontSize(preferences.fontSizeSp + 2f)
                sizeLabel.text = "字体大小调节: ${preferences.fontSizeSp.toInt()}sp"
            }
        }
        fontRow.addView(minusBtn)
        fontRow.addView(sizeBar)
        fontRow.addView(plusBtn)
        content.addView(fontRow)

        // Line Spacing
        content.addView(TextView(this).apply {
            text = "段落行距:"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        val spacingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        listOf(1.25f to "紧凑 1.25x", 1.5f to "标准 1.5x", 1.85f to "宽松 1.85x").forEach { (mult, name) ->
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = name
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) }
                setOnClickListener {
                    updateLineSpacing(mult)
                }
            }
            spacingRow.addView(btn)
        }
        content.addView(spacingRow)

        // Theme Cards
        content.addView(TextView(this).apply {
            text = "阅读背景主题:"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (t in ReaderTheme.entries) {
            val btn = TextView(this).apply {
                text = t.displayName
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(t.textColorHex))
                setPadding(dp(8), dp(12), dp(8), dp(12))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) }
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor(t.backgroundColorHex))
                    setStroke(dp(1), Color.parseColor("#10B981"))
                }
                setOnClickListener {
                    preferences = preferences.copy(theme = t)
                    ReaderProgressStore.savePreferences(this@ReaderActivity, preferences)
                    applyThemeAndTypography()
                }
            }
            themeRow.addView(btn)
        }
        content.addView(themeRow)

        MaterialAlertDialogBuilder(this)
            .setTitle("📗 Readext · 视觉与排版设置")
            .setView(scroll)
            .setPositiveButton("完成", null)
            .show()
    }

    private fun showPageTurnModeSheet() {
        val modes = PageTurnMode.entries
        val labels = modes.map { mode ->
            val currentMark = if (mode == preferences.pageTurnMode) "  ✓ 当前使用" else ""
            "${mode.displayName}$currentMark"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("选择 Readext 翻页方式")
            .setItems(labels) { _, which ->
                val chosen = modes[which]
                updatePageTurnMode(chosen)
                showFloatingHud("📗 翻页方式：${chosen.displayName}")
            }
            .show()
    }

    private fun showTocAndMemoryDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        scroll.addView(container)

        var dialogRef: androidx.appcompat.app.AlertDialog? = null

        // Chapters section
        container.addView(TextView(this).apply {
            text = "📑 章节目录 (${chapters.size} 章)"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        for ((i, ch) in chapters.withIndex()) {
            val pageNum = findPageIndexForOffset(ch.charOffset) + 1
            val tv = TextView(this).apply {
                text = "${i + 1}. ${ch.title}  (第 $pageNum 页)"
                textSize = 13.5f
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    jumpToPageIndex(pageNum - 1, animate = true)
                    dialogRef?.dismiss()
                }
            }
            container.addView(tv)
        }

        // Bookmarks section
        container.addView(TextView(this).apply {
            text = "\n🔖 当前书籍书签 (${bookmarks.size})"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(6))
        })
        if (bookmarks.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无书签（点击阅读界面中间呼出顶部栏可随时添加书签）"
                textSize = 12.5f
                setTextColor(Color.parseColor("#64748B"))
            })
        } else {
            for (bm in bookmarks) {
                val pageNum = findPageIndexForOffset(bm.charOffset) + 1
                val bmView = TextView(this).apply {
                    text = "🔖 [${bm.chapterTitle} · 第${pageNum}页] ${bm.snippet}"
                    textSize = 13f
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener {
                        jumpToPageIndex(pageNum - 1, animate = true)
                        dialogRef?.dismiss()
                    }
                }
                container.addView(bmView)
            }
        }

        // Progress Reset & Clear section inside the book's TOC dialog
        val progressActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val resetCurrentBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "🔄 重置本书进度 (0%)"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                ReaderProgressStore.resetBookProgressToZero(this@ReaderActivity, currentBookKey)
                bookmarks.clear()
                currentCharOffset = 0
                currentPageIndex = 0
                jumpToPageIndex(0, animate = true)
                dialogRef?.dismiss()
                showFloatingHud("🔄 已将本书阅读进度重置为开头 (0%)")
            }
        }
        val clearCurrentBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "🗑️ 清除全部记录"
            textSize = 12f
            setTextColor(Color.parseColor("#B91C1C"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                ReaderProgressStore.clearAllBookProgress(this@ReaderActivity)
                bookmarks.clear()
                dialogRef?.dismiss()
                showFloatingHud("🧹 已清除所有书籍阅读记录与进度缓存")
            }
        }
        progressActionRow.addView(resetCurrentBtn)
        progressActionRow.addView(clearCurrentBtn)
        container.addView(progressActionRow)

        dialogRef = MaterialAlertDialogBuilder(this)
            .setTitle("📗 Readext · 目录、书签与进度管理")
            .setView(scroll)
            .setNeutralButton("Readext 全局设置") { _, _ ->
                showPhoneBookshelfOverlay()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    companion object {
        private const val EXTRA_FILE_PATH = "extra_reader_file_path"
        private const val EXTRA_BOOK_TITLE = "extra_reader_book_title"

        fun createIntent(
            context: Context? = null,
            path: Path? = null,
            filePath: String? = null,
            title: String? = null
        ): Intent =
            ReaderActivity::class.createIntent().apply {
                if (path != null) {
                    extraPath = path
                }
                if (filePath != null) {
                    putExtra(EXTRA_FILE_PATH, filePath)
                }
                if (title != null) {
                    putExtra(EXTRA_BOOK_TITLE, title)
                }
            }
    }
}
