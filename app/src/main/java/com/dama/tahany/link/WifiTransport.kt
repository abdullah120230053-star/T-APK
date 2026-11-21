package com.dama.tahany.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.dama.tahany.BuildConfig
import com.dama.tahany.files.MAX_TRANSFER_BYTES
import com.dama.tahany.protocol.MAX_CONTROL_MESSAGE_BYTES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

internal sealed interface TransportEvent {
    data class Hosting(val address: String) : TransportEvent
    data class ConnectionInitiated(
        val endpointId: String,
        val name: String,
        val channelToken: ByteArray,
    ) : TransportEvent
    data class Connected(val endpointId: String, val name: String) : TransportEvent
    data class Disconnected(val endpointId: String) : TransportEvent
    data class BytesReceived(val endpointId: String, val bytes: ByteArray) : TransportEvent
    data class FileReceived(val endpointId: String, val payloadId: Long, val uri: Uri) : TransportEvent
    data class StreamReceived(val endpointId: String, val stream: InputStream) : TransportEvent
    data class TransferProgress(
        val endpointId: String,
        val transferId: String,
        val bytesTransferred: Long,
        val status: TransferProgressStatus,
    ) : TransportEvent
    data class Failure(val message: String) : TransportEvent
}

internal enum class TransferProgressStatus { IN_PROGRESS, FAILURE }

