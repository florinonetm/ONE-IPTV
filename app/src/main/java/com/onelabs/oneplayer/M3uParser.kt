package com.onelabs.oneplayer

import java.io.BufferedReader
import java.io.StringReader
import java.net.URL

object M3uParser {

    enum class Kind {
        CHANNEL_LIST,
        HLS_MASTER,
        HLS_MEDIA
    }

    data class Entry(
        val title: String,
        val url: String,
        val group: String = "",
        val logo: String = "",
        val duration: Int = 0,
        val bandwidth: Int = 0,
        val resolution: String = ""
    )

    data class Result(
        val kind: Kind,
        val entries: List<Entry>,
        val totalCount: Int
    )

    fun parse(content: String, sourceUrl: String = ""): Result {
        val trimmed = content.trim().removePrefix("\uFEFF")
        val reader = BufferedReader(StringReader(trimmed))
        return parseStream(reader, sourceUrl)
    }

    fun parseStream(reader: BufferedReader, sourceUrl: String = "", maxEntries: Int = 50000): Result {
        val lines = reader.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) {
            return Result(Kind.CHANNEL_LIST, emptyList(), 0)
        }

        val isHlsMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
        val isHlsMedia = !isHlsMaster && lines.any {
            it.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true) ||
            it.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true)
        }

        return when {
            isHlsMaster -> parseHlsMaster(lines, sourceUrl)
            isHlsMedia -> Result(Kind.HLS_MEDIA, emptyList(), 0)
            else -> parseChannelList(lines, sourceUrl, maxEntries)
        }
    }

    private fun parseHlsMaster(lines: List<String>, sourceUrl: String): Result {
        val entries = mutableListOf<Entry>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val bandwidth = parseIntAttr(line, "BANDWIDTH")
                val resolution = parseRawAttr(line, "RESOLUTION")
                val name = parseQuotedAttr(line, "NAME")
                val nextUrlLine = lines.getOrNull(i + 1)
                if (nextUrlLine != null && !nextUrlLine.startsWith("#")) {
                    val resolved = resolveUrl(sourceUrl, nextUrlLine)
                    val title = if (name.isNotEmpty()) name else if (resolution.isNotEmpty()) resolution else "Variant ${entries.size + 1}"
                    entries.add(
                        Entry(
                            title = title,
                            url = resolved,
                            group = "HLS variants",
                            logo = "",
                            duration = 0,
                            bandwidth = bandwidth,
                            resolution = resolution
                        )
                    )
                    i++
                }
            }
            i++
        }
        return Result(Kind.HLS_MASTER, entries, entries.size)
    }

    private fun parseChannelList(lines: List<String>, sourceUrl: String, maxEntries: Int): Result {
        val entries = mutableListOf<Entry>()
        var currentTitle = ""
        var currentGroup = ""
        var currentLogo = ""
        var currentDuration = 0

        var i = 0
        while (i < lines.size && entries.size < maxEntries) {
            val line = lines[i]
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                currentDuration = parseDuration(line)
                currentTitle = parseTitle(line)
                currentGroup = parseQuotedAttr(line, "group-title")
                currentLogo = parseQuotedAttr(line, "tvg-logo").ifEmpty { parseQuotedAttr(line, "logo") }
                val tvgName = parseQuotedAttr(line, "tvg-name")
                if (currentTitle.isEmpty()) {
                    currentTitle = tvgName.ifEmpty { "Channel ${entries.size + 1}" }
                }
            } else if (!line.startsWith("#") && isStreamUrl(line)) {
                val url = resolveUrl(sourceUrl, line)
                val title = if (currentTitle.isNotEmpty()) currentTitle else guessNameFromUrl(url, entries.size + 1)
                entries.add(
                    Entry(
                        title = title,
                        url = url,
                        group = currentGroup.ifEmpty { "All Channels" },
                        logo = currentLogo,
                        duration = currentDuration
                    )
                )
                currentTitle = ""
                currentGroup = ""
                currentLogo = ""
                currentDuration = 0
            }
            i++
        }
        return Result(Kind.CHANNEL_LIST, entries, entries.size)
    }

    fun parseDuration(line: String): Int {
        val durStr = line.substringAfter(":").substringBefore(",").trim()
        return durStr.toFloatOrNull()?.toInt() ?: 0
    }

    fun parseTitle(line: String): String {
        val commaIndex = line.indexOf(',')
        return if (commaIndex >= 0 && commaIndex < line.length - 1) {
            line.substring(commaIndex + 1).trim()
        } else ""
    }

    fun parseQuotedAttr(line: String, attrName: String): String {
        val regex = Regex("""$attrName="([^"]*)"""", RegexOption.IGNORE_CASE)
        val match = regex.find(line)
        if (match != null) return match.groupValues[1].trim()
        val singleRegex = Regex("""$attrName='([^']*)'""", RegexOption.IGNORE_CASE)
        return singleRegex.find(line)?.groupValues?.get(1)?.trim() ?: ""
    }

    fun parseRawAttr(line: String, attrName: String): String {
        val regex = Regex("""$attrName=([^,]+)""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.get(1)?.trim()?.trim('"', '\'') ?: ""
    }

    fun parseIntAttr(line: String, attrName: String): Int {
        return parseRawAttr(line, attrName).toIntOrNull() ?: 0
    }

    fun resolveUrl(baseUrl: String, relOrAbsUrl: String): String {
        val trimmed = relOrAbsUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return try {
            if (baseUrl.isNotEmpty()) {
                val base = URL(baseUrl)
                URL(base, trimmed).toString()
            } else {
                trimmed
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    fun isStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://") ||
                lower.startsWith("rtmp://") || lower.startsWith("rtsp://")
    }

    fun guessNameFromUrl(url: String, fallbackIndex: Int): String {
        return try {
            val path = URL(url).path
            val last = path.split("/").lastOrNull { it.isNotEmpty() } ?: "Stream $fallbackIndex"
            last.replace(Regex("""\.(m3u8?|mpd|ts|mp4|mkv)$"""), "")
        } catch (_: Exception) {
            "Stream $fallbackIndex"
        }
    }
}
