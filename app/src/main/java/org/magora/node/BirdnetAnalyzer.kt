package org.magora.node

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * On-device BirdNET inference. Uses the exact model and inference semantics of the
 * deployed Pi firmware (detect.py at pin f0c1940, via birdnetlib's default Analyzer):
 * BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite, 48 kHz mono float audio, 3 s chunks with
 * 1.5 s overlap (tails shorter than 1.5 s dropped, shorter tails zero-padded), and
 * detect.py's sensitivity=1.25. birdnetlib's RecordingBase converts that user sensitivity
 * to max(0.5, min(1.0 - (sensitivity - 1.0), 1.5)) = 0.75 before its flat sigmoid
 * 1 / (1 + exp(-0.75 * clip(logit, -15, 15))); that converted value is applied here.
 * The phone logs locally at >= 0.10; DetectionFilter decides what is posted.
 */
class BirdnetAnalyzer(context: Context) {

    data class Detection(
        val commonName: String,
        val scientificName: String,
        val confidence: Float,
        val windowStartSecs: Double,
        val windowEndSecs: Double
    )

    private val interpreter: Interpreter
    private val labels: List<String>

    init {
        val afd = context.assets.openFd(MODEL_ASSET)
        FileInputStream(afd.fileDescriptor).channel.use { channel ->
            val model = channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
        }
        labels = context.assets.open(LABELS_ASSET).bufferedReader().use { it.readLines() }
    }

    fun analyze(samples: FloatArray): List<Detection> {
        val detections = mutableListOf<Detection>()
        val output = Array(1) { FloatArray(labels.size) }
        var windowStart = 0.0
        var offset = 0
        val stepSamples = ((SAMPLE_SECS - OVERLAP_SECS) * SAMPLE_RATE).toInt()
        while (offset < samples.size) {
            var chunk = samples.copyOfRange(offset, minOf(offset + CHUNK_SAMPLES, samples.size))
            if (chunk.size < MIN_CHUNK_SAMPLES) break
            if (chunk.size < CHUNK_SAMPLES) chunk = chunk.copyOf(CHUNK_SAMPLES)
            output[0].fill(0f)
            interpreter.run(arrayOf(chunk), output)
            val logits = output[0]
            for (i in logits.indices) {
                val confidence = sigmoid(logits[i])
                if (confidence >= MIN_CONFIDENCE) {
                    val parts = labels[i].split("_", limit = 2)
                    detections.add(
                        Detection(
                            commonName = parts.getOrElse(1) { labels[i] },
                            scientificName = parts[0],
                            confidence = confidence,
                            windowStartSecs = windowStart,
                            windowEndSecs = windowStart + SAMPLE_SECS
                        )
                    )
                }
            }
            windowStart += SAMPLE_SECS - OVERLAP_SECS
            offset += stepSamples
        }
        return detections
    }

    companion object {
        /** birdnetlib flat_sigmoid with the converted sensitivity; float32 like numpy. */
        fun sigmoid(logit: Float): Float = 1.0f / (1.0f + exp(-EFFECTIVE_SENSITIVITY * logit.coerceIn(-15f, 15f)))

        /** birdnetlib RecordingBase: max(0.5, min(1.0 - (sensitivity - 1.0), 1.5)). */
        val EFFECTIVE_SENSITIVITY: Float = maxOf(0.5, minOf(1.0 - (SENSITIVITY_SETTING - 1.0), 1.5)).toFloat()

        const val SAMPLE_RATE = 48_000
        const val SAMPLE_SECS = 3.0
        const val OVERLAP_SECS = 1.5
        /** detect.py SENSITIVITY, as passed to birdnetlib's Recording. */
        const val SENSITIVITY_SETTING = 1.25
        const val MIN_CONFIDENCE = 0.10f
        const val CHUNK_SAMPLES = 144_000
        const val MIN_CHUNK_SAMPLES = 72_000
        const val MODEL_ASSET = "birdnet/BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite"
        const val LABELS_ASSET = "birdnet/BirdNET_GLOBAL_6K_V2.4_Labels.txt"
    }
}