internal class WifiTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val emit: (TransportEvent) -> Unit,
) {
    data class FilePayload(val id: Long, val file: File)

    private data class Wifi(val network: Network, val address: LinkAddress)
    private data class Frame(val type: Int, val id: Long, val data: ByteArray = byteArrayOf())

    private class Session(val id: String) {
        val frames = Channel<Frame>(16)
        @Volatile var authorized = false
        var job: Job? = null
        val files = mutableMapOf<Long, Job>()
        private val resources = mutableSetOf<Closeable>()
        private var closed = false

        @Synchronized
        fun own(resource: Closeable) {
            if (closed) {
                runCatching { resource.close() }
                throw CancellationException("Wi-Fi session closed")
            }
            resources.add(resource)
        }

        @Synchronized
        fun release(resource: Closeable) {
            resources.remove(resource)
            runCatching { resource.close() }
        }

        @Synchronized
        fun close() {
            closed = true
            resources.forEach { runCatching { it.close() } }
            resources.clear()
            frames.cancel()
        }
    }

    private val nextId = AtomicLong()
    private var active: Session? = null

    fun advertise() {
        start(UUID.randomUUID().toString(), "Could not host over Wi-Fi.") { session, wifi ->
            val server = WifiTls.serverContext().serverSocketFactory.createServerSocket() as SSLServerSocket
            session.own(server)
            server.enabledProtocols = server.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            server.bind(InetSocketAddress(wifi.address.address, 0))
            post(session, TransportEvent.Hosting("${wifi.address.address.hostAddress}:${server.localPort}"))
            val socket = server.accept() as SSLSocket
            session.own(socket)
            session.release(server)
            check(inSubnet(socket.inetAddress, wifi.address)) { "The other device must be on the same Wi-Fi network." }
            runConnection(session, socket, serverSide = true)
        }
    }

    fun connect(endpoint: String) {
        start(endpoint, "Could not reach the host. Check the address and make sure both devices are on the same Wi-Fi.") { session, wifi ->
            val parts = endpoint.split(':')
            require(parts.size == 2)
            val host = parseIpv4(parts[0]) ?: error("Enter the IPv4 address shown on the host.")
            val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: error("Enter the port shown on the host.")
            check(inSubnet(host, wifi.address)) { "The host address must be on your Wi-Fi network." }
            val raw = wifi.network.socketFactory.createSocket()
            session.own(raw)
            raw.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            val socket = WifiTls.clientContext().socketFactory.createSocket(raw, parts[0], port, true) as SSLSocket
            session.own(socket)
            runConnection(session, socket, serverSide = false)
        }
    }

    fun authorize(endpoint: String) {
        active?.takeIf { it.id == endpoint }?.authorized = true
    }

    fun sendBytes(endpoint: String, bytes: ByteArray) {
        val session = active?.takeIf { it.id == endpoint } ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.frames.send(Frame(CONTROL, 0, bytes))
        }
    }

    fun createFilePayload(file: File): FilePayload {
        require(file.length() in 0..MAX_TRANSFER_BYTES)
        return FilePayload(nextId.incrementAndGet(), file)
    }

    fun sendFile(endpoint: String, payload: FilePayload, transferId: String) {
        val session = active?.takeIf { it.id == endpoint && it.authorized } ?: return
        session.files[payload.id] = scope.launch {
            var completed = false
            try {
                withContext(Dispatchers.IO) {
                    session.frames.send(Frame(FILE_START, payload.id))
                    payload.file.inputStream().use { input ->
                        sendChunks(session, input, FILE_DATA, payload.id) { count ->
                            post(session, TransportEvent.TransferProgress(endpoint, transferId, count, TransferProgressStatus.IN_PROGRESS))
                        }
                    }
                    session.frames.send(Frame(FILE_END, payload.id))
                    completed = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                post(session, TransportEvent.TransferProgress(endpoint, transferId, 0, TransferProgressStatus.FAILURE))
            } finally {
                session.files.remove(payload.id)
                if (!completed && active === session) {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        session.frames.send(Frame(FILE_ABORT, payload.id))
                    }
                }
            }
        }
    }

    fun cancelPayload(id: Long) {
        active?.files?.get(id)?.cancel()
    }

    fun sendStream(endpoint: String, input: InputStream, onFailure: () -> Unit) {
        val session = active?.takeIf { it.id == endpoint && it.authorized }
        if (session == null) {
            input.close()
            onFailure()
            return
        }
        val id = nextId.incrementAndGet()
        session.own(input)
        scope.launch(Dispatchers.IO) {
            try {
                session.frames.send(Frame(STREAM_START, id))
                sendChunks(session, input, STREAM_DATA, id) {}
                session.frames.send(Frame(STREAM_END, id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                withContext(Dispatchers.Main.immediate) {
                    if (active === session) onFailure()
                }
            } finally {
                session.release(input)
            }
        }
    }

    fun stopSession() {
        val session = active ?: return
        active = null
        session.close()
        session.job?.cancel()
        session.files.values.toList().forEach(Job::cancel)
    }

    fun deleteIncomingFile(uri: Uri) {
        val path = uri.path ?: return
        if (uri.scheme == "file") File(path).delete()
    }

    private fun start(endpoint: String, failure: String, connect: suspend (Session, Wifi) -> Unit) {
        stopSession()
        val session = Session(endpoint)
        active = session
        session.job = scope.launch(Dispatchers.IO) {
            try {
                val wifi = currentWifi() ?: throw IllegalStateException("Connect this device to Wi-Fi and try again.")
                connect(session, wifi)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalStateException) {
                post(session, TransportEvent.Failure(error.message ?: failure))
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) Log.w("TahanyWifi", "Wi-Fi session failed", error)
                post(session, TransportEvent.Failure(failure))
            } finally {
                session.close()
            }
        }
    }

    private suspend fun runConnection(session: Session, socket: SSLSocket, serverSide: Boolean) {
        socket.soTimeout = TIMEOUT_MS
        socket.tcpNoDelay = true
        socket.startHandshake()
        val certificate = if (serverSide) socket.session.localCertificates.first() else socket.session.peerCertificates.first()
        val token = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        socket.soTimeout = 0
        post(session, TransportEvent.ConnectionInitiated(session.id, "Wi-Fi device", token))
        post(session, TransportEvent.Connected(session.id, "Wi-Fi device"))
        try {
            coroutineScope {
                val writer = launch {
                    val output = DataOutputStream(BufferedOutputStream(socket.outputStream))
                    try {
                        for (frame in session.frames) {
                            output.writeByte(frame.type)
                            output.writeLong(frame.id)
                            output.writeInt(frame.data.size)
                            output.write(frame.data)
                            output.flush()
                        }
                    } catch (_: IOException) {
                        session.release(socket)
                    }
                }
                try {
                    receive(session, DataInputStream(BufferedInputStream(socket.inputStream)))
                } finally {
                    writer.cancel()
                    session.release(socket)
                }
            }
        } catch (_: IOException) {
            // A closed or interrupted socket has the same user-facing outcome.
        }
        post(session, TransportEvent.Disconnected(session.id))
    }

    private suspend fun receive(session: Session, input: DataInputStream) {
        var pendingFile: IncomingFile? = null
        var stream: Pair<Long, PipedOutputStream>? = null
        try {
            while (true) {
                val type = input.readUnsignedByte()
                val id = input.readLong()
                val size = input.readInt()
                val limit = if (type == CONTROL) MAX_CONTROL_MESSAGE_BYTES else CHUNK_BYTES
                if (size !in 0..limit) throw IOException("Invalid frame size")
                if (type != CONTROL && !session.authorized) throw IOException("Pairing required")
                val bytes = ByteArray(size).also(input::readFully)
                when (type) {
                    CONTROL -> post(session, TransportEvent.BytesReceived(session.id, bytes))
                    FILE_START -> {
                        if (pendingFile != null) throw IOException("Unexpected file")
                        pendingFile = IncomingFile(id, File.createTempFile("wifi-in-", ".part", context.cacheDir))
                    }
                    FILE_DATA -> {
                        val file = pendingFile?.takeIf { it.id == id } ?: throw IOException("Unexpected file data")
                        file.write(bytes)
                    }
                    FILE_END -> {
                        val file = pendingFile?.takeIf { it.id == id } ?: throw IOException("Unexpected file end")
                        file.output.close()
                        if (!post(session, TransportEvent.FileReceived(session.id, id, Uri.fromFile(file.path)))) {
                            file.path.delete()
                        }
                        pendingFile = null
                    }
                    FILE_ABORT -> {
                        pendingFile?.takeIf { it.id == id }?.let {
                            it.discard()
                            pendingFile = null
                        }
                    }
                    STREAM_START -> {
                        stream?.second?.let(session::release)
                        val pipe = PipedInputStream(256 * 1024)
                        val output = PipedOutputStream(pipe)
                        stream = id to output
                        session.own(pipe)
                        session.own(output)
                        post(session, TransportEvent.StreamReceived(session.id, pipe))
                    }
                    STREAM_DATA -> stream?.takeIf { it.first == id }?.let { (_, output) ->
                        try {
                            output.write(bytes)
                        } catch (_: IOException) {
                            session.release(output)
                            stream = null
                        }
                    }
                    STREAM_END -> stream?.takeIf { it.first == id }?.let {
                        session.release(it.second)
                        stream = null
                    }
                    else -> throw IOException("Unknown Wi-Fi frame")
                }
            }
        } finally {
            pendingFile?.discard()
            stream?.second?.let(session::release)
        }
    }

    private class IncomingFile(val id: Long, val path: File) {
        val output: OutputStream = path.outputStream().buffered()
        private var size = 0L

        fun write(bytes: ByteArray) {
            size += bytes.size
            if (size > MAX_TRANSFER_BYTES) throw IOException("File exceeds transfer limit")
            output.write(bytes)
        }

        fun discard() {
            runCatching { output.close() }
            path.delete()
        }
    }

    private suspend fun sendChunks(
        session: Session,
        input: InputStream,
        type: Int,
        id: Long,
        progress: suspend (Long) -> Unit,
    ) {
        val buffer = ByteArray(CHUNK_BYTES)
        var count = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            session.frames.send(Frame(type, id, buffer.copyOf(read)))
            count += read
            progress(count)
        }
    }

    private suspend fun post(session: Session, event: TransportEvent): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (active !== session) return@withContext false
            emit(event)
            true
        }

    @Suppress("DEPRECATION")
    private fun currentWifi(): Wifi? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.firstNotNullOfOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return@firstNotNullOfOrNull null
            }
            manager.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address }
                ?.let { Wifi(network, it) }
        }
    }

    private fun parseIpv4(value: String): InetAddress? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            part.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }
                ?.toIntOrNull()?.takeIf { it in 0..255 }?.toByte() ?: return null
        }
        return InetAddress.getByAddress(octets.toByteArray())
    }

    private fun inSubnet(peer: InetAddress, local: LinkAddress): Boolean {
        if (peer !is Inet4Address || peer.isAnyLocalAddress || peer.isLoopbackAddress || peer.isMulticastAddress) {
            return false
        }
        return peer.address.zip(local.address.address).withIndex().all { (index, pair) ->
            val bits = (local.prefixLength - index * 8).coerceIn(0, 8)
            val mask = (0xff shl (8 - bits)) and 0xff
            ((pair.first.toInt() xor pair.second.toInt()) and mask) == 0
        }
    }

    private companion object {
        const val TIMEOUT_MS = 12_000
        const val CHUNK_BYTES = 32 * 1024
        const val CONTROL = 1
        const val FILE_START = 2
        const val FILE_DATA = 3
        const val FILE_END = 4
        const val FILE_ABORT = 5
        const val STREAM_START = 6
        const val STREAM_DATA = 7
        const val STREAM_END = 8
    }
}
