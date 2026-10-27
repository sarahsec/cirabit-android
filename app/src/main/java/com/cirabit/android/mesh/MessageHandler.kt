package com.cirabit.android.mesh

import android.util.Log
import com.cirabit.android.favorites.FavoriteControlMessage
import com.cirabit.android.model.CirabitMessage
import com.cirabit.android.model.IdentityAnnouncement
import com.cirabit.android.model.MessageReaction
import com.cirabit.android.model.CirabitMessageType
import com.cirabit.android.model.AuthenticatedPeerState
import com.cirabit.android.model.RoutedPacket
import com.cirabit.android.protocol.CirabitPacket
import com.cirabit.android.protocol.MessageType
import com.cirabit.android.protocol.MessageReactionCodec
import com.cirabit.android.sync.FileMessageIdUtil
import com.cirabit.android.sync.PacketIdUtil
import com.cirabit.android.util.toHexString
import com.cirabit.android.features.voice.LiveVoiceManager
import com.cirabit.android.features.voice.LiveVoiceScope
import kotlinx.coroutines.*
import java.util.*

sealed class AnnounceHandlingResult {
    data class Accepted(val isFirst: Boolean) : AnnounceHandlingResult()
    object Rejected : AnnounceHandlingResult()
}

/**
 * Handles processing of different message types
 * Extracted from BluetoothMeshService for better separation of concerns
 */
class MessageHandler(private val myPeerID: String, private val appContext: android.content.Context) {
    
    companion object {
        private const val TAG = "MessageHandler"
        private const val ANNOUNCE_CLOCK_SKEW_TOLERANCE_MS = 10 * 60 * 1000L
        private const val MAX_CONSECUTIVE_DECRYPT_FAILURES = 3
    }

    // Delegate for callbacks
    var delegate: MessageHandlerDelegate? = null

    // Reference to PacketProcessor for recursive packet handling
    var packetProcessor: PacketProcessor? = null

