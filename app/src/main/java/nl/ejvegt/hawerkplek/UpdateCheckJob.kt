package nl.ejvegt.hawerkplek

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Kijkt op de achtergrond (ongeveer elke 15 minuten, vaker staat Android niet toe) of er een
 * nieuwe versie op GitHub staat, en geeft dan één melding per nieuwe versie.
 */
class UpdateCheckJob : JobService() {

    companion object {
        private const val JOB_ID = 4711
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 1
        const val PREFS = "ha_settings"
        const val KEY_GH_TOKEN = "gh_update_token"
        const val KEY_AUTO_UPDATE = "auto_update"
        private const val KEY_NOTIFIED = "notified_version"
        const val REPO = "eejee79/thuisapp"
        /** Extra in de intent van de melding: meteen bijwerken. */
        const val EXTRA_INSTALL = "install_update"

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(job) }
        }

        /** "1.15" > "1.9": per onderdeel numeriek vergelijken. */
        fun isNewer(remote: String, local: String): Boolean {
            val r = remote.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val l = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(r.size, l.size)) {
                val a = r.getOrElse(i) { 0 }
                val b = l.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Melding als er een nieuwe versie van de app is"
                    }
                )
            }
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            runCatching { check() }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    private fun check() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_UPDATE, true)) return
        val token = prefs.getString(KEY_GH_TOKEN, "")!!.trim().ifEmpty { BuildConfig.GH_UPDATE_TOKEN.trim() }
        if (token.isEmpty()) return

        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("User-Agent", "thuisapp")
        if (conn.responseCode != 200) return
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val version = json.optString("tag_name").removePrefix("v").substringBefore('-')
        if (!isNewer(version, BuildConfig.VERSION_NAME)) return
        if (prefs.getString(KEY_NOTIFIED, "") == version) return  // al gemeld

        notifyUpdate(version)
        prefs.edit().putString(KEY_NOTIFIED, version).apply()
    }

    private fun notifyUpdate(version: String) {
        ensureChannel(this)
        val open = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_INSTALL, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("Thuisapp $version beschikbaar")
            .setContentText("Tik om bij te werken")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n) }
    }
}
