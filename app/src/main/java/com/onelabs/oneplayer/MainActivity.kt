package com.onelabs.oneplayer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var splash: LinearLayout
    private lateinit var webView: WebView
    private val io: ExecutorService = Executors.newCachedThreadPool()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        splash = findViewById(R.id.splash)
        webView = findViewById(R.id.webview)

        setupWebView()
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.setBackgroundColor(Color.parseColor("#0A0E1A"))
        webView.addJavascriptInterface(OneBridge(this), "OneNative")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                splash.visibility = View.GONE
                webView.requestFocus()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("file:///android_asset/")) {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    fun evalJs(script: String) {
        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        evalJs("window.onTvKeyDown && window.onTvKeyDown($keyCode)")
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            webView.evaluateJavascript("(function(){ if (window.onBackPressed && window.onBackPressed()) return true; return false; })()") { handled ->
                if (handled != "true") {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (webView.canGoBack()) {
                            webView.goBack()
                        } else {
                            finish()
                        }
                    }
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("OneNative")
        webView.destroy()
        io.shutdownNow()
        super.onDestroy()
    }

    inner class OneBridge(private val activity: MainActivity) {

        private val prefs: SharedPreferences by lazy {
            activity.getSharedPreferences("one_prefs", Context.MODE_PRIVATE)
        }

        @JavascriptInterface
        fun getDeviceId(): String {
            val savedMac = prefs.getString("device_mac", null)
            if (!savedMac.isNullOrEmpty()) return savedMac

            val androidId = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ANDROID_ID)
            val seed = if (androidId.isNullOrBlank() || androidId == "9774d56d682e549c" || androidId == "unknown") {
                val uuid = prefs.getString("device_uuid", null) ?: run {
                    val newId = UUID.randomUUID().toString()
                    prefs.edit().putString("device_uuid", newId).apply()
                    newId
                }
                uuid.replace("-", "").take(12)
            } else {
                androidId.padEnd(12, '0').take(12)
            }

            val mac = seed.chunked(2).take(6).joinToString(":") { it.uppercase() }
            prefs.edit().putString("device_mac", mac).apply()
            return mac
        }

        @JavascriptInterface
        fun checkLicense(mac: String, key: String) {
            io.execute {
                var conn: HttpURLConnection? = null
                try {
                    val url = "https://oneapk.ro/pair.php?mac=$mac&key=$key&check=1"
                    conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "ONEPlayer/1.02 (Android; SDK ${Build.VERSION.SDK_INT})")
                    conn.requestMethod = "GET"

                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

                    val (success, message) = evaluateLicenseResponse(code, body)
                    val quoted = JSONObject.quote(message)
                    activity.evalJs("window.onLicenseResult($success, $quoted)")
                } catch (e: Exception) {
                    val quoted = JSONObject.quote(e.message ?: "Network error")
                    activity.evalJs("window.onLicenseResult(false, $quoted)")
                } finally {
                    conn?.disconnect()
                }
            }
        }

        @JavascriptInterface
        fun pollLicense(mac: String, key: String) {
            io.execute {
                var conn: HttpURLConnection? = null
                try {
                    val url = "https://oneapk.ro/pair.php?mac=$mac&key=$key&poll=1"
                    conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "ONEPlayer/1.02 (Android; SDK ${Build.VERSION.SDK_INT})")
                    conn.requestMethod = "GET"

                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

                    val (success, message) = evaluateLicenseResponse(code, body)
                    val quoted = JSONObject.quote(message)
                    activity.evalJs("window.onLicensePoll($success, $quoted)")
                } catch (e: Exception) {
                    val quoted = JSONObject.quote(e.message ?: "poll error")
                    activity.evalJs("window.onLicensePoll(false, $quoted)")
                } finally {
                    conn?.disconnect()
                }
            }
        }

        private fun evaluateLicenseResponse(code: Int, body: String): Pair<Boolean, String> {
            val trimmed = body.trim()
            if (trimmed.isEmpty()) {
                return Pair(false, "Empty server response")
            }

            if (trimmed.startsWith("{")) {
                return try {
                    val json = JSONObject(trimmed)
                    val status = json.optString("status", "").lowercase()
                    val msg = json.optString("message", "").ifEmpty { json.optString("msg", "") }
                    val ok = json.optBoolean("ok", false)

                    if (ok || status in listOf("active", "paired", "success", "valid", "activated")) {
                        Pair(true, if (msg.isNotEmpty()) msg else "Activated")
                    } else if (status in listOf("waiting", "unpaired")) {
                        Pair(false, if (msg.isNotEmpty()) msg else "Waiting for activation")
                    } else if (status in listOf("expired", "invalid", "fail", "not_found", "error")) {
                        Pair(false, if (msg.isNotEmpty()) msg else "Subscription expired or invalid")
                    } else {
                        Pair(false, if (msg.isNotEmpty()) msg else "Waiting for activation")
                    }
                } catch (_: Exception) {
                    Pair(false, trimmed)
                }
            }

            val lower = trimmed.lowercase()
            return when {
                lower.contains("activated") || lower.contains("success") || lower.contains("active") || lower.contains("paired") -> {
                    Pair(true, "Activated")
                }
                lower.contains("expired") -> {
                    Pair(false, "Subscription expired")
                }
                lower.contains("not found") || lower.contains("invalid") -> {
                    Pair(false, "Device not registered")
                }
                code in 200..299 -> {
                    Pair(false, "Waiting for activation")
                }
                else -> {
                    Pair(false, "Server HTTP $code")
                }
            }
        }

        @JavascriptInterface
        fun syncCloudPlaylists(mac: String) {
            io.execute {
                var conn: HttpURLConnection? = null
                try {
                    val url = "https://oneapk.ro/api/v1/playlists.php?mac=$mac"
                    conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "ONEPlayer/1.02 (Android; SDK ${Build.VERSION.SDK_INT})")
                    conn.requestMethod = "GET"

                    val code = conn.responseCode
                    if (code in 200..299) {
                        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val quoted = JSONObject.quote(body)
                        activity.evalJs("window.onCloudPlaylistsLoaded(true, $quoted)")
                    } else {
                        val quoted = JSONObject.quote("HTTP $code")
                        activity.evalJs("window.onCloudPlaylistsLoaded(false, $quoted)")
                    }
                } catch (e: Exception) {
                    val quoted = JSONObject.quote(e.message ?: "Failed to sync playlists")
                    activity.evalJs("window.onCloudPlaylistsLoaded(false, $quoted)")
                } finally {
                    conn?.disconnect()
                }
            }
        }

        @JavascriptInterface
        fun fetchPlaylist(playlistUrl: String, playlistName: String) {
            io.execute {
                try {
                    // Check if Xtream get.php URL
                    val xtreamAccount = XtreamClient.parseGetPhpUrl(playlistUrl)
                    if (xtreamAccount != null) {
                        fetchXtream(xtreamAccount.server, xtreamAccount.username, xtreamAccount.password, playlistName)
                        return@execute
                    }

                    val conn = URL(playlistUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.setRequestProperty("User-Agent", "ONEPlayer/1.02")
                    conn.requestMethod = "GET"

                    val code = conn.responseCode
                    if (code !in 200..299) {
                        conn.disconnect()
                        val quoted = JSONObject.quote("HTTP $code: Failed to load playlist")
                        activity.evalJs("window.onPlaylistLoaded(false, $quoted)")
                        return@execute
                    }

                    val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                    val parsed = M3uParser.parseStream(reader, playlistUrl)
                    conn.disconnect()

                    if (parsed.entries.isEmpty() && parsed.kind != M3uParser.Kind.HLS_MEDIA) {
                        val quoted = JSONObject.quote("No channels or streams found in M3U/M3U8")
                        activity.evalJs("window.onPlaylistLoaded(false, $quoted)")
                        return@execute
                    }

                    val itemsArray = JSONArray()
                    var liveCount = 0
                    var vodCount = 0
                    var seriesCount = 0
                    val groupsSet = mutableSetOf<String>()

                    for (entry in parsed.entries) {
                        val lowerUrl = entry.url.lowercase()
                        val lowerGroup = entry.group.lowercase()
                        val type = when {
                            lowerGroup.contains("series") || lowerUrl.contains("/series/") -> {
                                seriesCount++
                                "series"
                            }
                            lowerGroup.contains("movie") || lowerGroup.contains("vod") || lowerUrl.contains("/movie/") ||
                            lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".mkv") -> {
                                vodCount++
                                "vod"
                            }
                            else -> {
                                liveCount++
                                "live"
                            }
                        }
                        if (entry.group.isNotEmpty()) groupsSet.add(entry.group)

                        val item = JSONObject().apply {
                            put("title", entry.title)
                            put("url", entry.url)
                            put("group", entry.group.ifEmpty { "All Channels" })
                            put("logo", entry.logo)
                            put("duration", entry.duration)
                            put("bandwidth", entry.bandwidth)
                            put("resolution", entry.resolution)
                            put("type", type)
                        }
                        itemsArray.put(item)
                    }

                    val resultObj = JSONObject().apply {
                        put("name", playlistName.ifEmpty { "Playlist" })
                        put("count", parsed.entries.size)
                        put("liveCount", liveCount)
                        put("vodCount", vodCount)
                        put("seriesCount", seriesCount)
                        put("groups", JSONArray(groupsSet.toList()))
                        put("kind", when (parsed.kind) {
                            M3uParser.Kind.CHANNEL_LIST -> "channel_list"
                            M3uParser.Kind.HLS_MASTER -> "hls_master"
                            M3uParser.Kind.HLS_MEDIA -> "hls_media"
                        })
                        put("items", itemsArray)
                    }

                    val quoted = JSONObject.quote(resultObj.toString())
                    activity.evalJs("window.onPlaylistLoaded(true, $quoted)")
                } catch (e: Exception) {
                    val quoted = JSONObject.quote(e.message ?: "Failed to load playlist")
                    activity.evalJs("window.onPlaylistLoaded(false, $quoted)")
                }
            }
        }

        @JavascriptInterface
        fun fetchXtream(server: String, user: String, pass: String, accountName: String) {
            io.execute {
                try {
                    val account = XtreamClient.Account(server, user, pass)
                    val result = XtreamClient.fetchAccount(account)

                    if (!result.ok) {
                        val quoted = JSONObject.quote(result.message)
                        activity.evalJs("window.onPlaylistLoaded(false, $quoted)")
                        return@execute
                    }

                    val itemsArray = JSONArray()
                    var liveCount = 0
                    var vodCount = 0
                    var seriesCount = 0
                    val groupsSet = mutableSetOf<String>()

                    for (ch in result.channels) {
                        when (ch.streamType) {
                            "series" -> seriesCount++
                            "vod" -> vodCount++
                            else -> liveCount++
                        }
                        val g = ch.categoryName.ifEmpty { "Uncategorized" }
                        groupsSet.add(g)

                        val item = JSONObject().apply {
                            put("title", ch.name)
                            put("url", ch.url)
                            put("group", g)
                            put("logo", ch.streamIcon)
                            put("type", ch.streamType)
                        }
                        itemsArray.put(item)
                    }

                    val resultObj = JSONObject().apply {
                        put("name", accountName.ifEmpty { "Xtream · $user" })
                        put("count", result.channels.size)
                        put("liveCount", liveCount)
                        put("vodCount", vodCount)
                        put("seriesCount", seriesCount)
                        put("groups", JSONArray(groupsSet.toList()))
                        put("kind", "xtream")
                        put("server", server)
                        put("username", user)
                        result.userInfo?.let { put("userInfo", it) }
                        result.serverInfo?.let { put("serverInfo", it) }
                        put("items", itemsArray)
                    }

                    val quoted = JSONObject.quote(resultObj.toString())
                    activity.evalJs("window.onPlaylistLoaded(true, $quoted)")
                } catch (e: Exception) {
                    val quoted = JSONObject.quote(e.message ?: "Xtream failed")
                    activity.evalJs("window.onPlaylistLoaded(false, $quoted)")
                }
            }
        }

        @JavascriptInterface
        fun loadPlaylists(): String {
            return prefs.getString("playlists_json", "[]") ?: "[]"
        }

        @JavascriptInterface
        fun savePlaylists(json: String) {
            prefs.edit().putString("playlists_json", json).apply()
        }

        @JavascriptInterface
        fun loadStreams(): String {
            return prefs.getString("streams_json", "[]") ?: "[]"
        }

        @JavascriptInterface
        fun saveStreams(json: String) {
            prefs.edit().putString("streams_json", json).apply()
        }

        @JavascriptInterface
        fun play(streamUrl: String, title: String, subtitle: String) {
            playWithPlaylist(streamUrl, title, subtitle, "[]", 0)
        }

        @JavascriptInterface
        fun playWithPlaylist(streamUrl: String, title: String, subtitle: String, channelsJson: String, currentIndex: Int) {
            activity.runOnUiThread {
                try {
                    val list = mutableListOf<PlayerActivity.ChannelItem>()
                    if (channelsJson.isNotBlank() && channelsJson != "[]") {
                        val arr = JSONArray(channelsJson)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            list.add(
                                PlayerActivity.ChannelItem(
                                    title = obj.optString("title", "Channel ${i + 1}"),
                                    url = obj.optString("url", ""),
                                    group = obj.optString("group", "Channels"),
                                    logo = obj.optString("logo", ""),
                                    num = obj.optInt("num", i + 1)
                                )
                            )
                        }
                    }
                    PlayerActivity.inMemoryPlaylist = list
                } catch (_: Exception) {
                    PlayerActivity.inMemoryPlaylist = null
                }

                val intent = Intent(activity, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_URL, streamUrl)
                    putExtra(PlayerActivity.EXTRA_TITLE, title)
                    putExtra(PlayerActivity.EXTRA_SUBTITLE, subtitle)
                    putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, currentIndex)
                }
                activity.startActivity(intent)
            }
        }

        @JavascriptInterface
        fun playDemo(title: String?) {
            play(
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                if (!title.isNullOrBlank()) title else "Demo Stream",
                "Demo HLS · ExoPlayer"
            )
        }
    }
}