    // Coroutines
    private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Consecutive decrypt failures per peer; only signature-verified packets reach this path,
    // so repeated failures mean the established session is stale (peer re-handshaked elsewhere).
    private val consecutiveDecryptFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Handle Noise encrypted transport message - SIMPLIFIED iOS-compatible version
     * Uses NoisePayloadType system exactly like iOS SimplifiedBluetoothService
     *
     * Returns false when the payload could not be decrypted, so callers can treat the
     * packet as not liveness-proving (no lastSeen refresh, no relay).
     */
    suspend fun handleNoiseEncrypted(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        // Skip our own messages
        if (peerID == myPeerID) return true

        // Check if this message is for us
        val recipientID = packet.recipientID?.toHexString()
        if (recipientID != myPeerID) {
            return true
        }

        try {
            // Decrypt the message using the Noise service
            val decryption = delegate?.decryptFromPeer(packet.payload, peerID)
            if (decryption == null) {
                Log.w(TAG, "Failed to decrypt Noise message from $peerID - may need handshake")
                registerDecryptFailure(peerID)
                return false
            }
            consecutiveDecryptFailures.remove(peerID)
            val decryptedData = decryption.plaintext

            if (decryptedData.isEmpty()) {
                Log.w(TAG, "Decrypted data is empty from $peerID")
                return true
            }

            val noisePayload = com.cirabit.android.model.NoisePayload.decode(decryptedData)
            if (noisePayload == null) {
                Log.w(TAG, "Failed to parse NoisePayload from $peerID")
                return true
            }
            
            when (noisePayload.type) {
                com.cirabit.android.model.NoisePayloadType.PRIVATE_MESSAGE -> {
                    // Decode TLV private message exactly like iOS
                    val privateMessage = com.cirabit.android.model.PrivateMessagePacket.decode(noisePayload.data)
                    if (privateMessage != null) {
                        // Handle favorite/unfavorite notifications embedded as PMs
                        val pmContent = privateMessage.content
                        if (FavoriteControlMessage.parse(pmContent) != null) {
                            handleFavoriteNotificationFromMesh(pmContent, peerID)
                            // Acknowledge delivery for UX parity
                            sendDeliveryAck(privateMessage.messageID, peerID)
                            return true
                        }
                        
                        // Create CirabitMessage - preserve source packet timestamp
                        val message = CirabitMessage(
                            id = privateMessage.messageID,
                            sender = delegate?.getPeerNickname(peerID) ?: "Unknown",
                            content = privateMessage.content,
                            timestamp = java.util.Date(packet.timestamp.toLong()),
                            isRelay = false,
                            originalSender = null,
                            isPrivate = true,
                            recipientNickname = delegate?.getMyNickname(),
                            senderPeerID = peerID,
                            mentions = null
                        )
                        
                        // Notify delegate
                        delegate?.onMessageReceived(message)
                        
                        // Send delivery ACK exactly like iOS
                        sendDeliveryAck(privateMessage.messageID, peerID)
                    }
                }
                
                com.cirabit.android.model.NoisePayloadType.FILE_TRANSFER -> {
                    if (isOversizedIncomingFilePayload(peerID, noisePayload.data.size, "noise")) {
                        return true
                    }
                    // Handle encrypted file transfer with deterministic message ID.
                    val file = com.cirabit.android.model.CirabitFilePacket.decode(noisePayload.data)
                    if (file != null) {
                        if (isOversizedIncomingFile(peerID, file.fileSize, file.content.size.toLong(), "noise")) {
                            return true
                        }
                        val stableMessageID = FileMessageIdUtil.computeIdHex(
                            senderPeerID = peerID,
                            timestampMs = packet.timestamp.toLong(),
                            encodedFilePayload = noisePayload.data
                        )
                        Log.d(TAG, "Encrypted file from $peerID: ${file.fileSize} bytes")
                        val savedPath = com.cirabit.android.features.file.FileUtils.saveIncomingFile(appContext, file)
                        val message = CirabitMessage(
                            id = stableMessageID,
                            sender = delegate?.getPeerNickname(peerID) ?: "Unknown",
                            content = savedPath,
                            type = com.cirabit.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                            timestamp = java.util.Date(packet.timestamp.toLong()),
                            isRelay = false,
                            isPrivate = true,
                            recipientNickname = delegate?.getMyNickname(),
                            senderPeerID = peerID
                        )

                        Log.d(TAG, "📄 Saved encrypted incoming file to $savedPath (msgId=$stableMessageID)")
                        if (!LiveVoiceManager.getInstance(appContext).absorbFinalizedVoiceNote(message)) {
                            delegate?.onMessageReceived(message)
                        }

                        // Send delivery ACK with deterministic message ID
                        sendDeliveryAck(stableMessageID, peerID)
                    } else {
                        Log.w(TAG, "Failed to decode encrypted file transfer from $peerID")
                    }
                }

                com.cirabit.android.model.NoisePayloadType.VOICE_FRAME -> {
                    return LiveVoiceManager.getInstance(appContext).handleFrame(
                        peerID = peerID,
                        nickname = delegate?.getPeerNickname(peerID) ?: peerID,
                        scope = LiveVoiceScope.DIRECT_MESSAGE,
                        payload = noisePayload.data,
                        timestampMs = packet.timestamp.toLong()
                    )
                }

                com.cirabit.android.model.NoisePayloadType.PEER_STATE -> {
                    val authenticatedState = AuthenticatedPeerState.decode(noisePayload.data)
                    if (authenticatedState == null) {
                        Log.w(TAG, "Dropping malformed authenticated peer state from ${peerID.take(8)}")
                    } else {
                        delegate?.onAuthenticatedPeerStateReceived(
                            peerID,
                            authenticatedState,
                            decryption.authenticatedSession
                        )
                    }
                }
                
                com.cirabit.android.model.NoisePayloadType.DELIVERED -> {
                    // Handle delivery ACK exactly like iOS
                    val messageID = String(noisePayload.data, Charsets.UTF_8)
                    Log.d(TAG, "Delivery ACK from $peerID for $messageID")
                    
                    // Simplified: Call delegate with messageID and peerID directly
                    delegate?.onDeliveryAckReceived(messageID, peerID)
                }
                
                com.cirabit.android.model.NoisePayloadType.READ_RECEIPT -> {
                    // Handle read receipt exactly like iOS
                    val messageID = String(noisePayload.data, Charsets.UTF_8)
                    Log.d(TAG, "Read receipt from $peerID for $messageID")
                    
                    // Simplified: Call delegate with messageID and peerID directly
                    delegate?.onReadReceiptReceived(messageID, peerID)
                }
                com.cirabit.android.model.NoisePayloadType.REACTION -> {
                    val reaction = MessageReactionCodec.decode(noisePayload.data)
                    if (reaction == null) {
                        Log.w(TAG, "Failed to decode encrypted reaction from $peerID")
                        return true
                    }

                    // Enforce sender identity from packet route information
                    val sanitized = reaction.copy(reactorPeerID = peerID)
                    delegate?.onMessageReactionReceived(
                        reaction = sanitized,
                        fromPeerID = peerID,
                        timestampMs = packet.timestamp.toLong()
                    )
                }
                com.cirabit.android.model.NoisePayloadType.VERIFY_CHALLENGE -> {
                    delegate?.onVerifyChallengeReceived(peerID, noisePayload.data, packet.timestamp.toLong())
                }
                com.cirabit.android.model.NoisePayloadType.VERIFY_RESPONSE -> {
                    delegate?.onVerifyResponseReceived(peerID, noisePayload.data, packet.timestamp.toLong())
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing Noise encrypted message from $peerID: ${e.message}")
        }
        return true
    }

    /**
     * Count consecutive decrypt failures from a signature-verified peer that we still hold an
     * established session for. After repeated failures the session is stale (the peer completed
     * a new handshake elsewhere), so destroy it and start a fresh handshake; the peer's side
     * will finish via the responder-candidate path and evict its own stale session.
     */
    private fun registerDecryptFailure(peerID: String) {
        if (peerID == "unknown" || peerID == myPeerID) return
        if (delegate?.hasNoiseSession(peerID) != true) return
        val failures = (consecutiveDecryptFailures[peerID] ?: 0) + 1
        if (failures >= MAX_CONSECUTIVE_DECRYPT_FAILURES) {
            consecutiveDecryptFailures.remove(peerID)
            Log.w(TAG, "Noise session with $peerID stale after $failures decrypt failures; resetting and re-handshaking")
            try { delegate?.removeNoiseSession(peerID) } catch (e: Exception) {
                Log.w(TAG, "Failed to reset Noise session for $peerID: ${e.message}")
            }
            try { delegate?.initiateNoiseHandshake(peerID) } catch (e: Exception) {
                Log.w(TAG, "Failed to re-initiate handshake with $peerID: ${e.message}")
            }
        } else {
            consecutiveDecryptFailures[peerID] = failures
        }
    }
    
    /**
     * Send delivery ACK for a received private message - exactly like iOS
     */
    private suspend fun sendDeliveryAck(messageID: String, senderPeerID: String) {
        try {
            // Create ACK payload: [type byte] + [message ID] - exactly like iOS
            val ackPayload = com.cirabit.android.model.NoisePayload(
                type = com.cirabit.android.model.NoisePayloadType.DELIVERED,
                data = messageID.toByteArray(Charsets.UTF_8)
            )
            
            // Encrypt the payload
            val encryptedPayload = delegate?.encryptForPeer(ackPayload.encode(), senderPeerID)
            if (encryptedPayload == null) {
                Log.w(TAG, "Failed to encrypt delivery ACK for $senderPeerID")
                return
            }
            
            // Create NOISE_ENCRYPTED packet exactly like iOS
                val packet = CirabitPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(senderPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = encryptedPayload,
                    signature = null,
                    ttl = com.cirabit.android.util.AppConstants.MESSAGE_TTL_HOPS // Same TTL as iOS messageTTL
                )
            
            delegate?.sendPacket(packet)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send delivery ACK to $senderPeerID: ${e.message}")
        }
    }
    
    /**
     * Handle announce message with TLV decoding and signature verification - exactly like iOS
     */
    suspend fun handleAnnounce(routed: RoutedPacket): Boolean {
        return (handleAnnounceWithResult(routed) as? AnnounceHandlingResult.Accepted)?.isFirst ?: false
    }

    suspend fun handleAnnounceWithResult(routed: RoutedPacket): AnnounceHandlingResult {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        if (peerID == myPeerID) return AnnounceHandlingResult.Rejected

        // Peers use wall-clock packet timestamps; tolerate moderate device clock skew
        // during identity learning, or later signed messages cannot be verified.
        val now = System.currentTimeMillis()
        val clockSkewMs = kotlin.math.abs(now - packet.timestamp.toLong())
        if (clockSkewMs > ANNOUNCE_CLOCK_SKEW_TOLERANCE_MS) {
            Log.w(TAG, "Ignoring ANNOUNCE from ${peerID.take(8)} with excessive clock skew (${clockSkewMs}ms > ${ANNOUNCE_CLOCK_SKEW_TOLERANCE_MS}ms)")
            return AnnounceHandlingResult.Rejected
        }
        
        val announcement = AnnouncementIdentityValidator.verify(packet, peerID) { signature, data, key ->
            delegate?.verifyEd25519Signature(signature, data, key) ?: false
        }
        if (announcement == null) {
            Log.w(TAG, "Rejecting malformed, unbound, or invalidly signed ANNOUNCE from ${peerID.take(8)}")
            return AnnounceHandlingResult.Rejected
        }

        val persistedSigningKey = delegate?.getAuthenticatedSigningKey(announcement.noisePublicKey)
        if (persistedSigningKey != null &&
            !persistedSigningKey.contentEquals(announcement.signingPublicKey)
        ) {
            Log.w(TAG, "Rejecting ANNOUNCE Ed key that conflicts with authenticated peer state")
            return AnnounceHandlingResult.Rejected
        }

        var verified = true

        // Check for existing peer with different noise public key
        // If existing peer has a different noise public key, do not consider this verified
        val existingPeer = delegate?.getPeerInfo(peerID)
        
        if (existingPeer != null && existingPeer.noisePublicKey != null && !existingPeer.noisePublicKey!!.contentEquals(announcement.noisePublicKey)) {
            Log.w(TAG, "Announce key mismatch for ${peerID.take(8)} - keeping unverified")
            verified = false
        }

        if (
            existingPeer?.signingPublicKey != null &&
            !existingPeer.signingPublicKey!!.contentEquals(announcement.signingPublicKey)
        ) {
            Log.w(
                TAG,
                "Rejecting signing-key replacement for ${peerID.take(8)} without authenticated peer-state proof"
            )
            verified = false
        }

        // Require verified announce; ignore otherwise (no backward compatibility)
        if (!verified) {
            return AnnounceHandlingResult.Rejected
        }
        
        // Extract nickname and public keys from TLV data
        val nickname = announcement.nickname
        val noisePublicKey = announcement.noisePublicKey
        val signingPublicKey = announcement.signingPublicKey
        
        // Update peer info with verification status through new method
        val isFirstAnnounce = delegate?.updatePeerInfoFromVerifiedAnnouncement(
            peerID = peerID,
            nickname = nickname,
            noisePublicKey = noisePublicKey,
            signingPublicKey = signingPublicKey,
            isVerified = true,
            capabilities = announcement.capabilities
        ) ?: false

        // Update mesh graph from gossip neighbors (only if TLV present)
        try {
            val neighborsOrNull = com.cirabit.android.services.meshgraph.GossipTLV.decodeNeighborsFromAnnouncementPayload(packet.payload)
            com.cirabit.android.services.meshgraph.MeshGraphService.getInstance()
                .updateFromAnnouncement(peerID, nickname, neighborsOrNull, packet.timestamp)
        } catch (_: Exception) { }

        Log.d(TAG, "Verified announce from $peerID (${announcement.nickname})")
        return AnnounceHandlingResult.Accepted(isFirstAnnounce)
    }
    
    /**
     * Handle Noise handshake - SIMPLIFIED iOS-compatible version
     * Single handshake type (0x10) with response determined by payload analysis
     */
    suspend fun handleNoiseHandshake(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        // Skip our own handshake messages
        if (peerID == myPeerID) return
        
        // Check if handshake is addressed to us
        val recipientID = packet.recipientID?.toHexString()
        if (recipientID != myPeerID) {
            return
        }
        
        try {
            // Process handshake message through delegate (simplified approach)
            val response = delegate?.processNoiseHandshakeMessage(packet.payload, peerID)
            
            if (response != null) {
                // Send response using same packet type (simplified iOS approach)
                val responsePacket = CirabitPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = response,
                    signature = null,
                    ttl = com.cirabit.android.util.AppConstants.MESSAGE_TTL_HOPS // Same TTL as iOS
                )
                
                delegate?.sendPacket(responsePacket)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process Noise handshake from $peerID: ${e.message}")
        }
    }
    
    /**
     * Handle broadcast or private message
     */
    suspend fun handleMessage(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        if (peerID == myPeerID) return
        val senderNickname = delegate?.getPeerNickname(peerID)
        if (senderNickname != null) {
            Log.d(TAG, "Received message from $senderNickname")
            delegate?.updatePeerNickname(peerID, senderNickname)
        }
        
        val recipientID = packet.recipientID?.takeIf { !it.contentEquals(delegate?.getBroadcastRecipient()) }
        
        if (recipientID == null) {
            // BROADCAST MESSAGE
            handleBroadcastMessage(routed)
        } else if (recipientID.toHexString() == myPeerID) {
            // PRIVATE MESSAGE FOR US
            handlePrivateMessage(packet, peerID)
        }
        // Message relay is now handled by centralized PacketRelayManager
    }

    /**
     * Handle public emoji reaction packet.
     */
    suspend fun handleMessageReaction(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        if (peerID == myPeerID) return

        val recipientID = packet.recipientID?.takeIf { !it.contentEquals(delegate?.getBroadcastRecipient()) }
        if (recipientID != null && recipientID.toHexString() != myPeerID) {
            return
        }

        if (recipientID == null) {
            val peerInfo = delegate?.getPeerInfo(peerID)
            if (peerInfo == null || !peerInfo.isVerifiedNickname) {
                Log.w(TAG, "🚫 Dropping reaction from unverified or unknown peer ${peerID.take(8)}...")
                return
            }
        }

        val reaction = MessageReactionCodec.decode(packet.payload)
        if (reaction == null) {
            Log.w(TAG, "Failed to decode reaction payload from $peerID")
            return
        }

        // Enforce sender identity from packet metadata.
        val sanitized = reaction.copy(reactorPeerID = peerID)
        delegate?.onMessageReactionReceived(
            reaction = sanitized,
            fromPeerID = peerID,
            timestampMs = packet.timestamp.toLong()
        )
    }

    /** Validate and ingest an ephemeral public push-to-talk frame. */
    fun handlePublicVoiceFrame(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        val peerID = routed.peerID ?: return false
        if (peerID == myPeerID) return true
        val recipient = packet.recipientID
        if (recipient != null && !recipient.contentEquals(delegate?.getBroadcastRecipient())) return false
        if (packet.timestamp > Long.MAX_VALUE.toULong()) return false
        val ageMs = System.currentTimeMillis() - packet.timestamp.toLong()
        if (ageMs !in -30_000L..30_000L) return false
        val peerInfo = delegate?.getPeerInfo(peerID)
        if (peerInfo == null || !peerInfo.isVerifiedNickname) return false
        return LiveVoiceManager.getInstance(appContext).handleFrame(
            peerID = peerID,
            nickname = delegate?.getPeerNickname(peerID) ?: peerID,
            scope = LiveVoiceScope.PUBLIC_MESH,
            payload = packet.payload,
            timestampMs = packet.timestamp.toLong()
        )
    }
    
    /**
     * Handle broadcast message with verification enforcement
     */
    private suspend fun handleBroadcastMessage(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        val stableMessageID = PacketIdUtil.computeIdHex(packet)
        
        // Enforce: only accept public messages from verified peers we know
        val peerInfo = delegate?.getPeerInfo(peerID)
        if (peerInfo == null || !peerInfo.isVerifiedNickname) {
            Log.w(TAG, "Dropping public message from unverified peer ${peerID.take(8)}")
            return
        }
        
        try {
            // Try file packet first (voice, image, etc.) and log outcome for FILE_TRANSFER
            val isFileTransfer = com.cirabit.android.protocol.MessageType.fromValue(packet.type) == com.cirabit.android.protocol.MessageType.FILE_TRANSFER
            if (isFileTransfer && isOversizedIncomingFilePayload(peerID, packet.payload.size, "broadcast")) {
                return
            }
            val file = com.cirabit.android.model.CirabitFilePacket.decode(packet.payload)
            if (file != null) {
                val stableFileMessageID = FileMessageIdUtil.computeIdHex(
                    senderPeerID = peerID,
                    timestampMs = packet.timestamp.toLong(),
                    encodedFilePayload = packet.payload
                )
                if (isOversizedIncomingFile(peerID, file.fileSize, file.content.size.toLong(), "broadcast")) {
                    return
                }
                if (isFileTransfer) {
                    Log.d(TAG, "📥 FILE_TRANSFER decode success (broadcast): name='${file.fileName}', size=${file.fileSize}, mime='${file.mimeType}', from=${peerID.take(8)}")
                }
                val savedPath = com.cirabit.android.features.file.FileUtils.saveIncomingFile(appContext, file)
                val message = CirabitMessage(
                    id = stableFileMessageID,
                    sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                    content = savedPath,
                    type = com.cirabit.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                    senderPeerID = peerID,
                    timestamp = Date(packet.timestamp.toLong())
                )
                if (!LiveVoiceManager.getInstance(appContext).absorbFinalizedVoiceNote(message)) {
                    delegate?.onMessageReceived(message)
                }
                return
            } else if (isFileTransfer) {
                Log.w(TAG, "FILE_TRANSFER decode failed (broadcast) from ${peerID.take(8)}")
            }

            // Fallback: plain text
            val message = CirabitMessage(
                id = stableMessageID,
                sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                content = String(packet.payload, Charsets.UTF_8),
                senderPeerID = peerID,
                timestamp = Date(packet.timestamp.toLong())
            )
            delegate?.onMessageReceived(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process broadcast message: ${e.message}")
        }
    }
    
    /**
     * Handle (decrypted) private message addressed to us
     */
    private suspend fun handlePrivateMessage(packet: CirabitPacket, peerID: String) {
        try {
            val stableMessageID = PacketIdUtil.computeIdHex(packet)
            val isFileTransfer = com.cirabit.android.protocol.MessageType.fromValue(packet.type) ==
                com.cirabit.android.protocol.MessageType.FILE_TRANSFER
            val signatureIsValid = packet.signature != null &&
                delegate?.verifySignature(packet, peerID) == true

            // Migration fallback is visible to relays, so sender authenticity
            // is mandatory. Never accept an unsigned directed raw file.
            if (isFileTransfer && !signatureIsValid) {
                Log.w(TAG, "Unsigned or invalid signed private file from $peerID")
                return
            }

            // Preserve prior behavior for other directed packet types: verify
            // a signature whenever one is present.
            if (!isFileTransfer && packet.signature != null && !signatureIsValid) {
                Log.w(TAG, "Invalid signature for private message from $peerID")
                return
            }

            // Try file packet first (voice, image, etc.) and log outcome for FILE_TRANSFER
            if (isFileTransfer && isOversizedIncomingFilePayload(peerID, packet.payload.size, "private")) {
                return
            }
            val file = com.cirabit.android.model.CirabitFilePacket.decode(packet.payload)
            if (file != null) {
                val stableFileMessageID = FileMessageIdUtil.computeIdHex(
                    senderPeerID = peerID,
                    timestampMs = packet.timestamp.toLong(),
                    encodedFilePayload = packet.payload
                )
                if (isOversizedIncomingFile(peerID, file.fileSize, file.content.size.toLong(), "private")) {
                    return
                }
                if (isFileTransfer) {
                    Log.d(TAG, "📥 FILE_TRANSFER decode success (private): name='${file.fileName}', size=${file.fileSize}, mime='${file.mimeType}', from=${peerID.take(8)}")
                }
                val savedPath = com.cirabit.android.features.file.FileUtils.saveIncomingFile(appContext, file)
                val message = CirabitMessage(
                    id = stableFileMessageID,
                    sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                    content = savedPath,
                    type = com.cirabit.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                    senderPeerID = peerID,
                    timestamp = Date(packet.timestamp.toLong()),
                    isPrivate = true,
                    recipientNickname = delegate?.getMyNickname()
                )
                Log.d(TAG, "📄 Saved incoming file to $savedPath")
                if (!LiveVoiceManager.getInstance(appContext).absorbFinalizedVoiceNote(message)) {
                    delegate?.onMessageReceived(message)
                }
                return
            } else if (isFileTransfer) {
                Log.w(TAG, "⚠️ FILE_TRANSFER decode failed (private) from ${peerID.take(8)} payloadSize=${packet.payload.size}")
            }

            // Fallback: plain text
            val message = CirabitMessage(
                id = stableMessageID,
                sender = delegate?.getPeerNickname(peerID) ?: "unknown",
                content = String(packet.payload, Charsets.UTF_8),
                senderPeerID = peerID,
                timestamp = Date(packet.timestamp.toLong())
            )
            delegate?.onMessageReceived(message)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process private message from $peerID: ${e.message}")
        }
    }

    
    
    /**
     * Handle leave message
     */
    suspend fun handleLeave(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        val content = String(packet.payload, Charsets.UTF_8)
        
        if (content.startsWith("#")) {
            // Channel leave
            delegate?.onChannelLeave(content, peerID)
        } else {
            // Peer disconnect
            delegate?.removePeer(peerID)
        }
        
        // Leave message relay is now handled by centralized PacketRelayManager
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Message Handler Debug Info ===")
            appendLine("Handler Scope Active: ${handlerScope.isActive}")
            appendLine("My Peer ID: $myPeerID")
        }
    }
    
    /**
     * Convert hex string peer ID to binary data (8 bytes) - same as iOS implementation
     */
    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 } // Initialize with zeros, exactly 8 bytes
        var tempID = hexString
        var index = 0
        
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                result[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }
        
        return result
    }

