package nl.ejvegt.hawerkplek

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Sonarr / Radarr (API v3). Adres en sleutel komen uit de instellingen van de app, nooit uit de code. */
class ArrApi(private val base: () -> String, private val key: () -> String) {

    fun configured() = base().isNotBlank() && key().isNotBlank()

    private fun call(method: String, path: String, body: JSONObject? = null): String {
        val b = base().trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        val conn = URL("$b/api/v3$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 6_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("X-Api-Key", key().trim())
        conn.setRequestProperty("Accept", "application/json")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code == 401) throw IOException("API-sleutel klopt niet")
        if (code !in 200..299) throw IOException("fout $code ${text.take(160)}")
        return text
    }

    fun arr(path: String): JSONArray = JSONArray(call("GET", path).ifBlank { "[]" })
    fun obj(path: String): JSONObject = JSONObject(call("GET", path).ifBlank { "{}" })
    /** Pagina-antwoord (queue, history): de records. */
    fun records(path: String): JSONArray = obj(path).optJSONArray("records") ?: JSONArray()
    fun post(path: String, body: JSONObject): String = call("POST", path, body)

    fun ping(): String = obj("/system/status").optString("version")

    companion object {
        /** Poster-url (liefst de openbare remoteUrl) uit een series/movie-object. */
        fun poster(o: JSONObject?): String? {
            val imgs = o?.optJSONArray("images") ?: return null
            for (i in 0 until imgs.length()) {
                val im = imgs.getJSONObject(i)
                if (im.optString("coverType") == "poster") {
                    im.optString("remoteUrl").takeIf { it.startsWith("http") }?.let { return it }
                }
            }
            return null
        }
    }
}
