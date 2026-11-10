package ru.tinyops.android.bookrecon.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libtorrent4j.AlertListener
import org.libtorrent4j.MoveFlags
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.StorageMovedAlert
import org.libtorrent4j.alerts.StorageMovedFailedAlert
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.bdecode_node
import org.libtorrent4j.swig.byte_vector
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.int_string_map
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.load_torrent_limits
import org.libtorrent4j.swig.session_handle
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.tcp_endpoint_vector
import org.libtorrent4j.swig.torrent_flags_t
import ru.tinyops.android.bookrecon.core.domain.AppError
import ru.tinyops.android.bookrecon.core.domain.DownloadProgress
import ru.tinyops.android.bookrecon.core.domain.TorrentFile
import ru.tinyops.android.bookrecon.core.domain.TorrentInfo
import ru.tinyops.android.bookrecon.core.port.TorrentEngine
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.libtorrent4j.TorrentInfo as NativeTorrentInfo

/**
 * [TorrentEngine] on libtorrent4j.
 *
 * One libtorrent session serves all jobs. It starts with the first torrent and stops as soon
 * as no torrent is left in it, so an idle app keeps no sockets open. A torrent is in the
 * session only while it downloads, seeds or moves its data: a finished, failed or abandoned
 * download is removed from the session, and its job state (the `.torrent`, the selection and
 * resume data) stays in [stateDir] until [cancel], so [resume], [seed] and [moveStorage] can
 * bring it back.
 *
 * With a proxy set by [useProxy], trackers are reached only through that HTTP proxy. Peers
 * go through it too when asked: then DHT, LSD, uTP, UPnP and NAT-PMP, which cannot go
 * through it, are off, and the session listens on the loopback interface only, so no peer
 * reaches the device directly. Otherwise peers, DHT and incoming connections stay direct.
 */
