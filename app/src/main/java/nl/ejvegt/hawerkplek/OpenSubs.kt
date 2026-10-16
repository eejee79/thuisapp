package nl.ejvegt.hawerkplek

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Ondertitels zoeken en downloaden via de OpenSubtitles.com REST-API. */
class OpenSubs(
    private val apiKey: () -> String,
    private val user: () -> String,
    private val pass: () -> String,
) {
    class Result(
        val fileId: Long, val lang: String, val release: String, val downloads: Int,
        val impaired: Boolean, val fileName: String,
    )

    /** Uit een bestandsnaam: titel, seizoen, aflevering, jaar. */
    class Query(val text: String, val season: Int?, val episode: Int?, val year: Int?)

    companion object {
        private const val BASE = "https://api.opensubtitles.com/api/v1"

        fun parse(fileName: String): Query {
            val name = fileName.substringAfterLast('/').substringBeforeLast('.')
            val clean = { t: String -> t.replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim(' ', '-') }
            Regex("(?i)^(.*?)[ ._-]*S(\\d{1,2})[ ._-]?E(\\d{1,3})").find(name)?.let {
                return Query(clean(it.groupValues[1]), it.groupValues[2].toInt(), it.groupValues[3].toInt(), null)
            }
            Regex("(?i)^(.*?)[ ._-]*(\\d{1,2})x(\\d{2})").find(name)?.let {
                return Query(clean(it.groupValues[1]), it.groupValues[2].toInt(), it.groupValues[3].toInt(), null)
            }
            Regex("^(.*?)[ ._(\\[-]+((?:19|20)\\d{2})(?:[ ._)\\]-]|$)").find(name)?.let {
                return Query(clean(it.groupValues[1]), null, null, it.groupValues[2].toInt())
            }
            return Query(clean(name), null, null, null)
        }

        fun langName(code: String) = when (code.lowercase()) {
            "nl" -> "🇳🇱 Nederlands"; "en" -> "🇬🇧 Engels"; "de" -> "🇩🇪 Duits"; "fr" -> "🇫🇷 Frans"
            "es" -> "🇪🇸 Spaans"; else -> code.uppercase()
        }
    }

    @Volatile private var token: String? = null
    @Volatile private var base = BASE

    private fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = false): JSONObject {
        val conn = URL((if (auth) base else BASE) + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Api-Key", apiKey())
        conn.setRequestProperty("User-Agent", "Thuisbasis v${BuildConfig.VERSION_NAME}")
        conn.setRequestProperty("Accept", "application/json")
        if (auth) token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        if (code !in 200..299) {
            val msg = json.optString("message").ifEmpty { json.optJSONArray("errors")?.optString(0) ?: "" }
            if (code == 401) token = null
            throw IOException(when (code) {
                401 -> "OpenSubtitles: inloggen of API-sleutel klopt niet"
                406 -> "OpenSubtitles: daglimiet voor downloads bereikt" + (msg.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
                429 -> "OpenSubtitles: even te veel verzoeken, probeer zo opnieuw"
                else -> "OpenSubtitles fout $code ${msg}".trim()
            })
        }
        return json
    }

    private fun login() {
        if (token != null) return
        val r = call("POST", "/login", JSONObject().put("username", user()).put("password", pass()))
        token = r.optString("token").ifEmpty { null } ?: throw IOException("OpenSubtitles: inloggen mislukt")
        r.optString("base_url").takeIf { it.isNotEmpty() }?.let { base = "https://$it/api/v1" }
    }

    fun search(q: Query, languages: String): List<Result> {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        // Parameters op alfabet en in kleine letters (zo wil de API het, anders een omleiding)
        val params = sortedMapOf<String, String>()
        params["languages"] = languages.lowercase().replace(" ", "")
        params["query"] = q.text.lowercase()
        q.season?.let { params["season_number"] = it.toString() }
        q.episode?.let { params["episode_number"] = it.toString() }
        q.year?.let { params["year"] = it.toString() }
        params["order_by"] = "download_count"
        val r = call("GET", "/subtitles?" + params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" })
        val data = r.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val a = data.getJSONObject(i).optJSONObject("attributes") ?: return@mapNotNull null
            val f = a.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
            Result(
                f.optLong("file_id"), a.optString("language"), a.optString("release").ifEmpty { f.optString("file_name") },
                a.optInt("download_count"), a.optBoolean("hearing_impaired"), f.optString("file_name"),
            )
        }
    }

    /** Geeft (inhoud van het .srt-bestand, resterende downloads vandaag). */
    fun download(r: Result): Pair<ByteArray, Int> {
        login()
        val d = try {
            call("POST", "/download", JSONObject().put("file_id", r.fileId), auth = true)
        } catch (e: IOException) {
            if (token == null) { login(); call("POST", "/download", JSONObject().put("file_id", r.fileId), auth = true) } else throw e
        }
        val link = d.optString("link").ifEmpty { throw IOException("OpenSubtitles gaf geen downloadlink") }
        val conn = URL(link).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        val bytes = conn.inputStream.use { it.readBytes() }
        return bytes to d.optInt("remaining", -1)
    }
}
