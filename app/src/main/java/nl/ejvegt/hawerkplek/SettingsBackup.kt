package nl.ejvegt.hawerkplek

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Alle instellingen van de app als één versleuteld bestand (AES-256-GCM, sleutel uit de pincode via PBKDF2).
 * Zonder de pincode is het bestand onleesbaar; zo kun je het veilig via WhatsApp/mail/Drive overzetten.
 */
object SettingsBackup {
    private const val ITER = 120_000

    private fun key(pin: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITER, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    fun export(prefs: SharedPreferences, pin: String): String {
        val data = JSONObject()
        for ((k, v) in prefs.all) {
            val e = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> null
            } ?: continue
            data.put(k, e)
        }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pin, salt), GCMParameterSpec(128, iv))
        val enc = c.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        return JSONObject().put("app", "thuisbasis").put("v", 1)
            .put("salt", b64(salt)).put("iv", b64(iv)).put("data", b64(enc)).toString()
    }

    // ---- Compacte variant voor een QR-code: gecomprimeerd, zonder tijdelijke gegevens, zelfde versleuteling ----
    const val QR_PREFIX = "TB1:"        // versleuteld met pincode (oude QR-codes)
    const val QR_PREFIX_OPEN = "TB2:"   // zonder pincode
    fun isCode(t: String) = t.trim().let { it.startsWith(QR_PREFIX) || it.startsWith(QR_PREFIX_OPEN) }
    fun needsPin(t: String) = t.trim().startsWith(QR_PREFIX)
    /** Tijdelijke/toestelgebonden gegevens die niet mee hoeven naar een ander toestel. */
    private fun skipForQr(k: String) = k == "tab" || k == "ma_started" || k.startsWith("arr_season_")

    fun exportQr(prefs: SharedPreferences, pin: String? = null): String {
        val data = JSONObject()
        for ((k, v) in prefs.all) {
            if (skipForQr(k)) continue
            val e = when (v) {
                is String -> JSONArray().put("s").put(v)
                is Int -> JSONArray().put("i").put(v)
                is Long -> JSONArray().put("l").put(v)
                is Boolean -> JSONArray().put("b").put(v)
                is Float -> JSONArray().put("f").put(v.toDouble())
                is Set<*> -> JSONArray().put("ss").put(JSONArray(v.map { it.toString() }))
                else -> null
            } ?: continue
            data.put(k, e)
        }
        val zipped = ByteArrayOutputStream().also { bo -> GZIPOutputStream(bo).use { it.write(data.toString().toByteArray(Charsets.UTF_8)) } }.toByteArray()
        if (pin == null) return QR_PREFIX_OPEN + b64(zipped)
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pin, salt), GCMParameterSpec(128, iv))
        return QR_PREFIX + b64(salt + iv + c.doFinal(zipped))
    }

    private fun importQr(prefs: SharedPreferences, text: String, pin: String?): Int {
        val t = text.trim()
        val zipped = if (t.startsWith(QR_PREFIX_OPEN)) unb64(t.removePrefix(QR_PREFIX_OPEN)) else {
            val raw = unb64(t.removePrefix(QR_PREFIX))
            if (raw.size < 29) throw IllegalArgumentException("Dit is geen QR-code van Thuisbasis")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(pin ?: "", raw.copyOfRange(0, 16)), GCMParameterSpec(128, raw.copyOfRange(16, 28)))
            runCatching { c.doFinal(raw.copyOfRange(28, raw.size)) }.getOrElse { throw IllegalArgumentException("Pincode klopt niet") }
        }
        val data = JSONObject(GZIPInputStream(ByteArrayInputStream(zipped)).use { String(it.readBytes(), Charsets.UTF_8) })
        val ed = prefs.edit()
        var n = 0
        for (k in data.keys()) {
            val e = data.getJSONArray(k)
            when (e.getString(0)) {
                "s" -> ed.putString(k, e.getString(1))
                "i" -> ed.putInt(k, e.getInt(1))
                "l" -> ed.putLong(k, e.getLong(1))
                "b" -> ed.putBoolean(k, e.getBoolean(1))
                "f" -> ed.putFloat(k, e.getDouble(1).toFloat())
                "ss" -> ed.putStringSet(k, e.getJSONArray(1).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                else -> continue
            }
            n++
        }
        ed.commit()
        return n
    }

    /** Geeft het aantal teruggezette instellingen; gooit een fout bij een verkeerde pincode of een ander bestand. */
    fun import(prefs: SharedPreferences, file: String, pin: String?): Int {
        if (isCode(file)) return importQr(prefs, file, pin)
        val o = JSONObject(file)
        if (o.optString("app") != "thuisbasis") throw IllegalArgumentException("Dit is geen instellingenbestand van Thuisbasis")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(pin ?: "", unb64(o.getString("salt"))), GCMParameterSpec(128, unb64(o.getString("iv"))))
        val plain = runCatching { c.doFinal(unb64(o.getString("data"))) }
            .getOrElse { throw IllegalArgumentException("Pincode klopt niet") }
        val data = JSONObject(String(plain, Charsets.UTF_8))
        val ed = prefs.edit()
        var n = 0
        for (k in data.keys()) {
            val e = data.getJSONObject(k)
            when (e.optString("t")) {
                "s" -> ed.putString(k, e.getString("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "ss" -> ed.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                else -> continue
            }
            n++
        }
        ed.commit()
        return n
    }
}
