package nl.ejvegt.hawerkplek

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * UniFi OS-gateway (UCG Ultra e.d.), lokaal account zonder MFA. Leest de verbonden apparaten
 * (stat/sta): IP, wifi-band, kanaal, verbindingssnelheid, signaal.
 * De gateway gebruikt een eigen (zelfondertekend) certificaat: dat wordt alleen voor dit
 * ene, zelf ingevulde adres in je thuisnetwerk geaccepteerd.
 */
class UnifiApi(private val host: () -> String, private val user: () -> String, private val pass: () -> String) {

    class Client(
        val name: String,
        val ip: String,
        val mac: String,
        val vendor: String,
        val wired: Boolean,
        val band: String?,        // "2,4 GHz" / "5 GHz" / "6 GHz"
        val channel: Int?,
        val ssid: String?,
        val txMbit: Int?,         // verbindingssnelheid van AP naar apparaat
        val rxMbit: Int?,
        val signal: Int?,         // dBm
        val proto: String?,       // "ax", "ac", "n" …
        val network: String?,
        val port: Int?,
        val uptimeS: Long?,
        val satisfaction: Int?,
        /** Huidig verkeer van/naar dit apparaat, bytes per seconde (beide richtingen samen). */
        val rateBps: Double = 0.0,
        /** Switch/gateway waar dit bekabelde apparaat aan hangt (MAC). */
        val swMac: String? = null,
    )

    /**
     * Verkeer per switchpoort, bytes/s (beide richtingen): (MAC switch, poortnummer) → snelheid.
     * Multicast (tv-zenders) wordt hier wél meegeteld, in tegenstelling tot de cijfers per apparaat.
     */
    class Live(val down: Double?, val up: Double?, val ports: Map<Pair<String, Int>, Double>, val tvPorts: Set<Pair<String, Int>>)

    private var tvNetId: String? = null
    private var tvNetIdAt = 0L

    /** Id van het tv-netwerk in UniFi (naam met "tv", of VLAN 7). */
    private fun tvNetworkId(): String? {
        if (tvNetId != null || System.currentTimeMillis() - tvNetIdAt < 300_000) return tvNetId
        tvNetIdAt = System.currentTimeMillis()
        val arr = runCatching { get("/proxy/network/api/s/default/rest/networkconf").optJSONArray("data") }.getOrNull() ?: return null
        tvNetId = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .firstOrNull { it.optInt("vlan", -1) == 7 || it.optString("name").lowercase().let { n -> n == "tv" || n.startsWith("tv ") } }
            ?.optString("_id")?.takeIf { it.isNotBlank() }
        return tvNetId
    }

    /**
     * Eén snelle vraag voor de live-cijfers: internet (gateway wan1/uplink) en verkeer per poort,
     * plus welke poorten in het tv-netwerk staan.
     */
    fun live(): Live {
        fun r(o: JSONObject?, k: String) = o?.optDouble(k, Double.NaN)?.takeIf { !it.isNaN() }
        val devs = get("/proxy/network/api/s/default/stat/device").optJSONArray("data") ?: JSONArray()
        val tvId = tvNetworkId()
        var down: Double? = null; var up: Double? = null
        val ports = HashMap<Pair<String, Int>, Double>()
        val tvPorts = HashSet<Pair<String, Int>>()
        for (i in 0 until devs.length()) {
            val d = devs.optJSONObject(i) ?: continue
            if (down == null && up == null && (d.has("wan1") || d.optString("type") in setOf("ugw", "udm", "uxg", "ucg"))) {
                for (o in listOfNotNull(d.optJSONObject("wan1"), d.optJSONObject("uplink"))) {
                    val dn = r(o, "rx_bytes-r"); val u2 = r(o, "tx_bytes-r")
                    if (dn != null || u2 != null) { down = dn; up = u2; break }
                }
            }
            val mac = d.optString("mac").lowercase()
            val pt = d.optJSONArray("port_table") ?: continue
            for (j in 0 until pt.length()) {
                val p = pt.optJSONObject(j) ?: continue
                val idx = p.optInt("port_idx", -1).takeIf { it >= 0 } ?: continue
                ports[mac to idx] = listOf("tx_bytes-r", "rx_bytes-r").sumOf { k -> p.optDouble(k, 0.0).takeIf { !it.isNaN() } ?: 0.0 }
                val native = p.optString("native_networkconf_id").ifBlank { p.optString("networkconf_id") }
                if (tvId != null && native == tvId && !p.optBoolean("is_uplink", false)) tvPorts.add(mac to idx)
            }
        }
        return Live(down, up, ports, tvPorts)
    }

