package nl.ejvegt.hawerkplek

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Kodi rechtstreeks bedienen via JSON-RPC over http (bladeren, afspelen, ondertitels, audio). */
class KodiApi(
    private val host: () -> String,
    private val port: () -> String,
    private val user: () -> String,
    private val pass: () -> String,
) {
    class Entry(
        val label: String, val file: String, val isDir: Boolean,
        val played: Boolean, val resumeSec: Double, val totalSec: Double,
        /** false = Kodi gaf geen kijkstatus mee in de maplijst (dan los opvragen). */
        val hasInfo: Boolean = true,
    )

    class Stream(val index: Int, val label: String)

    class PlayerState(
        val playerId: Int, val title: String, val time: String, val total: String,
        val subtitles: List<Stream>, val currentSub: Int, val subOn: Boolean,
        val audio: List<Stream>, val currentAudio: Int,
    )

    private var id = 0

    fun rpc(method: String, params: JSONObject = JSONObject(), timeoutMs: Int = 8_000): Any? {
        val conn = URL("http://${host()}:${port()}/jsonrpc").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs + 4_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (user().isNotEmpty()) {
            val auth = Base64.encodeToString("${user()}:${pass()}".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $auth")
        }
        val body = JSONObject().put("jsonrpc", "2.0").put("id", ++id).put("method", method).put("params", params)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        if (code == 401) throw IOException("Kodi weigert: gebruiker of wachtwoord klopt niet")
        if (code !in 200..299) throw IOException("Kodi gaf fout $code")
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        json.optJSONObject("error")?.let { throw IOException("Kodi: ${it.optString("message")}") }
        return json.opt("result")
    }

    fun ping(): Boolean = runCatching { rpc("JSONRPC.Ping", timeoutMs = 2_000) == "pong" }.getOrDefault(false)

    /** Video-bronnen (de mappen die je in Kodi hebt toegevoegd). */
    fun sources(): List<Entry> = listOf("video", "files").flatMap { media ->
        val r = runCatching { rpc("Files.GetSources", JSONObject().put("media", media)) as? JSONObject }.getOrNull()
        val arr = r?.optJSONArray("sources") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Entry(it.optString("label"), it.optString("file"), true, false, 0.0, 0.0) }
    }.distinctBy { it.file.trimEnd('/') }

    fun list(dir: String): List<Entry> {
        val params = JSONObject().put("directory", dir).put("media", "video")
            .put("properties", JSONArray().put("playcount").put("resume"))
            .put("sort", JSONObject().put("method", "label").put("order", "ascending").put("ignorearticle", true))
        val r = rpc("Files.GetDirectory", params) as? JSONObject ?: return emptyList()
        val arr = r.optJSONArray("files") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            val res = it.optJSONObject("resume")
            Entry(
                it.optString("label"), it.optString("file"), it.optString("filetype") == "directory",
                it.optInt("playcount") > 0, res?.optDouble("position") ?: 0.0, res?.optDouble("total") ?: 0.0,
                it.has("playcount") || it.has("resume"),
            )
        }
    }

    /** Kijkstatus van één bestand (ook als het niet in de bibliotheek staat). */
    fun details(e: Entry): Entry {
        val r = rpc("Files.GetFileDetails", JSONObject().put("file", e.file).put("media", "video")
            .put("properties", JSONArray().put("playcount").put("resume"))) as? JSONObject
        val d = r?.optJSONObject("filedetails") ?: return e
        val res = d.optJSONObject("resume")
        return Entry(e.label, e.file, e.isDir, d.optInt("playcount") > 0,
            res?.optDouble("position") ?: 0.0, res?.optDouble("total") ?: 0.0, true)
    }

    fun play(file: String, resume: Boolean) {
        rpc("Player.Open", JSONObject().put("item", JSONObject().put("file", file))
            .put("options", JSONObject().put("resume", resume)))
    }

    private fun activeVideoPlayer(): Int? {
        val arr = rpc("Player.GetActivePlayers") as? JSONArray ?: return null
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .firstOrNull { it.optString("type") == "video" }?.optInt("playerid")
    }

    private fun fmt(t: JSONObject?): String {
        if (t == null) return ""
        val h = t.optInt("hours"); val m = t.optInt("minutes"); val s = t.optInt("seconds")
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun streams(arr: JSONArray?): List<Stream> = (0 until (arr?.length() ?: 0)).map {
        val o = arr!!.getJSONObject(it)
        val lang = o.optString("language")
        val name = o.optString("name")
        val extra = listOfNotNull(
            o.optInt("channels").takeIf { c -> c > 0 }?.let { c -> if (c >= 6) "${c - 1}.1" else "stereo" },
            "ondertiteling voor doven".takeIf { o.optBoolean("isimpaired") },
        )
        Stream(o.optInt("index"), (listOf(langName(lang).ifEmpty { name.ifEmpty { "Spoor ${it + 1}" } }) +
            listOfNotNull(name.takeIf { n -> n.isNotEmpty() && langName(lang).isNotEmpty() && !n.equals(lang, true) }) + extra)
            .joinToString(" · "))
    }

    private fun langName(code: String) = when (code.lowercase()) {
        "dut", "nld", "nl" -> "Nederlands"; "eng", "en" -> "Engels"; "ger", "deu", "de" -> "Duits"
        "fre", "fra", "fr" -> "Frans"; "spa", "es" -> "Spaans"; "ita", "it" -> "Italiaans"
        "", "und" -> ""; else -> code.uppercase()
    }

    fun state(): PlayerState? {
        val pid = activeVideoPlayer() ?: return null
        val p = rpc("Player.GetProperties", JSONObject().put("playerid", pid).put("properties", JSONArray()
            .put("subtitles").put("currentsubtitle").put("subtitleenabled")
            .put("audiostreams").put("currentaudiostream").put("time").put("totaltime"))) as? JSONObject ?: return null
        val item = (rpc("Player.GetItem", JSONObject().put("playerid", pid)
            .put("properties", JSONArray().put("showtitle").put("season").put("episode").put("title").put("file"))) as? JSONObject)
            ?.optJSONObject("item")
        val title = item?.let {
            val show = it.optString("showtitle")
            if (show.isNotEmpty() && it.optInt("season") > 0) "%s · S%dE%d".format(show, it.optInt("season"), it.optInt("episode"))
            else it.optString("title").ifEmpty { it.optString("label").ifEmpty { it.optString("file").substringAfterLast('/') } }
        } ?: "Kodi"
        return PlayerState(
            pid, title, fmt(p.optJSONObject("time")), fmt(p.optJSONObject("totaltime")),
            streams(p.optJSONArray("subtitles")), p.optJSONObject("currentsubtitle")?.optInt("index", -1) ?: -1,
            p.optBoolean("subtitleenabled"),
            streams(p.optJSONArray("audiostreams")), p.optJSONObject("currentaudiostream")?.optInt("index", -1) ?: -1,
        )
    }

    fun setSubtitle(pid: Int, index: Int?) {
        val params = JSONObject().put("playerid", pid)
        if (index == null) params.put("subtitle", "off") else params.put("subtitle", index).put("enable", true)
        rpc("Player.SetSubtitle", params)
    }

    fun setAudio(pid: Int, index: Int) {
        rpc("Player.SetAudioStream", JSONObject().put("playerid", pid).put("stream", index))
    }

    /** Opent het zoekscherm voor ondertitels op de tv (zoekt via je ondertitel-add-ons). */
    fun searchSubtitles() {
        rpc("GUI.ActivateWindow", JSONObject().put("window", "subtitlesearch"))
    }

    fun action(name: String) {
        rpc("Input.ExecuteAction", JSONObject().put("action", name))
    }
}
