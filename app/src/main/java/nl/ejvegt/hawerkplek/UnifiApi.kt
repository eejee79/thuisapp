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
    )

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
                uptimeS = if (o.has("uptime")) o.optLong("uptime") else null,
                satisfaction = i("satisfaction"),
            )
        }
    }
}
