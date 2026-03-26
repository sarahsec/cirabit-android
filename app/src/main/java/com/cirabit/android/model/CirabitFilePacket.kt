package com.cirabit.android.model

import com.cirabit.android.util.AppConstants
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CirabitFilePacket: TLV-encoded file transfer payload for BLE mesh.
 * TLVs:
 *  - 0x01: filename (UTF-8)
 *  - 0x02: file size (8 bytes, UInt64)
 *  - 0x03: mime type (UTF-8)
 *  - 0x04: content (bytes) — may appear multiple times for large files
 *
 * Length field for TLV is 2 bytes (UInt16, big-endian) for all TLVs.
 * For large files, CONTENT is chunked into multiple TLVs of up to 65535 bytes each.
 *
 * Note: The outer CirabitPacket uses version 2 (4-byte payload length), so this
 * TLV payload can exceed 64 KiB even though each TLV value is limited to 65535 bytes.
 * Transport-level fragmentation then splits the final packet for BLE MTU.
 */
data class CirabitFilePacket(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val content: ByteArray
) {
    private enum class TLVType(val v: UByte) {
        FILE_NAME(0x01u), FILE_SIZE(0x02u), MIME_TYPE(0x03u), CONTENT(0x04u);
        companion object { fun from(value: UByte) = values().find { it.v == value } }
    }

    fun encode(): ByteArray? {
        try {
            android.util.Log.d("CirabitFilePacket", "🔄 Encoding: name=$fileName, size=$fileSize, mime=$mimeType")
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
        // Validate bounds for 2-byte TLV lengths (per-TLV). CONTENT may exceed 65535 and will be chunked.
        if (nameBytes.size > 0xFFFF || mimeBytes.size > 0xFFFF) {
                android.util.Log.e("CirabitFilePacket", "❌ TLV field too large: name=${nameBytes.size}, mime=${mimeBytes.size} (max: 65535)")
                return null
            }
            if (content.size > 0xFFFF) {
                android.util.Log.d("CirabitFilePacket", "📦 Content exceeds 65535 bytes (${content.size}); will be split into multiple CONTENT TLVs")
            } else {
                android.util.Log.d("CirabitFilePacket", "📏 TLV sizes OK: name=${nameBytes.size}, mime=${mimeBytes.size}, content=${content.size}")
            }
        val sizeFieldLen = 4 // UInt32 for FILE_SIZE (changed from 8 bytes)
        val contentLenFieldLen = 4 // UInt32 for CONTENT TLV as requested

        // Compute capacity: header TLVs + single CONTENT TLV with 4-byte length
        val contentTLVBytes = 1 + contentLenFieldLen + content.size
        val capacity = (1 + 2 + nameBytes.size) + (1 + 2 + sizeFieldLen) + (1 + 2 + mimeBytes.size) + contentTLVBytes
        val buf = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)

        // FILE_NAME
        buf.put(TLVType.FILE_NAME.v.toByte())
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)

        // FILE_SIZE (4 bytes)
        buf.put(TLVType.FILE_SIZE.v.toByte())
        buf.putShort(sizeFieldLen.toShort())
        buf.putInt(fileSize.toInt())

        // MIME_TYPE
        buf.put(TLVType.MIME_TYPE.v.toByte())
        buf.putShort(mimeBytes.size.toShort())
        buf.put(mimeBytes)

        // CONTENT (single TLV with 4-byte length)
        buf.put(TLVType.CONTENT.v.toByte())
        buf.putInt(content.size)
        buf.put(content)

        val result = buf.array()
            android.util.Log.d("CirabitFilePacket", "✅ Encoded successfully: ${result.size} bytes total")
            return result
        } catch (e: Exception) {
            android.util.Log.e("CirabitFilePacket", "❌ Encoding failed: ${e.message}", e)
            return null
        }
    }

    companion object {
        fun decode(
            data: ByteArray,
            maxIncomingBytes: Long = AppConstants.Media.MAX_INCOMING_FILE_BYTES
        ): CirabitFilePacket? {
            android.util.Log.d("CirabitFilePacket", "🔄 Decoding ${data.size} bytes")
            try {
                val envelopeLimit = maxIncomingBytes + AppConstants.Media.MAX_FILE_PACKET_OVERHEAD_BYTES
                if (data.size.toLong() > envelopeLimit) {
                    android.util.Log.w(
                        "CirabitFilePacket",
                        "❌ Rejecting file packet larger than limit: data=${data.size} bytes limit=$envelopeLimit"
                    )
                    return null
                }
                var off = 0
                var name: String? = null
                var size: Long? = null
                var mime: String? = null
                var totalContentBytes = 0L
                var sawContentTlv = false
                val contentBuffer = ByteArrayOutputStream()
                while (off + 3 <= data.size) { // minimum TLV header size (type + 2 bytes length)
                    val t = TLVType.from(data[off].toUByte()) ?: return null
                    off += 1
                    // CONTENT uses 4-byte length; others use 2-byte length
                    val len: Int
                    if (t == TLVType.CONTENT) {
                        if (off + 4 > data.size) return null
                        len = ((data[off].toInt() and 0xFF) shl 24) or ((data[off + 1].toInt() and 0xFF) shl 16) or ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
                        off += 4
                    } else {
                        if (off + 2 > data.size) return null
                        len = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
                        off += 2
                    }
                    if (len < 0 || off + len > data.size) return null
                    when (t) {
                        TLVType.FILE_NAME -> name = String(data, off, len, Charsets.UTF_8)
                        TLVType.FILE_SIZE -> {
                            if (len != 4) return null
                            val bb = ByteBuffer.wrap(data, off, len).order(ByteOrder.BIG_ENDIAN)
                            val declaredSize = bb.int.toUInt().toLong()
                            if (declaredSize > maxIncomingBytes) {
                                android.util.Log.w(
                                    "CirabitFilePacket",
                                    "❌ Rejecting file packet with declared size=$declaredSize bytes (limit=$maxIncomingBytes)"
                                )
                                return null
                            }
                            size = declaredSize
                        }
                        TLVType.MIME_TYPE -> mime = String(data, off, len, Charsets.UTF_8)
                        TLVType.CONTENT -> {
                            sawContentTlv = true
                            val nextTotal = totalContentBytes + len.toLong()
                            if (nextTotal > maxIncomingBytes) {
                                android.util.Log.w(
                                    "CirabitFilePacket",
                                    "❌ Rejecting file packet content=$nextTotal bytes (limit=$maxIncomingBytes)"
                                )
                                return null
                            }
                            contentBuffer.write(data, off, len)
                            totalContentBytes = nextTotal
                        }
                    }
                    off += len
                }
                val n = name ?: return null
                val c = contentBuffer.toByteArray()
                if (!sawContentTlv) return null
                val s = size ?: c.size.toLong()
                if (s > maxIncomingBytes || c.size.toLong() > maxIncomingBytes) {
                    android.util.Log.w(
                        "CirabitFilePacket",
                        "❌ Rejecting decoded file over limit: declared=$s content=${c.size}"
                    )
                    return null
                }
                val m = mime ?: "application/octet-stream"
                val result = CirabitFilePacket(n, s, m, c)
                android.util.Log.d("CirabitFilePacket", "✅ Decoded: name=$n, size=$s, mime=$m, content=${c.size} bytes")
                return result
            } catch (e: Exception) {
                android.util.Log.e("CirabitFilePacket", "❌ Decoding failed: ${e.message}", e)
                return null
            }
        }
    }
}
