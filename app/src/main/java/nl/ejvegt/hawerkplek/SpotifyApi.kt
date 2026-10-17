package nl.ejvegt.hawerkplek

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Rechtstreeks praten met de Spotify Web API: inloggen (PKCE, zonder geheime sleutel),
 * zoeken, apparaten opvragen en afspelen op een gekozen apparaat (Spotify Connect).
 */
class SpotifyApi(private val context: Context) {

    companion object {
        const val REDIRECT_URI = "thuisbasis://spotify-callback"
        private const val PREFS = "ha_settings"
        const val KEY_CLIENT_ID = "sp_client_id"
        private const val KEY_ACCESS = "sp_access"
        private const val KEY_REFRESH = "sp_refresh"
        private const val KEY_EXPIRY = "sp_expiry"
        private const val KEY_VERIFIER = "sp_verifier"
        const val KEY_LAST_DEVICE = "sp_last_device"
        const val KEY_BLOCKED_UNTIL = "sp_blocked_until"
        private const val SCOPES = "user-read-playback-state user-modify-playback-state " +
            "user-read-currently-playing playlist-read-private user-library-read user-read-private user-read-recently-played"
    }

    class Item(
        val name: String, val sub: String, val uri: String, val type: String,
        val date: String = "", val image: String? = null,
    )
    class Device(val id: String, val name: String, val type: String, val active: Boolean)
    class ApiException(val code: Int, message: String) : IOException(message)

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clientId(): String = prefs().getString(KEY_CLIENT_ID, "")!!.trim()
    fun isLoggedIn(): Boolean = prefs().getString(KEY_REFRESH, "")!!.isNotEmpty()

