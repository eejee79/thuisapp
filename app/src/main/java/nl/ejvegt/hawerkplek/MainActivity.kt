package nl.ejvegt.hawerkplek

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.time.Instant
import java.time.OffsetDateTime
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        /** De schakelaars op het Home Assistant-tabblad, met de exacte lampen (entity-ID's) per groep. */
        val LIGHT_GROUPS = listOf(
            "Eettafel" to listOf(
                "light.signify_netherlands_b_v_lwo001_light",    // Eettafel1
                "light.signify_netherlands_b_v_lwo001_light_2",  // Eettafel2
                "light.signify_netherlands_b_v_lwv001_light",    // Eettafel3
                "light.signify_netherlands_b_v_lwv001_light_2",  // Eettafel4
            ),
            "Werkplek" to listOf(
                "light.werkplek1_light",
                "light.werkplek2_light_2",
                "light.werkplek3_light_3",
            ),
            "Slaapkamer" to listOf(
                "light.slaapkamer1_light",
                "light.slaapkamer2_light_2",
            ),
            "Woonkamer" to listOf(
                "light.dressoir_light",
                "light.hanglamp1_light_2",
                "light.hanglamp2_light_3",
                "light.hanglamp3_light_4",
                "light.tv_lamp1_light",
                "light.tv_lamp2_light",
            ),
        )

        /** Dashboard: entiteiten in Home Assistant. */
        const val DASH_THERMOSTAT = "climate.tstat_d4b26c_t6_thermostat"
        const val DASH_HEATER = "climate.hombli_smart_convection_heater"
        const val DASH_RECEIVER = "media_player.denon_avc_x3800h"
        const val DASH_POWER = "sensor.p1_meter_3c39e72f3c6a_active_power"
        const val DASH_PRICE = "sensor.zonneplan_current_electricity_tariff"
        const val DASH_CHARGE_LIMIT = "number.skoda_elroq_charge_limit"
        const val DASH_CAR_BATTERY = "sensor.skoda_elroq_battery_percentage"

        /** Eigen bitcoinsaldo voor de cryptokaart. */
        const val BTC_AMOUNT = 0.00945966

        /** Interne nummers van de tabbladen. */
        const val TAB_DASH = 3

        /** De Proxmox VM's/containers (VMID) die op het Proxmox-tabblad staan. */
        val PVE_VMIDS = listOf(103)

        /** Hoe vaak het zichtbare tabblad ververst wordt. */
        const val POLL_INTERVAL_MS = 3_000L

        private const val PREFS = "ha_settings"
        private const val KEY_URL = "url"
        private const val KEY_URL_LOCAL = "url_local"
        private const val KEY_TOKEN = "token"
        private const val KEY_PVE_URL = "pve_url"
        private const val KEY_PVE_TOKEN_ID = "pve_token_id"
        private const val KEY_PVE_SECRET = "pve_secret"
        private const val KEY_PVE_PIN = "pve_cert_pin"
        private const val KEY_FRITZ_URL = "fritz_url"
        private const val KEY_FRITZ_USER = "fritz_user"
        private const val KEY_FRITZ_PASS = "fritz_pass"
        private const val KEY_FRITZ_PIN = "fritz_cert_pin"
        private const val KEY_TAB = "tab"

        /** Fritzbox niet elke 3 s bevragen; 15 s is ruim genoeg. */
        const val FRITZ_REFRESH_MS = 15_000L

        private val NL: Locale = Locale.forLanguageTag("nl-NL")
        private val GREEN = 0xFF4CAF50.toInt()
        private val AMBER = 0xFFFFB300.toInt()
        private val RED = 0xFFE53935.toInt()
        private val GREY = 0xFF757575.toInt()
    }

    private lateinit var haPanel: View
    private lateinit var pvePanel: View
    private lateinit var settingsPanel: View
    private lateinit var tabHa: View
    private lateinit var tabPve: View
    private lateinit var statusText: TextView
    private lateinit var groupList: LinearLayout
    private lateinit var vmList: LinearLayout
    private lateinit var pveStatus: TextView
    private lateinit var urlInput: EditText
    private lateinit var localUrlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var pveUrlInput: EditText
    private lateinit var pveTokenIdInput: EditText
    private lateinit var pveSecretInput: EditText
    private lateinit var fritzPanel: View
    private lateinit var tabFritz: View
    private lateinit var fritzList: LinearLayout
    private lateinit var fritzStatus: TextView
    private lateinit var fritzUrlInput: EditText
    private lateinit var fritzUserInput: EditText
    private lateinit var fritzPassInput: EditText
    private lateinit var dashPanel: View
    private lateinit var tabDash: View
    private lateinit var dashList: LinearLayout
    private lateinit var dashStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var currentTab = 0

    @Volatile private var haBusy = false
    @Volatile private var pveBusy = false

    private val poller = object : Runnable {
        override fun run() {
            refreshCurrentTab()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        haPanel = findViewById(R.id.haPanel)
        pvePanel = findViewById(R.id.pvePanel)
        settingsPanel = findViewById(R.id.settingsPanel)
        tabHa = findViewById(R.id.tabHa)
        tabPve = findViewById(R.id.tabPve)
        statusText = findViewById(R.id.statusText)
        groupList = findViewById(R.id.groupList)
        vmList = findViewById(R.id.vmList)
        pveStatus = findViewById(R.id.pveStatus)
        urlInput = findViewById(R.id.urlInput)
        localUrlInput = findViewById(R.id.localUrlInput)
        tokenInput = findViewById(R.id.tokenInput)
        pveUrlInput = findViewById(R.id.pveUrlInput)
        pveTokenIdInput = findViewById(R.id.pveTokenIdInput)
        pveSecretInput = findViewById(R.id.pveSecretInput)
        fritzPanel = findViewById(R.id.fritzPanel)
        tabFritz = findViewById(R.id.tabFritz)
        fritzList = findViewById(R.id.fritzList)
        fritzStatus = findViewById(R.id.fritzStatus)
        fritzUrlInput = findViewById(R.id.fritzUrlInput)
        fritzUserInput = findViewById(R.id.fritzUserInput)
        fritzPassInput = findViewById(R.id.fritzPassInput)
        dashPanel = findViewById(R.id.dashPanel)
        tabDash = findViewById(R.id.tabDash)
        dashList = findViewById(R.id.dashList)
        dashStatus = findViewById(R.id.dashStatus)

        // Android 15+: app loopt door achter status- en navigatiebalk; ruimte vrijhouden
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(0, i.top, 0, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }

        tabHa.setOnClickListener { selectTab(0) }
        tabPve.setOnClickListener { selectTab(1) }
        tabFritz.setOnClickListener { selectTab(2) }
        tabDash.setOnClickListener { selectTab(TAB_DASH) }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings(true) }
        findViewById<Button>(R.id.saveButton).setOnClickListener { saveSettings() }

        buildFritzRows()
        buildDashboard()
        buildGroupCards()
        selectTab(prefs().getInt(KEY_TAB, 0))
    }

    override fun onResume() {
        super.onResume()
        handler.post(poller)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settingsPanel.visibility == View.VISIBLE) {
            showSettings(false)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ================= Tabbladen =================

    private fun selectTab(tab: Int) {
        currentTab = tab
        settingsPanel.visibility = View.GONE
        val panels = listOf(haPanel, pvePanel, fritzPanel, dashPanel)
        val tabs = listOf(tabHa, tabPve, tabFritz, tabDash)
        panels.forEachIndexed { i, p -> p.visibility = if (i == tab) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, t ->
            t.alpha = if (i == tab) 1f else 0.35f
            t.setBackgroundColor(if (i == tab) 0xFF242424.toInt() else Color.TRANSPARENT)
        }
        prefs().edit().putInt(KEY_TAB, tab).apply()
        refreshCurrentTab(force = true)
    }

    private fun refreshCurrentTab(force: Boolean = false) {
        when (currentTab) {
            0 -> refreshLights()
            1 -> refreshPve()
            TAB_DASH -> refreshDashboard()
            else -> if (force || System.currentTimeMillis() - lastFritzRefresh > FRITZ_REFRESH_MS) refreshFritz()
        }
    }

    // ================= Instellingen =================

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Waarde uit de app-instellingen; leeg = ingebouwde waarde uit local.properties. */
    private fun setting(key: String, builtIn: String) =
        prefs().getString(key, "")!!.trim().ifEmpty { builtIn.trim() }

    private fun haUrl() = setting(KEY_URL, BuildConfig.HA_URL).trimEnd('/')
    private fun haLocalUrl() = setting(KEY_URL_LOCAL, BuildConfig.HA_URL_LOCAL).trimEnd('/')
    private fun haToken() = setting(KEY_TOKEN, BuildConfig.HA_TOKEN)
    private fun pveUrl() = setting(KEY_PVE_URL, BuildConfig.PVE_URL).trimEnd('/')
    private fun pveTokenId() = setting(KEY_PVE_TOKEN_ID, BuildConfig.PVE_TOKEN_ID)
    private fun pveSecret() = setting(KEY_PVE_SECRET, BuildConfig.PVE_SECRET)
    private fun fritzUrl() = setting(KEY_FRITZ_URL, BuildConfig.FRITZ_URL).trimEnd('/')
    private fun fritzUser() = setting(KEY_FRITZ_USER, BuildConfig.FRITZ_USER)
    private fun fritzPass() = setting(KEY_FRITZ_PASS, BuildConfig.FRITZ_PASS)

    private fun showSettings(show: Boolean) {
        if (show) {
            urlInput.setText(haUrl())
            localUrlInput.setText(haLocalUrl())
            tokenInput.setText(haToken())
            pveUrlInput.setText(pveUrl())
            pveTokenIdInput.setText(pveTokenId())
            pveSecretInput.setText(pveSecret())
            fritzUrlInput.setText(fritzUrl())
            fritzUserInput.setText(fritzUser())
            fritzPassInput.setText(fritzPass())
        }
        settingsPanel.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun saveSettings() {
        prefs().edit()
            .putString(KEY_URL, urlInput.text.toString().trim().trimEnd('/'))
            .putString(KEY_URL_LOCAL, localUrlInput.text.toString().trim().trimEnd('/'))
            .putString(KEY_TOKEN, tokenInput.text.toString().trim())
            .putString(KEY_PVE_URL, pveUrlInput.text.toString().trim().trimEnd('/'))
            .putString(KEY_PVE_TOKEN_ID, pveTokenIdInput.text.toString().trim())
            .putString(KEY_PVE_SECRET, pveSecretInput.text.toString().trim())
            .putString(KEY_FRITZ_URL, fritzUrlInput.text.toString().trim().trimEnd('/'))
            .putString(KEY_FRITZ_USER, fritzUserInput.text.toString().trim())
            .putString(KEY_FRITZ_PASS, fritzPassInput.text.toString())
            // Opslaan = zelfondertekende certificaten opnieuw leren (bijv. na vernieuwen)
            .remove(KEY_PVE_PIN)
            .remove(KEY_FRITZ_PIN)
            .apply()
        vmInfo.clear()
        pveNodes.clear()
        haActiveUntil = 0L
        fritzPossibleChannels.clear()
        showSettings(false)
        refreshCurrentTab(force = true)
    }

    // ================= Home Assistant: schakelaars per ruimte =================
    // Elke schakelaar bedient een vaste lijst lampen (LIGHT_GROUPS bovenaan).

    private class HaLight(val id: String, val name: String, val state: String)
    private class GroupCard(val root: LinearLayout, val sub: TextView, val sw: Switch)

    private val groupCards = mutableListOf<GroupCard>()
    private val groupMembers = MutableList(LIGHT_GROUPS.size) { emptyList<HaLight>() }
    private var updatingGroupSwitches = false

    private fun buildGroupCards() {
        LIGHT_GROUPS.forEachIndexed { index, (title, _) ->
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(88)
                setPadding(dp(20), dp(14), dp(20), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val name = TextView(this).apply {
                text = title
                textSize = 21f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }
            val sub = TextView(this).apply {
                text = "Laden…"
                textSize = 14f
                setTextColor(0xFFB0B0B0.toInt())
            }
            val sw = Switch(this).apply {
                scaleX = 1.3f
                scaleY = 1.3f
            }
            texts.addView(name)
            texts.addView(sub)
            root.addView(texts)
            root.addView(sw)

            sw.setOnCheckedChangeListener { _, checked -> if (!updatingGroupSwitches) setGroup(index, checked) }
            root.setOnClickListener { if (sw.isEnabled) sw.toggle() }
            root.setOnLongClickListener {
                showGroupMembers(index)
                true
            }
            groupList.addView(root)
            groupCards.add(GroupCard(root, sub, sw))
            styleGroupCard(index, null)
        }
    }

    private fun styleGroupCard(index: Int, on: Boolean?) {
        groupCards[index].root.background = GradientDrawable().apply {
            setColor(if (on == true) 0xFF3A2E10.toInt() else 0xFF1E1E1E.toInt())
            cornerRadius = dp(14).toFloat()
            if (on == true) setStroke(dp(1), AMBER)
        }
    }

    private fun refreshLights() {
        if ((haUrl().isEmpty() && haLocalUrl().isEmpty()) || haToken().isEmpty()) {
            statusText.text = "Vul de Home Assistant-gegevens in via ⚙"
            return
        }
        if (haBusy) return
        thread {
            try {
                val arr = JSONArray(haRequest("GET", "/api/states", null))
                val byId = mutableMapOf<String, HaLight>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("entity_id")
                    if (!id.startsWith("light.")) continue
                    val name = o.optJSONObject("attributes")?.optString("friendly_name")
                        ?.takeIf { it.isNotBlank() } ?: id.removePrefix("light.")
                    byId[id] = HaLight(id, name, o.optString("state"))
                }
                // Lamp die niet (meer) in Home Assistant bestaat → tonen als "niet gevonden"
                val members = LIGHT_GROUPS.map { (_, ids) ->
                    ids.map { id -> byId[id] ?: HaLight(id, id.removePrefix("light."), "not_found") }
                }
                runOnUiThread { showGroups(members) }
            } catch (e: Exception) {
                runOnUiThread {
                    groupCards.forEachIndexed { i, c ->
                        c.sub.text = "—"
                        styleGroupCard(i, null)
                    }
                    statusText.text = "Geen verbinding:\n${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun showGroups(members: List<List<HaLight>>) {
        updatingGroupSwitches = true
        members.forEachIndexed { i, list ->
            groupMembers[i] = list
            val card = groupCards[i]
            val usable = list.filter { it.state == "on" || it.state == "off" }
            val onCount = usable.count { it.state == "on" }
            card.sw.isEnabled = usable.isNotEmpty()
            card.sw.isChecked = onCount > 0
            card.sub.text = when {
                list.isEmpty() -> "Geen lampen gevonden"
                usable.isEmpty() -> "Niet bereikbaar"
                onCount == 0 -> "Uit  ·  ${usable.size} lampen"
                onCount == usable.size -> "Aan  ·  ${usable.size} lampen"
                else -> "$onCount van ${usable.size} aan"
            }
            card.sub.setTextColor(if (onCount > 0) AMBER else 0xFFB0B0B0.toInt())
            styleGroupCard(i, if (usable.isEmpty()) null else onCount > 0)
        }
        updatingGroupSwitches = false
        statusText.text = "Lang indrukken toont welke lampen onder een schakelaar vallen.\n" + haRouteLabel()
    }

    /** Aan = alle lampen van de groep aan; uit = allemaal uit. */
    private fun setGroup(index: Int, on: Boolean) {
        val ids = groupMembers[index].filter { it.state != "not_found" }.map { it.id }
        if (ids.isEmpty()) return
        haBusy = true
        thread {
            try {
                haRequest(
                    "POST", "/api/services/light/${if (on) "turn_on" else "turn_off"}",
                    JSONObject().put("entity_id", JSONArray(ids)).toString()
                )
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Schakelen mislukt:\n${e.message}" }
            } finally {
                Thread.sleep(500)
                runOnUiThread {
                    haBusy = false
                    refreshLights()
                }
            }
        }
    }

    /** Lang indrukken: laat zien welke lampen onder deze schakelaar vallen. */
    private fun showGroupMembers(index: Int) {
        val list = groupMembers[index]
        val text = list.joinToString("\n") { l ->
            val st = when (l.state) {
                "on" -> "aan"
                "off" -> "uit"
                "not_found" -> "niet gevonden in Home Assistant"
                else -> "niet bereikbaar"
            }
            "• ${l.name} ($st)\n   ${l.id}"
        }
        AlertDialog.Builder(this)
            .setTitle(LIGHT_GROUPS[index].first)
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------- Thuis lokaal, onderweg via Nabu Casa ----------
    // De app probeert eerst het lokale adres (kort, 1,5 s). Lukt dat, dan gaat alles lokaal;
    // anders via het externe adres. De keuze wordt 60 s onthouden en direct herzien als een
    // verzoek mislukt (bijvoorbeeld als je de deur uit loopt).

    @Volatile private var haActiveBase: String? = null
    @Volatile private var haActiveUntil = 0L

    @Synchronized
    private fun haBase(): String {
        val cached = haActiveBase
        if (cached != null && System.currentTimeMillis() < haActiveUntil) return cached
        val local = haLocalUrl()
        val chosen = if (local.isNotEmpty() && probeHa(local)) local else haUrl().ifEmpty { local }
        haActiveBase = chosen
        haActiveUntil = System.currentTimeMillis() + 60_000
        return chosen
    }

    private fun probeHa(base: String): Boolean = try {
        val c = URL("$base/api/").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 1_500
            c.readTimeout = 2_500
            c.setRequestProperty("Authorization", "Bearer ${haToken()}")
            c.responseCode in 200..299
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        false
    }

    /** "thuis" of "Nabu Casa", voor de statusregel. */
    private fun haRouteLabel(): String {
        val base = haActiveBase ?: return ""
        return if (base == haLocalUrl() && base.isNotEmpty()) "via thuisnetwerk" else "via Nabu Casa"
    }

    private fun haRequest(method: String, path: String, json: String?): String {
        val base = haBase()
        return try {
            haRequestAt(base, method, path, json)
        } catch (e: java.io.IOException) {
            // Netwerk gewisseld? Andere route proberen.
            val other = listOf(haLocalUrl(), haUrl()).firstOrNull { it.isNotEmpty() && it != base } ?: throw e
            val result = haRequestAt(other, method, path, json)
            haActiveBase = other
            haActiveUntil = System.currentTimeMillis() + 60_000
            result
        }
    }

    private fun haRequestAt(base: String, method: String, path: String, json: String?): String {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Authorization", "Bearer ${haToken()}")
            conn.setRequestProperty("Content-Type", "application/json")
            if (json != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = conn.responseCode
            if (code == 401) throw Exception("Token ongeldig (401)")
            if (code == 404) throw Exception("Niet gevonden (404)")
            if (code !in 200..299) throw Exception("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ================= Proxmox =================

    private class VmInfo(val node: String, val type: String, val name: String)

    private class VmCard(
        val title: TextView,
        val status: TextView,
        val cpuText: TextView,
        val cpuBar: ProgressBar,
        val netText: TextView,
        val memText: TextView,
        val btnShutdown: Button,
        val btnReboot: Button,
        val btnStart: Button,
    )

    private class NetSample(val netIn: Long, val netOut: Long, val timeMs: Long)

    private val vmInfo = ConcurrentHashMap<Int, VmInfo>()
    private val vmCards = mutableMapOf<Int, VmCard>()
    private val prevNet = ConcurrentHashMap<Int, NetSample>()
    private var pveMessageUntil = 0L

    private fun refreshPve() {
        if (pveUrl().isEmpty() || pveTokenId().isEmpty() || pveSecret().isEmpty()) {
            pveStatus.text = "Vul de Proxmox-gegevens in via ⚙"
            return
        }
        if (pveBusy) return
        pveBusy = true
        thread {
            try {
                if (PVE_VMIDS.any { !vmInfo.containsKey(it) }) discoverVms()
                refreshPveHosts()
                for (vmid in PVE_VMIDS) {
                    val info = vmInfo[vmid]
                    if (info == null) {
                        runOnUiThread { showVmMissing(vmid) }
                        continue
                    }
                    val d = JSONObject(pveRequest("GET", "/nodes/${info.node}/${info.type}/$vmid/status/current"))
                        .getJSONObject("data")

                    // Netwerk: Proxmox geeft totalen; snelheid = verschil sinds vorige meting
                    val now = System.currentTimeMillis()
                    val netIn = d.optLong("netin")
                    val netOut = d.optLong("netout")
                    val prev = prevNet[vmid]
                    prevNet[vmid] = NetSample(netIn, netOut, now)
                    var rateIn: Double? = null
                    var rateOut: Double? = null
                    if (prev != null && now > prev.timeMs && netIn >= prev.netIn && netOut >= prev.netOut) {
                        val sec = (now - prev.timeMs) / 1000.0
                        rateIn = (netIn - prev.netIn) / sec
                        rateOut = (netOut - prev.netOut) / sec
                    }
                    runOnUiThread { showVm(vmid, info, d, rateIn, rateOut) }
                }
                runOnUiThread { if (System.currentTimeMillis() > pveMessageUntil) pveStatus.text = "" }
            } catch (e: Exception) {
                runOnUiThread {
                    pveStatus.text = "Geen verbinding met Proxmox:\n${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                pveBusy = false
            }
        }
    }

    // ---------- Host (de mini-pc zelf): temperatuur, CPU, geheugen ----------
    // Proxmox geeft standaard geen temperaturen via de API. Met de bekende "sensors"-aanpassing
    // (lm-sensors + mod in Nodes.pm, zoals PVE-mods) staat de uitvoer van `sensors` in
    // /nodes/<node>/status als "sensorsOutput" (JSON) of "thermalstate" (tekst of JSON).

    private class HostCard(val title: TextView, val temp: TextView, val extra: TextView)
    private class Temps(val cpu: Double?, val nvme: Double?)

    private val hostCards = mutableMapOf<String, HostCard>()
    private val pveNodes = mutableListOf<String>()

    /** Wordt op de achtergrond-thread aangeroepen vanuit refreshPve(). */
    private fun refreshPveHosts() {
        if (pveNodes.isEmpty()) {
            runCatching {
                val arr = JSONObject(pveRequest("GET", "/nodes")).getJSONArray("data")
                for (i in 0 until arr.length()) arr.getJSONObject(i).optString("node").takeIf { it.isNotEmpty() }
                    ?.let { pveNodes.add(it) }
            }
            if (pveNodes.isEmpty()) pveNodes.addAll(vmInfo.values.map { it.node }.distinct())
        }
        for (node in pveNodes.toList()) {
            val result = runCatching {
                JSONObject(pveRequest("GET", "/nodes/$node/status")).getJSONObject("data")
            }
            runOnUiThread { showHost(node, result) }
        }
    }

    private fun showHost(node: String, result: Result<JSONObject>) {
        val card = hostCards.getOrPut(node) { createHostCard() }
        card.title.text = "Host  ·  $node"
        val d = result.getOrNull()
        if (d == null) {
            val msg = result.exceptionOrNull()?.message ?: "onbekende fout"
            card.temp.text = "—"
            card.temp.setTextColor(GREY)
            card.extra.text = if (msg.contains("403")) {
                "Geen rechten voor de host. Geef het token ook de rol PVEAuditor (path /)."
            } else "Hostgegevens niet beschikbaar: $msg"
            return
        }

        val temps = parseTemps(d)
        val cpuTemp = temps?.cpu
        val nvmeTemp = temps?.nvme
        if (cpuTemp != null) {
            card.temp.text = String.format(NL, "%.0f °C", cpuTemp)
            card.temp.setTextColor(
                when {
                    cpuTemp >= 80 -> RED
                    cpuTemp >= 65 -> AMBER
                    else -> GREEN
                }
            )
        } else {
            card.temp.text = "— °C"
            card.temp.setTextColor(GREY)
        }

        val parts = mutableListOf<String>()
        if (nvmeTemp != null) parts.add(String.format(NL, "NVMe %.0f °C", nvmeTemp))
        val cpuPct = d.optDouble("cpu", Double.NaN)
        if (!cpuPct.isNaN()) parts.add(String.format(NL, "CPU %.0f%%", cpuPct * 100))
        d.optJSONObject("memory")?.let { m ->
            val total = m.optLong("total")
            if (total > 0) parts.add("RAM ${formatGb(m.optLong("used"))} / ${formatGb(total)} GB")
        }
        if (temps == null) parts.add(0, "Temperatuur niet zichtbaar in de Proxmox-API")
        card.extra.text = parts.joinToString("  ·  ")
    }

    private fun createHostCard(): HostCard {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(ctx).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val extra = TextView(ctx).apply {
            textSize = 13f
            setTextColor(0xFFB0B0B0.toInt())
            setPadding(0, dp(4), dp(8), 0)
        }
        val temp = TextView(ctx).apply {
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(GREY)
            text = "…"
        }
        texts.addView(title)
        texts.addView(extra)
        root.addView(texts)
        root.addView(temp)
        vmList.addView(root, 0)
        return HostCard(title, temp, extra)
    }

    /** Leest CPU- en NVMe-temperatuur uit de sensors-uitvoer (JSON van `sensors -j`, of gewone tekst). */
    private fun parseTemps(d: JSONObject): Temps? {
        val raw = d.optString("sensorsOutput", "").ifBlank { d.optString("thermalstate", "") }
        if (raw.isBlank()) return null

        val json = runCatching { JSONObject(raw) }.getOrNull()
        if (json != null) {
            var cpuMain: Double? = null
            var cpuAny: Double? = null
            var nvme: Double? = null
            for (chip in json.keys()) {
                val c = json.optJSONObject(chip) ?: continue
                val isCpu = listOf("coretemp", "k10temp", "zenpower", "cpu_thermal", "acpitz")
                    .any { chip.startsWith(it) }
                val isNvme = chip.startsWith("nvme")
                for (label in c.keys()) {
                    val f = c.optJSONObject(label) ?: continue
                    val key = f.keys().asSequence().firstOrNull { it.startsWith("temp") && it.endsWith("_input") }
                        ?: continue
                    val v = f.optDouble(key, Double.NaN)
                    if (v.isNaN()) continue
                    if (isCpu) {
                        if (label.startsWith("Package") || label == "Tctl" || label == "Tdie") cpuMain = cpuMain ?: v
                        cpuAny = maxOf(cpuAny ?: v, v)
                    }
                    if (isNvme && (label == "Composite" || nvme == null)) nvme = v
                }
            }
            val cpu = cpuMain ?: cpuAny
            return if (cpu == null && nvme == null) null else Temps(cpu, nvme)
        }

        fun find(re: String) = Regex(re).find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
        val cpu = find("""(?:Package id \d+|Tctl|Tdie):\s*\+?([\d.]+)""")
            ?: find("""Core \d+:\s*\+?([\d.]+)""")
            ?: find("""temp1:\s*\+?([\d.]+)""")
        val nvme = find("""Composite:\s*\+?([\d.]+)""")
        return if (cpu == null && nvme == null) null else Temps(cpu, nvme)
    }

    /** Zoekt op welke node elke VM draait en of het een VM (qemu) of container (lxc) is. */
    private fun discoverVms() {
        val list = JSONObject(pveRequest("GET", "/cluster/resources?type=vm")).getJSONArray("data")
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val vmid = o.optInt("vmid", -1)
            if (vmid in PVE_VMIDS) {
                vmInfo[vmid] = VmInfo(
                    node = o.getString("node"),
                    type = o.optString("type", "qemu"),
                    name = o.optString("name", "VM $vmid"),
                )
            }
        }
    }

    private fun showVmMissing(vmid: Int) {
        val card = vmCards.getOrPut(vmid) { createVmCard(vmid) }
        card.title.text = "VM $vmid"
        card.status.text = "Niet gevonden. Bestaat de VM, en heeft het token rechten (rol PVEVMUser)?"
        card.status.setTextColor(RED)
        card.btnShutdown.visibility = View.GONE
        card.btnReboot.visibility = View.GONE
        card.btnStart.visibility = View.GONE
    }

    private fun showVm(vmid: Int, info: VmInfo, d: JSONObject, rateIn: Double?, rateOut: Double?) {
        val card = vmCards.getOrPut(vmid) { createVmCard(vmid) }
        val running = d.optString("status") == "running"
        val label = if (info.type == "lxc") "CT" else "VM"

        card.title.text = "${d.optString("name", info.name)}  ·  $label $vmid"
        if (running) {
            card.status.text = "● Actief  ·  up ${formatUptime(d.optLong("uptime"))}  ·  node ${info.node}"
            card.status.setTextColor(GREEN)
        } else {
            card.status.text = "● Uit  ·  node ${info.node}"
            card.status.setTextColor(GREY)
        }

        val cpuPct = d.optDouble("cpu", 0.0) * 100
        val cpus = d.optInt("cpus", 1)
        card.cpuBar.progress = if (running) cpuPct.toInt().coerceIn(0, 100) else 0
        card.cpuText.text = if (running) {
            String.format(NL, "CPU  %.1f%%  (%d cores)", cpuPct, cpus)
        } else "CPU  —"

        card.netText.text = when {
            !running -> "Netwerk  —"
            rateIn == null || rateOut == null -> "Netwerk  meten…"
            else -> "Netwerk  ↓ ${formatRate(rateIn)}   ↑ ${formatRate(rateOut)}"
        }

        val mem = d.optLong("mem")
        val maxMem = d.optLong("maxmem")
        card.memText.text = if (running && maxMem > 0) {
            "Geheugen  ${formatGb(mem)} / ${formatGb(maxMem)} GB"
        } else "Geheugen  —"

        card.btnShutdown.visibility = if (running) View.VISIBLE else View.GONE
        card.btnReboot.visibility = if (running) View.VISIBLE else View.GONE
        card.btnStart.visibility = if (running) View.GONE else View.VISIBLE
    }

    private fun createVmCard(vmid: Int): VmCard {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        fun text(size: Float, color: Int, topMargin: Int = 0, bold: Boolean = false) =
            TextView(ctx).apply {
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { this.topMargin = dp(topMargin) }
            }

        fun button(label: String, color: Int) = Button(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }

        val title = text(20f, Color.WHITE, bold = true).apply { text = "VM $vmid" }
        val status = text(14f, GREY, topMargin = 4).apply { text = "Laden…" }
        val cpuText = text(15f, Color.WHITE, topMargin = 16)
        val cpuBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)
            ).apply { topMargin = dp(6) }
        }
        val netText = text(15f, Color.WHITE, topMargin = 12)
        val memText = text(15f, Color.WHITE, topMargin = 8)

        val btnShutdown = button("Afsluiten", 0xFFB71C1C.toInt())
        val btnReboot = button("Herstarten", 0xFFE57000.toInt())
        val btnStart = button("Starten", 0xFF2E7D32.toInt())
        btnShutdown.setOnClickListener { confirmPveAction(vmid, "shutdown", "Afsluiten") }
        btnReboot.setOnClickListener { confirmPveAction(vmid, "reboot", "Herstarten") }
        btnStart.setOnClickListener { confirmPveAction(vmid, "start", "Starten") }
        btnStart.visibility = View.GONE

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            addView(btnShutdown)
            addView(btnReboot)
            addView(btnStart)
        }

        listOf(title, status, cpuText, cpuBar, netText, memText, buttons).forEach { root.addView(it) }
        vmList.addView(root)
        return VmCard(title, status, cpuText, cpuBar, netText, memText, btnShutdown, btnReboot, btnStart)
    }

    private fun confirmPveAction(vmid: Int, action: String, label: String) {
        val info = vmInfo[vmid] ?: return
        AlertDialog.Builder(this)
            .setTitle("$label?")
            .setMessage("Weet je zeker dat je ${info.name} (${vmid}) wilt ${label.lowercase(NL)}?")
            .setPositiveButton(label) { _, _ -> runPveAction(vmid, info, action, label) }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    /** POST /nodes/<node>/<qemu|lxc>/<vmid>/status/<shutdown|reboot|start> */
    private fun runPveAction(vmid: Int, info: VmInfo, action: String, label: String) {
        showPveMessage("$label: bezig…")
        thread {
            try {
                pveRequest("POST", "/nodes/${info.node}/${info.type}/$vmid/status/$action")
                runOnUiThread { showPveMessage("$label: opdracht verstuurd naar ${info.name}") }
            } catch (e: Exception) {
                runOnUiThread { showPveMessage("$label mislukt:\n${e.message}") }
            }
        }
    }

    private fun showPveMessage(msg: String) {
        pveStatus.text = msg
        pveMessageUntil = System.currentTimeMillis() + 10_000
    }

    private fun pveRequest(method: String, path: String): String {
        val conn = URL(pveUrl() + "/api2/json" + path).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = pveSslFactory
            conn.hostnameVerifier = pinningHostnameVerifier(KEY_PVE_PIN)
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Authorization", "PVEAPIToken=${pveTokenId()}=${pveSecret()}")
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { }
            }
            val code = conn.responseCode
            if (code == 401) throw Exception("Token ID of secret ongeldig (401)")
            if (code == 403) throw Exception("Token heeft onvoldoende rechten (403)")
            if (code !in 200..299) throw Exception("HTTP $code ${conn.responseMessage ?: ""}".trim())
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Certificaat van Proxmox ----------
    // Proxmox gebruikt standaard een zelfondertekend certificaat. Een certificaat dat Android
    // zelf vertrouwt (bijv. Let's Encrypt via nginx) wordt gewoon geaccepteerd. Anders onthoudt
    // de app bij de eerste verbinding de vingerafdruk en accepteert daarna alleen dat certificaat.

    private fun fingerprint(cert: Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    private inner class PinningTrustManager(private val pinKey: String) : X509TrustManager {
        private val system: X509TrustManager =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
                init(null as KeyStore?)
                trustManagers.filterIsInstance<X509TrustManager>().first()
            }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
                return
            } catch (e: CertificateException) {
                // niet door Android vertrouwd → vingerafdruk controleren
            }
            val fp = fingerprint(chain[0])
            val pinned = prefs().getString(pinKey, "")!!
            if (pinned.isEmpty()) {
                prefs().edit().putString(pinKey, fp).commit()
            } else if (!pinned.equals(fp, ignoreCase = true)) {
                throw CertificateException(
                    "Certificaat is veranderd. Open ⚙ en tik Opslaan om het nieuwe te accepteren."
                )
            }
        }
    }

    private fun pinningSslFactory(pinKey: String) =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(PinningTrustManager(pinKey)), null)
        }.socketFactory

    private val pveSslFactory by lazy { pinningSslFactory(KEY_PVE_PIN) }
    private val fritzSslFactory by lazy { pinningSslFactory(KEY_FRITZ_PIN) }

    private fun pinningHostnameVerifier(pinKey: String) = HostnameVerifier { host, session ->
        val pinned = prefs().getString(pinKey, "")!!
        val leaf = session.peerCertificates.firstOrNull()
        (pinned.isNotEmpty() && leaf != null && fingerprint(leaf).equals(pinned, ignoreCase = true)) ||
            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
    }

    // ================= Fritzbox (TR-064) =================
    // wlanconfig1 = 2,4 GHz, wlanconfig2 = 5 GHz (gastnetwerk = 3, laten we met rust)

    private class WifiBand(val label: String, val service: Int)
    private class FritzRow(val title: TextView, val sub: TextView, val value: TextView?)

    private val bands = listOf(WifiBand("2,4 GHz", 1), WifiBand("5 GHz", 2))
    private lateinit var rowReboot: FritzRow
    private lateinit var rowWifi: FritzRow
    private lateinit var wifiSwitch: Switch
    private val rowChannels = mutableListOf<FritzRow>()

    @Volatile private var fritzBusy = false
    @Volatile private var lastFritzRefresh = 0L
    private var wifiEnabled: Boolean? = null
    private val currentChannel = arrayOfNulls<String>(2)
    private val fritzPossibleChannels = ConcurrentHashMap<Int, List<String>>()
    private var fritzMessageUntil = 0L
    private var updatingSwitch = false

    private fun buildFritzRows() {
        val rebootBtn = Button(this).apply {
            text = "Reboot"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFFB71C1C.toInt())
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(44))
            setOnClickListener { confirmFritzReboot() }
        }
        rowReboot = fritzRow("Fritzbox herstarten", "Internet is ±2–3 minuten weg", rebootBtn)

        wifiSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, checked -> if (!updatingSwitch) onWifiSwitch(checked) }
        }
        rowWifi = fritzRow("WiFi", "Laden…", wifiSwitch)

        bands.forEachIndexed { i, band ->
            val value = TextView(this).apply {
                text = "…  ›"
                textSize = 18f
                setTextColor(Color.WHITE)
            }
            val row = fritzRow("WiFi-kanaal ${band.label}", "Tik om te wijzigen", value) { chooseChannel(i) }
            rowChannels.add(row)
        }
    }

    private fun fritzRow(title: String, sub: String, trailing: View, onClick: (() -> Unit)? = null): FritzRow {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val t = TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val s = TextView(this).apply {
            text = sub
            textSize = 13f
            setTextColor(0xFFB0B0B0.toInt())
        }
        texts.addView(t)
        texts.addView(s)
        root.addView(texts)
        root.addView(trailing)
        if (onClick != null) root.setOnClickListener { onClick() }
        fritzList.addView(root)
        return FritzRow(t, s, trailing as? TextView)
    }

    private fun refreshFritz() {
        if (fritzUrl().isEmpty() || fritzUser().isEmpty() || fritzPass().isEmpty()) {
            fritzStatus.text = "Vul de Fritzbox-gegevens in via ⚙"
            return
        }
        if (fritzBusy) return
        fritzBusy = true
        lastFritzRefresh = System.currentTimeMillis()
        thread {
            try {
                val infos = bands.map { tr064Wlan(it.service, "GetInfo") }
                bands.forEachIndexed { i, band ->
                    if (!fritzPossibleChannels.containsKey(i)) {
                        runCatching { tr064Wlan(band.service, "GetChannelInfo") }.getOrNull()
                            ?.get("NewPossibleChannels")
                            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                            ?.let { fritzPossibleChannels[i] = it }
                    }
                }
                runOnUiThread { showFritz(infos) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (System.currentTimeMillis() > fritzMessageUntil) {
                        fritzStatus.text = "Geen verbinding met de Fritzbox:\n${e.message ?: e.javaClass.simpleName}"
                    }
                }
            } finally {
                fritzBusy = false
            }
        }
    }

    private fun showFritz(infos: List<Map<String, String>>) {
        val enabled = infos.map { it["NewEnable"] == "1" }
        wifiEnabled = enabled.any { it }
        updatingSwitch = true
        wifiSwitch.isChecked = wifiEnabled == true
        updatingSwitch = false

        val ssid = infos.firstNotNullOfOrNull { it["NewSSID"]?.takeIf { s -> s.isNotEmpty() } }
        rowWifi.sub.text = when {
            enabled.all { it } -> "Aan" + (if (ssid != null) "  ·  $ssid" else "")
            enabled.none { it } -> "Uit"
            else -> "Alleen " + bands.filterIndexed { i, _ -> enabled[i] }.joinToString { it.label } + " aan"
        }

        bands.forEachIndexed { i, _ ->
            val ch = infos[i]["NewChannel"]
            currentChannel[i] = ch
            rowChannels[i].value?.text = if (ch.isNullOrEmpty()) "?  ›" else "$ch  ›"
            rowChannels[i].sub.text = if (enabled[i]) "Huidig kanaal $ch · tik om te wijzigen" else "Band staat uit"
        }
        if (System.currentTimeMillis() > fritzMessageUntil) fritzStatus.text = ""
    }

    private fun onWifiSwitch(on: Boolean) {
        if (on) {
            setWifi(true)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("WiFi uitzetten?")
            .setMessage(
                "Alle apparaten op WiFi (2,4 en 5 GHz) verliezen hun verbinding. " +
                    "Zit je telefoon zelf op deze WiFi, dan kun je hem daarna alleen via mobiel internet weer aanzetten."
            )
            .setPositiveButton("Uitzetten") { _, _ -> setWifi(false) }
            .setNegativeButton("Annuleren") { _, _ -> revertWifiSwitch() }
            .setOnCancelListener { revertWifiSwitch() }
            .show()
    }

    private fun revertWifiSwitch() {
        updatingSwitch = true
        wifiSwitch.isChecked = wifiEnabled == true
        updatingSwitch = false
    }

    private fun setWifi(on: Boolean) {
        showFritzMessage(if (on) "WiFi wordt aangezet…" else "WiFi wordt uitgezet…")
        fritzAction(if (on) "WiFi aangezet" else "WiFi uitgezet") {
            bands.forEach { tr064Wlan(it.service, "SetEnable", mapOf("NewEnable" to if (on) "1" else "0")) }
        }
    }

    private fun chooseChannel(i: Int) {
        val options = fritzPossibleChannels[i]
        if (options.isNullOrEmpty()) {
            showFritzMessage("Kanalenlijst is nog niet geladen; probeer het zo opnieuw.")
            refreshFritz()
            return
        }
        val labels = options.map { "Kanaal $it" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("WiFi-kanaal ${bands[i].label}")
            .setSingleChoiceItems(labels, options.indexOf(currentChannel[i])) { d, which ->
                d.dismiss()
                val ch = options[which]
                if (ch != currentChannel[i]) {
                    showFritzMessage("Kanaal ${bands[i].label} wordt $ch… (WiFi kan even wegvallen)")
                    fritzAction("Kanaal ${bands[i].label} ingesteld op $ch") {
                        tr064Wlan(bands[i].service, "SetChannel", mapOf("NewChannel" to ch))
                    }
                }
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun confirmFritzReboot() {
        AlertDialog.Builder(this)
            .setTitle("Fritzbox herstarten?")
            .setMessage("Internet, WiFi en alles via de Fritzbox is ±2–3 minuten weg. Ook deze app verliest even de verbinding.")
            .setPositiveButton("Herstarten") { _, _ ->
                fritzAction("Fritzbox herstart; over ±3 minuten is hij terug") {
                    tr064("/upnp/control/deviceconfig", "urn:dslforum-org:service:DeviceConfig:1", "Reboot")
                }
                fritzMessageUntil = System.currentTimeMillis() + 180_000
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    /** Voert een Fritzbox-opdracht uit op de achtergrond en ververst daarna de status. */
    private fun fritzAction(doneMessage: String, block: () -> Unit) {
        thread {
            val error = runCatching { block() }.exceptionOrNull()
            runOnUiThread {
                if (error == null) showFritzMessage(doneMessage)
                else showFritzMessage("Mislukt:\n${error.message ?: error.javaClass.simpleName}")
                handler.postDelayed({ if (currentTab == 2) refreshFritz() }, 3_000)
            }
        }
    }

    private fun showFritzMessage(msg: String) {
        fritzStatus.text = msg
        fritzMessageUntil = maxOf(fritzMessageUntil, System.currentTimeMillis() + 12_000)
    }

    private fun tr064Wlan(service: Int, action: String, args: Map<String, String> = emptyMap()) =
        tr064("/upnp/control/wlanconfig$service", "urn:dslforum-org:service:WLANConfiguration:$service", action, args)

    /** TR-064 SOAP-aanroep met Digest-login. Geeft de <NewXxx>-waarden uit het antwoord terug. */
    private fun tr064(
        controlPath: String,
        serviceType: String,
        action: String,
        args: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val argXml = args.entries.joinToString("") { (k, v) -> "<$k>${xmlEscape(v)}</$k>" }
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$serviceType\">$argXml</u:$action>" +
            "</s:Body></s:Envelope>"
        val url = URL(fritzUrl() + controlPath)
        var auth: String? = null

        for (attempt in 0..1) {
            val conn = url.openConnection() as HttpURLConnection
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = fritzSslFactory
                conn.hostnameVerifier = pinningHostnameVerifier(KEY_FRITZ_PIN)
            }
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 6_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                conn.setRequestProperty("SOAPAction", "\"$serviceType#$action\"")
                auth?.let { conn.setRequestProperty("Authorization", it) }
                conn.outputStream.use { it.write(body.toByteArray()) }

                val code = conn.responseCode
                if (code == 401) {
                    if (attempt == 1) throw Exception("Gebruikersnaam of wachtwoord onjuist, of de gebruiker mist rechten (401)")
                    val challenge = conn.getHeaderField("WWW-Authenticate")
                        ?: throw Exception("Fritzbox vraagt om login, maar zonder Digest (401)")
                    auth = digestHeader(challenge, "POST", url.file.ifEmpty { "/" })
                    continue
                }
                if (code == 500) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    val desc = Regex("<errorDescription>(.*?)</errorDescription>").find(err)?.groupValues?.get(1)
                    throw Exception("Fritzbox weigert $action: ${desc ?: "fout 500"}")
                }
                if (code == 404) throw Exception("Niet gevonden (404). Klopt de Fritzbox-URL?")
                if (code !in 200..299) throw Exception("HTTP $code")

                val xml = conn.inputStream.bufferedReader().use { it.readText() }
                return Regex("<(New\\w+)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(xml)
                    .associate { it.groupValues[1] to xmlUnescape(it.groupValues[2]) }
            } finally {
                conn.disconnect()
            }
        }
        throw Exception("Inloggen bij de Fritzbox mislukt")
    }

    private fun digestHeader(challenge: String, method: String, uri: String): String {
        val params = Regex("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))")
            .findAll(challenge.removePrefix("Digest").trim())
            .associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
        val realm = params["realm"] ?: ""
        val nonce = params["nonce"] ?: ""
        val qop = params["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        val opaque = params["opaque"]
        val user = fritzUser()
        val ha1 = md5("$user:$realm:${fritzPass()}")
        val ha2 = md5("$method:$uri")
        val nc = "00000001"
        val cnonce = md5(System.nanoTime().toString()).take(16)
        val response = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", ")
            append("algorithm=MD5, response=\"$response\"")
            if (qop != null) append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            if (opaque != null) append(", opaque=\"$opaque\"")
        }
    }

    private fun md5(s: String) =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun xmlUnescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    // ================= Dashboard =================
    // Thermostaat, verwarming werkplek, receiver, stroom nu, crypto, stroomprijs en laadlimiet.
    // Alles via Home Assistant (zelfde URL + token), behalve de bitcoinkoers (CoinGecko).

    /** Schuifje dat een SeekBar (alleen gehele stappen) omrekent naar min/max/stap. */
    private inner class DashSlider(
        val seek: SeekBar,
        val label: TextView,
        val fmt: (Double) -> String,
        val onCommit: (Double) -> Unit,
    ) {
        var min = 0.0
        var max = 100.0
        var step = 1.0
        private var dragging = false
        private var lastCommit = 0L

        init {
            seek.progressTintList = ColorStateList.valueOf(AMBER)
            seek.thumbTintList = ColorStateList.valueOf(AMBER)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) label.text = fmt(value(p))
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    dragging = true
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    lastCommit = System.currentTimeMillis()
                    onCommit(value(sb.progress))
                }
            })
        }

        private fun busy() = dragging || System.currentTimeMillis() - lastCommit < 5_000

        fun value(p: Int) = min + p * step

        fun setRange(mn: Double, mx: Double, st: Double) {
            if (busy() || st <= 0 || mx <= mn) return
            if (mn == min && mx == max && st == step) return
            min = mn
            max = mx
            step = st
            seek.max = Math.round((mx - mn) / st).toInt()
        }

        fun show(v: Double?) {
            if (busy()) return
            if (v == null) {
                label.text = "—"
                return
            }
            seek.progress = Math.round((v - min) / step).toInt().coerceIn(0, seek.max)
            label.text = fmt(v)
        }
    }

    /** Kaart met kopregel (titel + rechts een waarde of knoppen), grote waarde en subregel. */
    private inner class DashCard(title: String, half: Boolean = false) {
        val root = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = if (half) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
        }
        val header = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this@MainActivity).apply {
            text = title
            textSize = 13f
            setTextColor(0xFFB0B0B0.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val right = TextView(this@MainActivity).apply {
            textSize = 13f
            setTextColor(0xFFB0B0B0.toInt())
        }
        val big = TextView(this@MainActivity).apply {
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            text = "…"
        }
        val sub = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }

        init {
            header.addView(titleView)
            header.addView(right)
            root.addView(header)
            root.addView(big)
            root.addView(sub)
        }

        fun addSeek(): SeekBar = SeekBar(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            root.addView(this)
        }
    }

    private lateinit var thermoCard: DashCard
    private lateinit var thermoSlider: DashSlider
    private lateinit var thermoSwitch: Switch
    private lateinit var heaterCard: DashCard
    private lateinit var heaterSlider: DashSlider
    private lateinit var heaterSwitch: Switch
    private lateinit var receiverCard: DashCard
    private lateinit var receiverSwitch: Switch
    private lateinit var sourceButton: Button
    private lateinit var volumeSlider: DashSlider
    private lateinit var powerCard: DashCard
    private lateinit var cryptoCard: DashCard
    private lateinit var priceCard: DashCard
    private lateinit var priceBars: LinearLayout
    private lateinit var chargeCard: DashCard
    private lateinit var chargeSlider: DashSlider

    private val dashAttrs = ConcurrentHashMap<String, JSONObject>()
    private val dashStates = ConcurrentHashMap<String, String>()
    @Volatile private var dashBusy = false
    private var updatingDash = false
    @Volatile private var btcEur: Double? = null
    @Volatile private var lastBtcFetch = 0L

    private fun smallSwitch(onChange: (Boolean) -> Unit) = Switch(this).apply {
        setOnCheckedChangeListener { _, checked -> if (!updatingDash) onChange(checked) }
    }

    private fun buildDashboard() {
        // Thermostaat
        thermoCard = DashCard("🌡  Thermostaat · woonkamer")
        thermoSwitch = smallSwitch { on -> setHvac(DASH_THERMOSTAT, on) }
        thermoCard.header.addView(thermoSwitch)
        thermoSlider = DashSlider(thermoCard.addSeek(), thermoCard.big, ::fmtTemp) { v ->
            val data = JSONObject().put("entity_id", DASH_THERMOSTAT).put("temperature", v)
            if (dashStates[DASH_THERMOSTAT] == "off") data.put("hvac_mode", heatMode(DASH_THERMOSTAT))
            haService("climate", "set_temperature", data)
        }
        dashList.addView(thermoCard.root)

        // Verwarming werkplek
        heaterCard = DashCard("🔥  Verwarming werkplek")
        heaterSwitch = smallSwitch { on -> setHvac(DASH_HEATER, on) }
        heaterCard.header.addView(heaterSwitch)
        heaterSlider = DashSlider(heaterCard.addSeek(), heaterCard.big, ::fmtTemp) { v ->
            val data = JSONObject().put("entity_id", DASH_HEATER).put("temperature", v)
            if (dashStates[DASH_HEATER] == "off") data.put("hvac_mode", heatMode(DASH_HEATER))
            haService("climate", "set_temperature", data)
        }
        dashList.addView(heaterCard.root)

        // Receiver: aan/uit, bronknop en volume
        receiverCard = DashCard("🔊  Receiver")
        receiverCard.big.textSize = 18f
        sourceButton = Button(this).apply {
            text = "Bron ▾"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 13f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                setColor(0xFF2E2E2E.toInt())
                cornerRadius = dp(18).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { chooseSource() }
        }
        receiverSwitch = smallSwitch { on ->
            haService("media_player", if (on) "turn_on" else "turn_off", JSONObject().put("entity_id", DASH_RECEIVER))
        }
        receiverCard.header.addView(sourceButton)
        receiverCard.header.addView(receiverSwitch)
        volumeSlider = DashSlider(receiverCard.addSeek(), receiverCard.sub, { "Volume ${it.toInt()}%" }) { v ->
            haService(
                "media_player", "volume_set",
                JSONObject().put("entity_id", DASH_RECEIVER).put("volume_level", v / 100.0)
            )
        }
        volumeSlider.setRange(0.0, 100.0, 1.0)
        dashList.addView(receiverCard.root)

        // Stroom nu + crypto naast elkaar
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        powerCard = DashCard("⚡  Stroom nu", half = true)
        cryptoCard = DashCard("₿  Crypto", half = true)
        (cryptoCard.root.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
        cryptoCard.sub.text = "${fmtNumber(BTC_AMOUNT, 8)} BTC"
        row.addView(powerCard.root)
        row.addView(cryptoCard.root)
        dashList.addView(row)

        // Stroomprijs komende uren
        priceCard = DashCard("📊  Stroomprijs komende 3 uur")
        priceCard.right.text = "€/kWh"
        priceCard.root.removeView(priceCard.big)
        priceCard.root.removeView(priceCard.sub)
        priceBars = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        priceCard.root.addView(priceBars)
        priceCard.root.addView(priceCard.sub)
        dashList.addView(priceCard.root)

        // Laadlimiet auto
        chargeCard = DashCard("🚗  Škoda Elroq · laadlimiet")
        chargeSlider = DashSlider(chargeCard.addSeek(), chargeCard.big, { "${it.toInt()}%" }) { v ->
            haService("number", "set_value", JSONObject().put("entity_id", DASH_CHARGE_LIMIT).put("value", v))
        }
        chargeSlider.setRange(50.0, 100.0, 10.0)
        dashList.addView(chargeCard.root)
    }

    private fun refreshDashboard() {
        if ((haUrl().isEmpty() && haLocalUrl().isEmpty()) || haToken().isEmpty()) {
            dashStatus.text = "Vul de Home Assistant-gegevens in via ⚙"
            return
        }
        if (dashBusy) return
        dashBusy = true
        thread {
            try {
                val ids = listOf(
                    DASH_THERMOSTAT, DASH_HEATER, DASH_RECEIVER, DASH_POWER,
                    DASH_PRICE, DASH_CHARGE_LIMIT, DASH_CAR_BATTERY,
                )
                val results = ids.map { id ->
                    id to runCatching { JSONObject(haRequest("GET", "/api/states/$id", null)) }
                }
                if (System.currentTimeMillis() - lastBtcFetch > 60_000) {
                    lastBtcFetch = System.currentTimeMillis()
                    runCatching { fetchBtcEur() }.getOrNull()?.let { btcEur = it }
                }
                runOnUiThread { showDashboard(results) }
            } finally {
                dashBusy = false
            }
        }
    }

    private fun showDashboard(results: List<Pair<String, Result<JSONObject>>>) {
        val ok = results.mapNotNull { (id, r) -> r.getOrNull()?.let { id to it } }.toMap()
        if (ok.isEmpty()) {
            val err = results.first().second.exceptionOrNull()
            dashStatus.text = "Geen verbinding met Home Assistant:\n${err?.message ?: err?.javaClass?.simpleName}"
            return
        }
        for ((id, o) in ok) {
            dashStates[id] = o.optString("state")
            dashAttrs[id] = o.optJSONObject("attributes") ?: JSONObject()
        }
        updatingDash = true
        try {
            showClimate(DASH_THERMOSTAT, ok[DASH_THERMOSTAT], thermoCard, thermoSlider, thermoSwitch)
            showClimate(DASH_HEATER, ok[DASH_HEATER], heaterCard, heaterSlider, heaterSwitch)
            showReceiver(ok[DASH_RECEIVER])
            showPower(ok[DASH_POWER])
            showCrypto()
            showPrice(ok[DASH_PRICE])
            showCharge(ok[DASH_CHARGE_LIMIT], ok[DASH_CAR_BATTERY])
        } finally {
            updatingDash = false
        }
        val missing = results.filter { it.second.isFailure }.map { it.first }
        dashStatus.text = if (missing.isEmpty()) haRouteLabel() else "Niet bereikbaar: ${missing.joinToString()}"
    }

    private fun showClimate(id: String, o: JSONObject?, card: DashCard, slider: DashSlider, sw: Switch) {
        if (o == null) {
            card.big.text = "—"
            card.sub.text = "Niet bereikbaar"
            sw.isEnabled = false
            return
        }
        val a = o.optJSONObject("attributes") ?: JSONObject()
        val mode = o.optString("state")
        val cur = a.optDouble("current_temperature", Double.NaN)
        val set = a.optDouble("temperature", Double.NaN)
        slider.setRange(a.optDouble("min_temp", 5.0), a.optDouble("max_temp", 30.0), a.optDouble("target_temp_step", 0.5))
        slider.show(if (set.isNaN()) null else set)
        card.right.text = if (cur.isNaN()) "" else "nu ${fmtTemp(cur)}  "
        card.sub.text = when (mode) {
            "heat" -> "Verwarmen"
            "off" -> "Uit"
            "auto" -> "Automatisch"
            "cool" -> "Koelen"
            "unavailable" -> "Niet bereikbaar"
            else -> mode
        }
        sw.isEnabled = mode != "unavailable"
        sw.isChecked = mode != "off" && mode != "unavailable"
    }

    private fun showReceiver(o: JSONObject?) {
        if (o == null) {
            receiverCard.big.text = "Niet bereikbaar"
            receiverSwitch.isEnabled = false
            sourceButton.isEnabled = false
            volumeSlider.seek.isEnabled = false
            return
        }
        val a = o.optJSONObject("attributes") ?: JSONObject()
        val state = o.optString("state")
        val on = state != "off" && state != "standby" && state != "unavailable"
        receiverCard.big.text = a.optString("friendly_name", "Receiver")
        receiverSwitch.isEnabled = state != "unavailable"
        receiverSwitch.isChecked = on
        val source = a.optString("source", "")
        sourceButton.text = (if (on && source.isNotEmpty()) source else "Bron") + " ▾"
        sourceButton.isEnabled = on
        volumeSlider.seek.isEnabled = on
        val vol = a.optDouble("volume_level", Double.NaN)
        if (on && !vol.isNaN()) {
            volumeSlider.show(vol * 100)
            if (a.optBoolean("is_volume_muted", false)) receiverCard.sub.append("  ·  gedempt")
        } else {
            volumeSlider.show(null)
            receiverCard.sub.text = if (on) "Volume —" else "Uit"
        }
    }

    private fun chooseSource() {
        val list = dashAttrs[DASH_RECEIVER]?.optJSONArray("source_list")
        if (list == null || list.length() == 0) {
            dashStatus.text = "De receiver geeft geen bronnenlijst door."
            return
        }
        val sources = (0 until list.length()).map { list.optString(it) }
        val current = sources.indexOf(dashAttrs[DASH_RECEIVER]?.optString("source"))
        AlertDialog.Builder(this)
            .setTitle("Bron kiezen")
            .setSingleChoiceItems(sources.toTypedArray(), current) { d, which ->
                d.dismiss()
                sourceButton.text = sources[which] + " ▾"
                haService(
                    "media_player", "select_source",
                    JSONObject().put("entity_id", DASH_RECEIVER).put("source", sources[which])
                )
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun showPower(o: JSONObject?) {
        val w = o?.optString("state")?.toDoubleOrNull()
        if (w == null) {
            powerCard.big.text = "—"
            powerCard.sub.text = "Niet bereikbaar"
            return
        }
        powerCard.big.text = String.format(NL, "%.0f W", w).replace("-", "−")
        powerCard.big.setTextColor(if (w < 0) GREEN else AMBER)
        powerCard.sub.text = if (w < 0) "teruglevering" else "verbruik"
    }

    private fun showCrypto() {
        val price = btcEur
        if (price == null) {
            cryptoCard.big.text = "€ ···"
            return
        }
        cryptoCard.big.text = "€ " + fmtNumber(price * BTC_AMOUNT, 2)
        cryptoCard.sub.text = "${fmtNumber(BTC_AMOUNT, 8)} BTC\nkoers € ${fmtNumber(price, 0)}"
    }

    /** Bitcoinkoers in euro via CoinGecko (gratis, geen account). */
    private fun fetchBtcEur(): Double {
        val conn = URL("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=eur")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body).getJSONObject("bitcoin").getDouble("eur")
        } finally {
            conn.disconnect()
        }
    }

    private fun showPrice(o: JSONObject?) {
        priceBars.removeAllViews()
        if (o == null) {
            priceCard.sub.text = "Niet bereikbaar"
            return
        }
        val current = o.optString("state").toDoubleOrNull()
        val points = priceForecast(o.optJSONObject("attributes") ?: JSONObject(), current)
        if (points.isEmpty()) {
            priceCard.sub.text = "Geen prijsgegevens"
            return
        }
        val maxP = points.maxOf { it.second }.coerceAtLeast(0.01)
        val ranked = points.map { it.second }.sorted()
        for ((label, p) in points) {
            val rank = ranked.indexOf(p)
            val color = when {
                points.size == 1 -> AMBER
                rank == 0 -> GREEN
                rank == ranked.size - 1 -> RED
                rank * 2 < ranked.size -> AMBER
                else -> 0xFFE57000.toInt()
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, dp(78), 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            }
            val bar = TextView(this).apply {
                text = String.format(NL, "%.2f", p)
                textSize = 11f
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(Color.BLACK)
                setPadding(0, dp(2), 0, 0)
                background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadii = floatArrayOf(
                        dp(4).toFloat(), dp(4).toFloat(), dp(4).toFloat(), dp(4).toFloat(), 0f, 0f, 0f, 0f
                    )
                }
                val h = (dp(56) * (p / maxP)).toInt().coerceAtLeast(dp(18))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            }
            val lbl = TextView(this).apply {
                text = label
                textSize = 10f
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(0xFF888888.toInt())
            }
            col.addView(bar)
            col.addView(lbl)
            priceBars.addView(col)
        }
        priceCard.sub.text = if (points.size == 1) "Verwachting voor de komende uren niet beschikbaar" else ""
    }

    /** Zonneplan geeft de komende uren als 'forecast'-lijst; neemt nu + 3 uur. */
    private fun priceForecast(a: JSONObject, current: Double?): List<Pair<String, Double>> {
        val hourStart = System.currentTimeMillis() / 3_600_000 * 3_600_000
        val list = mutableListOf<Pair<Long, Double>>()
        val fc = a.optJSONArray("forecast")
        if (fc != null) {
            for (i in 0 until fc.length()) {
                val o = fc.optJSONObject(i) ?: continue
                val ts = parseIsoMillis(o.optString("datetime")) ?: continue
                if (ts < hourStart) continue
                var p = o.optDouble("electricity_price", Double.NaN)
                if (p.isNaN()) continue
                if (p > 10) p /= 10_000_000.0   // Zonneplan geeft soms 2813779 i.p.v. 0,2813779
                list.add(ts to p)
            }
        }
        val points = list.sortedBy { it.first }.take(4).map { (ts, p) ->
            val h = ((ts - hourStart) / 3_600_000).toInt()
            (if (h <= 0) "nu" else "+$h u") to p
        }.toMutableList()
        if (current != null) {
            if (points.isEmpty()) {
                points.add("nu" to current)
            } else if (points[0].first == "nu") {
                points[0] = "nu" to current
            }
        }
        return points
    }

    private fun parseIsoMillis(s: String): Long? =
        runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()

    private fun showCharge(o: JSONObject?, battery: JSONObject?) {
        if (o == null) {
            chargeCard.big.text = "—"
            chargeCard.sub.text = "Niet bereikbaar"
            return
        }
        val a = o.optJSONObject("attributes") ?: JSONObject()
        chargeSlider.setRange(a.optDouble("min", 50.0), a.optDouble("max", 100.0), a.optDouble("step", 10.0))
        chargeSlider.show(o.optString("state").toDoubleOrNull())
        val bat = battery?.optString("state")?.toDoubleOrNull()
        chargeCard.right.text = if (bat != null) "accu ${bat.toInt()}%" else ""
    }

    /** Eerste verwarmstand die het apparaat kent (meestal 'heat'). */
    private fun heatMode(id: String): String {
        val modes = dashAttrs[id]?.optJSONArray("hvac_modes") ?: return "heat"
        val list = (0 until modes.length()).map { modes.optString(it) }
        return if ("heat" in list) "heat" else list.firstOrNull { it != "off" } ?: "heat"
    }

    private fun setHvac(id: String, on: Boolean) {
        haService(
            "climate", "set_hvac_mode",
            JSONObject().put("entity_id", id).put("hvac_mode", if (on) heatMode(id) else "off")
        )
    }

    /** Roept een Home Assistant-service aan en ververst daarna het dashboard. */
    private fun haService(domain: String, service: String, data: JSONObject) {
        thread {
            val err = runCatching { haRequest("POST", "/api/services/$domain/$service", data.toString()) }
                .exceptionOrNull()
            runOnUiThread {
                if (err != null) dashStatus.text = "Mislukt: ${err.message ?: err.javaClass.simpleName}"
                handler.postDelayed({ if (currentTab == TAB_DASH) refreshDashboard() }, 1_500)
            }
        }
    }

    private fun fmtTemp(v: Double) = String.format(NL, if (v % 1.0 == 0.0) "%.0f °C" else "%.1f °C", v)

    private fun fmtNumber(v: Double, decimals: Int): String =
        java.text.NumberFormat.getNumberInstance(NL).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }.format(v)

    // ================= Hulpjes =================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun formatRate(bytesPerSec: Double): String {
        val bits = bytesPerSec * 8
        return if (bits >= 1_000_000) String.format(NL, "%.1f Mbit/s", bits / 1_000_000)
        else String.format(NL, "%.0f kbit/s", bits / 1_000)
    }

    private fun formatGb(bytes: Long) = String.format(NL, "%.1f", bytes / 1_073_741_824.0)

    private fun formatUptime(sec: Long): String {
        val d = sec / 86_400
        val h = (sec % 86_400) / 3_600
        val m = (sec % 3_600) / 60
        return when {
            d > 0 -> "${d}d ${h}u"
            h > 0 -> "${h}u ${m}m"
            else -> "${m}m"
        }
    }
}
