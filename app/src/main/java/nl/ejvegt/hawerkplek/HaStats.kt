package nl.ejvegt.hawerkplek

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Langetermijnstatistieken van Home Assistant (zoals het Energie-dashboard ze gebruikt), via de websocket-API.
 * Eén verbinding per opvraging; blokkeert, dus altijd in een achtergrondthread.
 */
class HaStats(private val baseUrl: String, private val token: String) {

    class Point(val start: Long, val value: Double)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** Stuurt de berichten na het inloggen en geeft de antwoorden (op id) terug. */
    private fun session(requests: List<JSONObject>): Map<Int, JSONObject> {
        val ws = baseUrl.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/websocket"
        val inbox = LinkedBlockingQueue<Any>()
        val socket = client.newWebSocket(Request.Builder().url(ws).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { inbox.put(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { inbox.put(t) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { inbox.put(IOException("verbinding gesloten: $reason")) }
        })
        try {
            val out = HashMap<Int, JSONObject>()
            val deadline = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < deadline) {
                val m = inbox.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: break
                if (m is Throwable) throw IOException(m.message ?: "websocket-fout", m)
                val o = JSONObject(m as String)
                when (o.optString("type")) {
                    "auth_required" -> socket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                    "auth_invalid" -> throw IOException("Token ongeldig")
                    "auth_ok" -> requests.forEach { socket.send(it.toString()) }
                    "result" -> {
                        if (!o.optBoolean("success")) throw IOException(o.optJSONObject("error")?.optString("message") ?: "opvragen mislukt")
                        out[o.optInt("id")] = o
                        if (out.size == requests.size) return out
                    }
                }
            }
            throw IOException("Geen antwoord van Home Assistant")
        } finally {
            socket.close(1000, null)
        }
    }

    /** Alle statistieken met een totaal (energie, gas) en met een gemiddelde (prijs): id → eenheid. */
    fun listIds(): Map<String, String> {
        val r = session(listOf(JSONObject().put("id", 1).put("type", "recorder/list_statistic_ids")))
        val arr = r[1]?.optJSONArray("result") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .associate { it.optString("statistic_id") to it.optString("statistics_unit_of_measurement") }
    }

    /**
     * Per periode ("hour", "day", "month") het verbruik (change) of het gemiddelde (mean) van elke statistiek.
     * Tijden in ms; antwoord: id → punten.
     */
    fun during(ids: List<String>, from: Long, to: Long, period: String, type: String): Map<String, List<Point>> {
        if (ids.isEmpty()) return emptyMap()
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val req = JSONObject().put("id", 1).put("type", "recorder/statistics_during_period")
            .put("start_time", iso.format(java.util.Date(from))).put("end_time", iso.format(java.util.Date(to)))
            .put("statistic_ids", JSONArray(ids)).put("period", period).put("types", JSONArray().put(type))
        val res = session(listOf(req))[1]?.optJSONObject("result") ?: return emptyMap()
        val out = HashMap<String, List<Point>>()
        for (id in res.keys()) {
            val a = res.getJSONArray(id)
            out[id] = (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { p ->
                val v = p.optDouble(type, Double.NaN)
                if (v.isNaN()) null else Point(p.optLong("start"), v)
            }
        }
        return out
    }
}
