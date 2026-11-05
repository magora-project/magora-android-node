package org.magora.node

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * birdnetlib 0.18.1's location filter (SpeciesList.return_list with the Recording default
 * filter_threshold LOCATION_FILTER_THRESHOLD = 0.03): the BirdNET V2.4 metadata model
 * scores every label for [lat, lon, week_48]; labels scoring >= 0.03 form the allow-list,
 * keyed "Scientific name_Common name". Cached per (lat, lon, week).
 */
class LocationFilter(context: Context) {
    private val interpreter: Interpreter
    private val labels: List<String>
    private var cacheKey: String? = null
    private var cached: Set<String> = emptySet()

    init {
        val afd = context.assets.openFd(MODEL_ASSET)
        FileInputStream(afd.fileDescriptor).channel.use { channel ->
            val model = channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            interpreter = Interpreter(model, Interpreter.Options().setNumThreads(1))
        }
        labels = context.assets.open(BirdnetAnalyzer.LABELS_ASSET).bufferedReader().use { it.readLines() }
        val outputs = interpreter.getOutputTensor(0).shape().last()
        check(outputs == labels.size) { "meta model has $outputs outputs for ${labels.size} labels" }
    }

    @Synchronized
    fun allowList(lat: Double, lon: Double, week48: Int): Set<String> {
        val key = "$lon-$lat-$week48-$THRESHOLD"
        if (key == cacheKey) return cached
        val input = arrayOf(floatArrayOf(lat.toFloat(), lon.toFloat(), week48.toFloat()))
        val output = Array(1) { FloatArray(labels.size) }
        interpreter.run(input, output)
        val allowed = HashSet<String>()
        for (i in labels.indices) if (output[0][i] >= THRESHOLD) allowed.add(labels[i])
        cacheKey = key
        cached = allowed
        return allowed
    }

    companion object {
        const val THRESHOLD = 0.03f
        const val MODEL_ASSET = "birdnet/BirdNET_GLOBAL_6K_V2.4_MData_Model_V2_FP16.tflite"
    }
}
