package me.zhanghai.android.files.cloud

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.TextUtils
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
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
import kotlinx.coroutines.launch
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
import me.zhanghai.android.files.navigation.NavigationItemListLiveData
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.getColorByAttr
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.viewer.media.MediaPlayerActivity
import java.io.File
import java.util.Locale

class CloudDriveActivity : AppActivity() {

    data class FolderCrumb(val id: String, val name: String)

    private var activeProvider: CloudProvider = CloudProvider.GOOGLE_DRIVE
    private val folderStack = ArrayDeque<FolderCrumb>()
    private var currentFolder = FolderCrumb("root", "我的云盘 (Root)")
    private var currentSearchQuery: String = ""
    private var selectedAccountEmail: String = ""

    private lateinit var toolbar: MaterialToolbar
    private lateinit var providerTabLayout: TabLayout
    private lateinit var accountStatusBadge: TextView
    private lateinit var accountNameText: TextView
    private lateinit var accountQuotaText: TextView
    private lateinit var accountQuotaBar: LinearProgressIndicator
    private lateinit var switchAccountButton: MaterialButton
    private lateinit var logoutButton: MaterialButton

    // Samsung My Files style Account Picker & Password Login Card (shown when not signed in)
    private lateinit var loginCard: MaterialCardView
    private lateinit var loginHeaderTitle: TextView
    private lateinit var accountRadioGroup: RadioGroup
    private lateinit var customEmailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var signInButton: MaterialButton

