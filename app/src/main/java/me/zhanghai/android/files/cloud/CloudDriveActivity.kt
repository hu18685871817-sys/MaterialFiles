package me.zhanghai.android.files.cloud

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.file.isAudio
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.viewer.archive.ArchivePreviewActivity
import me.zhanghai.android.files.viewer.document.DocumentPreviewActivity
import me.zhanghai.android.files.viewer.media.MediaPlayerActivity
import me.zhanghai.android.files.viewer.reader.ReaderActivity
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class CloudDriveActivity : AppActivity() {

    enum class WorkMode {
        WEB_CLIENT,
        LOCAL_AND_SAF,
        REST_API
    }

    data class FolderCrumb(val id: String, val name: String)

    private var activeProvider: CloudProvider = CloudProvider.GOOGLE_DRIVE
    private var currentWorkMode: WorkMode = WorkMode.WEB_CLIENT

    // REST API folder navigation state
    private val folderStack = ArrayDeque<FolderCrumb>()
    private var currentFolder = FolderCrumb("root", "云盘根目录 (Root)")
    private var currentSearchQuery: String = ""

    private lateinit var toolbar: MaterialToolbar
    private lateinit var providerTabLayout: TabLayout
    private lateinit var modeChipWeb: TextView
    private lateinit var modeChipLocalSaf: TextView
    private lateinit var modeChipApi: TextView
    private lateinit var statusBannerText: TextView
    private lateinit var webProgressBar: LinearProgressIndicator

    // Download Progress Bar Banner
    private lateinit var downloadProgressCard: MaterialCardView
    private lateinit var downloadProgressTitle: TextView
    private lateinit var downloadProgressBar: LinearProgressIndicator

    // 1. Real Official Web Client Section (Google Drive / OneDrive)
    private lateinit var webSectionContainer: LinearLayout
    private lateinit var webUrlStatusText: TextView
    private lateinit var cloudWebView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // 2. Local Downloaded Files & Android SAF Cloud Mount Section
    private lateinit var localSafSectionScroll: ScrollView
    private lateinit var localSafContentContainer: LinearLayout

    // 3. REST API v3 Direct Section
    private lateinit var restApiSectionScroll: ScrollView
    private lateinit var restApiContentContainer: LinearLayout

    private val webFileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback != null) {
            callback.onReceiveValue(uris.toTypedArray())
        }
    }

    private val safImportCloudFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            importDocumentsFromSaf(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialProviderName = intent.getStringExtra(EXTRA_PROVIDER)
        if (initialProviderName != null) {
            runCatching { CloudProvider.valueOf(initialProviderName) }.onSuccess {
                activeProvider = it
            }
        }

        setContentView(buildCloudDriveLayout())
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.cloud_drive_title)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentWorkMode == WorkMode.WEB_CLIENT && ::cloudWebView.isInitialized && cloudWebView.canGoBack()) {
                    cloudWebView.goBack()
                } else if (currentWorkMode == WorkMode.REST_API && folderStack.isNotEmpty()) {
                    currentFolder = folderStack.removeLast()
                    renderRestApiSection()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        switchProvider(activeProvider, forceReloadWeb = true)
    }

    override fun onDestroy() {
        runCatching {
            CookieManager.getInstance().flush()
            if (::cloudWebView.isInitialized) {
                cloudWebView.stopLoading()
                cloudWebView.destroy()
            }
        }
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildCloudDriveLayout(): View {
        val colorOnSurface = getColorByAttr(com.google.android.material.R.attr.colorOnSurface)
        val colorSurface = getColorByAttr(com.google.android.material.R.attr.colorSurface)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorSurface)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top AppBar with Toolbar + Provider Tabs (Google Drive / Microsoft OneDrive)
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
        providerTabLayout = TabLayout(this).apply {
            addTab(newTab().setText("Google Drive"))
            addTab(newTab().setText("Microsoft OneDrive"))
            getTabAt(if (activeProvider == CloudProvider.GOOGLE_DRIVE) 0 else 1)?.select()
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    val newProvider = if (tab?.position == 1) CloudProvider.ONEDRIVE else CloudProvider.GOOGLE_DRIVE
                    if (activeProvider != newProvider) {
                        switchProvider(newProvider, forceReloadWeb = true)
                    }
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
        }
        appBar.addView(toolbar)
        appBar.addView(providerTabLayout)
        root.addView(appBar)

        // Sub-header: Mode Switcher + Quick Account Login Bar
        val modeBarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val modeBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        modeChipWeb = makeModeChip("🌐 官方云盘网页端 (真实登录)") {
            switchWorkMode(WorkMode.WEB_CLIENT)
        }
        modeChipLocalSaf = makeModeChip("📥 已下载与系统云盘挂载") {
            switchWorkMode(WorkMode.LOCAL_AND_SAF)
        }
        modeChipApi = makeModeChip("🔑 OAuth API 直连") {
            switchWorkMode(WorkMode.REST_API)
        }
        val quickAccountBtn = makeModeChip("👤 账号切换/登录") {
            showAccountQuickLoginDialog()
        }

        modeBarRow.addView(modeChipWeb)
        modeBarRow.addView(modeChipLocalSaf)
        modeBarRow.addView(modeChipApi)
        modeBarRow.addView(quickAccountBtn)
        modeBarScroll.addView(modeBarRow)
        root.addView(modeBarScroll)

        // Status & Web Progress Bar
        statusBannerText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#E2E8F0"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(statusBannerText)

        webProgressBar = LinearProgressIndicator(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            trackThickness = dp(3)
            max = 100
            visibility = View.GONE
        }
        root.addView(webProgressBar)

        // Active Download Progress Card
        downloadProgressCard = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            visibility = View.GONE
            setCardBackgroundColor(Color.parseColor("#EFF6FF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), dp(8), dp(12), dp(4))
            }
        }
        val dlCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        downloadProgressTitle = TextView(this).apply {
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E3A8A"))
        }
        downloadProgressBar = LinearProgressIndicator(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            max = 100
        }
        dlCol.addView(downloadProgressTitle)
        dlCol.addView(downloadProgressBar)
        downloadProgressCard.addView(dlCol)
        root.addView(downloadProgressCard)

        // Main Frame holding the 3 working sections
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // Section 1: Real WebView Client for Google Drive & OneDrive
        webSectionContainer = buildWebClientSection()
        contentFrame.addView(webSectionContainer)

        // Section 2: Local Downloaded Files & Android SAF Cloud Mount
        localSafSectionScroll = ScrollView(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        localSafContentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(28))
        }
        localSafSectionScroll.addView(localSafContentContainer)
        contentFrame.addView(localSafSectionScroll)

        // Section 3: REST API v3 Direct Mode
        restApiSectionScroll = ScrollView(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        restApiContentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(28))
        }
        restApiSectionScroll.addView(restApiContentContainer)
        contentFrame.addView(restApiSectionScroll)

        root.addView(contentFrame)
        return root
    }

    private fun makeModeChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#334155"))
            }
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebClientSection(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Compact Navigation & Action Bar for the Cloud Web Client
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        fun makeNavBtn(text: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#1E293B"))
                setPadding(dp(8), dp(5), dp(8), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(4) }
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#E2E8F0"))
                }
                setOnClickListener { onClick() }
            }
        }

        val backBtn = makeNavBtn("⬅ 后退") {
            if (cloudWebView.canGoBack()) cloudWebView.goBack()
        }
        val reloadBtn = makeNavBtn("🔄 刷新") {
            cloudWebView.reload()
        }
        val homeBtn = makeNavBtn("🏠 云盘主页") {
            cloudWebView.loadUrl(activeProvider.webDriveHomeUrl)
        }
        webUrlStatusText = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#475569"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(4); marginEnd = dp(4) }
        }
        val openLocalBtn = makeNavBtn("📂 本地目录") {
            val dir = getLocalDownloadDirectory(activeProvider)
            dir.mkdirs()
            startActivitySafe(FileListActivity.createViewIntent(Paths.get(dir.absolutePath)))
        }

        navRow.addView(backBtn)
        navRow.addView(reloadBtn)
        navRow.addView(homeBtn)
        navRow.addView(webUrlStatusText)
        navRow.addView(openLocalBtn)
        container.addView(navRow)

        cloudWebView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadsImagesAutomatically = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                javaScriptCanOpenWindowsAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // Critical: Use standard Chrome Mobile User-Agent so Google Sign-In works directly
                userAgentString = CloudDriveClient.CHROME_MOBILE_USER_AGENT
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    webProgressBar.visibility = View.VISIBLE
                    webProgressBar.progress = 15
                    webUrlStatusText.text = url.orEmpty()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    webProgressBar.visibility = View.GONE
                    webUrlStatusText.text = view?.title?.takeIf { it.isNotBlank() } ?: url.orEmpty()
                    CookieManager.getInstance().flush()
                    updateTopStatusBanner()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val reqUri = request?.url ?: return false
                    val scheme = reqUri.scheme?.lowercase(Locale.ROOT).orEmpty()
                    if (scheme == "http" || scheme == "https") {
                        return false // Load inside our authenticated WebView
                    }
                    // Handle external intent:// or market:// links safely
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, reqUri))
                    }
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    webProgressBar.progress = newProgress
                    if (newProgress >= 100) {
                        webProgressBar.visibility = View.GONE
                    } else {
                        webProgressBar.visibility = View.VISIBLE
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@CloudDriveActivity.filePathCallback?.onReceiveValue(null)
                    this@CloudDriveActivity.filePathCallback = filePathCallback
                    return try {
                        webFileChooserLauncher.launch(arrayOf("*/*"))
                        true
                    } catch (e: Exception) {
                        this@CloudDriveActivity.filePathCallback = null
                        false
                    }
                }
            }

            // Intercept file downloads from Google Drive / OneDrive Web Client and save to local CloudDrive folder
            setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                if (url.startsWith("blob:")) {
                    showToast("正在通过浏览器触发云端文件下载...")
                    return@setDownloadListener
                }
                downloadCloudFileFromWebUrl(
                    url = url,
                    userAgent = userAgent ?: CloudDriveClient.CHROME_MOBILE_USER_AGENT,
                    contentDisposition = contentDisposition,
                    mimeType = mimetype
                )
            }
        }

        container.addView(cloudWebView)
        return container
    }

    private fun switchProvider(provider: CloudProvider, forceReloadWeb: Boolean) {
        activeProvider = provider
        folderStack.clear()
        currentFolder = FolderCrumb("root", "${provider.displayName} 根目录")
        currentSearchQuery = ""

        val brandColor = Color.parseColor(provider.brandColorHex)
        webProgressBar.setIndicatorColor(brandColor)
        downloadProgressBar.setIndicatorColor(brandColor)

        updateTopStatusBanner()
        updateModeChipsStyle()

        if (forceReloadWeb && ::cloudWebView.isInitialized) {
            val savedSession = CloudDriveClient.getActiveSession(this, provider)
            val targetUrl = if (CloudDriveClient.hasWebLoginSessionCookie(provider)) {
                provider.webDriveHomeUrl
            } else if (savedSession != null && savedSession.email.contains("@")) {
                CloudDriveClient.buildWebAuthUrl(provider, savedSession.email)
            } else {
                provider.webDriveHomeUrl
            }
            cloudWebView.loadUrl(targetUrl)
        }

        if (currentWorkMode == WorkMode.LOCAL_AND_SAF) {
            renderLocalAndSafSection()
        } else if (currentWorkMode == WorkMode.REST_API) {
            renderRestApiSection()
        }
    }

    private fun switchWorkMode(mode: WorkMode) {
        currentWorkMode = mode
        updateModeChipsStyle()
        webSectionContainer.visibility = if (mode == WorkMode.WEB_CLIENT) View.VISIBLE else View.GONE
        localSafSectionScroll.visibility = if (mode == WorkMode.LOCAL_AND_SAF) View.VISIBLE else View.GONE
        restApiSectionScroll.visibility = if (mode == WorkMode.REST_API) View.VISIBLE else View.GONE

        when (mode) {
            WorkMode.WEB_CLIENT -> updateTopStatusBanner()
            WorkMode.LOCAL_AND_SAF -> renderLocalAndSafSection()
            WorkMode.REST_API -> renderRestApiSection()
        }
    }

    private fun updateModeChipsStyle() {
        val brandColor = Color.parseColor(activeProvider.brandColorHex)
        val inactiveColor = Color.parseColor("#334155")

        fun styleChip(chip: TextView, selected: Boolean) {
            chip.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (selected) brandColor else inactiveColor)
            }
        }

        styleChip(modeChipWeb, currentWorkMode == WorkMode.WEB_CLIENT)
        styleChip(modeChipLocalSaf, currentWorkMode == WorkMode.LOCAL_AND_SAF)
        styleChip(modeChipApi, currentWorkMode == WorkMode.REST_API)
    }

    private fun updateTopStatusBanner() {
        val hasCookie = CloudDriveClient.hasWebLoginSessionCookie(activeProvider)
        val session = CloudDriveClient.getActiveSession(this, activeProvider)
        val localCount = getLocalDownloadDirectory(activeProvider)
            .listFiles()?.count { it.isFile } ?: 0

        statusBannerText.text = when {
            hasCookie && session != null && session.email.isNotBlank() ->
                "✅ 已连接 ${activeProvider.displayName} (${session.email}) · 点击云盘内任意文件的「下载」即可保存至本机 (已下载 $localCount 项)"
            hasCookie ->
                "✅ ${activeProvider.displayName} 官方会话已登录 · 可直接浏览、上传或点击文件下载至本机 (已下载 $localCount 项)"
            session != null && session.email.isNotBlank() ->
                "👤 当前预设账号: ${session.email} · 请在下方 ${activeProvider.displayName} 官方页面输入密码完成验证"
            else ->
                "💡 请在下方 ${activeProvider.displayName} 官方登录页输入您的真实账号与密码，或点击上方「👤 账号切换/登录」快速填入本机账号"
        }
    }

    /**
     * Dialog allowing the user to pick a real device Google/Microsoft account or enter their email
     * and immediately jump to the official login page pre-filled with that email, or sign out.
     */
    private fun showAccountQuickLoginDialog() {
        val discoveredAccounts = CloudDriveClient.getSelectableAccounts(this, activeProvider)
        val currentSession = CloudDriveClient.getActiveSession(this, activeProvider)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        container.addView(TextView(this).apply {
            text = "选择本机已关联的 ${activeProvider.accountTypeLabel} 或输入您的真实邮箱，系统将自动填入 ${activeProvider.displayName} 官方登录页进行密码验证："
            textSize = 13f
            setPadding(0, 0, 0, dp(10))
        })

        val emailInput = EditText(this).apply {
            hint = if (activeProvider == CloudProvider.GOOGLE_DRIVE) {
                "输入您的 Google 邮箱 (如 example@gmail.com)"
            } else {
                "输入您的 Microsoft 邮箱 (如 example@outlook.com)"
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(currentSession?.email ?: discoveredAccounts.firstOrNull().orEmpty())
            textSize = 14f
        }
        container.addView(emailInput)

        if (discoveredAccounts.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "本机历史/系统账号 (点击快速填入):"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(4))
            })
            for (acc in discoveredAccounts) {
                val btn = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = acc
                    textSize = 12f
                    setOnClickListener {
                        emailInput.setText(acc)
                    }
                }
                container.addView(btn)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("${activeProvider.displayName} 账号登录与切换")
            .setView(container)
            .setPositiveButton("前往官方页面输入密码登录") { _, _ ->
                val email = emailInput.text?.toString()?.trim().orEmpty()
                if (email.isNotEmpty()) {
                    CloudDriveClient.saveActiveSession(
                        this,
                        CloudAccountSession(
                            provider = activeProvider,
                            email = email,
                            displayName = email.substringBefore("@"),
                            accessToken = currentSession?.accessToken.orEmpty()
                        )
                    )
                }
                switchWorkMode(WorkMode.WEB_CLIENT)
                val authUrl = CloudDriveClient.buildWebAuthUrl(activeProvider, email)
                cloudWebView.loadUrl(authUrl)
                updateTopStatusBanner()
            }
            .setNeutralButton("退出当前账号并清除Cookie") { _, _ ->
                CloudDriveClient.logout(this, activeProvider)
                cloudWebView.clearCache(true)
                cloudWebView.loadUrl(activeProvider.webLoginBaseUrl)
                updateTopStatusBanner()
                showToast("已退出 ${activeProvider.displayName} 登录状态")
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun downloadCloudFileFromWebUrl(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val targetDir = getLocalDownloadDirectory(activeProvider)
        targetDir.mkdirs()

        val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        downloadProgressCard.visibility = View.VISIBLE
        downloadProgressTitle.text = "正在从 ${activeProvider.displayName} 下载: $guessedName"
        downloadProgressBar.isIndeterminate = true

        lifecycleScope.launch {
            val result = CloudDriveClient.downloadRealUrlToFile(
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                destinationDir = targetDir,
                fallbackFileName = guessedName
            ) { downloaded, total, resolvedName ->
                runOnUiThread {
                    val dlStr = downloaded.asFileSize().formatHumanReadable(this@CloudDriveActivity)
                    if (total > 0L) {
                        downloadProgressBar.isIndeterminate = false
                        val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                        downloadProgressBar.progress = pct
                        val totStr = total.asFileSize().formatHumanReadable(this@CloudDriveActivity)
                        downloadProgressTitle.text = "正在下载 $resolvedName: $dlStr / $totStr ($pct%)"
                    } else {
                        downloadProgressBar.isIndeterminate = true
                        downloadProgressTitle.text = "正在下载 $resolvedName: 已接收 $dlStr"
                    }
                }
            }

            downloadProgressCard.visibility = View.GONE
            result.onSuccess { savedFile ->
                showToast("下载完成: ${savedFile.name}")
                updateTopStatusBanner()
                showDownloadedFileActionDialog(savedFile)
            }.onFailure {
                // Fallback to Android system DownloadManager with cookies attached
                enqueueSystemDownloadManager(url, userAgent, contentDisposition, mimeType, guessedName)
            }
        }
    }

    private fun enqueueSystemDownloadManager(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
        fileName: String
    ) {
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookie)
                }
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription("正在从 ${activeProvider.displayName} 下载云端文件")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                if (!mimeType.isNullOrBlank()) {
                    setMimeType(mimeType)
                }
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "CloudDrive/${activeProvider.localSubDirName}/$fileName"
                )
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            showToast("已加入系统下载队列: Download/CloudDrive/${activeProvider.localSubDirName}/$fileName")
        }.onFailure { e ->
            showToast("无法启动下载: ${e.localizedMessage}")
        }
    }

    private fun showDownloadedFileActionDialog(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("云盘文件已下载至本机")
            .setMessage("文件名: ${file.name}\n大小: ${file.length().asFileSize().formatHumanReadable(this)}\n保存路径: ${file.absolutePath}")
            .setPositiveButton("立即打开") { _, _ ->
                openLocalFileSmart(file)
            }
            .setNeutralButton("打开所在文件夹") { _, _ ->
                val parent = file.parentFile ?: getLocalDownloadDirectory(activeProvider)
                startActivitySafe(FileListActivity.createViewIntent(Paths.get(parent.absolutePath)))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /**
     * Section 2: Local Downloaded Files & System Cloud Drive SAF Integration
     */
    private fun renderLocalAndSafSection() {
        val colorOnSurface = getColorByAttr(com.google.android.material.R.attr.colorOnSurface)
        val brandColor = Color.parseColor(activeProvider.brandColorHex)
        localSafContentContainer.removeAllViews()

        // 1. System Cloud Drive SAF Mount Card
        val safCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val safCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        safCol.addView(TextView(this).apply {
            text = "☁️ 挂载系统 ${activeProvider.displayName} 客户端 / SAF 云端存储"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface)
        })
        safCol.addView(TextView(this).apply {
            text = "如果您的手机已安装或登录系统级 Google Drive / OneDrive 客户端，可直接通过 Android SAF 文档提供器拉取云盘文件并同步到本地目录："
            textSize = 12.5f
            setTextColor(colorOnSurface)
            alpha = 0.78f
            setPadding(0, dp(6), 0, dp(10))
        })

        val safBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val pickSafBtn = MaterialButton(this).apply {
            text = "📂 从系统云盘选择并导入文件"
            textSize = 12f
            setBackgroundColor(brandColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(8) }
            setOnClickListener {
                safImportCloudFileLauncher.launch(arrayOf("*/*"))
            }
        }
        val openFolderBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "在文件列表中打开"
            textSize = 12f
            setOnClickListener {
                val dir = getLocalDownloadDirectory(activeProvider)
                dir.mkdirs()
                startActivitySafe(FileListActivity.createViewIntent(Paths.get(dir.absolutePath)))
            }
        }
        safBtnRow.addView(pickSafBtn)
        safBtnRow.addView(openFolderBtn)
        safCol.addView(safBtnRow)
        safCard.addView(safCol)
        localSafContentContainer.addView(safCard)

        // 2. Local Downloaded Files List
        val localDir = getLocalDownloadDirectory(activeProvider)
        localDir.mkdirs()
        val files = localDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

        val listCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val listCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        listCol.addView(TextView(this).apply {
            text = "📥 本机已下载的 ${activeProvider.displayName} 文件 (${files.size} 项)"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(brandColor)
        })
        listCol.addView(TextView(this).apply {
            text = "存储路径: ${localDir.absolutePath}"
            textSize = 11.5f
            setTextColor(colorOnSurface)
            alpha = 0.65f
            setPadding(0, dp(2), 0, dp(10))
        })

        if (files.isEmpty()) {
            listCol.addView(TextView(this).apply {
                text = "当前暂无已下载的 ${activeProvider.displayName} 文件。\n您可以在「🌐 官方云盘网页端」登录账号后点击任意文件下载，或点击上方「📂 从系统云盘选择并导入文件」。"
                textSize = 13f
                setTextColor(colorOnSurface)
                alpha = 0.75f
                setPadding(0, dp(12), 0, dp(12))
            })
        } else {
            for (file in files) {
                val mime = MimeType.guessFromPath(file.name)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    setOnClickListener { openLocalFileSmart(file) }
                }
                val icon = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
                    setImageResource(mime.iconRes)
                }
                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                textCol.addView(TextView(this).apply {
                    text = file.name
                    textSize = 13.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(colorOnSurface)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                })
                textCol.addView(TextView(this).apply {
                    text = file.length().asFileSize().formatHumanReadable(this@CloudDriveActivity)
                    textSize = 11.5f
                    setTextColor(colorOnSurface)
                    alpha = 0.7f
                })
                val openBtn = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "打开"
                    textSize = 11.5f
                    setOnClickListener { openLocalFileSmart(file) }
                }
                val delBtn = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "删除"
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#DC2626"))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(6) }
                    setOnClickListener {
                        file.delete()
                        showToast("已删除本地文件: ${file.name}")
                        renderLocalAndSafSection()
                        updateTopStatusBanner()
                    }
                }
                row.addView(icon)
                row.addView(textCol)
                row.addView(openBtn)
                row.addView(delBtn)
                listCol.addView(row)
            }
        }

        listCard.addView(listCol)
        localSafContentContainer.addView(listCard)
    }

    private fun importDocumentsFromSaf(uris: List<Uri>) {
        val targetDir = getLocalDownloadDirectory(activeProvider)
        targetDir.mkdirs()

        lifecycleScope.launch {
            var importedCount = 0
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    runCatching {
                        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "CloudFile_${System.currentTimeMillis()}"
                        contentResolver.query(uri, null, null, null, null)?.use { c ->
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0 && c.moveToFirst()) {
                                displayName = c.getString(idx) ?: displayName
                            }
                        }
                        val destFile = File(targetDir, displayName)
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (destFile.exists() && destFile.length() > 0L) {
                            importedCount++
                        }
                    }
                }
            }
            showToast("已成功导入 $importedCount 个云盘文件至 ${targetDir.name}")
            updateTopStatusBanner()
            renderLocalAndSafSection()
        }
    }

    /**
     * Section 3: Real Google Drive REST API v3 / Microsoft Graph API Direct Mode (using OAuth2 Bearer Token)
     */
    private fun renderRestApiSection() {
        val colorOnSurface = getColorByAttr(com.google.android.material.R.attr.colorOnSurface)
        val brandColor = Color.parseColor(activeProvider.brandColorHex)
        val session = CloudDriveClient.getActiveSession(this, activeProvider)
        restApiContentContainer.removeAllViews()

        val tokenCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val tokenCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        tokenCol.addView(TextView(this).apply {
            text = "🔑 ${activeProvider.displayName} REST API v3 原生直连"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface)
        })
        tokenCol.addView(TextView(this).apply {
            text = "如果您需要直接通过原生列表访问 Google Drive API v3 (googleapis.com/drive/v3/files) 或 Microsoft Graph API，请在下方填入有效的 OAuth2 Access Token (如 ya29.xxx)："
            textSize = 12f
            setTextColor(colorOnSurface)
            alpha = 0.75f
            setPadding(0, dp(4), 0, dp(8))
        })

        val tokenInput = EditText(this).apply {
            hint = "粘贴 OAuth2 Bearer Access Token (例如 ya29.a0AfH6SM...)"
            setText(session?.accessToken.orEmpty())
            textSize = 13f
            setSingleLine(true)
        }
        tokenCol.addView(tokenInput)

        val connectApiBtn = MaterialButton(this).apply {
            text = "连接 REST API 并拉取真实云端文件列表"
            textSize = 13f
            setBackgroundColor(brandColor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                val token = tokenInput.text?.toString()?.trim().orEmpty()
                if (token.isEmpty()) {
                    showToast("请先粘贴有效的 OAuth2 Access Token，或使用「🌐 官方云盘网页端」直接登录")
                    return@setOnClickListener
                }
                val newSession = CloudAccountSession(
                    provider = activeProvider,
                    email = session?.email.orEmpty(),
                    displayName = session?.displayName.orEmpty().ifBlank { activeProvider.displayName },
                    accessToken = token
                )
                CloudDriveClient.saveActiveSession(this@CloudDriveActivity, newSession)
                renderRestApiSection()
            }
        }
        tokenCol.addView(connectApiBtn)
        tokenCard.addView(tokenCol)
        restApiContentContainer.addView(tokenCard)

        val activeToken = session?.accessToken?.trim().orEmpty()
        if (activeToken.isEmpty()) {
            return
        }

        // Quota & File List Container for REST API
        val apiResultCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val apiListCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        val quotaTv = TextView(this).apply {
            text = "正在通过 REST API 查询云盘配额与目录 (${currentFolder.name})..."
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(brandColor)
        }
        apiListCol.addView(quotaTv)
        apiResultCard.addView(apiListCol)
        restApiContentContainer.addView(apiResultCard)

        lifecycleScope.launch {
            val validSession = session ?: return@launch
            CloudDriveClient.fetchAccountQuota(validSession).onSuccess { q ->
                val usedStr = q.usedBytes.asFileSize().formatHumanReadable(this@CloudDriveActivity)
                val totalStr = q.totalBytes.asFileSize().formatHumanReadable(this@CloudDriveActivity)
                quotaTv.text = "账号: ${q.userName} (${q.email})\n真实配额: 已用 $usedStr / 总计 $totalStr"
            }.onFailure { e ->
                quotaTv.text = "API 配额查询失败: ${e.localizedMessage}"
            }

            val listResult = CloudDriveClient.listFiles(
                session = validSession,
                folderId = currentFolder.id,
                searchQuery = currentSearchQuery
            )
            listResult.onSuccess { items ->
                if (items.isEmpty()) {
                    apiListCol.addView(TextView(this@CloudDriveActivity).apply {
                        text = "当前云盘目录下没有文件"
                        textSize = 13f
                        setTextColor(colorOnSurface)
                        setPadding(0, dp(12), 0, dp(12))
                    })
                } else {
                    for (item in items) {
                        val row = LinearLayout(this@CloudDriveActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(4), dp(10), dp(4), dp(10))
                            setOnClickListener {
                                if (item.isFolder) {
                                    folderStack.addLast(currentFolder)
                                    currentFolder = FolderCrumb(item.id, item.name)
                                    renderRestApiSection()
                                } else if (!item.downloadUrl.isNullOrBlank()) {
                                    downloadRestApiFile(item, validSession)
                                }
                            }
                        }
                        val tv = TextView(this@CloudDriveActivity).apply {
                            text = "${if (item.isFolder) "📁" else "📄"} ${item.name}"
                            textSize = 13.5f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(colorOnSurface)
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        row.addView(tv)
                        apiListCol.addView(row)
                    }
                }
            }.onFailure { e ->
                apiListCol.addView(TextView(this@CloudDriveActivity).apply {
                    text = "REST API 列表请求失败: ${e.localizedMessage}\n提示：您也可以直接切换到顶部「🌐 官方云盘网页端」使用账号密码直接登录。"
                    textSize = 12.5f
                    setTextColor(Color.parseColor("#DC2626"))
                    setPadding(0, dp(10), 0, dp(10))
                })
            }
        }
    }

    private fun downloadRestApiFile(item: CloudFileItem, session: CloudAccountSession) {
        val url = item.downloadUrl ?: return
        val targetDir = getLocalDownloadDirectory(activeProvider)
        downloadProgressCard.visibility = View.VISIBLE
        downloadProgressTitle.text = "正在通过 API 下载: ${item.name}"
        downloadProgressBar.isIndeterminate = false
        downloadProgressBar.progress = 0

        lifecycleScope.launch {
            val res = CloudDriveClient.downloadRealUrlToFile(
                url = url,
                accessToken = session.accessToken,
                destinationDir = targetDir,
                fallbackFileName = item.name
            ) { downloaded, total, _ ->
                runOnUiThread {
                    val tot = if (total > 0) total else item.size
                    if (tot > 0) {
                        val pct = ((downloaded * 100L) / tot).toInt().coerceIn(0, 100)
                        downloadProgressBar.progress = pct
                    }
                }
            }
            downloadProgressCard.visibility = View.GONE
            res.onSuccess { savedFile ->
                showToast("下载完成: ${savedFile.name}")
                showDownloadedFileActionDialog(savedFile)
            }.onFailure { e ->
                showToast("API 下载失败: ${e.localizedMessage}")
            }
        }
    }

    private fun openLocalFileSmart(file: File) {
        val path = Paths.get(file.absolutePath)
        val ext = file.extension.lowercase(Locale.ROOT)
        val mime = MimeType.guessFromPath(file.name)
        when {
            ext in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "ts") || mime.isVideo ->
                startActivitySafe(MediaPlayerActivity.createIntent(this, path, isAudio = false))
            ext in setOf("mp3", "flac", "wav", "m4a", "ogg", "aac") || mime.isAudio ->
                startActivitySafe(MediaPlayerActivity.createIntent(this, path, isAudio = true))
            ext in setOf("txt", "log", "ini", "cfg") ->
                startActivitySafe(ReaderActivity.createIntent(this, path))
            ext in setOf("docx", "xlsx", "csv", "md", "markdown") ->
                startActivitySafe(DocumentPreviewActivity.createIntent(this, path))
            ext in setOf("zip", "rar", "7z", "tar", "apk") ->
                startActivitySafe(ArchivePreviewActivity.createIntent(this, path))
            else -> {
                val viewIntent = path.fileProviderUri.createViewIntent(mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    .apply { extraPath = path }
                startActivitySafe(viewIntent)
            }
        }
    }

    private fun getLocalDownloadDirectory(provider: CloudProvider): File {
        val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(File(downloadsRoot, "CloudDrive"), provider.localSubDirName)
    }

    companion object {
        private const val EXTRA_PROVIDER = "extra_cloud_provider"

        fun createIntent(provider: CloudProvider = CloudProvider.GOOGLE_DRIVE): Intent =
            CloudDriveActivity::class.createIntent().apply {
                putExtra(EXTRA_PROVIDER, provider.name)
            }
    }
}
