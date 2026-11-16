package me.zhanghai.android.files.viewer.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.viewer.document.DocumentPreviewActivity
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

class ReaderActivity : AppActivity() {

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

    // Moon+ Reader (静读天下) Overlay State: Hidden by default during reading!
    private var isOverlayMenuVisible: Boolean = false
    private var currentBrightness: Float = 0.75f

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

    // Moon+ Reader Floating Top & Bottom Overlay Menus (Hidden by default)
    private lateinit var topOverlayBar: LinearLayout
    private lateinit var overlayTitleText: TextView
    private lateinit var overlaySubtitleText: TextView

    private lateinit var bottomOverlayBar: LinearLayout
    private lateinit var pageSeekBar: SeekBar
    private lateinit var seekProgressLabel: TextView

    // Toast/Banner for Memory Cache Restored & Brightness Gesture HUD
    private lateinit var floatingToastCard: MaterialCardView
    private lateinit var floatingToastText: TextView

    // Local Phone Bookshelf / File Scanner Overlay (shown when opened from drawer without a file)
    private lateinit var phoneBookshelfOverlay: LinearLayout
    private lateinit var bookshelfListContainer: LinearLayout

    private val hideToastRunnable = Runnable {
        floatingToastCard.animate().alpha(0f).setDuration(220L).withEndAction {
            floatingToastCard.visibility = View.GONE
        }.start()
    }

    private val pickBookLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            phoneBookshelfOverlay.visibility = View.GONE
            loadBookFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = ReaderProgressStore.loadPreferences(this)

        setContentView(buildMoonReaderLayout())
        applyThemeAndTypography()
        resolveInitialBook(intent)
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
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (phoneBookshelfOverlay.visibility == View.VISIBLE) {
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

    private fun buildMoonReaderLayout(): View {
        rootFrame = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Fullscreen Immersive Reading Canvas (100% dedicated to reading)
        readingCanvasLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Subtle Moon+ Reader Top Mini Header (Book Title, 20dp high)
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
        setupMoonReaderTouchGestures(pagedTextView)
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

        // Subtle Moon+ Reader Bottom Mini Status Bar (24dp high, never blocks reading)
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

        // 2. Floating HUD / Memory Restored Pill (auto-fades after 1.8s)
        floatingToastCard = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            setCardBackgroundColor(Color.parseColor("#D90F172A"))
            cardElevation = dp(6).toFloat()
            strokeWidth = 0
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

        // 3. Moon+ Reader Floating Top Bar (Hidden by default; shown only when center screen tapped)
        topOverlayBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#E61E293B"))
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
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(12), dp(4))
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
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        overlaySubtitleText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(overlayTitleText)
        titleCol.addView(overlaySubtitleText)

        val addBookmarkBtn = makeTopPill("🔖 加书签") {
            addBookmarkAtCurrentPosition()
        }
        val editBtn = makeTopPill("✏️ 编辑") {
            if (currentFilePath.isNotEmpty() && File(currentFilePath).exists()) {
                startActivity(DocumentPreviewActivity.createIntent(this, filePath = currentFilePath))
            }
        }
        topOverlayBar.addView(backBtn)
        topOverlayBar.addView(titleCol)
        topOverlayBar.addView(addBookmarkBtn)
        topOverlayBar.addView(editBtn)
        rootFrame.addView(topOverlayBar)

        // 4. Moon+ Reader Floating Bottom Control Panel (Hidden by default; shown only when center tapped)
        bottomOverlayBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EB1E293B"))
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
        val prevChapBtn = makeTopPill("上一章") {
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
        val nextChapBtn = makeTopPill("下一章") {
            jumpToAdjacentChapter(1)
        }
        seekRow.addView(prevChapBtn)
        seekRow.addView(pageSeekBar)
        seekRow.addView(nextChapBtn)
        bottomOverlayBar.addView(seekRow)

        seekProgressLabel = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#CBD5E1"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        bottomOverlayBar.addView(seekProgressLabel)

        // Row 2: Moon+ Reader 5 Bottom Menu Tabs
        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabsRow.addView(makeMoonBottomTab("📑", "目录/记忆") {
            setOverlayMenuVisibility(false)
            showTocAndMemoryDialog()
        })
        tabsRow.addView(makeMoonBottomTab("Aa", "排版字号") {
            setOverlayMenuVisibility(false)
            showVisualTypographySheet()
        })
        tabsRow.addView(makeMoonBottomTab("📖", "翻页方式") {
            setOverlayMenuVisibility(false)
            showPageTurnModeSheet()
        })
        tabsRow.addView(makeMoonBottomTab("🌗", "日夜护眼") {
            cycleThemeQuickly()
        })
        tabsRow.addView(makeMoonBottomTab("📂", "手机文件") {
            setOverlayMenuVisibility(false)
            showPhoneBookshelfOverlay()
        })
        bottomOverlayBar.addView(tabsRow)
        rootFrame.addView(bottomOverlayBar)

        // 5. Real Phone Bookshelf & Local File Scanner Overlay (when opened without a specific file)
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
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
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
        val shelfTitle = TextView(this).apply {
            text = "静读天下书架 · 手机本地书籍与记忆缓存"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pickDocBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "选择文件"
            textSize = 12f
            setTextColor(Color.WHITE)
            setOnClickListener {
                pickBookLauncher.launch(arrayOf("text/*", "*/*"))
            }
        }
        shelfHeader.addView(shelfBack)
        shelfHeader.addView(shelfTitle)
        shelfHeader.addView(pickDocBtn)
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
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }
        shelfScroll.addView(bookshelfListContainer)
        phoneBookshelfOverlay.addView(shelfScroll)
        rootFrame.addView(phoneBookshelfOverlay)

        return rootFrame
    }

