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

    /** Geeft het aantal teruggezette instellingen; gooit een fout bij een verkeerde pincode of een ander bestand. */
    fun import(prefs: SharedPreferences, file: String, pin: String): Int {
        val o = JSONObject(file)
        if (o.optString("app") != "thuisbasis") throw IllegalArgumentException("Dit is geen instellingenbestand van Thuisbasis")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(pin, unb64(o.getString("salt"))), GCMParameterSpec(128, unb64(o.getString("iv"))))
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
