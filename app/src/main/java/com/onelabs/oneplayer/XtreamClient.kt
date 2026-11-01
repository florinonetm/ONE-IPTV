package com.onelabs.oneplayer

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object XtreamClient {

    data class Account(
        val server: String,
        val username: String,
        val password: String
    )

    data class Channel(
        val id: String = "",
        val num: Int = 0,
        val name: String = "",
        val streamType: String = "live",
        val streamIcon: String = "",
        val categoryId: String = "",
        val categoryName: String = "",
        val directSource: String = "",
        val url: String = ""
    )

    data class ImportResult(
        val ok: Boolean,
        val message: String,
        val channels: List<Channel> = emptyList(),
        val serverInfo: JSONObject? = null,
        val userInfo: JSONObject? = null
    )

    private fun enc(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }

    fun normalizeServer(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
            s = "http://$s"
        }
        if (s.endsWith("/player_api.php")) s = s.removeSuffix("/player_api.php")
        if (s.endsWith("/get.php")) s = s.removeSuffix("/get.php")
        return s
    }

    fun httpGet(urlStr: String, timeoutMs: Int = 12000): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "ONEPlayer/1.02")
        conn.requestMethod = "GET"

        try {
            val code = conn.responseCode
            if (code in 200..299) {
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                throw RuntimeException("HTTP $code from Xtream server")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun loadArray(server: String, user: String, pass: String, action: String): JSONArray {
        val url = "$server/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=$action"
        return try {
            val resp = httpGet(url).trim()
            if (resp.startsWith("[")) JSONArray(resp) else JSONArray()
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun loadCategoryMap(server: String, user: String, pass: String, action: String): Map<String, String> {
        val arr = loadArray(server, user, pass, action)
        val map = mutableMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("category_id", "")
            val name = obj.optString("category_name", "")
            if (id.isNotEmpty()) {
                map[id] = name
            }
        }
        return map
    }

    fun fetchAccount(
        account: Account,
        maxLive: Int = 5000,
        maxVod: Int = 3000,
        maxSeries: Int = 2000
    ): ImportResult {
        val server = normalizeServer(account.server)
        val user = account.username.trim()
        val pass = account.password.trim()

        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            return ImportResult(false, "Server, username and password are required")
        }

        try {
            val loginUrl = "$server/player_api.php?username=${enc(user)}&password=${enc(pass)}"
            val loginResp = httpGet(loginUrl).trim()
            if (!loginResp.startsWith("{")) {
                return fetchViaGetPhp(account)
            }

            val json = JSONObject(loginResp)
            val userInfo = json.optJSONObject("user_info")
            val serverInfo = json.optJSONObject("server_info")

            if (userInfo == null) {
                return ImportResult(false, "Authentication failed (check server / user / password)")
            }

            val auth = userInfo.optInt("auth", -1)
            val status = userInfo.optString("status", "")
            if (auth == 0 || status.equals("Disabled", ignoreCase = true) || status.equals("Banned", ignoreCase = true)) {
                return ImportResult(false, "Account disabled or invalid credentials")
            }

            val channels = mutableListOf<Channel>()

            // 1. Live categories & streams
            try {
                val liveCats = loadCategoryMap(server, user, pass, "get_live_categories")
                val liveStreams = loadArray(server, user, pass, "get_live_streams")
                val limit = minOf(liveStreams.length(), maxLive)
                for (i in 0 until limit) {
                    val s = liveStreams.optJSONObject(i) ?: continue
                    val streamId = s.optString("stream_id", "")
                    if (streamId.isEmpty()) continue
                    val catId = s.optString("category_id", "")
                    val catName = liveCats[catId] ?: s.optString("category_name", "Live")
                    val name = s.optString("name", "Live Channel")
                    val icon = s.optString("stream_icon", "")
                    val num = s.optInt("num", channels.size + 1)
                    val url = "$server/live/${enc(user)}/${enc(pass)}/$streamId.m3u8"

                    channels.add(
                        Channel(
                            id = streamId,
                            num = num,
                            name = name,
                            streamType = "live",
                            streamIcon = icon,
                            categoryId = catId,
                            categoryName = catName,
                            url = url
                        )
                    )
                }
            } catch (_: Exception) {}

            // 2. VOD categories & streams
            try {
                val vodCats = loadCategoryMap(server, user, pass, "get_vod_categories")
                val vodStreams = loadArray(server, user, pass, "get_vod_streams")
                val limit = minOf(vodStreams.length(), maxVod)
                for (i in 0 until limit) {
                    val s = vodStreams.optJSONObject(i) ?: continue
                    val streamId = s.optString("stream_id", "")
                    if (streamId.isEmpty()) continue
                    val catId = s.optString("category_id", "")
                    val catName = vodCats[catId] ?: s.optString("category_name", "Movies")
                    val name = s.optString("name", "Movie")
                    val icon = s.optString("stream_icon", "")
                    val ext = s.optString("container_extension", "mp4").ifEmpty { "mp4" }
                    val url = "$server/movie/${enc(user)}/${enc(pass)}/$streamId.$ext"

                    channels.add(
                        Channel(
                            id = streamId,
                            num = channels.size + 1,
                            name = name,
                            streamType = "vod",
                            streamIcon = icon,
                            categoryId = catId,
                            categoryName = catName,
                            url = url
                        )
                    )
                }
            } catch (_: Exception) {}

            // 3. Series
            try {
                val seriesCats = loadCategoryMap(server, user, pass, "get_series_categories")
                val seriesList = loadArray(server, user, pass, "get_series")
                val limit = minOf(seriesList.length(), maxSeries)
                for (i in 0 until limit) {
                    val s = seriesList.optJSONObject(i) ?: continue
                    val seriesId = s.optString("series_id", "")
                    if (seriesId.isEmpty()) continue
                    val catId = s.optString("category_id", "")
                    val catName = seriesCats[catId] ?: s.optString("category_name", "Series")
                    val name = s.optString("name", "Series")
                    val icon = s.optString("cover", "")
                    val url = "$server/series/${enc(user)}/${enc(pass)}/$seriesId"

                    channels.add(
                        Channel(
                            id = seriesId,
                            num = channels.size + 1,
                            name = name,
                            streamType = "series",
                            streamIcon = icon,
                            categoryId = catId,
                            categoryName = catName,
                            url = url
                        )
                    )
                }
            } catch (_: Exception) {}

            if (channels.isEmpty()) {
                return ImportResult(true, "Logged in, but no streams available", emptyList(), serverInfo, userInfo)
            }

            return ImportResult(true, "OK", channels, serverInfo, userInfo)
        } catch (e: Exception) {
            return ImportResult(false, e.message ?: "Xtream request failed")
        }
    }

    fun fetchViaGetPhp(account: Account): ImportResult {
        val server = normalizeServer(account.server)
        val user = account.username.trim()
        val pass = account.password.trim()
        val getUrl = "$server/get.php?username=${enc(user)}&password=${enc(pass)}&type=m3u_plus&output=hls"

        return try {
            val m3uContent = httpGet(getUrl)
            val parsed = M3uParser.parse(m3uContent, getUrl)
            val channels = parsed.entries.mapIndexed { idx, e ->
                Channel(
                    id = "$idx",
                    num = idx + 1,
                    name = e.title,
                    streamType = "live",
                    streamIcon = e.logo,
                    categoryName = e.group.ifEmpty { "Live" },
                    url = e.url
                )
            }
            if (channels.isEmpty()) {
                ImportResult(false, "get.php M3U returned no channels")
            } else {
                ImportResult(true, "OK", channels)
            }
        } catch (e: Exception) {
            ImportResult(false, e.message ?: "get.php failed")
        }
    }

    fun parseGetPhpUrl(urlStr: String): Account? {
        return try {
            val url = URL(urlStr)
            val path = url.path ?: ""
            if (!path.contains("get.php")) return null
            val query = url.query ?: return null
            var user = ""
            var pass = ""
            for (param in query.split("&")) {
                val parts = param.split("=")
                if (parts.size == 2) {
                    val k = parts[0].trim()
                    val v = java.net.URLDecoder.decode(parts[1].trim(), "UTF-8")
                    if (k.equals("username", ignoreCase = true)) user = v
                    if (k.equals("password", ignoreCase = true)) pass = v
                }
            }
            if (user.isNotEmpty() && pass.isNotEmpty()) {
                val base = "${url.protocol}://${url.host}" + if (url.port > 0) ":${url.port}" else ""
                Account(base, user, pass)
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
