package com.cirabit.android.mesh

import com.cirabit.android.model.MessageReaction
import com.cirabit.android.util.AppConstants
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateReactionQueueTest {

    @Test
    fun privateReaction_queuedWhenNoSession() = runBlocking {
        var hasSession = false
        val handshakeRequests = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, MessageReaction>>()
        val manager = PrivateReactionQueueManager(
            hasSession = { hasSession },
            initiateHandshake = { peerID -> handshakeRequests += peerID },
            sendReaction = { peerID, reaction ->
                sent += peerID to reaction
                true
            }
        )
        val reaction = MessageReaction("msg-1", "👍", "0011223344556677", false)

        val result = manager.sendOrQueue("AABBCCDD", reaction)

        assertEquals(PrivateReactionQueueManager.SendResult.QUEUED, result)
        assertEquals(1, manager.pendingCount("AABBCCDD"))
        assertTrue(sent.isEmpty())
        assertEquals(listOf("AABBCCDD"), handshakeRequests)
    }

    @Test
    fun privateReaction_flushedAfterHandshake() = runBlocking {
        var hasSession = false
        val sent = mutableListOf<Pair<String, MessageReaction>>()
        val manager = PrivateReactionQueueManager(
            hasSession = { hasSession },
            initiateHandshake = { },
            sendReaction = { peerID, reaction ->
                sent += peerID to reaction
                true
            }
        )
        val reaction = MessageReaction("msg-2", "😂", "0011223344556677", false)

        manager.sendOrQueue("AABBCCDD", reaction)
        assertEquals(1, manager.pendingCount("AABBCCDD"))

        hasSession = true
        val flushed = manager.flush("AABBCCDD")

        assertEquals(1, flushed)
        assertEquals(1, sent.size)
        assertEquals("AABBCCDD", sent.first().first)
        assertEquals(reaction, sent.first().second)
        assertEquals(0, manager.pendingCount("AABBCCDD"))
    }

    @Test
    fun privateReaction_droppedAfterTTL() = runBlocking {
        var hasSession = true
        var nowMs = 200_000L
        val sent = mutableListOf<Pair<String, MessageReaction>>()
        val manager = PrivateReactionQueueManager(
            hasSession = { hasSession },
            initiateHandshake = { },
            sendReaction = { peerID, reaction ->
                sent += peerID to reaction
                true
            },
            nowMs = { nowMs }
        )
        val reaction = MessageReaction("msg-3", "😮", "0011223344556677", false)
        manager.enqueueForTest(
            peerID = "AABBCCDD",
            reaction = reaction,
            queuedAtMs = nowMs - AppConstants.Reactions.PRIVATE_PENDING_TTL_MS - 1_000L
        )

        val flushed = manager.flush("AABBCCDD")

        assertEquals(0, flushed)
        assertTrue(sent.isEmpty())
        assertEquals(0, manager.pendingCount("AABBCCDD"))
    }

    @Test
    fun privateReaction_optimisticUIRemainsOnFailure() = runBlocking {
        var hasSession = true
        var nowMs = 300_000L
        val dropped = mutableListOf<Triple<String, MessageReaction, String>>()
        val manager = PrivateReactionQueueManager(
            hasSession = { hasSession },
            initiateHandshake = { },
            sendReaction = { _, _ -> true },
            nowMs = { nowMs },
            onReactionDropped = { peerID, reaction, reason ->
                dropped += Triple(peerID, reaction, reason)
            }
        )
        val reaction = MessageReaction("msg-4", "❤️", "0011223344556677", false)
        manager.enqueueForTest(
            peerID = "AABBCCDD",
            reaction = reaction,
            queuedAtMs = nowMs - AppConstants.Reactions.PRIVATE_PENDING_TTL_MS - 5_000L
        )

        manager.flush("AABBCCDD")

        assertEquals(1, dropped.size)
        assertEquals("AABBCCDD", dropped.first().first)
        assertEquals(reaction, dropped.first().second)
        assertEquals(PrivateReactionQueueManager.DROP_REASON_TTL_EXPIRED, dropped.first().third)
    }
}