    /**
     * Shutdown the handler
     */
    fun shutdown() {
        handlerScope.cancel()
    }

    /**
     * Handle favorite/unfavorite notification received over mesh as a private message.
     * Content format: "[FAVORITED]:npub..." or "[UNFAVORITED]:npub..."
     */
    private fun handleFavoriteNotificationFromMesh(content: String, fromPeerID: String) {
        try {
            val control = FavoriteControlMessage.parse(content) ?: return

            val peerInfo = delegate?.getPeerInfo(fromPeerID)
            val noiseKey = peerInfo?.noisePublicKey
            if (noiseKey != null) {
                com.cirabit.android.favorites.FavoritesPersistenceService.shared.updatePeerFavoritedUs(noiseKey, control.isFavorite)
                if (control.npub != null) {
                    com.cirabit.android.favorites.FavoritesPersistenceService.shared.updateNostrPublicKey(noiseKey, control.npub)
                    com.cirabit.android.favorites.FavoritesPersistenceService.shared.updateNostrPublicKeyForPeerID(fromPeerID, control.npub)
                }

                val rel = com.cirabit.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                val guidance = if (control.isFavorite) {
                    if (rel?.isFavorite == true) {
                        " — mutual! You can continue DMs via Nostr when out of mesh."
                    } else {
                        " — favorite back to continue DMs later."
                    }
                } else {
                    ". DMs over Nostr will pause unless you both favorite again."
                }

                val action = if (control.isFavorite) "favorited" else "unfavorited"
                val notice = "${peerInfo.nickname} $action you$guidance"
                val sys = com.cirabit.android.model.CirabitMessage(
                    sender = "system",
                    content = notice,
                    timestamp = java.util.Date(),
                    isRelay = false
                )
                delegate?.onMessageReceived(sys)

                // Mirror the notice into the private conversation so it's visible while chatting
                try {
                    val conversationID = com.cirabit.android.services.ContactDirectory
                        .canonicalConversationId(fromPeerID)
                    val sysPrivate = com.cirabit.android.model.CirabitMessage(
                        sender = "system",
                        content = notice,
                        timestamp = java.util.Date(),
                        isRelay = false,
                        isPrivate = true,
                        senderPeerID = conversationID
                    )
                    delegate?.onMessageReceived(sysPrivate)
                } catch (_: Exception) {
                    // Best-effort; public notice already delivered
                }
            }
        } catch (_: Exception) {
            // Best-effort; ignore errors
        }
    }

