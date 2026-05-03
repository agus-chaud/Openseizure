package com.seizureguard.phone.ml

/**
 * Ingiere un chunk serializado (`/osd/accel_data`), actualiza [buffer] y decide si corre
 * inferencia según DEC-040 (warm-up + ventana deslizante).
 *
 * @param structuredLog Invocable para logs; el primer argumento es la clave
 *        (`chunk_received`, `buffer_fill`, `inference_triggered`), el segundo el detalle.
 */
class PhoneAccelChunkProcessor(
    private val buffer: PhoneCircularBuffer,
    private val infer: (FloatArray) -> Byte,
    private val structuredLog: (String, String) -> Unit = { _, _ -> }
) {

    data class ProcessResult(
        val didInfer: Boolean,
        val alarmState: Byte,
        val window: FloatArray?
    )

    fun processChunk(bytes: ByteArray): ProcessResult {
        val floats = AccelPayloadCodec.bytesToFloats(bytes)
        structuredLog("chunk_received", "bytes=${bytes.size} floats=${floats.size}")

        buffer.addAll(floats)
        structuredLog("buffer_fill", "size=${buffer.size} isFull=${buffer.isFull}")

        if (!buffer.isFull) {
            return ProcessResult(didInfer = false, alarmState = 0, window = null)
        }

        val window = buffer.snapshot()
        val alarm = infer(window)
        structuredLog(
            "inference_triggered",
            "alarm=$alarm window0=${window.firstOrNull()} windowLast=${window.lastOrNull()}"
        )
        return ProcessResult(didInfer = true, alarmState = alarm, window = window)
    }
}
