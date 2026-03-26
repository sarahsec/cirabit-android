package com.cirabit.android.mesh

import com.cirabit.android.model.MessageReaction
import com.cirabit.android.util.AppConstants

internal class PrivateReactionQueueManager(
    private val hasSession: (String) -> Boolean,
    private val initiateHandshake: (String) -> Unit,
    private val sendReaction: suspend (String, MessageReaction) -> Boolean,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val maxPerPeer: Int = AppConstants.Reactions.PRIVATE_PENDING_MAX_PER_PEER,
    private val ttlMs: Long = AppConstants.Reactions.PRIVATE_PENDING_TTL_MS,
    private val onReactionDropped: ((peerID: String, reaction: MessageReaction, reason: String) -> Unit)? = null
) {
    companion object {
        const val DROP_REASON_TTL_EXPIRED = "ttl_expired"
        const val DROP_REASON_QUEUE_CAP = "queue_cap"
        const val DROP_REASON_SEND_FAILURE = "send_failure"
    }

    private data class PendingReaction(
        val reaction: MessageReaction,
        val queuedAtMs: Long
    )

    enum class SendResult { SENT, QUEUED, DROPPED }

    private val lock = Any()
    private val pendingByPeer = mutableMapOf<String, ArrayDeque<PendingReaction>>()

    suspend fun sendOrQueue(peerID: String, reaction: MessageReaction): SendResult {
        if (!hasSession(peerID)) {
            enqueue(peerID, reaction, nowMs())
            // Close TOCTOU window: session may become ready right after enqueue.
            if (hasSession(peerID)) {
                flush(peerID)
                return if (pendingCount(peerID) == 0) SendResult.SENT else SendResult.QUEUED
            }
            initiateHandshake(peerID)
            // Handshake may complete synchronously in tests/some implementations.
            if (hasSession(peerID)) {
                flush(peerID)
                return if (pendingCount(peerID) == 0) SendResult.SENT else SendResult.QUEUED
            }
            return SendResult.QUEUED
        }

        flush(peerID)
        val sent = sendReaction(peerID, reaction)
        if (!sent) {
            onReactionDropped?.invoke(peerID, reaction, DROP_REASON_SEND_FAILURE)
            return SendResult.DROPPED
        }
        return SendResult.SENT
    }

    suspend fun flush(peerID: String): Int {
        var sentCount = 0
        while (hasSession(peerID)) {
            val next = pollNextValid(peerID, nowMs()) ?: break
            val sent = sendReaction(peerID, next)
            if (!sent) {
                onReactionDropped?.invoke(peerID, next, DROP_REASON_SEND_FAILURE)
                break
            }
            sentCount++
        }
        return sentCount
    }

    internal fun pendingCount(peerID: String): Int = synchronized(lock) {
        pendingByPeer[peerID]?.size ?: 0
    }

    internal fun enqueueForTest(peerID: String, reaction: MessageReaction, queuedAtMs: Long) {
        enqueue(peerID, reaction, queuedAtMs)
    }

    private fun enqueue(peerID: String, reaction: MessageReaction, queuedAtMs: Long) {
        synchronized(lock) {
            val queue = pendingByPeer.getOrPut(peerID) { ArrayDeque() }
            dropExpiredLocked(peerID, queue, queuedAtMs)

            while (queue.size >= maxPerPeer) {
                val dropped = queue.removeFirst()
                onReactionDropped?.invoke(peerID, dropped.reaction, DROP_REASON_QUEUE_CAP)
            }

            queue.addLast(PendingReaction(reaction, queuedAtMs))
        }
    }

    private fun pollNextValid(peerID: String, referenceNowMs: Long): MessageReaction? {
        synchronized(lock) {
            val queue = pendingByPeer[peerID] ?: return null
            while (queue.isNotEmpty()) {
                val candidate = queue.removeFirst()
                val ageMs = referenceNowMs - candidate.queuedAtMs
                if (ageMs <= ttlMs) {
                    if (queue.isEmpty()) {
                        pendingByPeer.remove(peerID)
                    }
                    return candidate.reaction
                }
                onReactionDropped?.invoke(peerID, candidate.reaction, DROP_REASON_TTL_EXPIRED)
            }

            pendingByPeer.remove(peerID)
            return null
        }
    }

    private fun dropExpiredLocked(peerID: String, queue: ArrayDeque<PendingReaction>, referenceNowMs: Long) {
        while (queue.isNotEmpty()) {
            val first = queue.first()
            if (referenceNowMs - first.queuedAtMs <= ttlMs) {
                return
            }
            val dropped = queue.removeFirst()
            onReactionDropped?.invoke(peerID, dropped.reaction, DROP_REASON_TTL_EXPIRED)
        }
    }
}
