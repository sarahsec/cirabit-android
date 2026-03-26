package com.cirabit.android.mesh

import android.util.Log
import com.cirabit.android.protocol.CirabitPacket
import com.cirabit.android.protocol.MessageType
import com.cirabit.android.protocol.MessagePadding
import com.cirabit.android.model.FragmentPayload
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages message fragmentation and reassembly - 100% iOS Compatible
 * 
 * This implementation exactly matches iOS SimplifiedBluetoothService fragmentation:
 * - Same fragment payload structure (13-byte header + data)
 * - Same MTU thresholds and fragment sizes
 * - Same reassembly logic and timeout handling
 * - Uses new FragmentPayload model for type safety
 */
class FragmentManager(
    private val maxIncomingBytes: Long = com.cirabit.android.util.AppConstants.Media.MAX_INCOMING_FILE_BYTES,
    private val maxFragmentsPerId: Int = com.cirabit.android.util.AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID,
    private val maxActiveFragmentSets: Int = com.cirabit.android.util.AppConstants.Fragmentation.MAX_ACTIVE_FRAGMENT_SETS,
    private val maxGlobalBufferedBytes: Long = com.cirabit.android.util.AppConstants.Fragmentation.MAX_GLOBAL_FRAGMENT_TOTAL_BYTES
) {
    
    companion object {
        private const val TAG = "FragmentManager"
        // iOS values: 512 MTU threshold, 469 max fragment size (512 MTU - headers)
        private const val FRAGMENT_SIZE_THRESHOLD = com.cirabit.android.util.AppConstants.Fragmentation.FRAGMENT_SIZE_THRESHOLD // Matches iOS: if data.count > 512
        private const val MAX_FRAGMENT_SIZE = com.cirabit.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_SIZE        // Matches iOS: maxFragmentSize = 469 
        private const val FRAGMENT_TIMEOUT = com.cirabit.android.util.AppConstants.Fragmentation.FRAGMENT_TIMEOUT_MS     // Matches iOS: 30 seconds cleanup
        private const val CLEANUP_INTERVAL = com.cirabit.android.util.AppConstants.Fragmentation.CLEANUP_INTERVAL_MS     // 10 seconds cleanup check
    }
    
    // Fragment storage - iOS equivalent: incomingFragments: [String: [Int: Data]]
    private val incomingFragments = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    // iOS equivalent: fragmentMetadata: [String: (type: UInt8, total: Int, timestamp: Date)]
    private val fragmentMetadata = ConcurrentHashMap<String, Triple<UByte, Int, Long>>() // originalType, totalFragments, timestamp
    // Tracks accumulated payload bytes per fragment set to guard against oversized reassembly.
    private val incomingFragmentBytes = ConcurrentHashMap<String, Long>()
    private val fragmentStateLock = Any()
    private var globalBufferedBytes: Long = 0L

    private val maxFragmentSetBytes: Long =
        minOf(
            maxIncomingBytes,
            com.cirabit.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_TOTAL_BYTES
        )
    
    // Delegate for callbacks
    var delegate: FragmentManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Create fragments from a large packet - 100% iOS Compatible
     * Matches iOS sendFragmentedPacket() implementation exactly
     */
    fun createFragments(packet: CirabitPacket): List<CirabitPacket> {
        try {
            Log.d(TAG, "🔀 Creating fragments for packet type ${packet.type}, payload: ${packet.payload.size} bytes")
        val encoded = packet.toBinaryData()
            if (encoded == null) {
                Log.e(TAG, "❌ Failed to encode packet to binary data")
                return emptyList()
            }
            Log.d(TAG, "📦 Encoded to ${encoded.size} bytes")
        
        // Fragment the unpadded frame; each fragment will be encoded (and padded) independently - iOS fix
        val fullData = try {
                MessagePadding.unpad(encoded)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to unpad data: ${e.message}", e)
                return emptyList()
            }
            Log.d(TAG, "📏 Unpadded to ${fullData.size} bytes")
        
        // iOS logic: if data.count > 512 && packet.type != MessageType.fragment.rawValue
        if (fullData.size <= FRAGMENT_SIZE_THRESHOLD) {
            return listOf(packet) // No fragmentation needed
        }
        
        val fragments = mutableListOf<CirabitPacket>()
        
        // iOS: let fragmentID = Data((0..<8).map { _ in UInt8.random(in: 0...255) })
        val fragmentID = FragmentPayload.generateFragmentID()
        
        // iOS: stride(from: 0, to: fullData.count, by: maxFragmentSize)
        // Calculate dynamic fragment size to fit in MTU (512)
        // Packet = Header + Sender + Recipient + Route + FragmentHeader + Payload + PaddingBuffer
        val hasRoute = packet.route != null
        val version = if (hasRoute) 2 else 1
        val headerSize = if (version == 2) 15 else 13
        val senderSize = 8
        val recipientSize = if (packet.recipientID != null) 8 else 0
        // Route: 1 byte count + 8 bytes per hop
        val routeSize = if (hasRoute) (1 + (packet.route?.size ?: 0) * 8) else 0
        val fragmentHeaderSize = 13 // FragmentPayload header
        val paddingBuffer = 16 // MessagePadding.optimalBlockSize adds 16 bytes overhead

        // 512 - Overhead
        val packetOverhead = headerSize + senderSize + recipientSize + routeSize + fragmentHeaderSize + paddingBuffer
        val maxDataSize = (512 - packetOverhead).coerceAtMost(MAX_FRAGMENT_SIZE)
        
        if (maxDataSize <= 0) {
            Log.e(TAG, "❌ Calculated maxDataSize is non-positive ($maxDataSize). Route too large?")
            return emptyList()
        }

        Log.d(TAG, "📏 Dynamic fragment size: $maxDataSize (MAX: $MAX_FRAGMENT_SIZE, Overhead: $packetOverhead)")

        val fragmentChunks = stride(0, fullData.size, maxDataSize) { offset ->
            val endOffset = minOf(offset + maxDataSize, fullData.size)
            fullData.sliceArray(offset..<endOffset)
        }

        if (fragmentChunks.size > maxFragmentsPerId) {
            Log.w(
                TAG,
                "❌ Fragmentation would exceed supported fragment count (${fragmentChunks.size} > $maxFragmentsPerId)"
            )
            return emptyList()
        }
        
        Log.d(TAG, "Creating ${fragmentChunks.size} fragments for ${fullData.size} byte packet (iOS compatible)")
        
        // iOS: for (index, fragment) in fragments.enumerated()
        for (index in fragmentChunks.indices) {
            val fragmentData = fragmentChunks[index]
            
            // Create iOS-compatible fragment payload
            val fragmentPayload = FragmentPayload(
                fragmentID = fragmentID,
                index = index,
                total = fragmentChunks.size,
                originalType = packet.type,
                data = fragmentData
            )
            
            // iOS: MessageType.fragment.rawValue (single fragment type)
            // Fix: Fragments must inherit source route and use v2 if routed
            val fragmentPacket = CirabitPacket(
                version = if (packet.route != null) 2u else 1u,
                type = MessageType.FRAGMENT.value,
                ttl = packet.ttl,
                senderID = packet.senderID,
                recipientID = packet.recipientID,
                timestamp = packet.timestamp,
                payload = fragmentPayload.encode(),
                route = packet.route,
                signature = null // iOS: signature: nil
            )
            
            fragments.add(fragmentPacket)
        }
        
        Log.d(TAG, "✅ Created ${fragments.size} fragments successfully")
            return fragments
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fragment creation failed: ${e.message}", e)
            Log.e(TAG, "❌ Packet type: ${packet.type}, payload: ${packet.payload.size} bytes")
            return emptyList()
        }
    }
    
    /**
     * Handle incoming fragment - 100% iOS Compatible  
     * Matches iOS handleFragment() implementation exactly
     */
    fun handleFragment(packet: CirabitPacket): CirabitPacket? {
        // iOS: guard packet.payload.count > 13 else { return }
        if (packet.payload.size < FragmentPayload.HEADER_SIZE) {
            Log.w(TAG, "Fragment packet too small: ${packet.payload.size}")
            return null
        }
        
        // Don't process our own fragments - iOS equivalent check
        // This would be done at a higher level but we'll include for safety
        
        try {
            // Use FragmentPayload for type-safe decoding
            val fragmentPayload = FragmentPayload.decode(packet.payload)
            if (fragmentPayload == null || !fragmentPayload.isValid()) {
                Log.w(TAG, "Invalid fragment payload")
                return null
            }
            
            // iOS: let fragmentID = packet.payload[0..<8].map { String(format: "%02x", $0) }.joined()
            val fragmentIDString = fragmentPayload.getFragmentIDString()
            
            Log.d(TAG, "Received fragment ${fragmentPayload.index}/${fragmentPayload.total} for fragmentID: $fragmentIDString, originalType: ${fragmentPayload.originalType}")
            synchronized(fragmentStateLock) {
                if (fragmentPayload.total > maxFragmentsPerId) {
                    Log.w(
                        TAG,
                        "Rejecting fragment with excessive total count: ${fragmentPayload.total} > $maxFragmentsPerId"
                    )
                    return null
                }

                val existingMetadata = fragmentMetadata[fragmentIDString]
                if (existingMetadata != null) {
                    val (expectedType, expectedTotal, _) = existingMetadata
                    if (expectedTotal != fragmentPayload.total || expectedType != fragmentPayload.originalType) {
                        Log.w(
                            TAG,
                            "Rejecting fragment for $fragmentIDString: inconsistent metadata " +
                                "(expected type=$expectedType total=$expectedTotal, got type=${fragmentPayload.originalType} total=${fragmentPayload.total})"
                        )
                        removeFragmentSetLocked(fragmentIDString)
                        return null
                    }
                }

                val isNewSet = !incomingFragments.containsKey(fragmentIDString)
                if (isNewSet) {
                    if (incomingFragments.size >= maxActiveFragmentSets) {
                        Log.w(
                            TAG,
                            "Rejecting new fragment set $fragmentIDString: active fragment sets ${incomingFragments.size} >= $maxActiveFragmentSets"
                        )
                        return null
                    }
                    incomingFragments[fragmentIDString] = mutableMapOf()
                    fragmentMetadata[fragmentIDString] = Triple(
                        fragmentPayload.originalType,
                        fragmentPayload.total,
                        System.currentTimeMillis()
                    )
                    incomingFragmentBytes[fragmentIDString] = 0L
                }

                val fragmentMap = incomingFragments[fragmentIDString]
                val currentTotalBytes = incomingFragmentBytes[fragmentIDString]
                if (fragmentMap == null || currentTotalBytes == null) {
                    Log.w(TAG, "Dropping fragment set $fragmentIDString due to missing reassembly state")
                    removeFragmentSetLocked(fragmentIDString)
                    return null
                }

                val previousFragmentSize = fragmentMap[fragmentPayload.index]?.size?.toLong() ?: 0L
                val proposedTotalBytes = currentTotalBytes - previousFragmentSize + fragmentPayload.data.size.toLong()
                if (proposedTotalBytes > maxFragmentSetBytes) {
                    Log.w(
                        TAG,
                        "Dropping oversized fragment set $fragmentIDString from ${packet.senderID.joinToString("") { "%02x".format(it) }.take(8)}..." +
                            " total=$proposedTotalBytes limit=$maxFragmentSetBytes"
                    )
                    removeFragmentSetLocked(fragmentIDString)
                    return null
                }

                val globalDelta = fragmentPayload.data.size.toLong() - previousFragmentSize
                val proposedGlobal = globalBufferedBytes + globalDelta
                if (proposedGlobal > maxGlobalBufferedBytes) {
                    Log.w(
                        TAG,
                        "Rejecting fragment for $fragmentIDString: global buffered bytes $proposedGlobal exceeds cap $maxGlobalBufferedBytes"
                    )
                    if (isNewSet) {
                        removeFragmentSetLocked(fragmentIDString)
                    }
                    return null
                }

                fragmentMap[fragmentPayload.index] = fragmentPayload.data
                incomingFragmentBytes[fragmentIDString] = proposedTotalBytes
                globalBufferedBytes = proposedGlobal

                val expectedTotal = fragmentMetadata[fragmentIDString]?.second ?: fragmentPayload.total
                if (fragmentMap.size == expectedTotal) {
                    Log.d(TAG, "All fragments received for $fragmentIDString, reassembling...")
                    val expectedSize = incomingFragmentBytes[fragmentIDString] ?: fragmentMap.values.sumOf { it.size.toLong() }
                    if (expectedSize > maxFragmentSetBytes || expectedSize > Int.MAX_VALUE.toLong()) {
                        Log.w(
                            TAG,
                            "Dropping oversized reassembly for $fragmentIDString total=$expectedSize limit=$maxFragmentSetBytes"
                        )
                        removeFragmentSetLocked(fragmentIDString)
                        return null
                    }

                    val reassembledData = ByteArray(expectedSize.toInt())
                    var writeOffset = 0
                    for (i in 0 until expectedTotal) {
                        val data = fragmentMap[i]
                        if (data == null) {
                            Log.w(TAG, "Missing fragment index $i for $fragmentIDString during reassembly")
                            removeFragmentSetLocked(fragmentIDString)
                            return null
                        }
                        if (writeOffset + data.size > reassembledData.size) {
                            Log.w(TAG, "Fragment overflow while reassembling $fragmentIDString")
                            removeFragmentSetLocked(fragmentIDString)
                            return null
                        }
                        System.arraycopy(data, 0, reassembledData, writeOffset, data.size)
                        writeOffset += data.size
                    }
                    val completeData = if (writeOffset == reassembledData.size) reassembledData else reassembledData.copyOf(writeOffset)

                    // Decode the original packet bytes we reassembled, so flags/compression are preserved - iOS fix
                    val originalPacket = CirabitPacket.fromBinaryData(completeData)
                    if (originalPacket != null) {
                        removeFragmentSetLocked(fragmentIDString)

                        // Suppress re-broadcast of the reassembled packet by zeroing TTL.
                        // We already relayed the incoming fragments; setting TTL=0 ensures
                        // PacketRelayManager will skip relaying this reconstructed packet.
                        val suppressedTtlPacket = originalPacket.copy(ttl = 0u.toUByte())
                        Log.d(TAG, "Successfully reassembled original (${completeData.size} bytes); set TTL=0 to suppress relay")
                        return suppressedTtlPacket
                    }

                    val metadata = fragmentMetadata[fragmentIDString]
                    Log.e(TAG, "Failed to decode reassembled packet (type=${metadata?.first}, total=${metadata?.second})")
                    removeFragmentSetLocked(fragmentIDString)
                } else {
                    val received = fragmentMap.size
                    Log.d(TAG, "Fragment ${fragmentPayload.index} stored, have $received/${fragmentPayload.total} fragments for $fragmentIDString")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle fragment: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Helper function to match iOS stride functionality
     * stride(from: 0, to: fullData.count, by: maxFragmentSize)
     */
    private fun <T> stride(from: Int, to: Int, by: Int, transform: (Int) -> T): List<T> {
        val result = mutableListOf<T>()
        var current = from
        while (current < to) {
            result.add(transform(current))
            current += by
        }
        return result
    }

    private fun removeFragmentSetLocked(fragmentIDString: String) {
        incomingFragments.remove(fragmentIDString)
        fragmentMetadata.remove(fragmentIDString)
        val bytes = incomingFragmentBytes.remove(fragmentIDString) ?: 0L
        if (bytes > 0L) {
            globalBufferedBytes = (globalBufferedBytes - bytes).coerceAtLeast(0L)
        }
    }
    
    /**
     * iOS cleanup - exactly matching performCleanup() implementation
     * Clean old fragments (> 30 seconds old)
     */
    private fun cleanupOldFragments() {
        synchronized(fragmentStateLock) {
            val now = System.currentTimeMillis()
            val cutoff = now - FRAGMENT_TIMEOUT

            // iOS: let oldFragments = fragmentMetadata.filter { $0.value.timestamp < cutoff }.map { $0.key }
            val oldFragments = fragmentMetadata.filter { it.value.third < cutoff }.map { it.key }

            // iOS: for fragmentID in oldFragments { incomingFragments.removeValue(forKey: fragmentID) }
            for (fragmentID in oldFragments) {
                removeFragmentSetLocked(fragmentID)
            }

            if (oldFragments.isNotEmpty()) {
                Log.d(TAG, "Cleaned up ${oldFragments.size} old fragment sets (iOS compatible)")
            }
        }
    }
    
    /**
     * Get debug information - matches iOS debugging
     */
    fun getDebugInfo(): String {
        synchronized(fragmentStateLock) {
            return buildString {
                appendLine("=== Fragment Manager Debug Info (iOS Compatible) ===")
                appendLine("Active Fragment Sets: ${incomingFragments.size}")
                appendLine("Global Buffered Bytes: $globalBufferedBytes")
                appendLine("Fragment Size Threshold: $FRAGMENT_SIZE_THRESHOLD bytes")
                appendLine("Max Fragment Size: $MAX_FRAGMENT_SIZE bytes")

                fragmentMetadata.forEach { (fragmentID, metadata) ->
                    val (originalType, totalFragments, timestamp) = metadata
                    val received = incomingFragments[fragmentID]?.size ?: 0
                    val ageSeconds = (System.currentTimeMillis() - timestamp) / 1000
                    appendLine("  - $fragmentID: $received/$totalFragments fragments, type: $originalType, age: ${ageSeconds}s")
                }
            }
        }
    }
    
    /**
     * Start periodic cleanup of old fragments - matches iOS maintenance timer
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupOldFragments()
            }
        }
    }
    
    /**
     * Clear all fragments
     */
    fun clearAllFragments() {
        synchronized(fragmentStateLock) {
            incomingFragments.clear()
            fragmentMetadata.clear()
            incomingFragmentBytes.clear()
            globalBufferedBytes = 0L
        }
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllFragments()
    }
}

/**
 * Delegate interface for fragment manager callbacks
 */
interface FragmentManagerDelegate {
    fun onPacketReassembled(packet: CirabitPacket)
}
