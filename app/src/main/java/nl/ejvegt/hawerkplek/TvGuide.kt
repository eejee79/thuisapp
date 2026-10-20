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
    /** Eigen archief: programma's van de afgelopen dagen blijven bewaard, ook als de bron ze niet meer levert. */
    private const val KEEP_MS = 7 * 24 * 3_600_000L   // zoals terugkijken bij KPN: 7 dagen
    @Volatile var histDir: File? = null

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
        val claimed = HashSet<String>()   // elke app-zender krijgt maar één bron (niet NPO1 én NPO1 HD)
        fun claim(id: String, name: String) {
            if (idToName.containsKey(id) || name in claimed) return
            idToName[id] = name; claimed.add(name)
        }
        val out = HashMap<String, MutableList<Prog>>()
        val from = System.currentTimeMillis() - KEEP_MS
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
                            chId?.let { id -> wanted[norm(id)]?.let { claim(id, it) } }
                        }
                        "display-name" -> if (chId != null) {
                            val name = p.nextText()
                            wanted[norm(name)]?.let { claim(chId!!, it) }
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
        data = (histDir?.let { runCatching { mergeHistory(it, out) }.getOrNull() }
            ?: out.mapValues { (_, v) -> v.sortedBy { it.start }.distinctBy { it.start } })
            .mapValues { (_, v) -> clean(v) }
        loadedAt = System.currentTimeMillis()
    }

    private fun esc(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    /** Archief inlezen, vullen met de nieuwe gids (die wint vanaf zijn eerste programma) en terugschrijven. */
    private fun mergeHistory(dir: File, fresh: Map<String, List<Prog>>): Map<String, List<Prog>> {
        val f = File(dir, "epg_history.tsv")
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val all = HashMap<String, MutableList<Prog>>()
        if (f.exists()) f.forEachLine { line ->
            val p = line.split('\t')
            if (p.size < 4) return@forEachLine
            val st = p[1].toLongOrNull() ?: return@forEachLine
            val sp = p[2].toLongOrNull() ?: return@forEachLine
            if (sp < cutoff) return@forEachLine
            all.getOrPut(p[0]) { mutableListOf() }.add(Prog(st, sp, p[3], p.getOrNull(4)?.takeIf { it.isNotEmpty() }))
        }
        for ((ch, l) in fresh) {
            if (l.isEmpty()) continue
            val first = l.minOf { it.start }
            val keep = all[ch]?.filter { it.start < first }.orEmpty()
            all[ch] = (keep + l).toMutableList()
        }
        val result = all.mapValues { (_, v) -> v.sortedBy { it.start }.distinctBy { it.start } }
        val tmp = File(dir, "epg_history.tmp")
        tmp.bufferedWriter().use { w ->
            for ((ch, l) in result) for (p in l) {
                w.write(esc(ch)); w.write("\t"); w.write(p.start.toString()); w.write("\t"); w.write(p.stop.toString())
                w.write("\t"); w.write(esc(p.title)); w.write("\t"); w.write(esc(p.desc?.take(300) ?: "")); w.write("\n")
            }
        }
        tmp.renameTo(f)
        return result
    }

    /** Overlappende (dubbele) programma's eruit: alleen wat na het einde van het vorige begint. */
    private fun clean(sorted: List<Prog>): List<Prog> {
        val out = ArrayList<Prog>(sorted.size)
        for (p in sorted) {
            val last = out.lastOrNull()
            if (last == null || p.start >= last.stop - 60_000L) out.add(p)
        }
        return out
    }

    /** Programma's van een zender binnen [from, to). */
    fun range(channel: String, from: Long, to: Long): List<Prog> =
        data[channel]?.filter { it.stop > from && it.start < to }.orEmpty()

    /** (nu, straks) voor een zender. */
    fun nowNext(channel: String, at: Long = System.currentTimeMillis()): Pair<Prog?, Prog?> {
        val list = data[channel] ?: return null to null
        val now = at
        val i = list.indexOfFirst { it.start <= now && it.stop > now }
        return if (i >= 0) list[i] to list.getOrNull(i + 1) else null to list.firstOrNull { it.start > now }
    }
}
