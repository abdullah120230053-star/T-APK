package com.dama.tahany.link

import android.content.Context
import android.net.Uri
import android.os.Build
import com.dama.tahany.files.MAX_TRANSFER_BYTES
import com.google.android.gms.common.api.ApiException
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
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

const val NEARBY_SERVICE_ID = "com.dama.tahany.link"

sealed interface TransportEvent {
    data class PeerFound(val endpointId: String, val name: String) : TransportEvent
    data class PeerLost(val endpointId: String) : TransportEvent
    data class ConnectionInitiated(
        val endpointId: String,
        val name: String,
        val incoming: Boolean,
        val channelToken: ByteArray,
    ) : TransportEvent

    data class Connected(val endpointId: String, val name: String) : TransportEvent
    data class Disconnected(val endpointId: String) : TransportEvent
    data class BytesReceived(val endpointId: String, val bytes: ByteArray) : TransportEvent
    data class FileReceived(
        val endpointId: String,
        val payloadId: Long,
        val uri: Uri,
    ) : TransportEvent

    data class StreamReceived(val endpointId: String, val stream: InputStream) : TransportEvent

    data class TransferProgress(
        val endpointId: String,
        val transferId: String?,
        val payloadId: Long,
        val bytesTransferred: Long,
        val totalBytes: Long,
        val status: Int,
    ) : TransportEvent

    data class Failure(val message: String, val cause: Throwable? = null) : TransportEvent
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
    private val incomingFiles = mutableMapOf<Long, Uri>()
    private var currentEndpointId: String? = null
    private var pendingEndpointId: String? = null
    private var acceptingIncoming = false
    private var session = 0

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (info.isIncomingConnection && !acceptingIncoming ||
                pendingEndpointId?.let { it != endpointId } == true ||
                currentEndpointId != null
            ) {
                client.rejectConnection(endpointId)
                return
            }
            pendingEndpointId = endpointId
            endpointNames[endpointId] = info.endpointName
            emit(
                TransportEvent.ConnectionInitiated(
                    endpointId = endpointId,
                    name = info.endpointName,
                    incoming = info.isIncomingConnection,
                    channelToken = info.rawAuthenticationToken,
                ),
            )
            // The six-digit SAS is checked after the link opens. Accepting here
            // only opens the encrypted Nearby channel; no app data is trusted yet.
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { error -> fail("Could not accept the connection", error) }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (pendingEndpointId != endpointId) return
            pendingEndpointId = null
            acceptingIncoming = false
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
            if (currentEndpointId != endpointId && pendingEndpointId != endpointId) return
            currentEndpointId = null
            pendingEndpointId = null
            clearIncomingFiles()
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
            if (endpointId != currentEndpointId && endpointId != pendingEndpointId) {
                client.cancelPayload(payload.id)
                return
            }
            when (payload.type) {
                Payload.Type.BYTES -> {
                    payload.asBytes()?.let { emit(TransportEvent.BytesReceived(endpointId, it)) }
                }

                Payload.Type.FILE -> {
                    val file = payload.asFile() ?: return
                    @Suppress("DEPRECATION")
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        file.asUri()
                    } else {
                        file.asJavaFile()?.let(Uri::fromFile)
                    }
                    if (uri == null || incomingFiles.size >= 32) {
                        client.cancelPayload(payload.id)
                        uri?.let(::deleteIncomingFile)
                    } else {
                        incomingFiles[payload.id] = uri
                    }
                }

                Payload.Type.STREAM -> payload.asStream()?.asInputStream()?.let {
                    emit(TransportEvent.StreamReceived(endpointId, it))
                }
                else -> client.cancelPayload(payload.id)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.payloadId in incomingFiles &&
                maxOf(update.totalBytes, update.bytesTransferred) > MAX_TRANSFER_BYTES
            ) {
                client.cancelPayload(update.payloadId)
                incomingFiles.remove(update.payloadId)?.let(::deleteIncomingFile)
                return
            }
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
                incomingFiles.remove(update.payloadId)?.let { uri ->
                    if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                        emit(TransportEvent.FileReceived(endpointId, update.payloadId, uri))
                    } else {
                        deleteIncomingFile(uri)
                    }
                }
            }
        }
    }

    fun advertise(endpointName: String) {
        acceptingIncoming = true
        val attempt = session
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()
        client.startAdvertising(NEARBY_SERVICE_ID, endpointName, lifecycleCallback, options)
            .addOnFailureListener { error -> if (session == attempt) fail("Could not advertise", error) }
    }

    fun discover() {
        val attempt = session
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()
        client.startDiscovery(NEARBY_SERVICE_ID, discoveryCallback, options)
            .addOnFailureListener { error -> if (session == attempt) fail("Could not discover", error) }
    }

    fun connect(endpointId: String, endpointName: String) {
        pendingEndpointId = endpointId
        val attempt = session
        val options = ConnectionOptions.Builder()
            .setLowPower(false)
            .build()
        client.requestConnection(endpointId, endpointName, lifecycleCallback, options)
            .addOnFailureListener { error -> if (session == attempt) fail("Could not request a connection", error) }
    }

    fun sendBytes(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnFailureListener { error -> fail("Could not send a control message", error) }
    }

    fun createFilePayload(file: File): Payload = Payload.fromFile(file)

    fun sendFile(endpointId: String, payload: Payload, transferId: String) {
        outgoingPayloads[payload.id] = transferId
        client.sendPayload(endpointId, payload)
            .addOnFailureListener {
                val failedId = outgoingPayloads.remove(payload.id) ?: return@addOnFailureListener
                emit(TransportEvent.TransferProgress(
                    endpointId, failedId, payload.id, 0L, 0L, PayloadTransferUpdate.Status.FAILURE,
                ))
            }
    }

    fun sendStream(endpointId: String, stream: InputStream, onFailure: () -> Unit) {
        client.sendPayload(endpointId, Payload.fromStream(stream)).addOnFailureListener {
            runCatching { stream.close() }
            onFailure()
        }
    }

    fun cancelPayload(id: Long) {
        client.cancelPayload(id)
    }

    fun stopSession() {
        session += 1
        acceptingIncoming = false
        pendingEndpointId = null
        clearIncomingFiles()
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        currentEndpointId = null
        endpointNames.clear()
        outgoingPayloads.clear()
    }

    private fun clearIncomingFiles() {
        incomingFiles.values.forEach(::deleteIncomingFile)
        incomingFiles.clear()
    }

    fun deleteIncomingFile(uri: Uri) {
        runCatching {
            if (uri.scheme == "file") uri.path?.let { File(it).delete() }
            else context.contentResolver.delete(uri, null, null)
        }
    }

    private fun fail(message: String, error: Throwable? = null) {
        if (error == null) {
            emit(TransportEvent.Failure(message))
            return
        }
        val status = (error as? ApiException)?.statusCode
        val advice = when (status) {
            8029, 8037, 8038, 8039 -> "Allow Nearby devices in Android app permissions."
            8034, 8036 -> "Allow Location in Android app permissions."
            8025 -> "Turn on Location in Android settings."
            8007 -> "Turn on Bluetooth and Wi-Fi, then try again."
            8001, 8002, 8008, 8050 -> "Close other nearby sharing sessions, then try again."
            else -> "Check Bluetooth, Wi-Fi and Nearby devices permissions."
        }
        val detail = if (status == null) message else "$message (Nearby $status)"
        emit(TransportEvent.Failure("$detail. $advice", error))
    }
}
