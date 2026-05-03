package com.seizureguard.phone.ml

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Codec binario para payloads `/osd/accel_data` (DEC-039, DEC-034).
 * Misma convención que el watch: IEEE-754 float32 little-endian.
 */
object AccelPayloadCodec {

    fun bytesToFloats(bytes: ByteArray): FloatArray {
        val n = bytes.size / Float.SIZE_BYTES
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { buffer.getFloat() }
    }

    fun floatsToBytes(samples: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buf.putFloat(it) }
        return buf.array()
    }
}
