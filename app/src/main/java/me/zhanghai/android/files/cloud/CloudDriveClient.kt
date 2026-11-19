package me.zhanghai.android.files.cloud

import android.accounts.AccountManager
import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

enum class CloudProvider(
    val displayName: String,
    val accountTypeLabel: String,
    val brandColorHex: String,
    val localSubDirName: String,
    val webDriveHomeUrl: String,
    val webLoginBaseUrl: String
) {
    GOOGLE_DRIVE(
        displayName = "Google Drive",
        accountTypeLabel = "Google 账号",
        brandColorHex = "#2563EB",
        localSubDirName = "GoogleDrive",
        webDriveHomeUrl = "https://drive.google.com/drive/my-drive",
        webLoginBaseUrl = "https://accounts.google.com/ServiceLogin?service=wise&passive=true&continue=https%3A%2F%2Fdrive.google.com%2Fdrive%2Fmy-drive"
    ),
    ONEDRIVE(
        displayName = "Microsoft OneDrive",
        accountTypeLabel = "Microsoft 账号",
        brandColorHex = "#0078D4",
        localSubDirName = "OneDrive",
        webDriveHomeUrl = "https://onedrive.live.com/",
        webLoginBaseUrl = "https://login.live.com/login.srf?wa=wsignin1.0&wreply=https%3A%2F%2Fonedrive.live.com%2F"
    )
}

data class CloudAccountSession(
    val provider: CloudProvider,
    val email: String,
    val displayName: String,
    val accessToken: String = "",
    val loginTimestamp: Long = System.currentTimeMillis()
)

data class CloudAccountQuota(
    val userName: String,
    val email: String,
    val totalBytes: Long,
    val usedBytes: Long
)

data class CloudFileItem(
    val id: String,
    val parentId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modifiedTime: String,
    val isFolder: Boolean,
    val downloadUrl: String? = null
)

object CloudDriveClient {
    private const val PREFS_NAME = "real_cloud_drive_prefs"

    /**
     * Standard Chrome Mobile User-Agent (without "; wv") so Google Sign-In & Drive Web work natively
     * without triggering "403 disallowed_useragent".
     */
    const val CHROME_MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /**
     * Returns only real saved accounts or real system accounts on the device (NO fake demo emails).
     */
    fun getSelectableAccounts(context: Context, provider: CloudProvider): List<String> {
        val accounts = linkedSetOf<String>()

        // 1. Read user's previously used accounts from SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedJson = prefs.getString("saved_accounts_${provider.name}", null)
        if (!savedJson.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(savedJson)
                for (i in 0 until arr.length()) {
                    val email = arr.optString(i).trim()
                    if (email.isNotEmpty() && email.contains("@")) {
                        accounts.add(email)
                    }
                }
            }
        }

        // 2. Discover real system accounts registered on the phone
        runCatching {
            val sysAccounts = AccountManager.get(context).accounts
            for (acc in sysAccounts) {
                if (acc.name.contains("@")) {
                    val isGoogle = acc.type.contains("google", ignoreCase = true) ||
                        acc.name.endsWith("@gmail.com", ignoreCase = true)
                    val isMicrosoft = acc.type.contains("microsoft", ignoreCase = true) ||
                        acc.type.contains("live", ignoreCase = true) ||
                        acc.name.endsWith("@outlook.com", ignoreCase = true) ||
                        acc.name.endsWith("@hotmail.com", ignoreCase = true) ||
                        acc.name.endsWith("@live.com", ignoreCase = true)
                    if (provider == CloudProvider.GOOGLE_DRIVE && isGoogle) {
                        accounts.add(acc.name)
                    } else if (provider == CloudProvider.ONEDRIVE && isMicrosoft) {
                        accounts.add(acc.name)
                    }
                }
            }
        }

