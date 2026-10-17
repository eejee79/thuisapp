package nl.ejvegt.hawerkplek

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Muziek van een SMB-share (bijv. \\192.168.50.208\NextcloudFiles\music):
 * mappen bladeren, en de nummers via een mini-webserver in de app doorgeven aan een
 * Chromecast (die alleen http-adressen kan afspelen).
 */
class SmbMusic {

    class Entry(val name: String, val path: String, val isDir: Boolean)

    companion object {
        private val AUDIO = mapOf(
            "mp3" to "audio/mpeg", "flac" to "audio/flac", "m4a" to "audio/mp4", "aac" to "audio/aac",
            "ogg" to "audio/ogg", "opus" to "audio/ogg", "wav" to "audio/wav", "wma" to "audio/x-ms-wma",
        )

        fun mimeOf(name: String) = AUDIO[name.substringAfterLast('.', "").lowercase()]
        fun isAudio(name: String) = mimeOf(name) != null

        /** "\\host\share\map\sub" → (host, share, "map\sub") */
        fun parseUnc(unc: String): Triple<String, String, String>? {
            val parts = unc.trim().replace('/', '\\').trim('\\').split('\\').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            return Triple(parts[0], parts[1], parts.drop(2).joinToString("\\"))
        }
    }

    @Volatile private var host = ""
    @Volatile private var shareName = ""
    @Volatile private var user = ""
    @Volatile private var pass = ""

    private val client = SMBClient()
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    fun configure(host: String, share: String, user: String, pass: String) {
        if (host != this.host || share != shareName || user != this.user || pass != this.pass) {
            close()
            this.host = host; this.shareName = share; this.user = user; this.pass = pass
        }
    }

    @Synchronized
    fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        share = null; session = null; connection = null
    }

    @Synchronized
    private fun disk(): DiskShare {
        share?.let { if (it.isConnected) return it }
        close()
        val c = client.connect(host)
        val s = c.authenticate(AuthenticationContext(user, pass.toCharArray(), ""))
        val d = s.connectShare(shareName) as DiskShare
        connection = c; session = s; share = d
        return d
    }

    /** Eén keer opnieuw proberen als de verbinding intussen verbroken was. */
    private fun <T> withShare(f: (DiskShare) -> T): T =
        try { f(disk()) } catch (e: Exception) { close(); f(disk()) }

    /** Inhoud van een map: eerst mappen, dan muziekbestanden, beide op naam. */
    fun list(path: String): List<Entry> = withShare { d ->
        d.list(path).filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith(".") }
            .map {
                val dir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                Entry(it.fileName, if (path.isEmpty()) it.fileName else "$path\\${it.fileName}", dir)
            }
            .filter { it.isDir || isAudio(it.name) }
            .sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() }))
    }

    /** Klein bestand lezen (bijv. cover.jpg); null als het niet bestaat of te groot is. */
    fun readSmall(path: String, max: Long = 3_000_000): ByteArray? = runCatching {
        withShare { d ->
            if (!d.fileExists(path)) return@withShare null
            d.openFile(
                path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null,
            ).use { f ->
                val size = f.fileInformation.standardInformation.endOfFile
                if (size <= 0 || size > max) return@use null
                f.inputStream.use { it.readBytes() }
            }
        }
    }.getOrNull()

    /** Bestand schrijven op de share (bijv. een ondertitel naast de video). */
    fun write(path: String, data: ByteArray) = withShare { d ->
        d.openFile(
            path, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
        ).use { f -> f.outputStream.use { it.write(data) } }
    }

    // ---------- Mini-webserver voor de Chromecast ----------

    private var server: ServerSocket? = null
    private val tokens = ConcurrentHashMap<String, String>()   // token → pad op de share

    /** IPv4-adres van de telefoon op het thuisnetwerk (wifi). */
    fun localIp(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress }
        .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }

    @Synchronized
    private fun ensureServer(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = runCatching { ServerSocket(8765) }.getOrElse { ServerSocket(0) }
        server = s
        thread(isDaemon = true, name = "muziek-server") {
            while (!s.isClosed) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(sock) }; runCatching { sock.close() } }
            }
        }
        return s.localPort
    }

    /** Webadres waarop de Chromecast dit nummer kan ophalen. */
    fun urlFor(path: String): String {
        val ip = localIp() ?: throw IllegalStateException("Geen wifi-adres gevonden (alleen thuis via wifi)")
        val port = ensureServer()
        val token = Integer.toHexString(path.hashCode()) + Integer.toHexString((Math.random() * 1e8).toInt())
        tokens[token] = path
        val ext = path.substringAfterLast('.', "mp3").lowercase()
        return "http://$ip:$port/a/$token.$ext"
    }

    private fun serve(sock: Socket) {
        sock.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        val requestLine = reader.readLine() ?: return
        var range: String? = null
        while (true) {
            val h = reader.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Range:", ignoreCase = true)) range = h.substringAfter(':').trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: ""
        val token = parts.getOrNull(1)?.substringAfter("/a/")?.substringBefore('.') ?: ""
        val out = sock.getOutputStream()
        val path = tokens[token]
        if (path == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val mime = mimeOf(path) ?: "application/octet-stream"
        // Geen automatische herhaling hier: als de Chromecast halverwege afhaakt, gewoon stoppen
        val d = runCatching { disk() }.getOrElse { close(); disk() }
        run {
            d.openFile(
                path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null,
            ).use { f ->
                val size = f.fileInformation.standardInformation.endOfFile
                var start = 0L
                var end = size - 1
                val partial = range?.startsWith("bytes=") == true
                if (partial) {
                    val spec = range!!.removePrefix("bytes=").substringBefore(',')
                    val a = spec.substringBefore('-')
                    val b = spec.substringAfter('-')
                    if (a.isNotEmpty()) {
                        start = a.toLong()
                        if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1)
                    } else if (b.isNotEmpty()) {
                        start = maxOf(0L, size - b.toLong())
                    }
                }
                val len = end - start + 1
                val head = StringBuilder()
                    .append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    .append("Content-Type: $mime\r\n")
                    .append("Content-Length: $len\r\n")
                    .append("Accept-Ranges: bytes\r\n")
                    .append("Access-Control-Allow-Origin: *\r\n")
                    .apply { if (partial) append("Content-Range: bytes $start-$end/$size\r\n") }
                    .append("Connection: close\r\n\r\n")
                out.write(head.toString().toByteArray())
                if (method != "HEAD") copy(f, start, len, out)
                out.flush()
            }
        }
    }

    private fun copy(f: com.hierynomus.smbj.share.File, start: Long, len: Long, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        var pos = start
        var left = len
        while (left > 0) {
            val n = f.read(buf, pos, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            pos += n
            left -= n
        }
    }
}
