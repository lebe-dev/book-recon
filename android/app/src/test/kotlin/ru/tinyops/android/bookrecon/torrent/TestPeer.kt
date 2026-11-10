package ru.tinyops.android.bookrecon.torrent

import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.byte_vector
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.tcp_endpoint_vector
import org.libtorrent4j.swig.torrent_handle
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A plain libtorrent peer on the loopback interface, running in its own process.
 *
 * libtorrent4j builds its bundled OpenSSL without thread support, so OpenSSL's locks and
 * reference counts are not atomic. Two libtorrent sessions in one process hash pieces on
 * their own threads at the same time, which can free OpenSSL's cached SHA-1 method while it
 * is still in use; the process then aborts in OpenSSL's exit handler. That is why the test
 * JVM runs only the engine under test, and every other peer runs here. The process is killed
 * when the peer is closed, so its own exit handlers never run.
 */
class TestPeer private constructor(private val process: Process) : AutoCloseable {
    private val lines = LinkedBlockingQueue<String>()

    val address: InetSocketAddress

    init {
        thread(isDaemon = true, name = "test-peer-output") {
            process.inputStream.bufferedReader().forEachLine { lines.put(it) }
            lines.put(EOF)
        }
        address = InetSocketAddress("127.0.0.1", expect(PORT, 30.seconds).toInt())
    }

    fun limitUpload(bytesPerSecond: Int) {
        command("$LIMIT $bytesPerSecond")
        expect(LIMITED, 10.seconds)
    }

    /** Waits until a leeching peer has every selected file. */
    fun awaitComplete(timeout: Duration) {
        expect(COMPLETE, timeout)
    }

    override fun close() {
        process.destroyForcibly()
        process.waitFor()
    }

    private fun command(line: String) {
        process.outputStream.write("$line\n".toByteArray())
        process.outputStream.flush()
    }

    private fun expect(prefix: String, timeout: Duration): String {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (true) {
            val left = deadline - System.nanoTime()
            check(left > 0) { "test peer did not report '$prefix' in $timeout" }
            val line = lines.poll(left, TimeUnit.NANOSECONDS) ?: continue
            check(line != EOF) { "test peer exited before reporting '$prefix'" }
            if (line.startsWith("$prefix ")) return line.removePrefix("$prefix ")
        }
    }

    companion object {
        private const val PORT = "port"
        private const val LIMIT = "limit"
        private const val LIMITED = "limited"
        private const val COMPLETE = "complete"
        private const val EOF = "\u0000eof"

        /** Seeds [torrent] from `<dir>/<name>/`. */
        fun seed(torrent: TestTorrent, dir: File): TestPeer = start("seed", torrent, dir, null, emptySet())

        /** Downloads the files [indexes] of [torrent] into [dir] from [peer]. */
        fun leech(torrent: TestTorrent, dir: File, peer: InetSocketAddress, indexes: Set<Int>): TestPeer =
            start("leech", torrent, dir, peer, indexes)

        private fun start(mode: String, torrent: TestTorrent, dir: File, peer: InetSocketAddress?, indexes: Set<Int>): TestPeer {
            dir.mkdirs()
            val torrentFile = File.createTempFile("peer", ".torrent", dir.parentFile).apply { writeBytes(torrent.bytes) }
            val java = ProcessHandle.current().info().command().orElse("java")
            val command = listOf(
                java,
                "-D$JNI_PATH=${System.getProperty(JNI_PATH)}",
                "-cp",
                System.getProperty("java.class.path"),
                TestPeer::class.java.name,
                mode,
                torrentFile.absolutePath,
                dir.absolutePath,
                peer?.port?.toString() ?: "0",
                indexes.joinToString(","),
            )
            val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            return try {
                TestPeer(process)
            } catch (e: Exception) {
                process.destroyForcibly()
                throw e
            }
        }

        private const val JNI_PATH = "libtorrent4j.jni.path"

        /** Entry point of the peer process: `seed|leech <torrent> <dir> <peer port> <indexes>`. */
        @JvmStatic
        fun main(args: Array<String>) {
            val (mode, torrentPath, dir, peerPort) = args
            val indexes = args.getOrNull(4).orEmpty().split(',').filter { it.isNotEmpty() }.map { it.toInt() }.toSet()
            val session = SessionManager(false)
            val settings = SettingsPack().listenInterfaces("127.0.0.1:0")
            settings.setEnableDht(false)
            settings.setEnableLsd(false)
            settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
            settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
            session.start(SessionParams(settings))

            val info = TorrentInfo(File(torrentPath).readBytes())
            val params = add_torrent_params()
            params.set_ti(info.swig())
            params.setSave_path(dir)
            if (mode == "seed") {
                params.setFlags(TorrentFlags.SEED_MODE)
            } else {
                val priorities = byte_vector()
                for (index in 0 until info.numFiles()) {
                    priorities.add(if (index in indexes) Priority.DEFAULT.swig() else Priority.IGNORE.swig())
                }
                params.set_file_priorities(priorities)
                val peers = tcp_endpoint_vector()
                peers.add(TcpEndpoint("127.0.0.1", peerPort.toInt()).swig())
                params.setPeers(peers)
            }
            val error = error_code()
            val handle: torrent_handle = session.swig().add_torrent(params, error)
            check(!error.failed()) { error.message() }
            println("$PORT ${session.swig().listen_port()}")

            if (mode == "leech") {
                thread(isDaemon = true) {
                    while (!handle.status().is_finished) Thread.sleep(50)
                    println("$COMPLETE ok")
                }
            }
            System.`in`.bufferedReader().forEachLine { line ->
                val parts = line.split(' ')
                if (parts.first() != LIMIT) return@forEachLine
                handle.set_upload_limit(parts[1].toInt())
                println("$LIMITED ${parts[1]}")
            }
            // Standard input closes when the test JVM is gone; nobody reads this exit status.
            Runtime.getRuntime().halt(0)
        }
    }
}