    // Cloud Browser Container (shown when signed in)
    private lateinit var browserSection: LinearLayout
    private lateinit var breadcrumbText: TextView
    private lateinit var searchInput: EditText
    private lateinit var loadingContainer: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var downloadProgressCard: MaterialCardView
    private lateinit var downloadProgressTitle: TextView
    private lateinit var downloadProgressBar: LinearProgressIndicator
    private lateinit var fileListContainer: LinearLayout

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
                if (folderStack.isNotEmpty()) {
                    currentFolder = folderStack.removeLast()
                    refreshCloudDriveState()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        refreshCloudDriveState()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (folderStack.isNotEmpty()) {
                currentFolder = folderStack.removeLast()
                refreshCloudDriveState()
            } else {
                finish()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildCloudDriveLayout(): View {
        val colorOnSurface = getColorByAttr(com.google.android.material.R.attr.colorOnSurface)
        val colorSurface = getColorByAttr(com.google.android.material.R.attr.colorSurface)
        val colorPrimary = getColorByAttr(androidx.appcompat.R.attr.colorPrimary)

        val root = LinearLayout(this).apply {
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
        providerTabLayout = TabLayout(this).apply {
            addTab(newTab().setText("Google Drive"))
            addTab(newTab().setText("Microsoft OneDrive"))
            getTabAt(if (activeProvider == CloudProvider.GOOGLE_DRIVE) 0 else 1)?.select()
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    val newProvider = if (tab?.position == 1) CloudProvider.ONEDRIVE else CloudProvider.GOOGLE_DRIVE
                    if (activeProvider != newProvider) {
                        activeProvider = newProvider
                        folderStack.clear()
                        currentFolder = FolderCrumb("root", "我的云盘 (Root)")
                        currentSearchQuery = ""
                        searchInput.setText("")
                        refreshCloudDriveState()
                    }
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
        }
        appBar.addView(toolbar)
        appBar.addView(providerTabLayout)
        root.addView(appBar)

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
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }

        // 1. Account Status & Storage Quota Card
        val accountCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val accountBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cloudIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginEnd = dp(10)
            }
            setImageResource(R.drawable.cloud_drive_icon_white_24dp)
        }
        accountNameText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface)
        }
        accountStatusBadge = TextView(this).apply {
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setTextColor(Color.WHITE)
        }
        headerRow.addView(cloudIcon)
        headerRow.addView(accountNameText)
        headerRow.addView(accountStatusBadge)
        accountBody.addView(headerRow)

        accountQuotaText = TextView(this).apply {
            textSize = 13f
            setTextColor(colorOnSurface)
            alpha = 0.82f
            setPadding(0, dp(8), 0, dp(6))
        }
        accountBody.addView(accountQuotaText)

        accountQuotaBar = LinearProgressIndicator(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            trackThickness = dp(6)
            trackCornerRadius = dp(3)
            max = 1000
            progress = 0
        }
        accountBody.addView(accountQuotaBar)

        val accountActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        switchAccountButton = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "切换账号"
            textSize = 12f
            setOnClickListener { showSwitchAccountDialog() }
        }
        logoutButton = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "退出登录"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            setOnClickListener {
                CloudDriveClient.logout(this@CloudDriveActivity, activeProvider)
                folderStack.clear()
                currentFolder = FolderCrumb("root", "我的云盘 (Root)")
                refreshCloudDriveState()
            }
        }
        val openLocalDirBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "已下载目录"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            setOnClickListener {
                val dir = getLocalDownloadDirectory(activeProvider)
                dir.mkdirs()
                startActivitySafe(FileListActivity.createViewIntent(Paths.get(dir.absolutePath)))
            }
        }
        accountActionRow.addView(switchAccountButton)
        accountActionRow.addView(logoutButton)
        accountActionRow.addView(openLocalDirBtn)
        accountBody.addView(accountActionRow)
        accountCard.addView(accountBody)
        contentColumn.addView(accountCard)

        // 2. Samsung My Files Style Account Selection & Password Login Card
        loginCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        val loginBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18))
        }
        loginHeaderTitle = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface)
        }
        val loginSubtitle = TextView(this).apply {
            text = "请选择本机已关联的云盘账号或输入其他账号，并输入密码登录以浏览和下载云盘文件："
            textSize = 13f
            setTextColor(colorOnSurface)
            alpha = 0.75f
            setPadding(0, dp(4), 0, dp(10))
        }
        loginBody.addView(loginHeaderTitle)
        loginBody.addView(loginSubtitle)

        accountRadioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        loginBody.addView(accountRadioGroup)

        customEmailInput = EditText(this).apply {
            hint = "请输入新账号邮箱 (例如 name@gmail.com / @outlook.com)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            textSize = 14f
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        loginBody.addView(customEmailInput)

        val passwordLabel = TextView(this).apply {
            text = "账号密码"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface)
            setPadding(0, dp(12), 0, dp(4))
        }
        loginBody.addView(passwordLabel)

        passwordInput = EditText(this).apply {
            hint = "请输入账号登录密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            textSize = 14f
        }
        loginBody.addView(passwordInput)

        signInButton = MaterialButton(this).apply {
            text = "登录并同步云盘文件"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
            setOnClickListener { performAccountPasswordLogin() }
        }
        loginBody.addView(signInButton)
        loginCard.addView(loginBody)
        contentColumn.addView(loginCard)

        // 3. Cloud File Browser Section (shown after login)
        browserSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        searchInput = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            hint = "在云盘中搜索文件或文件夹…"
            textSize = 14f
            setSingleLine(true)
        }
        val searchBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.search)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            setOnClickListener {
                currentSearchQuery = searchInput.text?.toString()?.trim().orEmpty()
                refreshCloudDriveState()
            }
        }
        val refreshBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.refresh)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            setOnClickListener {
                currentSearchQuery = ""
                searchInput.setText("")
                refreshCloudDriveState()
            }
        }
        searchRow.addView(searchInput)
        searchRow.addView(searchBtn)
        searchRow.addView(refreshBtn)
        browserSection.addView(searchRow)

        val crumbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        breadcrumbText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorPrimary)
        }
        val upCrumbBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "返回上级"
            textSize = 12f
            setOnClickListener {
                if (folderStack.isNotEmpty()) {
                    currentFolder = folderStack.removeLast()
                    refreshCloudDriveState()
                }
            }
        }
        crumbRow.addView(breadcrumbText)
        crumbRow.addView(upCrumbBtn)
        browserSection.addView(crumbRow)

        downloadProgressCard = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val dlCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14))
        }
        downloadProgressTitle = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorOnSurface)
        }
        downloadProgressBar = LinearProgressIndicator(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            max = 100
        }
        dlCol.addView(downloadProgressTitle)
        dlCol.addView(downloadProgressBar)
        downloadProgressCard.addView(dlCol)
        browserSection.addView(downloadProgressCard)

        loadingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
        }
        loadingContainer.addView(ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        })
        loadingText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
            textSize = 13f
            setTextColor(colorOnSurface)
        }
        loadingContainer.addView(loadingText)
        browserSection.addView(loadingContainer)

        val filesCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        fileListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12))
        }
        filesCard.addView(fileListContainer)
        browserSection.addView(filesCard)

        contentColumn.addView(browserSection)
        scrollView.addView(contentColumn)
        root.addView(scrollView)
        return root
    }

    private fun populateAccountSelectionList() {
        val colorOnSurface = getColorByAttr(com.google.android.material.R.attr.colorOnSurface)
        val accounts = CloudDriveClient.getSelectableAccounts(this, activeProvider)
        loginHeaderTitle.text = "登录 ${activeProvider.displayName} (${activeProvider.accountTypeLabel})"

        accountRadioGroup.removeAllViews()
        selectedAccountEmail = accounts.firstOrNull().orEmpty()
        customEmailInput.visibility = View.GONE

        for ((idx, email) in accounts.withIndex()) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = email
                textSize = 14f
                setTextColor(colorOnSurface)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isChecked = (idx == 0)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedAccountEmail = email
                        customEmailInput.visibility = View.GONE
                    }
                }
            }
            accountRadioGroup.addView(rb)
        }

        val addCustomRb = RadioButton(this).apply {
            id = View.generateViewId()
            text = "+ 使用其他 ${activeProvider.accountTypeLabel}"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(activeProvider.brandColorHex))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedAccountEmail = ""
                    customEmailInput.visibility = View.VISIBLE
                    customEmailInput.requestFocus()
                }
            }
        }
        accountRadioGroup.addView(addCustomRb)
    }

    private fun performAccountPasswordLogin() {
        val emailToUse = if (customEmailInput.visibility == View.VISIBLE) {
            customEmailInput.text?.toString()?.trim().orEmpty()
        } else {
            selectedAccountEmail
        }
        val password = passwordInput.text?.toString().orEmpty()

        signInButton.isEnabled = false
        signInButton.text = "正在验证账号与密码…"

        lifecycleScope.launch {
            val result = CloudDriveClient.loginWithAccountPassword(
                context = this@CloudDriveActivity,
                provider = activeProvider,
                email = emailToUse,
                password = password
            )
            signInButton.isEnabled = true
            signInButton.text = "登录并同步云盘文件"

            result.onSuccess { session ->
                passwordInput.setText("")
                showToast("已登录 ${activeProvider.displayName}: ${session.email}")
                folderStack.clear()
                currentFolder = FolderCrumb("root", "我的云盘 (Root)")
                refreshCloudDriveState()
            }.onFailure { e ->
                showToast(e.localizedMessage ?: "登录失败，请检查账号和密码")
            }
        }
    }

    private fun showSwitchAccountDialog() {
        val accounts = CloudDriveClient.getSelectableAccounts(this, activeProvider)
        val options = (accounts + listOf("+ 输入其他账号登录…")).toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("选择 ${activeProvider.displayName} 账号")
            .setItems(options) { _, which ->
                if (which < accounts.size) {
                    showPasswordPromptForAccount(accounts[which])
                } else {
                    CloudDriveClient.logout(this, activeProvider)
                    refreshCloudDriveState()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showPasswordPromptForAccount(email: String) {
        val pwdEdit = EditText(this).apply {
            hint = "请输入 $email 的登录密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("验证账号密码")
            .setMessage("账号: $email")
            .setView(pwdEdit)
            .setPositiveButton("登录") { _, _ ->
                val pwd = pwdEdit.text?.toString().orEmpty()
                lifecycleScope.launch {
                    val res = CloudDriveClient.loginWithAccountPassword(
                        this@CloudDriveActivity,
                        activeProvider,
                        email,
                        pwd
                    )
                    res.onSuccess {
                        showToast("已切换至账号: $email")
                        folderStack.clear()
                        currentFolder = FolderCrumb("root", "我的云盘 (Root)")
                        refreshCloudDriveState()
                    }.onFailure { e ->
                        showToast(e.localizedMessage ?: "密码验证失败")
                    }
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun refreshCloudDriveState() {
        val brandColor = Color.parseColor(activeProvider.brandColorHex)
        val colorOnSurface = getColorByAttr(com.google.android.material.R.attr.colorOnSurface)
        val session = CloudDriveClient.getActiveSession(this, activeProvider)

        accountQuotaBar.setIndicatorColor(brandColor)
        signInButton.setBackgroundColor(brandColor)

        if (session == null) {
            accountNameText.text = "${activeProvider.displayName} (未登录)"
            accountStatusBadge.text = "待登录"
            accountStatusBadge.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#757575"))
            }
            accountQuotaText.text =
                "像三星「我的文件」一样，直接在下方选择 ${activeProvider.accountTypeLabel} 并输入密码即可登录浏览与下载文件。"
            accountQuotaBar.progress = 0
            switchAccountButton.visibility = View.GONE
            logoutButton.visibility = View.GONE

            loginCard.visibility = View.VISIBLE
            browserSection.visibility = View.GONE
            populateAccountSelectionList()
            return
        }

        // Logged in state
        loginCard.visibility = View.GONE
        browserSection.visibility = View.VISIBLE
        switchAccountButton.visibility = View.VISIBLE
        logoutButton.visibility = View.VISIBLE

        accountNameText.text = "${session.displayName} (${session.email})"
        accountStatusBadge.text = "已连接"
        accountStatusBadge.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(brandColor)
        }

        val trailNames = (folderStack.map { it.name } + currentFolder.name).joinToString(" > ")
        breadcrumbText.text = if (currentSearchQuery.isNotBlank()) {
            "搜索: \"$currentSearchQuery\" (位于 $trailNames)"
        } else {
            "当前位置: $trailNames"
        }

        loadingContainer.visibility = View.VISIBLE
        loadingText.text = "正在同步 ${activeProvider.displayName} 云盘目录…"

        lifecycleScope.launch {
            val quotaResult = CloudDriveClient.fetchAccountQuota(session)
            quotaResult.onSuccess { quota ->
                val usedStr = quota.usedBytes.asFileSize().formatHumanReadable(this@CloudDriveActivity)
                val totalStr = quota.totalBytes.asFileSize().formatHumanReadable(this@CloudDriveActivity)
                val freeStr = (quota.totalBytes - quota.usedBytes).coerceAtLeast(0L)
                    .asFileSize().formatHumanReadable(this@CloudDriveActivity)
                val pct = if (quota.totalBytes > 0L) {
                    (quota.usedBytes.toDouble() * 100.0 / quota.totalBytes.toDouble())
                } else 0.0
                accountQuotaText.text =
                    "云端存储空间: 已用 $usedStr / 总容量 $totalStr · 可用 $freeStr (${String.format(Locale.US, "%.1f%%", pct)})"
                accountQuotaBar.progress = (pct * 10).toInt().coerceIn(0, 1000)
            }

            val filesResult = CloudDriveClient.listFiles(
                session = session,
                folderId = currentFolder.id,
                searchQuery = currentSearchQuery
            )
            loadingContainer.visibility = View.GONE
            filesResult.onSuccess { items ->
                renderCloudFileList(items, session, colorOnSurface, brandColor)
            }.onFailure { e ->
                showToast("获取云盘文件失败: ${e.localizedMessage}")
            }
        }
    }

    private fun renderCloudFileList(
        items: List<CloudFileItem>,
        session: CloudAccountSession,
        colorOnSurface: Int,
        brandColor: Int
    ) {
        fileListContainer.removeAllViews()
        val localDir = getLocalDownloadDirectory(activeProvider)

        if (items.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "当前云盘目录没有匹配的文件"
                textSize = 14f
                setTextColor(colorOnSurface)
                alpha = 0.7f
                setPadding(dp(8), dp(16), dp(8), dp(16))
            }
            fileListContainer.addView(emptyView)
        } else {
            for (item in items) {
                val mime = if (item.isFolder) MimeType.DIRECTORY else MimeType.guessFromPath(item.name)
                val localFile = File(localDir, item.name)
                val isAlreadyDownloaded = !item.isFolder && localFile.exists() && localFile.length() > 0L

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    setOnClickListener {
                        if (item.isFolder) {
                            folderStack.addLast(currentFolder)
                            currentFolder = FolderCrumb(item.id, item.name)
                            currentSearchQuery = ""
                            searchInput.setText("")
                            refreshCloudDriveState()
                        } else if (isAlreadyDownloaded) {
                            openLocalFile(Paths.get(localFile.absolutePath), localFile.name, mime)
                        } else {
                            downloadAndOpenCloudFile(item, session)
                        }
                    }
                }

                val icon = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                        marginEnd = dp(12)
                    }
                    setImageResource(if (item.isFolder) R.drawable.file_directory_icon else mime.iconRes)
                }

                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameText = TextView(this).apply {
                    text = item.name
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setTextColor(colorOnSurface)
                }
                val metaText = TextView(this).apply {
                    val sizePart = if (item.isFolder) {
                        "云端文件夹"
                    } else {
                        item.size.asFileSize().formatHumanReadable(this@CloudDriveActivity)
                    }
                    val dlTag = if (isAlreadyDownloaded) " · 已下载至本地" else ""
                    text = "$sizePart · ${item.modifiedTime}$dlTag"
                    textSize = 12f
                    setTextColor(if (isAlreadyDownloaded) Color.parseColor("#2E7D32") else colorOnSurface)
                    alpha = if (isAlreadyDownloaded) 0.95f else 0.72f
                }
                textCol.addView(nameText)
                textCol.addView(metaText)

                val actionBtn = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = when {
                        item.isFolder -> "打开"
                        isAlreadyDownloaded -> if (mime.isAudio || mime.isVideo) "播放" else "查看"
                        else -> "下载"
                    }
                    textSize = 12f
                    setTextColor(if (isAlreadyDownloaded) Color.parseColor("#2E7D32") else brandColor)
                    setOnClickListener {
                        if (item.isFolder) {
                            folderStack.addLast(currentFolder)
                            currentFolder = FolderCrumb(item.id, item.name)
                            currentSearchQuery = ""
                            searchInput.setText("")
                            refreshCloudDriveState()
                        } else if (isAlreadyDownloaded) {
                            openLocalFile(Paths.get(localFile.absolutePath), localFile.name, mime)
                        } else {
                            downloadAndOpenCloudFile(item, session)
                        }
                    }
                }

                row.addView(icon)
                row.addView(textCol)
                row.addView(actionBtn)
                fileListContainer.addView(row)
            }
        }

        renderLocalDownloadedSection(colorOnSurface, brandColor)
    }

    private fun renderLocalDownloadedSection(colorOnSurface: Int, brandColor: Int) {
        val localDir = getLocalDownloadDirectory(activeProvider)
        val localFiles = localDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (localFiles.isEmpty()) return

        val header = TextView(this).apply {
            text = "已下载到本地 (${localDir.name}) 的文件 (${localFiles.size})"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(brandColor)
            setPadding(dp(4), dp(16), dp(4), dp(6))
        }
        fileListContainer.addView(header)

        for (file in localFiles) {
            val path = Paths.get(file.absolutePath)
            val mime = MimeType.guessFromPath(file.name)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnClickListener { openLocalFile(path, file.name, mime) }
            }
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) }
                setImageResource(mime.iconRes)
            }
            val nameText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = file.name
                textSize = 13f
                setTextColor(colorOnSurface)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            val sizeText = TextView(this).apply {
                text = file.length().asFileSize().formatHumanReadable(this@CloudDriveActivity)
                textSize = 12f
                setTextColor(brandColor)
                setPadding(dp(8), 0, 0, 0)
            }
            row.addView(icon)
            row.addView(nameText)
            row.addView(sizeText)
            fileListContainer.addView(row)
        }
    }

    private fun downloadAndOpenCloudFile(item: CloudFileItem, session: CloudAccountSession) {
        val targetDir = getLocalDownloadDirectory(activeProvider)
        val destFile = File(targetDir, item.name)

        downloadProgressCard.visibility = View.VISIBLE
        downloadProgressTitle.text = "正在从 ${activeProvider.displayName} 下载: ${item.name}"
        downloadProgressBar.isIndeterminate = false
        downloadProgressBar.progress = 0

        lifecycleScope.launch {
            val result = CloudDriveClient.downloadCloudFile(
                session = session,
                item = item,
                destinationFile = destFile
            ) { downloaded, total ->
                runOnUiThread {
                    if (total > 0L) {
                        val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                        downloadProgressBar.progress = pct
                        val dlStr = downloaded.asFileSize().formatHumanReadable(this@CloudDriveActivity)
                        val totStr = total.asFileSize().formatHumanReadable(this@CloudDriveActivity)
                        downloadProgressTitle.text = "正在下载 ${item.name}: $dlStr / $totStr ($pct%)"
                    }
                }
            }
            downloadProgressCard.visibility = View.GONE
            result.onSuccess { savedFile ->
                showToast("下载完成: ${savedFile.name}")
                refreshCloudDriveState()
                val savedPath = Paths.get(savedFile.absolutePath)
                val mime = MimeType.guessFromPath(savedFile.name)
                openLocalFile(savedPath, savedFile.name, mime)
            }.onFailure { e ->
                showToast("下载失败: ${e.localizedMessage}")
            }
        }
    }

    private fun openLocalFile(path: java8.nio.file.Path, fileName: String, mimeType: MimeType) {
        if (mimeType.isAudio || mimeType.isVideo) {
            startActivitySafe(
                MediaPlayerActivity.createIntent(
                    this,
                    path.fileProviderUri,
                    fileName,
                    mimeType.isVideo
                ).apply { extraPath = path }
            )
        } else {
            val viewIntent = path.fileProviderUri.createViewIntent(mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .apply { extraPath = path }
            startActivitySafe(viewIntent)
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
