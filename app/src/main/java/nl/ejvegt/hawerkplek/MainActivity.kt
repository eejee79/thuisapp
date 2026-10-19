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
    class TvGroup(val title: String, val open: Boolean, val channels: List<TvChannel>)

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
        /** Kodi op de GoogleTV-box (JSON-RPC via http). Wachtwoord vul je in de app in. */
        const val KODI_PKG = "org.xbmc.kodi"
        const val YOUTUBE_PKG = "com.google.android.youtube.tv"
        private const val KEY_TILE_ORDER = "tile_order"
        private const val KEY_ZONE_UP = "zone_up"
        private const val KEY_KPN_INFO_KEY = "kpn_info_key"
        private const val KEY_KPN_SKIP_SEC = "kpn_skip_sec_v2"
        private const val KEY_TMDB = "tmdb_key"
        private const val KEY_T_MUSIC = "target_music"
        private const val KEY_CAR_SWITCH = "car_charge_switch"
        private const val KEY_CAR_SWITCH_MANUAL = "car_charge_switch_manual"
        private const val KEY_T_MA = "target_ma"
        private const val KEY_T_SPOTIFY = "target_spotify"
        const val KODI_DEFAULT_HOST = "192.168.50.140"
        private const val KEY_KODI_HOST = "kodi_host"
        private const val KEY_KODI_PORT = "kodi_port"
        private const val KEY_KODI_USER = "kodi_user"
        private const val KEY_KODI_PASS = "kodi_pass"
        private const val KEY_OS_KEY = "os_key"
        private const val KEY_OS_USER = "os_user"
        private const val KEY_OS_PASS = "os_pass"
        private const val KEY_OS_LANG = "os_lang"
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
        private val C_FILM = 0xFF6D4C41.toInt()
        private val C_DOC = 0xFF558B2F.toInt()
        private val C_MUSIC = 0xFFAD1457.toInt()
        private val C_KIDS = 0xFF6A1B9A.toInt()
        private val C_NEWS = 0xFF00838F.toInt()
        private val C_INTL = 0xFF37474F.toInt()

        /** Alle zenders per soort (KPN-zenderoverzicht 1 april 2026). Open = standaard uitgeklapt. */
        val TV_GROUPS = listOf(
            TvGroup("Nederlands", true, listOf(
                TvChannel(1, "NPO 1", C_NPO),
                TvChannel(2, "NPO 2", C_NPO),
                TvChannel(3, "NPO 3", C_NPO),
                TvChannel(4, "RTL 4", C_NPO),
                TvChannel(5, "RTL 5", C_NPO),
                TvChannel(6, "SBS6", C_NPO),
                TvChannel(7, "RTL 7", C_NPO),
                TvChannel(8, "NET5", C_NPO),
                TvChannel(9, "Veronica", C_NPO),
                TvChannel(10, "RTL 8", C_NPO),
                TvChannel(11, "RTL Z", C_NPO),
                TvChannel(19, "SBS9", C_NPO),
                TvChannel(81, "NPO 1 Extra", C_NPO),
                TvChannel(82, "NPO 2 Extra", C_NPO),
                TvChannel(83, "NPO Nieuws & Politiek", C_NPO),
                TvChannel(538, "TV 538", C_NPO),
                TvChannel(73, "RTL Lounge", C_NPO),
                TvChannel(74, "RTL Crime", C_NPO),
                TvChannel(89, "ONS", C_NPO),
                TvChannel(78, "Family7", C_NPO),
                TvChannel(38, "InPlus", C_NPO),
                TvChannel(41, "Beleef KPN", C_NPO),
                TvChannel(42, "Zender van de maand", C_NPO),
            )),
            TvGroup("Sport", true, listOf(
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
                TvChannel(35, "Eurosport 1", C_SPORT),
                TvChannel(36, "Eurosport 2", C_SPORT),
                TvChannel(37, "W-Sport", C_SPORT),
                TvChannel(230, "Viaplay TV+", C_SPORT),
            )),
            TvGroup("Film & series", false, listOf(
                TvChannel(12, "Comedy Central", C_FILM),
                TvChannel(13, "Paramount Network", C_FILM),
                TvChannel(16, "STAR Channel", C_FILM),
                TvChannel(51, "Viaplay TV", C_FILM),
                TvChannel(50, "FilmBox", C_FILM),
                TvChannel(245, "Film1 Premiere", C_FILM),
                TvChannel(246, "Film1 Action", C_FILM),
                TvChannel(247, "Film1 Family", C_FILM),
                TvChannel(248, "Film1 Drama", C_FILM),
                TvChannel(71, "E! Entertainment", C_FILM),
            )),
            TvGroup("Documentaire & lifestyle", false, listOf(
                TvChannel(15, "Discovery", C_DOC),
                TvChannel(84, "Discovery Science", C_DOC),
                TvChannel(17, "TLC", C_DOC),
                TvChannel(18, "National Geographic", C_DOC),
                TvChannel(87, "Nat Geo Wild", C_DOC),
                TvChannel(85, "Animal Planet", C_DOC),
                TvChannel(21, "ID", C_DOC),
                TvChannel(32, "History", C_DOC),
                TvChannel(44, "Crime & Investigation", C_DOC),
                TvChannel(34, "Love Nature", C_DOC),
                TvChannel(24, "24Kitchen", C_DOC),
                TvChannel(39, "Njam!", C_DOC),
                TvChannel(43, "HGTV", C_DOC),
                TvChannel(77, "OUTtv", C_DOC),
            )),
            TvGroup("Muziek", false, listOf(
                TvChannel(20, "MTV", C_MUSIC),
                TvChannel(22, "XITE", C_MUSIC),
                TvChannel(115, "XITE Klassiek", C_MUSIC),
                TvChannel(111, "SLAM! TV", C_MUSIC),
                TvChannel(112, "TV Oranje", C_MUSIC),
                TvChannel(113, "192TV", C_MUSIC),
                TvChannel(114, "Top40 TV", C_MUSIC),
            )),
            TvGroup("Kinderen", false, listOf(
                TvChannel(28, "Disney Channel", C_KIDS),
                TvChannel(33, "Nickelodeon", C_KIDS),
                TvChannel(52, "Nicktoons", C_KIDS),
                TvChannel(53, "Nick Jr.", C_KIDS),
                TvChannel(60, "Cartoon Network", C_KIDS),
                TvChannel(55, "Cartoonito", C_KIDS),
                TvChannel(23, "DreamWorks", C_KIDS),
                TvChannel(59, "RTL Telekids", C_KIDS),
                TvChannel(57, "BabyTV", C_KIDS),
                TvChannel(31, "Ketnet", C_KIDS),
            )),
            TvGroup("Nieuws & Engelstalig", false, listOf(
                TvChannel(25, "BBC NL", C_NEWS),
                TvChannel(26, "BBC One", C_NEWS),
                TvChannel(27, "BBC Two", C_NEWS),
                TvChannel(40, "BBC News", C_NEWS),
                TvChannel(61, "CNN", C_NEWS),
                TvChannel(62, "Euronews", C_NEWS),
                TvChannel(63, "Al Jazeera", C_NEWS),
            )),
            TvGroup("Vlaams", false, listOf(
                TvChannel(29, "VRT 1", C_ETC),
                TvChannel(30, "VRT Canvas", C_ETC),
            )),
            TvGroup("Duits", false, listOf(
                TvChannel(45, "Das Erste", C_INTL),
                TvChannel(46, "ZDF", C_INTL),
                TvChannel(47, "RTL Television", C_INTL),
                TvChannel(48, "WDR", C_INTL),
                TvChannel(120, "NDR", C_INTL),
                TvChannel(49, "ARTE", C_INTL),
                TvChannel(119, "VOX", C_INTL),
                TvChannel(121, "Sat.1", C_INTL),
                TvChannel(124, "ProSieben", C_INTL),
                TvChannel(127, "RTL II", C_INTL),
                TvChannel(128, "Super RTL", C_INTL),
                TvChannel(122, "tagesschau24", C_INTL),
                TvChannel(123, "3sat", C_INTL),
                TvChannel(125, "KiKA", C_INTL),
                TvChannel(126, "ONE", C_INTL),
            )),
            TvGroup("Regionaal", false, listOf(
                TvChannel(501, "TV Noord", C_ETC),
                TvChannel(502, "Omrop Fryslân", C_ETC),
                TvChannel(503, "RTV Drenthe", C_ETC),
                TvChannel(504, "Oost", C_ETC),
                TvChannel(505, "Omroep Flevoland", C_ETC),
                TvChannel(506, "Omroep Gelderland", C_ETC),
                TvChannel(507, "Omroep Brabant", C_ETC),
                TvChannel(508, "L1", C_ETC),
                TvChannel(509, "NH", C_ETC),
                TvChannel(510, "AT5", C_ETC),
                TvChannel(511, "RTV Utrecht", C_ETC),
                TvChannel(512, "Omroep West", C_ETC),
                TvChannel(513, "Rijnmond", C_ETC),
                TvChannel(514, "Omroep Zeeland", C_ETC),
            )),
            TvGroup("4K", false, listOf(
                TvChannel(692, "InUltra 4K", C_ETC),
                TvChannel(693, "Love Nature 4K", C_ETC),
                TvChannel(694, "ESPN 4K", C_ETC),
            )),
            TvGroup("Anderstalig", false, listOf(
                TvChannel(92, "France 2", C_INTL),
                TvChannel(96, "Mediaset Italia", C_INTL),
                TvChannel(93, "RTV-7", C_INTL),
                TvChannel(140, "TRT Türk", C_INTL),
                TvChannel(141, "TRT Haber", C_INTL),
                TvChannel(142, "ATV Avrupa", C_INTL),
                TvChannel(143, "Euro D", C_INTL),
                TvChannel(144, "Euro Star", C_INTL),
                TvChannel(145, "Show Türk", C_INTL),
                TvChannel(146, "TV8", C_INTL),
                TvChannel(147, "TRT Çocuk", C_INTL),
                TvChannel(148, "TRT Spor", C_INTL),
                TvChannel(129, "Utsav Bharat", C_INTL),
                TvChannel(130, "Zee Cinema", C_INTL),
                TvChannel(131, "Zee TV", C_INTL),
                TvChannel(132, "Sony TV Asia", C_INTL),
                TvChannel(133, "Sony MAX", C_INTL),
                TvChannel(134, "Utsav Plus", C_INTL),
                TvChannel(135, "Colors", C_INTL),
                TvChannel(136, "Utsav Gold", C_INTL),
            )),
            TvGroup("Live sport (260–283)", false, listOf(
                TvChannel(260, "Live sport", C_SPORT),
                TvChannel(261, "Live sport", C_SPORT),
                TvChannel(262, "Live sport", C_SPORT),
                TvChannel(263, "Live sport", C_SPORT),
                TvChannel(264, "Live sport", C_SPORT),
                TvChannel(265, "Live sport", C_SPORT),
                TvChannel(266, "Live sport", C_SPORT),
                TvChannel(267, "Live sport", C_SPORT),
                TvChannel(268, "Live sport", C_SPORT),
                TvChannel(269, "Live sport", C_SPORT),
                TvChannel(270, "Live sport", C_SPORT),
                TvChannel(271, "Live sport", C_SPORT),
                TvChannel(272, "Live sport", C_SPORT),
                TvChannel(273, "Live sport", C_SPORT),
                TvChannel(274, "Live sport", C_SPORT),
                TvChannel(275, "Live sport", C_SPORT),
                TvChannel(276, "Live sport", C_SPORT),
                TvChannel(277, "Live sport", C_SPORT),
                TvChannel(278, "Live sport", C_SPORT),
                TvChannel(279, "Live sport", C_SPORT),
                TvChannel(280, "Live sport", C_SPORT),
                TvChannel(281, "Live sport", C_SPORT),
                TvChannel(282, "Live sport", C_SPORT),
                TvChannel(283, "Live sport", C_SPORT),
            )),
        )
        val TV_CHANNELS = TV_GROUPS.flatMap { it.channels }

        /** Zenderlogo's uit de openbare verzameling tv-logo/tv-logos (GitHub), pad onder countries/. */
        private const val LOGO_BASE = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/"
        val TV_LOGOS = mapOf(
            1 to "netherlands/npo1-nl",
            2 to "netherlands/npo2-nl",
            3 to "netherlands/npo3-nl",
            4 to "netherlands/rtl4-nl",
            5 to "netherlands/rtl5-nl",
            6 to "netherlands/sbs6-nl",
            7 to "netherlands/rtl7-nl",
            8 to "netherlands/net5-nl",
            9 to "netherlands/veronica-nl",
            10 to "netherlands/rtl8-nl",
            11 to "netherlands/rtlz-nl",
            12 to "netherlands/comedy-central-nl",
            13 to "netherlands/paramount-network-nl",
            14 to "netherlands/espn-nl",
            15 to "netherlands/discovery-channel-nl",
            16 to "netherlands/star-channel-nl",
            17 to "netherlands/tlc-nl",
            18 to "netherlands/national-geographic-nl",
            19 to "netherlands/sbs9-nl",
            20 to "netherlands/mtv-nl",
            21 to "netherlands/id-investigation-discovery-nl",
            22 to "netherlands/xite-nl",
            23 to "netherlands/dreamworks-channel-nl",
            24 to "netherlands/24-kitchen-nl",
            25 to "netherlands/bbc-nl-nl",
            26 to "united-kingdom/bbc-one-uk",
            27 to "united-kingdom/bbc-two-uk",
            28 to "netherlands/disney-channel-nl",
            29 to "belgium/vrt-1-be",
            30 to "belgium/vrt-canvas-be",
            31 to "belgium/ketnet-be",
            32 to "netherlands/history-channel-nl",
            33 to "netherlands/nickelodeon-nl",
            34 to "netherlands/love-nature-nl",
            35 to "netherlands/eurosport-1-nl",
            36 to "netherlands/eurosport-2-nl",
            39 to "netherlands/njam-nl",
            40 to "united-kingdom/bbc-news-uk",
            43 to "united-kingdom/hgtv-uk",
            44 to "netherlands/crime-and-investigation-nl",
            45 to "germany/das-erste-de",
            46 to "germany/zdf-de",
            47 to "germany/rtl-de",
            48 to "germany/wdr-de",
            49 to "germany/arte-de",
            50 to "international/filmbox-int",
            51 to "netherlands/viaplay-tv-nl",
            52 to "netherlands/nick-toons-nl",
            53 to "netherlands/nick-jr-nl",
            55 to "united-kingdom/cartoonito-uk",
            57 to "netherlands/baby-tv-nl",
            59 to "netherlands/rtl-telekids-nl",
            60 to "netherlands/cartoon-network-nl",
            61 to "international/cnn-international-int",
            71 to "netherlands/e-entertainment-nl",
            73 to "netherlands/rtl-lounge-nl",
            74 to "netherlands/rtl-crime-nl",
            77 to "netherlands/out-tv-nl",
            78 to "netherlands/family7-nl",
            81 to "netherlands/npo1-extra-nl",
            82 to "netherlands/npo2-extra-nl",
            83 to "netherlands/npo-politiek-en-nieuws-nl",
            84 to "netherlands/discovery-science-nl",
            85 to "netherlands/animal-planet-nl",
            87 to "netherlands/national-geographic-wild-nl",
            89 to "netherlands/ons-nl",
            92 to "france/france-2-fr",
            93 to "netherlands/rtv7-nl",
            111 to "netherlands/slam-tv-nl",
            112 to "netherlands/tv-oranje-nl",
            113 to "netherlands/192-tv-nl",
            119 to "germany/vox-de",
            120 to "germany/ndr-de",
            121 to "germany/sat-1-de",
            122 to "germany/tagesschau24-de",
            123 to "germany/3sat-de",
            124 to "germany/pro-sieben-de",
            125 to "germany/kika-de",
            126 to "germany/one-de",
            127 to "germany/rtl-zwei-de",
            128 to "germany/rtl-super-de",
            129 to "united-kingdom/utsav-bharat-uk",
            130 to "india/zee-cinema-in",
            131 to "india/zee-tv-in",
            132 to "india/sony-entertainment-television-in",
            133 to "india/sony-max-in",
            134 to "netherlands/utsav-plus-nl",
            135 to "india/colors-in",
            136 to "netherlands/utsav-gold-nl",
            140 to "turkey/trt-turk-tr",
            141 to "turkey/trt-haber-tr",
            142 to "turkey/atv-avrupa-tr",
            143 to "turkey/euro-d-tr",
            144 to "turkey/euro-star-tr",
            145 to "turkey/show-turk-tr",
            146 to "turkey/tv8-int-tr",
            147 to "turkey/trt-cocuk-tr",
            148 to "turkey/trt-spor-tr",
            222 to "netherlands/espn-2-nl",
            223 to "netherlands/espn-3-nl",
            224 to "netherlands/espn-4-nl",
            230 to "netherlands/viaplay-tv-nl",
            233 to "netherlands/ziggo-sport-nl",
            234 to "netherlands/ziggo-sport-2-nl",
            235 to "netherlands/ziggo-sport-3-nl",
            236 to "netherlands/ziggo-sport-4-nl",
            237 to "netherlands/ziggo-sport-5-nl",
            238 to "netherlands/ziggo-sport-6-nl",
            245 to "netherlands/film1-premiere-nl",
            246 to "netherlands/film1-action-nl",
            247 to "netherlands/film1-family-nl",
            248 to "netherlands/film1-drama-nl",
            501 to "netherlands/rtv-noord-nl",
            502 to "netherlands/omrop-fryslan-nl",
            503 to "netherlands/rtv-drenthe-nl",
            504 to "netherlands/rtv-oost-nl",
            505 to "netherlands/omroep-flevoland-nl",
            506 to "netherlands/omroep-gelderland-nl",
            507 to "netherlands/omroep-brabant-nl",
            508 to "netherlands/l1-tv-nl",
            509 to "netherlands/nh-nl",
            510 to "netherlands/at5-nl",
            511 to "netherlands/rtv-utrecht-nl",
            512 to "netherlands/omroep-west-nl",
            513 to "netherlands/rtv-rijnmond-nl",
            514 to "netherlands/omroep-zeeland-nl",
            538 to "netherlands/tv-538-nl",
            693 to "united-kingdom/love-nature-4k-uk",
            694 to "netherlands/espn-ultra-hd-nl",
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
    private lateinit var kodiSection: View
    private lateinit var kodiHostInput: EditText
    private lateinit var kodiPortInput: EditText
    private lateinit var kodiUserInput: EditText
    private lateinit var kodiPassInput: EditText
    private lateinit var kodiStatus: TextView
    private lateinit var osKeyInput: EditText
    private lateinit var tmdbSection: View
    private lateinit var tmdbKeyInput: EditText
    private lateinit var tmdbStatus: TextView
    private val tmdb by lazy { TmdbApi { prefs().getString(KEY_TMDB, "") ?: "" } }
    private lateinit var osUserInput: EditText
    private lateinit var osPassInput: EditText
    private lateinit var osLangInput: EditText
    private val openSubs by lazy {
        OpenSubs(
            { prefs().getString(KEY_OS_KEY, "")!!.trim() },
            { prefs().getString(KEY_OS_USER, "")!!.trim() },
            { prefs().getString(KEY_OS_PASS, "") ?: "" },
        )
    }
    private fun osLangs() = prefs().getString(KEY_OS_LANG, "")!!.trim().ifEmpty { "nl,en" }
    private val kodi by lazy {
        KodiApi(
            { prefs().getString(KEY_KODI_HOST, "")!!.trim().ifEmpty { KODI_DEFAULT_HOST } },
            { prefs().getString(KEY_KODI_PORT, "")!!.trim().ifEmpty { "8080" } },
            { prefs().getString(KEY_KODI_USER, "")!!.trim().ifEmpty { "kodi" } },
            { prefs().getString(KEY_KODI_PASS, "") ?: "" },
        )
    }
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
        kodiSection = findViewById(R.id.kodiSection)
        kodiHostInput = findViewById(R.id.kodiHostInput)
        kodiPortInput = findViewById(R.id.kodiPortInput)
        kodiUserInput = findViewById(R.id.kodiUserInput)
        kodiPassInput = findViewById(R.id.kodiPassInput)
        kodiStatus = findViewById(R.id.kodiStatus)
        osKeyInput = findViewById(R.id.osKeyInput)
        tmdbSection = findViewById(R.id.tmdbSection)
        tmdbKeyInput = findViewById(R.id.tmdbKeyInput)
        tmdbStatus = findViewById(R.id.tmdbStatus)
        osUserInput = findViewById(R.id.osUserInput)
        osPassInput = findViewById(R.id.osPassInput)
        osLangInput = findViewById(R.id.osLangInput)
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
        findViewById<View>(R.id.menuFritz).visibility = View.GONE   // Fritzbox-tab is vervangen door Auto
        menuItem(R.id.menuSpotify, R.drawable.ic_music, PAGE_SPOTIFY)
        menuItem(R.id.menuMusic, R.drawable.ic_folder_music, PAGE_MUSIC)
        findViewById<Button>(R.id.saveMusic).setOnClickListener { saveMusicSettings() }
        menuItem(R.id.menuKodi, R.drawable.logo_kodi, PAGE_KODI)
        findViewById<Button>(R.id.saveKodi).setOnClickListener { saveKodiSettings() }
        menuItem(R.id.menuTmdb, R.drawable.ic_search, PAGE_TMDB)
        findViewById<View>(R.id.menuTmdb).visibility = View.GONE   // "Wat kijken?" voorlopig uit
        findViewById<Button>(R.id.saveTmdb).setOnClickListener { saveTmdbSettings() }
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

    /** Dialoog/onderblad waarin de volumeknoppen van de telefoon gewoon de receiver blijven regelen. */
    private fun sheet(): Dialog = object : Dialog(this) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                return this@MainActivity.dispatchKeyEvent(event)
            }
            return super.dispatchKeyEvent(event)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val down = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        // Speelt er muziek op deze telefoon zelf, dan regelen de knoppen gewoon het telefoonvolume
        val phonePlaying = runCatching { localPlayer?.isPlaying == true }.getOrDefault(false)
        if ((up || down) && !phonePlaying && settingsPanel.visibility != View.VISIBLE && recOn == true && recVol != null) {
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
                PAGE_HA, PAGE_PVE, PAGE_FRITZ, PAGE_SPOTIFY, PAGE_MUSIC, PAGE_KODI, PAGE_TMDB -> showSettingsPage(PAGE_API)
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
            else -> refreshCar()
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
    private val PAGE_KODI = 8
    private val PAGE_TMDB = 9
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
        kodiSection.visibility = if (page == PAGE_KODI) View.VISIBLE else View.GONE
        tmdbSection.visibility = if (page == PAGE_TMDB) View.VISIBLE else View.GONE
        updateSection.visibility = if (page == PAGE_UPDATE) View.VISIBLE else View.GONE
        settingsTitle.text = when (page) {
            PAGE_API -> "API's"
            PAGE_HA -> "Home Assistant"
            PAGE_PVE -> "Proxmox"
            PAGE_FRITZ -> "Fritzbox"
            PAGE_SPOTIFY -> "Spotify"
            PAGE_MUSIC -> "Muziek (netwerkmap)"
            PAGE_KODI -> "Kodi"
            PAGE_TMDB -> "Films & series (TMDB)"
            PAGE_UPDATE -> "Updates"
            else -> "Instellingen"
        }
        if (page == PAGE_TMDB) {
            tmdbKeyInput.setText(prefs().getString(KEY_TMDB, ""))
            tmdbStatus.text = ""
        }
        if (page == PAGE_KODI) {
            kodiHostInput.setText(prefs().getString(KEY_KODI_HOST, KODI_DEFAULT_HOST))
            kodiPortInput.setText(prefs().getString(KEY_KODI_PORT, "8080"))
            kodiUserInput.setText(prefs().getString(KEY_KODI_USER, "kodi"))
            kodiPassInput.setText(prefs().getString(KEY_KODI_PASS, ""))
            osKeyInput.setText(prefs().getString(KEY_OS_KEY, ""))
            osUserInput.setText(prefs().getString(KEY_OS_USER, ""))
            osPassInput.setText(prefs().getString(KEY_OS_PASS, ""))
            osLangInput.setText(osLangs())
            kodiStatus.text = ""
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

    // ================= Auto: laden alleen binnen een tijdvenster =================
    //
    // De regel draait als automatisering in Home Assistant (ook als de app dicht is):
    //  - op de begintijd: laden aan
    //  - op de eindtijd: laden uit
    //  - gaat laden buiten het venster toch aan (bijv. na insteken): meteen weer uit
    //  - stekker erin binnen het venster: laden aan

    private val CAR_AUTOMATION_ID = "thuisbasis_laadvenster"
    private val CAR_AUTOMATION_ENTITY = "automation.thuisbasis_laadvenster"
    @Volatile private var carBuilt = false
    private lateinit var carBattery: TextView
    private lateinit var carState: TextView
    private lateinit var carSwitchLabel: TextView
    private lateinit var carEnabled: Switch
    private lateinit var carFrom: Button
    private lateinit var carTo: Button
    private lateinit var carSave: Button
    private lateinit var carArm: Button
    private var carArmed = false
    private lateinit var carInfo: TextView
    private var carFromMin = 23 * 60
    private lateinit var carPrices: LinearLayout
    private var carPricesKey = ""
    private var carToMin = 7 * 60
    private var carDirty = false      // tijden gewijzigd maar nog niet opgeslagen
    @Volatile private var carNeedsRewrite = false   // automatisering gebruikte de verkeerde schakelaar
    private var carUpdating = false   // schakelaar wordt door de app zelf gezet

    private fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    /** MySkoda: (schakelaar "laden" van de auto, knop "wakker maken"). Om een slapende auto te laten starten. */
    private fun skodaEntities(): Pair<String?, String?> {
        val tpl = "{% for e in integration_entities('myskoda') %}{{ e }}\n{% endfor %}"
        val ids = runCatching {
            haRequest("POST", "/api/template", JSONObject().put("template", tpl).toString()).lines().map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
        val sw = ids.firstOrNull { it.startsWith("switch.") && it.endsWith("_charging") }
            ?: ids.firstOrNull { it.startsWith("switch.") && "charg" in it && listOf("care", "reduced", "unlock", "auto").none { x -> x in it } }
        val wake = ids.firstOrNull { it.startsWith("button.") && "wake" in it }
        return sw to wake
    }

    /** Keuzelijst "Slim laden" van de Peblar (eigen laadschema van de paal); null = niet gevonden. */
    @Volatile private var peblarSmart: String? = null
    @Volatile private var carSkoda: Pair<String?, String?> = null to null

    private fun isPhaseSwitch(e: String) = listOf("phase", "fase", "single", "enkel").any { it in e.lowercase(NL) }

    /** Entiteiten van de Peblar-laadpaal: (schakelaar laden, status-sensor, vermogen-sensor). */
    private fun peblarEntities(): Triple<String?, String?, String?> {
        val tpl = "{% for e in integration_entities('peblar') %}{{ e }}|{{ state_attr(e, 'unit_of_measurement') }}|{{ states(e) }}\n{% endfor %}"
        val rows = runCatching {
            haRequest("POST", "/api/template", JSONObject().put("template", tpl).toString())
                .lines().filter { it.contains('|') }.map { it.split('|') }
        }.getOrDefault(emptyList())
        // Fase-schakelaar ("dwing enkelvoudige fase af" / force single phase) NOOIT gebruiken
        val sw = rows.map { it[0] }.firstOrNull { it.startsWith("switch.") && !isPhaseSwitch(it) }
        // Status: de sensor met waarden als charging / suspended / no_ev_connected
        val st = rows.firstOrNull {
            it[0].startsWith("sensor.") && (it.getOrNull(2) in listOf("charging", "suspended", "no_ev_connected", "error", "fault", "invalid")
                || it[0].endsWith("_state") || "cp_state" in it[0])
        }?.get(0)
        val pw = rows.firstOrNull {
            it[0].startsWith("sensor.") && ("power" in it[0] || "vermogen" in it[0]) && it.getOrNull(1) in listOf("W", "kW") && !isPhaseSwitch(it[0])
        }?.get(0)
        peblarSmart = rows.map { it[0] }.firstOrNull { it.startsWith("select.") && ("slim" in it || "smart" in it) }
        return Triple(sw, st, pw)
    }

    /** Schakelaar waarmee laden aan/uit gaat (zelf gezocht, of eerder gekozen). */
    private fun carChargeSwitch(): String? = prefs().getString(KEY_CAR_SWITCH, null)?.ifBlank { null }

    private fun buildCar() {
        if (carBuilt) return
        carBuilt = true
        fritzList.removeAllViews()
        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(0xFF1E1E1E.toInt()); cornerRadius = dp(14).toFloat() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(0xFFB0B0B0.toInt()) }

        // Status
        val status = card()
        status.addView(label("🚗  Škoda Elroq"))
        carBattery = TextView(this).apply {
            textSize = 34f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); text = "—"
        }
        carState = TextView(this).apply { textSize = 14f; setTextColor(0xFFB0B0B0.toInt()) }
        status.addView(carBattery)
        status.addView(carState)
        fritzList.addView(status)
        // Laadlimiet van de auto (verhuisd van het Dashboard)
        (chargeCard.root.parent as? ViewGroup)?.removeView(chargeCard.root)
        chargeCard.root.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        fritzList.addView(chargeCard.root)

        // Laadvenster
        val win = card()
        win.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Laden tussen"
                textSize = 17f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            carEnabled = Switch(this@MainActivity).apply { visibility = View.GONE }
            addView(carEnabled)
        })
        fun timeBtn(onPick: (Int) -> Unit, get: () -> Int) = Button(this).apply {
            textSize = 26f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(12).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, dp(72), 1f)
            setOnClickListener {
                val m = get()
                android.app.TimePickerDialog(this@MainActivity, { _, h, min -> onPick(h * 60 + min) }, m / 60, m % 60, true).show()
            }
        }
        carFrom = timeBtn({ carFromMin = it; carFrom.text = hhmm(it); carDirty = true; carInfo.text = "Nog niet opgeslagen" }) { carFromMin }
        carTo = timeBtn({ carToMin = it; carTo.text = hhmm(it); carDirty = true; carInfo.text = "Nog niet opgeslagen" }) { carToMin }
        win.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(label("van"))
                addView(carFrom.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)) })
            })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(12), 1))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(label("tot"))
                addView(carTo.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)) })
            })
        })
        carInfo = TextView(this).apply { textSize = 13f; setTextColor(0xFF9A9A9A.toInt()); setPadding(0, dp(8), 0, dp(8)) }
        win.addView(carInfo)
        // Grote knop: laadopdracht geven of intrekken. Zonder opdracht laadt de paal niet.
        carArm = Button(this).apply {
            textSize = 17f
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(0xFF4FC3F7.toInt()); cornerRadius = dp(14).toFloat() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { topMargin = dp(4) }
            text = "⚡  Laden in dit tijdvak"
            setOnClickListener { if (carArmed) disarmCar() else saveCarWindow(arm = true) }
        }
        win.addView(carArm)
        carSave = Button(this).apply {
            text = "Alleen tijden opslaan"
            setOnClickListener { saveCarWindow(arm = carArmed) }
        }
        win.addView(carSave)
        carSwitchLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF777777.toInt())
            setPadding(0, dp(10), 0, 0)
            isClickable = true
            setOnClickListener { chooseCarSwitch {} }
        }
        win.addView(carSwitchLabel)
        fritzList.addView(win)

        // Stroomprijs per uur (alles wat Zonneplan al weet), goedkoopste uren groen
        val prices = card()
        prices.addView(label("⚡  Stroomprijs per uur (Zonneplan) · tik op een uur om de begintijd te zetten"))
        carPrices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        prices.addView(carPrices)
        fritzList.addView(prices)

        carFrom.text = hhmm(carFromMin)
        carTo.text = hhmm(carToMin)
    }

    /** Alle bekende uurprijzen van Zonneplan vanaf dit uur, per dag, met balkjes. */
    private fun showCarPrices(o: JSONObject?) {
        val fc = o?.optJSONObject("attributes")?.optJSONArray("forecast")
        val hourStart = System.currentTimeMillis() / 3_600_000 * 3_600_000
        val list = mutableListOf<Pair<Long, Double>>()
        if (fc != null) for (i in 0 until fc.length()) {
            val p = fc.optJSONObject(i) ?: continue
            val ts = parseIsoMillis(p.optString("datetime")) ?: continue
            if (ts < hourStart) continue
            var v = p.optDouble("electricity_price", Double.NaN)
            if (v.isNaN()) continue
            if (v > 10) v /= 10_000_000.0
            list.add(ts to v)
        }
        val pts = list.distinctBy { it.first }.sortedBy { it.first }
        val key = pts.joinToString { "${it.first}:${it.second}" }
        if (key == carPricesKey) return
        carPricesKey = key
        carPrices.removeAllViews()
        if (pts.isEmpty()) {
            carPrices.addView(TextView(this).apply {
                text = if (o == null) "Zonneplan niet bereikbaar" else "Geen prijzen bekend"
                setTextColor(0xFF9A9A9A.toInt())
            })
            return
        }
        val maxP = pts.maxOf { it.second }.coerceAtLeast(0.01)
        // Goedkoopste kwart groen, duurste kwart oranje
        val sorted = pts.map { it.second }.sorted()
        val cheap = sorted[(sorted.size - 1) / 4]
        val dear = sorted[sorted.size - 1 - (sorted.size - 1) / 4]
        val zone = java.time.ZoneId.systemDefault()
        val dayFmt = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", NL)
        var lastDay = ""
        for ((ts, v) in pts) {
            val t = java.time.Instant.ofEpochMilli(ts).atZone(zone)
            val day = t.format(dayFmt).replaceFirstChar { it.uppercase() }
            if (day != lastDay) {
                lastDay = day
                carPrices.addView(TextView(this).apply {
                    text = day
                    textSize = 13f
                    setTextColor(0xFF4FC3F7.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(10), 0, dp(4))
                })
            }
            val color = when {
                v <= cheap -> 0xFF43A047.toInt()
                v >= dear -> 0xFFE57000.toInt()
                else -> 0xFF6B6B6B.toInt()
            }
            val now = ts == hourStart
            val frac = (v.coerceAtLeast(0.0) / maxP).toFloat().coerceIn(0.02f, 1f)
            carPrices.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(30)
                setPadding(dp(4), dp(2), dp(4), dp(2))
                if (now) background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(6).toFloat() }
                addView(TextView(this@MainActivity).apply {
                    text = if (now) "nu" else "%02d:00".format(t.hour)
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    if (now) setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0, dp(12), 1f)
                    addView(View(this@MainActivity).apply {
                        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(3).toFloat() }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, frac))
                    addView(View(this@MainActivity), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - frac + 0.0001f))
                })
                addView(TextView(this@MainActivity).apply {
                    text = String.format(NL, "€ %.3f", v)
                    textSize = 13f
                    setTextColor(if (v <= cheap) 0xFF7CE38A.toInt() else 0xFFCFCFCF.toInt())
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                isClickable = true
                setOnClickListener {
                    carFromMin = t.hour * 60
                    carFrom.text = hhmm(carFromMin)
                    carDirty = true
                    carInfo.text = "Begintijd ${hhmm(carFromMin)} gekozen – tik op Laden of Alleen tijden opslaan"
                }
            })
        }
    }

    /** Laad-schakelaars in HA zoeken; bij precies één (van de Škoda) die nemen, anders laten kiezen. */
    private fun chooseCarSwitch(onChosen: (String) -> Unit) {
        thread {
            val tpl = "{% set p = integration_entities('peblar') %}" +
                "{% for s in states.switch if s.entity_id in p or 'peblar' in s.entity_id or 'charg' in s.entity_id or 'laad' in s.entity_id %}" +
                "{{ s.entity_id }}|{{ s.name }}\n{% endfor %}"
            val found = runCatching {
                haRequest("POST", "/api/template", JSONObject().put("template", tpl).toString())
                    .lines().filter { it.contains('|') }.map { it.substringBefore('|') to it.substringAfter('|') }
                    .filterNot { isPhaseSwitch(it.first) }
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (found.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("Geen laadschakelaar gevonden")
                        .setMessage("In Home Assistant staat geen schakelaar van de Peblar-laadpaal. Staat de Peblar-integratie aan?")
                        .setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("Welke schakelaar zet het laden aan/uit?")
                    .setItems(found.map { "${it.second}\n${it.first}" }.toTypedArray()) { _, i ->
                        prefs().edit().putString(KEY_CAR_SWITCH, found[i].first).putBoolean(KEY_CAR_SWITCH_MANUAL, true).apply()
                        carSwitchLabel.text = "Laadschakelaar: ${found[i].first}  (tik om te wijzigen)"
                        onChosen(found[i].first)
                    }
                    .setNegativeButton("Annuleren", null)
                    .show()
            }
        }
    }

    private fun refreshCar() {
        buildCar()
        thread {
            // Laden gaat via de Peblar-laadpaal: die schakelaar nemen (ook als eerder iets anders gekozen was)
            val (pSw, pState, pPower) = peblarEntities()
            carSkoda = skodaEntities()
            val saved = carChargeSwitch()
            if (saved != null && isPhaseSwitch(saved)) {
                // Eerder per ongeluk de fase-schakelaar gekozen: terug naar 3 fasen en die nooit meer gebruiken
                quietService("switch/turn_off", JSONObject().put("entity_id", saved))
                prefs().edit().remove(KEY_CAR_SWITCH).remove(KEY_CAR_SWITCH_MANUAL).apply()
                carNeedsRewrite = true
            }
            if (pSw != null && (carChargeSwitch() == null || !carChargeSwitch()!!.contains("peblar") && !prefs().getBoolean(KEY_CAR_SWITCH_MANUAL, false))) {
                prefs().edit().putString(KEY_CAR_SWITCH, pSw).apply()
            }
            val sw = carChargeSwitch()
            val pst = pState?.let { stateOf(it) }?.optString("state")
            val pkw = pPower?.let { stateOf(it) }?.let { o ->
                val v = o.optString("state").toDoubleOrNull()
                val unit = o.optJSONObject("attributes")?.optString("unit_of_measurement")
                v?.let { if (unit == "W") it / 1000 else it }
            }
            val bat = stateOf(DASH_CAR_BATTERY)
            val swState = sw?.let { stateOf(it) }
            val limit = stateOf(DASH_CHARGE_LIMIT)
            val price = stateOf(DASH_PRICE)
            runOnUiThread { showCharge(limit, bat); showCarPrices(price) }
            val auto = stateOf(CAR_AUTOMATION_ENTITY)
            val cfg = runCatching {
                JSONObject(haRequest("GET", "/api/config/automation/config/$CAR_AUTOMATION_ID", null))
            }.getOrNull()
            runOnUiThread {
                val b = bat?.optString("state")?.toDoubleOrNull()
                carBattery.text = if (b != null) "${b.toInt()}%" else "—"
                // Status van de laadpaal zelf (Peblar): laden / gepauzeerd / geen auto
                val charging = if (pst != null) pst == "charging" else swState?.optString("state") == "on"
                carState.text = listOfNotNull(
                    when (pst) {
                        "charging" -> "⚡ Laadt" + (pkw?.takeIf { it > 0.05 }?.let { String.format(NL, " met %.1f kW", it) } ?: "")
                        "suspended" -> "Stekker erin, laadt niet"
                        "no_ev_connected" -> "Geen auto aangesloten"
                        "error", "fault", "invalid" -> "⚠ Laadpaal meldt een fout ($pst)"
                        null -> if (charging) "⚡ Aan het laden" else "Laadt niet"
                        else -> pst
                    },
                    "Peblar".takeIf { pst != null },
                    swState?.optString("state")?.let { if (it == "on") "laden toegestaan" else "laden uit" },
                ).joinToString("  ·  ")
                carSwitchLabel.text = if (sw != null) "Laadschakelaar: $sw  (tik om te wijzigen)" else "Laadschakelaar kiezen…"
                // Opgeslagen venster uit de automatisering halen
                if (!carDirty) cfg?.optString("description")?.let { d ->
                    Regex("(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})").find(d)?.let { m ->
                        val (h1, m1, h2, m2) = m.destructured
                        carFromMin = h1.toInt() * 60 + m1.toInt(); carToMin = h2.toInt() * 60 + m2.toInt()
                        carFrom.text = hhmm(carFromMin); carTo.text = hhmm(carToMin)
                    }
                }
                val active = auto?.optString("state") == "on"
                carArmed = active
                if (carNeedsRewrite) {
                    carNeedsRewrite = false
                    // Automatisering opnieuw schrijven met de juiste laadschakelaar (of uitzetten)
                    if (carChargeSwitch() != null) saveCarWindow(arm = active)
                    else quietService("automation/turn_off", JSONObject().put("entity_id", CAR_AUTOMATION_ENTITY))
                }
                carArm.text = if (active) "■  Laadopdracht intrekken" else "⚡  Laden in dit tijdvak"
                (carArm.background as? GradientDrawable)?.setColor(if (active) 0xFFFFB300.toInt() else 0xFF4FC3F7.toInt())
                if (!carDirty) carInfo.text = when {
                    active && charging -> "Laadopdracht actief: laadt nu, stopt om ${hhmm(carToMin)}."
                    active -> "Laadopdracht actief: laadt tussen ${hhmm(carFromMin)} en ${hhmm(carToMin)} en stopt daarna vanzelf. Dit regelt Home Assistant, ook als de app dicht is."
                    else -> "Geen laadopdracht: de laadpaal laadt niet. Tik op de knop om binnen het tijdvak te laden."
                }
            }
        }
    }

    /** Automatisering in HA aanmaken/bijwerken en aanzetten. */
    /**
     * Bewaker (staat altijd aan): gaat "Opladen" aan of begint de paal te laden terwijl er geen
     * laadopdracht is (of buiten het tijdvak), dan meteen weer uit. Zo laadt hij nooit zonder jouw opdracht.
     */
    private fun writeCarGuard(sw: String, from: String, to: String) {
        val status = peblarEntities().second
        val triggers = JSONArray()
            .put(JSONObject().put("trigger", "state").put("entity_id", sw).put("to", "on"))
            .put(JSONObject().put("trigger", "homeassistant").put("event", "start"))
            .put(JSONObject().put("trigger", "time_pattern").put("minutes", "/15"))
        if (status != null) triggers.put(JSONObject().put("trigger", "state").put("entity_id", status).put("to", "charging"))
        val allowed = JSONObject().put("condition", "and").put("conditions", JSONArray()
            .put(JSONObject().put("condition", "state").put("entity_id", CAR_AUTOMATION_ENTITY).put("state", "on"))
            .put(JSONObject().put("condition", "time").put("after", from).put("before", to)))
        val cfg = JSONObject()
            .put("id", "thuisbasis_laadbewaker")
            .put("alias", "Thuisbasis laadbewaker")
            .put("description", "Laadpaal alleen laten laden met een laadopdracht uit de Thuisbasis-app, binnen het tijdvak.")
            .put("mode", "single")
            .put("triggers", triggers)
            .put("conditions", JSONArray()
                .put(JSONObject().put("condition", "state").put("entity_id", sw).put("state", "on"))
                .put(JSONObject().put("condition", "not").put("conditions", JSONArray().put(allowed))))
            .put("actions", JSONArray().put(JSONObject().put("action", "switch.turn_off").put("target", JSONObject().put("entity_id", sw))))
        haRequest("POST", "/api/config/automation/config/thuisbasis_laadbewaker", cfg.toString())
        quietService("automation/turn_on", JSONObject().put("entity_id", "automation.thuisbasis_laadbewaker"))
    }

    /** Laadopdracht intrekken: automatisering uit en laadpaal uit. */
    private fun disarmCar() {
        val sw = carChargeSwitch()
        carInfo.text = "Laadopdracht intrekken…"
        thread {
            quietService("automation/turn_off", JSONObject().put("entity_id", CAR_AUTOMATION_ENTITY))
            sw?.let { quietService("switch/turn_off", JSONObject().put("entity_id", it)) }
            runOnUiThread { handler.postDelayed({ refreshCar() }, 1_200) }
        }
    }

    /**
     * Automatisering in HA aanmaken/bijwerken. [arm] = laadopdracht geven: binnen het tijdvak laden,
     * daarbuiten niet; na de eindtijd vervalt de opdracht vanzelf.
     */
    private fun saveCarWindow(arm: Boolean) {
        val sw = carChargeSwitch()
        if (sw == null) { chooseCarSwitch { saveCarWindow(arm) }; return }
        val from = hhmm(carFromMin) + ":00"
        val to = hhmm(carToMin) + ":00"
        val inWindow = JSONObject().put("condition", "time").put("after", from).put("before", to)
        val smart = peblarSmart
        val turnOn = JSONArray()
        // Eigen laadschema van de Peblar uitzetten ("Standaard" = gewoon laden), anders blijft hij gepauzeerd
        if (smart != null) turnOn.put(JSONObject().put("action", "select.select_option").put("continue_on_error", true)
            .put("target", JSONObject().put("entity_id", smart)).put("data", JSONObject().put("option", "default")))
        turnOn.put(JSONObject().put("action", "switch.turn_on").put("target", JSONObject().put("entity_id", sw)))
        // Slapende Škoda wekken en laden laten starten (anders merkt hij niet dat de paal stroom geeft)
        val (skSw, skWake) = carSkoda
        if (skWake != null || skSw != null) {
            turnOn.put(JSONObject().put("delay", JSONObject().put("seconds", 30)))
            if (skWake != null) turnOn.put(JSONObject().put("action", "button.press").put("continue_on_error", true)
                .put("target", JSONObject().put("entity_id", skWake)))
            if (skSw != null) {
                turnOn.put(JSONObject().put("delay", JSONObject().put("seconds", 45)))
                turnOn.put(JSONObject().put("action", "switch.turn_on").put("continue_on_error", true)
                    .put("target", JSONObject().put("entity_id", skSw)))
            }
        }
        val turnOff = JSONArray().put(JSONObject().put("action", "switch.turn_off").put("target", JSONObject().put("entity_id", sw)))
        fun trig(id: String) = JSONObject().put("condition", "trigger").put("id", id)
        carInfo.text = "Opslaan…"
        thread {
            val r = runCatching {
                // Status van de laadpaal: auto aangesloten binnen het venster = laden aan
                val plug = peblarEntities().second
                val triggers = JSONArray()
                    .put(JSONObject().put("trigger", "time").put("at", from).put("id", "start"))
                    .put(JSONObject().put("trigger", "time").put("at", to).put("id", "stop"))
                    .put(JSONObject().put("trigger", "state").put("entity_id", sw).put("to", "on").put("id", "aan"))
                    .put(JSONObject().put("trigger", "homeassistant").put("event", "start").put("id", "herstart"))
                if (plug != null) triggers.put(JSONObject().put("trigger", "state").put("entity_id", plug)
                    .put("from", "no_ev_connected").put("for", JSONObject().put("seconds", 30)).put("id", "stekker"))
                // Begint de laadpaal buiten het venster toch te laden: ook dan meteen uit
                if (plug != null) triggers.put(JSONObject().put("trigger", "state").put("entity_id", plug)
                    .put("to", "charging").put("id", "laadt"))
                val choose = JSONArray()
                    .put(JSONObject().put("conditions", JSONArray().put(trig("start"))).put("sequence", turnOn))
                    .put(JSONObject().put("conditions", JSONArray().put(trig("stop"))).put("sequence", JSONArray(turnOff.toString())
                        // Eindtijd: laden uit en de opdracht vervalt (volgende keer opnieuw op de knop)
                        .put(JSONObject().put("action", "automation.turn_off").put("target", JSONObject().put("entity_id", CAR_AUTOMATION_ENTITY)))))
                    .put(JSONObject().put("conditions", JSONArray().put(trig("stekker")).put(inWindow)).put("sequence", turnOn))
                    .put(JSONObject().put("conditions", JSONArray().put(
                        JSONObject().put("condition", "not").put("conditions", JSONArray().put(inWindow))
                    )).put("sequence", turnOff))
                val cfg = JSONObject()
                    .put("id", CAR_AUTOMATION_ID)
                    .put("alias", "Thuisbasis laadvenster")
                    .put("description", "Laadopdracht ${hhmm(carFromMin)}-${hhmm(carToMin)} via de Peblar-laadpaal (Thuisbasis-app). Aan = opdracht actief; na de eindtijd gaat hij vanzelf uit.")
                    .put("mode", "queued")
                    .put("triggers", triggers)
                    .put("conditions", JSONArray())
                    .put("actions", JSONArray().put(JSONObject().put("choose", choose)))
                haRequest("POST", "/api/config/automation/config/$CAR_AUTOMATION_ID", cfg.toString())
                writeCarGuard(sw, from, to)
                Thread.sleep(1_500)   // HA laadt de automatiseringen opnieuw
                val nowMin = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
                val inside = if (carFromMin <= carToMin) nowMin in carFromMin until carToMin
                    else nowMin >= carFromMin || nowMin < carToMin
                if (arm) {
                    quietService("automation/turn_on", JSONObject().put("entity_id", CAR_AUTOMATION_ENTITY))
                    // Meteen toepassen: binnen het tijdvak nu laden, erbuiten (nog) niet
                    if (inside) peblarSmart?.let {
                        quietService("select/select_option", JSONObject().put("entity_id", it).put("option", "default"))
                    }
                    quietService(if (inside) "switch/turn_on" else "switch/turn_off", JSONObject().put("entity_id", sw))
                    if (inside) {
                        // Auto wekken en laten starten (op de achtergrond, duurt ruim een minuut)
                        val (skSw, skWake) = carSkoda
                        thread {
                            Thread.sleep(30_000)
                            skWake?.let { quietService("button/press", JSONObject().put("entity_id", it)) }
                            Thread.sleep(45_000)
                            skSw?.let { quietService("switch/turn_on", JSONObject().put("entity_id", it)) }
                        }
                    }
                } else {
                    // Alleen tijden opgeslagen: geen opdracht, dus niet laden
                    quietService("automation/turn_off", JSONObject().put("entity_id", CAR_AUTOMATION_ENTITY))
                }
            }
            runOnUiThread {
                if (r.isSuccess) carDirty = false
                carInfo.text = r.exceptionOrNull()?.let { "Opslaan mislukt: ${it.message}" }
                    ?: if (arm) "Laadopdracht gegeven ✓ Laadt tussen ${hhmm(carFromMin)} en ${hhmm(carToMin)}." else "Tijden opgeslagen ✓"
                handler.postDelayed({ refreshCar() }, 1_500)
            }
        }
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

        /** Wijziging geannuleerd: meteen weer de echte waarde laten zien. */
        fun release() { lastCommit = 0L }

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
            // Eerst bevestigen: een per ongeluk aangeraakte schuif (bijv. tijdens scrollen) verandert zo niets
            AlertDialog.Builder(this)
                .setTitle("Laadlimiet naar ${v.toInt()}%?")
                .setPositiveButton("Ja, wijzigen") { _, _ ->
                    haService("number", "set_value", JSONObject().put("entity_id", DASH_CHARGE_LIMIT).put("value", v))
                }
                .setNegativeButton("Annuleren") { _, _ -> chargeSlider.release(); refreshCar() }
                .setOnCancelListener { chargeSlider.release(); refreshCar() }
                .show()
        }
        chargeSlider.setRange(50.0, 100.0, 10.0)
        // De laadlimiet staat op het tabblad Auto (zie buildCar)
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
        val current = if (zoneUp) options.size else options.indexOfFirst { it.first == attrs?.optString("source") }
        AlertDialog.Builder(this)
            .setTitle("Bron kiezen")
            .setSingleChoiceItems((options.map { it.second } + "PiCore (boven)").toTypedArray(), current) { d, which ->
                d.dismiss()
                if (which == options.size) {
                    zoneUp = true
                    nowTitle.text = "PiCore (boven)"
                    mediaStatus.text = "Bron: PiCore boven"
                    refreshMedia()
                    return@setSingleChoiceItems
                }
                zoneUp = false
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
    private lateinit var nowSubsBtn: ImageView
    private lateinit var nowGuideBtn: TextView
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
                text = if (text.toString() == "❚❚") "▶" else "❚❚"
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
            setOnClickListener {
                val r = nowRemote
                if (r != null) showRemoteSheet(r, r == TVPLUS_REMOTE)
                else nowEntity?.let { showMediaSheet(it, nowTitle.text.toString().removeSuffix(" ▾")) }
            }
        }
        nowKeyboardBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_keyboard)
            contentDescription = "Tekst typen"
            setPadding(dp(10), dp(6), dp(6), dp(6))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { typeOnTv() }
        }
        nowSubsBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_subtitles)
            contentDescription = "Ondertitels en audio"
            setPadding(dp(10), dp(6), dp(6), dp(6))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { showKodiSubsSheet() }
        }
        nowGuideBtn = TextView(this).apply {
            text = "Gids"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                setColor(0xFF1565C0.toInt())
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            visibility = View.GONE
            // Gids openen op TV+ en meteen de afstandsbediening erbij
            setOnClickListener { showGuideSheet() }
        }
        nowBar.addView(nowChannelBtn)
        nowBar.addView(nowGuideBtn)
        nowBar.addView(nowSubsBtn)
        nowBar.addView(nowKeyboardBtn)
        nowBar.addView(nowRemoteBtn)
        nowBar.addView(nowPlayPause)
        mediaList.addView(nowBar)

        tileGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mediaList.addView(tileGrid)
        buildTiles()
    }

    private lateinit var tileGrid: LinearLayout

    /** Alle tegels met een vaste sleutel (voor de eigen volgorde). */
    private fun tileMakers(): List<Pair<String, (Boolean) -> View>> =
        listOf<Pair<String, (Boolean) -> View>>(
            "tv" to { second -> watchTvTile(second) },
            "heos" to { second -> heosTile(second) },
            "spotify" to { second -> spotifyTile(second) },
            "music" to { second -> musicTile(second) },
        ) + MEDIA_APPS.map { app -> app.pkg to { second: Boolean -> appTile(app, second) } }

    private fun tileOrder(): List<String> {
        val all = tileMakers().map { it.first }
        val saved = prefs().getString(KEY_TILE_ORDER, "")!!.split(',').filter { it in all }
        return saved + all.filter { it !in saved }   // nieuwe tegels achteraan
    }

    /** Tegels (opnieuw) opbouwen; ingedrukt houden en slepen verplaatst een tegel. */
    private fun buildTiles() {
        tileGrid.removeAllViews()
        val makers = tileMakers().toMap()
        tileOrder().chunked(TILE_COLS).forEach { keys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(TILE_GAP) }
            }
            keys.forEachIndexed { i, key ->
                val v = makers.getValue(key)(i > 0)
                makeDraggable(v, key)
                row.addView(v)
            }
            repeat(TILE_COLS - keys.size) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(TILE_H), 1f).apply { marginStart = dp(TILE_GAP) }
                })
            }
            tileGrid.addView(row)
        }
    }

    @Volatile private var tileMoved = false

    /** YouTube-tegel: zoekterm intypen en de resultaten meteen op de tv openen (of gewoon YouTube openen). */
    private fun showYoutubeSearch(app: MediaApp) {
        val input = EditText(this).apply {
            hint = "Zoeken op YouTube"
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        val d = AlertDialog.Builder(this)
            .setTitle("YouTube")
            .setView(input)
            .setPositiveButton("Zoeken op tv", null)
            .setNeutralButton("Alleen openen") { _, _ -> launchApp(app) }
            .setNegativeButton("Annuleren", null)
            .create()
        fun go() {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return
            d.dismiss()
            youtubeSearchOnTv(q)
        }
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { go() }
            input.requestFocus()
        }
        input.setOnEditorActionListener { _, _, _ -> go(); true }
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    private fun youtubeSearchOnTv(query: String) {
        val clean = query.replace(Regex("[\\\\'\"`$;&|<>()]"), " ").replace(Regex("\\s+"), " ").trim()
        mediaStatus.text = "\"$clean\" zoeken op YouTube…"
        thread { prepareWatching(SOURCE_APPS) }
        thread {
            quietService("remote/turn_on", JSONObject().put("entity_id", MEDIA_REMOTE))
            Thread.sleep(1_500)
            // Zoekopdracht rechtstreeks in de YouTube-app op de box (twee manieren; de werkende wint)
            quietService("androidtv/adb_command", JSONObject().put("entity_id", MEDIA_ADB).put("command",
                "am start -a android.intent.action.SEARCH --es query '$clean' $YOUTUBE_PKG"))
            Thread.sleep(1_200)
            quietService("androidtv/adb_command", JSONObject().put("entity_id", MEDIA_ADB).put("command",
                "am start -a android.intent.action.VIEW -d 'https://www.youtube.com/results?search_query=${Uri.encode(clean)}' $YOUTUBE_PKG"))
            runOnUiThread {
                mediaStatus.text = "\"$clean\" staat klaar op YouTube"
                handler.postDelayed({ refreshMedia() }, 2_500)
            }
        }
    }

    /** Kodi vastgelopen? Geforceerd stoppen (en desgewenst opnieuw starten) via ADB. */
    private fun kodiTileMenu() {
        AlertDialog.Builder(this)
            .setTitle("Kodi")
            .setItems(arrayOf("⏹  Kodi geforceerd stoppen", "↻  Kodi herstarten")) { _, i ->
                mediaStatus.text = if (i == 0) "Kodi stoppen…" else "Kodi herstarten…"
                thread {
                    quietService("androidtv/adb_command", JSONObject().put("entity_id", MEDIA_ADB)
                        .put("command", "am force-stop $KODI_PKG"))
                    if (i == 1) {
                        Thread.sleep(1_500)
                        quietService("androidtv/adb_command", JSONObject().put("entity_id", MEDIA_ADB).put(
                            "command", "monkey -p $KODI_PKG -c android.intent.category.LEANBACK_LAUNCHER -c android.intent.category.LAUNCHER 1"
                        ))
                    }
                    runOnUiThread {
                        mediaStatus.text = if (i == 0) "Kodi is gestopt" else "Kodi is opnieuw gestart"
                        handler.postDelayed({ refreshMedia() }, 2_000)
                    }
                }
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun makeDraggable(v: View, key: String) {
        v.setOnLongClickListener {
            tileMoved = false
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            v.startDragAndDrop(android.content.ClipData.newPlainText("tegel", key), View.DragShadowBuilder(v), key, 0)
            v.alpha = 0.3f
            true
        }
        v.setOnDragListener { target, e ->
            when (e.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> true
                android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                    if (e.localState != key) { target.scaleX = 0.9f; target.scaleY = 0.9f }
                    true
                }
                android.view.DragEvent.ACTION_DRAG_EXITED -> { target.scaleX = 1f; target.scaleY = 1f; true }
                android.view.DragEvent.ACTION_DROP -> {
                    val from = e.localState as? String
                    if (from != null && from != key) {
                        tileMoved = true
                        val order = tileOrder().toMutableList()
                        val toIdx = order.indexOf(key)
                        val fromIdx = order.indexOf(from)
                        order.removeAt(fromIdx)
                        order.add(toIdx, from)   // op de plek van de tegel waar je hem loslaat
                        prefs().edit().putString(KEY_TILE_ORDER, order.joinToString(",")).apply()
                    }
                    true
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    target.scaleX = 1f; target.scaleY = 1f
                    if (target === v && e.localState == key) {
                        // Kodi ingedrukt gehouden en losgelaten zonder te verslepen: Kodi-menu
                        if (!tileMoved && key == KODI_PKG) tileGrid.post { kodiTileMenu() }
                        // Na het slepen alles opnieuw tekenen in de nieuwe volgorde
                        v.alpha = 1f
                        tileGrid.post { buildTiles() }
                    }
                    true
                }
                else -> false
            }
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
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
        }
        return tile.apply {
            setupTile(app.color, second) {
                when (app.pkg) {
                    KODI_PKG -> showKodiSheet()
                    YOUTUBE_PKG -> showYoutubeSearch(app)
                    else -> launchApp(app)
                }
            }
        }
    }

    /** Tegel "TV kijken": tv en receiver aan, receiver op TV+ woonkamer. */
    private fun watchTvTile(second: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_tv_watch)
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        })
        addView(TextView(this@MainActivity).apply {
            text = "TV kijken"
            textSize = 13f
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

    // ================= Films & series zoeken (TMDB) en op de tv openen =================

    private fun saveTmdbSettings() {
        prefs().edit().putString(KEY_TMDB, tmdbKeyInput.text.toString().trim()).apply()
        tmdbStatus.text = "Testen…"
        thread {
            val r = runCatching { tmdb.trending().size }
            runOnUiThread {
                tmdbStatus.text = r.exceptionOrNull()?.let { "Lukt niet: ${it.message}" } ?: "Werkt ✓"
            }
        }
    }

    private fun searchTile(second: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_search)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })
        addView(TextView(this@MainActivity).apply {
            text = "Wat kijken?"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        setupTile(0xFF01414F.toInt(), second) { showTitleSearch() }
    }

    private fun showTitleSearch() {
        if (prefs().getString(KEY_TMDB, "").isNullOrBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Wat kijken?")
                .setMessage("Hiervoor is een gratis sleutel van TMDB (themoviedb.org) nodig. Vul die in via ⚙ → API's → Films & series.")
                .setPositiveButton("Naar instellingen") { _, _ -> showSettings(true); showSettingsPage(PAGE_TMDB) }
                .setNegativeButton("Annuleren", null)
                .show()
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val dialog = sheetDialog(content)
        val input = EditText(this).apply {
            hint = "Zoek een film of serie"
            setHintTextColor(0xFF777777.toInt())
            setTextColor(Color.WHITE)
            setSingleLine()
            textSize = 17f
        }
        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(input)
        content.addView(status)
        content.addView(ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(500))
        })
        var seq = 0
        fun show(items: List<TmdbApi.Title>, header: String) {
            list.removeAllViews()
            status.text = header
            items.forEach { t ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(10).toFloat() }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    isClickable = true
                    setOnClickListener { showTitleDetails(t) }
                }
                val img = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    background = GradientDrawable().apply { setColor(0xFF1A1A1A.toInt()); cornerRadius = dp(6).toFloat() }
                    layoutParams = LinearLayout.LayoutParams(dp(54), dp(80)).apply { marginEnd = dp(12) }
                }
                t.poster?.let { loadImage(img, it) }
                row.addView(img)
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = t.name + if (t.year.isNotEmpty()) " (${t.year})" else ""
                        textSize = 15f
                        setTextColor(Color.WHITE)
                        setTypeface(typeface, Typeface.BOLD)
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = (if (t.type == "tv") "Serie" else "Film") +
                            (if (t.rating > 0) "  ·  ★ " + String.format(NL, "%.1f", t.rating) else "")
                        textSize = 12f
                        setTextColor(0xFF4FC3F7.toInt())
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = t.overview
                        textSize = 12f
                        setTextColor(0xFFAAAAAA.toInt())
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                })
                list.addView(row)
            }
        }
        fun load(q: String) {
            val my = ++seq
            status.text = "Zoeken…"
            thread {
                val r = runCatching { if (q.isBlank()) tmdb.trending() else tmdb.search(q) }
                runOnUiThread {
                    if (my != seq) return@runOnUiThread
                    val items = r.getOrNull()
                    if (items == null) { status.text = "Mislukt: ${r.exceptionOrNull()?.message}"; return@runOnUiThread }
                    show(items, if (q.isBlank()) "Populair deze week" else if (items.isEmpty()) "Niets gevonden" else "${items.size} resultaten")
                }
            }
        }
        // Zoeken tijdens het typen (kort wachten tot je even stopt)
        val typed = Runnable { load(input.text.toString().trim()) }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                handler.removeCallbacks(typed)
                handler.postDelayed(typed, 400)
            }
        })
        dialog.setOnDismissListener { handler.removeCallbacks(typed) }
        dialog.show()
        load("")
    }

    /** Welke van mijn app-tegels hoort bij deze aanbieder (Netflix, Prime, …)? */
    private fun appForProvider(name: String): MediaApp? {
        val n = name.lowercase(NL)
        return MEDIA_APPS.firstOrNull { app ->
            val a = app.name.lowercase(NL)
            when {
                "netflix" in n -> a == "netflix"
                "prime" in n || "amazon" in n -> a.startsWith("prime")
                "viaplay" in n -> a == "viaplay"
                "nlziet" in n || "nl ziet" in n -> a == "nlziet"
                "youtube" in n -> a == "youtube"
                else -> false
            }
        }
    }

    private fun showTitleDetails(t: TmdbApi.Title) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }
        val dialog = sheetDialog(content)
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val img = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply { setColor(0xFF1A1A1A.toInt()); cornerRadius = dp(8).toFloat() }
                layoutParams = LinearLayout.LayoutParams(dp(100), dp(150)).apply { marginEnd = dp(14) }
            }
            t.poster?.let { loadImage(img, it) }
            addView(img)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = t.name
                    textSize = 19f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = listOf(if (t.type == "tv") "Serie" else "Film", t.year,
                        if (t.rating > 0) "★ " + String.format(NL, "%.1f", t.rating) else "").filter { it.isNotEmpty() }.joinToString("  ·  ")
                    textSize = 13f
                    setTextColor(0xFF4FC3F7.toInt())
                    setPadding(0, dp(2), 0, dp(6))
                })
                addView(TextView(this@MainActivity).apply {
                    text = t.overview.ifEmpty { "Geen beschrijving." }
                    textSize = 13f
                    setTextColor(0xFFCCCCCC.toInt())
                    maxLines = 7
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
        })
        val provHeader = TextView(this).apply {
            text = "Te zien op…"
            textSize = 13f
            setTextColor(0xFF4FC3F7.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(16), 0, dp(8))
        }
        val provList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(provHeader)
        content.addView(provList)
        content.addView(sheetRow("🔍   Zoeken op de tv (Google TV)", 0xFF18BCF2.toInt(), dark = true) {
            dialog.dismiss(); searchOnTv(t.name, null)
        })
        dialog.show()
        thread {
            val r = runCatching { tmdb.providers(t) }
            runOnUiThread {
                val ps = r.getOrNull()
                if (ps == null) { provHeader.text = "Kon niet ophalen waar het te zien is"; return@runOnUiThread }
                if (ps.isEmpty()) { provHeader.text = "Niet te zien bij een streamingdienst in Nederland"; return@runOnUiThread }
                // Eerst wat je via je eigen apps kunt kijken
                ps.sortedBy { if (appForProvider(it.name) != null) 0 else 1 }.forEach { p ->
                    val app = appForProvider(p.name)
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(12), dp(8))
                        background = GradientDrawable().apply {
                            setColor(if (app != null) 0xFF1F3A2A.toInt() else 0xFF2A2A2A.toInt()); cornerRadius = dp(10).toFloat()
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(6) }
                    }
                    val logo = ImageView(this).apply {
                        clipToOutline = true
                        background = GradientDrawable().apply { setColor(0xFF111111.toInt()); cornerRadius = dp(6).toFloat() }
                        layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) }
                    }
                    p.logo?.let { loadImage(logo, it) }
                    row.addView(logo)
                    row.addView(TextView(this).apply {
                        text = "${p.name}  ·  ${p.kind}"
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    if (app != null) {
                        row.addView(TextView(this).apply {
                            text = "▶ Open"
                            textSize = 14f
                            setTextColor(0xFF7CFC9A.toInt())
                            setTypeface(typeface, Typeface.BOLD)
                        })
                        row.isClickable = true
                        row.setOnClickListener { dialog.dismiss(); searchOnTv(t.name, app) }
                    }
                    provList.addView(row)
                }
            }
        }
    }

    /**
     * Titel op de tv zoeken: tv, receiver en box aan; dan via ADB het zoeken van Google TV met de titel.
     * Met [app]: eerst die app openen (zo zie je meteen de juiste dienst), daarna zoeken.
     */
    private fun searchOnTv(title: String, app: MediaApp?) {
        val q = title.replace(Regex("[\\\\'\"`$;&|<>()]"), " ").replace(Regex("\\s+"), " ").trim()
        mediaStatus.text = "\"$title\" zoeken op de tv…"
        thread { prepareWatching(SOURCE_APPS) }
        thread {
            quietService("remote/turn_on", JSONObject().put("entity_id", MEDIA_REMOTE))
            Thread.sleep(1_500)
            val cmd = if (app?.pkg == "com.netflix.ninja")
                // Netflix: rechtstreeks het zoekscherm van Netflix met de titel
                "am start -a android.intent.action.VIEW -d 'https://www.netflix.com/search?q=${Uri.encode(q)}' com.netflix.ninja"
            else
                "am start -a android.search.action.GLOBAL_SEARCH --es query '$q'"
            quietService("androidtv/adb_command", JSONObject().put("entity_id", MEDIA_ADB).put("command", cmd))
            runOnUiThread {
                mediaStatus.text = "\"$title\" staat klaar op de tv"
                handler.postDelayed({ refreshMedia() }, 2_500)
            }
        }
    }

    // ================= Kodi: bibliotheek bladeren, afspelen, ondertitels =================

    private fun kodiConfigured() = (prefs().getString(KEY_KODI_PASS, "") ?: "").isNotEmpty()

    private fun saveKodiSettings() {
        prefs().edit()
            .putString(KEY_KODI_HOST, kodiHostInput.text.toString().trim())
            .putString(KEY_KODI_PORT, kodiPortInput.text.toString().trim())
            .putString(KEY_KODI_USER, kodiUserInput.text.toString().trim())
            .putString(KEY_KODI_PASS, kodiPassInput.text.toString())
            .putString(KEY_OS_KEY, osKeyInput.text.toString().trim())
            .putString(KEY_OS_USER, osUserInput.text.toString().trim())
            .putString(KEY_OS_PASS, osPassInput.text.toString())
            .putString(KEY_OS_LANG, osLangInput.text.toString().trim())
            .apply()
        kodiStatus.text = "Verbinden met Kodi…"
        thread {
            val r = runCatching { kodi.sources() }
            runOnUiThread {
                kodiStatus.text = r.exceptionOrNull()?.let {
                    "Lukt niet: ${it.message ?: it.javaClass.simpleName}\n(Staat Kodi open op de box en is http-bediening aan?)"
                } ?: "Verbonden ✓  Bronnen: ${r.getOrNull()!!.joinToString { it.label }}"
            }
        }
    }

    private fun kodiNorm(l: String) = l.lowercase(NL).replace(Regex("[^a-z0-9]"), "")
    private fun kodiWanted(l: String) = kodiNorm(l) in setOf("series", "movies", "films")
    /** Echte mappen op schijf/netwerk; geen add-ons of bibliotheek-lijsten (plugin://, videodb:// …). */
    private fun kodiRealPath(f: String) =
        listOf("plugin://", "videodb://", "library://", "addons://", "special://", "sources://").none { f.startsWith(it, true) }

    /**
     * Alleen Series en Movies tonen (Series 2 niet). Staan ze niet als bron in Kodi, dan zoeken we
     * ze één en twee niveaus diep in de bronnen (bijv. Download/Series).
     */
    private fun kodiRoot(): List<KodiApi.Entry> {
        fun seg(f: String) = f.trimEnd('/').substringAfterLast('/')
        // Mooie naam uit de map zelf: "series (2)" in Kodi wordt gewoon "Series"
        fun nice(e: KodiApi.Entry) = KodiApi.Entry(
            seg(e.file).replaceFirstChar { it.uppercase() }, e.file, true, false, 0.0, 0.0,
        )
        fun wanted(e: KodiApi.Entry) = kodiWanted(seg(e.file)) || kodiWanted(e.label)
        val all = kodi.sources().filter { kodiRealPath(it.file) }
        val found = LinkedHashMap<String, KodiApi.Entry>()
        all.filter { wanted(it) }.forEach { found[it.file.trimEnd('/')] = nice(it) }
        // Ook naast de bronnen kijken: …/NextcloudFiles/movies naast …/NextcloudFiles/series
        all.map { it.file.trimEnd('/').substringBeforeLast('/') + "/" }.distinct()
            .filter { it.count { c -> c == '/' } > 3 }
            .forEach { parent ->
                runCatching { kodi.list(parent) }.getOrNull()
                    ?.filter { it.isDir && kodiRealPath(it.file) && wanted(it) }
                    ?.forEach { found.putIfAbsent(it.file.trimEnd('/'), nice(it)) }
            }
        if (found.isNotEmpty()) return found.values.sortedBy { if (kodiNorm(it.label) == "series") 0 else 1 }
        // Anders één en twee niveaus diep in de bronnen zoeken
        var level = all
        repeat(2) {
            if (found.isNotEmpty()) return@repeat
            val next = ArrayList<KodiApi.Entry>()
            level.forEach { src ->
                runCatching { kodi.list(src.file) }.getOrNull()?.filter { it.isDir && kodiRealPath(it.file) }?.forEach { d ->
                    if (wanted(d)) found.putIfAbsent(d.file.trimEnd('/'), nice(d)) else next.add(d)
                }
            }
            level = next.take(40)
        }
        return found.values.toList().ifEmpty { all }
    }

    /** Onderblad-dialoog in dezelfde stijl als Mijn muziek. */
    private fun sheetDialog(content: View): Dialog {
        val dialog = sheet()
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            })
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    private fun sheetRow(text: String, color: Int = 0xFF2A2A2A.toInt(), dark: Boolean = false, onClick: () -> Unit) =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(if (dark) Color.BLACK else Color.WHITE)
            if (dark) setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun fmtSec(sec: Double): String {
        val t = sec.toLong()
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    private fun showKodiSheet() {
        if (!kodiConfigured()) {
            AlertDialog.Builder(this)
                .setTitle("Kodi")
                .setMessage(
                    "Zet in Kodi aan: Instellingen → Diensten → Bediening → \"Bediening via HTTP toestaan\" " +
                        "(poort 8080, kies een gebruikersnaam en wachtwoord).\n\nVul daarna het wachtwoord in via ⚙ → API's → Kodi."
                )
                .setPositiveButton("Naar instellingen") { _, _ -> showSettings(true); showSettingsPage(PAGE_KODI) }
                .setNeutralButton("Alleen Kodi openen") { _, _ -> MEDIA_APPS.firstOrNull { it.pkg == KODI_PKG }?.let { launchApp(it) } }
                .setNegativeButton("Annuleren", null)
                .show()
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val dialog = sheetDialog(content)
        val title = TextView(this).apply {
            text = "Kodi"
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
        val scroll = ScrollView(this).apply {
            addView(list)
            isScrollbarFadingEnabled = false
            if (Build.VERSION.SDK_INT >= 29) {
                scrollBarSize = dp(8)
                verticalScrollbarThumbDrawable = GradientDrawable().apply {
                    setColor(0xFF8D8D8D.toInt()); cornerRadius = dp(4).toFloat()
                }
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
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
        content.addView(title)
        content.addView(status)
        content.addView(android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(460))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(scroll)
                addView(strip)
            }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(bubble, android.widget.FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        })

        // Pad als stapel (bestand, naam); leeg = de bronnen. Scrollpositie per map onthouden.
        val stack = ArrayList<Pair<String, String>>()
        var showAll = false
        val scrollPos = HashMap<String, Int>()
        var seq = 0
        lateinit var load: () -> Unit
        fun go(push: Pair<String, String>?) {
            scrollPos[stack.lastOrNull()?.first ?: ""] = scroll.scrollY
            if (push != null) stack.add(push) else if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            load()
        }
        load = {
            val my = ++seq
            val dir = stack.lastOrNull()
            title.text = dir?.second ?: "Kodi"
            status.text = "Laden…"
            list.removeAllViews()
            firstRow.clear()
            thread {
                if (dir == null && !kodi.ping()) {
                    runOnUiThread { if (my == seq) status.text = "GoogleTV-box en Kodi aanzetten…" }
                    kodiEnsureRunning()
                }
                val r = runCatching {
                    if (dir == null) (if (showAll) kodi.sources() else kodiRoot()) else kodi.list(dir.first)
                }
                val playing = if (dir == null) runCatching { kodi.state() }.getOrNull() else null
                runOnUiThread {
                    if (my != seq) return@runOnUiThread
                    val entries = r.getOrNull()
                    if (entries == null) {
                        status.text = "Kodi niet bereikbaar: ${r.exceptionOrNull()?.message}"
                        list.addView(sheetRow("▶   Kodi openen op de tv", 0xFF17B2E7.toInt(), dark = true) {
                            status.text = "Kodi starten…"
                            thread { kodiEnsureRunning(); runOnUiThread { load() } }
                        })
                        return@runOnUiThread
                    }
                    if (dir != null) list.addView(sheetRow("←   Terug") { go(null) })
                    if (dir == null) list.addView(sheetRow(if (showAll) "↺   Alleen Series en Movies" else "☰   Alle bronnen in Kodi tonen") {
                        showAll = !showAll; load()
                    })
                    playing?.let { st ->
                        list.addView(sheetRow("💬   Ondertitels & audio · ${st.title}", 0xFF17B2E7.toInt(), dark = true) {
                            showKodiSubsSheet()
                        })
                    }
                    val files = entries.count { !it.isDir }
                    status.text = if (dir == null) "Kies een map" else
                        listOfNotNull(
                            entries.count { it.isDir }.takeIf { it > 0 }?.let { "$it mappen" },
                            files.takeIf { it > 0 }?.let { "$it video's" },
                        ).joinToString(" · ").ifEmpty { "Lege map" }
                    val toCheck = ArrayList<Pair<KodiApi.Entry, (KodiApi.Entry) -> Unit>>()
                    entries.forEach { e ->
                        val label = if (e.isDir) e.label else e.label.substringBeforeLast('.').ifEmpty { e.label }
                        val pathHint = if (dir == null) "\n" + e.file.replace(Regex("//[^/@]*@"), "//") else ""
                        var cur = e
                        val labelView = TextView(this).apply {
                            textSize = 15f
                            setTextColor(Color.WHITE)
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        val badge = TextView(this).apply {
                            textSize = 12f
                            setTypeface(typeface, Typeface.BOLD)
                            setPadding(dp(8), dp(3), dp(8), dp(3))
                            visibility = View.GONE
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginStart = dp(8) }
                        }
                        val bg = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(10).toFloat() }
                        val v = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(12), dp(14), dp(12), dp(14))
                            background = bg
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp(6) }
                            isClickable = true
                            addView(labelView)
                            addView(badge)
                            setOnClickListener { if (cur.isDir) go(cur.file to cur.label) else kodiPlay(cur) { dialog.dismiss() } }
                        }
                        fun show(x: KodiApi.Entry) {
                            cur = x
                            labelView.text = "${if (x.isDir) "📁" else "🎬"}   $label$pathHint"
                            labelView.setTextColor(if (x.played) 0xFF9E9E9E.toInt() else Color.WHITE)
                            bg.setColor(if (x.played) 0xFF232323.toInt() else 0xFF2A2A2A.toInt())
                            when {
                                x.isDir -> badge.visibility = View.GONE
                                x.played -> {
                                    badge.text = "✓ Bekeken"
                                    badge.setTextColor(Color.BLACK)
                                    badge.background = GradientDrawable().apply { setColor(0xFF4CAF50.toInt()); cornerRadius = dp(10).toFloat() }
                                    badge.visibility = View.VISIBLE
                                }
                                x.resumeSec > 0 -> {
                                    val pct = if (x.totalSec > 0) " · ${(x.resumeSec * 100 / x.totalSec).toInt()}%" else ""
                                    badge.text = "◐ ${fmtSec(x.resumeSec)}$pct"
                                    badge.setTextColor(Color.BLACK)
                                    badge.background = GradientDrawable().apply { setColor(0xFFFFB300.toInt()); cornerRadius = dp(10).toFloat() }
                                    badge.visibility = View.VISIBLE
                                }
                                else -> badge.visibility = View.GONE
                            }
                        }
                        show(e)
                        if (!e.isDir && !e.hasInfo) toCheck.add(e to { x: KodiApi.Entry -> show(x) })
                        list.addView(v)
                        val c = label.firstOrNull()?.uppercaseChar()
                        firstRow.putIfAbsent(if (c != null && c in 'A'..'Z') c.toString() else "#", v)
                    }
                    val y = scrollPos[dir?.first ?: ""] ?: 0
                    scroll.post { scroll.scrollTo(0, y) }
                    // Kijkstatus los ophalen als Kodi die niet in de maplijst meegaf
                    if (toCheck.isNotEmpty()) thread {
                        for ((entry, update) in toCheck.take(150)) {
                            if (my != seq || !dialog.isShowing) break
                            val d = runCatching { kodi.details(entry) }.getOrNull() ?: continue
                            if (d.played || d.resumeSec > 0) runOnUiThread { if (my == seq) update(d) }
                        }
                    }
                }
            }
        }
        // Terug-knop van de telefoon: eerst een map omhoog
        dialog.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP && stack.isNotEmpty()) {
                go(null); true
            } else keyCode == KeyEvent.KEYCODE_BACK && stack.isNotEmpty()
        }
        dialog.show()
        // Meteen tv en receiver klaarzetten, zodat je kunt kiezen en kijken
        thread { prepareWatching(SOURCE_APPS) }
        load()
    }

    /** Kodi draait en luistert? Zo niet: box aan en Kodi starten, dan wachten tot hij antwoordt. */
    private fun kodiEnsureRunning(): Boolean {
        if (kodi.ping()) return true
        // Box altijd aanzetten (doet niets als hij al aan staat), ook als HA denkt dat hij aan is
        val wasOn = currentMediaState() == "on"
        quietService("remote/turn_on", JSONObject().put("entity_id", MEDIA_REMOTE))
        Thread.sleep(if (wasOn) 800 else 3_000)
        quietService(
            "androidtv/adb_command", JSONObject().put("entity_id", MEDIA_ADB).put(
                "command", "monkey -p $KODI_PKG -c android.intent.category.LEANBACK_LAUNCHER -c android.intent.category.LAUNCHER 1"
            )
        )
        repeat(20) {
            Thread.sleep(1_000)
            if (kodi.ping()) { boxApp = KODI_PKG; return true }
        }
        return false
    }

    private fun kodiPlay(e: KodiApi.Entry, onStarted: () -> Unit) {
        fun start(resume: Boolean) {
            onStarted()
            val name = e.label.substringBeforeLast('.').ifEmpty { e.label }
            mediaStatus.text = "$name starten in Kodi…"
            thread { prepareWatching(SOURCE_APPS) }   // tv + receiver klaarzetten
            thread {
                val r = runCatching {
                    if (!kodiEnsureRunning()) throw IOException("Kodi reageert niet (staat http-bediening aan?)")
                    kodi.play(e.file, resume)
                    boxApp = KODI_PKG
                }
                runOnUiThread {
                    mediaStatus.text = r.exceptionOrNull()?.let { "Afspelen mislukt: ${it.message}" } ?: "▶ $name"
                    handler.postDelayed({ refreshMedia() }, 3_000)
                }
                // Zodra de video echt loopt (en de sporen bekend zijn): meteen ondertitels & audio tonen
                if (r.isSuccess) {
                    for (i in 0 until 15) {
                        Thread.sleep(1_000)
                        val st = runCatching { kodi.state() }.getOrNull() ?: continue
                        if (st.total.isNotEmpty() && st.total != "0:00") {
                            runOnUiThread { if (!isFinishing) showKodiSubsSheet() }
                            break
                        }
                    }
                }
            }
        }
        if (e.resumeSec > 0 && !e.played) {
            AlertDialog.Builder(this)
                .setTitle(e.label.substringBeforeLast('.'))
                .setItems(arrayOf("Verder kijken vanaf ${fmtSec(e.resumeSec)}", "Vanaf het begin")) { _, i -> start(i == 0) }
                .setNegativeButton("Annuleren", null)
                .show()
        } else start(false)
    }

    private fun kodiSearchOnTv() {
        thread {
            val res = runCatching { kodi.searchSubtitles() }
            runOnUiThread {
                if (res.isFailure) { mediaStatus.text = "Mislukt: ${res.exceptionOrNull()?.message}"; return@runOnUiThread }
                Toast.makeText(this, "Kies de ondertitel op de tv", Toast.LENGTH_LONG).show()
                showRemoteSheet(MEDIA_REMOTE, false)
            }
        }
    }

    /** Ondertitels zoeken op OpenSubtitles.com en direct in Kodi laden. */
    private fun showSubSearchSheet(st: KodiApi.PlayerState) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val dialog = sheetDialog(content)
        val q = OpenSubs.parse(st.file.ifEmpty { st.title })
        content.addView(TextView(this).apply {
            text = "Ondertitels zoeken"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        val input = EditText(this).apply {
            setText(q.text)
            setTextColor(Color.WHITE)
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val se = EditText(this).apply {
            setText(listOfNotNull(q.season?.let { "S%02d".format(it) }, q.episode?.let { "E%02d".format(it) }).joinToString(""))
            hint = "S01E01"
            setHintTextColor(0xFF666666.toInt())
            setTextColor(Color.WHITE)
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
        }
        val go = Button(this).apply { text = "Zoek" }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input); addView(se); addView(go)
        })
        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }
        content.addView(status)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420))
        })
        var busy = false
        fun applySub(r: OpenSubs.Result) {
            if (busy) return
            busy = true
            status.text = "Downloaden en in Kodi laden…"
            thread {
                val res = runCatching {
                    val (bytes, remaining) = openSubs.download(r)
                    val lang = r.lang.lowercase().ifEmpty { "nl" }
                    // Video op de netwerkmap? Dan de ondertitel ernaast zetten (blijft ook voor later bewaard)
                    val file = st.file.replace(Regex("^smb://[^/@]*@"), "smb://")
                    val m = Regex("^smb://([^/]+)/([^/]+)/(.+)$").find(file)
                    smbConfigure()
                    val (host, share, _) = SmbMusic.parseUnc(smbPath()) ?: Triple("", "", "")
                    if (m == null || !m.groupValues[1].equals(host, true) || !m.groupValues[2].equals(share, true)) {
                        throw IOException("De video staat niet op de netwerkmap \\\\$host\\$share, dus de ondertitel kan er niet naast")
                    }
                    val rel = java.net.URLDecoder.decode(m.groupValues[3].replace("+", "%2B"), "UTF-8")
                    val subRel = rel.substringBeforeLast('.') + ".$lang.srt"
                    smb.write(subRel.replace('/', '\\'), bytes)
                    val kodiPath = st.file.substringBeforeLast('.') + ".$lang.srt"
                    val how = try {
                        kodi.addSubtitle(st.playerId, kodiPath)
                        "geladen"
                    } catch (e: IOException) {
                        // Oudere Kodi zonder AddSubtitle: opnieuw starten op dezelfde plek, dan ziet hij het bestand
                        kodi.play(st.file, true)
                        "geladen (video kort opnieuw gestart)"
                    }
                    how to remaining
                }
                runOnUiThread {
                    busy = false
                    val e = res.exceptionOrNull()
                    if (e != null) { status.text = "Mislukt: ${e.message}"; return@runOnUiThread }
                    val (how, remaining) = res.getOrNull()!!
                    dialog.dismiss()
                    Toast.makeText(
                        this, "Ondertitel $how ✓" + (if (remaining >= 0) " · nog $remaining downloads vandaag" else ""),
                        Toast.LENGTH_LONG,
                    ).show()
                    handler.postDelayed({ showKodiSubsSheet() }, 1_500)
                }
            }
        }
        var seq = 0
        fun search() {
            val my = ++seq
            val m = Regex("(?i)S?(\\d{1,2})\\s*[Ex](\\d{1,3})").find(se.text.toString())
            val query = OpenSubs.Query(
                input.text.toString().trim(),
                m?.groupValues?.get(1)?.toInt(), m?.groupValues?.get(2)?.toInt(),
                if (m == null) q.year else null,
            )
            status.text = "Zoeken…"
            list.removeAllViews()
            thread {
                val res = runCatching { openSubs.search(query, osLangs()) }
                runOnUiThread {
                    if (my != seq) return@runOnUiThread
                    val items = res.getOrNull()
                    if (items == null) { status.text = "Mislukt: ${res.exceptionOrNull()?.message}"; return@runOnUiThread }
                    // Gesorteerd op taalvoorkeur, daarna populariteit
                    val order = osLangs().lowercase().split(',').map { it.trim() }
                    val sorted = items.sortedWith(compareBy<OpenSubs.Result>({ order.indexOf(it.lang.lowercase()).let { i -> if (i < 0) 99 else i } }, { -it.downloads }))
                    status.text = if (sorted.isEmpty()) "Niets gevonden. Pas de zoekterm aan." else "${sorted.size} gevonden · tik om te laden"
                    sorted.forEach { r ->
                        list.addView(sheetRow(
                            "${OpenSubs.langName(r.lang)}  ·  ⬇ ${r.downloads}" + (if (r.impaired) "  ·  doven/slechthorenden" else "") + "\n" + r.release
                        ) { applySub(r) })
                    }
                }
            }
        }
        go.setOnClickListener { search() }
        input.setOnEditorActionListener { _, _, _ -> search(); true }
        content.addView(sheetRow("📺   Toch op de tv zoeken") { dialog.dismiss(); kodiSearchOnTv() })
        dialog.show()
        search()
    }

    /** Ondertitels en audiospoor van wat Kodi nu afspeelt; ook online ondertitels zoeken. */
    private fun showKodiSubsSheet() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val dialog = sheetDialog(content)
        val title = TextView(this).apply {
            text = "Ondertitels & audio"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, dp(4), 0, dp(8))
            text = "Laden…"
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title)
        content.addView(status)
        content.addView(ScrollView(this).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        fun header(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(0xFF17B2E7.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(10), 0, dp(6))
        }
        fun pill(t: String, onClick: () -> Unit) = TextView(this).apply {
            text = t
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(10).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            isClickable = true
            setOnClickListener { onClick() }
        }
        lateinit var load: () -> Unit
        fun act(f: () -> Unit) {
            thread {
                val r = runCatching(f)
                runOnUiThread {
                    r.exceptionOrNull()?.let { status.text = "Mislukt: ${it.message}" }
                    handler.postDelayed({ if (dialog.isShowing) load() }, 600)
                }
            }
        }
        load = {
            thread {
                val r = runCatching { kodi.state() }
                runOnUiThread {
                    list.removeAllViews()
                    val st = r.getOrNull()
                    if (st == null) {
                        status.text = r.exceptionOrNull()?.let { "Kodi niet bereikbaar: ${it.message}" }
                            ?: "Kodi speelt nu geen video af."
                        return@runOnUiThread
                    }
                    title.text = st.title
                    status.text = if (st.total.isNotEmpty()) "${st.time} / ${st.total}" else ""
                    list.addView(header("ONDERTITELS"))
                    list.addView(sheetRow((if (!st.subOn) "✓   " else "      ") + "Uit") {
                        act { kodi.setSubtitle(st.playerId, null) }
                    })
                    st.subtitles.forEach { s ->
                        val on = st.subOn && s.index == st.currentSub
                        list.addView(sheetRow((if (on) "✓   " else "      ") + s.label, if (on) 0xFF1F4E5F.toInt() else 0xFF2A2A2A.toInt()) {
                            act { kodi.setSubtitle(st.playerId, s.index) }
                        })
                    }
                    list.addView(sheetRow("🔍   Online ondertitels zoeken…", 0xFF17B2E7.toInt(), dark = true) {
                        if (prefs().getString(KEY_OS_KEY, "").isNullOrBlank() || prefs().getString(KEY_OS_USER, "").isNullOrBlank()) {
                            // Zoeken in de app staat voorlopig uit: gewoon op de tv zoeken
                            dialog.dismiss(); kodiSearchOnTv()
                        } else if (false) {
                            AlertDialog.Builder(this)
                                .setTitle("Ondertitels zoeken")
                                .setMessage("Om in de app te zoeken heb je een (gratis) OpenSubtitles.com-account en API-sleutel nodig. Vul die in via ⚙ → API's → Kodi.\n\nOf zoek nu op de tv met de afstandsbediening.")
                                .setPositiveButton("Naar instellingen") { _, _ -> dialog.dismiss(); showSettings(true); showSettingsPage(PAGE_KODI) }
                                .setNeutralButton("Op de tv zoeken") { _, _ -> dialog.dismiss(); kodiSearchOnTv() }
                                .setNegativeButton("Annuleren", null)
                                .show()
                        } else {
                            dialog.dismiss(); showSubSearchSheet(st)
                        }
                    })
                    if (st.subOn) {
                        list.addView(header("ONDERTITELS LOPEN NIET GELIJK?"))
                        list.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp(6) }
                            addView(pill("− eerder") { act { kodi.action("subtitledelayminus") } })
                            addView(pill("+ later") { act { kodi.action("subtitledelayplus") } })
                        })
                    }
                    if (st.audio.size > 1) {
                        list.addView(header("AUDIO"))
                        st.audio.forEach { a ->
                            val on = a.index == st.currentAudio
                            list.addView(sheetRow((if (on) "✓   " else "      ") + a.label, if (on) 0xFF1F4E5F.toInt() else 0xFF2A2A2A.toInt()) {
                                act { kodi.setAudio(st.playerId, a.index) }
                            })
                        }
                    }
                }
            }
        }
        dialog.show()
        load()
    }

    // ================= Vaste speler per bron + mini-speler in de bladerschermen =================

    /** Waar muziek speelt: "phone" (deze telefoon), "ma" (Music Assistant-speler) of "sp" (Spotify Connect). */
    private class Target(val kind: String, val id: String, val name: String) {
        fun save() = "$kind|$id|$name"
        companion object {
            fun load(s: String?): Target? {
                val p = s?.split('|') ?: return null
                return if (p.size >= 3) Target(p[0], p[1], p.drop(2).joinToString("|")) else null
            }
        }
    }

    private fun savedTarget(key: String) = Target.load(prefs().getString(key, null))
    private fun saveTarget(key: String, t: Target) = prefs().edit().putString(key, t.save()).apply()

    /**
     * Speler kiezen (lijst met ✓ bij de huidige). [spotify]: ook Spotify Connect-apparaten;
     * [phone]: "Deze telefoon" aanbieden.
     */
    private fun pickTarget(key: String, withSp: Boolean, phone: Boolean, onChosen: (Target) -> Unit) {
        mediaStatus.text = "Spelers zoeken…"
        thread {
            val ma = runCatching {
                val tpl = "{% for e in integration_entities('music_assistant') if e.startswith('media_player.') %}" +
                    "{{ e }}|{{ state_attr(e, 'friendly_name') }}|{{ states(e) }}\n{% endfor %}"
                haRequest("POST", "/api/template", JSONObject().put("template", tpl).toString())
                    .lines().filter { it.contains('|') }.map { it.split('|') }
                    .filter { it.getOrNull(2) != "unavailable" }
                    .map { Triple(it[0], it[1], it.getOrNull(2) ?: "") }
            }.getOrDefault(emptyList())
            val sp = if (withSp) runCatching { spotify.devices() }.getOrDefault(emptyList()).filterNot { isThisPhone(it) } else emptyList()
            // Music Assistant-spelers die Spotify zelf ook al kent niet dubbel tonen
            val maShown = if (withSp) ma.filter { (_, n, _) ->
                sp.none { d -> d.name.equals(n, true) || d.name.contains(n, true) || n.contains(d.name, true) }
            } else ma
            val options = ArrayList<Target>()
            val labels = ArrayList<String>()
            val cur = savedTarget(key)
            fun add(t: Target, label: String) {
                options.add(t)
                labels.add((if (cur != null && cur.kind == t.kind && cur.id == t.id) "✓  " else "     ") + label)
            }
            if (phone) add(Target("phone", "", "Deze telefoon"), "📱  Deze telefoon")
            sp.forEach { add(Target("sp", it.id, it.name), it.name + if (it.active) "  (speelt)" else "") }
            maShown.forEach { (e, n, st) ->
                add(Target("ma", e, n), n + (if (withSp) "  (via Music Assistant)" else "") + if (st == "playing") "  (speelt)" else "")
            }
            runOnUiThread {
                mediaStatus.text = ""
                if (options.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("Afspelen op").setMessage("Geen spelers gevonden.")
                        .setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("Afspelen op")
                    .setItems(labels.toTypedArray()) { _, i ->
                        saveTarget(key, options[i])
                        onChosen(options[i])
                    }
                    .setNegativeButton("Annuleren", null)
                    .show()
            }
        }
    }

    /** Knop "Afspelen op: … ▾" bovenin een bladerscherm. */
    private fun targetButton(key: String, withSp: Boolean, phone: Boolean, accent: Int): TextView {
        val b = TextView(this)
        fun refresh() {
            val t = savedTarget(key)
            b.text = "Afspelen op:  " + (t?.name ?: "kies een speler") + "  ▾"
        }
        b.apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(44)
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                setColor(0xFF262626.toInt()); cornerRadius = dp(22).toFloat(); setStroke(dp(1), accent)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); bottomMargin = dp(4) }
            isClickable = true
            setOnClickListener { pickTarget(key, withSp, phone) { refresh() } }
        }
        refresh()
        return b
    }

    /** Gekozen speler gebruiken, of eerst laten kiezen als er nog geen is. */
    private fun withTarget(key: String, withSp: Boolean, phone: Boolean, button: TextView?, go: (Target) -> Unit) {
        val t = savedTarget(key)
        if (t != null && (phone || t.kind != "phone")) go(t)
        else pickTarget(key, withSp, phone) { chosen ->
            button?.text = "Afspelen op:  ${chosen.name}  ▾"
            go(chosen)
        }
    }

    /**
     * Mini-speler onderin een bladerscherm: wat er op de gekozen speler speelt, met ⏯ en ⏭.
     * Tik erop voor de volledige bediening.
     */
    private fun miniPlayer(dialog: Dialog, key: String, accent: Int): View {
        val title = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sub = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFA8A8A8.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { setColor(0xFF3A3A3A.toInt()); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) }
        }
        val prog = View(this).apply { background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(2).toFloat() } }
        val progTrack = android.widget.FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0xFF444444.toInt()); cornerRadius = dp(2).toFloat() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
                topMargin = dp(6); marginStart = dp(4); marginEnd = dp(4)
            }
            addView(prog, android.widget.FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        var entity: String? = null     // HA-entiteit, of "local" voor deze telefoon
        var playing = false
        var pos = 0.0
        var dur = 0.0
        var lastArt: String? = null
        var lastFetch = 0L
        val playBtn = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.WHITE); shape = GradientDrawable.OVAL }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            contentDescription = "Afspelen of pauzeren"
            isClickable = true
        }
        val nextBtn = TextView(this).apply {
            text = "⏭︎"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            contentDescription = "Volgende"
            isClickable = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            // Licht getint met de kleur van de bron, zodat hij zich onderscheidt van de lijst
            fun mix(c: Int, base: Int, f: Double) = (base + (c - base) * f).toInt().coerceIn(0, 255)
            val tint = Color.rgb(
                mix(Color.red(accent), 0x1E, 0.13), mix(Color.green(accent), 0x1E, 0.13), mix(Color.blue(accent), 0x1E, 0.13),
            )
            background = GradientDrawable().apply {
                setColor(tint); cornerRadius = dp(14).toFloat()
                setStroke(dp(1), (accent and 0x00FFFFFF) or 0x55000000)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            visibility = View.GONE
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(art)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(title)
                    addView(sub)
                })
                addView(playBtn)
                addView(nextBtn)
            })
            addView(progTrack)
        }
        fun render() {
            playBtn.text = if (playing) "❚❚" else "▶︎"
            val w = progTrack.width
            (prog.layoutParams as android.widget.FrameLayout.LayoutParams).width =
                if (dur > 0 && w > 0) (w * (pos / dur).coerceIn(0.0, 1.0)).toInt() else 0
            prog.requestLayout()
        }
        fun svc(service: String) {
            val e = entity ?: return
            if (e == "local") {
                if (service == "media_play_pause") toggleLocal()
                else if (localIndex + 1 < localQueue.size) playLocalAt(localIndex + 1)
            } else thread { quietService("media_player/$service", JSONObject().put("entity_id", e)) }
            lastFetch = System.currentTimeMillis() - 2_300   // zo meteen opnieuw ophalen
        }
        playBtn.setOnClickListener { playing = !playing; render(); svc("media_play_pause") }
        nextBtn.setOnClickListener { svc("media_next_track") }
        box.setOnClickListener {
            val e = entity ?: return@setOnClickListener
            if (e == "local") showLocalSheet() else showMediaSheet(e, savedTarget(key)?.name ?: "Speler")
        }
        fun fetch() {
            val t = savedTarget(key)
            thread {
                // Deze telefoon met muziek van de netwerkmap: de eigen speler
                val lp = localPlayer
                if ((t == null || t.kind == "phone") && lp != null) {
                    val e = localQueue.getOrNull(localIndex)
                    val pl = runCatching { lp.isPlaying }.getOrDefault(false)
                    val p = runCatching { lp.currentPosition / 1000.0 }.getOrDefault(0.0)
                    val d = runCatching { lp.duration / 1000.0 }.getOrDefault(0.0)
                    runOnUiThread {
                        entity = "local"
                        box.visibility = if (e != null) View.VISIBLE else View.GONE
                        val tg = e?.let { tagCache[it.path] }
                        if (e != null && tg == null) readTags(e) {}
                        title.text = tg?.title ?: e?.name?.substringBeforeLast('.') ?: ""
                        sub.text = joinNonEmpty(tg?.artist, "Deze telefoon · ${localIndex + 1}/${localQueue.size}")
                        if (tg?.art != null) art.setImageBitmap(tg.art) else art.setImageDrawable(null)
                        playing = pl; pos = p; dur = d
                        render()
                    }
                    return@thread
                }
                val ent = when (t?.kind) {
                    "ma" -> t.id
                    "sp", "phone" -> SPOTIFY
                    else -> null
                } ?: run { runOnUiThread { box.visibility = View.GONE }; return@thread }
                val st = stateOf(ent)
                val a = st?.optJSONObject("attributes")
                val s = st?.optString("state")
                val active = s == "playing" || s == "paused"
                var p = a?.optDouble("media_position", 0.0) ?: 0.0
                if (s == "playing") runCatching {
                    p += (System.currentTimeMillis() - OffsetDateTime.parse(a!!.optString("media_position_updated_at"))
                        .toInstant().toEpochMilli()) / 1000.0
                }
                val pic = attr(st, "entity_picture")?.let { if (it.startsWith("http")) it else haBase() + it }
                runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    entity = ent
                    box.visibility = if (active) View.VISIBLE else View.GONE
                    title.text = attr(st, "media_title") ?: ""
                    sub.text = joinNonEmpty(attr(st, "media_artist"), t?.name)
                    playing = s == "playing"
                    pos = if (p.isNaN()) 0.0 else p
                    dur = a?.optDouble("media_duration", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
                    val tKey = "cover:" + (attr(st, "media_title") ?: "")
                    if (pic != null && pic != lastArt && lastArt != tKey) {
                        lastArt = pic
                        val ar0 = attr(st, "media_artist"); val al0 = attr(st, "media_album_name"); val ti0 = attr(st, "media_title")
                        loadImage(art, pic) {
                            lastArt = tKey
                            if (ti0 != null) thread {
                                val bmp = coverForPlaying(ent, ar0, al0, ti0)
                                runOnUiThread { if (bmp != null && lastArt == tKey) { art.tag = null; art.setImageBitmap(bmp) } }
                            }
                        }
                    }
                    if (pic == null && lastArt != tKey) {
                        lastArt = tKey
                        art.setImageDrawable(null)
                        val ar = attr(st, "media_artist"); val al = attr(st, "media_album_name"); val ti = attr(st, "media_title")
                        if (ti != null) thread {
                            val bmp = coverForPlaying(ent, ar, al, ti)
                            runOnUiThread { if (bmp != null && lastArt == tKey) art.setImageBitmap(bmp) }
                        }
                    }
                    render()
                }
            }
        }
        val ticker = object : Runnable {
            override fun run() {
                if (playing && entity != "local") pos += 1.0
                render()
                val now = System.currentTimeMillis()
                if (now - lastFetch > 2_500) { lastFetch = now; fetch() }
                handler.postDelayed(this, 1_000)
            }
        }
        handler.post(ticker)
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { handler.removeCallbacks(ticker) }
        })
        return box
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
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })
        addView(TextView(this@MainActivity).apply {
            text = "Mijn muziek"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        setupTile(0xFF6D4C41.toInt(), second) { showMusicSheet() }
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
        val dialog = sheet()
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
        val tBtn = targetButton(KEY_T_MUSIC, false, true, 0xFFFFB300.toInt())
        content.addView(tBtn)
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(scroll)
                addView(strip)
            }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(bubble, android.widget.FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        }
        content.addView(listArea)
        content.addView(miniPlayer(dialog, KEY_T_MUSIC, 0xFFFFB300.toInt()))
        // Het nummer dat je net startte oplichten; je blijft gewoon in de map
        val trackRows = HashMap<String, TextView>()
        fun styleRow(v: TextView, on: Boolean) {
            v.setTextColor(if (on) 0xFFFFB300.toInt() else Color.WHITE)
            v.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            v.background = GradientDrawable().apply {
                setColor(if (on) 0xFF2E2610.toInt() else 0xFF2A2A2A.toInt()); cornerRadius = dp(10).toFloat()
                if (on) setStroke(dp(1), 0xFFFFB300.toInt())
            }
        }
        fun markPlaying(path: String) {
            musicPlayingPath = path
            trackRows.forEach { (p, v) -> styleRow(v, p == path) }
        }

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
            trackRows.clear()
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
                    if (path == base) {
                        list.addView(row("🔍", "Zoeken op artiest of album") { dialog.dismiss(); showMusicSearch(maSource) })
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
                            playMusicFolder(tracks, 0, tBtn); markPlaying(tracks[0].path)
                        })
                    }
                    entries.forEach { e ->
                        val v = if (e.isDir) row("📁", e.name) { open(e.path) }
                        else row("🎵", e.name.substringBeforeLast('.')) {
                            playMusicFolder(tracks, tracks.indexOf(e), tBtn); markPlaying(e.path)
                        }
                        if (!e.isDir) {
                            trackRows[e.path] = v
                            if (e.path == musicPlayingPath) styleRow(v, true)
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

    @Volatile private var musicPlayingPath: String? = null
    /** Laatste map die naar een Music Assistant-speler ging: (speler, naam, nummers), voor de wachtrij. */
    @Volatile private var maFolder: Triple<String, String, List<SmbMusic.Entry>>? = null
    /** Laatste album/playlist (Spotify) dat naar een Music Assistant-speler ging: (speler, naam, item). */
    private var maStarted: Triple<String, String, SpotifyApi.Item>?
        get() = runCatching {
            val o = JSONObject(prefs().getString("ma_started", null) ?: return null)
            Triple(o.getString("e"), o.getString("n"),
                SpotifyApi.Item(o.getString("name"), "", o.getString("uri"), o.optString("type")))
        }.getOrNull()
        // Bewaard, zodat de wachtrij ook na een app-update of herstart nog klopt
        set(v) {
            prefs().edit().putString("ma_started", v?.let {
                JSONObject().put("e", it.first).put("n", it.second).put("name", it.third.name)
                    .put("uri", it.third.uri).put("type", it.third.type).toString()
            }).apply()
        }

    /** Nummers uit de map afspelen op de vaste speler van Mijn muziek (eerst kiezen als die er nog niet is). */
    private fun playMusicFolder(tracks: List<SmbMusic.Entry>, startAt: Int, btn: TextView?) {
        withTarget(KEY_T_MUSIC, false, true, btn) { t ->
            when (t.kind) {
                "phone" -> playLocal(tracks, startAt)
                else -> playFolderOnMa(tracks.drop(startAt), t.id, t.name)
            }
        }
    }

    /** Uit een zoekscherm afspelen op de vaste speler van die bron. */
    private fun playFromSearch(src: MusicSource, item: SpotifyApi.Item, btn: TextView?) {
        if (src === spotifySource) withTarget(KEY_T_SPOTIFY, true, true, btn) { t ->
            when (t.kind) {
                "phone" -> playSpotifyHere(item)
                "sp" -> playSpotify(item, SpotifyApi.Device(t.id, t.name, "", false))
                else -> playOnMa(item, t.id, t.name)
            }
        } else withTarget(KEY_T_MA, false, false, btn) { t -> playOnMa(item, t.id, t.name) }
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
        silencePhone()
        maFolder = Triple(entity, name, tracks)
        maStarted = null
        zoneUp = "picore" in name.lowercase(NL)
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
    /** Music Assistant-speler die nu op deze box speelt (of gepauzeerd staat), anders null. */
    private fun maPlayerOnBox(remote: String): String? {
        if (remote != MEDIA_REMOTE) return null
        val tpl = "{% for e in integration_entities('music_assistant') if e.startswith('media_player.') " +
            "and states(e) in ['playing','paused'] %}{{ e }}|{{ state_attr(e, 'friendly_name') }}\n{% endfor %}"
        val active = runCatching {
            haRequest("POST", "/api/template", JSONObject().put("template", tpl).toString())
                .lines().filter { it.contains('|') }.map { it.substringBefore('|') to it.substringAfter('|').lowercase(NL) }
        }.getOrDefault(emptyList())
        // Staat er een gewone app open op de box (YouTube, Netflix, Kodi…), dan speelt Music Assistant daar niet
        val app = boxApp
        if (app != null && MEDIA_APPS.any { it.pkg == app }) return null
        // Alleen de speler van de box zelf, niet bijv. "Audio Woonkamer" (de Chromecast Audio)
        return active.firstOrNull { (_, n) -> ("google" in n || Regex("\\btv\\b").containsMatchIn(n)) && "audio" !in n && "chromecast" !in n }?.first
    }

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
        silencePhone()
        maStarted = Triple(entity, name, item)
        maFolder = null
        zoneUp = "picore" in name.lowercase(NL)
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
                r.exceptionOrNull()?.let { showPlayError(name, it.message, viaMa = true) }
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

    private fun spotifyTile(second: Boolean): View = ImageView(this).apply {
        setImageResource(R.drawable.logo_spotify)
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "Spotify"
        clipToOutline = true
        setupTile(0xFF191414.toInt(), second) { showSpotifySheet() }
    }

    // Hoesjes laden zonder extra bibliotheek: eenvoudige cache in het geheugen
    private val imageCache = object : android.util.LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }
    private val imagePool = java.util.concurrent.Executors.newFixedThreadPool(4)

    private fun loadImage(view: ImageView, url: String, onFail: (() -> Unit)? = null) {
        view.tag = url
        imageCache.get(url)?.let { view.setImageBitmap(it); return }
        imagePool.execute {
            val bmp = runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                }
            }.getOrNull() ?: run { onFail?.let { runOnUiThread { if (view.tag == url) it() } }; return@execute }
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
        val blocked = spotify.blockedFor()
        if (blocked > 0) {
            // Spotify remt de app af: zoeken via Music Assistant (die heeft een eigen tegoed bij Spotify)
            Toast.makeText(this, "Spotify remt de app nog ${if (blocked > 3600) "${blocked / 3600} uur" else "${blocked / 60 + 1} min"} af; zoeken via Music Assistant", Toast.LENGTH_LONG).show()
            showMusicSearch(maSource)
            return
        }
        showMusicSearch(spotifySource)
    }

    /** Zoekscherm voor een muziekbron: eerst recent, dan zoeken tijdens het typen, artiestpagina's. */
    private fun showMusicSearch(src: MusicSource) {
        val dialog = sheet()
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
        val isSp = src === spotifySource
        val tKey = if (isSp) KEY_T_SPOTIFY else KEY_T_MA
        val tAccent = if (isSp) 0xFF1DB954.toInt() else 0xFFFFB300.toInt()
        val tBtn = targetButton(tKey, isSp, isSp, tAccent)
        content.addView(tBtn)
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
            // Neemt de ruimte die overblijft: met toetsenbord kleiner, zodat de mini-speler zichtbaar blijft
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        content.addView(scroll)
        content.addView(miniPlayer(dialog, tKey, tAccent))
        // Wat je net startte licht op; het scherm blijft open
        val pickedRows = HashMap<String, View>()
        var pickedUri: String? = null
        fun stylePicked(v: View, on: Boolean) {
            v.background = GradientDrawable().apply {
                setColor(if (on) 0xFF13301F.toInt() else 0xFF2A2A2A.toInt()); cornerRadius = dp(12).toFloat()
                if (on) setStroke(dp(1), tAccent)
            }
        }
        fun markPicked(uri: String) {
            pickedUri = uri
            pickedRows.forEach { (u, v) -> stylePicked(v, u == uri) }
        }

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
                val v = row(
                    item.name, if (item.type == "artist") "Artiest" else item.sub,
                    image = item.image, round = item.type == "artist", art = true,
                ) {
                    if (item.type == "artist") showArtist(item)
                    else { playFromSearch(src, item, tBtn); markPicked(item.uri) }
                }
                if (item.type != "artist") {
                    pickedRows[item.uri] = v
                    if (item.uri == pickedUri) stylePicked(v, true)
                }
                results.addView(v)
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
                        playFromSearch(src, artist, tBtn)
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

        // Blad over (bijna) het hele scherm; met het toetsenbord open krimpt alleen de lijst,
        // zodat de mini-speler gewoon boven het toetsenbord blijft staan
        content.background = GradientDrawable().apply {
            setColor(0xFF1E1E1E.toInt())
            cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
        }
        val topGap = (resources.displayMetrics.heightPixels * 0.12).toInt()
        dialog.setContentView(android.widget.FrameLayout(this).apply {
            setOnClickListener { dialog.dismiss() }   // tik boven het blad = sluiten
            addView(content, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { topMargin = topGap })
            content.isClickable = true
        })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
                val err = r.exceptionOrNull()
                // Afgeremd door Spotify? Dan alleen de spelers van Music Assistant aanbieden
                val devices = r.getOrNull() ?: if ((err as? SpotifyApi.ApiException)?.code == 429 && maExtra.isNotEmpty()) {
                    Toast.makeText(this, "Spotify remt de app af; kies een speler via Music Assistant", Toast.LENGTH_LONG).show()
                    emptyList()
                } else null
                if (devices == null) {
                    mediaStatus.text = "Spotify: ${err?.message}"
                    showPlayError("Spotify", err?.message, viaMa = false)
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

    /** Foutmelding bij afspelen duidelijk in beeld (de statusregel valt vaak buiten beeld). */
    private fun showPlayError(device: String, msg: String?, viaMa: Boolean) {
        val hint = when {
            viaMa -> "\n\nAfspelen via Music Assistant lukt alleen als in Music Assistant de Spotify-provider is toegevoegd (Instellingen → Providers → Spotify) en de speler aan staat."
            msg?.contains("not found", true) == true || msg?.contains("404") == true ->
                "\n\nSpotify kan dit apparaat nu niet bereiken. Cast-apparaten (Nest, Chromecast, tv) werken vaak pas nadat je ze één keer vanuit de Spotify-app hebt gekozen. Of kies dezelfde speler \"via Music Assistant\"."
            msg?.contains("restricted", true) == true ->
                "\n\nDit apparaat laat zich niet op afstand bedienen door Spotify."
            else -> ""
        }
        AlertDialog.Builder(this)
            .setTitle("Afspelen op $device mislukt")
            .setMessage((msg ?: "Onbekende fout") + hint)
            .setPositiveButton("OK", null)
            .show()
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
            var openedApp = false
            if (phone == null) {
                // Spotify op de achtergrond wekken met een "afspelen"-knopdruk (zoals een koptelefoonknop):
                // de app komt dan niet in beeld, maar meldt zich wel als apparaat
                for (code in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    runCatching {
                        sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage("com.spotify.music")
                            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(code, KeyEvent.KEYCODE_MEDIA_PLAY)))
                    }
                }
                for (t in 0 until 6) {
                    Thread.sleep(1_000)
                    phone = findPhone()
                    if (phone != null) break
                }
            }
            if (phone == null) {
                // Lukt dat niet: de app toch even openen, en daarna Thuisbasis weer naar voren halen
                openedApp = true
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
            if (openedApp) runOnUiThread {
                runCatching {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
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

    // ---------- Tags (titel, artiest, album, hoes) uit de muziekbestanden zelf ----------

    private class Tags(val title: String?, val artist: String?, val album: String?, val art: android.graphics.Bitmap?)
    private val tagCache = ConcurrentHashMap<String, Tags>()
    private val tagPool = java.util.concurrent.Executors.newFixedThreadPool(2)

    /** Tags van een nummer op de netwerkmap lezen (alleen het begin van het bestand wordt opgehaald). */
    private fun readTags(e: SmbMusic.Entry, cb: (Tags) -> Unit) {
        tagCache[e.path]?.let { cb(it); return }
        tagPool.execute {
            val t = runCatching {
                smbConfigure()
                val url = smb.urlFor(e.path)
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(url, HashMap())
                    val pic = mmr.embeddedPicture?.let { bytes ->
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                        var sample = 1
                        while (o.outWidth / (sample * 2) >= 160) sample *= 2
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                    val title = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifEmpty { null }
                    val artist = (mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))?.trim()?.ifEmpty { null }
                    val album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.ifEmpty { null }
                    // Geen hoes in het bestand: cover.jpg in de map, anders online opzoeken
                    Tags(title, artist, album, pic ?: findCover(e, artist, album, title))
                } finally {
                    runCatching { mmr.release() }
                }
            }.getOrElse { Tags(null, null, null, null) }
            tagCache[e.path] = t
            runOnUiThread { cb(t) }
        }
    }

    /** Gevonden hoesjes per map en per album (null = gezocht, niets gevonden). */
    private val coverCache = ConcurrentHashMap<String, java.util.Optional<android.graphics.Bitmap>>()

    private fun decodeCover(bytes: ByteArray): android.graphics.Bitmap? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= 160) sample *= 2
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Hoes zoeken: eerst cover.jpg/folder.jpg in de map, anders online (iTunes) op artiest + album. */
    private fun findCover(e: SmbMusic.Entry?, artist: String?, album: String?, title: String?): android.graphics.Bitmap? {
        val dir = e?.path?.substringBeforeLast('\\', "")
        if (dir != null) coverCache["dir:$dir"]?.let { if (it.isPresent) return it.get() }
        if (dir != null && !coverCache.containsKey("dir:$dir")) {
            val names = listOf("cover.jpg", "folder.jpg", "front.jpg", "Cover.jpg", "Folder.jpg", "Front.jpg", "cover.png", "folder.png", "AlbumArt.jpg")
            val found = names.firstNotNullOfOrNull { n ->
                smb.readSmall(if (dir.isEmpty()) n else "$dir\\$n")?.let { decodeCover(it) }
            }
            coverCache["dir:$dir"] = java.util.Optional.ofNullable(found)
            if (found != null) return found
        }
        if (artist == null) return null
        val key = "web:${artist.lowercase(NL)}|${(album ?: title ?: "").lowercase(NL)}"
        coverCache[key]?.let { return if (it.isPresent) it.get() else null }
        val bmp = runCatching {
            val term = java.net.URLEncoder.encode("$artist ${album ?: title ?: ""}".trim(), "UTF-8")
            val entity = if (album != null) "album" else "song"
            val conn = URL("https://itunes.apple.com/search?term=$term&entity=$entity&limit=15&country=NL").openConnection() as HttpURLConnection
            conn.connectTimeout = 6_000; conn.readTimeout = 8_000
            val res = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("results")
            val items = (0 until (res?.length() ?: 0)).mapNotNull { res!!.optJSONObject(it) }
            // Het album dat echt zo heet (anders zou bijv. een ander album van dezelfde artiest gekozen kunnen worden)
            fun norm(x: String) = x.lowercase(NL).replace(Regex("[^a-z0-9]"), "")
            val best = album?.let { al -> items.firstOrNull { norm(it.optString("collectionName")) == norm(al) }
                ?: items.firstOrNull { norm(it.optString("collectionName")).startsWith(norm(al)) } } ?: items.firstOrNull()
            val art = best?.optString("artworkUrl100")?.takeIf { it.startsWith("http") }
                ?.replace("100x100bb", "300x300bb") ?: return@runCatching null
            val c2 = URL(art).openConnection() as HttpURLConnection
            c2.connectTimeout = 6_000; c2.readTimeout = 10_000
            decodeCover(c2.inputStream.use { it.readBytes() })
        }.getOrNull()
        coverCache[key] = java.util.Optional.ofNullable(bmp)
        return bmp
    }

    /**
     * Hoes voor wat een speler (bijv. Music Assistant op de Chromecast) nu speelt als die zelf geen
     * plaatje meegeeft: via de map die we daar afspelen, anders online op artiest/album/titel.
     */
    private fun coverForPlaying(entity: String, artist: String?, album: String?, title: String?): android.graphics.Bitmap? {
        val f = maFolder
        val entry = if (f != null && f.first == entity && title != null) {
            val t = title.lowercase(NL)
            f.third.firstOrNull { e -> tagCache[e.path]?.title?.equals(title, true) == true }
                ?: f.third.firstOrNull { e -> e.name.lowercase(NL).contains(t) }
        } else null
        entry?.let { tagCache[it.path]?.art }?.let { return it }
        return findCover(entry, artist, album, title)
    }

    /**
     * Regel in een wachtrij voor een nummer van de netwerkmap: eerst de bestandsnaam, zodra de tags
     * binnen zijn titel, artiest en hoes. [now] = speelt nu; [curTitle] = titel die de speler meldt.
     */
    private fun tagRow(e: SmbMusic.Entry, nr: Int, now: Boolean, curTitle: String?, done: Boolean, onClick: () -> Unit): View {
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { setColor(0xFF3A3A3A.toInt()); cornerRadius = dp(6).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) }
        }
        val t = TextView(this).apply {
            text = "$nr.  " + e.name.substringBeforeLast('.')
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sub = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFA0A0A0.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        val bg = GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(5) }
            addView(img)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(t)
                addView(sub)
            })
            isClickable = true
            setOnClickListener { onClick() }
        }
        fun style(on: Boolean) {
            bg.setColor(if (on) 0xFF2E2610.toInt() else 0xFF262626.toInt())
            t.setTextColor(if (on) 0xFFFFB300.toInt() else if (done) 0xFF8A8A8A.toInt() else Color.WHITE)
        }
        style(now)
        readTags(e) { tags ->
            tags.title?.let { t.text = "$nr.  $it" }
            val s2 = joinNonEmpty(tags.artist, tags.album)
            if (s2.isNotEmpty()) { sub.text = s2; sub.visibility = View.VISIBLE }
            tags.art?.let { img.setImageBitmap(it) }
            if (!now && curTitle != null && tags.title != null && tags.title.equals(curTitle, true)) style(true)
        }
        return row
    }

    // ---------- Geluid op deze telefoon: andere apps stil zetten ----------

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    private var focusReq: android.media.AudioFocusRequest? = null

    private fun dropAudioFocus() {
        focusReq?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusReq = null
    }

    /**
     * Audiofocus pakken: andere apps op deze telefoon (bijv. Spotify) stoppen dan met spelen.
     * [keep] = we spelen zelf af en houden de focus; anders even pakken en weer loslaten.
     */
    private fun takeAudioFocus(keep: Boolean) {
        dropAudioFocus()
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setOnAudioFocusChangeListener { change ->
                // Een andere app gaat spelen: wij pauzeren (bij een korte melding hervatten we daarna)
                runOnUiThread {
                    val lp = localPlayer ?: return@runOnUiThread
                    when (change) {
                        android.media.AudioManager.AUDIOFOCUS_LOSS,
                        android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                            if (runCatching { lp.isPlaying }.getOrDefault(false)) {
                                lp.pause(); pausedByFocus = change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            }
                        android.media.AudioManager.AUDIOFOCUS_GAIN ->
                            if (pausedByFocus) { pausedByFocus = false; lp.start() }
                    }
                }
            }
            .build()
        runCatching { audioManager.requestAudioFocus(req) }
        focusReq = req
        if (!keep) handler.postDelayed({ if (focusReq === req && localPlayer == null) dropAudioFocus() }, 1_500)
    }

    @Volatile private var pausedByFocus = false

    /** Iets elders laten spelen: geluid op deze telefoon (eigen speler én andere apps) stoppen. */
    private fun silencePhone() {
        stopLocal()
        takeAudioFocus(keep = false)
    }

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
                        takeAudioFocus(keep = true)
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
        val had = localPlayer != null
        localPlayer?.let { runCatching { it.release() } }
        localPlayer = null
        localQueue = emptyList()
        if (had) dropAudioFocus()
    }

    /** Hele songtekst in een scrollbaar venster. */
    private fun showLyricsText(title: String, text: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply {
                addView(TextView(this@MainActivity).apply {
                    this.text = text
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setLineSpacing(0f, 1.2f)
                    setPadding(dp(22), dp(8), dp(22), dp(16))
                })
            })
            .setPositiveButton("Sluiten", null)
            .show()
    }

    /** Bediening voor muziek van de netwerkmap op deze telefoon: voortgang, spoelen, wachtrij (veegbaar). */
    private fun showLocalSheet() {
        val dialog = sheet()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(22))
        }
        content.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(0xFF555555.toInt()); cornerRadius = dp(2).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) }
        })
        content.addView(TextView(this).apply {
            text = "Deze telefoon"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        val cover = TextView(this).apply {
            text = "♫"
            textSize = 64f
            setTextColor(0xFFFFB300.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0xFF2E2610.toInt()); cornerRadius = dp(12).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(160), dp(160)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) }
        }
        content.addView(cover)
        val title = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(12), 0, 0)
        }
        val sub = TextView(this).apply {
            textSize = 14f; setTextColor(0xFFB0B0B0.toInt()); gravity = Gravity.CENTER; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, 0, dp(8))
        }
        content.addView(title)
        content.addView(sub)
        val lyricsBar = LyricsBar(this, 0xFFFFC94D.toInt()) { showLyricsText(title.text.toString(), it) }
        content.addView(lyricsBar)
        val bar = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(0xFFFFB300.toInt())
            thumbTintList = ColorStateList.valueOf(0xFFFFB300.toInt())
        }
        val pos = TextView(this).apply { textSize = 12f; setTextColor(0xFF999999.toInt()) }
        val dur = TextView(this).apply { textSize = 12f; setTextColor(0xFF999999.toInt()); gravity = Gravity.END }
        content.addView(bar)
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(14), dp(8))
            addView(pos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(dur, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        var dragging = false
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                val d = runCatching { localPlayer?.duration ?: 0 }.getOrDefault(0)
                if (fromUser && d > 0) pos.text = fmtSec(d / 1000.0 * v / 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                localPlayer?.let { lp -> runCatching { lp.seekTo((lp.duration.toLong() * sb.progress / 1000).toInt()) } }
            }
        })
        fun seekBy(sec: Int) {
            localPlayer?.let { lp -> runCatching { lp.seekTo((lp.currentPosition + sec * 1000).coerceIn(0, lp.duration)) } }
        }
        fun ctl(label: String, size: Float = 24f, onTap: () -> Unit) = TextView(this).apply {
            text = label; textSize = size; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(14).toFloat() }
            isClickable = true
            setOnClickListener { alpha = 0.5f; postDelayed({ alpha = 1f }, 150); onTap() }
            layoutParams = LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(6); marginEnd = dp(6) }
        }
        val playBtn = ctl("❚❚", 22f) { toggleLocal() }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            addView(ctl("⏮\uFE0E") {
                // Na 3 s: terug naar het begin van dit nummer, anders het vorige
                val lp = localPlayer
                if (lp != null && runCatching { lp.currentPosition }.getOrDefault(0) > 3_000) runCatching { lp.seekTo(0) }
                else if (localIndex > 0) playLocalAt(localIndex - 1)
            })
            addView(ctl("−15", 16f) { seekBy(-15) })
            addView(playBtn)
            addView(ctl("+30", 16f) { seekBy(30) })
            addView(ctl("⏭\uFE0E") { if (localIndex + 1 < localQueue.size) playLocalAt(localIndex + 1) })
        })
        val queueHint = TextView(this).apply {
            text = "▲  Wachtrij"; textSize = 13f; setTextColor(0xFF9A9A9A.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4)); isClickable = true
        }
        content.addView(queueHint)
        val queueList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val queueScroll = ScrollView(this).apply {
            addView(queueList)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320))
        }
        content.addView(queueScroll)
        var shownIndex = -1
        fun fillQueue() {
            queueList.removeAllViews()
            shownIndex = localIndex
            localQueue.forEachIndexed { i, e ->
                queueList.addView(tagRow(e, i + 1, i == localIndex, null, i < localIndex) { playLocalAt(i) })
            }
        }
        var queueOpen = false
        fun setQueue(open: Boolean) {
            queueOpen = open
            queueScroll.visibility = if (open) View.VISIBLE else View.GONE
            cover.visibility = if (open) View.GONE else View.VISIBLE
            queueHint.text = if (open) "▼  Wachtrij inklappen" else "▲  Wachtrij"
            if (open) fillQueue()
        }
        queueHint.setOnClickListener { setQueue(!queueOpen) }
        val ticker = object : Runnable {
            override fun run() {
                val lp = localPlayer
                val e = localQueue.getOrNull(localIndex)
                if (e == null) { title.text = "Niets aan het spelen"; sub.text = "" }
                else {
                    val tg = tagCache[e.path]
                    if (tg == null) readTags(e) {}
                    title.text = tg?.title ?: e.name.substringBeforeLast('.')
                    sub.text = (joinNonEmpty(tg?.artist, tg?.album).ifEmpty { e.path.substringBeforeLast('\\').substringAfterLast('\\') }) +
                        "  ·  ${localIndex + 1}/${localQueue.size}"
                    if (tg?.art != null && cover.tag !== tg.art) {
                        cover.tag = tg.art
                        cover.text = ""
                        cover.background = android.graphics.drawable.BitmapDrawable(resources, tg.art)
                        cover.clipToOutline = true
                    }
                }
                val p = runCatching { lp?.currentPosition ?: 0 }.getOrDefault(0)
                val d = runCatching { lp?.duration ?: 0 }.getOrDefault(0)
                if (!dragging && d > 0) { bar.progress = (p.toLong() * 1000 / d).toInt(); pos.text = fmtSec(p / 1000.0) }
                e?.let { tagCache[it.path] }?.let { tg -> lyricsBar.load(tg.artist, tg.title, tg.album, if (d > 0) d / 1000.0 else null) }
                lyricsBar.update(p / 1000.0)
                dur.text = if (d > 0) fmtSec(d / 1000.0) else ""
                playBtn.text = if (runCatching { lp?.isPlaying == true }.getOrDefault(false)) "❚❚" else "▶\uFE0E"
                if (queueOpen && shownIndex != localIndex) fillQueue()
                handler.postDelayed(this, 500)
            }
        }
        dialog.setContentView(SwipeSheet(this, content, noIntercept = queueScroll,
            onUp = { if (!queueOpen) setQueue(true) },
            onDown = { if (queueOpen) setQueue(false) else dialog.dismiss() }))
        dialog.setOnKeyListener { _, _, event ->
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) dispatchKeyEvent(event) else false
        }
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            })
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { handler.removeCallbacks(ticker) }
        dialog.show()
        handler.post(ticker)
    }

    private fun playSpotify(item: SpotifyApi.Item, device: SpotifyApi.Device) {
        silencePhone()
        if ("picore" in device.name.lowercase(NL)) zoneUp = true
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
                r.exceptionOrNull()?.let { showPlayError(device.name, it.message, viaMa = false) }
                mediaStatus.text = r.exceptionOrNull()?.let { "Afspelen mislukt: ${it.message}" }
                    ?: "${item.name} speelt op ${device.name}"
                handler.postDelayed({ refreshMedia() }, 2_000)
            }
        }
    }

    /** Tegels: 3 naast elkaar, verhouding past precies bij de logo's (480×300). */
    private val TILE_COLS = 3
    private val TILE_H = 72
    private val TILE_GAP = 10

    private fun View.setupTile(color: Int, second: Boolean, onTap: () -> Unit) {
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(16).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(0, dp(TILE_H), 1f).apply { if (second) marginStart = dp(TILE_GAP) }
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
            .setMessage("Alle lampen, de tv en de receiver gaan uit. De GoogleTV-box en TV+ blijven aan.")
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
                // GoogleTV-box en TV+ blijven aan (uit = standby, en dan reageren ze traag/niet)
            )
            // Wel stoppen met afspelen op de box, zodat Kodi e.d. niet doorspelen
            quietService("media_player/media_stop", JSONObject().put("entity_id", MEDIA_PLAYER))
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
        zoneUp = false   // iets beneden gestart: balk toont weer de woonkamer
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
        return TV_CHANNELS.firstOrNull { it.num == n }
    }

    private val logoMem = android.util.LruCache<String, android.graphics.Bitmap>(120)

    /** Zenderlogo laden: eenmalig downloaden naar de cache-map, verkleind decoderen. */
    private fun loadLogo(view: ImageView, path: String, onFail: () -> Unit) {
        view.tag = path
        logoMem.get(path)?.let { view.setImageBitmap(it); return }
        imagePool.execute {
            val bmp = runCatching {
                val dir = java.io.File(cacheDir, "logos").apply { mkdirs() }
                val file = java.io.File(dir, path.replace('/', '_') + ".png")
                if (!file.exists() || file.length() == 0L) {
                    val c = URL(LOGO_BASE + path + ".png").openConnection() as HttpURLConnection
                    c.connectTimeout = 8_000; c.readTimeout = 10_000
                    if (c.responseCode !in 200..299) throw java.io.IOException("logo ${c.responseCode}")
                    val tmp = java.io.File(dir, file.name + ".tmp")
                    c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                    tmp.renameTo(file)
                }
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= 160) sample *= 2
                android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
            runOnUiThread {
                if (view.tag != path) return@runOnUiThread
                if (bmp == null) onFail() else { logoMem.put(path, bmp); view.setImageBitmap(bmp) }
            }
        }
    }

    /** Logo van een zender (of null als er geen is); bij mislukken wordt [fallback] zichtbaar. */
    private fun channelLogo(ch: TvChannel, w: Int, h: Int, fallback: View): ImageView? {
        val path = TV_LOGOS[ch.num] ?: return null
        fallback.visibility = View.GONE
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            layoutParams = LinearLayout.LayoutParams(w, h)
            contentDescription = ch.name
            loadLogo(this, path) { visibility = View.GONE; fallback.visibility = View.VISIBLE }
        }
    }

    /** Inklapbare groepskop; stand wordt onthouden. Geeft de container voor de inhoud terug. */
    private fun tvGroup(parent: LinearLayout, title: String, count: Int, defaultOpen: Boolean): LinearLayout {
        val key = "tvgrp_" + title
        var open = prefs().getBoolean(key, defaultOpen)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (open) View.VISIBLE else View.GONE }
        val head = TextView(this).apply {
            textSize = 12f
            letterSpacing = 0.05f
            setTextColor(0xFFCCCCCC.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(12), dp(4), dp(10))
            fun label() { text = (if (open) "▾  " else "▸  ") + title.uppercase(NL) + "  ·  " + count }
            label()
            isClickable = true
            setOnClickListener {
                open = !open
                prefs().edit().putBoolean(key, open).apply()
                body.visibility = if (open) View.VISIBLE else View.GONE
                label()
            }
        }
        parent.addView(head)
        parent.addView(body)
        return body
    }

    /** Keuzescherm dat van onderen omhoog komt: favorieten, alle zenders, zappen en nummer. */
    /** Tv-gids in de app: per zender wat er nu loopt (met voortgang) en wat er straks komt. Tik = overschakelen. */
    private fun showGuideSheet() {
        val dialog = sheet()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(18))
        }
        content.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(0xFF555555.toInt()); cornerRadius = dp(2).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) }
        })
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Tv-gids"
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, dp(6), 0, dp(6))
            text = "Gids laden…"
        }
        content.addView(status)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        val timeFmt = java.text.SimpleDateFormat("HH:mm", NL)
        fun hm(t: Long) = timeFmt.format(java.util.Date(t))
        val current = prefs().getInt(KEY_LAST_CHANNEL, -1)
        // Tijdstip in de gids: null = nu (live), anders terugbladeren
        var at: Long? = null
        val atFmt = java.text.SimpleDateFormat("EEE d MMM HH:mm", NL)
        lateinit var render: () -> Unit
        val atLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(16).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8); marginEnd = dp(8) }
        }
        fun setAt(t: Long?) {
            at = t?.takeIf { it < System.currentTimeMillis() - 60_000L }
            atLabel.text = at?.let { "↺  " + atFmt.format(java.util.Date(it)) } ?: "Nu"
            atLabel.background = GradientDrawable().apply {
                setColor(if (at == null) 0xFF2A2A2A.toInt() else 0xFF4A3A10.toInt()); cornerRadius = dp(16).toFloat()
            }
            render()
        }
        fun stepBtn(label: String, onTap: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(16).toFloat() }
            isClickable = true
            setOnClickListener { onTap() }
        }
        val hour = 3_600_000L
        fun floorHour(t: Long) = t - (t % (30 * 60_000L))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(2))
            addView(stepBtn("◀ 1 uur") { setAt(floorHour(at ?: System.currentTimeMillis()) - if (at == null) 0 else hour) })
            addView(atLabel)
            addView(stepBtn("1 uur ▶") { at?.let { setAt(it + hour) } })
        }, 2)  // direct onder de titel
        atLabel.setOnClickListener {
            // Dag kiezen (7 dagen terug), daarna het tijdstip
            val dayFmt = java.text.SimpleDateFormat("EEEE d MMMM", NL)
            val cal = java.util.Calendar.getInstance()
            val days = (0..7).map { back ->
                (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -back) }
            }
            val names = arrayOf("Nu (live)") + days.mapIndexed { i, c ->
                when (i) { 0 -> "Vandaag"; 1 -> "Gisteren"; else -> dayFmt.format(c.time) }
            }
            AlertDialog.Builder(this).setTitle("Terugkijken: welke dag?").setItems(names) { _, which ->
                if (which == 0) { setAt(null); return@setItems }
                val c = days[which - 1]
                android.app.TimePickerDialog(this, { _, h, m ->
                    c.set(java.util.Calendar.HOUR_OF_DAY, h); c.set(java.util.Calendar.MINUTE, m)
                    c.set(java.util.Calendar.SECOND, 0)
                    setAt(c.timeInMillis)
                }, 20, 30, true).show()
            }.show()
        }
        atLabel.text = "Nu"
        render = fun() {
            list.removeAllViews()
            var missing = 0
            fun header(t: String) = list.addView(TextView(this).apply {
                text = t.uppercase(NL)
                textSize = 12f
                letterSpacing = 0.05f
                setTextColor(0xFFAAAAAA.toInt())
                setPadding(0, dp(12), 0, dp(6))
            })
            fun rows(chs: List<TvChannel>, into: LinearLayout) = chs.forEach { ch ->
                val t = at ?: System.currentTimeMillis()
                val (now, next) = TvGuide.nowNext(ch.name, t)
                if (now == null && next == null) missing++
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = GradientDrawable().apply {
                        setColor(0xFF262626.toInt()); cornerRadius = dp(12).toFloat()
                        if (ch.num == current) setStroke(dp(2), 0xFF18BCF2.toInt())
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    isClickable = true
                    setOnClickListener { dialog.dismiss(); tuneChannel(ch.num, ch.name, if (at != null) now?.start else null) }
                }
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
                    isClickable = true
                    setOnClickListener { showChannelDaySheet(ch, at ?: System.currentTimeMillis()) }
                    addView(TextView(this@MainActivity).apply {
                        text = ch.num.toString()
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        setTypeface(typeface, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setPadding(dp(6), dp(3), dp(6), dp(3))
                        background = GradientDrawable().apply { setColor(ch.color); cornerRadius = dp(7).toFloat() }
                    })
                    val nameView = TextView(this@MainActivity).apply {
                        text = ch.name
                        textSize = 11f
                        setTextColor(0xFFCFCFCF.toInt())
                        gravity = Gravity.CENTER
                        maxLines = 2
                        setPadding(0, dp(3), 0, 0)
                    }
                    channelLogo(ch, dp(60), dp(30), nameView)?.let {
                        (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(4)
                        addView(it)
                    }
                    addView(nameView)
                })
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = now?.title ?: if (next == null) "Geen gidsgegevens" else "—"
                        textSize = 15f
                        setTextColor(if (now != null) Color.WHITE else 0xFF8A8A8A.toInt())
                        setTypeface(typeface, Typeface.BOLD)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    if (now != null) {
                        val frac = ((t - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        addView(LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(4), 0, dp(2))
                            addView(TextView(this@MainActivity).apply {
                                text = hm(now.start); textSize = 11f; setTextColor(0xFF9A9A9A.toInt())
                            })
                            addView(LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                background = GradientDrawable().apply { setColor(0xFF3A3A3A.toInt()); cornerRadius = dp(2).toFloat() }
                                layoutParams = LinearLayout.LayoutParams(0, dp(4), 1f).apply { marginStart = dp(6); marginEnd = dp(6) }
                                addView(View(this@MainActivity).apply {
                                    background = GradientDrawable().apply { setColor(0xFF18BCF2.toInt()); cornerRadius = dp(2).toFloat() }
                                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, frac + 0.0001f))
                                addView(View(this@MainActivity), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - frac + 0.0001f))
                            })
                            addView(TextView(this@MainActivity).apply {
                                text = hm(now.stop); textSize = 11f; setTextColor(0xFF9A9A9A.toInt())
                            })
                        })
                    }
                    if (next != null) addView(TextView(this@MainActivity).apply {
                        text = "${hm(next.start)}  ${next.title}"
                        textSize = 12f
                        setTextColor(0xFFA8A8A8.toInt())
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                })
                into.addView(row)
            }
            for (grp in TV_GROUPS) rows(grp.channels, tvGroup(list, grp.title, grp.channels.size, grp.open))
            status.text = if (TvGuide.data.isEmpty()) status.text
                else if (at != null) "Wat er toen liep · tik op de rij om het terug te kijken · logo = dagoverzicht"
                else "Tik op een zender om over te schakelen · tik op het logo voor het dagoverzicht"
        }
        render()
        thread {
            TvGuide.histDir = filesDir
            val err = TvGuide.ensure(cacheDir, TV_CHANNELS.map { it.name })
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                if (err != null && TvGuide.data.isEmpty()) status.text = "Gids niet beschikbaar: $err"
                render()
            }
        }
        val sheetScroll = ScrollView(this).apply { addView(content) }
        dialog.setContentView(SwipeSheet(this, sheetScroll, noIntercept = null, onUp = {}, onDown = { dialog.dismiss() },
            scroller = sheetScroll, canSwipeUp = false))
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            })
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.85).toInt())
        }
        dialog.show()
    }

    /** Dagoverzicht van één zender: 7 dagen terug t/m morgen, met beschrijving. Tik = overschakelen. */
    private fun showChannelDaySheet(ch: TvChannel, around: Long) {
        val dialog = sheet()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(18))
        }
        content.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(0xFF555555.toInt()); cornerRadius = dp(2).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) }
        })
        val title = TextView(this).apply {
            text = "${ch.num}  ${ch.name}"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            channelLogo(ch, dp(64), dp(32), View(this@MainActivity))?.let { addView(it) }
            addView(title)
        })
        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(8)) }
        content.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(dayRow)
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        val sheetScroll = ScrollView(this).apply { addView(content) }
        val hmFmt = java.text.SimpleDateFormat("HH:mm", NL)
        val dayFmt = java.text.SimpleDateFormat("EEE d", NL)
        fun dayStart(back: Int) = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, -back)
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val chips = ArrayList<TextView>()
        fun showDay(back: Int, focus: Long?) {
            chips.forEachIndexed { i, c ->
                c.background = GradientDrawable().apply {
                    setColor(if (7 - i == back) 0xFF18BCF2.toInt() else 0xFF2A2A2A.toInt()); cornerRadius = dp(14).toFloat()
                }
                c.setTextColor(if (7 - i == back) 0xFF0B2430.toInt() else Color.WHITE)
            }
            list.removeAllViews()
            val from = dayStart(back)
            val progs = TvGuide.range(ch.name, from, from + 24 * 3_600_000L)
            val now = System.currentTimeMillis()
            if (progs.isEmpty()) list.addView(TextView(this).apply {
                text = if (back > 0) "Geen gidsgegevens voor deze dag. De app bewaart de gids vanaf nu zelf, dus dit vult zich de komende dagen."
                    else "Geen gidsgegevens voor deze zender."
                textSize = 14f
                setTextColor(0xFFAAAAAA.toInt())
                setPadding(dp(4), dp(10), dp(4), dp(10))
            })
            var focusView: View? = null
            for (p in progs) {
                val live = p.start <= now && p.stop > now
                val past = p.stop <= now
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = GradientDrawable().apply {
                        setColor(if (live) 0xFF13303A.toInt() else 0xFF262626.toInt()); cornerRadius = dp(12).toFloat()
                        if (live) setStroke(dp(1), 0xFF18BCF2.toInt())
                    }
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { bottomMargin = dp(6) }
                    isClickable = true
                    setOnClickListener {
                        dialog.dismiss()
                        when {
                            p.start > System.currentTimeMillis() -> tuneChannel(ch.num, ch.name)
                            live -> AlertDialog.Builder(this@MainActivity).setTitle(p.title)
                                .setItems(arrayOf("Live kijken", "Vanaf het begin")) { _, w ->
                                    tuneChannel(ch.num, ch.name, if (w == 1) p.start else null)
                                }.show()
                            else -> tuneChannel(ch.num, ch.name, p.start)
                        }
                    }
                }
                row.addView(TextView(this).apply {
                    text = hmFmt.format(java.util.Date(p.start))
                    textSize = 14f
                    setTextColor(if (live) 0xFF18BCF2.toInt() else 0xFFCFCFCF.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = (if (past) "↺ " else if (live) "● " else "") + p.title
                        textSize = 15f
                        setTextColor(if (past) 0xFFD8D8D8.toInt() else Color.WHITE)
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    p.desc?.let { d ->
                        addView(TextView(this@MainActivity).apply {
                            text = d
                            textSize = 12f
                            setTextColor(0xFF9A9A9A.toInt())
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(0, dp(2), 0, 0)
                            var open = false
                            isClickable = true
                            setOnClickListener { open = !open; maxLines = if (open) 50 else 2 }
                        })
                    }
                })
                list.addView(row)
                if (focus != null && p.start <= focus && p.stop > focus) focusView = row
            }
            val fv = focusView
            sheetScroll.post { sheetScroll.scrollTo(0, if (fv != null) (fv.top + list.top - dp(80)).coerceAtLeast(0) else 0) }
        }
        val todayStart = dayStart(0)
        val aroundBack = if (around >= todayStart) 0 else (((todayStart - around) / (24 * 3_600_000L)) + 1).toInt().coerceIn(0, 7)
        for (back in 7 downTo -1) {
            val c = TextView(this).apply {
                text = when (back) { 0 -> "Vandaag"; 1 -> "Gisteren"; -1 -> "Morgen"; else -> dayFmt.format(java.util.Date(dayStart(back))) }
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(6) }
                isClickable = true
                setOnClickListener { showDay(back, null) }
            }
            chips.add(c); dayRow.addView(c)
        }
        showDay(aroundBack, around)
        (dayRow.parent as? android.widget.HorizontalScrollView)?.post {
            (dayRow.parent as android.widget.HorizontalScrollView).scrollTo(chips[7 - aroundBack].left - dp(60), 0)
        }
        dialog.setContentView(SwipeSheet(this, sheetScroll, noIntercept = null, onUp = {}, onDown = { dialog.dismiss() },
            scroller = sheetScroll, canSwipeUp = false))
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
            })
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.85).toInt())
        }
        dialog.show()
    }

    private fun showChannelSheet() {
        val dialog = sheet()
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

        fun grid(list: List<TvChannel>, into: LinearLayout) {
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
                        val nameView = TextView(this).apply {
                            text = ch.name
                            textSize = 13f
                            setTextColor(Color.WHITE)
                            setTypeface(typeface, Typeface.BOLD)
                            maxLines = 2
                            setPadding(dp(7), 0, 0, 0)
                        }
                        channelLogo(ch, 0, dp(28), nameView)?.let {
                            it.layoutParams = LinearLayout.LayoutParams(0, dp(28), 1f).apply { marginStart = dp(6) }
                            cell.addView(it)
                        }
                        cell.addView(nameView)
                        cell.isClickable = true
                        cell.setOnClickListener { dialog.dismiss(); tuneChannel(ch.num, ch.name) }
                    }
                    row.addView(cell)
                }
                into.addView(row)
            }
        }

        for (grp in TV_GROUPS) grid(grp.channels, tvGroup(content, grp.title, grp.channels.size, grp.open))

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

        val sheetScroll = ScrollView(this).apply { addView(content) }
        dialog.setContentView(SwipeSheet(this, sheetScroll, noIntercept = null, onUp = {}, onDown = { dialog.dismiss() },
            scroller = sheetScroll, canSwipeUp = false))
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
    /** Bediening voor een mediaspeler in HA (Chromecast Audio, PiCore, Spotify…): voortgang, spoelen, vorige/volgende. */
    private fun showMediaSheet(entity: String, label: String) {
        val dialog = sheet()
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
            text = label
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(12).toFloat() }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(160), dp(160)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12)
            }
        }
        content.addView(art)
        val title = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(12), 0, 0)
        }
        val artist = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB0B0B0.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(title)
        content.addView(artist)
        val lyricsBar = LyricsBar(this, 0xFF7CE3A0.toInt()) { showLyricsText(title.text.toString(), it) }
        content.addView(lyricsBar)
        val bar = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(0xFF1DB954.toInt())
            thumbTintList = ColorStateList.valueOf(0xFF1DB954.toInt())
        }
        val pos = TextView(this).apply { textSize = 12f; setTextColor(0xFF999999.toInt()) }
        val dur = TextView(this).apply { textSize = 12f; setTextColor(0xFF999999.toInt()); gravity = Gravity.END }
        val progBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(bar)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), 0, dp(14), dp(8))
                addView(pos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(dur, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        content.addView(progBox)

        var curPos = 0.0
        var curDur = 0.0
        var playing = false
        var dragging = false
        var lastFetch = 0L
        var curAlbumName: String? = null
        var curArtistName: String? = null
        var albumTried = ""
        var lastArt: String? = null
        fun render() {
            if (curDur <= 0) { progBox.visibility = View.GONE; return }
            progBox.visibility = View.VISIBLE
            dur.text = fmtSec(curDur)
            if (!dragging) {
                bar.progress = (curPos.coerceIn(0.0, curDur) * 1000 / curDur).toInt()
                pos.text = fmtSec(curPos.coerceIn(0.0, curDur))
            }
        }
        fun call(service: String, extra: JSONObject.() -> Unit = {}) {
            thread {
                quietService("media_player/$service", JSONObject().put("entity_id", entity).apply(extra))
                Thread.sleep(700)
                runOnUiThread { lastFetch = 0 }
            }
        }
        fun seekTo(sec: Double) {
            curPos = sec.coerceIn(0.0, curDur.takeIf { it > 0 } ?: sec)
            render()
            call("media_seek") { put("seek_position", curPos) }
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                if (fromUser) pos.text = fmtSec(curDur * v / 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) { dragging = false; seekTo(curDur * sb.progress / 1000) }
        })

        fun ctl(label: String, size: Float = 24f, onTap: () -> Unit) = TextView(this).apply {
            text = label
            textSize = size
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(14).toFloat() }
            isClickable = true
            setOnClickListener { alpha = 0.5f; postDelayed({ alpha = 1f }, 150); onTap() }
            layoutParams = LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(6); marginEnd = dp(6) }
        }
        val playBtn = ctl("⏯\uFE0E", 26f) {
            playing = !playing
            call("media_play_pause")
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            addView(ctl("⏮\uFE0E") { call("media_previous_track") })
            addView(ctl("−15", 16f) { seekTo(curPos - 15) })
            addView(playBtn)
            addView(ctl("+30", 16f) { seekTo(curPos + 30) })
            addView(ctl("⏭\uFE0E") { call("media_next_track") })
        })

        // Wachtrij: omhoog vegen (of tikken) klapt hem uit, omlaag vegen klapt in / sluit
        val queueHint = TextView(this).apply {
            text = "▲  Wachtrij"
            textSize = 13f
            setTextColor(0xFF9A9A9A.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
            isClickable = true
        }
        content.addView(queueHint)
        val queueList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val queueScroll = ScrollView(this).apply {
            addView(queueList)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320))
        }
        content.addView(queueScroll)
        fun queueRow(t: String, sub: String, img: String?, now: Boolean) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(if (now) 0xFF1F3326.toInt() else 0xFF262626.toInt()); cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(5) }
            val iv = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply { setColor(0xFF3A3A3A.toInt()); cornerRadius = dp(6).toFloat() }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) }
            }
            img?.let { loadImage(iv, it) }
            addView(iv)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = t
                    textSize = 14f
                    setTextColor(if (now) 0xFF7CE3A0.toInt() else Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (sub.isNotEmpty()) addView(TextView(this@MainActivity).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(0xFFA0A0A0.toInt())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
        }
        var queueOpen = false
        fun loadQueue() {
            // Map van Mijn muziek op deze speler: de hele map als wachtrij, tik = vanaf daar spelen
            val folder = maFolder
            if (folder != null && folder.first == entity) {
                queueList.removeAllViews()
                val cur = title.text.toString().lowercase(NL)
                folder.third.forEachIndexed { i, e ->
                    val stem = e.name.substringBeforeLast('.')
                    val now = cur.isNotEmpty() && (stem.lowercase(NL).contains(cur) || cur.contains(stem.lowercase(NL)))
                    queueList.addView(tagRow(e, i + 1, now, title.text.toString().ifEmpty { null }, false) {
                        playFolderOnMa(folder.third.drop(i), folder.first, folder.second)
                    })
                }
                return
            }
            // Spotify-album of -playlist via Music Assistant: de hele lijst ophalen bij Spotify
            val started = maStarted
            val startedOk = started != null && started.first == entity && started.third.uri.startsWith("spotify:")
            // Niet via de app gestart (of onbekend)? Album van het huidige nummer bij Spotify opzoeken
            val curAlbum = curAlbumName
            if (!startedOk && entity != SPOTIFY && curAlbum != null && spotify.isLoggedIn() && spotify.blockedFor() == 0L &&
                albumTried != "$entity|$curAlbum") {
                albumTried = "$entity|$curAlbum"
                val ar = curArtistName
                thread {
                    val uri = runCatching { spotify.findAlbumUri(curAlbum, ar) }.getOrNull()
                    if (uri != null) runOnUiThread {
                        // "guess": door de app geraden, wordt hieronder nog gecontroleerd
                        maStarted = Triple(entity, label, SpotifyApi.Item(curAlbum, "guess", uri, "album"))
                        if (queueOpen) loadQueue()
                    }
                }
            }
            if (started != null && started.first == entity && started.third.uri.startsWith("spotify:") &&
                (started.third.uri.contains(":album:") || started.third.uri.contains(":playlist:"))) {
                queueList.removeAllViews()
                queueList.addView(TextView(this).apply { text = "Laden…"; setTextColor(0xFF9A9A9A.toInt()); setPadding(dp(4), dp(8), 0, dp(8)) })
                thread {
                    val r = runCatching { spotify.contextTracks(started.third.uri) }
                    // Geraden album controleren: klopt het volgende nummer van Music Assistant met het album?
                    // Zo niet (bijv. een playlist of jaaroverzicht), dan is het geen album en tonen we alleen "volgende".
                    if (started.third.sub == "guess") {
                        val tracks = r.getOrNull().orEmpty()
                        fun norm(x: String) = x.lowercase(NL).replace(Regex("\\s+-\\s+.*$"), "").replace(Regex("[^a-z0-9]"), "")
                        val cur = norm(title.text.toString())
                        val idx = tracks.indexOfFirst { norm(it.name) == cur }
                        val maNext = runCatching {
                            val resp = JSONObject(haRequest("POST", "/api/services/music_assistant/get_queue?return_response",
                                JSONObject().put("entity_id", entity).toString()))
                            val n = resp.optJSONObject("service_response")?.optJSONObject(entity)?.optJSONObject("next_item")
                            n?.optJSONObject("media_item")?.optString("name")?.ifEmpty { null } ?: n?.optString("name")
                        }.getOrNull()
                        val albumNext = tracks.getOrNull(idx + 1)?.name
                        val fits = idx >= 0 && (maNext == null || albumNext == null || norm(maNext) == norm(albumNext))
                        if (!fits) {
                            runOnUiThread {
                                maStarted = null
                                if (queueOpen) loadQueue()
                            }
                            return@thread
                        }
                    }
                    runOnUiThread {
                        queueList.removeAllViews()
                        val tracks = r.getOrNull()
                        if (tracks.isNullOrEmpty()) {
                            queueList.addView(TextView(this).apply {
                                text = r.exceptionOrNull()?.message?.let { "Wachtrij niet beschikbaar ($it)" } ?: "Geen nummers gevonden"
                                setTextColor(0xFF9A9A9A.toInt()); setPadding(dp(4), dp(8), 0, dp(8))
                            })
                            return@runOnUiThread
                        }
                        queueList.addView(TextView(this).apply {
                            text = started.third.name.uppercase(NL)
                            textSize = 12f
                            setTextColor(0xFF1DB954.toInt())
                            setTypeface(typeface, Typeface.BOLD)
                            setPadding(dp(2), dp(4), 0, dp(6))
                        })
                        val cur = title.text.toString()
                        tracks.forEachIndexed { i, t ->
                            val now = t.name.equals(cur, true)
                            val v = queueRow("${i + 1}.  ${t.name}", t.artists, t.image, now)
                            v.isClickable = true
                            v.setOnClickListener {
                                // Vanaf dit nummer verder spelen (rest van het album/de playlist erachter)
                                thread {
                                    quietService("music_assistant/play_media", JSONObject().put("entity_id", entity)
                                        .put("media_id", JSONArray(tracks.drop(i).map { it.uri }))
                                        .put("media_type", "track").put("enqueue", "replace"))
                                }
                            }
                            queueList.addView(v)
                        }
                    }
                }
                return
            }
            queueList.removeAllViews()
            queueList.addView(TextView(this).apply { text = "Laden…"; setTextColor(0xFF9A9A9A.toInt()); setPadding(dp(4), dp(8), 0, dp(8)) })
            thread {
                // Spotify: volledige wachtrij via de Web API; Music Assistant: huidig en volgend nummer
                val r: Result<List<Triple<String, String, String?>>> = runCatching {
                    if (entity == SPOTIFY) spotify.queue()
                    else {
                        val resp = JSONObject(haRequest("POST", "/api/services/music_assistant/get_queue?return_response",
                            JSONObject().put("entity_id", entity).toString()))
                        val q = resp.optJSONObject("service_response")?.optJSONObject(entity) ?: JSONObject()
                        fun item(o: JSONObject?): Triple<String, String, String?>? {
                            if (o == null) return null
                            val mi = o.optJSONObject("media_item")
                            val name = mi?.optString("name")?.ifEmpty { null } ?: o.optString("name")
                            val artists = mi?.optJSONArray("artists")?.let { a ->
                                (0 until a.length()).joinToString(", ") { a.optJSONObject(it)?.optString("name").orEmpty() }
                            }.orEmpty()
                            val image = mi?.optString("image")?.takeIf { it.startsWith("http") }
                                ?: o.optString("image").takeIf { it.startsWith("http") }
                            return Triple(name, artists, image)
                        }
                        listOfNotNull(item(q.optJSONObject("next_item"))).ifEmpty { throw IOException("Geen volgend nummer bekend") }
                    }
                }
                runOnUiThread {
                    queueList.removeAllViews()
                    val items = r.getOrNull()
                    if (items.isNullOrEmpty()) {
                        queueList.addView(TextView(this).apply {
                            text = r.exceptionOrNull()?.message?.let { "Wachtrij niet beschikbaar ($it)" } ?: "De wachtrij is leeg"
                            setTextColor(0xFF9A9A9A.toInt()); setPadding(dp(4), dp(8), 0, dp(8))
                        })
                        return@runOnUiThread
                    }
                    queueList.addView(TextView(this).apply {
                        text = if (entity == SPOTIFY) "HIERNA" else "VOLGENDE"
                        textSize = 12f
                        setTextColor(0xFF1DB954.toInt())
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(dp(2), dp(4), 0, dp(6))
                    })
                    items.forEach { (t, a, img) -> queueList.addView(queueRow(t, a, img, false)) }
                }
            }
        }
        fun setQueue(open: Boolean) {
            queueOpen = open
            queueScroll.visibility = if (open) View.VISIBLE else View.GONE
            art.visibility = if (open || lastArt == null) View.GONE else View.VISIBLE
            queueHint.text = if (open) "▼  Wachtrij inklappen" else "▲  Wachtrij"
            if (open) loadQueue()
        }
        queueHint.setOnClickListener { setQueue(!queueOpen) }

        val ticker = object : Runnable {
            override fun run() {
                if (playing && !dragging) curPos += 1.0
                lyricsBar.update(curPos)
                render()
                playBtn.text = if (playing) "❚❚" else "▶\uFE0E"
                val now = System.currentTimeMillis()
                if (now - lastFetch > 4_000) {
                    lastFetch = now
                    thread {
                        val st = stateOf(entity) ?: return@thread
                        val a = st.optJSONObject("attributes") ?: JSONObject()
                        var p = a.optDouble("media_position", 0.0)
                        val isPl = st.optString("state") == "playing"
                        if (isPl) runCatching {
                            p += (System.currentTimeMillis() - OffsetDateTime.parse(a.optString("media_position_updated_at"))
                                .toInstant().toEpochMilli()) / 1000.0
                        }
                        val pic = attr(st, "entity_picture")?.let { if (it.startsWith("http")) it else haBase() + it }
                        runOnUiThread {
                            if (!dialog.isShowing) return@runOnUiThread
                            val newTitle = attr(st, "media_title") ?: "Niets aan het spelen"
                            val changed = newTitle != title.text.toString()
                            title.text = newTitle
                            // Ander nummer terwijl de wachtrij open staat: wachtrij opnieuw laden
                            curAlbumName = attr(st, "media_album_name")
                            curArtistName = attr(st, "media_artist")
                            if (changed && queueOpen) loadQueue()
                            lyricsBar.load(attr(st, "media_artist"), attr(st, "media_title"), attr(st, "media_album_name"),
                                a.optDouble("media_duration", 0.0).takeIf { !it.isNaN() })
                            artist.text = joinNonEmpty(attr(st, "media_artist"), attr(st, "media_album_name"))
                            playing = isPl
                            curDur = a.optDouble("media_duration", 0.0).takeIf { !it.isNaN() } ?: 0.0
                            if (!dragging) curPos = if (p.isNaN()) 0.0 else p
                            val tKey = "cover:" + title.text
                            val ar = attr(st, "media_artist"); val al = attr(st, "media_album_name"); val ti = attr(st, "media_title")
                            fun lookup() {
                                lastArt = tKey
                                thread {
                                    val bmp = coverForPlaying(entity, ar, al, ti)
                                    runOnUiThread {
                                        if (bmp != null && lastArt == tKey) {
                                            art.tag = null
                                            art.setImageBitmap(bmp)
                                            if (!queueOpen) art.visibility = View.VISIBLE
                                        }
                                    }
                                }
                            }
                            if (pic != null && pic != lastArt && lastArt != tKey) {
                                lastArt = pic
                                if (!queueOpen) art.visibility = View.VISIBLE
                                // Plaatje van de speler laadt niet (bijv. geen hoes in het bestand): zelf zoeken
                                loadImage(art, pic) { lookup() }
                            }
                            // Geen plaatje van de speler: zelf zoeken (map of online), één keer per nummer
                            if (pic == null && lastArt != tKey && title.text.isNotEmpty()) {
                                lastArt = tKey
                                thread {
                                    val bmp = coverForPlaying(entity, ar, al, ti)
                                    runOnUiThread {
                                        if (bmp != null && lastArt == tKey) {
                                            art.setImageBitmap(bmp)
                                            if (!queueOpen) art.visibility = View.VISIBLE
                                        }
                                    }
                                }
                            }
                            render()
                        }
                    }
                }
                handler.postDelayed(this, 1_000)
            }
        }
        dialog.setContentView(SwipeSheet(this, content, noIntercept = queueScroll,
            onUp = { if (!queueOpen) setQueue(true) },
            onDown = { if (queueOpen) setQueue(false) else dialog.dismiss() }))
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
        dialog.setOnDismissListener { handler.removeCallbacks(ticker) }
        dialog.show()
        handler.post(ticker)
    }

    private fun showRemoteSheet(remote: String, kpn: Boolean) {
        val dialog = sheet()
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
            // Gids-toets van de box springt naar het beginscherm; daarom de gids in de app openen
            if (kpn) btn("Gids", null).apply {
                background = GradientDrawable().apply { setColor(0xFF1565C0.toInt()); cornerRadius = dp(14).toFloat() }
                isClickable = true
                setOnClickListener { dialog.dismiss(); showGuideSheet() }
            }
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
        // Afspelen. \uFE0E = gewoon symbool, geen (gele) emoji.
        // Vorige/volgende/pauze: speelt Music Assistant op deze box, dan via MA (de box kent de wachtrij niet)
        fun mediaBtn(label: String, service: String, key: String) = btn(label, null).apply {
            background = GradientDrawable().apply { setColor(0xFF2A2A2A.toInt()); cornerRadius = dp(14).toFloat() }
            isClickable = true
            setOnClickListener {
                alpha = 0.5f
                postDelayed({ alpha = 1f }, 150)
                thread {
                    val ma = maPlayerOnBox(remote)
                    if (ma != null) quietService("media_player/$service", JSONObject().put("entity_id", ma))
                    else runOnUiThread { send(key) }
                }
            }
        }
        content.addView(row(
            mediaBtn("⏮\uFE0E", "media_previous_track", "MEDIA_PREVIOUS"),
            btn("⏪\uFE0E", "MEDIA_REWIND"),
            mediaBtn("⏯\uFE0E", "media_play_pause", "MEDIA_PLAY_PAUSE"),
            btn("⏩\uFE0E", "MEDIA_FAST_FORWARD"),
            mediaBtn("⏭\uFE0E", "media_next_track", "MEDIA_NEXT"),
        ))
        if (kpn) {
            // Gekleurde knoppen (rood, groen, geel, blauw)
            content.addView(row(
                btn("", "PROG_RED", 0xFFD32F2F.toInt()),
                btn("", "PROG_GREEN", 0xFF388E3C.toInt()),
                btn("", "PROG_YELLOW", 0xFFFBC02D.toInt()),
                btn("", "PROG_BLUE", 0xFF1976D2.toInt()),
                height = 40,
            ))
            // Spoelen bij terugkijken zoals op de KPN-afstandsbediening: tijd intoetsen (mmss, 600 = 6 min)
            // en dan ⏪/⏩. Meerdere tikken tellen op (2× "3 min" = 6 min); verstuurd na een korte pauze.
            val jumpPending = intArrayOf(0, 0)   // minuten terug, vooruit
            var jumpStatus: TextView? = null
            val jumpSend = arrayOfNulls<Runnable>(2)
            fun jumpBtn(minutes: Int, forward: Boolean) = btn((if (forward) "+" else "−") + "$minutes min", null, size = 15f).apply {
                background = GradientDrawable().apply {
                    setColor(if (forward) 0xFF1E4D3A.toInt() else 0xFF33414A.toInt()); cornerRadius = dp(12).toFloat()
                }
                isClickable = true
                val d = if (forward) 1 else 0
                setOnClickListener {
                    alpha = 0.5f; postDelayed({ alpha = 1f }, 200)
                    jumpPending[d] += minutes
                    jumpStatus?.text = (if (forward) "⏩ vooruit " else "⏪ terug ") + "${jumpPending[d]} min…"
                    jumpStatus?.setTextColor(if (forward) 0xFF7CE3A0.toInt() else 0xFFB0C4CE.toInt())
                    jumpSend[d]?.let { handler.removeCallbacks(it) }
                    val r = Runnable {
                        val total = jumpPending[d]
                        jumpPending[d] = 0
                        if (total <= 0) return@Runnable
                        val digits = "${total}00".map { it.toString() }
                        val keys = JSONArray(digits).put(if (forward) "MEDIA_FAST_FORWARD" else "MEDIA_REWIND")
                        jumpStatus?.text = (if (forward) "⏩ " else "⏪ ") + "$total min verstuurd"
                        jumpStatus?.postDelayed({ jumpStatus?.text = "Tik vaker om op te tellen"; jumpStatus?.setTextColor(0xFF9FB7C2.toInt()) }, 3_000)
                        thread {
                            quietService("remote/send_command", JSONObject().put("entity_id", remote)
                                .put("command", keys).put("delay_secs", 0.3))
                        }
                    }
                    jumpSend[d] = r
                    handler.postDelayed(r, 1_200)
                }
            }
            // Blok "Spoelen bij terugkijken": per richting een rij met label en drie knoppen
            val jumpInfo = TextView(this).apply {
                textSize = 12f
                setTextColor(0xFF9FB7C2.toInt())
                text = "Tik vaker om op te tellen"
            }
            jumpStatus = jumpInfo
            fun jumpRow(label: String, forward: Boolean) = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                listOf(1, 3, 10).forEachIndexed { k, m ->
                    addView(jumpBtn(m, forward).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                            .apply { if (k > 0) marginStart = dp(8) }
                    })
                }
            }
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(14))
                background = GradientDrawable().apply { setColor(0xFF1B262C.toInt()); cornerRadius = dp(16).toFloat() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = "Spoelen bij terugkijken"
                        textSize = 15f
                        setTextColor(Color.WHITE)
                        setTypeface(typeface, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(jumpInfo)
                })
                addView(jumpRow("⏪  Terug", false))
                addView(jumpRow("⏩  Vooruit", true))
            })
            content.addView(row(
                btn("▼ CH−", "CHANNEL_DOWN"),
                btn("▲ CH+", "CHANNEL_UP"),
            ))
        }
        // Voortgang van wat er speelt (Kodi, Music Assistant of de box zelf); verslepen = spoelen
        val progTitle = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFCCCCCC.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        val progBar = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(0xFF18BCF2.toInt())
            thumbTintList = ColorStateList.valueOf(0xFF18BCF2.toInt())
        }
        val progPos = TextView(this).apply { textSize = 12f; setTextColor(0xFF999999.toInt()) }
        val progDur = TextView(this).apply { textSize = 12f; setTextColor(0xFF999999.toInt()); gravity = Gravity.END }
        val progBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(progTitle)
            addView(progBar)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), 0, dp(14), 0)
                addView(progPos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(progDur, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        content.addView(progBox)
        // Wat er nu speelt: (bron, positie, duur, speelt, titel); bron = "kodi:<id>" of een HA-entiteit
        class Prog(val src: String, var pos: Double, val dur: Double, val playing: Boolean, val title: String)
        var prog: Prog? = null
        var dragging = false
        var lastFetch = 0L
        fun render() {
            val p = prog
            if (p == null || p.dur <= 0) { progBox.visibility = View.GONE; return }
            progBox.visibility = View.VISIBLE
            progTitle.text = p.title
            progDur.text = fmtSec(p.dur)
            if (!dragging) {
                progBar.progress = (p.pos.coerceIn(0.0, p.dur) * 1000 / p.dur).toInt()
                progPos.text = fmtSec(p.pos.coerceIn(0.0, p.dur))
            }
        }
        progBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                val p = prog ?: return
                if (fromUser) progPos.text = fmtSec(p.dur * v / 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                val p = prog ?: run { dragging = false; return }
                val frac = sb.progress / 1000.0
                p.pos = p.dur * frac
                thread {
                    runCatching {
                        if (p.src.startsWith("kodi:")) kodi.seekPercent(p.src.removePrefix("kodi:").toInt(), frac * 100)
                        else haRequest("POST", "/api/services/media_player/media_seek",
                            JSONObject().put("entity_id", p.src).put("seek_position", p.dur * frac).toString())
                    }
                    Thread.sleep(800)
                    runOnUiThread { dragging = false; lastFetch = 0 }
                }
            }
        })
        fun fetch(): Prog? {
            if (remote == MEDIA_REMOTE && boxApp == KODI_PKG && kodiConfigured()) {
                runCatching { kodi.progress() }.getOrNull()?.let { (pid, pd, playing) ->
                    val title = runCatching { kodi.state()?.title }.getOrNull() ?: "Kodi"
                    return Prog("kodi:$pid", pd.first, pd.second, playing, title)
                }
            }
            val entity = maPlayerOnBox(remote) ?: if (remote == MEDIA_REMOTE) MEDIA_PLAYER else TVPLUS_PLAYER
            val st = stateOf(entity) ?: return null
            val a = st.optJSONObject("attributes") ?: return null
            val dur = a.optDouble("media_duration", 0.0)
            if (dur <= 0 || !a.has("media_position")) return null
            val playing = st.optString("state") == "playing"
            var pos = a.optDouble("media_position", 0.0)
            if (playing) runCatching {
                pos += (System.currentTimeMillis() - OffsetDateTime.parse(a.optString("media_position_updated_at"))
                    .toInstant().toEpochMilli()) / 1000.0
            }
            val title = listOf(a.optString("media_title"), a.optString("media_artist")).filter { it.isNotEmpty() }.joinToString(" · ")
            return Prog(entity, pos, dur, playing, title)
        }
        val ticker = object : Runnable {
            override fun run() {
                if (!dialog.isShowing && lastFetch != 0L) return
                prog?.let { if (it.playing && !dragging) it.pos += 1.0 }
                render()
                val now = System.currentTimeMillis()
                if (now - lastFetch > 5_000) {
                    lastFetch = now
                    thread { val p = fetch(); runOnUiThread { if (!dragging) { prog = p; render() } } }
                }
                handler.postDelayed(this, 1_000)
            }
        }
        handler.post(ticker)
        dialog.setOnDismissListener { handler.removeCallbacks(ticker) }

        val sheetScroll = ScrollView(this).apply { addView(content) }
        dialog.setContentView(SwipeSheet(this, sheetScroll, noIntercept = null, onUp = {}, onDown = { dialog.dismiss() },
            scroller = sheetScroll, canSwipeUp = false))
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
                val name = TV_CHANNELS.firstOrNull { it.num == n }?.name ?: "Kanaal $n"
                tuneChannel(n, name)
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    /** Alles aan (indien nodig) en het kanaalnummer als cijfertoetsen naar de TV+-box sturen. */
    /** Overschakelen; met [rewindTo] daarna terugspoelen naar dat tijdstip (KPN: minuten intoetsen + ⏪). */
    private fun tuneChannel(num: Int, name: String, rewindTo: Long? = null) {
        prefs().edit().putInt(KEY_LAST_CHANNEL, num).apply()
        mediaStatus.text = "$name kiezen…"
        showRemoteSheet(TVPLUS_REMOTE, true)  // na zappen direct de afstandsbediening
        thread {
            val boxWasOff = stateOf(TVPLUS_REMOTE)?.optString("state") != "on"
            if (boxWasOff) quietService("remote/turn_on", JSONObject().put("entity_id", TVPLUS_REMOTE))
            prepareWatching(SOURCE_TV)
            if (boxWasOff) Thread.sleep(6_000)  // box laten opstarten
            else {
                // Bij terugkijken zou het zendernummer als spoeltijd gelden: eerst naar het beginscherm (Home)
                quietService("remote/send_command", JSONObject().put("entity_id", TVPLUS_REMOTE).put("command", "HOME"))
                Thread.sleep(2_000)
            }
            val digits = num.toString().map { it.toString() }
            quietService(
                "remote/send_command",
                JSONObject().put("entity_id", TVPLUS_REMOTE).put("command", JSONArray(digits)).put("delay_secs", 0.3)
            )
            if (rewindTo != null) {
                Thread.sleep(5_000)  // zender eerst laten starten
                val minutes = ((System.currentTimeMillis() - rewindTo) / 60_000L + 1).toInt()
                if (minutes in 1..9_999) {
                    // Bij live tv zijn cijfers een zendernummer; eerst pauzeren zodat de box in terugkijk-stand staat
                    quietService("remote/send_command", JSONObject().put("entity_id", TVPLUS_REMOTE).put("command", "MEDIA_PAUSE"))
                    Thread.sleep(2_000)
                    val keys = JSONArray("${minutes}00".map { it.toString() }).put("MEDIA_REWIND")
                    quietService("remote/send_command", JSONObject().put("entity_id", TVPLUS_REMOTE)
                        .put("command", keys).put("delay_secs", 0.3))
                }
            }
            runOnUiThread {
                mediaStatus.text = if (rewindTo != null) "$name: terugkijken vanaf het begin" else "$name (kanaal $num)"
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
        if (zoneUp) return picoreInfo()
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
                val sp = stateOf(SPOTIFY)
                // Speelt Spotify via een omweg (bijv. Lyrion → Chromecast), dan kennen MA en de Chromecast
                // zelf de titel niet; Spotify weet hem wel
                val maTitle = ma?.let { attr(it, "media_title") }.orEmpty()
                val spPlaying = sp?.optString("state") == "playing"
                if (ma != null && (isPlaying(ma) || (ma.optString("state") == "paused" && !isPlaying(c))) &&
                    !(maTitle.isEmpty() && spPlaying)) {
                    return NowInfo(
                        title = "Chromecast Audio",
                        sub = joinNonEmpty(attr(ma, "media_artist"), attr(ma, "media_title")).ifEmpty { "Music Assistant" },
                        icon = null, iconBg = 0xFF455A64.toInt(),
                        entity = ma.optString("entity_id"), playing = isPlaying(ma),
                    )
                }
                // Speelt Spotify? Dan die gegevens gebruiken; anders wat de Chromecast zelf meldt
                // Speelt de Chromecast zelf (bijv. muziek van de netwerkmap), dan heeft die voorrang
                // Spotify alleen als die echt op de Chromecast speelt (niet op een ander apparaat)
                val castTitle = attr(c, "media_title").orEmpty()
                val spOnCast = attr(sp, "source")?.lowercase(NL)?.let { "chromecast" in it || "audio woonkamer" in it } ?: true ||
                    (spPlaying && castTitle.isEmpty() && isPlaying(c))
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

    // ---------- Zone boven: PiCore (via Music Assistant) als "bron" in de balk ----------

    /** true = de balk toont PiCore boven i.p.v. de receiver beneden. */
    private var zoneUp: Boolean
        get() = prefs().getBoolean(KEY_ZONE_UP, false)
        set(v) { prefs().edit().putBoolean(KEY_ZONE_UP, v).apply() }

    private fun picorePlayer(): Pair<String, String>? =
        maPlayers().firstOrNull { (_, n) -> "picore" in n.lowercase(NL) }

    private fun picoreInfo(): NowInfo {
        val p = picorePlayer()
        val st = p?.let { stateOf(it.first) }
        val active = st?.optString("state") in listOf("playing", "paused")
        return NowInfo(
            title = "${p?.second ?: "PiCore"} (boven)",
            sub = when {
                p == null -> "Niet gevonden in Music Assistant"
                active -> joinNonEmpty(attr(st, "media_artist"), attr(st, "media_title")).ifEmpty { "Music Assistant" }
                else -> "Speelt niets · kies muziek via Spotify of Mijn muziek"
            },
            icon = R.drawable.ic_folder_music, iconBg = 0xFF6D4C41.toInt(),
            entity = if (p != null && active) p.first else null, playing = isPlaying(st),
        )
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
        nowChannelBtn.visibility = View.GONE  // zender kiezen gaat via de gids
        nowGuideBtn.visibility = if (info.tv) View.VISIBLE else View.GONE
        nowRemote = info.remote
        // Afstandsbediening: box/TV+ met pijltjes, of bij muziek (Chromecast Audio, PiCore…) een speler-bediening
        val musicEntity = info.remote == null && info.entity != null && info.entity != "local"
        nowRemoteBtn.visibility = if (info.remote != null || musicEntity) View.VISIBLE else View.GONE
        // Typen kan alleen op de GoogleTV-box (via ADB)
        nowKeyboardBtn.visibility = if (info.remote == MEDIA_REMOTE) View.VISIBLE else View.GONE
        nowSubsBtn.visibility = if (info.remote == MEDIA_REMOTE && boxApp == KODI_PKG) View.VISIBLE else View.GONE
        nowPlayPause.text = if (info.playing) "❚❚" else "▶"
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

/**
 * Onderblad dat je kunt vegen: omhoog = [onUp], omlaag = [onDown]. Tijdens omlaag vegen schuift het mee.
 * Het vegen wordt hier vóór de knoppen en de scroll-lijst afgehandeld (dispatchTouchEvent), zodat een
 * ScrollView of knop het gebaar niet kan "inpikken". Omlaag vegen gaat pas als [scroller] bovenaan staat;
 * in [noIntercept] (bijv. de wachtrij) wordt niet geveegd.
 */
class SwipeSheet(
    ctx: Context, child: View, private val noIntercept: View?,
    private val onUp: () -> Unit, private val onDown: () -> Unit,
    private val scroller: View? = null,
    private val canSwipeUp: Boolean = true,
) : android.widget.FrameLayout(ctx) {
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private val density = ctx.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var blocked = false     // dit gebaar hoort bij de lijst/knoppen, niet bij het vegen
    private var velocity: android.view.VelocityTracker? = null
    private val content = child

    init { addView(child) }

    private fun inNoIntercept(x: Float, y: Float): Boolean {
        val v = noIntercept ?: return false
        if (v.visibility != View.VISIBLE) return false
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        val me = IntArray(2); getLocationOnScreen(me)
        val ax = x + me[0]; val ay = y + me[1]
        return ax >= loc[0] && ax <= loc[0] + v.width && ay >= loc[1] && ay <= loc[1] + v.height
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; dragging = false
                blocked = inNoIntercept(ev.x, ev.y)
                velocity?.recycle(); velocity = android.view.VelocityTracker.obtain()
                velocity?.addMovement(ev)
                super.dispatchTouchEvent(ev)
                return true   // altijd de rest van het gebaar blijven ontvangen
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (!dragging && !blocked && kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                    val down = dy > 0
                    val ok = if (down) scroller?.canScrollVertically(-1) != true else canSwipeUp
                    if (ok) {
                        dragging = true
                        // Knoppen/lijst laten weten dat dit gebaar niet voor hen is
                        val cancel = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    } else blocked = true   // bijv. lijst scrollt: die mag het gebaar hebben
                }
                if (dragging) {
                    content.translationY = if (dy > 0) dy else dy / 4
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    velocity?.addMovement(ev)
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    val dy = ev.y - downY
                    dragging = false
                    val fling = 900 * density
                    when {
                        ev.actionMasked == android.view.MotionEvent.ACTION_UP && (dy > 90 * density || (dy > 24 * density && vy > fling)) -> {
                            onDown()   // sluiten (of de wachtrij inklappen)
                            content.animate().translationY(0f).setDuration(150).start()
                        }
                        ev.actionMasked == android.view.MotionEvent.ACTION_UP && (dy < -40 * density || (dy < -16 * density && vy < -fling)) -> {
                            content.animate().translationY(0f).setDuration(150).start()
                            onUp()
                        }
                        else -> content.animate().translationY(0f).setDuration(150).start()
                    }
                    velocity?.recycle(); velocity = null
                    return true
                }
                velocity?.recycle(); velocity = null
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
