package com.metrolist.netease

import com.metrolist.netease.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.setBody
import io.ktor.http.Parameters
import io.ktor.http.userAgent
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import kotlin.math.abs
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private fun convertNeteaseJsonLyric(lyricJson: String): String {
    // The lyricJson may contain both JSON object lines (from Netease) and standard LRC lines.
    // We only convert JSON lines (starting with '{'), and keep LRC lines unchanged.
    val lines = lyricJson.split("\n").filter { it.trim().isNotEmpty() }
    val result = StringBuilder()
    lines.forEach { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            // Try to parse as JSON object with "t" and "c"
            try {
                val json = JSONObject(trimmed)
                val t = json.optLong("t", 0)
                val cArray = json.optJSONArray("c") ?: return@forEach

                // Build lyric text from c array's tx fields
                val sb = StringBuilder()
                for (i in 0 until cArray.length()) {
                    val obj = cArray.optJSONObject(i) ?: continue
                    val tx = obj.optString("tx", "")
                    if (tx.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append(" ")
                        sb.append(tx)
                    }
                }

                if (sb.isNotEmpty()) {
                    // Convert time t (ms) to LRC tag [MM:SS.xx]
                    val totalMs = t
                    val minutes = totalMs / 60000
                    val seconds = (totalMs % 60000) / 1000
                    val centis = (totalMs % 1000) / 10
                    val timeTag = String.format(Locale.ROOT, "[%02d:%02d.%02d]", minutes, seconds, centis)
                    result.append(timeTag).append(sb).append("\n")
                }
            } catch (e: Exception) {
                // If parsing fails, keep original line
                if (BuildConfig.DEBUG) {
                    Timber.tag("NeteaseProvider").w(e, "Failed to parse JSON lyric line")
                }
                result.append(line).append("\n")
            }
        } else {
            // Assume it's already in LRC or plain text format; keep as-is.
            result.append(line).append("\n")
        }
    }
    return result.toString().trim()
}

@OptIn(ExperimentalSerializationApi::class, ExperimentalCoroutinesApi::class)
object NeteaseCloudMusicLyricsProvider {
    // 提供获取歌词的功能，不实现 LyricsProvider 接口
    // 由 app 模块的 wrapper 负责接口适配

    private const val OFFICIAL_API_BASE_URL = "https://interface.music.163.com"
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val EAPI_MAGIC = "36cd479b6b5"
    private const val DURATION_TOLERANCE = 8000