        return accounts.toList()
    }

    fun saveAccountToHistory(context: Context, provider: CloudProvider, email: String) {
        val trimmed = email.trim()
        if (trimmed.isEmpty() || !trimmed.contains("@")) return
        val current = getSelectableAccounts(context, provider).toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        val arr = JSONArray()
        current.take(10).forEach { arr.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("saved_accounts_${provider.name}", arr.toString())
            .apply()
    }

    fun getActiveSession(context: Context, provider: CloudProvider): CloudAccountSession? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("session_${provider.name}", null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            CloudAccountSession(
                provider = provider,
                email = obj.optString("email", ""),
                displayName = obj.optString("displayName", ""),
                accessToken = obj.optString("accessToken", ""),
                loginTimestamp = obj.optLong("loginTimestamp", System.currentTimeMillis())
            )
        }.getOrNull()
    }

    fun saveActiveSession(context: Context, session: CloudAccountSession) {
        val json = JSONObject().apply {
            put("email", session.email)
            put("displayName", session.displayName)
            put("accessToken", session.accessToken)
            put("loginTimestamp", session.loginTimestamp)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("session_${session.provider.name}", json.toString())
            .apply()
        if (session.email.contains("@")) {
            saveAccountToHistory(context, session.provider, session.email)
        }
    }

    fun logout(context: Context, provider: CloudProvider) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("session_${provider.name}")
            .apply()
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
    }

    /**
     * Checks whether the WebView CookieManager currently holds an authenticated Google or Microsoft session cookie.
     */
    fun hasWebLoginSessionCookie(provider: CloudProvider): Boolean {
        return runCatching {
            val cm = CookieManager.getInstance()
            when (provider) {
                CloudProvider.GOOGLE_DRIVE -> {
                    val googleCookies = (cm.getCookie("https://drive.google.com").orEmpty() + ";" +
                        cm.getCookie("https://accounts.google.com").orEmpty())
                    googleCookies.contains("SID=") ||
                        googleCookies.contains("HSID=") ||
                        googleCookies.contains("SSID=") ||
                        googleCookies.contains("__Secure-1PSID=") ||
                        googleCookies.contains("__Secure-3PSID=")
                }
                CloudProvider.ONEDRIVE -> {
                    val msCookies = (cm.getCookie("https://onedrive.live.com").orEmpty() + ";" +
                        cm.getCookie("https://login.live.com").orEmpty())
                    msCookies.contains("WLSSC=") ||
                        msCookies.contains("RPSSecAuth=") ||
                        msCookies.contains("MSPAuth=") ||
                        msCookies.contains(" PPLState=")
                }
            }
        }.getOrDefault(false)
    }

    /**
     * Builds the real login URL pre-populated with the user's chosen email address if provided.
     */
    fun buildWebAuthUrl(provider: CloudProvider, emailHint: String): String {
        val trimmed = emailHint.trim()
        if (trimmed.isEmpty()) {
            return provider.webDriveHomeUrl
        }
        val encodedEmail = URLEncoder.encode(trimmed, "UTF-8")
        return when (provider) {
            CloudProvider.GOOGLE_DRIVE ->
                "https://accounts.google.com/AccountChooser?Email=$encodedEmail&continue=https%3A%2F%2Fdrive.google.com%2Fdrive%2Fmy-drive"
            CloudProvider.ONEDRIVE ->
                "https://login.live.com/login.srf?wa=wsignin1.0&username=$encodedEmail&wreply=https%3A%2F%2Fonedrive.live.com%2F"
        }
    }

    /**
     * Queries real cloud storage quota via Google Drive REST API v3 or Microsoft Graph API v1.0.
     */
    suspend fun fetchAccountQuota(
        session: CloudAccountSession
    ): Result<CloudAccountQuota> = withContext(Dispatchers.IO) {
        runCatching {
            val token = session.accessToken.trim()
            if (token.isEmpty()) {
                throw IllegalStateException("未配置 OAuth Access Token")
            }
            when (session.provider) {
                CloudProvider.GOOGLE_DRIVE -> {
                    val url = "https://www.googleapis.com/drive/v3/about?fields=user(displayName,emailAddress),storageQuota(limit,usage)"
                    val json = executeGetJson(url, token)
                    val userObj = json.optJSONObject("user")
                    val quotaObj = json.optJSONObject("storageQuota")
                    CloudAccountQuota(
                        userName = userObj?.optString("displayName").orEmpty().ifBlank { session.displayName },
                        email = userObj?.optString("emailAddress").orEmpty().ifBlank { session.email },
                        totalBytes = quotaObj?.optString("limit")?.toLongOrNull() ?: 0L,
                        usedBytes = quotaObj?.optString("usage")?.toLongOrNull() ?: 0L
                    )
                }
                CloudProvider.ONEDRIVE -> {
                    val url = "https://graph.microsoft.com/v1.0/me/drive"
                    val json = executeGetJson(url, token)
                    val userObj = json.optJSONObject("owner")?.optJSONObject("user")
                    val quotaObj = json.optJSONObject("quota")
                    CloudAccountQuota(
                        userName = userObj?.optString("displayName").orEmpty().ifBlank { session.displayName },
                        email = userObj?.optString("email").orEmpty().ifBlank { session.email },
                        totalBytes = quotaObj?.optLong("total") ?: 0L,
                        usedBytes = quotaObj?.optLong("used") ?: 0L
                    )
                }
            }
        }
    }

    /**
     * Lists real cloud files via Google Drive REST API v3 or Microsoft Graph API v1.0.
     */
    suspend fun listFiles(
        session: CloudAccountSession,
        folderId: String = "root",
        searchQuery: String = ""
    ): Result<List<CloudFileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = session.accessToken.trim()
            if (token.isEmpty()) {
                throw IllegalStateException("需要有效的 OAuth Access Token 才能调用 REST API")
            }
            when (session.provider) {
                CloudProvider.GOOGLE_DRIVE -> {
                    val queryBuilder = StringBuilder("trashed = false")
                    if (searchQuery.isNotBlank()) {
                        val escaped = searchQuery.replace("'", "\\'")
                        queryBuilder.append(" and name contains '$escaped'")
                    } else {
                        val parent = folderId.ifBlank { "root" }
                        queryBuilder.append(" and '$parent' in parents")
                    }
                    val encodedQ = URLEncoder.encode(queryBuilder.toString(), "UTF-8")
                    val fields = URLEncoder.encode(
                        "files(id,name,mimeType,size,modifiedTime,webContentLink)",
                        "UTF-8"
                    )
                    val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQ&fields=$fields&orderBy=folder,name&pageSize=100"
                    val json = executeGetJson(url, token)
                    val filesArr = json.optJSONArray("files") ?: return@runCatching emptyList()
                    val list = mutableListOf<CloudFileItem>()
                    for (i in 0 until filesArr.length()) {
                        val item = filesArr.getJSONObject(i)
                        val fileId = item.getString("id")
                        val mime = item.optString("mimeType", "application/octet-stream")
                        val isFolder = mime == "application/vnd.google-apps.folder"
                        val dlUrl = if (isFolder) {
                            null
                        } else {
                            "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
                        }
                        list.add(
                            CloudFileItem(
                                id = fileId,
                                parentId = folderId,
                                name = item.optString("name", "Untitled"),
                                mimeType = mime,
                                size = item.optString("size").toLongOrNull() ?: 0L,
                                modifiedTime = item.optString("modifiedTime", "").replace("T", " ").removeSuffix("Z"),
                                isFolder = isFolder,
                                downloadUrl = dlUrl ?: item.optString("webContentLink").takeIf { it.isNotBlank() }
                            )
                        )
                    }
                    list
                }
                CloudProvider.ONEDRIVE -> {
                    val url = if (searchQuery.isNotBlank()) {
                        val encodedQ = URLEncoder.encode(searchQuery, "UTF-8")
                        "https://graph.microsoft.com/v1.0/me/drive/root/search(q='$encodedQ')"
                    } else if (folderId.isBlank() || folderId == "root") {
                        "https://graph.microsoft.com/v1.0/me/drive/root/children"
                    } else {
                        "https://graph.microsoft.com/v1.0/me/drive/items/$folderId/children"
                    }
                    val json = executeGetJson(url, token)
                    val valueArr = json.optJSONArray("value") ?: return@runCatching emptyList()
                    val list = mutableListOf<CloudFileItem>()
                    for (i in 0 until valueArr.length()) {
                        val item = valueArr.getJSONObject(i)
                        val isFolder = item.has("folder")
                        val fileObj = item.optJSONObject("file")
                        val mime = fileObj?.optString("mimeType")
                            ?: if (isFolder) "inode/directory" else "application/octet-stream"
                        list.add(
                            CloudFileItem(
                                id = item.getString("id"),
                                parentId = folderId,
                                name = item.optString("name", "Untitled"),
                                mimeType = mime,
                                size = item.optLong("size", 0L),
                                modifiedTime = item.optString("lastModifiedDateTime", "").replace("T", " ").removeSuffix("Z"),
                                isFolder = isFolder,
                                downloadUrl = item.optString("@microsoft.graph.downloadUrl")
                                    .takeIf { it.isNotBlank() }
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    /**
     * Downloads a real file from Google Drive / OneDrive (either via WebView Cookie session or OAuth Bearer Token).
     */
    suspend fun downloadRealUrlToFile(
        url: String,
        userAgent: String = CHROME_MOBILE_USER_AGENT,
        contentDisposition: String? = null,
        accessToken: String = "",
        destinationDir: File,
        fallbackFileName: String? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long, resolvedName: String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destinationDir.mkdirs()
            val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

            var currentUrl = url
            var redirects = 0
            var conn: HttpURLConnection

            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", userAgent.ifBlank { CHROME_MOBILE_USER_AGENT })
                    val domainCookie = runCatching { CookieManager.getInstance().getCookie(currentUrl) }.getOrNull()
                        ?: cookies
                    if (!domainCookie.isNullOrBlank()) {
                        setRequestProperty("Cookie", domainCookie)
                    }
                    if (accessToken.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer $accessToken")
                    }
                }
                val code = conn.responseCode
                if (code in 301..308) {
                    val location = conn.getHeaderField("Location") ?: break
                    currentUrl = URL(URL(currentUrl), location).toString()
                    redirects++
                    if (redirects > 8) break
                } else {
                    break
                }
            }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("下载请求返回 HTTP $responseCode")
            }

            val headerDisposition = conn.getHeaderField("Content-Disposition") ?: contentDisposition
            val resolvedName = extractFileName(headerDisposition, currentUrl, fallbackFileName)
            val targetFile = File(destinationDir, resolvedName)
            val totalLength = conn.contentLengthLong.takeIf { it > 0L } ?: -1L

            conn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalLength, resolvedName)
                    }
                    output.flush()
                }
            }
            targetFile
        }
    }

    private fun extractFileName(
        contentDisposition: String?,
        url: String,
        fallbackName: String?
    ): String {
        if (!contentDisposition.isNullOrBlank()) {
            // Match filename*=UTF-8''...
            val utf8Match = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
                .find(contentDisposition)
            if (utf8Match != null) {
                val decoded = runCatching {
                    URLDecoder.decode(utf8Match.groupValues[1].trim('"', ' '), "UTF-8")
                }.getOrNull()
                if (!decoded.isNullOrBlank()) return sanitizeFileName(decoded)
            }
            // Match filename="..."
            val plainMatch = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(contentDisposition)
            if (plainMatch != null) {
                val decoded = runCatching {
                    URLDecoder.decode(plainMatch.groupValues[1].trim('"', ' '), "UTF-8")
                }.getOrNull()
                if (!decoded.isNullOrBlank()) return sanitizeFileName(decoded)
            }
        }
        if (!fallbackName.isNullOrBlank()) {
            return sanitizeFileName(fallbackName)
        }
        val pathSegment = runCatching {
            URL(url).path.substringAfterLast('/').takeIf { it.isNotBlank() && it != "download" && it != "uc" }
        }.getOrNull()
        if (!pathSegment.isNullOrBlank()) {
            return sanitizeFileName(URLDecoder.decode(pathSegment, "UTF-8"))
        }
        return "CloudFile_${System.currentTimeMillis()}"
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank {
            "CloudFile_${System.currentTimeMillis()}"
        }
    }

    private fun executeGetJson(url: String, accessToken: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: $body")
        }
        return JSONObject(body)
    }
}