    fun portRates(): Map<Pair<String, Int>, Double> {
        val arr = get("/proxy/network/api/s/default/stat/device").optJSONArray("data") ?: JSONArray()
        val out = HashMap<Pair<String, Int>, Double>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val mac = d.optString("mac").lowercase()
            val ports = d.optJSONArray("port_table") ?: continue
            for (j in 0 until ports.length()) {
                val p = ports.optJSONObject(j) ?: continue
                val idx = p.optInt("port_idx", -1).takeIf { it >= 0 } ?: continue
                val r = listOf("tx_bytes-r", "rx_bytes-r").sumOf { k -> p.optDouble(k, 0.0).takeIf { !it.isNaN() } ?: 0.0 }
                out[mac to idx] = r
            }
        }
        return out
    }

    /**
     * Internetverbinding nu: (download, upload) in bytes/s. Bij UniFi OS-gateways (UCG/UDM) staan die
     * bij het apparaat zelf (wan1 / uplink); bij oudere systemen in stat/health.
     */
    fun wanRate(): Pair<Double?, Double?> {
        fun r(o: JSONObject?, k: String) = o?.optDouble(k, Double.NaN)?.takeIf { !it.isNaN() }
        val devs = get("/proxy/network/api/s/default/stat/device").optJSONArray("data") ?: JSONArray()
        val gw = (0 until devs.length()).mapNotNull { devs.optJSONObject(it) }
            .firstOrNull { it.has("wan1") || it.optString("type") in setOf("ugw", "udm", "uxg", "ucg") }
        for (o in listOfNotNull(gw?.optJSONObject("wan1"), gw?.optJSONObject("uplink"))) {
            val down = r(o, "rx_bytes-r"); val up = r(o, "tx_bytes-r")
            if (down != null || up != null) return down to up
        }
        val arr = get("/proxy/network/api/s/default/stat/health").optJSONArray("data") ?: JSONArray()
        val wan = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("subsystem") == "wan" }
        return r(wan, "rx_bytes-r") to r(wan, "tx_bytes-r")
    }

    /** Voor foutzoeken: alle "…-r"-snelheden die de gateway nu meldt. */
    fun rateDiagnose(): String {
        val sb = StringBuilder()
        val devs = get("/proxy/network/api/s/default/stat/device").optJSONArray("data") ?: JSONArray()
        for (i in 0 until devs.length()) {
            val d = devs.optJSONObject(i) ?: continue
            sb.append("• ").append(d.optString("name", d.optString("model"))).append(" (").append(d.optString("type")).append(")\n")
            for (k in listOf("wan1", "wan2", "uplink")) d.optJSONObject(k)?.let { o ->
                val rx = o.optDouble("rx_bytes-r", Double.NaN); val tx = o.optDouble("tx_bytes-r", Double.NaN)
                sb.append("   $k: rx ").append(if (rx.isNaN()) "—" else "%.0f".format(rx)).append(" tx ").append(if (tx.isNaN()) "—" else "%.0f".format(tx)).append(" B/s\n")
            }
            val pt = d.optJSONArray("port_table")
            if (pt != null) for (j in 0 until pt.length()) pt.optJSONObject(j)?.let { p ->
                val rt = p.optDouble("rx_bytes-r", 0.0) + p.optDouble("tx_bytes-r", 0.0)
                if (rt > 1000) sb.append("   poort ").append(p.optInt("port_idx")).append(" ").append(p.optString("name")).append(": ").append("%.0f".format(rt)).append(" B/s\n")
            }
        }
        return sb.toString().ifEmpty { "Geen apparaten van UniFi ontvangen" }
    }

    fun configured() = host().isNotBlank() && user().isNotBlank() && pass().isNotBlank()

    private var cookie: String? = null
    private var csrf: String? = null

    private fun base(): String {
        val h = host().trim().trimEnd('/').removePrefix("https://").removePrefix("http://")
        require(isPrivate(h.substringBefore(':'))) { "Alleen een adres in je thuisnetwerk (bijv. 192.168.50.1)" }
        return "https://$h"
    }

    private fun isPrivate(h: String) =
        h.startsWith("192.168.") || h.startsWith("10.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h) ||
            h.endsWith(".local") || h.endsWith(".lan")

    private val trustAll: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), java.security.SecureRandom())
        }
    }

    private fun open(path: String): HttpsURLConnection {
        val c = URL(base() + path).openConnection() as HttpsURLConnection
        c.sslSocketFactory = trustAll.socketFactory
        c.hostnameVerifier = HostnameVerifier { _, _ -> true }
        c.connectTimeout = 6_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    private fun login() {
        val c = open("/api/auth/login")
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use {
            it.write(JSONObject().put("username", user().trim()).put("password", pass()).put("remember", true).toString().toByteArray())
        }
        val code = c.responseCode
        if (code == 401 || code == 403) throw IOException("Inloggen bij UniFi mislukt (lokaal account zonder MFA nodig)")
        if (code == 429) throw IOException("UniFi: te veel inlogpogingen, probeer het over een paar minuten")
        if (code !in 200..299) throw IOException("UniFi-login gaf fout $code")
        cookie = c.headerFields["Set-Cookie"].orEmpty().map { it.substringBefore(';') }.filter { it.startsWith("TOKEN=") }
            .joinToString("; ").ifEmpty { null } ?: throw IOException("UniFi gaf geen sessie terug")
        csrf = c.getHeaderField("X-CSRF-Token") ?: c.getHeaderField("x-updated-csrf-token")
        c.inputStream.close()
    }

    private fun get(path: String, retry: Boolean = true): JSONObject {
        if (cookie == null) login()
        val c = open(path)
        c.setRequestProperty("Cookie", cookie)
        csrf?.let { c.setRequestProperty("X-CSRF-Token", it) }
        val code = c.responseCode
        if (code == 401 && retry) { cookie = null; return get(path, false) }
        if (code !in 200..299) throw IOException("UniFi gaf fout $code")
        c.getHeaderField("x-updated-csrf-token")?.let { csrf = it }
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    }

    /** Alle nu verbonden apparaten. */
    fun clients(): List<Client> {
        val arr = get("/proxy/network/api/s/default/stat/sta").optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            fun s(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
            fun i(k: String) = if (o.has(k)) o.optInt(k) else null
            val wired = o.optBoolean("is_wired", false)
            val band = when (s("radio")) { "ng" -> "2,4 GHz"; "na" -> "5 GHz"; "6e" -> "6 GHz"; else -> null }
            Client(
                name = s("name") ?: s("hostname") ?: s("oui") ?: s("mac") ?: "?",
                ip = s("ip") ?: s("last_ip") ?: "—",
                mac = s("mac") ?: "",
                vendor = s("oui") ?: "",
                wired = wired,
                band = if (wired) null else band,
                channel = if (wired) null else i("channel"),
                ssid = if (wired) null else s("essid"),
                txMbit = if (wired) null else i("tx_rate")?.let { it / 1000 },
                rxMbit = if (wired) null else i("rx_rate")?.let { it / 1000 },
                signal = if (wired) null else i("signal"),
                proto = if (wired) null else s("radio_proto"),
                network = s("network"),
                port = if (wired) i("sw_port") else null,
                swMac = if (wired) s("sw_mac")?.lowercase() else null,
                uptimeS = if (o.has("uptime")) o.optLong("uptime") else null,
                satisfaction = i("satisfaction"),
                rateBps = (if (wired) listOf("wired-rx_bytes-r", "wired-tx_bytes-r") else listOf("rx_bytes-r", "tx_bytes-r"))
                    .sumOf { k -> o.optDouble(k, 0.0).takeIf { !it.isNaN() } ?: 0.0 },
            )
        }
    }
}