    private fun makeTopPill(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#334155"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeMoonBottomTab(icon: String, label: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = icon
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(Color.parseColor("#CBD5E1"))
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
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
     * Moon+ Reader 3-Zone Touch + Left-Edge Brightness Slide + Horizontal Swipe Page Turn
     */
    private fun setupMoonReaderTouchGestures(view: View) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val w = view.width.coerceAtLeast(1)
                when {
                    // Left 1/3 zone: Previous Page (and hide overlay menu if open)
                    e.x < w * 0.33f -> {
                        if (isOverlayMenuVisible) {
                            setOverlayMenuVisibility(false)
                        } else {
                            turnToPreviousPage()
                        }
                    }
                    // Right 1/3 zone: Next Page (and hide overlay menu if open)
                    e.x > w * 0.67f -> {
                        if (isOverlayMenuVisible) {
                            setOverlayMenuVisibility(false)
                        } else {
                            turnToNextPage()
                        }
                    }
                    // Center 1/3 zone: Toggle Moon+ Reader Overlay Menu!
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
                // Moon+ Reader signature feature: Vertical slide along left edge (left 16% of screen) adjusts brightness!
                if (start.x < w * 0.16f && abs(distanceY) > abs(distanceX)) {
                    val delta = distanceY / (h * 0.75f)
                    currentBrightness = (currentBrightness + delta).coerceIn(0.05f, 1.0f)
                    val lp = window.attributes
                    lp.screenBrightness = currentBrightness
                    window.attributes = lp
                    val pct = (currentBrightness * 100f).toInt()
                    showFloatingHud("☀️ 阅读亮度 $pct%", 900L)
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
    }

    private fun cycleThemeQuickly() {
        val all = ReaderTheme.entries
        val next = all[(preferences.theme.ordinal + 1) % all.size]
        preferences = preferences.copy(theme = next)
        ReaderProgressStore.savePreferences(this, preferences)
        applyThemeAndTypography()
        showFloatingHud("🎨 已切换阅读主题：${next.displayName}")
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

        // Opened from Navigation Drawer without a file: show the Real Phone Bookshelf & Memory Cache History!
        showPhoneBookshelfOverlay()
    }

    private fun showPhoneBookshelfOverlay() {
        phoneBookshelfOverlay.visibility = View.VISIBLE
        bookshelfListContainer.removeAllViews()

        val pickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        val browseStorageBtn = MaterialButton(this).apply {
            text = "📂 从手机存储选择文件打开 (.txt / .md / .log)"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                pickBookLauncher.launch(arrayOf("text/*", "*/*"))
            }
        }
        pickRow.addView(browseStorageBtn)
        bookshelfListContainer.addView(pickRow)

        val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        // Section 1: Cached Reading History (Memory Cache)
        val cachedHistory = ReaderProgressStore.getAllBookProgress(this)
            .filter { it.filePath.isNotEmpty() && File(it.filePath).exists() }
        if (cachedHistory.isNotEmpty()) {
            bookshelfListContainer.addView(TextView(this).apply {
                text = "🔖 最近阅读记忆缓存（点击继续上次阅读进度）"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#0F172A"))
                setPadding(0, dp(4), 0, dp(8))
            })
            for (record in cachedHistory) {
                val card = MaterialCardView(this).apply {
                    radius = dp(10).toFloat()
                    setCardBackgroundColor(Color.WHITE)
                    strokeColor = Color.parseColor("#86EFAC")
                    strokeWidth = dp(1)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                    setOnClickListener {
                        val f = File(record.filePath)
                        if (f.exists()) {
                            phoneBookshelfOverlay.visibility = View.GONE
                            loadBookFromFile(f)
                        }
                    }
                }
                val pctStr = String.format(Locale.US, "%.1f%%", record.progressPercent)
                val tv = TextView(this).apply {
                    text = "📖 ${record.title}\n" +
                        "上次进度: 第 ${record.pageIndex + 1}/${record.totalPages} 页 ($pctStr) · ${record.chapterTitle}\n" +
                        "路径: ${record.filePath} · ${df.format(Date(record.updatedAtMillis))}"
                    textSize = 12.5f
                    setTextColor(Color.parseColor("#1E293B"))
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                }
                card.addView(tv)
                bookshelfListContainer.addView(card)
            }
        }

        // Section 2: Real Local Text/Book Files Scanned from Phone Storage
        val scanHeader = TextView(this).apply {
            text = "📱 手机存储中的文本与电子书文件（正在扫描...）"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(0, dp(10), 0, dp(8))
        }
        bookshelfListContainer.addView(scanHeader)

        val scannedListWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        bookshelfListContainer.addView(scannedListWrapper)

        thread {
            val foundBooks = mutableListOf<File>()
            val rootDir = Environment.getExternalStorageDirectory()
            val readableExts = setOf("txt", "md", "markdown", "log", "csv", "json", "xml", "html")
            fun scan(dir: File, depth: Int) {
                if (depth > 4 || !dir.exists() || !dir.canRead()) return
                val list = dir.listFiles() ?: return
                for (f in list) {
                    if (f.name.startsWith(".")) continue
                    if (f.isDirectory) {
                        if (!f.name.equals("Android", ignoreCase = true)) {
                            scan(f, depth + 1)
                        }
                    } else if (f.extension.lowercase(Locale.ROOT) in readableExts && f.length() > 0L) {
                        foundBooks.add(f)
                    }
                }
            }
            scan(rootDir, 0)
            val sortedBooks = foundBooks.sortedByDescending { it.lastModified() }

            runOnUiThread {
                scanHeader.text = "📱 手机存储中的文本与电子书 (${sortedBooks.size} 项)"
                scannedListWrapper.removeAllViews()
                if (sortedBooks.isEmpty()) {
                    scannedListWrapper.addView(TextView(this).apply {
                        text = "未在常用目录扫描到 .txt / .md 文件，请点击上方「从手机存储选择文件打开」浏览任意目录。"
                        textSize = 13f
                        setTextColor(Color.parseColor("#64748B"))
                        setPadding(dp(4), dp(12), dp(4), dp(12))
                    })
                } else {
                    for (file in sortedBooks.take(60)) {
                        val key = ReaderProgressStore.computeBookKey(file.absolutePath, file.name)
                        val cached = ReaderProgressStore.getBookProgress(this, key)
                        val card = MaterialCardView(this).apply {
                            radius = dp(10).toFloat()
                            setCardBackgroundColor(Color.WHITE)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp(8) }
                            setOnClickListener {
                                phoneBookshelfOverlay.visibility = View.GONE
                                loadBookFromFile(file)
                            }
                        }
                        val sizeStr = file.length().asFileSize().formatHumanReadable(this)
                        val progressBadge = cached?.let {
                            " · 🔖 上次读到 ${String.format(Locale.US, "%.1f%%", it.progressPercent)} (第${it.pageIndex + 1}页)"
                        }.orEmpty()
                        val tv = TextView(this).apply {
                            text = "📄 ${file.name}\n${file.parentFile?.name ?: ""} · $sizeStr$progressBadge"
                            textSize = 13f
                            setTextColor(Color.parseColor("#1E293B"))
                            setPadding(dp(14), dp(10), dp(14), dp(10))
                        }
                        card.addView(tv)
                        scannedListWrapper.addView(card)
                    }
                }
            }
        }
    }