    private val client = HttpClient {
        expectSuccess = false

        install(ContentNegotiation) {
            val json = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = false
            }
            json(json)
        }
    }

    suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = runCatching {
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").i("getLyrics called")
        }
        val songId = findSongId(title, artist, duration) ?: throw IllegalStateException("No matching song found on Netease")
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").i("Found songId")
        }
        val result = fetchLyrics(songId)
        buildString {
            append(result.lyric)
            if (!result.tlyric.isNullOrBlank()) {
                append("\n\n[translate]\n")
                append(result.tlyric)
            }
        }.also { combined ->
            if (BuildConfig.DEBUG) {
                Timber.tag("NeteaseProvider").i("Returning lyrics: length=${combined.length}")
            }
        }
    }

    suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").i("getAllLyrics called")
        }
        try {
            val songId = findSongId(title, artist, duration)
            if (songId != null) {
                if (BuildConfig.DEBUG) {
                    Timber.tag("NeteaseProvider").i("Found songId")
                }
                val result = fetchLyrics(songId)
                val combined = buildString {
                    append(result.lyric)
                    if (!result.tlyric.isNullOrBlank()) {
                        append("\n\n[translate]\n")
                        append(result.tlyric)
                    }
                }
                if (BuildConfig.DEBUG) {
                    Timber.tag("NeteaseProvider").i("Calling callback with lyrics: length=${combined.length}")
                }
                callback(combined)
            } else {
                if (BuildConfig.DEBUG) {
                    Timber.tag("NeteaseProvider").w("No songId found")
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Timber.tag("NeteaseProvider").e(e, "getAllLyrics failed")
            }
            // Ignore and continue
        }
    }

    private data class NeteaseLyricsResult(
        val lyric: String,
        val tlyric: String? = null,
    )

    private data class NeteaseSong(
        val id: String,
        val name: String,
        val artists: List<String>,
        val album: String?,
        val duration: Int,
    )

    private suspend fun findSongId(title: String, artist: String, duration: Int): String? {
        val searchQuery = buildString {
            append(title)
            append(" ")
            append(artist)
        }
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").w("FIND_SONG_ID")
        }

        return try {
            val json = eapiRequest(
                path = "/api/cloudsearch/pc",
                data = mapOf(
                    "s" to searchQuery,
                    "type" to "1",
                    "limit" to "30",
                    "offset" to "0",
                    "e_r" to "false"
                )
            )

            val jsonObj = json.jsonObject
            val code = (jsonObj.get("code") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            if (BuildConfig.DEBUG) {
                Timber.tag("NeteaseProvider").d("Search response code: $code")
            }
            
            if (code == 200) {
                val result = jsonObj.get("result")?.jsonObject ?: return null
                val songs = result.get("songs")?.jsonArray ?: return null

                val songList = songs.mapNotNull { songObj ->
                    val song = songObj.jsonObject
                    val id = (song.get("id") as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val name = (song.get("name") as? JsonPrimitive)?.content ?: ""
                    val artists = song.get("artists")?.jsonArray?.map { 
                        (it.jsonObject.get("name") as? JsonPrimitive)?.content ?: "" 
                    } ?: emptyList()
                    val album = song.get("album")?.jsonObject
                    val albumName = (album?.get("name") as? JsonPrimitive)?.content
                    val durationMs = (song.get("duration") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

                    NeteaseSong(id, name, artists, albumName, durationMs)
                }

                if (BuildConfig.DEBUG) {
                    Timber.tag("NeteaseProvider").d("Found ${songList.size} songs")
                }
                val bestMatch = songList
                    .filter { abs(it.duration - duration) <= DURATION_TOLERANCE }
                    .minByOrNull { abs(it.duration - duration) }

                val chosen = bestMatch
                if (BuildConfig.DEBUG) {
                    Timber.tag("NeteaseProvider").d("Selected match: ${chosen != null}, duration=${chosen?.duration}")
                }
                return chosen?.id
            } else {
                if (BuildConfig.DEBUG) {
                    Timber.tag("NeteaseProvider").w("Search failed with code: $code")
                }
                return null
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Timber.tag("NeteaseProvider").e(e, "Search exception")
            }
            return null
        }
    }

    private suspend fun fetchLyrics(songId: String): NeteaseLyricsResult {
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").d("Fetching lyrics")
        }
        val json = eapiRequest(
            path = "/api/song/lyric/v1",
            data = mapOf(
                "id" to songId,
                "cp" to "false",
                "tv" to "0",
                "lv" to "0",
                "rv" to "0",
                "kv" to "0",
                "yv" to "0",
                "ytv" to "0",
                "yrv" to "0"
                // Note: e_r parameter omitted (default false, plaintext response)
            )
        )

        val jsonObj = json.jsonObject
        // Response code can be in 'code' or 'status' field
        val code = (jsonObj.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
            ?: (jsonObj.get("status") as? JsonPrimitive)?.content?.toIntOrNull()
            ?: 0
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").d("Lyrics response code: $code")
        }

        if (code == 200) {
            // Parse lrc object
            val lrc = jsonObj.get("lrc")?.jsonObject
                ?: throw IllegalStateException("No lrc in response")
            
            // lyric can be JsonPrimitive (string), JsonObject, or null
            val lyricRaw = when (val e = lrc.get("lyric")) {
                is JsonPrimitive -> e.content
                is JsonObject -> e.toString()
                null -> throw IllegalStateException("lyric field missing in lrc")
                else -> throw IllegalStateException("Unexpected lyric type: ${e::class.simpleName}")
            }
            // Convert Netease JSON lyric ({"t":..., "c":[...]}) to standard LRC format.
            // This handles both pure JSON strings and mixed LRC+JSON content.
            val lyric = convertNeteaseJsonLyric(lyricRaw)

            // tlyric may be at top level; handle similar to lrc
            val tlyricElement = jsonObj.get("tlyric")
            val tlyricRaw = when (tlyricElement) {
                is JsonPrimitive -> tlyricElement.content
                is JsonObject -> tlyricElement.toString()
                null -> null
                else -> null
            }
            val tlyric = tlyricRaw?.let { convertNeteaseJsonLyric(it) }

            if (BuildConfig.DEBUG) {
                Timber.tag("NeteaseProvider").d("Lyrics fetched: length=${lyric.length}, hasTranslation=${tlyric != null}")
            }
            return NeteaseLyricsResult(lyric, tlyric)
        } else {
            if (BuildConfig.DEBUG) {
                Timber.tag("NeteaseProvider").w("Failed to fetch lyrics: code $code")
            }
            throw IllegalStateException("Failed to fetch lyrics: code $code")
        }
    }

    private suspend fun eapiRequest(path: String, data: Map<String, Any>): JsonElement {
        // 重要：加密和签名必须使用原始 path（例如 /api/cloudsearch/pc）
        // 只有最终请求 URL 才使用转换后的路径
        val transformedPath = if (path.startsWith("/api/")) "/eapi/${path.substring(5)}" else path
        val url = "$OFFICIAL_API_BASE_URL$transformedPath"
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").d("EAPI Request URL: $url")
        }

        // Build eapi encrypted params with header (matching api-enhanced)
        val header = buildEapiHeader()
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").d("Constructed header: $header")
        }

        // Build JSON using org.json.JSONObject to avoid Kotlinx serialization builder issues
        val jsonData = JSONObject().apply {
            data.forEach { (k, v) -> put(k, v) }
            val headerObj = JSONObject().apply {
                header.forEach { (k, v) -> put(k, v) }
            }
            put("header", headerObj)
        }.toString()

        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").d("Data with header (JSON): $jsonData")
        }

        // 关键修复：签名和加密数据使用原始 path（不是 transformedPath）
        val message = "nobody${path}use${jsonData}md5forencrypt"
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").d("Sign message: $message")
        }

        val digest = md5(message)
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").d("MD5 digest: $digest")
        }

        val eapiData = "$path-$EAPI_MAGIC-$jsonData-$EAPI_MAGIC-$digest"
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").d("EAPI data before encryption: $eapiData")
        }

        val encryptedParams = aesEncrypt(eapiData, EAPI_KEY)
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").d("Encrypted params (hex, first 100 chars): ${encryptedParams.take(100)}...")
        }

        // Send as form data: params=encryptedParams
        val response = client.post(url) {
            userAgent("NeteaseMusic/9.1.65.240927161425(9001065);Dalvik/2.1.0 (Linux; U; Android 14; 23013RK75C Build/UKQ1.230804.001)")
            setBody(FormDataContent(Parameters.build {
                append("params", encryptedParams)
            }))
        }

        val responseStatus = response.status.value
        val responseHeaders = response.headers.toString()
        val responseBody = response.body<String>()
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").w("Response status: $responseStatus")
            Timber.tag("NeteaseProvider").d("Response headers: $responseHeaders")
            Timber.tag("NeteaseProvider").d("Response body (length=${responseBody.length}): ${responseBody.take(500)}")
        }

        return Json.parseToJsonElement(responseBody)
    }

    private fun buildEapiHeader(): Map<String, String> {
        val random = java.util.Random()
        val deviceId = ByteArray(32).apply { random.nextBytes(this) }.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val requestId = System.currentTimeMillis().toString() + random.nextInt(1000)
        val buildVer = (System.currentTimeMillis() / 1000).toString().substring(0, 10)

        val header = linkedMapOf(
            "osver" to "14",
            "deviceId" to deviceId,
            "os" to "android",
            "appver" to "8.20.20.231215173437",
            "versioncode" to "140",
            "mobilename" to "",
            "buildver" to buildVer,
            "resolution" to "1920x1080",
            "__csrf" to "",
            "channel" to "netease",
            "requestId" to requestId
        )
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").d("Built eapi header: $header")
        }
        return header
    }

    private fun aesEncrypt(input: String, key: String): String {
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encrypted = cipher.doFinal(input.toByteArray(Charsets.UTF_8))
        val result = bytesToHex(encrypted)
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").d("AES-ECB encrypt: inputLen=${input.length}, outputLen=${result.length}")
        }
        return result
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        val result = bytesToHex(digest)
        if (BuildConfig.DEBUG) {
            Timber.tag("NeteaseProvider").d("MD5: inputLen=${input.length}, output=$result")
        }
        return result
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexArray = "0123456789abcdef".toCharArray()
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }
}
