package nl.ejvegt.hawerkplek

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Openbare laadpunten in Nederland via het nationale toegangspunt van NDW (DOT-NL, AFIR-gegevens).
 * Gratis, zonder sleutel; max. 1000 punten en 1 vierkante graad per vraag.
 * De API is volgens NDW nog in ontwikkeling: de velden worden daarom ruim gelezen.
 */
object ChargeMap {

    class Station(
        val name: String,
        val address: String,
        val operator: String,
        val lat: Double,
        val lon: Double,
        val distanceM: Double,
        val maxKw: Double?,
        val dc: Boolean,
        val connectors: List<String>,
        val total: Int,
        val free: Int?,          // null = status onbekend
        val price: String?,
    )

    private const val BASE = "https://dotnl.ndw.nu/api/rest/geojson/dynamic-road-status/charge-point-data/v1/features"

    /** Laadpunten rond (lat, lon) binnen ongeveer [radiusKm]; dichtstbijzijnde eerst. */
    fun near(lat: Double, lon: Double, radiusKm: Double): List<Station> {
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)))
        val bbox = "%.6f,%.6f,%.6f,%.6f".format(java.util.Locale.ROOT, lon - dLon, lat - dLat, lon + dLon, lat + dLat)
        val conn = URL("$BASE?bbox=$bbox").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/geo+json, application/json")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        val code = conn.responseCode
        if (code == 429) throw IOException("NDW: even te veel vragen, probeer het zo opnieuw")
        if (code !in 200..299) throw IOException("NDW gaf fout $code")
        val raw = conn.inputStream.let { if (conn.contentEncoding == "gzip") GZIPInputStream(it) else it }
        val text = raw.bufferedReader().use { it.readText() }
        val feats = JSONObject(text).optJSONArray("features") ?: JSONArray()
        val out = mutableListOf<Station>()
        for (i in 0 until feats.length()) {
            val f = feats.optJSONObject(i) ?: continue
            parse(f, lat, lon)?.let { out.add(it) }
        }
        // Zelfde plek (meerdere laadpunten van één locatie) samenvoegen
        return out.groupBy { "%.5f,%.5f".format(java.util.Locale.ROOT, it.lat, it.lon) + it.operator }
            .values.map { g ->
                if (g.size == 1) g[0] else {
                    val f = g.first()
                    val frees = g.mapNotNull { it.free }
                    Station(f.name, f.address, f.operator, f.lat, f.lon, f.distanceM,
                        g.mapNotNull { it.maxKw }.maxOrNull(), g.any { it.dc },
                        g.flatMap { it.connectors }.distinct(), g.sumOf { it.total },
                        if (frees.isEmpty()) null else frees.sum(), g.firstNotNullOfOrNull { it.price })
                }
            }
            .sortedBy { it.distanceM }
    }

    /**
     * België (en elders): Open Charge Map, open database van laadpunten. Gratis API-sleutel nodig.
     * Live vrij/bezet zit hier meestal niet in.
     */
    fun nearOcm(lat: Double, lon: Double, radiusKm: Double, key: String, fastOnly: Boolean = false): List<Station> {
        // Snelladers direct bij Open Charge Map filteren; anders vullen de dichtstbijzijnde gewone palen
        // de hele lijst en blijven er na het filteren maar een paar snelladers over
        val url = "https://api.openchargemap.io/v3/poi/?output=json&compact=false&verbose=false" +
            "&latitude=$lat&longitude=$lon&distance=$radiusKm&distanceunit=KM&maxresults=250" +
            (if (fastOnly) "&minpowerkw=50" else "")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("X-API-Key", key.trim())
        conn.setRequestProperty("User-Agent", "Thuisbasis-app")
        val code = conn.responseCode
        if (code == 401 || code == 403) throw IOException("Open Charge Map: API-sleutel klopt niet")
        if (code !in 200..299) throw IOException("Open Charge Map gaf fout $code")
        val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        val out = mutableListOf<Station>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val a = p.optJSONObject("AddressInfo") ?: continue
            val la = a.optDouble("Latitude", Double.NaN); val lo = a.optDouble("Longitude", Double.NaN)
            if (la.isNaN() || lo.isNaN()) continue
            val conns = p.optJSONArray("Connections") ?: JSONArray()
            var maxKw: Double? = null; var dc = false; var total = 0
            val types = mutableListOf<String>()
            for (j in 0 until conns.length()) {
                val cn = conns.optJSONObject(j) ?: continue
                cn.optDouble("PowerKW", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.let { maxKw = maxOf(maxKw ?: 0.0, it) }
                if (cn.optJSONObject("CurrentType")?.optString("Title").orEmpty().contains("DC", true)) dc = true
                val t = cn.optJSONObject("ConnectionType")?.optString("Title").orEmpty()
                when {
                    "CCS" in t || "Combo" in t -> { types.add("CCS"); dc = true }
                    "CHAdeMO" in t -> { types.add("CHAdeMO"); dc = true }
                    "Type 2" in t || "Mennekes" in t -> types.add("Type 2")
                    "Tesla" in t -> types.add("Tesla")
                }
                total += cn.optInt("Quantity", 1).coerceAtLeast(1)
            }
            if ((maxKw ?: 0.0) >= 50) dc = true
            val street = listOf(a.optString("AddressLine1"), a.optString("Town")).filter { it.isNotBlank() && it != "null" }.joinToString(", ")
            val cost = p.optString("UsageCost").takeIf { it.isNotBlank() && it != "null" }?.take(40)
            out.add(Station(
                a.optString("Title").takeIf { it != "null" }.orEmpty(), street,
                p.optJSONObject("OperatorInfo")?.optString("Title")?.takeIf { it != "null" && !it.startsWith("(") }.orEmpty(),
                la, lo, distance(lat, lon, la, lo), maxKw, dc, types.distinct(), total.coerceAtLeast(1), null, cost,
            ))
        }
        return out.sortedBy { it.distanceM }
    }

    /** Alle waarden in een object plat: "evses.0.status" → "AVAILABLE". */
    private fun flatten(o: Any?, prefix: String, out: MutableList<Pair<String, Any>>) {
        when (o) {
            is JSONObject -> o.keys().forEach { k -> flatten(o.opt(k), if (prefix.isEmpty()) k else "$prefix.$k", out) }
            is JSONArray -> for (i in 0 until o.length()) flatten(o.opt(i), "$prefix.$i", out)
            null, JSONObject.NULL -> {}
            else -> out.add(prefix.lowercase() to o)
        }
    }

    private fun parse(f: JSONObject, lat0: Double, lon0: Double): Station? {
        val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val lon = c.optDouble(0, Double.NaN); val lat = c.optDouble(1, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        val flat = mutableListOf<Pair<String, Any>>()
        flatten(f.optJSONObject("properties"), "", flat)
        fun last(k: String) = k.substringAfterLast('.')
        fun str(vararg keys: String) = flat.firstOrNull { (k, v) -> last(k) in keys && v is String && v.isNotBlank() }?.second as String?

        val name = str("name", "locationname", "location_name", "title") ?: ""
        val street = str("address", "street", "streetname", "adres")
        val city = str("city", "town", "plaats", "woonplaats")
        val operator = str("operator", "operatorname", "operator_name", "party_id", "cpo", "owner") ?: ""

        // Vermogen: W of kW; ook uit spanning × stroom als dat er staat
        val powers = flat.filter { (k, v) -> v is Number && Regex("power|vermogen").containsMatchIn(last(k)) }
            .map { (it.second as Number).toDouble() }
            .map { if (it > 1000) it / 1000 else it }
        val maxKw = powers.maxOrNull()?.takeIf { it > 0 }
        val types = flat.filter { (k, v) -> v is String && last(k) in setOf("standard", "connector_type", "connectortype", "type", "plug") }
            .map { it.second as String }
        val connectors = types.map { pretty(it) }.filter { it.isNotEmpty() }.distinct()
        val formats = flat.filter { (k, _) -> last(k) in setOf("power_type", "powertype", "current_type", "format") }.map { it.second.toString().uppercase() }
        val dc = formats.any { "DC" in it } || connectors.any { it == "CCS" || it == "CHAdeMO" } || (maxKw ?: 0.0) >= 50

        val statuses = flat.filter { (k, v) -> v is String && last(k) in setOf("status", "availability", "evse_status") }
            .map { (it.second as String).uppercase() }
        val total = statuses.size.coerceAtLeast(
            flat.count { (k, _) -> Regex("""(^|\.)evses\.\d+\.uid$""").containsMatchIn(k) }.coerceAtLeast(1))
        val free = if (statuses.isEmpty()) null else statuses.count { it == "AVAILABLE" || it == "FREE" || it == "VRIJ" }

        val price = flat.firstOrNull { (k, v) -> v is Number && Regex("price|tarief|kwh_price").containsMatchIn(last(k)) }
            ?.let { (k, v) -> val p = (v as Number).toDouble(); if (p in 0.05..2.0) "€ %.2f/kWh".format(java.util.Locale("nl", "NL"), p) else null }

        val d = distance(lat0, lon0, lat, lon)
        return Station(name, listOfNotNull(street, city).joinToString(", "), operator, lat, lon, d, maxKw, dc, connectors, total, free, price)
    }

    private fun pretty(t: String): String {
        val u = t.uppercase()
        return when {
            "COMBO" in u || "CCS" in u -> "CCS"
            "CHADEMO" in u -> "CHAdeMO"
            "62196_T2" in u || "TYPE2" in u || "TYPE_2" in u || u == "T2" -> "Type 2"
            "TESLA" in u -> "Tesla"
            "DOMESTIC" in u || "SCHUKO" in u -> "Stopcontact"
            else -> ""
        }
    }

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        val dy = (lat2 - lat1) * 110_540.0
        return sqrt(dx * dx + dy * dy)
    }
}