    private fun isOversizedIncomingFilePayload(peerID: String, payloadSizeBytes: Int, channel: String): Boolean {
        val limit = com.cirabit.android.util.AppConstants.Media.MAX_INCOMING_FILE_BYTES +
                com.cirabit.android.util.AppConstants.Media.MAX_FILE_PACKET_OVERHEAD_BYTES
        return if (payloadSizeBytes.toLong() > limit) {
            Log.w(
                TAG,
                "🚫 Dropping oversized file payload ($channel) from ${peerID.take(8)}... payload=$payloadSizeBytes bytes limit=$limit"
            )
            true
        } else {
            false
        }
    }

    private fun isOversizedIncomingFile(
        peerID: String,
        declaredFileSizeBytes: Long,
        contentSizeBytes: Long,
        channel: String
    ): Boolean {
        val limit = com.cirabit.android.util.AppConstants.Media.MAX_INCOMING_FILE_BYTES
        return if (declaredFileSizeBytes > limit || contentSizeBytes > limit) {
            Log.w(
                TAG,
                "🚫 Dropping oversized decoded file ($channel) from ${peerID.take(8)}... declared=$declaredFileSizeBytes content=$contentSizeBytes limit=$limit"
            )
            true
        } else {
            false
        }
    }
}

/**
 * Delegate interface for message handler callbacks
 */
