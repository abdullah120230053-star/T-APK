package com.dama.tahany.link

import android.content.Context
import com.dama.tahany.util.FileNames
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val NEARBY_SERVICE_ID = "com.dama.tahany.link"

sealed interface TransportEvent {
    data class PeerFound(val endpointId: String, val name: String) : TransportEvent
    data class PeerLost(val endpointId: String) : TransportEvent
    data class ConnectionInitiated(
        val endpointId: String,
        val name: String,
        val incoming: Boolean,
    ) : TransportEvent

    data class Connected(val endpointId: String, val name: String) : TransportEvent
    data class Disconnected(val endpointId: String) : TransportEvent
    data class BytesReceived(val endpointId: String, val bytes: ByteArray) : TransportEvent
    data class FileReceived(
        val endpointId: String,
        val file: File,
        val name: String,
        val size: Long,
    ) : TransportEvent

    data class TransferProgress(
        val endpointId: String,
        val transferId: String?,
        val payloadId: Long,
        val bytesTransferred: Long,
        val totalBytes: Long,
        val status: Int,
    ) : TransportEvent

    data class Failure(val message: String, val cause: Throwable? = null) : TransportEvent
    data object AdvertisingStarted : TransportEvent
    data object Discovering : TransportEvent
}

/**
 * Thin adapter over Nearby Connections. It owns no app policy: handshake,
 * pairing, and file placement live in LinkController and the service.
 */
class NearbyTransport(
    private val context: Context,
    private val emit: (TransportEvent) -> Unit,
) {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val endpointNames = ConcurrentHashMap<String, String>()
    private val outgoingPayloads = ConcurrentHashMap<Long, String>()
    private var currentEndpointId: String? = null

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            endpointNames[endpointId] = info.endpointName
            emit(
                TransportEvent.ConnectionInitiated(
                    endpointId = endpointId,
                    name = info.endpointName,
                    incoming = info.isIncomingConnection,
                ),
            )
            // The six-digit SAS is checked after the link opens. Accepting here
            // only opens the encrypted Nearby channel; no app data is trusted yet.
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { error -> fail("Could not accept the connection", error) }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                currentEndpointId = endpointId
                client.stopAdvertising()
                client.stopDiscovery()
                emit(
                    TransportEvent.Connected(
                        endpointId = endpointId,
                        name = endpointNames[endpointId] ?: "Nearby device",
                    ),
                )
            } else {
                fail("Connection failed: ${resolution.status.statusMessage ?: resolution.status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (currentEndpointId == endpointId) currentEndpointId = null
            emit(TransportEvent.Disconnected(endpointId))
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpointNames[endpointId] = info.endpointName
            emit(TransportEvent.PeerFound(endpointId, info.endpointName))
        }

        override fun onEndpointLost(endpointId: String) {
            endpointNames.remove(endpointId)
            emit(TransportEvent.PeerLost(endpointId))
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    payload.asBytes()?.let { emit(TransportEvent.BytesReceived(endpointId, it)) }
                }

                Payload.Type.FILE -> {
                    val file = payload.asFile()?.asJavaFile()
                    if (file == null) {
                        fail("Incoming file payload had no file")
                        return
                    }
                    // Nearby can delete this temp file after the callback, so
                    // move it into our own cache before doing any slow work.
                    val staged = stageIncomingFile(file, payload.asFile()?.size ?: file.length())
                    emit(
                        TransportEvent.FileReceived(
                            endpointId = endpointId,
                            file = staged,
                            name = file.name,
                            size = staged.length(),
                        ),
                    )
                }

                else -> fail("Unsupported payload type ${payload.type}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            emit(
                TransportEvent.TransferProgress(
                    endpointId = endpointId,
                    transferId = outgoingPayloads[update.payloadId],
                    payloadId = update.payloadId,
                    bytesTransferred = update.bytesTransferred,
                    totalBytes = update.totalBytes,
                    status = update.status,
                ),
            )
            if (update.status == PayloadTransferUpdate.Status.SUCCESS ||
                update.status == PayloadTransferUpdate.Status.FAILURE ||
                update.status == PayloadTransferUpdate.Status.CANCELED
            ) {
                outgoingPayloads.remove(update.payloadId)
            }
        }
    }

    fun advertise(endpointName: String) {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()
        client.startAdvertising(NEARBY_SERVICE_ID, endpointName, lifecycleCallback, options)
            .addOnSuccessListener { emit(TransportEvent.AdvertisingStarted) }
            .addOnFailureListener { error -> fail("Could not advertise", error) }
    }

    fun discover() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()
        client.startDiscovery(NEARBY_SERVICE_ID, discoveryCallback, options)
            .addOnSuccessListener { emit(TransportEvent.Discovering) }
            .addOnFailureListener { error -> fail("Could not discover", error) }
    }

    fun connect(endpointId: String, endpointName: String) {
        val options = ConnectionOptions.Builder()
            .setLowPower(false)
            .build()
        client.requestConnection(endpointId, endpointName, lifecycleCallback, options)
            .addOnFailureListener { error -> fail("Could not request a connection", error) }
    }

    fun sendBytes(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnFailureListener { error -> fail("Could not send a control message", error) }
    }

    /**
     * Returns the Nearby payload id, or null when the payload could not be
     * created. Transfer updates refer to this id, not to our transfer id.
     */
    fun sendFile(endpointId: String, file: File, transferId: String): Long? {
        val payload = try {
            Payload.fromFile(file)
        } catch (error: Exception) {
            fail("Could not read ${file.name}", error)
            return null
        }
        outgoingPayloads[payload.id] = transferId
        client.sendPayload(endpointId, payload)
            .addOnFailureListener { error ->
                outgoingPayloads.remove(payload.id)
                fail("Could not send ${file.name}", error)
            }
        return payload.id
    }

    fun disconnect(endpointId: String? = currentEndpointId) {
        if (endpointId != null) {
            client.disconnectFromEndpoint(endpointId)
        }
        currentEndpointId = null
    }

    fun stopSession() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        currentEndpointId = null
        endpointNames.clear()
        outgoingPayloads.clear()
    }

    private fun stageIncomingFile(source: File, size: Long): File {
        val directory = File(context.cacheDir, "nearby-incoming").apply { mkdirs() }
        val safeName = FileNames.sanitize(source.name)
        val staged = File(directory, "${UUID.randomUUID()}-$safeName")
        source.copyTo(staged, overwrite = true)
        if (size > 0 && staged.length() != size) {
            // A short copy is not a complete payload; keep the file so the
            // receiver can report a checksum/size failure instead of silently
            // presenting a truncated image.
            fail("Incoming ${source.name} was ${staged.length()} of $size bytes")
        }
        return staged
    }

    private fun fail(message: String, error: Throwable? = null) {
        emit(TransportEvent.Failure(message, error))
    }
}