    private fun loadBookFromFile(file: File) {
        currentFilePath = file.absolutePath
        currentTitle = file.name
        val bytes = runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
        val text = decodeTextBytes(bytes)
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
        val bytes = contentResolver.openInputStream(uri)?.use(InputStream::readBytes) ?: ByteArray(0)
        val text = decodeTextBytes(bytes)
        setupBookContent(
            filePath = uri.toString(),
            title = displayName,
            content = text
        )
    }

    private fun decodeTextBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "（空文件）"
        val utf8 = String(bytes, Charsets.UTF_8)
        if (utf8.count { it == '\uFFFD' } > 3) {
            return runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(utf8)
        }
        return utf8
    }

    private fun setupBookContent(filePath: String, title: String, content: String) {
        currentFilePath = filePath
        currentTitle = title
        currentBookKey = ReaderProgressStore.computeBookKey(filePath, title)
        fullText = content.replace("\r\n", "\n").replace("\r", "\n").ifBlank { "（空文档）" }

        chapters = extractChapters(fullText)
        repaginateBook()

        topMiniHeaderText.text = title
        overlayTitleText.text = title
        // Ensure overlay menu starts hidden for pure immersive reading!
        setOverlayMenuVisibility(false)

        // Restore reading progress from memory cache!
        val savedProgress = ReaderProgressStore.getBookProgress(this, currentBookKey)
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
                    "🔖 已自动恢复上次阅读进度\n第 ${restoredPage + 1}/${pages.size} 页 ($pctStr · ${savedProgress.chapterTitle})",
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
        showFloatingHud("📖 ${targetChap.title}")
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
            if (animate && preferences.pageTurnMode == PageTurnMode.SWIPE_PAGER && oldIndex != clamped) {
                val direction = if (clamped > oldIndex) 1 else -1
                pagedTextView.translationX = (direction * dp(28)).toFloat()
                pagedTextView.alpha = 0.4f
                pagedTextView.text = slice.text
                pagedTextView.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(160L)
                    .start()
            } else {
                pagedTextView.translationX = 0f
                pagedTextView.alpha = 1f
                pagedTextView.text = slice.text
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
        overlaySubtitleText.text = "$chapterName · 进度 ${String.format(Locale.US, "%.1f%%", pct)} (已实时缓存)"
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
        showFloatingHud("🔖 已添加书签：[$chapter] 第 ${currentPageIndex + 1} 页")
    }

    /**
     * Moon+ Reader Visual Options Sheet (Font Size, Line Spacing, Theme Cards)
     */
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
            max = 24 // 12sp .. 36sp
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
                    setStroke(dp(1), Color.parseColor("#94A3B8"))
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
            .setTitle("静读天下 · 视觉与排版设置")
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
            .setTitle("选择翻页方式")
            .setItems(labels) { _, which ->
                val chosen = modes[which]
                updatePageTurnMode(chosen)
                showFloatingHud("📖 翻页方式：${chosen.displayName}")
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

        dialogRef = MaterialAlertDialogBuilder(this)
            .setTitle("目录与书签")
            .setView(scroll)
            .setNeutralButton("阅读记忆书架") { _, _ ->
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