class LibTorrentEngine(
    stateDir: File,
    private val config: Config = Config(),
    private val logger: TorrentLogger = AndroidTorrentLogger,
) : TorrentEngine {

    /**
     * @property localOnly listen on the loopback interface only and skip DHT, LSD, UPnP and
     *   NAT-PMP; for tests.
     * @property peers peers added to every torrent; for tests.
     */
    data class Config(
        val pollInterval: Duration = 2.seconds,
        val stallTimeout: Duration = 10.minutes,
        val resumeSaveInterval: Duration = 30.seconds,
        val localOnly: Boolean = false,
        val peers: List<InetSocketAddress> = emptyList(),
    )

    private enum class Mode { DOWNLOADING, SEEDING, MOVING }

    private class Active(val handle: TorrentHandle, val infoHash: String, val native: NativeTorrentInfo, var mode: Mode)

    internal val store = JobStore(stateDir)

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Guarded by lock.
    private var session: SessionManager? = null
    private var proxyPort: Int? = null
    private var proxyPeers = false
    private val active = HashMap<String, Active>()
    private val seedTimers = HashMap<String, Job>()

    private val moves = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    private val moveListener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.STORAGE_MOVED.swig(),
            AlertType.STORAGE_MOVED_FAILED.swig(),
        )

        override fun alert(alert: Alert<*>) {
            when (alert) {
                is StorageMovedAlert -> moves.remove(alert.handle().infoHash().toHex())?.complete(null)
                is StorageMovedFailedAlert ->
                    moves.remove(alert.handle().infoHash().toHex())?.complete(alert.error().message ?: "unknown error")
            }
        }
    }

    /** True while [jobId] has a torrent in the session. */
    internal fun isInSession(jobId: String): Boolean = runBlocking { lock.withLock { jobId in active } }

    internal val isSessionRunning: Boolean
        get() = runBlocking { lock.withLock { session != null } }

    /** Port the running session listens on; for tests. */
    internal val listenPort: Int
        get() = runBlocking { lock.withLock { session?.swig()?.listen_port() ?: 0 } }

    override fun parse(bytes: ByteArray): TorrentInfo = TorrentParser.parse(bytes)

    /**
     * Sends tracker traffic, and peer traffic too when [peers] is set, through the HTTP
     * CONNECT proxy on 127.0.0.1:[port], or everything directly again when [port] is null.
     * A running session switches at once.
     */
    suspend fun useProxy(port: Int?, peers: Boolean = true) = withContext(Dispatchers.IO) {
        lock.withLock {
            val proxiedPeers = port != null && peers
            if (port == proxyPort && proxiedPeers == proxyPeers) return@withLock
            proxyPort = port
            proxyPeers = proxiedPeers
            session?.applySettings(sessionSettings())
            logger.info(
                when {
                    port == null -> "Torrent traffic goes direct"
                    proxiedPeers -> "Torrent trackers and peers go through the server tunnel"
                    else -> "Torrent trackers go through the server tunnel, peers go direct"
                },
            )
        }
    }

    override fun download(jobId: String, bytes: ByteArray, indexes: Set<Int>, stagingDir: File): Flow<DownloadProgress> =
        flow {
            val info = parse(bytes)
            val selected = selectedFiles(info, indexes)
            val job = StoredJob(jobId, bytes, indexes, stagingDir.absoluteFile)
            val handle = lock.withLock {
                ensureNotBusy(jobId, info.infoHash)
                store.create(job)
                add(job, info, resume = null, mode = Mode.DOWNLOADING)
            }
            logger.info("Torrent download started: job $jobId, ${selected.size} files, ${info.infoHash}")
            monitor(jobId, job, info, selected, handle)
        }.flowOn(Dispatchers.IO)

    override fun resume(jobId: String): Flow<DownloadProgress> = flow {
        val job = store.load(jobId) ?: throw AppError.NotFound("no saved torrent for job $jobId")
        val info = parse(job.torrent)
        val selected = selectedFiles(info, job.indexes)
        val handle = lock.withLock {
            ensureNotBusy(jobId, info.infoHash)
            add(job, info, store.loadResume(jobId), Mode.DOWNLOADING)
        }
        logger.info("Torrent download resumed: job $jobId, ${info.infoHash}")
        monitor(jobId, job, info, selected, handle)
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(jobId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            seedTimers.remove(jobId)?.cancel()
            active[jobId]?.let { removeFromSession(jobId, it, deletePartFile = true) }
            store.delete(jobId)
        }
        logger.info("Torrent job cancelled: $jobId")
    }

    override suspend fun seed(jobId: String, duration: Duration) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = active[jobId]
            if (current != null) {
                current.mode = Mode.SEEDING
                current.handle.setFlags(TorrentFlags.UPLOAD_MODE)
                current.handle.resume()
            } else {
                attach(jobId, Mode.SEEDING)
            }
            seedTimers.remove(jobId)?.cancel()
            seedTimers[jobId] = scope.launch {
                delay(duration)
                finishSeeding(jobId)
            }
        }
        logger.info("Torrent seeding started: job $jobId for $duration")
    }

    override suspend fun pauseSeeding(jobId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (jobId !in seedTimers) throw AppError.NotFound("job $jobId is not seeding")
            val current = active[jobId] ?: return@withLock
            saveResume(jobId, current)
            removeFromSession(jobId, current, deletePartFile = false)
        }
        logger.info("Torrent seeding paused: job $jobId")
    }

    override suspend fun resumeSeeding(jobId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (jobId !in seedTimers) throw AppError.NotFound("job $jobId is not seeding")
            if (jobId in active) return@withLock
            attach(jobId, Mode.SEEDING)
        }
        logger.info("Torrent seeding resumed: job $jobId")
    }

    override suspend fun moveStorage(jobId: String, dir: File) = withContext(Dispatchers.IO) {
        val target = dir.absoluteFile
        if (!target.isDirectory && !target.mkdirs()) throw AppError.StorageFailed("cannot create the target folder")
        val done = CompletableDeferred<String?>()
        val current = lock.withLock {
            val current = active[jobId] ?: attach(jobId, Mode.MOVING)
            moves[current.infoHash] = done
            current.handle.moveStorage(target.absolutePath, MoveFlags.FAIL_IF_EXIST)
            current
        }
        val error = try {
            done.await()
        } finally {
            moves.remove(current.infoHash, done)
            withContext(NonCancellable) {
                lock.withLock {
                    if (active[jobId] !== current) return@withLock
                    saveResume(jobId, current)
                    if (current.mode == Mode.MOVING) removeFromSession(jobId, current, deletePartFile = false)
                }
            }
        }
        if (error != null) {
            logger.warn("Torrent data move failed: job $jobId, $error")
            throw AppError.StorageFailed("cannot move torrent data: $error")
        }
        logger.info("Torrent data moved: job $jobId")
    }

    /** Removes every torrent from the session and stops it; for tests and shutdown. */
    fun close() {
        scope.cancel()
        runBlocking {
            lock.withLock {
                active.keys.toList().forEach { id ->
                    val current = active.getValue(id)
                    saveResume(id, current)
                    removeFromSession(id, current, deletePartFile = false)
                }
                stopSessionIfIdle()
            }
        }
    }

    private suspend fun FlowCollector<DownloadProgress>.monitor(
        jobId: String,
        job: StoredJob,
        info: TorrentInfo,
        selected: List<TorrentFile>,
        current: Active,
    ) {
        val total = selected.sumOf { it.size }
        val tracker = ProgressTracker(config.stallTimeout, System.nanoTime())
        var lastResumeSave = System.nanoTime()
        var finished = false
        try {
            while (true) {
                if (!current.handle.isValid) throw AppError.Interrupted()
                val progress = current.handle.fileProgress()
                val completed = selected.sumOf { file -> progress.getOrElse(file.index) { 0L }.coerceIn(0, file.size) }
                val status = current.handle.status()
                val peers = status.numPeers()
                val now = System.nanoTime()
                val sample = tracker.sample(completed, peers, now)
                emit(DownloadProgress(completed, total, peers, sample.speed))

                if (completed >= total && (status.isFinished || status.isSeeding)) {
                    complete(jobId, job, info, selected, current)
                    finished = true
                    return
                }
                sample.failure?.let { failure ->
                    logger.warn("Torrent download failed: job $jobId, ${failure.code}")
                    throw failure
                }
                if (now - lastResumeSave >= config.resumeSaveInterval.inWholeNanoseconds) {
                    lock.withLock { if (active[jobId] === current) saveResume(jobId, current) }
                    lastResumeSave = now
                }
                delay(config.pollInterval)
            }
        } finally {
            if (!finished) {
                withContext(NonCancellable) { detach(jobId, current) }
            }
        }
    }

    /** Leaves the session with fresh resume data; the job can continue via [resume]. */
    private suspend fun detach(jobId: String, current: Active) {
        lock.withLock {
            if (active[jobId] !== current) return
            saveResume(jobId, current)
            removeFromSession(jobId, current, deletePartFile = false)
        }
        logger.info("Torrent download stopped: job $jobId")
    }

    private suspend fun complete(jobId: String, job: StoredJob, info: TorrentInfo, selected: List<TorrentFile>, current: Active) {
        lock.withLock {
            if (active[jobId] !== current) throw AppError.Interrupted()
            saveResume(jobId, current)
            removeFromSession(jobId, current, deletePartFile = true)
        }
        removeUnselected(job.stagingDir, info, selected, current)
        logger.info("Torrent download complete: job $jobId, ${selected.size} files")
    }

    /**
     * Pieces span file boundaries, so neighbours of selected files may be partly written.
     * Only files of this torrent are touched, never anything else in the staging folder.
     */
    private fun removeUnselected(stagingDir: File, info: TorrentInfo, selected: List<TorrentFile>, current: Active) {
        val keep = selected.map { File(stagingDir, it.path).absolutePath }.toSet()
        val storage = current.native.files()
        for (index in 0 until storage.numFiles()) {
            val file = File(storage.filePath(index, stagingDir.absolutePath))
            if (file.absolutePath in keep || !file.isFile) continue
            if (!file.delete()) logger.warn("Cannot remove an unselected torrent file: job ${stagingDir.name}")
        }
        File(stagingDir, ".${info.infoHash}.parts").delete()
        // Empty folders left behind; delete() refuses folders that still hold selected files.
        File(stagingDir, storage.name()).walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
    }

    private suspend fun finishSeeding(jobId: String) {
        lock.withLock {
            seedTimers.remove(jobId)
            active[jobId]?.let { removeFromSession(jobId, it, deletePartFile = true) }
            store.delete(jobId)
        }
        logger.info("Torrent seeding finished: job $jobId")
    }

    /** Brings a stored job back into the session; caller holds [lock]. */
    private fun attach(jobId: String, mode: Mode): Active {
        val job = store.load(jobId) ?: throw AppError.NotFound("no saved torrent for job $jobId")
        val info = parse(job.torrent)
        ensureNotBusy(jobId, info.infoHash)
        return add(job, info, store.loadResume(jobId), mode)
    }

    private fun ensureNotBusy(jobId: String, infoHash: String) {
        if (jobId in active || active.values.any { it.infoHash == infoHash }) throw AppError.AlreadyDownloading()
    }

    private fun selectedFiles(info: TorrentInfo, indexes: Set<Int>): List<TorrentFile> {
        if (indexes.isEmpty()) throw AppError.SelectionEmpty()
        val byIndex = info.files.associateBy { it.index }
        return indexes.sorted().map { byIndex[it] ?: throw AppError.Validation("file $it is not in the torrent") }
    }

    /** Adds a torrent to the session; caller holds [lock]. */
    private fun add(job: StoredJob, info: TorrentInfo, resume: ByteArray?, mode: Mode): Active {
        val native = try {
            NativeTorrentInfo(job.torrent)
        } catch (e: IllegalArgumentException) {
            throw AppError.ParseError("libtorrent rejected the torrent", e)
        }
        val params = resume?.let { readResume(job.id, it) } ?: add_torrent_params()
        params.set_ti(native.swig())
        // libtorrent 2.1 keeps trackers out of torrent_info, and resume data written before
        // this fix has none either; without them a proxied session (no DHT) finds no peers.
        if (params.getTrackers().isEmpty()) addTrackers(job, params)
        if (params.save_path.isNullOrEmpty()) params.setSave_path(job.stagingDir.absolutePath)
        params.set_file_priorities(priorities(native, job.indexes))
        // Files that version 0.9.0 renamed into the library keep their places from resume data.
        val renamed = renames(native, info, job.indexes)
        params.get_renamed_files().forEach { (index, path) -> renamed[index] = path }
        params.set_renamed_files(renamed)
        params.setFlags(flagsFor(mode))
        if (config.peers.isNotEmpty()) {
            val peers = tcp_endpoint_vector()
            config.peers.forEach { peers.add(TcpEndpoint(it.hostString, it.port).swig()) }
            params.setPeers(peers)
        }

        val session = ensureSession()
        val error = error_code()
        val handle = TorrentHandle(session.swig().add_torrent(params, error))
        if (error.failed()) {
            stopSessionIfIdle()
            throw AppError.Unknown("libtorrent cannot add the torrent: ${error.message()}")
        }
        val current = Active(handle, info.infoHash, native, mode)
        active[job.id] = current
        return current
    }

    /** Copies the announce list of the `.torrent` of [job] into [params]. */
    private fun addTrackers(job: StoredJob, params: add_torrent_params) {
        val buffer = Vectors.bytes2byte_vector(job.torrent)
        val node = bdecode_node()
        val error = error_code()
        bdecode_node.bdecode(buffer, node, error)
        val loaded = if (error.failed()) null else libtorrent.load_torrent_parsed(node, error, load_torrent_limits())
        if (loaded == null || error.failed()) {
            logger.warn("Cannot read the trackers of the torrent: job ${job.id}, ${error.message()}")
            return
        }
        params.setTrackers(loaded.getTrackers())
        params.setTracker_tiers(loaded.getTracker_tiers())
        logger.info("Torrent trackers added: job ${job.id}, ${loaded.getTrackers().size} trackers")
    }

    private fun readResume(jobId: String, data: ByteArray): add_torrent_params? {
        val error = error_code()
        val params = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(data), error)
        if (!error.failed()) return params
        logger.warn("Ignoring unreadable resume data: job $jobId, ${error.message()}")
        return null
    }

    private fun priorities(native: NativeTorrentInfo, indexes: Set<Int>): byte_vector {
        val vector = byte_vector()
        for (index in 0 until native.numFiles()) {
            vector.add(if (index in indexes) Priority.DEFAULT.swig() else Priority.IGNORE.swig())
        }
        return vector
    }

    /** Makes libtorrent write selected files exactly where [TorrentParser] said they are. */
    private fun renames(native: NativeTorrentInfo, info: TorrentInfo, indexes: Set<Int>): int_string_map {
        val map = int_string_map()
        val storage = native.files()
        info.files.filter { it.index in indexes }.forEach { file ->
            if (file.index >= storage.numFiles() || storage.fileSize(file.index) != file.size) {
                throw AppError.ParseError("torrent file list does not match libtorrent")
            }
            val nativePath = storage.filePath(file.index).replace(File.separatorChar, '/')
            if (nativePath != file.path) map[file.index] = file.path.replace('/', File.separatorChar)
        }
        return map
    }

    private fun flagsFor(mode: Mode): torrent_flags_t {
        val base = TorrentFlags.UPDATE_SUBSCRIBE.or_(TorrentFlags.APPLY_IP_FILTER)
        return when (mode) {
            Mode.DOWNLOADING -> base
            Mode.SEEDING -> base.or_(TorrentFlags.UPLOAD_MODE)
            Mode.MOVING -> base.or_(TorrentFlags.UPLOAD_MODE).or_(TorrentFlags.PAUSED)
        }
    }

    private fun saveResume(jobId: String, current: Active) {
        if (!current.handle.isValid) return
        try {
            val params = current.handle.swig().get_resume_data()
            store.saveResume(jobId, Vectors.byte_vector2bytes(libtorrent.write_resume_data_buf_ex(params)))
        } catch (e: AppError) {
            logger.warn("Cannot save torrent resume data: job $jobId", e)
        }
    }

    /** Caller holds [lock]. */
    private fun removeFromSession(jobId: String, current: Active, deletePartFile: Boolean) {
        active.remove(jobId)
        val session = session ?: return
        if (current.handle.isValid) {
            if (deletePartFile) {
                session.swig().remove_torrent(current.handle.swig(), session_handle.delete_partfile)
            } else {
                session.swig().remove_torrent(current.handle.swig())
            }
        }
        stopSessionIfIdle()
    }

    /** Caller holds [lock]. */
    private fun ensureSession(): SessionManager {
        session?.let { return it }
        val manager = SessionManager(false)
        manager.addListener(moveListener)
        manager.start(SessionParams(sessionSettings()))
        session = manager
        logger.info("Torrent session started")
        logger.debug { "Torrent session: proxy port ${proxyPort ?: "none"}, local only ${config.localOnly}" }
        return manager
    }

    /** Every setting that depends on [Config.localOnly], [proxyPort] and [proxyPeers], set both ways. Caller holds [lock]. */
    private fun sessionSettings(): SettingsPack {
        val port = proxyPort
        val peersProxied = port != null && proxyPeers
        val direct = !peersProxied && !config.localOnly
        val settings = SettingsPack()
        settings.listenInterfaces(if (direct) DEFAULT_LISTEN else LOOPBACK_LISTEN)
        settings.setEnableDht(direct)
        settings.setEnableLsd(direct)
        settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), direct)
        settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), direct)
        // uTP runs over UDP, which an HTTP proxy does not carry.
        settings.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), !peersProxied)
        settings.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), !peersProxied)
        val proxyType = if (port == null) settings_pack.proxy_type_t.none else settings_pack.proxy_type_t.http
        settings.setInteger(settings_pack.int_types.proxy_type.swigValue(), proxyType.swigValue())
        settings.setString(settings_pack.string_types.proxy_hostname.swigValue(), PROXY_HOST)
        settings.setInteger(settings_pack.int_types.proxy_port.swigValue(), port ?: 0)
        settings.setBoolean(settings_pack.bool_types.proxy_peer_connections.swigValue(), peersProxied)
        settings.setBoolean(settings_pack.bool_types.proxy_tracker_connections.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.proxy_hostnames.swigValue(), true)
        // Through the proxy every tracker connects to 127.0.0.1, and SSRF mitigation then
        // refuses announce paths other than /announce (RuTracker uses /ann). The server checks
        // tunnel targets itself.
        settings.setBoolean(settings_pack.bool_types.ssrf_mitigation.swigValue(), port == null)
        // Every peer is a WebSocket on the server, which caps tunnels per installation.
        settings.setInteger(
            settings_pack.int_types.connections_limit.swigValue(),
            if (peersProxied) PROXIED_CONNECTIONS else DEFAULT_CONNECTIONS,
        )
        return settings
    }

    /** Caller holds [lock]. */
    private fun stopSessionIfIdle() {
        if (active.isNotEmpty()) return
        val manager = session ?: return
        session = null
        manager.removeListener(moveListener)
        manager.stop()
        logger.info("Torrent session stopped")
    }

    private companion object {
        const val DEFAULT_LISTEN = "0.0.0.0:6881,[::]:6881"
        const val LOOPBACK_LISTEN = "127.0.0.1:0"
        const val PROXY_HOST = "127.0.0.1"
        const val DEFAULT_CONNECTIONS = 200
        const val PROXIED_CONNECTIONS = 40
    }
}
