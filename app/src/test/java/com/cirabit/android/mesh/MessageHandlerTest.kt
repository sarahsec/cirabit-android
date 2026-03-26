package com.cirabit.android.mesh

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.cirabit.android.model.CirabitFilePacket
import com.cirabit.android.model.CirabitMessage
import com.cirabit.android.model.MessageReaction
import com.cirabit.android.model.NoisePayload
import com.cirabit.android.model.NoisePayloadType
import com.cirabit.android.model.RoutedPacket
import com.cirabit.android.protocol.CirabitPacket
import com.cirabit.android.protocol.MessageReactionCodec
import com.cirabit.android.protocol.MessageType
import com.cirabit.android.ui.ChatViewModel
import com.cirabit.android.ui.MediaSendingManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class MessageHandlerTest {

    @Test
    fun stableFileIdTest_senderAndReceiverComputeSameId() = runBlocking {
        val myPeerID = "0011223344556677"
        val senderPeerID = "8899aabbccddeeff"
        val timestampMs = 1_730_000_000_000L
        val filePacket = CirabitFilePacket(
            fileName = "photo.jpg",
            fileSize = 4,
            mimeType = "image/jpeg",
            content = byteArrayOf(1, 2, 3, 4)
        )
        val filePayload = filePacket.encode()
        assertNotNull(filePayload)

        val stableId = MediaSendingManager.computeStableFileMessageID(
            senderPeerID = senderPeerID,
            timestampMs = timestampMs,
            encodedFilePayload = filePayload!!
        )

        val noisePayload = NoisePayload(
            type = NoisePayloadType.FILE_TRANSFER,
            data = filePayload
        ).encode()

        val routedPacket = RoutedPacket(
            packet = CirabitPacket(
                version = 1u,
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = hexToByteArray(senderPeerID),
                recipientID = hexToByteArray(myPeerID),
                timestamp = timestampMs.toULong(),
                payload = byteArrayOf(0x01),
                signature = null,
                ttl = 7u
            ),
            peerID = senderPeerID
        )

        val delegate = mock<MessageHandlerDelegate>()
        val received = mutableListOf<CirabitMessage>()
        whenever(delegate.decryptFromPeer(any(), eq(senderPeerID))).thenReturn(noisePayload)
        whenever(delegate.getPeerNickname(senderPeerID)).thenReturn("alice")
        whenever(delegate.getMyNickname()).thenReturn("me")
        doAnswer {
            received += it.getArgument<CirabitMessage>(0)
            null
        }.whenever(delegate).onMessageReceived(any())

        val handler = MessageHandler(myPeerID, ApplicationProvider.getApplicationContext())
        handler.delegate = delegate

        handler.handleNoiseEncrypted(routedPacket)
        handler.handleNoiseEncrypted(routedPacket)

        assertEquals(2, received.size)
        assertEquals(stableId, received[0].id)
        assertEquals(stableId, received[1].id)
        assertFalse(
            "File ID must not be a random UUID",
            received[0].id.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))
        )
        handler.shutdown()
    }

    @Test
    fun reactionTargetResolvesForFileMessage() {
        val senderPeerID = "8899aabbccddeeff"
        val timestampMs = 1_730_000_000_123L
        val filePayload = CirabitFilePacket(
            fileName = "voice.m4a",
            fileSize = 3,
            mimeType = "audio/mp4",
            content = byteArrayOf(9, 8, 7)
        ).encode()
        assertNotNull(filePayload)

        val fileMessageID = MediaSendingManager.computeStableFileMessageID(
            senderPeerID = senderPeerID,
            timestampMs = timestampMs,
            encodedFilePayload = filePayload!!
        )
        val reaction = MessageReaction(
            messageID = fileMessageID,
            emoji = "👍",
            reactorPeerID = "0011223344556677",
            isRemoval = false
        )

        val encoded = MessageReactionCodec.encode(reaction)
        assertNotNull(encoded)
        val decoded = MessageReactionCodec.decode(encoded!!)
        assertNotNull(decoded)
        assertEquals(reaction, decoded)

        val updatedState = ChatViewModel.applyReactionToState(emptyMap(), decoded!!)
        assertTrue(updatedState[fileMessageID]?.get("👍")?.contains("0011223344556677") == true)
    }

    private fun hexToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8)
        for (i in 0 until 8) {
            result[i] = hexString.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