interface MessageHandlerDelegate {
    // Peer management
    fun addOrUpdatePeer(peerID: String, nickname: String): Boolean
    fun removePeer(peerID: String)
    fun updatePeerNickname(peerID: String, nickname: String)
    fun getPeerNickname(peerID: String): String?
    fun getNetworkSize(): Int
    fun getMyNickname(): String?
    fun getPeerInfo(peerID: String): PeerInfo?
    fun updatePeerInfoFromVerifiedAnnouncement(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean,
        capabilities: com.cirabit.android.model.PeerCapabilities? = null
    ): Boolean
    
    // Packet operations
    fun sendPacket(packet: CirabitPacket)
    fun relayPacket(routed: RoutedPacket)
    fun getBroadcastRecipient(): ByteArray
    
    // Cryptographic operations
    fun verifySignature(packet: CirabitPacket, peerID: String): Boolean
    fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray?
    fun decryptFromPeer(
        encryptedData: ByteArray,
        senderPeerID: String
    ): com.cirabit.android.noise.NoiseDecryptionResult?
    fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean
    fun getAuthenticatedSigningKey(noisePublicKey: ByteArray): ByteArray? = null
    
    // Noise protocol operations
    fun hasNoiseSession(peerID: String): Boolean
    fun initiateNoiseHandshake(peerID: String)
    fun removeNoiseSession(peerID: String) {}
    fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray?
    fun onAuthenticatedPeerStateReceived(
        peerID: String,
        state: AuthenticatedPeerState,
        authenticatedSession: com.cirabit.android.noise.AuthenticatedNoiseSession
    ) {}
    
    // Message operations
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?

    // Callbacks
    fun onMessageReceived(message: CirabitMessage)
    fun onMessageReactionReceived(reaction: MessageReaction, fromPeerID: String, timestampMs: Long) {}
    fun onChannelLeave(channel: String, fromPeer: String)
    fun onDeliveryAckReceived(messageID: String, peerID: String)
    fun onReadReceiptReceived(messageID: String, peerID: String)
    fun onVerifyChallengeReceived(peerID: String, payload: ByteArray, timestampMs: Long)
    fun onVerifyResponseReceived(peerID: String, payload: ByteArray, timestampMs: Long)
}