    fun logout() {
        prefs().edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRY).apply()
    }

    // ---------- Inloggen ----------

    /** Adres van de Spotify-inlogpagina; bewaart de PKCE-sleutel voor de terugkeer. */
    fun authorizeUrl(): String {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = b64(bytes)
        prefs().edit().putString(KEY_VERIFIER, verifier).apply()
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPES)
            .build().toString()
    }

    /** Na het inloggen: de ontvangen code inwisselen voor tokens. */
    fun exchangeCode(code: String) {
        val verifier = prefs().getString(KEY_VERIFIER, "")!!
        tokenRequest(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to clientId(),
            "code_verifier" to verifier,
        )
        // Nieuwe login (bijv. met een nieuwe Client ID): oude afremming vergeten
        prefs().edit().remove(KEY_BLOCKED_UNTIL).apply()
    }

    private fun b64(b: ByteArray) =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun tokenRequest(vararg form: Pair<String, String>) {
        val body = form.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val conn = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optString("error_description") }.getOrNull()
            throw ApiException(code, msg?.ifEmpty { null } ?: "inloggen mislukt ($code)")
        }
        val json = JSONObject(text)
        val e = prefs().edit()
            .putString(KEY_ACCESS, json.getString("access_token"))
            .putLong(KEY_EXPIRY, System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000 - 60_000)
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { e.putString(KEY_REFRESH, it) }
        e.apply()
    }

    @Synchronized
    private fun accessToken(): String {
        val p = prefs()
        if (System.currentTimeMillis() < p.getLong(KEY_EXPIRY, 0)) {
            p.getString(KEY_ACCESS, null)?.let { return it }
        }
        val refresh = p.getString(KEY_REFRESH, "")!!
        if (refresh.isEmpty()) throw ApiException(401, "Niet ingelogd bij Spotify (⚙ → API's → Spotify)")
        tokenRequest("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId())
        return p.getString(KEY_ACCESS, "")!!
    }

    // ---------- Web API ----------

    /** Seconden dat Spotify de app nog afremt (0 = vrij). Tijdens afremmen niets vragen: dat verlengt het. */
    fun blockedFor(): Long = maxOf(0L, (prefs().getLong(KEY_BLOCKED_UNTIL, 0) - System.currentTimeMillis()) / 1000)

    private fun waitText(wait: Long) = when {
        wait <= 0 -> "even"
        wait < 120 -> "$wait seconden"
        wait < 7200 -> "${wait / 60} minuten"
        else -> "${wait / 3600} uur"
    }

    private fun call(method: String, path: String, body: String? = null): String {
        blockedFor().takeIf { it > 0 }?.let {
            throw ApiException(429, "Spotify remt de app nog ${waitText(it)} af (te veel verzoeken).")
        }
        val conn = URL("https://api.spotify.com/v1$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer ${accessToken()}")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        } else if (method == "PUT" || method == "POST") {
            conn.setFixedLengthStreamingMode(0)
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code == 429) {
            // Spotify remt af: alle apps met dezelfde Client ID tellen samen
            val wait = conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 30L
            prefs().edit().putLong(KEY_BLOCKED_UNTIL, System.currentTimeMillis() + wait * 1000).apply()
            throw ApiException(429, "Spotify remt tijdelijk af (te veel verzoeken). Probeer het over ${waitText(wait)} opnieuw.")
        }
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
            throw ApiException(code, msg?.ifEmpty { null } ?: "Spotify gaf fout $code")
        }
        return text
    }

    /** Kleinste afbeelding van minstens 64 px (lijstjes hebben geen grote nodig). */
    private fun img(o: JSONObject?): String? {
        val arr = o?.optJSONArray("images") ?: return null
        var best: String? = null
        var bestW = Int.MAX_VALUE
        for (i in 0 until arr.length()) {
            val im = arr.optJSONObject(i) ?: continue
            val w = im.optInt("width", 300).let { if (it == 0) 300 else it }
            if (w >= 64 && w < bestW) { bestW = w; best = im.optString("url") }
        }
        return best ?: arr.optJSONObject(0)?.optString("url")
    }

    private fun names(arr: JSONArray?): String =
        (0 until (arr?.length() ?: 0)).joinToString(", ") { arr!!.getJSONObject(it).optString("name") }

    /** Zoekt artiesten, nummers, albums en playlists. */
    fun search(query: String): List<Item> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = JSONObject(call("GET", "/search?q=$q&type=artist,track,album,playlist&limit=6"))
        val out = mutableListOf<Item>()
        fun each(key: String, f: (JSONObject) -> Item?) {
            val items = json.optJSONObject(key)?.optJSONArray("items") ?: return
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue   // Spotify geeft soms lege plekken terug
                f(o)?.let { out.add(it) }
            }
        }
        each("artists") { Item(it.optString("name"), "Artiest", it.optString("uri"), "artist", image = img(it)) }
        each("tracks") {
            Item(it.optString("name"), names(it.optJSONArray("artists")), it.optString("uri"), "track",
                image = img(it.optJSONObject("album")))
        }
        each("albums") {
            Item(it.optString("name"), "Album · " + names(it.optJSONArray("artists")), it.optString("uri"), "album",
                image = img(it))
        }
        each("playlists") {
            Item(
                it.optString("name"),
                "Playlist · " + (it.optJSONObject("owner")?.optString("display_name") ?: ""),
                it.optString("uri"), "playlist", image = img(it),
            )
        }
        return out
    }

    /**
     * Albums en singles/EP's van een artiest, elk nieuw → oud. Spotify geeft ze in pagina's;
     * die halen we allemaal op (met een maximum).
     */
    fun artistReleases(artistUri: String): List<Item> {
        val id = artistUri.substringAfterLast(':')
        val out = mutableListOf<Item>()
        var path: String? = "/artists/$id/albums?include_groups=album,single&limit=10"
        var pages = 0
        while (path != null && pages < 15) {
            val json = JSONObject(call("GET", path))
            val items = json.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val date = o.optString("release_date")
                val kind = o.optString("album_group").ifEmpty { o.optString("album_type") }
                val type = if (kind == "album") "album" else "single"
                val label = if (type == "album") "Album" else if (o.optInt("total_tracks") > 3) "EP" else "Single"
                out.add(Item(o.optString("name"), "${date.take(4)} · $label", o.optString("uri"), type, date, img(o)))
            }
            path = json.optString("next").takeIf { it.startsWith("https://api.spotify.com/v1") }
                ?.removePrefix("https://api.spotify.com/v1")
            pages++
        }
        // Zelfde titel meerdere keren (bijv. deluxe/andere regio): alleen de nieuwste houden
        return out.sortedByDescending { it.date }.distinctBy { it.type + it.name.lowercase() }
    }

    /** Playlists rond een artiest (bijv. "This Is …"). */
    fun artistPlaylists(name: String): List<Item> {
        val q = URLEncoder.encode(name, "UTF-8")
        val items = JSONObject(call("GET", "/search?q=$q&type=playlist&limit=10"))
            .optJSONObject("playlists")?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            Item(
                o.optString("name"),
                "Playlist · " + (o.optJSONObject("owner")?.optString("display_name") ?: ""),
                o.optString("uri"), "playlist", image = img(o),
            )
        }
    }

    /**
     * Recent beluisterd: de laatste [maxArtists] verschillende artiesten en [maxAlbums] albums.
     * (Vraagt de toestemming "user-read-recently-played"; zonder die krijg je een 403.)
     */
    fun recent(maxArtists: Int = 10, maxAlbums: Int = 5): Pair<List<Item>, List<Item>> {
        val items = JSONObject(call("GET", "/me/player/recently-played?limit=50")).optJSONArray("items") ?: JSONArray()
        val artists = LinkedHashMap<String, Item>()
        val albums = LinkedHashMap<String, Item>()
        for (i in 0 until items.length()) {
            val track = items.optJSONObject(i)?.optJSONObject("track") ?: continue
            val album = track.optJSONObject("album")
            val albumImg = img(album)
            val a = track.optJSONArray("artists")?.optJSONObject(0)
            if (a != null && artists.size < maxArtists && !artists.containsKey(a.optString("uri"))) {
                artists[a.optString("uri")] = Item(a.optString("name"), "Artiest", a.optString("uri"), "artist", image = albumImg)
            }
            if (album != null && album.optString("album_type") == "album" && albums.size < maxAlbums &&
                !albums.containsKey(album.optString("uri"))
            ) {
                albums[album.optString("uri")] = Item(
                    album.optString("name"), "Album · " + names(album.optJSONArray("artists")),
                    album.optString("uri"), "album", album.optString("release_date"), albumImg,
                )
            }
        }
        // Echte artiestfoto's ophalen (lukt dat niet, dan blijft het albumhoesje staan)
        val withPhotos = runCatching {
            val ids = artists.keys.joinToString(",") { it.substringAfterLast(':') }
            val arr = JSONObject(call("GET", "/artists?ids=$ids")).optJSONArray("artists") ?: JSONArray()
            val photos = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .associate { it.optString("uri") to img(it) }
            artists.values.map { Item(it.name, it.sub, it.uri, it.type, image = photos[it.uri] ?: it.image) }
        }.getOrDefault(artists.values.toList())
        return withPhotos to albums.values.toList()
    }

    fun devices(): List<Device> {
        val arr = JSONObject(call("GET", "/me/player/devices")).optJSONArray("devices") ?: JSONArray()
        return (0 until arr.length()).map {
            val d = arr.getJSONObject(it)
            Device(d.optString("id"), d.optString("name"), d.optString("type"), d.optBoolean("is_active"))
        }.filter { it.id.isNotEmpty() }
    }

    /** Wachtrij van wat er nu speelt: (titel, artiest, hoes). */
    fun queue(): List<Triple<String, String, String?>> {
        val arr = JSONObject(call("GET", "/me/player/queue")).optJSONArray("queue") ?: JSONArray()
        return (0 until minOf(arr.length(), 30)).map { arr.getJSONObject(it) }.map { t ->
            val artists = t.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } }
                ?: t.optJSONObject("show")?.optString("name").orEmpty()
            Triple(t.optString("name"), artists, img(t.optJSONObject("album")))
        }
    }

    /** Afspelen op een apparaat: een nummer direct, de rest (artiest/album/playlist) als context. */
    fun play(deviceId: String, item: Item) {
        val body = if (item.type == "track") JSONObject().put("uris", JSONArray().put(item.uri))
        else JSONObject().put("context_uri", item.uri)
        val path = "/me/player/play?device_id=" + URLEncoder.encode(deviceId, "UTF-8")
        try {
            call("PUT", path, body.toString())
        } catch (e: ApiException) {
            // Slapend apparaat (bijv. een Chromecast/Nest): eerst "wakker maken" door het afspelen
            // ernaartoe over te zetten, even wachten, en dan nogmaals
            if (e.code !in listOf(404, 502, 503)) throw e
            runCatching {
                call("PUT", "/me/player", JSONObject().put("device_ids", JSONArray().put(deviceId)).put("play", false).toString())
            }
            Thread.sleep(2_000)
            call("PUT", path, body.toString())
        }
        prefs().edit().putString(KEY_LAST_DEVICE, deviceId).apply()
    }
}
