package nl.ejvegt.hawerkplek

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Tv-gids uit een openbaar XMLTV-bestand (epgshare01, Nederlandse zenders).
 * Eén keer per 12 uur downloaden, daarna uit de cache; alleen de zenders die de app kent worden bewaard.
 */
object TvGuide {
    private const val URL_NL = "https://epgshare01.online/epgshare01/epg_ripper_NL1.xml.gz"

    class Prog(val start: Long, val stop: Long, val title: String, val desc: String?)

    /** Programma's per zendernaam zoals in de app (bijv. "NPO 1"). */
    @Volatile var data: Map<String, List<Prog>> = emptyMap()
        private set
    @Volatile var loadedAt = 0L
        private set

    private val SUFFIXES = listOf("hd", "nederland", "nl", "channel", "tv", "televisie")

    /** Naam vergelijkbaar maken: "NPO 1 HD" → "npo1", "Discovery Channel" → "discovery". */
    fun norm(s: String): String {
        var n = s.lowercase(Locale.ROOT).substringBefore(".nl").replace("&", "and").replace(Regex("[^a-z0-9]"), "")
        var changed = true
        while (changed) {
            changed = false
            for (suf in SUFFIXES) if (n.length > suf.length + 1 && n.endsWith(suf)) { n = n.removeSuffix(suf); changed = true }
        }
        return n
    }

    private val timeFmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT)
    private fun time(s: String?): Long? = s?.let { runCatching { synchronized(timeFmt) { timeFmt.parse(it.trim())?.time } }.getOrNull() }

    /** Gids (bij)laden; blokkeert, dus in een achtergrondthread aanroepen. Geeft een foutmelding of null. */
    fun ensure(cacheDir: File, channels: List<String>, force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!force && data.isNotEmpty() && now - loadedAt < 3 * 3_600_000L) return null
        val file = File(cacheDir, "epg_nl.xml.gz")
        if (force || !file.exists() || now - file.lastModified() > 12 * 3_600_000L) {
            val r = runCatching {
                val c = URL(URL_NL).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 60_000
                c.setRequestProperty("User-Agent", "Thuisbasis (prive app)")
                if (c.responseCode !in 200..299) throw java.io.IOException("gids gaf ${c.responseCode}")
                val tmp = File(cacheDir, "epg_nl.tmp")
                c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                tmp.renameTo(file)
            }
            if (r.isFailure && !file.exists()) return r.exceptionOrNull()?.message ?: "downloaden mislukt"
        }
        return runCatching { parse(file, channels); null }.getOrElse { "gids lezen mislukt: ${it.message}" }
    }

    private fun parse(file: File, channels: List<String>) {
        val wanted = channels.associateBy { norm(it) }          // genormaliseerd → naam in de app
        val idToName = HashMap<String, String>()
        val out = HashMap<String, MutableList<Prog>>()
        val from = System.currentTimeMillis() - 3 * 3_600_000L
        val until = System.currentTimeMillis() + 30 * 3_600_000L
        val p = android.util.Xml.newPullParser()
        GZIPInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
            p.setInput(input, "UTF-8")
            var ev = p.eventType
            var chId: String? = null
            var progCh: String? = null
            var start = 0L
            var stop = 0L
            var title: String? = null
            var desc: String? = null
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> {
                            chId = p.getAttributeValue(null, "id")
                            // Ook de id zelf proberen (bijv. "NPO1.nl")
                            chId?.let { id -> wanted[norm(id)]?.let { idToName.putIfAbsent(id, it) } }
                        }
                        "display-name" -> if (chId != null) {
                            val name = p.nextText()
                            wanted[norm(name)]?.let { idToName.putIfAbsent(chId!!, it) }
                        }
                        "programme" -> {
                            val c = p.getAttributeValue(null, "channel")
                            progCh = if (c != null && idToName.containsKey(c)) c else null
                            if (progCh != null) {
                                start = time(p.getAttributeValue(null, "start")) ?: 0L
                                stop = time(p.getAttributeValue(null, "stop")) ?: 0L
                                if (stop < from || start > until) progCh = null
                            }
                            title = null; desc = null
                        }
                        "title" -> if (progCh != null && title == null) title = p.nextText()
                        "desc" -> if (progCh != null && desc == null) desc = p.nextText()
                    }
                } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG) {
                    when (p.name) {
                        "channel" -> chId = null
                        "programme" -> {
                            val c = progCh
                            if (c != null && title != null) {
                                out.getOrPut(idToName.getValue(c)) { mutableListOf() }.add(Prog(start, stop, title!!, desc))
                            }
                            progCh = null
                        }
                    }
                }
                ev = p.next()
            }
        }
        data = out.mapValues { (_, v) -> v.sortedBy { it.start }.distinctBy { it.start } }
        loadedAt = System.currentTimeMillis()
    }

    /** (nu, straks) voor een zender. */
    fun nowNext(channel: String): Pair<Prog?, Prog?> {
        val list = data[channel] ?: return null to null
        val now = System.currentTimeMillis()
        val i = list.indexOfFirst { it.start <= now && it.stop > now }
        return if (i >= 0) list[i] to list.getOrNull(i + 1) else null to list.firstOrNull { it.start > now }
    }
}
