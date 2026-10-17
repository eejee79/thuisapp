package nl.ejvegt.hawerkplek

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Songteksten van LRCLIB (gratis, vaak met tijden per regel). */
object LyricsApi {
    class Lyrics(val lines: List<Pair<Double, String>>, val plain: String?)

    private val cache = ConcurrentHashMap<String, java.util.Optional<Lyrics>>()

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "Thuisbasis (prive app)")
        if (c.responseCode !in 200..299) throw java.io.IOException("lrclib ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun parse(o: JSONObject): Lyrics? {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        val lines = synced?.lines()?.mapNotNull { l ->
            val m = Regex("""^\[(\d+):(\d+(?:\.\d+)?)]\s?(.*)$""").find(l.trim()) ?: return@mapNotNull null
            (m.groupValues[1].toInt() * 60 + m.groupValues[2].toDouble()) to m.groupValues[3]
        }.orEmpty()
        if (lines.isEmpty() && plain == null) return null
        return Lyrics(lines, plain)
    }

    /** Zoekt de tekst; null = niets gevonden. Blokkeert (aanroepen in een achtergrondthread). */
    fun find(artist: String, title: String, album: String?, durationSec: Double?): Lyrics? {
        val clean = title.replace(Regex("""\s*[(\[].*?(remaster|live|version|mix|edit).*?[)\]]""", RegexOption.IGNORE_CASE), "").trim()
        val key = "${artist.lowercase()}|${clean.lowercase()}"
        cache[key]?.let { return if (it.isPresent) it.get() else null }
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val r = runCatching {
            var url = "https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(clean)}"
            if (album != null) url += "&album_name=${enc(album)}"
            if (durationSec != null && durationSec > 0) url += "&duration=${durationSec.toInt()}"
            parse(JSONObject(get(url)))
        }.getOrNull() ?: runCatching {
            val arr = JSONArray(get("https://lrclib.net/api/search?artist_name=${enc(artist)}&track_name=${enc(clean)}"))
            // Liefst een treffer met tijden per regel
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            (list.firstOrNull { it.optString("syncedLyrics").isNotBlank() && it.optString("syncedLyrics") != "null" } ?: list.firstOrNull())
                ?.let { parse(it) }
        }.getOrNull()
        cache[key] = java.util.Optional.ofNullable(r)
        return r
    }
}

/**
 * Rollende songtekst: vorige, huidige (groot) en volgende regel, meelopend met de afspeeltijd.
 * Zonder tijden per regel: een tik toont de hele tekst.
 */
class LyricsBar(ctx: Context, private val accent: Int, private val onShowAll: (String) -> Unit) : LinearLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val prev = line(13f, 0xFF8A8A8A.toInt(), false)
    private val cur = line(17f, Color.WHITE, true)
    private val next = line(13f, 0xFF8A8A8A.toInt(), false)
    private var lyrics: LyricsApi.Lyrics? = null
    private var key = ""
    private var shownIdx = -2

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        addView(prev); addView(cur); addView(next)
        visibility = GONE
        isClickable = true
        setOnClickListener {
            val l = lyrics ?: return@setOnClickListener
            onShowAll(l.plain ?: l.lines.joinToString("\n") { it.second })
        }
    }

    private fun line(size: Float, color: Int, bold: Boolean) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 2
        setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
    }

    /** Nieuw nummer: tekst opzoeken (alleen als het een ander nummer is). */
    fun load(artist: String?, title: String?, album: String?, duration: Double?) {
        if (artist.isNullOrBlank() || title.isNullOrBlank()) { key = ""; lyrics = null; visibility = GONE; return }
        val k = "$artist|$title"
        if (k == key) return
        key = k
        lyrics = null
        visibility = GONE
        shownIdx = -2
        thread {
            val l = LyricsApi.find(artist, title, album, duration)
            post {
                if (key != k) return@post
                lyrics = l
                if (l == null) return@post
                visibility = VISIBLE
                if (l.lines.isEmpty()) {
                    prev.text = ""; next.text = ""
                    cur.text = "Songtekst"
                    cur.setTextColor(accent)
                    next.text = "tik om te lezen"
                }
            }
        }
    }

    /** Afspeeltijd in seconden: de juiste regel tonen. */
    fun update(pos: Double) {
        val l = lyrics ?: return
        if (l.lines.isEmpty()) return
        val idx = l.lines.indexOfLast { it.first <= pos + 0.3 }
        if (idx == shownIdx) return
        shownIdx = idx
        fun t(i: Int) = l.lines.getOrNull(i)?.second?.ifBlank { "♪" } ?: ""
        prev.text = t(idx - 1)
        cur.text = if (idx < 0) "♪" else t(idx)
        cur.setTextColor(accent)
        next.text = t(idx + 1)
    }
}
