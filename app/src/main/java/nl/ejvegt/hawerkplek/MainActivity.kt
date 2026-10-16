package nl.ejvegt.hawerkplek

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.text.InputType
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
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
import android.view.KeyEvent
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.io.File
import java.io.IOException
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

    /**
     * Een app op de Google TV-box. [link] = deeplink (betrouwbaarst volgens Home Assistant);
     * zonder link wordt de app via zijn pakketnaam gestart.
     */
    class MediaApp(
        val name: String,
        val pkg: String,
        val color: Int,
        val link: String? = null,
        val scheme: String? = null,
        /** Eigen logo voor de tegel (drawable); zonder logo toont de tegel de naam. */
        val logo: Int? = null,
        /** Extra links om te proberen (bijv. de website, die de app soms zelf opent). */
        val alt: List<String> = emptyList(),
    )

    /** Een zender op KPN TV+: kanaalnummer, naam, kleur van het nummerblokje. */
    class TvChannel(val num: Int, val name: String, val color: Int)

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
        /** Dezelfde Denon via de HEOS-integratie: speelt HEOS-favorieten (radio) af. */
        const val DASH_HEOS = "media_player.denon_avc_x3800h_2"
        /** Bij bron "HEOS Music" automatisch deze HEOS-favoriet starten (deel van de naam). */
        const val HEOS_RADIO = "joe"
        /** Zenders op de HEOS-tegel: naam in de lijst → (deel van) de naam van de HEOS-favoriet. */
        val HEOS_STATIONS = listOf(
            "Absolut relax" to "absolut",
            "Sky Radio 90's Hits" to "90",
            "Sky Radio 101 FM" to "101",
            "Qmusic" to "qmusic",
            "JOE Nederland" to "joe",
        )
        const val DASH_POWER = "sensor.p1_meter_3c39e72f3c6a_active_power"
        const val DASH_PRICE = "sensor.zonneplan_current_electricity_tariff"
        const val DASH_CHARGE_LIMIT = "number.skoda_elroq_charge_limit"
        const val DASH_CAR_BATTERY = "sensor.skoda_elroq_battery_percentage"

        /** Bronnen van de receiver die de app toont: naam in de Denon → naam in de app (in deze volgorde). */
        val RECEIVER_SOURCES = listOf(
            "AUX1" to "Chromecast Audio",
            "GoogleTV Box" to "GoogleTV Box",
            "KPN Hangplek" to "TV+ woonkamer",
            "TV Audio" to "TV Audio",
            "Xbox" to "Xbox",
            "HEOS Music" to "HEOS Music",
        )

        /** Eigen bitcoinsaldo voor de cryptokaart. */
        const val BTC_AMOUNT = 0.00945966

        /** Interne nummers van de tabbladen. */
        const val TAB_DASH = 3
        const val TAB_MEDIA = 4

        /** Multimedia: Google TV-box (Android TV Remote-integratie). */
        const val MEDIA_REMOTE = "remote.googletv_box"
        const val MEDIA_PLAYER = "media_player.googletv_box"
        /** Dezelfde box via de Android Debug Bridge-integratie: start elke app betrouwbaar. */
        const val MEDIA_ADB = "media_player.android_tv_192_168_50_140"
        /** De LG-tv in de woonkamer (via HomeKit) en zijn MAC-adres voor Wake on LAN. */
        const val MEDIA_TV = "media_player.lg_webos_tv_5581"
        const val TV_MAC = "44:27:45:29:30:14"
        const val TV_BROADCAST = "192.168.50.255"
        /** Ingangen van de Denon (interne namen): apps via de Google TV-box, tv kijken via TV+. */
        const val SOURCE_APPS = "GoogleTV Box"
        const val SOURCE_TV = "KPN Hangplek"
        /** De TV+-box achter die Denon-ingang ("KPN Hangplek" in HA). */
        const val TVPLUS_REMOTE = "remote.kpn_diw7022"
        const val TVPLUS_PLAYER = "media_player.kpn_diw7022"
        const val CHROMECAST_AUDIO = "media_player.chromecastaudio8913"
        /** Spotify-integratie: weet altijd welk nummer er speelt (de cast-bridge geeft dat niet door). */
        const val SPOTIFY = "media_player.spotify_ejvegt"
        /** Muziek op de netwerkmap (SMB). Wachtwoord vul je in de app in, het staat niet in de code. */
        const val SMB_DEFAULT_PATH = "\\\\192.168.50.208\\NextcloudFiles\\music"
        const val SMB_DEFAULT_USER = "ejvegt"
        private const val KEY_SMB_PATH = "smb_path"
        private const val KEY_SMB_USER = "smb_user"
        private const val KEY_SMB_PASS = "smb_pass"
        private const val KEY_MA_LAST_PLAYER = "ma_last_player"
        /** Cast-apparaten waarop muziek van de netwerkmap kan spelen: naam → (entity, bron Denon). */
        val MUSIC_TARGETS = listOf(
            Triple("Chromecast Audio", "media_player.chromecastaudio8913", "AUX1"),
            Triple("GoogleTV Box", "media_player.tv_in_woonkamer", "GoogleTV Box"),
        )

        private val C_NPO = 0xFFE65100.toInt()
        private val C_RTL = 0xFFC62828.toInt()
        private val C_TALPA = 0xFF283593.toInt()
        private val C_SPORT = 0xFF2E7D32.toInt()
        private val C_ETC = 0xFF455A64.toInt()

        /** Favorieten bovenaan (sport). Kanaalnummers volgens de KPN-zenderindeling (sep 2026). */
        val TV_FAVORITES = listOf(
            TvChannel(14, "ESPN", C_SPORT),
            TvChannel(222, "ESPN 2", C_SPORT),
            TvChannel(223, "ESPN 3", C_SPORT),
            TvChannel(224, "ESPN 4", C_SPORT),
            TvChannel(233, "Ziggo Sport", C_SPORT),
            TvChannel(234, "Ziggo Sport 2", C_SPORT),
            TvChannel(235, "Ziggo Sport 3", C_SPORT),
            TvChannel(236, "Ziggo Sport 4", C_SPORT),
            TvChannel(237, "Ziggo Sport 5", C_SPORT),
            TvChannel(238, "Ziggo Sport 6", C_SPORT),
        )

        /** Gangbare Nederlandse zenders. */
        val TV_CHANNELS = listOf(
            TvChannel(1, "NPO 1", C_NPO),
            TvChannel(2, "NPO 2", C_NPO),
            TvChannel(3, "NPO 3", C_NPO),
            TvChannel(4, "RTL 4", C_RTL),
            TvChannel(5, "RTL 5", C_RTL),
            TvChannel(6, "SBS6", C_TALPA),
            TvChannel(7, "RTL 7", C_RTL),
            TvChannel(8, "NET5", C_TALPA),
            TvChannel(9, "Veronica", C_TALPA),
            TvChannel(10, "RTL 8", C_RTL),
            TvChannel(11, "RTL Z", C_RTL),
            TvChannel(12, "Comedy Central", C_ETC),
            TvChannel(13, "Paramount", C_ETC),
            TvChannel(15, "Discovery", C_ETC),
            TvChannel(16, "STAR Channel", C_ETC),
            TvChannel(17, "TLC", C_ETC),
            TvChannel(18, "National Geographic", C_ETC),
            TvChannel(20, "MTV", C_ETC),
            TvChannel(21, "ID", C_ETC),
            TvChannel(24, "24Kitchen", C_ETC),
            TvChannel(28, "Disney Channel", C_ETC),
            TvChannel(33, "Nickelodeon", C_ETC),
            TvChannel(35, "Eurosport 1", C_SPORT),
            TvChannel(36, "Eurosport 2", C_SPORT),
            TvChannel(43, "HGTV", C_ETC),
            TvChannel(51, "Viaplay TV", C_ETC),
        )
        private const val KEY_LAST_CHANNEL = "last_tv_channel"

        /** App-tegels: naam, pakketnaam op de Google TV, kleur van de tegel. */
        val MEDIA_APPS = listOf(
            MediaApp("Netflix", "com.netflix.ninja", 0xFF000000.toInt(), "netflix://", logo = R.drawable.logo_netflix),
            MediaApp("Prime Video", "com.amazon.amazonvideo.livingroom", 0xFF0F171E.toInt(), "https://app.primevideo.com", logo = R.drawable.logo_prime),
            MediaApp("YouTube", "com.google.android.youtube.tv", 0xFFFFFFFF.toInt(), "https://www.youtube.com", logo = R.drawable.logo_youtube),
            MediaApp("NLziet", "nl.nlziet", 0xFF000000.toInt(), scheme = "nlziet://", logo = R.drawable.logo_nlziet),
            MediaApp("Viaplay", "com.viaplay.android", 0xFF1D1D27.toInt(), scheme = "viaplay://", logo = R.drawable.logo_viaplay, alt = listOf("https://viaplay.nl/", "https://viaplay.com/")),
            MediaApp("F1 TV", "com.formulaone.production", 0xFFCE2C1D.toInt(), scheme = "f1tv://", logo = R.drawable.logo_f1tv, alt = listOf("https://f1tv.formula1.com/")),
            MediaApp("Kodi", "org.xbmc.kodi", 0xFF121212.toInt(), logo = R.drawable.logo_kodi),
        )

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
        private const val KEY_GH_TOKEN = "gh_update_token"
        private const val KEY_AUTO_UPDATE = "auto_update"

        /** GitHub-repository waar de nieuwe versies (Releases) staan. */
        private const val UPDATE_REPO = "eejee79/thuisapp"
        private const val ACTION_INSTALL_DONE = "nl.ejvegt.hawerkplek.INSTALL_DONE"

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
    private lateinit var mediaPanel: View
    private lateinit var tabMedia: View
    private lateinit var mediaList: LinearLayout
    private lateinit var mediaStatus: TextView
    private lateinit var settingsTitle: TextView
    private lateinit var settingsMenu: View
    private lateinit var apiMenu: View
    private lateinit var haSection: View
    private lateinit var pveSection: View
    private lateinit var fritzSection: View
    private lateinit var spotifySection: View
    private lateinit var spClientIdInput: EditText
    private lateinit var spStatus: TextView
    private val spotify by lazy { SpotifyApi(this) }
    private lateinit var musicSection: View
    private lateinit var smbPathInput: EditText
    private lateinit var smbUserInput: EditText
    private lateinit var smbPassInput: EditText
    private lateinit var smbStatus: TextView
    private val smb by lazy { SmbMusic() }
    private lateinit var updateSection: View
    private lateinit var updateVersion: TextView
    private lateinit var ghTokenInput: EditText
    private lateinit var autoUpdateSwitch: Switch
    private lateinit var updateStatus: TextView
    private lateinit var updateBanner: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var currentTab = 0

    @Volatile private var haBusy = false
    @Volatile private var pveBusy = false

    /** Elke minuut kijken of er een nieuwe versie op GitHub staat (alleen als de app open is). */
    private val updatePoller = object : Runnable {
        override fun run() {
            if (prefs().getBoolean(KEY_AUTO_UPDATE, true) && ghToken().isNotEmpty() && !downloadBusy) {
                checkForUpdate(manual = false)
            }
            handler.postDelayed(this, 60_000L)
        }
    }

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
        mediaPanel = findViewById(R.id.mediaPanel)
        tabMedia = findViewById(R.id.tabMedia)
        mediaList = findViewById(R.id.mediaList)
        mediaStatus = findViewById(R.id.mediaStatus)
        settingsTitle = findViewById(R.id.settingsTitle)
        settingsMenu = findViewById(R.id.settingsMenu)
        apiMenu = findViewById(R.id.apiMenu)
        haSection = findViewById(R.id.haSection)
        pveSection = findViewById(R.id.pveSection)
        fritzSection = findViewById(R.id.fritzSection)
        spotifySection = findViewById(R.id.spotifySection)
        spClientIdInput = findViewById(R.id.spClientIdInput)
        spStatus = findViewById(R.id.spStatus)
        musicSection = findViewById(R.id.musicSection)
        smbPathInput = findViewById(R.id.smbPathInput)
        smbUserInput = findViewById(R.id.smbUserInput)
        smbPassInput = findViewById(R.id.smbPassInput)
        smbStatus = findViewById(R.id.smbStatus)
        updateSection = findViewById(R.id.updateSection)
        updateVersion = findViewById(R.id.updateVersion)
        ghTokenInput = findViewById(R.id.ghTokenInput)
        autoUpdateSwitch = findViewById(R.id.autoUpdateSwitch)
        updateStatus = findViewById(R.id.updateStatus)
        updateBanner = findViewById(R.id.updateBanner)

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
        tabMedia.setOnClickListener { selectTab(TAB_MEDIA) }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings(true) }
        findViewById<View>(R.id.powerButton).setOnClickListener { confirmAllOff() }
        listOf(R.id.saveHa, R.id.savePve, R.id.saveFritz).forEach { id ->
            findViewById<Button>(id).setOnClickListener { saveSettings() }
        }
        menuItem(R.id.menuApi, R.drawable.ic_key, PAGE_API)
        menuItem(R.id.menuHa, R.drawable.ic_tab_ha, PAGE_HA)
        menuItem(R.id.menuPve, R.drawable.ic_tab_pve, PAGE_PVE)
        menuItem(R.id.menuFritz, R.drawable.ic_tab_fritz, PAGE_FRITZ)
        menuItem(R.id.menuSpotify, R.drawable.ic_music, PAGE_SPOTIFY)
        menuItem(R.id.menuMusic, R.drawable.ic_folder_music, PAGE_MUSIC)
        findViewById<Button>(R.id.saveMusic).setOnClickListener { saveMusicSettings() }
        findViewById<Button>(R.id.spLoginButton).setOnClickListener { spotifyLogin() }
        findViewById<Button>(R.id.spLogoutButton).setOnClickListener {
            spotify.logout()
            spStatus.text = "Uitgelogd."
        }
        menuItem(R.id.menuUpdate, R.drawable.ic_update, PAGE_UPDATE)
        findViewById<Button>(R.id.updateSaveButton).setOnClickListener { saveUpdateSettings() }
        findViewById<Button>(R.id.checkUpdateButton).setOnClickListener {
            saveUpdateSettings(close = false)
            checkForUpdate(manual = true)
        }
        updateBanner.setOnClickListener { pendingRelease?.let { startUpdate(it) } }

        buildFritzRows()
        buildDashboard()
        buildMedia()
        buildGroupCards()
        selectTab(prefs().getInt(KEY_TAB, 0))

        // Meldingen voor nieuwe versies (achtergrond) + toestemming voor meldingen (Android 13+)
        UpdateCheckJob.ensureChannel(this)
        UpdateCheckJob.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleInstallIntent(intent)
        handleSpotifyCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallIntent(intent)
        handleSpotifyCallback(intent)
    }

    /** Geopend via de update-melding: meteen controleren en bijwerken. */
    private fun handleInstallIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateCheckJob.EXTRA_INSTALL, false) == true) {
            intent.removeExtra(UpdateCheckJob.EXTRA_INSTALL)
            installWhenFound = true
            checkForUpdate(manual = false)
        }
    }

    override fun onDestroy() {
        installReceiver?.let { runCatching { unregisterReceiver(it) } }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        handler.post(poller)
        handler.post(updatePoller)
        handler.post(receiverPoller)
    }

    // ================= Volumetoetsen → receiver =================
    // Zolang de app open is regelen de volumetoetsen van de telefoon de Denon (als die aan staat).

    @Volatile private var recOn: Boolean? = null
    @Volatile private var recVol: Double? = null
    private var volPendingUntil = 0L
    private var volToast: Toast? = null

    /** Stand van de receiver bijhouden (niet overschrijven vlak na een eigen wijziging). */
    private fun noteReceiver(on: Boolean, vol: Double) {
        recOn = on
        if (!vol.isNaN() && System.currentTimeMillis() > volPendingUntil) recVol = vol
    }

    private val receiverPoller = object : Runnable {
        override fun run() {
            thread {
                val o = runCatching { JSONObject(haRequest("GET", "/api/states/$DASH_RECEIVER", null)) }.getOrNull()
                if (o != null) {
                    val st = o.optString("state")
                    val on = st != "off" && st != "standby" && st != "unavailable"
                    noteReceiver(on, o.optJSONObject("attributes")?.optDouble("volume_level", Double.NaN) ?: Double.NaN)
                }
            }
            handler.postDelayed(this, 15_000L)
        }
    }

    private val sendVolume = Runnable {
        val v = recVol ?: return@Runnable
        thread {
            quietService("media_player/volume_set", JSONObject().put("entity_id", DASH_RECEIVER).put("volume_level", v))
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val down = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if ((up || down) && settingsPanel.visibility != View.VISIBLE && recOn == true && recVol != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val v = ((recVol ?: 0.0) + if (up) 0.02 else -0.02).coerceIn(0.0, 1.0)
                recVol = v
                volPendingUntil = System.currentTimeMillis() + 4_000
                volToast?.cancel()
                volToast = Toast.makeText(this, "Receiver: ${Math.round(v * 100)}%", Toast.LENGTH_SHORT).also { it.show() }
                // Even wachten zodat ingedrukt houden niet tientallen opdrachten stuurt
                handler.removeCallbacks(sendVolume)
                handler.postDelayed(sendVolume, 250)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
        handler.removeCallbacks(updatePoller)
        handler.removeCallbacks(receiverPoller)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settingsPanel.visibility == View.VISIBLE) {
            when (settingsPage) {
                PAGE_MENU -> showSettings(false)
                PAGE_HA, PAGE_PVE, PAGE_FRITZ, PAGE_SPOTIFY, PAGE_MUSIC -> showSettingsPage(PAGE_API)
                else -> showSettingsPage(PAGE_MENU)
            }
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ================= Tabbladen =================

    private fun selectTab(tab: Int) {
        currentTab = tab
        settingsPanel.visibility = View.GONE
        val panels = listOf(haPanel, pvePanel, fritzPanel, dashPanel, mediaPanel)
        val tabs = listOf(tabHa, tabPve, tabFritz, tabDash, tabMedia)
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
            TAB_MEDIA -> refreshMedia()
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
    private fun ghToken() = setting(KEY_GH_TOKEN, BuildConfig.GH_UPDATE_TOKEN)

    private val PAGE_MENU = 0
    private val PAGE_HA = 1
    private val PAGE_PVE = 2
    private val PAGE_FRITZ = 3
    private val PAGE_UPDATE = 4
    private val PAGE_API = 5
    private val PAGE_SPOTIFY = 6
    private val PAGE_MUSIC = 7
    private var settingsPage = PAGE_MENU

    /** Instellingen: menu, of één van de twee onderdelen. */
    private fun showSettingsPage(page: Int) {
        settingsPage = page
        settingsMenu.visibility = if (page == PAGE_MENU) View.VISIBLE else View.GONE
        apiMenu.visibility = if (page == PAGE_API) View.VISIBLE else View.GONE
        haSection.visibility = if (page == PAGE_HA) View.VISIBLE else View.GONE
        pveSection.visibility = if (page == PAGE_PVE) View.VISIBLE else View.GONE
        fritzSection.visibility = if (page == PAGE_FRITZ) View.VISIBLE else View.GONE
        spotifySection.visibility = if (page == PAGE_SPOTIFY) View.VISIBLE else View.GONE
        musicSection.visibility = if (page == PAGE_MUSIC) View.VISIBLE else View.GONE
        updateSection.visibility = if (page == PAGE_UPDATE) View.VISIBLE else View.GONE
        settingsTitle.text = when (page) {
            PAGE_API -> "API's"
            PAGE_HA -> "Home Assistant"
            PAGE_PVE -> "Proxmox"
            PAGE_FRITZ -> "Fritzbox"
            PAGE_SPOTIFY -> "Spotify"
            PAGE_MUSIC -> "Muziek (netwerkmap)"
            PAGE_UPDATE -> "Updates"
            else -> "Instellingen"
        }
        if (page == PAGE_MUSIC) {
            smbPathInput.setText(smbPath())
            smbUserInput.setText(prefs().getString(KEY_SMB_USER, SMB_DEFAULT_USER))
            smbPassInput.setText(prefs().getString(KEY_SMB_PASS, ""))
            smbStatus.text = ""
        }
        if (page == PAGE_SPOTIFY) {
            spClientIdInput.setText(spotify.clientId())
            spStatus.text = if (spotify.isLoggedIn()) "Ingelogd ✓" else "Nog niet ingelogd."
        }
        if (page == PAGE_UPDATE) {
            updateVersion.text = "Geïnstalleerd: versie ${BuildConfig.VERSION_NAME}"
            ghTokenInput.setText(ghToken())
            autoUpdateSwitch.isChecked = prefs().getBoolean(KEY_AUTO_UPDATE, true)
            if (updateStatus.text.isEmpty()) {
                updateStatus.text = if (ghToken().isEmpty()) "Vul eerst het GitHub-token in." else ""
            }
        }
    }

    /** Menu-item met het logo van het tabblad ervoor (hoogte 28dp, breedte naar verhouding). */
    private fun menuItem(viewId: Int, iconRes: Int, page: Int) {
        val item = findViewById<TextView>(viewId)
        getDrawable(iconRes)?.let { d ->
            val h = dp(28)
            val w = if (d.intrinsicHeight > 0) h * d.intrinsicWidth / d.intrinsicHeight else h
            d.setBounds(0, 0, w, h)
            item.setCompoundDrawablesRelative(d, null, null, null)
        }
        item.setOnClickListener { showSettingsPage(page) }
    }

    private fun saveUpdateSettings(close: Boolean = true) {
        prefs().edit()
            .putString(KEY_GH_TOKEN, ghTokenInput.text.toString().trim())
            .putBoolean(KEY_AUTO_UPDATE, autoUpdateSwitch.isChecked)
            .apply()
        if (close) showSettingsPage(PAGE_MENU)
    }

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
            showSettingsPage(PAGE_MENU)
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
        showSettingsPage(PAGE_API)
        refreshCurrentTab(force = true)
    }

    // ================= Updates via GitHub Releases =================
    // Haalt de nieuwste release op, downloadt de APK en laat Android hem installeren.

    private class Release(val version: String, val tag: String, val apiUrl: String, val size: Long)

    @Volatile private var pendingRelease: Release? = null
    @Volatile private var updateBusy = false
    @Volatile private var downloadBusy = false
    @Volatile private var installWhenFound = false
    private var installReceiver: BroadcastReceiver? = null

    /** "1.15" > "1.9": per onderdeel numeriek vergelijken. */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun githubGet(url: String, accept: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("Authorization", "Bearer ${ghToken()}")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "thuisapp")
        return conn
    }

    private fun setUpdateStatus(text: String) = runOnUiThread { updateStatus.text = text }

    private fun checkForUpdate(manual: Boolean) {
        if (ghToken().isEmpty()) {
            if (manual) setUpdateStatus("Vul eerst het GitHub-token in.")
            return
        }
        if (updateBusy) return
        updateBusy = true
        if (manual) setUpdateStatus("Controleren…")
        thread {
            try {
                val conn = githubGet("https://api.github.com/repos/$UPDATE_REPO/releases/latest", "application/vnd.github+json")
                val code = conn.responseCode
                if (code != 200) {
                    val msg = when (code) {
                        401 -> "GitHub-token ongeldig of verlopen."
                        403 -> "GitHub weigert toegang (rechten van het token?)."
                        404 -> "Geen release gevonden, of het token heeft geen toegang tot $UPDATE_REPO."
                        else -> "GitHub gaf fout $code."
                    }
                    if (manual) setUpdateStatus(msg)
                    return@thread
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name")
                val version = tag.removePrefix("v").substringBefore('-')
                val assets = json.optJSONArray("assets") ?: JSONArray()
                var apk: JSONObject? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { apk = a; break }
                }
                if (!isNewer(version, BuildConfig.VERSION_NAME)) {
                    pendingRelease = null
                    installWhenFound = false
                    runOnUiThread { updateBanner.visibility = View.GONE }
                    if (manual) setUpdateStatus("Je hebt de nieuwste versie (${BuildConfig.VERSION_NAME}).")
                    return@thread
                }
                if (apk == null) {
                    if (manual) setUpdateStatus("Versie $version gevonden, maar zonder APK.")
                    return@thread
                }
                val rel = Release(version, tag, apk.optString("url"), apk.optLong("size"))
                pendingRelease = rel
                runOnUiThread {
                    updateBanner.text = "⬆  Versie $version beschikbaar – tik om bij te werken"
                    updateBanner.visibility = View.VISIBLE
                    updateStatus.text = "Versie $version beschikbaar."
                    if (manual || installWhenFound) {
                        installWhenFound = false
                        startUpdate(rel)
                    }
                }
            } catch (e: Exception) {
                if (manual) setUpdateStatus("Kan GitHub niet bereiken: ${e.message}")
            } finally {
                updateBusy = false
            }
        }
    }

    private fun startUpdate(rel: Release) {
        // Android vraagt eenmalig toestemming om apps te installeren vanuit deze app
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Toestemming nodig")
                .setMessage("Sta in het volgende scherm 'Deze bron toestaan' aan, ga terug en tik nogmaals op bijwerken.")
                .setPositiveButton("Openen") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("Annuleren", null)
                .show()
            return
        }
        if (downloadBusy) return
        downloadBusy = true
        runOnUiThread {
            updateBanner.text = "Versie ${rel.version} downloaden…"
            updateStatus.text = "Versie ${rel.version} downloaden…"
        }
        thread {
            try {
                val file = downloadApk(rel)
                runOnUiThread {
                    updateBanner.text = "Versie ${rel.version} installeren…"
                    updateStatus.text = "Installeren…"
                }
                installApk(file)
            } catch (e: Exception) {
                runOnUiThread {
                    updateBanner.text = "⬆  Bijwerken mislukt – tik om opnieuw te proberen"
                    updateStatus.text = "Bijwerken mislukt: ${e.message}"
                }
            } finally {
                downloadBusy = false
            }
        }
    }

    /** Download via de GitHub-API; die verwijst door naar een tijdelijke link zonder token. */
    private fun downloadApk(rel: Release): File {
        var conn = githubGet(rel.apiUrl, "application/octet-stream")
        var code = conn.responseCode
        if (code in 300..399) {
            val location = conn.getHeaderField("Location") ?: throw IOException("Geen downloadlink")
            conn.disconnect()
            conn = URL(location).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "thuisapp")
            code = conn.responseCode
        }
        if (code != 200) throw IOException("download gaf fout $code")
        val file = File(cacheDir, "update.apk")
        var done = 0L
        var lastShown = 0L
        conn.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (rel.size > 0 && done - lastShown > 512 * 1024) {
                        lastShown = done
                        val pct = (done * 100 / rel.size).toInt()
                        runOnUiThread { updateBanner.text = "Versie ${rel.version} downloaden… $pct%" }
                    }
                }
            }
        }
        if (rel.size > 0 && file.length() != rel.size) throw IOException("download onvolledig")
        return file
    }

    private fun installApk(file: File) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("thuisapp.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            registerInstallReceiver()
            val intent = Intent(ACTION_INSTALL_DONE).setPackage(packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(this, sessionId, intent, flags)
            session.commit(pi.intentSender)
        }
    }

    private fun registerInstallReceiver() {
        if (installReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (confirm != null) startActivity(confirm)
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        updateStatus.text = "Bijgewerkt."
                    }
                    else -> {
                        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "onbekende fout"
                        updateBanner.text = "⬆  Installeren mislukt – tik om opnieuw te proberen"
                        updateStatus.text = "Installeren mislukt: $msg"
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_INSTALL_DONE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        installReceiver = receiver
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
        receiverSwitch = smallSwitch { on ->
            haService("media_player", if (on) "turn_on" else "turn_off", JSONObject().put("entity_id", DASH_RECEIVER))
        }
        receiverCard.header.addView(receiverSwitch)
        volumeSlider = DashSlider(receiverCard.addSeek(), receiverCard.sub, { "Volume ${it.toInt()}%" }) { v ->
            haService(
                "media_player", "volume_set",
                JSONObject().put("entity_id", DASH_RECEIVER).put("volume_level", v / 100.0)
            )
        }
        volumeSlider.setRange(0.0, 100.0, 1.0)
        // Receiver-blok staat niet meer op het Dashboard: bron via Multimedia, volume via de volumeknoppen

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
            volumeSlider.seek.isEnabled = false
            return
        }
        val a = o.optJSONObject("attributes") ?: JSONObject()
        val state = o.optString("state")
        val on = state != "off" && state != "standby" && state != "unavailable"
        val src = a.optString("source", "")
        receiverCard.big.text = if (on && src.isNotEmpty()) sourceLabel(src) else a.optString("friendly_name", "Receiver")
        receiverSwitch.isEnabled = state != "unavailable"
        receiverSwitch.isChecked = on
        volumeSlider.seek.isEnabled = on
        val vol = a.optDouble("volume_level", Double.NaN)
        noteReceiver(on, vol)
        if (on && !vol.isNaN()) {
            volumeSlider.show(vol * 100)
            if (a.optBoolean("is_volume_muted", false)) receiverCard.sub.append("  ·  gedempt")
        } else {
            volumeSlider.show(null)
            receiverCard.sub.text = if (on) "Volume —" else "Uit"
        }
    }

    /**
     * Start de HEOS-favoriet met [HEOS_RADIO] in de naam (JOE Nederland). HEOS hervat bij het
     * omschakelen eerst de vorige bron (bijv. Spotify), dus even wachten, starten en controleren.
     */
    private fun playHeosRadio(match: String = HEOS_RADIO, label: String = "JOE", status: TextView = dashStatus) {
        runOnUiThread { status.text = "$label starten…" }
        thread {
            Thread.sleep(4_000)  // receiver laten omschakelen en HEOS laten hervatten
            val list = stateOf(DASH_HEOS)?.optJSONObject("attributes")?.optJSONArray("source_list")
            val names = list?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
            val fav = names.firstOrNull { it.contains(match, ignoreCase = true) }
            if (fav == null) {
                runOnUiThread {
                    status.text = if (names.isEmpty()) "Geen HEOS-favorieten gevonden op $DASH_HEOS."
                    else "$label niet in je HEOS-favorieten (herlaad de HEOS-integratie). Wel: " + names.take(6).joinToString(", ")
                }
                return@thread
            }
            var ok = false
            for (attempt in 0 until 3) {
                quietService("media_player/select_source", JSONObject().put("entity_id", DASH_HEOS).put("source", fav))
                Thread.sleep(3_000)
                val a = stateOf(DASH_HEOS)?.optJSONObject("attributes")
                val now = listOf("source", "media_title", "media_artist", "media_album_name", "media_station")
                    .joinToString(" ") { a?.optString(it).orEmpty() }
                if (now.contains(match, ignoreCase = true)) { ok = true; break }
            }
            runOnUiThread {
                status.text = if (ok) "$fav wordt afgespeeld" else "$label kon niet worden gestart (HEOS speelt iets anders)"
            }
        }
    }

    /** Naam zoals de app hem toont (bijv. "AUX1" → "Chromecast Audio"). */
    private fun sourceLabel(source: String) =
        RECEIVER_SOURCES.firstOrNull { it.first == source }?.second ?: source

    private fun chooseSource() {
        // Alleen de gekozen bronnen, en alleen als de receiver ze ook echt kent
        val attrs = recState?.optJSONObject("attributes") ?: dashAttrs[DASH_RECEIVER]
        val known = attrs?.optJSONArray("source_list")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() }
        val options = RECEIVER_SOURCES.filter { known == null || it.first in known }
        if (options.isEmpty()) {
            mediaStatus.text = "Geen van de ingestelde bronnen gevonden op de receiver."
            return
        }
        val current = options.indexOfFirst { it.first == attrs?.optString("source") }
        AlertDialog.Builder(this)
            .setTitle("Bron kiezen")
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), current) { d, which ->
                d.dismiss()
                val (denonName, label) = options[which]
                nowTitle.text = "$label ▾"
                mediaStatus.text = "Bron: $label"
                haService(
                    "media_player", "select_source",
                    JSONObject().put("entity_id", DASH_RECEIVER).put("source", denonName)
                )
                if (denonName == "HEOS Music") playHeosRadio(status = mediaStatus)
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
                val status = if (currentTab == TAB_MEDIA) mediaStatus else dashStatus
                if (err != null) status.text = "Mislukt: ${err.message ?: err.javaClass.simpleName}"
                handler.postDelayed({ if (currentTab == TAB_DASH || currentTab == TAB_MEDIA) refreshCurrentTab(force = true) }, 1_500)
            }
        }
    }

    private fun fmtTemp(v: Double) = String.format(NL, if (v % 1.0 == 0.0) "%.0f °C" else "%.1f °C", v)

    private fun fmtNumber(v: Double, decimals: Int): String =
        java.text.NumberFormat.getNumberInstance(NL).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }.format(v)

    // ================= Multimedia (Google TV-box) =================
    // Apps starten via de Android TV Remote-integratie: remote.turn_on met activity = pakketnaam.
    // De box gaat daarbij vanzelf aan.

    // Informatiebalk bovenaan: welk apparaat er via de Denon speelt en wat er te zien/horen is
    private lateinit var nowBar: LinearLayout
    private lateinit var nowIcon: ImageView
    private lateinit var nowTitle: TextView
    private lateinit var nowSub: TextView
    private lateinit var nowPlayPause: Button
    @Volatile private var nowEntity: String? = null
    /** Laatst opgehaalde stand van de receiver (voor de bronkeuze). */
    @Volatile private var recState: JSONObject? = null
    private lateinit var nowChannelBtn: TextView
    private lateinit var nowRemoteBtn: ImageView
    private lateinit var nowKeyboardBtn: ImageView
    @Volatile private var nowRemote: String? = null
    @Volatile private var mediaBusy = false

    /** Wat de balk toont. [entity] = mediaspeler voor de pauzeknop (null = geen knop). */
    private class NowInfo(
        val title: String, val sub: String, val icon: Int?, val iconBg: Int,
        val entity: String?, val playing: Boolean, val tv: Boolean = false,
        /** Remote-entiteit voor de afstandsbediening (null = geen knop). */
        val remote: String? = null,
    )

    private fun buildMedia() {
        nowBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            visibility = View.GONE
        }
        nowIcon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(35)).apply { marginEnd = dp(12) }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        nowTitle = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        nowSub = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFB0B0B0.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        texts.addView(nowTitle)
        texts.addView(nowSub)
        // Tik op de naam of het logo: bron van de receiver kiezen
        texts.isClickable = true
        texts.setOnClickListener { chooseSource() }
        nowIcon.setOnClickListener { chooseSource() }
        nowPlayPause = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                val e = nowEntity ?: return@setOnClickListener
                text = if (text.toString() == "⏸") "▶" else "⏸"
                if (e == "local") toggleLocal()
                else haService("media_player", "media_play_pause", JSONObject().put("entity_id", e))
            }
        }
        nowBar.addView(nowIcon)
        nowBar.addView(texts)
        nowChannelBtn = TextView(this).apply {
            text = "Zender ▾"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                setColor(0xFF2E7D32.toInt())
                cornerRadius = dp(16).toFloat()
            }
            visibility = View.GONE
            setOnClickListener { showChannelSheet() }
        }
        nowRemoteBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_remote)
            contentDescription = "Afstandsbediening"
            setPadding(dp(10), dp(6), dp(6), dp(6))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { nowRemote?.let { showRemoteSheet(it, it == TVPLUS_REMOTE) } }
        }
        nowKeyboardBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_keyboard)
            contentDescription = "Tekst typen"
            setPadding(dp(10), dp(6), dp(6), dp(6))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { typeOnTv() }
        }
        nowBar.addView(nowChannelBtn)
        nowBar.addView(nowKeyboardBtn)
        nowBar.addView(nowRemoteBtn)
        nowBar.addView(nowPlayPause)
        mediaList.addView(nowBar)

        val tiles = listOf<(Boolean) -> View>({ second -> watchTvTile(second) }, { second -> heosTile(second) }, { second -> spotifyTile(second) }, { second -> musicTile(second) }) +
            MEDIA_APPS.map { app -> { second: Boolean -> appTile(app, second) } }
        tiles.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
            }
            pair.forEachIndexed { i, make -> row.addView(make(i == 1)) }
            if (pair.size == 1) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(100), 1f).apply { marginStart = dp(12) }
                })
            }
            mediaList.addView(row)
        }
    }

    private fun appTile(app: MediaApp, second: Boolean): View {
        val tile: View = if (app.logo != null) {
            ImageView(this).apply {
                setImageResource(app.logo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = app.name
                clipToOutline = true  // ronde hoeken van de achtergrond ook voor het logo
            }
        } else {
            TextView(this).apply {
                text = app.name
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
        }
        return tile.apply {
            setupTile(app.color, second) { launchApp(app) }
            setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(app.name)
                    .setMessage("Pakketnaam: ${app.pkg}" + (app.link?.let { "\nDeeplink: $it" } ?: ""))
                    .setPositiveButton("OK", null)
                    .show()
                true
            }
        }
    }

    /** Tegel "TV kijken": tv en receiver aan, receiver op TV+ woonkamer. */
    private fun watchTvTile(second: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_tv_watch)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        })
        addView(TextView(this@MainActivity).apply {
            text = "TV kijken"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        setupTile(0xFF1565C0.toInt(), second) { showChannelSheet() }
    }

    /** Tegel "HEOS radio": kies een zender; receiver gaat aan op HEOS Music en speelt hem af. */
    private fun heosTile(second: Boolean): View = ImageView(this).apply {
        setImageResource(R.drawable.logo_heos)
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "HEOS radio"
        clipToOutline = true
        setupTile(0xFF000000.toInt(), second) { chooseStation() }
    }

    private fun chooseStation() {
        AlertDialog.Builder(this)
            .setTitle("Zender kiezen")
            .setItems(HEOS_STATIONS.map { it.first }.toTypedArray()) { _, which ->
                val (name, match) = HEOS_STATIONS[which]
                mediaStatus.text = "$name: receiver aanzetten…"
                thread {
                    prepareReceiver("HEOS Music")
                    playHeosRadio(match, name, mediaStatus)
                }
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    // ================= Muziek van de netwerkmap (SMB) =================

    private fun smbPath() = prefs().getString(KEY_SMB_PATH, null)?.ifBlank { null } ?: SMB_DEFAULT_PATH

    /** Instellingen doorgeven aan de SMB-verbinding; geeft de map binnen de share terug. */
    private fun smbConfigure(): String? {
        val (host, share, base) = SmbMusic.parseUnc(smbPath()) ?: return null
        smb.configure(
            host, share,
            prefs().getString(KEY_SMB_USER, SMB_DEFAULT_USER) ?: SMB_DEFAULT_USER,
            prefs().getString(KEY_SMB_PASS, "") ?: "",
        )
        return base
    }

    private fun saveMusicSettings() {
        prefs().edit()
            .putString(KEY_SMB_PATH, smbPathInput.text.toString().trim())
            .putString(KEY_SMB_USER, smbUserInput.text.toString().trim())
            .putString(KEY_SMB_PASS, smbPassInput.text.toString())
            .apply()
        smbStatus.text = "Verbinden…"
        thread {
            val r = runCatching {
                val base = smbConfigure() ?: throw IllegalArgumentException("Netwerkmap klopt niet, bijv. \\\\server\\share\\map")
                smb.list(base).size
            }
            runOnUiThread {
                smbStatus.text = r.exceptionOrNull()?.let { "Lukt niet: ${it.message ?: it.javaClass.simpleName}" }
                    ?: "Verbonden ✓  ${r.getOrNull()} mappen/nummers gevonden."
            }
        }
    }

    private fun musicTile(second: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_folder_music)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        })
        addView(TextView(this@MainActivity).apply {
            text = "Mijn muziek"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        setupTile(0xFF6D4C41.toInt(), second) { showMusicSheet() }
        // Lang indrukken: zoeken in Music Assistant (op tags: artiest/album)
        setOnLongClickListener { showMusicSearch(maSource); true }
    }

    /** Bladeren door de netwerkmap: mappen openen, op een nummer tikken speelt de map vanaf daar. */
    private fun showMusicSheet() {
        if ((prefs().getString(KEY_SMB_PASS, "") ?: "").isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Mijn muziek")
                .setMessage("Vul eerst het wachtwoord van de netwerkmap in via ⚙ → API's → Muziek (netwerkmap).")
                .setPositiveButton("Naar instellingen") { _, _ -> showSettings(true); showSettingsPage(PAGE_MUSIC) }
                .setNegativeButton("Annuleren", null)
                .show()
            return
        }
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val title = TextView(this).apply {
            text = "Mijn muziek"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title)
        content.addView(status)
        val scroll = ScrollView(this).apply {
            addView(list)
            isScrollbarFadingEnabled = false
            if (Build.VERSION.SDK_INT >= 29) {
                // Bredere, beter zichtbare schuifbalk
                scrollBarSize = dp(8)
                verticalScrollbarThumbDrawable = GradientDrawable().apply {
                    setColor(0xFF8D8D8D.toInt()); cornerRadius = dp(4).toFloat()
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        // Rechts een letterbalk: tik of sleep naar een letter om er direct heen te springen
        val letters = listOf("#") + ('A'..'Z').map { it.toString() }
        val firstRow = HashMap<String, View>()
        val bubble = TextView(this).apply {
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0xCC333333.toInt()); cornerRadius = dp(14).toFloat() }
            visibility = View.GONE
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(4) }
            background = GradientDrawable().apply { setColor(0xFF262626.toInt()); cornerRadius = dp(14).toFloat() }
            letters.forEach { l ->
                addView(TextView(this@MainActivity).apply {
                    text = l
                    textSize = 10f
                    setTextColor(0xFFBBBBBB.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                })
            }
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                        val i = (ev.y / v.height * letters.size).toInt().coerceIn(0, letters.size - 1)
                        // Deze letter, of anders de eerstvolgende die wel voorkomt
                        val target = letters.drop(i).firstNotNullOfOrNull { firstRow[it] }
                            ?: letters.take(i).reversed().firstNotNullOfOrNull { firstRow[it] }
                        target?.let { scroll.scrollTo(0, it.top) }
                        bubble.text = letters[i]
                        bubble.visibility = View.VISIBLE
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        bubble.postDelayed({ bubble.visibility = View.GONE }, 400)
                        true
                    }
                    else -> false
                }
            }
        }
        val listArea = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(460))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(scroll)
                addView(strip)
            }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(bubble, android.widget.FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        }
        content.addView(listArea)

        fun row(icon: String, text: String, accent: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
            this.text = "$icon   $text"
            textSize = 15f
            setTextColor(if (accent) Color.BLACK else Color.WHITE)
            if (accent) setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply {
                setColor(if (accent) 0xFFFFB300.toInt() else 0xFF2A2A2A.toInt()); cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            isClickable = true
            setOnClickListener { onClick() }
        }

        var seq = 0
        // Scrollpositie per map onthouden, zodat "Omhoog" terugkomt op dezelfde plek
        val scrollPos = HashMap<String, Int>()
        var current = ""
        lateinit var open: (String) -> Unit
        open = { path ->
            scrollPos[current] = scroll.scrollY
            current = path
            val my = ++seq
            status.text = "Laden…"
            list.removeAllViews()
            firstRow.clear()
            thread {
                val base = smbConfigure()
                val r = runCatching { smb.list(path) }
                runOnUiThread {
                    if (my != seq) return@runOnUiThread
                    val entries = r.getOrNull()
                    title.text = if (path == base || path.isEmpty()) "Mijn muziek" else path.substringAfterLast('\\')
                    if (entries == null) {
                        status.text = "Netwerkmap niet bereikbaar: ${r.exceptionOrNull()?.message}"
                        return@runOnUiThread
                    }
                    if (path != base) {
                        val up = if (path.contains('\\')) path.substringBeforeLast('\\') else ""
                        list.addView(row("←", "Omhoog") { open(up) })
                    }
                    val tracks = entries.filter { !it.isDir }
                    status.text = "${entries.count { it.isDir }} mappen · ${tracks.size} nummers"
                    val y = scrollPos[path] ?: 0
                    scroll.post { scroll.scrollTo(0, y) }
                    if (tracks.isNotEmpty()) {
                        list.addView(row("▶", "Speel deze map af (${tracks.size} nummers)", accent = true) {
                            dialog.dismiss(); chooseMusicTarget(tracks, 0)
                        })
                    }
                    entries.forEach { e ->
                        val v = if (e.isDir) row("📁", e.name) { open(e.path) }
                        else row("🎵", e.name.substringBeforeLast('.')) {
                            dialog.dismiss(); chooseMusicTarget(tracks, tracks.indexOf(e))
                        }
                        list.addView(v)
                        val c = e.name.firstOrNull()?.uppercaseChar()
                        val key = if (c != null && c in 'A'..'Z') c.toString() else "#"
                        firstRow.putIfAbsent(key, v)
                    }
                }
            }
        }

        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            })
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        thread {
            val base = smbConfigure() ?: ""
            runOnUiThread { open(base) }
        }
    }

    /** Map afspelen via Music Assistant op een gekozen speler (mappen zoals op schijf, geen tags). */
    private fun chooseMusicTarget(tracks: List<SmbMusic.Entry>, startAt: Int) {
        val label = if (startAt == 0) tracks.firstOrNull()?.path?.substringBeforeLast('\\')?.substringAfterLast('\\') ?: "Map"
        else tracks[startAt].name.substringBeforeLast('.')
        chooseMaPlayer(label, phone = { playLocal(tracks, startAt) }) { entity, name ->
            playFolderOnMa(tracks.drop(startAt), entity, name)
        }
    }

    private fun playFolderOnMa(tracks: List<SmbMusic.Entry>, entity: String, name: String) {
        stopLocal()
        musicRun++   // oude wachtrij via de telefoon stoppen
        mediaStatus.text = "${tracks.size} nummers op $name…"
        thread {
            val n = name.lowercase(NL)
            when {
                "chromecast" in n || "audio woonkamer" in n -> prepareReceiver("AUX1")
                "googletv" in n || "google tv" in n || "tv in woonkamer" in n -> prepareWatching(SOURCE_APPS)
                "denon" in n || "heos" in n -> prepareReceiver("HEOS Music")
            }
            // Music Assistant leest dezelfde netwerkmap; het pad t.o.v. de muziekmap wijst het bestand aan
            val base = smbConfigure().orEmpty()
            fun rel(e: SmbMusic.Entry) =
                (if (base.isNotEmpty() && e.path.startsWith("$base\\")) e.path.removePrefix("$base\\") else e.path)
                    .replace('\\', '/')
            fun play(ids: List<String>) = haRequest(
                "POST", "/api/services/music_assistant/play_media",
                JSONObject().put("entity_id", entity).put("media_id", JSONArray(ids))
                    .put("media_type", "track").put("enqueue", "replace").toString(),
            )
            var how = ""
            val r = runCatching {
                try {
                    play(tracks.map { "filesystem_smb://track/" + rel(it) })
                } catch (e: Exception) {
                    // Reserve: de telefoon geeft de bestanden zelf door (alleen thuis, app open laten)
                    play(tracks.map { smb.urlFor(it.path) })
                    how = " (via de telefoon)"
                }
            }
            runOnUiThread {
                mediaStatus.text = r.exceptionOrNull()?.let { "Afspelen mislukt: ${it.message}" }
                    ?: "${tracks.size} nummers spelen op $name$how"
                handler.postDelayed({ refreshMedia() }, 2_000)
            }
        }
    }

    /** Oude manier: rechtstreeks casten vanaf de telefoon (niet meer standaard). */
    private fun chooseMusicTargetDirect(tracks: List<SmbMusic.Entry>, startAt: Int) {
        AlertDialog.Builder(this)
            .setTitle("Afspelen op")
            .setItems(MUSIC_TARGETS.map { it.first }.toTypedArray()) { _, i ->
                startMusicQueue(tracks, startAt, MUSIC_TARGETS[i].second, MUSIC_TARGETS[i].third)
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    // Wachtrij: de app speelt het volgende nummer af zodra de Chromecast klaar is met het vorige
    @Volatile private var musicQueue: List<SmbMusic.Entry> = emptyList()
    @Volatile private var musicIndex = 0
    @Volatile private var musicTarget = ""
    @Volatile private var musicUrl: String? = null
    @Volatile private var musicRun = 0

    private fun startMusicQueue(tracks: List<SmbMusic.Entry>, startAt: Int, entity: String, source: String) {
        musicQueue = tracks
        musicIndex = startAt
        musicTarget = entity
        val run = ++musicRun
        mediaStatus.text = "Muziek klaarzetten…"
        thread {
            if (source == "GoogleTV Box") prepareWatching(SOURCE_APPS) else prepareReceiver(source)
            playMusicTrack(run)
            // Volgen: is het nummer afgelopen (idle), dan het volgende
            var started = false
            while (run == musicRun) {
                Thread.sleep(2_500)
                val st = stateOf(musicTarget)
                val state = st?.optString("state")
                val id = attr(st, "media_content_id")
                if (state == "playing" || state == "paused" || state == "buffering") {
                    if (id != null && musicUrl != null && id != musicUrl) { musicRun++; break }  // iets anders gestart
                    started = true
                } else if (started && (state == "idle" || state == "off")) {
                    started = false
                    if (musicIndex + 1 < musicQueue.size) {
                        musicIndex++
                        playMusicTrack(run)
                    } else {
                        runOnUiThread { mediaStatus.text = "Klaar met afspelen" }
                        musicRun++
                    }
                }
            }
        }
    }

    private fun playMusicTrack(run: Int) {
        if (run != musicRun) return
        val e = musicQueue.getOrNull(musicIndex) ?: return
        val r = runCatching {
            val url = smb.urlFor(e.path)
            musicUrl = url
            quietService(
                "media_player/play_media",
                JSONObject().put("entity_id", musicTarget)
                    .put("media_content_id", url)
                    .put("media_content_type", SmbMusic.mimeOf(e.name) ?: "music")
                    .put("extra", JSONObject().put("metadata", JSONObject()
                        .put("metadataType", 3)
                        .put("title", e.name.substringBeforeLast('.'))
                        .put("albumName", e.path.substringBeforeLast('\\').substringAfterLast('\\'))))
            )
        }
        runOnUiThread {
            mediaStatus.text = r.exceptionOrNull()?.let { "Afspelen mislukt: ${it.message}" }
                ?: "🎵 ${e.name.substringBeforeLast('.')} (${musicIndex + 1}/${musicQueue.size})"
        }
    }

    // ================= Music Assistant (eigen muziek + Spotify, via Home Assistant) =================

    @Volatile private var maEntryId: String? = null

    /** De config-entry van Music Assistant (nodig voor zoeken/bibliotheek). */
    private fun maEntry(): String {
        maEntryId?.let { return it }
        val arr = JSONArray(haRequest("GET", "/api/config/config_entries/entry", null))
        val id = (0 until arr.length()).map { arr.getJSONObject(it) }
            .firstOrNull { it.optString("domain") == "music_assistant" }?.optString("entry_id")
            ?: throw IOException("Music Assistant-integratie niet gevonden in Home Assistant")
        maEntryId = id
        return id
    }

    /** Een Music Assistant-actie uitvoeren en het antwoord teruggeven. */
    private fun maCall(service: String, data: JSONObject): JSONObject {
        data.put("config_entry_id", maEntry())
        val r = JSONObject(haRequest("POST", "/api/services/music_assistant/$service?return_response", data.toString()))
        return r.optJSONObject("service_response") ?: JSONObject()
    }

    @Volatile private var maPlayerCache: List<Pair<String, String>> = emptyList()
    @Volatile private var maPlayerCacheAt = 0L

    /** Music Assistant-spelers (entity, naam), 10 minuten onthouden. */
    private fun maPlayers(): List<Pair<String, String>> {
        if (System.currentTimeMillis() - maPlayerCacheAt < 600_000) return maPlayerCache
        maPlayerCacheAt = System.currentTimeMillis()
        maPlayerCache = runCatching {
            val tpl = "{% for e in integration_entities('music_assistant') if e.startswith('media_player.') %}" +
                "{{ e }}|{{ state_attr(e, 'friendly_name') }}\n{% endfor %}"
            haRequest("POST", "/api/template", JSONObject().put("template", tpl).toString())
                .lines().filter { it.contains('|') }.map { it.substringBefore('|') to it.substringAfter('|') }
        }.getOrDefault(maPlayerCache)
        return maPlayerCache
    }

    private fun maItem(o: JSONObject): SpotifyApi.Item {
        val type = o.optString("media_type")
        val artists = o.optJSONArray("artists")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.joinToString(", ")
        }.orEmpty()
        val year = o.optInt("year", 0).takeIf { it > 0 }?.toString().orEmpty()
        val sub = when (type) {
            "artist" -> "Artiest"
            "album" -> listOf(year, o.optString("album_type").replaceFirstChar { it.uppercase() }.ifEmpty { "Album" }, artists)
                .filter { it.isNotEmpty() }.joinToString(" · ")
            "track" -> artists
            "radio" -> "Radio"
            else -> "Playlist"
        }
        val image = o.optString("image").takeIf { it.startsWith("http") }
        val albumType = o.optString("album_type")
        val itemType = if (type == "album" && (albumType == "single" || albumType == "ep")) "single" else type
        return SpotifyApi.Item(o.optString("name"), sub, o.optString("uri"), itemType, year, image)
    }

    private fun maList(resp: JSONObject, key: String): List<SpotifyApi.Item> {
        val arr = resp.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { maItem(it) }
    }

    private val maSource = object : MusicSource {
        override val title = "Mijn muziek"

        override fun search(q: String): List<SpotifyApi.Item> {
            val r = maCall("search", JSONObject().put("name", q).put("limit", 6))
            return maList(r, "artists") + maList(r, "albums") + maList(r, "tracks") +
                maList(r, "playlists") + maList(r, "radio")
        }

        private fun library(type: String, order: String, limit: Int): List<SpotifyApi.Item> {
            val r = maCall("get_library", JSONObject().put("media_type", type).put("order_by", order).put("limit", limit))
            return maList(r, "items")
        }

        override fun recent(): Pair<List<SpotifyApi.Item>, List<SpotifyApi.Item>> {
            fun lib(type: String, limit: Int) = runCatching { library(type, "last_played_desc", limit) }
                .getOrElse { library(type, "timestamp_added_desc", limit) }
            return lib("artist", 10) to lib("album", 5)
        }

        override fun artistReleases(artist: SpotifyApi.Item): List<SpotifyApi.Item> {
            val r = maCall(
                "search",
                JSONObject().put("name", artist.name).put("media_type", JSONArray().put("album")).put("limit", 50),
            )
            // Alleen albums waar deze artiest bij staat; nieuw → oud
            val arr = r.optJSONArray("albums") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { o ->
                    val a = o.optJSONArray("artists") ?: return@filter true
                    (0 until a.length()).any { a.optJSONObject(it)?.optString("name").equals(artist.name, true) }
                }
                .map { maItem(it) }
                .sortedByDescending { it.date }
                .distinctBy { it.type + it.name.lowercase() }
        }

        override fun artistPlaylists(artist: SpotifyApi.Item): List<SpotifyApi.Item> =
            maList(
                maCall("search", JSONObject().put("name", artist.name).put("media_type", JSONArray().put("playlist")).put("limit", 10)),
                "playlists",
            )

        override fun pick(item: SpotifyApi.Item) = chooseMaPlayer(item)
    }

    /** Kies een Music Assistant-speler (Chromecast, Denon/HEOS, GoogleTV, piCorePlayer…). */
    private fun chooseMaPlayer(item: SpotifyApi.Item) =
        chooseMaPlayer(item.name) { entity, name -> playOnMa(item, entity, name) }

    private fun chooseMaPlayer(label: String, phone: (() -> Unit)? = null, onChosen: (String, String) -> Unit) {
        mediaStatus.text = "Spelers zoeken…"
        thread {
            val r = runCatching {
                val tpl = "{% for e in integration_entities('music_assistant') if e.startswith('media_player.') %}" +
                    "{{ e }}|{{ state_attr(e, 'friendly_name') }}|{{ states(e) }}\n{% endfor %}"
                haRequest("POST", "/api/template", JSONObject().put("template", tpl).toString())
                    .lines().filter { it.contains('|') }
                    .map { it.split('|') }
                    .filter { it.getOrNull(2) != "unavailable" }
                    .map { Triple(it[0], it[1], it.getOrNull(2) ?: "") }
            }
            runOnUiThread {
                val players = r.getOrNull().orEmpty()
                if (players.isEmpty() && phone == null) {
                    mediaStatus.text = "Geen Music Assistant-spelers gevonden" +
                        (r.exceptionOrNull()?.message?.let { ": $it" } ?: "")
                    return@runOnUiThread
                }
                mediaStatus.text = ""
                val last = prefs().getString(KEY_MA_LAST_PLAYER, null)
                val sorted = players.sortedBy { if (it.first == last) 0 else 1 }
                val labels = (if (phone != null) listOf("📱  Deze telefoon") else emptyList()) +
                    sorted.map { it.second + if (it.third == "playing") "  (speelt nu)" else "" }
                val off = if (phone != null) 1 else 0
                AlertDialog.Builder(this)
                    .setTitle("$label afspelen op")
                    .setItems(labels.toTypedArray()) { _, i ->
                        if (phone != null && i == 0) { phone(); return@setItems }
                        val p = sorted[i - off]
                        prefs().edit().putString(KEY_MA_LAST_PLAYER, p.first).apply()
                        onChosen(p.first, p.second)
                    }
                    .setNegativeButton("Annuleren", null)
                    .show()
            }
        }
    }

    private fun playOnMa(item: SpotifyApi.Item, entity: String, name: String) {
        stopLocal()
        prefs().edit().putString(KEY_MA_LAST_PLAYER, entity).apply()
        musicRun++   // eventuele oude wachtrij van de netwerkmap stoppen
        mediaStatus.text = "${item.name} op $name…"
        thread {
            val n = name.lowercase(NL)
            when {
                "chromecast" in n || "audio woonkamer" in n -> prepareReceiver("AUX1")
                "googletv" in n || "google tv" in n || "tv in woonkamer" in n -> prepareWatching(SOURCE_APPS)
                "denon" in n || "heos" in n -> prepareReceiver("HEOS Music")
            }
            val type = if (item.type == "single") "album" else item.type
            val r = runCatching {
                haRequest(
                    "POST", "/api/services/music_assistant/play_media",
                    JSONObject().put("entity_id", entity).put("media_id", item.uri)
                        .put("media_type", type).put("enqueue", "replace").toString(),
                )
            }
            runOnUiThread {
                mediaStatus.text = r.exceptionOrNull()?.let { "Afspelen mislukt: ${it.message}" }
                    ?: "${item.name} speelt op $name"
                handler.postDelayed({ refreshMedia() }, 2_000)
            }
        }
    }

    // ================= Spotify (zoeken en afspelen op een apparaat) =================

    private fun spotifyLogin() {
        val id = spClientIdInput.text.toString().trim()
        prefs().edit().putString(SpotifyApi.KEY_CLIENT_ID, id).apply()
        if (id.isEmpty()) {
            spStatus.text = "Vul eerst de Client ID in."
            return
        }
        spStatus.text = "Inloggen in de browser…"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spotify.authorizeUrl())))
    }

    /** Terug uit de browser na inloggen: code omwisselen voor een token. */
    private fun handleSpotifyCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "thuisbasis" || data.host != "spotify-callback") return
        intent.data = null
        val code = data.getQueryParameter("code")
        showSettings(true)
        showSettingsPage(PAGE_SPOTIFY)
        if (code == null) {
            spStatus.text = "Inloggen afgebroken: ${data.getQueryParameter("error") ?: "onbekend"}"
            return
        }
        spStatus.text = "Bezig met inloggen…"
        thread {
            val r = runCatching { spotify.exchangeCode(code) }
            runOnUiThread {
                spStatus.text = r.exceptionOrNull()?.let { "Inloggen mislukt: ${it.message}" }
                    ?: "Ingelogd ✓  Je vindt Spotify nu bij Multimedia."
            }
        }
    }

    private fun spotifyTile(second: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_music)
            setColorFilter(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        })
        addView(TextView(this@MainActivity).apply {
            text = "Spotify"
            textSize = 17f
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        setupTile(0xFF1DB954.toInt(), second) { showSpotifySheet() }
    }

    // Hoesjes laden zonder extra bibliotheek: eenvoudige cache in het geheugen
    private val imageCache = object : android.util.LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }
    private val imagePool = java.util.concurrent.Executors.newFixedThreadPool(4)

    private fun loadImage(view: ImageView, url: String) {
        view.tag = url
        imageCache.get(url)?.let { view.setImageBitmap(it); return }
        imagePool.execute {
            val bmp = runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                }
            }.getOrNull() ?: return@execute
            imageCache.put(url, bmp)
            runOnUiThread { if (view.tag == url) view.setImageBitmap(bmp) }
        }
    }

    /** Zoekscherm: typ een artiest, nummer, album of playlist en kies wat je wilt horen. */
    /** Een muziekbron voor het zoekscherm (Spotify of Music Assistant). */
    interface MusicSource {
        val title: String
        fun search(q: String): List<SpotifyApi.Item>
        fun recent(): Pair<List<SpotifyApi.Item>, List<SpotifyApi.Item>>
        fun artistReleases(artist: SpotifyApi.Item): List<SpotifyApi.Item>
        fun artistPlaylists(artist: SpotifyApi.Item): List<SpotifyApi.Item>
        fun pick(item: SpotifyApi.Item)
    }

    private val spotifySource = object : MusicSource {
        override val title = "Spotify"
        override fun search(q: String) = spotify.search(q)
        override fun recent() = spotify.recent()
        override fun artistReleases(artist: SpotifyApi.Item) = spotify.artistReleases(artist.uri)
        override fun artistPlaylists(artist: SpotifyApi.Item) = spotify.artistPlaylists(artist.name)
        override fun pick(item: SpotifyApi.Item) = chooseSpotifyDevice(item)
    }

    private fun showSpotifySheet() {
        if (!spotify.isLoggedIn()) {
            AlertDialog.Builder(this)
                .setTitle("Spotify")
                .setMessage("Log eerst in bij Spotify via ⚙ → API's → Spotify.")
                .setPositiveButton("Naar instellingen") { _, _ -> showSettings(true); showSettingsPage(PAGE_SPOTIFY) }
                .setNegativeButton("Annuleren", null)
                .show()
            return
        }
        showMusicSearch(spotifySource)
    }

    /** Zoekscherm voor een muziekbron: eerst recent, dan zoeken tijdens het typen, artiestpagina's. */
    private fun showMusicSearch(src: MusicSource) {
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        content.addView(TextView(this).apply {
            text = src.title
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        val input = EditText(this).apply {
            hint = "Artiest, nummer, album of playlist"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
        }
        content.addView(input)
        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, dp(6), 0, dp(6))
        }
        content.addView(status)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(results)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420))
        }
        content.addView(scroll)

        var searchSeq = 0
        lateinit var showArtist: (SpotifyApi.Item) -> Unit
        lateinit var showRecent: () -> Unit

        fun row(title: String, sub: String?, accent: Boolean = false, image: String? = null, round: Boolean = false,
                 art: Boolean = false, onClick: () -> Unit) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(10), dp(8))
                minimumHeight = if (art) dp(72) else dp(52)
                background = GradientDrawable().apply {
                    setColor(if (accent) 0xFF1DB954.toInt() else 0xFF2A2A2A.toInt()); cornerRadius = dp(12).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                if (art) addView(ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply {
                        setColor(0xFF3A3A3A.toInt())
                        if (round) shape = GradientDrawable.OVAL else cornerRadius = dp(6).toFloat()
                    }
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) }
                    if (image != null) loadImage(this, image)
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = 16f
                        setTextColor(if (accent) Color.BLACK else Color.WHITE)
                        setTypeface(typeface, Typeface.BOLD)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    if (!sub.isNullOrEmpty()) addView(TextView(this@MainActivity).apply {
                        text = sub
                        textSize = 13f
                        setTextColor(0xFFAAAAAA.toInt())
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                })
                isClickable = true
                setOnClickListener { onClick() }
            }

        /** Lijst tonen met kopjes; [section] bepaalt onder welk kopje een item valt. */
        fun render(items: List<SpotifyApi.Item>, section: (SpotifyApi.Item) -> String) {
            var last = ""
            items.forEach { item ->
                val sec = section(item)
                if (sec != last) {
                    last = sec
                    results.addView(TextView(this).apply {
                        text = sec
                        textSize = 12f
                        setTextColor(0xFF1DB954.toInt())
                        setPadding(0, dp(12), 0, dp(4))
                    })
                }
                results.addView(row(
                    item.name, if (item.type == "artist") "Artiest" else item.sub,
                    image = item.image, round = item.type == "artist", art = true,
                ) {
                    if (item.type == "artist") showArtist(item)
                    else { dialog.dismiss(); src.pick(item) }
                })
            }
        }

        fun doSearch() {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return
            val seq = ++searchSeq
            status.text = "Zoeken…"
            thread {
                val r = runCatching { src.search(q) }
                runOnUiThread {
                    if (seq != searchSeq) return@runOnUiThread  // er is intussen verder getypt
                    results.removeAllViews()
                    val items = r.getOrNull()
                    if (items == null) {
                        status.text = "Zoeken mislukt: ${r.exceptionOrNull()?.message}"
                        return@runOnUiThread
                    }
                    status.text = if (items.isEmpty()) "Niets gevonden." else "Tik om af te spelen, of op een artiest voor zijn albums"
                    render(items) {
                        when (it.type) {
                            "artist" -> "ARTIESTEN"; "track" -> "NUMMERS"; "album" -> "ALBUMS"; "radio" -> "RADIO"; else -> "PLAYLISTS"
                        }
                    }
                }
            }
        }

        // Artiestpagina: albums (nieuw → oud), dan singles/EP's, dan playlists
        showArtist = { artist ->
            val seq = ++searchSeq
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
            status.text = "${artist.name} laden…"
            results.removeAllViews()
            thread {
                val rel = runCatching { src.artistReleases(artist) }
                val pls = runCatching { src.artistPlaylists(artist) }.getOrDefault(emptyList())
                runOnUiThread {
                    if (seq != searchSeq) return@runOnUiThread
                    results.removeAllViews()
                    status.text = artist.name
                    results.addView(row("← Terug", null) {
                        if (input.text.isNullOrBlank()) showRecent() else doSearch()
                    })
                    results.addView(row("▶  Alles van ${artist.name} afspelen", null, accent = true) {
                        dialog.dismiss(); src.pick(artist)
                    })
                    val releases = rel.getOrNull()
                    if (releases == null) {
                        status.text = "Laden mislukt: ${rel.exceptionOrNull()?.message}"
                    }
                    val albums = releases.orEmpty().filter { it.type == "album" }
                    val singles = releases.orEmpty().filter { it.type == "single" }
                    render(albums + singles + pls) {
                        when (it.type) { "album" -> "ALBUMS"; "single" -> "SINGLES & EP'S"; else -> "PLAYLISTS" }
                    }
                }
            }
        }
        // Voordat je zoekt: recent beluisterde artiesten en albums
        showRecent = {
            val seq = ++searchSeq
            status.text = "Recent beluisterd laden…"
            results.removeAllViews()
            thread {
                val r = runCatching { src.recent() }
                runOnUiThread {
                    if (seq != searchSeq) return@runOnUiThread
                    results.removeAllViews()
                    val (artists, albums) = r.getOrNull() ?: run {
                        val e = r.exceptionOrNull()
                        status.text = if ((e as? SpotifyApi.ApiException)?.code == 403 ||
                            e?.message?.contains("scope", ignoreCase = true) == true
                        ) "Voor 'recent beluisterd' moet je één keer opnieuw inloggen (⚙ → API's → ${src.title})."
                        else "Typ om te zoeken"
                        return@runOnUiThread
                    }
                    status.text = "Recent beluisterd · typ om te zoeken"
                    render(artists + albums) { if (it.type == "artist") "RECENTE ARTIESTEN" else "RECENTE ALBUMS" }
                }
            }
        }

        input.setOnEditorActionListener { _, _, _ -> doSearch(); true }
        // Zoeken tijdens het typen: kort wachten tot je even stopt, vanaf 2 tekens
        val typed = Runnable { doSearch() }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                handler.removeCallbacks(typed)
                val len = e?.trim()?.length ?: 0
                if (len >= 2) handler.postDelayed(typed, 350)
                else if (len == 0) showRecent()
            }
        })
        dialog.setOnDismissListener { handler.removeCallbacks(typed) }

        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            })
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        input.requestFocus()
        showRecent()
    }

    /** Kies waar het moet spelen (Spotify Connect-apparaten); het laatst gebruikte staat bovenaan. */
    private fun chooseSpotifyDevice(item: SpotifyApi.Item) {
        mediaStatus.text = "Apparaten zoeken…"
        thread {
            val r = runCatching { spotify.devices() }
            // Spelers van Music Assistant (bijv. piCorePlayer) die Spotify zelf niet kent
            val maExtra = runCatching { maPlayers() }.getOrDefault(emptyList()).filter { (_, n) ->
                r.getOrNull().orEmpty().none { d -> d.name.equals(n, true) || d.name.contains(n, true) || n.contains(d.name, true) }
            }
            runOnUiThread {
                val devices = r.getOrNull()
                if (devices == null) {
                    mediaStatus.text = "Spotify: ${r.exceptionOrNull()?.message}"
                    return@runOnUiThread
                }
                val last = prefs().getString(SpotifyApi.KEY_LAST_DEVICE, null)
                // Deze telefoon staat apart bovenaan; die zelf dus niet nog eens in de lijst
                val sorted = devices.filterNot { isThisPhone(it) }
                    .sortedBy { if (it.id == last) 0 else if (it.active) 1 else 2 }
                val labels = listOf("📱  Deze telefoon") + sorted.map { it.name + if (it.active) "  (speelt nu)" else "" } +
                    maExtra.map { "${it.second}  (via Music Assistant)" }
                AlertDialog.Builder(this)
                    .setTitle("${item.name} afspelen op")
                    .setItems(labels.toTypedArray()) { _, i ->
                        when {
                            i == 0 -> playSpotifyHere(item)
                            i <= sorted.size -> playSpotify(item, sorted[i - 1])
                            else -> maExtra[i - 1 - sorted.size].let { (entity, name) -> playOnMa(item, entity, name) }
                        }
                    }
                    .setNegativeButton("Annuleren", null)
                    .show()
                mediaStatus.text = ""
            }
        }
    }

    private fun isThisPhone(d: SpotifyApi.Device) = d.type.equals("Smartphone", true) &&
        (d.name.equals(Build.MODEL, true) || d.name.contains(Build.MODEL, true) ||
            d.name.equals(Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: "-", true))

    /** Afspelen in Spotify op deze telefoon; staat Spotify nog niet klaar, dan eerst de app openen. */
    private fun playSpotifyHere(item: SpotifyApi.Item) {
        stopLocal()
        mediaStatus.text = "${item.name} op deze telefoon…"
        thread {
            fun findPhone() = runCatching { spotify.devices() }.getOrDefault(emptyList()).let { list ->
                list.firstOrNull { isThisPhone(it) } ?: list.firstOrNull { it.type.equals("Smartphone", true) }
            }
            var phone = findPhone()
            if (phone == null) {
                // Spotify-app wakker maken; daarna meldt de telefoon zich als apparaat
                runOnUiThread {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.uri)).setPackage("com.spotify.music"))
                    }.onFailure { mediaStatus.text = "Spotify-app niet gevonden op deze telefoon" }
                }
                for (t in 0 until 8) {
                    Thread.sleep(1_000)
                    phone = findPhone()
                    if (phone != null) break
                }
            }
            val dev = phone
            if (dev == null) {
                runOnUiThread { mediaStatus.text = "Spotify is geopend: tik daar op afspelen" }
                return@thread
            }
            val r = runCatching { spotify.play(dev.id, item) }
            runOnUiThread {
                mediaStatus.text = r.exceptionOrNull()?.let { "Afspelen mislukt: ${it.message}" }
                    ?: "${item.name} speelt op deze telefoon"
            }
        }
    }

    // ---------- Eigen muziek afspelen op deze telefoon ----------

    private var localPlayer: android.media.MediaPlayer? = null
    @Volatile private var localQueue: List<SmbMusic.Entry> = emptyList()
    @Volatile private var localIndex = 0

    private fun playLocal(tracks: List<SmbMusic.Entry>, startAt: Int) {
        musicRun++   // eventuele cast-wachtrij stoppen
        localQueue = tracks
        playLocalAt(startAt)
    }

    private fun playLocalAt(i: Int) {
        localPlayer?.let { runCatching { it.release() } }
        localPlayer = null
        val e = localQueue.getOrNull(i) ?: run { mediaStatus.text = "Klaar met afspelen"; localQueue = emptyList(); return }
        localIndex = i
        mediaStatus.text = "📱 ${e.name.substringBeforeLast('.')} laden…"
        thread {
            val url = runCatching { smbConfigure(); smb.urlFor(e.path) }
            runOnUiThread {
                val u = url.getOrNull() ?: run { mediaStatus.text = "Afspelen mislukt: ${url.exceptionOrNull()?.message}"; return@runOnUiThread }
                localPlayer = android.media.MediaPlayer().apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
                    )
                    setWakeMode(this@MainActivity, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                    setOnPreparedListener {
                        it.start()
                        mediaStatus.text = "📱 ${e.name.substringBeforeLast('.')} (${i + 1}/${localQueue.size})"
                        refreshMedia()
                    }
                    setOnCompletionListener { playLocalAt(localIndex + 1) }
                    setOnErrorListener { _, what, _ -> mediaStatus.text = "Afspelen mislukt (fout $what)"; true }
                    setDataSource(u)
                    prepareAsync()
                }
            }
        }
    }

    private fun toggleLocal() {
        val p = localPlayer ?: return
        if (p.isPlaying) p.pause() else p.start()
        refreshMedia()
    }

    private fun stopLocal() {
        localPlayer?.let { runCatching { it.release() } }
        localPlayer = null
        localQueue = emptyList()
    }

    private fun playSpotify(item: SpotifyApi.Item, device: SpotifyApi.Device) {
        stopLocal()
        mediaStatus.text = "${item.name} op ${device.name}…"
        thread {
            // Receiver aan en op de juiste ingang, afhankelijk van het apparaat
            val n = device.name.lowercase(NL)
            when {
                "chromecast" in n || "audio woonkamer" in n -> prepareReceiver("AUX1")
                "googletv" in n || "google tv" in n || "tv in woonkamer" in n -> prepareWatching(SOURCE_APPS)
                "denon" in n || "heos" in n -> prepareReceiver("HEOS Music")
            }
            val r = runCatching { spotify.play(device.id, item) }
            runOnUiThread {
                mediaStatus.text = r.exceptionOrNull()?.let { "Afspelen mislukt: ${it.message}" }
                    ?: "${item.name} speelt op ${device.name}"
                handler.postDelayed({ refreshMedia() }, 2_000)
            }
        }
    }

    private fun View.setupTile(color: Int, second: Boolean, onTap: () -> Unit) {
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(16).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(0, dp(100), 1f).apply { if (second) marginStart = dp(12) }
        isClickable = true
        isFocusable = true
        setOnClickListener {
            alpha = 0.6f
            postDelayed({ alpha = 1f }, 250)
            onTap()
        }
    }

    // ---------- Alles uit ----------

    private fun confirmAllOff() {
        AlertDialog.Builder(this)
            .setTitle("Alles uit?")
            .setMessage("Alle lampen, de tv, de receiver, de GoogleTV-box en TV+ woonkamer gaan uit.")
            .setPositiveButton("Alles uit") { _, _ -> allOff() }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun allOff() {
        stopLocal()
        val status = when (currentTab) {
            TAB_DASH -> dashStatus
            TAB_MEDIA -> mediaStatus
            1 -> pveStatus
            2 -> fritzStatus
            else -> statusText
        }
        status.text = "Alles uitzetten…"
        thread {
            val lights = JSONArray().apply { LIGHT_GROUPS.forEach { (_, ids) -> ids.forEach { put(it) } } }
            val calls = listOf(
                "light/turn_off" to JSONObject().put("entity_id", lights),
                "media_player/turn_off" to JSONObject().put("entity_id", MEDIA_TV),
                "media_player/turn_off" to JSONObject().put("entity_id", DASH_RECEIVER),
                "remote/turn_off" to JSONObject().put("entity_id", MEDIA_REMOTE),
                "remote/turn_off" to JSONObject().put("entity_id", TVPLUS_REMOTE),
            )
            val failed = calls.count { (service, data) ->
                runCatching { haRequest("POST", "/api/services/$service", data.toString()) }.isFailure
            }
            runOnUiThread {
                status.text = if (failed == 0) "Alles is uit" else "Niet alles ging uit ($failed van ${calls.size} mislukt)"
                handler.postDelayed({ refreshCurrentTab(force = true) }, 1_500)
            }
        }
    }

    // ---------- Automatisch: tv en receiver klaarzetten ----------

    private fun stateOf(entity: String): JSONObject? =
        runCatching { JSONObject(haRequest("GET", "/api/states/$entity", null)) }.getOrNull()

    /** Stilletjes een dienst aanroepen (geen foutmelding in beeld). */
    private fun quietService(service: String, data: JSONObject) {
        runCatching { haRequest("POST", "/api/services/$service", data.toString()) }
    }

    /**
     * Zet de LG-tv aan en de receiver aan op [source]. Draait op de achtergrond en blokkeert niets.
     */
    private fun prepareWatching(source: String) {
        // LG-tv aan (staat hij uit, dan is hij in HA vaak 'unavailable')
        if (stateOf(MEDIA_TV)?.optString("state") != "on") {
            // Wekpakket rechtstreeks (werkt ook als de tv helemaal slaapt) + aan via HomeKit
            quietService(
                "wake_on_lan/send_magic_packet",
                JSONObject().put("mac", TV_MAC).put("broadcast_address", TV_BROADCAST)
            )
            quietService("media_player/turn_on", JSONObject().put("entity_id", MEDIA_TV))
        }
        prepareReceiver(source)
    }

    /** Receiver aan en op de juiste bron. */
    private fun prepareReceiver(source: String) {
        val rec = stateOf(DASH_RECEIVER)
        if (rec?.optString("state") in listOf(null, "off", "standby", "unavailable")) {
            quietService("media_player/turn_on", JSONObject().put("entity_id", DASH_RECEIVER))
            Thread.sleep(3_000)
        }
        for (attempt in 0 until 3) {
            val current = stateOf(DASH_RECEIVER)?.optJSONObject("attributes")?.optString("source")
            if (current == source) break
            quietService("media_player/select_source", JSONObject().put("entity_id", DASH_RECEIVER).put("source", source))
            Thread.sleep(2_000)
        }
    }

    // ---------- Zenders kiezen (KPN TV+) ----------

    private fun lastChannel(): TvChannel? {
        val n = prefs().getInt(KEY_LAST_CHANNEL, -1)
        return (TV_FAVORITES + TV_CHANNELS).firstOrNull { it.num == n }
    }

    /** Keuzescherm dat van onderen omhoog komt: favorieten, alle zenders, zappen en nummer. */
    private fun showChannelSheet() {
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(18))
        }
        content.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(0xFF555555.toInt()); cornerRadius = dp(2).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12)
            }
        })
        content.addView(TextView(this).apply {
            text = "Zender kiezen"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })

        fun section(title: String) = TextView(this).apply {
            text = title.uppercase(NL)
            textSize = 12f
            letterSpacing = 0.05f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, dp(14), 0, dp(8))
        }

        fun grid(list: List<TvChannel>) {
            list.chunked(3).forEach { rowList ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }
                for (i in 0 until 3) {
                    val ch = rowList.getOrNull(i)
                    val cell = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(9), dp(6), dp(9))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { if (i > 0) marginStart = dp(8) }
                    }
                    if (ch != null) {
                        cell.background = GradientDrawable().apply {
                            setColor(0xFF2A2A2A.toInt())
                            cornerRadius = dp(12).toFloat()
                            if (ch.num == prefs().getInt(KEY_LAST_CHANNEL, -1)) setStroke(dp(2), 0xFF18BCF2.toInt())
                        }
                        cell.addView(TextView(this).apply {
                            text = ch.num.toString()
                            textSize = 12f
                            setTextColor(Color.WHITE)
                            setTypeface(typeface, Typeface.BOLD)
                            gravity = Gravity.CENTER
                            minWidth = dp(28)
                            setPadding(dp(4), dp(3), dp(4), dp(3))
                            background = GradientDrawable().apply { setColor(ch.color); cornerRadius = dp(7).toFloat() }
                        })
                        cell.addView(TextView(this).apply {
                            text = ch.name
                            textSize = 13f
                            setTextColor(Color.WHITE)
                            setTypeface(typeface, Typeface.BOLD)
                            maxLines = 2
                            setPadding(dp(7), 0, 0, 0)
                        })
                        cell.isClickable = true
                        cell.setOnClickListener { dialog.dismiss(); tuneChannel(ch.num, ch.name) }
                    }
                    row.addView(cell)
                }
                content.addView(row)
            }
        }

        content.addView(section("Favorieten"))
        grid(TV_FAVORITES)
        content.addView(section("Alle zenders"))
        grid(TV_CHANNELS)

        // Onderaan: aanzetten, zappen, nummer intikken
        val keys = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        fun key(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(11), dp(4), dp(11))
            background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(12).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (keys.childCount > 0) marginStart = dp(8) }
            setOnClickListener { action() }
        }
        keys.addView(key("Aan") { dialog.dismiss(); watchTv() })
        keys.addView(key("▲ CH+") { sendTvKeys(listOf("CHANNEL_UP"), "Volgende zender") })
        keys.addView(key("▼ CH−") { sendTvKeys(listOf("CHANNEL_DOWN"), "Vorige zender") })
        keys.addView(key("⌨ Nummer") { dialog.dismiss(); askChannelNumber() })
        content.addView(keys)

        dialog.setContentView(ScrollView(this).apply { addView(content) })
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            })
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    /** Afstandsbediening voor de GoogleTV-box of de TV+-box (via remote.send_command). */
    private fun showRemoteSheet(remote: String, kpn: Boolean) {
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(22))
        }
        content.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(0xFF555555.toInt()); cornerRadius = dp(2).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12)
            }
        })
        content.addView(TextView(this).apply {
            text = if (kpn) "TV+ woonkamer" else "GoogleTV Box"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })

        fun send(key: String) = sendRemoteKey(remote, key)

        fun btn(label: String, key: String?, color: Int = 0xFF2A2A2A.toInt(), round: Boolean = false, size: Float = 16f) =
            TextView(this).apply {
                text = label
                textSize = size
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                if (key != null) {
                    background = GradientDrawable().apply {
                        setColor(color)
                        if (round) shape = GradientDrawable.OVAL else cornerRadius = dp(14).toFloat()
                    }
                    isClickable = true
                    setOnClickListener {
                        alpha = 0.5f
                        postDelayed({ alpha = 1f }, 150)
                        send(key)
                    }
                }
            }

        fun row(vararg views: View, height: Int = 52) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
                .apply { bottomMargin = dp(10) }
            views.forEachIndexed { i, v ->
                v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    .apply { if (i > 0) marginStart = dp(10) }
                addView(v)
            }
        }

        // Bovenste rij: terug, home, en bij KPN de gids (anders menu)
        content.addView(row(
            btn("↩ Terug", "BACK"),
            btn("⌂ Home", "HOME"),
            if (kpn) btn("Gids", "GUIDE", 0xFF1565C0.toInt())
            else btn("⌨ Typen", null).apply {
                background = GradientDrawable().apply { setColor(0xFF1565C0.toInt()); cornerRadius = dp(14).toFloat() }
                isClickable = true
                setOnClickListener { typeOnTv() }
            },
        ))
        // Pijltjes met OK in het midden
        val dark = 0xFF333333.toInt()
        content.addView(row(btn("", null), btn("▲", "DPAD_UP", dark, size = 22f), btn("", null), height = 64))
        content.addView(row(
            btn("◀", "DPAD_LEFT", dark, size = 22f),
            btn("OK", "DPAD_CENTER", 0xFF18BCF2.toInt(), round = true, size = 18f),
            btn("▶", "DPAD_RIGHT", dark, size = 22f),
            height = 64,
        ))
        content.addView(row(btn("", null), btn("▼", "DPAD_DOWN", dark, size = 22f), btn("", null), height = 64))
        // Afspelen
        content.addView(row(
            btn("⏪", "MEDIA_REWIND"),
            btn("⏯", "MEDIA_PLAY_PAUSE"),
            btn("⏩", "MEDIA_FAST_FORWARD"),
        ))
        if (kpn) {
            content.addView(row(
                btn("▼ CH−", "CHANNEL_DOWN"),
                btn("ℹ Info", "INFO"),
                btn("▲ CH+", "CHANNEL_UP"),
            ))
        }
        content.addView(TextView(this).apply {
            text = "Volume: met de volumeknoppen van je telefoon"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
        })

        dialog.setContentView(ScrollView(this).apply { addView(content) })
        // Volumeknoppen blijven de receiver regelen terwijl de afstandsbediening open is
        dialog.setOnKeyListener { _, _, event ->
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                dispatchKeyEvent(event)
            } else false
        }
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            })
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    /**
     * Tekst typen op de GoogleTV-box met het toetsenbord van de telefoon. De tekst gaat via ADB
     * ("input text") naar het zoekveld dat op de tv openstaat.
     */
    private fun typeOnTv() {
        val input = EditText(this).apply {
            hint = "Bijv. Yellowstone"
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(TextView(this@MainActivity).apply {
                text = "Zet op de tv eerst de cursor in het zoekveld."
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                setPadding(0, dp(6), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Typen op de tv")
            .setView(box)
            .setPositiveButton("Zoeken") { _, _ -> sendTextToTv(input.text.toString(), enter = true) }
            .setNeutralButton("Alleen typen") { _, _ -> sendTextToTv(input.text.toString(), enter = false) }
            .setNegativeButton("Annuleren", null)
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            sendTextToTv(input.text.toString(), enter = true)
            dialog.dismiss()
            true
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun sendTextToTv(text: String, enter: Boolean) {
        if (text.isBlank()) return
        // "input text" kent geen spaties (→ %s) en de tekst moet veilig tussen enkele aanhalingstekens
        val escaped = text.replace("%", "\\%").replace(" ", "%s").replace("'", "'\\''")
        var cmd = "input text '$escaped'"
        if (enter) cmd += " && input keyevent 66"
        mediaStatus.text = "Typen: $text"
        thread {
            quietService("androidtv/adb_command", JSONObject().put("entity_id", MEDIA_ADB).put("command", cmd))
        }
    }

    private fun askChannelNumber() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Kanaalnummer"
        }
        AlertDialog.Builder(this)
            .setTitle("Kanaal kiezen")
            .setView(input)
            .setPositiveButton("Kijken") { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                val name = (TV_FAVORITES + TV_CHANNELS).firstOrNull { it.num == n }?.name ?: "Kanaal $n"
                tuneChannel(n, name)
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    /** Alles aan (indien nodig) en het kanaalnummer als cijfertoetsen naar de TV+-box sturen. */
    private fun tuneChannel(num: Int, name: String) {
        prefs().edit().putInt(KEY_LAST_CHANNEL, num).apply()
        mediaStatus.text = "$name kiezen…"
        thread {
            val boxWasOff = stateOf(TVPLUS_REMOTE)?.optString("state") != "on"
            if (boxWasOff) quietService("remote/turn_on", JSONObject().put("entity_id", TVPLUS_REMOTE))
            prepareWatching(SOURCE_TV)
            if (boxWasOff) Thread.sleep(6_000)  // box laten opstarten
            val digits = num.toString().map { it.toString() }
            quietService(
                "remote/send_command",
                JSONObject().put("entity_id", TVPLUS_REMOTE).put("command", JSONArray(digits)).put("delay_secs", 0.3)
            )
            runOnUiThread {
                mediaStatus.text = "$name (kanaal $num)"
                refreshMedia()
            }
        }
    }

    private fun sendTvKeys(keys: List<String>, label: String) {
        mediaStatus.text = label
        keys.forEach { sendRemoteKey(TVPLUS_REMOTE, it) }
    }

    /**
     * Toetsen gaan één voor één in volgorde naar de box (niet allemaal tegelijk), zodat snel
     * achter elkaar drukken de box niet overspoelt. Loopt de wachtrij te ver achter, dan vervallen
     * de oudste toetsen in plaats van dat de box nog seconden blijft doorschuiven.
     */
    private val keyQueue = java.util.concurrent.LinkedBlockingDeque<Pair<String, JSONObject>>()
    private val keyWorker = thread(isDaemon = true, name = "toetsen") {
        while (true) {
            val (service, data) = keyQueue.take()
            quietService(service, data)
        }
    }

    /** In Kodi deze toetsen via ADB sturen (Android-keycodes): OK, Terug, Pauze. */
    private val KODI_ADB_KEYS = mapOf("DPAD_CENTER" to 23, "BACK" to 4, "MEDIA_PLAY_PAUSE" to 85)

    /** App die nu op de GoogleTV-box openstaat (bijgewerkt door de infobalk). */
    @Volatile private var boxApp: String? = null

    private fun sendRemoteKey(remote: String, key: String) {
        while (keyQueue.size >= 4) keyQueue.pollFirst()
        val kodiCode = KODI_ADB_KEYS[key]
        val item = if (remote == MEDIA_REMOTE && kodiCode != null && boxApp == "org.xbmc.kodi") {
            // Kodi reageert op OK/Terug via de Remote-integratie anders dan op de echte afstandsbediening.
            // Via ADB komen ze binnen zoals een fysieke toets.
            "androidtv/adb_command" to JSONObject().put("entity_id", MEDIA_ADB).put("command", "input keyevent $kodiCode")
        } else {
            "remote/send_command" to JSONObject().put("entity_id", remote).put("command", key).put("delay_secs", 0)
        }
        keyQueue.offerLast(item)
    }

    private fun watchTv() {
        mediaStatus.text = "TV kijken: tv en receiver aanzetten…"
        thread {
            // TV+-box aan (alleen als hij uit staat; aan/uit is bij deze box een schakelknop)
            if (stateOf(TVPLUS_REMOTE)?.optString("state") != "on") {
                quietService("remote/turn_on", JSONObject().put("entity_id", TVPLUS_REMOTE))
            }
            prepareWatching(SOURCE_TV)
            runOnUiThread {
                mediaStatus.text = "TV kijken: receiver staat op TV+ woonkamer"
                refreshMedia()
            }
        }
    }

    /**
     * Start een app en controleert of hij echt opent. Werkt een methode niet, dan probeert hij de
     * volgende; de methode die werkt wordt per app onthouden en de volgende keer meteen gebruikt.
     */
    private fun launchApp(app: MediaApp) {
        mediaStatus.text = "${app.name} openen…"
        val remote = { activity: String -> JSONObject().put("entity_id", MEDIA_REMOTE).put("activity", activity) }
        val all = buildList {
            // 1. Via ADB: precies wat de box doet als je de app-tegel kiest (TV- of gewone app)
            add(Triple("adb", "androidtv/adb_command", JSONObject().put("entity_id", MEDIA_ADB).put(
                "command",
                "monkey -p ${app.pkg} -c android.intent.category.LEANBACK_LAUNCHER -c android.intent.category.LAUNCHER 1"
            )))
            // 2. Reserve via de Remote-integratie (alleen manieren zonder meldingen op de tv)
            app.link?.let { add(Triple("link", "remote/turn_on", remote(it))) }
            add(Triple("market", "remote/turn_on", remote("market://launch?id=${app.pkg}")))
            add(
                Triple(
                    "play", "media_player/play_media",
                    JSONObject().put("entity_id", MEDIA_PLAYER)
                        .put("media_content_type", "app").put("media_content_id", app.pkg)
                )
            )
            add(Triple("pkg", "remote/turn_on", remote(app.pkg)))
        }
        val prefKey = "launch_${app.pkg}"
        val known = prefs().getString(prefKey, null)
        val attempts = all.sortedBy { if (it.first == "adb") 0 else if (it.first == known) 1 else 2 }

        thread { prepareWatching(SOURCE_APPS) }  // tv + receiver tegelijk klaarzetten
        thread {
            // Box eerst aan (nodig voor de mediaspeler-methode)
            if (currentMediaState() != "on") {
                runCatching { haRequest("POST", "/api/services/remote/turn_on", JSONObject().put("entity_id", MEDIA_REMOTE).toString()) }
                Thread.sleep(2_500)
            }
            for ((i, attempt) in attempts.withIndex()) {
                val (kind, service, data) = attempt
                if (i > 0) runOnUiThread { mediaStatus.text = "${app.name} openen… (andere manier, ${i + 1}/${attempts.size})" }
                runCatching { haRequest("POST", "/api/services/$service", data.toString()) }
                // Tot 6 s wachten: sommige apps starten traag
                var opened = false
                for (t in 0 until 6) {
                    Thread.sleep(1_000)
                    if (currentAppId() == app.pkg) { opened = true; break }
                }
                if (opened) {
                    prefs().edit().putString(prefKey, kind).apply()
                    runOnUiThread {
                        mediaStatus.text = "${app.name} geopend"
                        refreshMedia()
                    }
                    return@thread
                }
            }
            runOnUiThread {
                mediaStatus.text = "${app.name} kon niet worden gestart. Open hem één keer handmatig en probeer het opnieuw."
                refreshMedia()
            }
        }
    }

    private fun currentMediaState(): String? =
        runCatching { JSONObject(haRequest("GET", "/api/states/$MEDIA_REMOTE", null)).optString("state") }.getOrNull()

    /** Welke app er nu op de box openstaat (pakketnaam). */
    private fun currentAppId(): String? {
        val p = runCatching { JSONObject(haRequest("GET", "/api/states/$MEDIA_PLAYER", null)) }.getOrNull()
        p?.optJSONObject("attributes")?.optString("app_id")?.takeIf { it.isNotEmpty() }?.let { return it }
        val r = runCatching { JSONObject(haRequest("GET", "/api/states/$MEDIA_REMOTE", null)) }.getOrNull()
        return r?.optJSONObject("attributes")?.optString("current_activity")?.takeIf { it.isNotEmpty() }
    }

    private fun refreshMedia() {
        if ((haUrl().isEmpty() && haLocalUrl().isEmpty()) || haToken().isEmpty()) {
            mediaStatus.text = "Vul de Home Assistant-gegevens in via ⚙"
            return
        }
        if (mediaBusy) return
        mediaBusy = true
        thread {
            try {
                val info = runCatching { nowPlaying() }
                runOnUiThread { showNowBar(info) }
            } finally {
                mediaBusy = false
            }
        }
    }

    private fun attr(o: JSONObject?, vararg keys: String): String? {
        val a = o?.optJSONObject("attributes") ?: return null
        return keys.firstNotNullOfOrNull { k -> a.optString(k).takeIf { it.isNotEmpty() && it != "null" } }
    }

    private fun isOn(o: JSONObject?) = o != null && o.optString("state") !in listOf("off", "standby", "unavailable", "unknown", "")
    private fun isPlaying(o: JSONObject?) = o?.optString("state") == "playing"
    private fun joinNonEmpty(vararg parts: String?) = parts.filterNot { it.isNullOrEmpty() }.joinToString(" · ")

    /** Bepaalt via de bron van de Denon welk apparaat speelt, en wat. */
    private fun nowPlaying(): NowInfo? {
        // Speelt er muziek op deze telefoon zelf? Dan die eerst
        localPlayer?.let { p ->
            val e = localQueue.getOrNull(localIndex)
            if (e != null) return NowInfo(
                title = "Deze telefoon",
                sub = "${e.name.substringBeforeLast('.')} (${localIndex + 1}/${localQueue.size})",
                icon = R.drawable.ic_folder_music, iconBg = 0xFF6D4C41.toInt(),
                entity = "local", playing = runCatching { p.isPlaying }.getOrDefault(false),
            )
        }
        val rec = stateOf(DASH_RECEIVER)
        recState = rec
        val recOn = isOn(rec)
        val source = if (recOn) attr(rec, "source") else null
        val box = stateOf(MEDIA_REMOTE)

        // GoogleTV Box (via de Denon, of de box staat aan terwijl de Denon uit is)
        if (source == SOURCE_APPS || (!recOn && isOn(box))) {
            val p = stateOf(MEDIA_PLAYER)
            val appId = attr(p, "app_id") ?: attr(box, "current_activity")
            boxApp = appId
            val app = MEDIA_APPS.firstOrNull { it.pkg == appId }
            val appName = app?.name ?: attr(p, "app_name") ?: appId
            return NowInfo(
                title = appName ?: "GoogleTV Box",
                sub = joinNonEmpty("GoogleTV Box", attr(p, "media_title"), attr(p, "media_artist")),
                icon = app?.logo ?: R.drawable.ic_tv_watch, iconBg = app?.color ?: 0xFF1565C0.toInt(),
                entity = MEDIA_PLAYER, playing = isPlaying(p), remote = MEDIA_REMOTE,
            )
        }
        if (!recOn) return null
        return when (source) {
            SOURCE_TV -> {
                val p = stateOf(TVPLUS_PLAYER)
                val last = lastChannel()
                NowInfo(
                    title = "TV+ woonkamer",
                    sub = last?.let { "${it.name} · kanaal ${it.num}" } ?: "KPN",
                    icon = R.drawable.ic_tv_watch, iconBg = 0xFF1565C0.toInt(),
                    entity = null, playing = isPlaying(p), tv = true, remote = TVPLUS_REMOTE,
                )
            }
            "HEOS Music" -> {
                val h = stateOf(DASH_HEOS)
                NowInfo(
                    title = attr(h, "media_station", "media_album_name") ?: "HEOS",
                    sub = joinNonEmpty(attr(h, "media_artist"), attr(h, "media_title")).ifEmpty { "HEOS" },
                    icon = R.drawable.logo_heos, iconBg = 0xFF000000.toInt(),
                    entity = DASH_HEOS, playing = isPlaying(h),
                )
            }
            "AUX1" -> {
                val c = stateOf(CHROMECAST_AUDIO)
                // Speelt Music Assistant op de Chromecast? Dan die gegevens (staat los van Spotify)
                val ma = maPlayers().firstOrNull { (_, name) ->
                    val n = name.lowercase(NL)
                    "chromecast" in n || "audio woonkamer" in n
                }?.let { stateOf(it.first) }
                if (ma != null && (isPlaying(ma) || (ma.optString("state") == "paused" && !isPlaying(c)))) {
                    return NowInfo(
                        title = "Chromecast Audio",
                        sub = joinNonEmpty(attr(ma, "media_artist"), attr(ma, "media_title")).ifEmpty { "Music Assistant" },
                        icon = null, iconBg = 0xFF455A64.toInt(),
                        entity = ma.optString("entity_id"), playing = isPlaying(ma),
                    )
                }
                val sp = stateOf(SPOTIFY)
                // Speelt Spotify? Dan die gegevens gebruiken; anders wat de Chromecast zelf meldt
                // Speelt de Chromecast zelf (bijv. muziek van de netwerkmap), dan heeft die voorrang
                // Spotify alleen als die echt op de Chromecast speelt (niet op een ander apparaat)
                val spOnCast = attr(sp, "source")?.lowercase(NL)?.let { "chromecast" in it || "audio woonkamer" in it } ?: true
                val spActive = spOnCast && (sp?.optString("state") == "playing" ||
                    (sp?.optString("state") == "paused" && !isPlaying(c)))
                if (spActive) NowInfo(
                    title = "Chromecast Audio",
                    sub = joinNonEmpty(attr(sp, "media_artist"), attr(sp, "media_title")).ifEmpty { "Spotify" },
                    icon = null, iconBg = 0xFF455A64.toInt(),
                    entity = SPOTIFY, playing = isPlaying(sp),
                ) else NowInfo(
                    title = "Chromecast Audio",
                    sub = joinNonEmpty(attr(c, "media_artist"), attr(c, "media_title")).ifEmpty { "Klaar om te casten" },
                    icon = null, iconBg = 0xFF455A64.toInt(),
                    entity = if (isOn(c)) CHROMECAST_AUDIO else null, playing = isPlaying(c),
                )
            }
            else -> NowInfo(
                title = sourceLabel(source ?: ""), sub = "via de Denon",
                icon = null, iconBg = 0xFF455A64.toInt(), entity = null, playing = false,
            )
        }
    }

    private fun showNowBar(result: Result<NowInfo?>) {
        val info = result.getOrNull()
        if (result.isFailure) {
            mediaStatus.text = "Home Assistant niet bereikbaar: ${result.exceptionOrNull()?.message}"
        } else if (mediaStatus.text.startsWith("Home Assistant niet") || mediaStatus.text.isEmpty()) {
            mediaStatus.text = haRouteLabel()
        }
        if (info == null) {
            nowBar.visibility = View.GONE
            nowEntity = null
            return
        }
        nowBar.visibility = View.VISIBLE
        nowTitle.text = "${info.title} ▾"
        nowSub.text = info.sub
        nowIcon.background = GradientDrawable().apply {
            setColor(info.iconBg)
            cornerRadius = dp(8).toFloat()
        }
        if (info.icon != null) {
            nowIcon.setImageResource(info.icon)
            nowIcon.scaleType = if (info.icon == R.drawable.ic_tv_watch) ImageView.ScaleType.CENTER_INSIDE
            else ImageView.ScaleType.CENTER_CROP
            nowIcon.setPadding(if (info.icon == R.drawable.ic_tv_watch) dp(6) else 0, 0, 0, 0)
            nowIcon.visibility = View.VISIBLE
        } else {
            nowIcon.visibility = View.GONE
        }
        nowEntity = info.entity
        nowPlayPause.visibility = if (info.entity != null) View.VISIBLE else View.GONE
        nowChannelBtn.visibility = if (info.tv) View.VISIBLE else View.GONE
        nowRemote = info.remote
        nowRemoteBtn.visibility = if (info.remote != null) View.VISIBLE else View.GONE
        // Typen kan alleen op de GoogleTV-box (via ADB)
        nowKeyboardBtn.visibility = if (info.remote == MEDIA_REMOTE) View.VISIBLE else View.GONE
        nowPlayPause.text = if (info.playing) "⏸" else "▶"
    }

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
