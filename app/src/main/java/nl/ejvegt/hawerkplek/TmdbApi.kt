package nl.ejvegt.hawerkplek

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Films en series opzoeken via TMDB (themoviedb.org): hoes, beschrijving en waar het te zien is. */
class TmdbApi(private val key: () -> String) {

    class Title(
        val id: Int, val type: String, val name: String, val year: String,
        val overview: String, val poster: String?, val rating: Double,
    )

    class Provider(val name: String, val logo: String?, val kind: String)

    companion object {
        const val IMG = "https://image.tmdb.org/t/p/"
    }

    private fun get(path: String): JSONObject {
        val k = key().trim()
        if (k.isEmpty()) throw IOException("Geen TMDB-sleutel ingesteld")
        // Lange sleutel = "API Read Access Token" (Bearer), korte = v3 api_key
        val bearer = k.length > 40
        val sep = if ('?' in path) '&' else '?'
        val url = "https://api.themoviedb.org/3$path" + if (bearer) "" else "${sep}api_key=$k"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 12_000
        conn.setRequestProperty("Accept", "application/json")
        if (bearer) conn.setRequestProperty("Authorization", "Bearer $k")
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code == 401) throw IOException("TMDB-sleutel klopt niet")
        if (code !in 200..299) throw IOException("TMDB gaf fout $code")
        return JSONObject(text)
    }

    private fun titles(o: JSONObject): List<Title> {
        val arr = o.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val r = arr.getJSONObject(i)
            val type = r.optString("media_type").ifEmpty { if (r.has("title")) "movie" else "tv" }
            if (type != "movie" && type != "tv") return@mapNotNull null
            Title(
                r.optInt("id"), type,
                r.optString(if (type == "movie") "title" else "name"),
                r.optString(if (type == "movie") "release_date" else "first_air_date").take(4),
                r.optString("overview"),
                r.optString("poster_path").takeIf { it.startsWith("/") }?.let { "${IMG}w185$it" },
                r.optDouble("vote_average", 0.0),
            )
        }
    }

    fun search(q: String): List<Title> =
        titles(get("/search/multi?language=nl-NL&region=NL&include_adult=false&query=" + URLEncoder.encode(q, "UTF-8")))

    fun trending(): List<Title> = titles(get("/trending/all/week?language=nl-NL"))

    /** Waar is het in Nederland te zien: abonnement, gratis (met reclame), huren of kopen. */
    fun providers(t: Title): List<Provider> {
        val nl = get("/${t.type}/${t.id}/watch/providers").optJSONObject("results")?.optJSONObject("NL") ?: return emptyList()
        val out = ArrayList<Provider>()
        for ((key, kind) in listOf("flatrate" to "abonnement", "free" to "gratis", "ads" to "gratis", "rent" to "huren", "buy" to "kopen")) {
            val arr = nl.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val name = p.optString("provider_name")
                if (out.none { it.name == name }) {
                    out.add(Provider(name, p.optString("logo_path").takeIf { it.startsWith("/") }?.let { "${IMG}w92$it" }, kind))
                }
            }
        }
        return out
    }
}
