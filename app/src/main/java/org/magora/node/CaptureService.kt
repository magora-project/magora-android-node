package org.magora.node

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CaptureService : Service() {
    private var captureThread: Thread? = null
    private var analysisThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var serviceAlive = true
    private val analysisQueue = ArrayBlockingQueue<AnalysisItem>(8)
    @Volatile private var ingest: Ingest? = null
    private val thermal = ThermalGovernor()

    /** Phase 2 prod ingest for the imported node. Absent until a config has been imported. */
    private class Ingest(
        val config: NodeConfig,
        val queue: IngestQueue,
        val worker: IngestWorker,
        val whitelist: SpeciesWhitelist,
    ) {
        fun enqueue(table: String, json: String) {
            queue.enqueue(table, json)
            worker.wake()
        }
    }

    private data class AnalysisItem(
        val cycleId: String,
        val samples: FloatArray,
        val source: String,
        val inputDevice: String,
        val battery: BatteryInfo.Reading
    )

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureIngest()
        val reason = intent?.getStringExtra(EXTRA_START_REASON) ?: "system_restart"
        if (intent?.getBooleanExtra(EXTRA_RESTART_CAPTURE, false) == true) stopCaptureLoop()
        if (!running) {
            if (intent?.getBooleanExtra(EXTRA_DEFER_CAPTURE, false) == true) {
                // Boot path: become foreground without opening the microphone. The home
                // activity (or a notification tap) starts capture once visible.
                NodeState.setListening(this, true)
                NodeState.setDeferred(this, true)
                NodeState.setStartReason(this, reason)
                DailyLog.append(this, "event=service_start reason=$reason deferred=true")
            } else {
                startCaptureLoop(reason)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceAlive = false
        stopCaptureLoop()
        analysisThread?.interrupt()
        ingest?.worker?.stop()
        ingest = null
        NodeState.setListening(this, false)
        NodeState.setDeferred(this, false)
        DailyLog.append(this, "event=service_stop")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopCaptureLoop() {
        running = false
        val oldThread = captureThread
        oldThread?.interrupt()
        oldThread?.join(500L)
        captureThread = null
    }

    private fun startCaptureLoop(reason: String) {
        running = true
        NodeState.setListening(this, true)
        NodeState.setDeferred(this, false)
        NodeState.setStartReason(this, reason)
        NodeState.setLastCycleSilent(this, false)
        if (NodeState.startedAt(this) == 0L) NodeState.setStartedAt(this, System.currentTimeMillis())
        DailyLog.append(this, "event=service_start reason=$reason")
        ensureAnalysisThread()
        captureThread = thread(name = "magora-capture") { captureLoop() }
    }

    private fun captureLoop() {
        while (running && !Thread.currentThread().isInterrupted) {
            val cycleId = DailyLog.utcNow()
            val source = preferredSource(this)
            val sourceName = sourceName(source)
            val inputDevice = activeInputDevice(this)
            NodeState.setCycleFields(this, sourceName, inputDevice)
            DailyLog.append(this, "event=cycle_start cycle_id=$cycleId granted_source=$sourceName input_device=$inputDevice")

            val sampleRate = 48_000
            val channel = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minimum = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            val record = createAudioRecord(sampleRate, channel, encoding, minimum)
            if (record == null) {
                DailyLog.append(this, "event=cycle_error cycle_id=$cycleId error=audio_source_unavailable")
                Thread.sleep(5_000)
                continue
            }

            val pcm = ByteArrayOutputStream(sampleRate * 2 * 15)
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("AudioRecord did not enter RECORDSTATE_RECORDING")
                }
                val grantedSource = record.audioSource
                val grantedSourceName = sourceName(grantedSource)
                NodeState.setCycleFields(this, grantedSourceName, activeInputDevice(this))
                val buffer = ByteArray(4096)
                val deadline = SystemClock.elapsedRealtime() + 15_000L
                var totalSquares = 0L
                var sampleCount = 0
                var peak = 0
                while (running && SystemClock.elapsedRealtime() < deadline && !Thread.currentThread().isInterrupted) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        pcm.write(buffer, 0, read)
                        var index = 0
                        while (index + 1 < read) {
                            val sample = (buffer[index].toInt() and 0xff or (buffer[index + 1].toInt() shl 8)).toShort().toInt()
                            val absolute = kotlin.math.abs(sample)
                            peak = kotlin.math.max(peak, absolute)
                            totalSquares += sample.toLong() * sample.toLong()
                            sampleCount++
                            index += 2
                        }
                    }
                }
                val rms = if (sampleCount > 0) kotlin.math.sqrt(totalSquares.toDouble() / sampleCount) else 0.0
                val silent = peak <= 1
                val battery = BatteryInfo.read(this)
                val batteryFields = "battery_pct=${battery.percent} battery_temp_c=${BatteryInfo.formatTemp(battery.temperatureC)} charging=${battery.state}"
                NodeState.setLastCycleSilent(this, silent)
                if (silent) {
                    DailyLog.append(this, "event=cycle_silent cycle_id=$cycleId granted_source=$grantedSourceName input_device=${activeInputDevice(this)} duration_ms=15000 rms=$rms peak=$peak $batteryFields")
                    BootReceiver.postResumeNotification(this)
                } else {
                    NodeState.incrementCycles(this)
                    DailyLog.append(this, "event=cycle_stop cycle_id=$cycleId granted_source=$grantedSourceName input_device=${activeInputDevice(this)} duration_ms=15000 rms=$rms peak=$peak $batteryFields result=success")
                    val samples = toFloatSamples(pcm.toByteArray())
                    val item = AnalysisItem(cycleId, samples, grantedSourceName, activeInputDevice(this), battery)
                    if (!analysisQueue.offer(item)) {
                        DailyLog.append(this, "event=analysis_dropped cycle_id=$cycleId")
                    }
                }
            } catch (error: Exception) {
                DailyLog.append(this, "event=cycle_error cycle_id=$cycleId error=${error.javaClass.simpleName}")
            } finally {
                record.stopSafely()
                record.release()
            }
        }
    }

    /**
     * Starts ingest when a config has been imported (or re-imported for another node).
     * Runs in deferred boot mode too, so queued rows drain before the mic is back.
     */
    @Synchronized
    private fun ensureIngest() {
        NodeConfig.importIfPresent(this)
        val config = NodeConfig.load(this) ?: return
        val current = ingest
        if (current != null && current.config == config) return
        current?.worker?.stop()
        val client = SupabaseClient(config, {
            try {
                CredentialStore.load(this)
            } catch (error: Exception) {
                DailyLog.append(this, "event=credential_load_error error=${error.javaClass.simpleName}")
                null
            }
        })
        // One queue per node: rows carry their node_id, and only that node's credentials may send them.
        val queue = IngestQueue.forDirectory(File(File(filesDir, "ingest"), config.nodeId))
        val worker = IngestWorker(this, queue, client)
        val whitelist = SpeciesWhitelist(this, config.nodeId)
        val next = Ingest(config, queue, worker, whitelist)
        ingest = next
        DailyLog.append(this, "event=ingest_start node_id=${config.nodeId} queue_depth=${queue.depth()} posting=${NodeState.postingEnabled(this)}")
        worker.start()
        thread(name = "magora-whitelist") {
            whitelist.resolve(client) { serviceAlive && ingest === next }
        }
    }

    private fun ensureAnalysisThread() {
        if (analysisThread?.isAlive == true) return
        analysisThread = thread(name = "magora-analysis") { analysisLoop() }
    }

    private fun analysisLoop() {
        val birdnet = try {
            BirdnetAnalyzer(applicationContext)
        } catch (error: Exception) {
            DailyLog.append(this, "event=analysis_error error=${error.javaClass.simpleName} at=model_load")
            null
        }
        var locationFilter: LocationFilter? = null
        var locationFilterFailed = false
        var whitelistWaitedFor: Ingest? = null
        while (serviceAlive) {
            val item = try {
                analysisQueue.poll(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                return
            } ?: continue
            try {
                val now = Instant.parse(item.cycleId)
                val aci = AcousticComplexity.calculate(item.samples, BirdnetAnalyzer.SAMPLE_RATE)

                when (thermal.update(item.battery.temperatureC)) {
                    ThermalGovernor.Transition.PAUSED -> {
                        NodeState.setInferencePaused(this, true)
                        DailyLog.append(this, "event=thermal_pause cycle_id=${item.cycleId} battery_temp_c=${BatteryInfo.formatTemp(item.battery.temperatureC)}")
                    }
                    ThermalGovernor.Transition.RESUMED -> {
                        NodeState.setInferencePaused(this, false)
                        DailyLog.append(this, "event=thermal_resume cycle_id=${item.cycleId} battery_temp_c=${BatteryInfo.formatTemp(item.battery.temperatureC)}")
                    }
                    null -> Unit
                }
                val detections = if (thermal.paused) emptyList() else birdnet?.analyze(item.samples) ?: emptyList()

                val ing = ingest
                val config = ing?.config
                if (ing != null && whitelistWaitedFor !== ing) {
                    // Like detect.py, resolve the whitelist before the first detection is judged.
                    if (!ing.whitelist.await(WHITELIST_WAIT_MILLIS)) DailyLog.append(this, "event=whitelist_wait_timeout")
                    whitelistWaitedFor = ing
                }
                if (config != null && locationFilter == null && !locationFilterFailed) {
                    locationFilter = try {
                        LocationFilter(applicationContext)
                    } catch (error: Exception) {
                        locationFilterFailed = true
                        DailyLog.append(this, "event=location_filter_error error=${error.javaClass.simpleName}")
                        null
                    }
                }
                val week48 = Temporal.week48(now)
                // birdnetlib applies the location filter only when lat and lon are both truthy.
                val allow = if (config != null && config.lat != 0.0 && config.lon != 0.0) {
                    locationFilter?.allowList(config.lat, config.lon, week48)
                } else {
                    null
                }
                val whitelist = ing?.whitelist?.species
                if (ing != null) {
                    NodeState.setFilterStatus(this, "${ing.whitelist.source}:${whitelist?.size ?: 0}", allow?.size ?: -1, week48)
                }

                val posting = ing != null && NodeState.postingEnabled(this)
                val temporal = config?.let { Temporal.context(now, it.lat, it.lon) }
                // A persistence failure must never cost the raw log its detection lines.
                fun tryEnqueue(table: String, json: () -> String): Boolean = try {
                    ing!!.enqueue(table, json())
                    true
                } catch (error: Exception) {
                    DailyLog.append(this, "event=enqueue_error cycle_id=${item.cycleId} table=$table error=${error.javaClass.simpleName}")
                    false
                }

                var aciQueued = false
                if (posting && aci != null && config != null && temporal != null) {
                    aciQueued = tryEnqueue("aci_logs") { Rows.aci(config.site, aci, now, temporal) }
                }

                var posted = 0
                for (d in detections) {
                    val decision = DetectionFilter.decide(d.commonName, d.scientificName, d.confidence, allow, whitelist)
                    val outcome = when {
                        !decision.post -> "decision=filtered reason=${decision.reason!!.logName}"
                        ing == null || config == null || temporal == null -> "decision=held reason=no_config"
                        !posting -> "decision=held reason=posting_off"
                        tryEnqueue("detections") { Rows.detection(config.site, d.commonName, d.scientificName, d.confidence, now, temporal) } -> {
                            posted++
                            if (decision.unusual) "decision=posted unusual=true" else "decision=posted"
                        }
                        else -> "decision=enqueue_failed"
                    }
                    DailyLog.append(
                        this,
                        "event=detection cycle_id=${item.cycleId} common=\"${d.commonName}\" scientific=\"${d.scientificName}\" " +
                            "confidence=${d.confidence} window_start_s=${d.windowStartSecs} window_end_s=${d.windowEndSecs} $outcome"
                    )
                }
                DailyLog.append(
                    this,
                    "event=analysis cycle_id=${item.cycleId} aci=$aci detections=${detections.size} posted=$posted aci_queued=$aciQueued " +
                        "inference=${if (thermal.paused) "paused_thermal" else "ran"} " +
                        "granted_source=${item.source} input_device=${item.inputDevice} " +
                        "battery_pct=${item.battery.percent} battery_temp_c=${BatteryInfo.formatTemp(item.battery.temperatureC)} charging=${item.battery.state}"
                )
            } catch (error: Throwable) {
                // Errors too (e.g. a missing platform API): never let one cycle kill the process.
                DailyLog.append(this, "event=analysis_error cycle_id=${item.cycleId} error=${error.javaClass.simpleName}")
            }
        }
    }

    private fun toFloatSamples(pcm: ByteArray): FloatArray {
        val samples = FloatArray(pcm.size / 2)
        var index = 0
        var out = 0
        while (index + 1 < pcm.size) {
            samples[out] = ((pcm[index].toInt() and 0xff or (pcm[index + 1].toInt() shl 8)).toShort().toInt()) / 32768f
            index += 2
            out++
        }
        return if (out == samples.size) samples else samples.copyOf(out)
    }

    private fun createAudioRecord(sampleRate: Int, channel: Int, encoding: Int, minimum: Int): AudioRecord? {
        for (source in listOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )) {
            try {
                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channel)
                    .setEncoding(encoding)
                    .build()
                val record = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minimum * 2, 16_384))
                    .build()
                if (record.state == AudioRecord.STATE_INITIALIZED) return record
                record.release()
            } catch (_: UnsupportedOperationException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        return null
    }

    private fun preferredSource(context: Context): Int {
        val packageManager = context.packageManager
        if (Build.VERSION.SDK_INT >= 24) {
            val unprocessed = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
            if (unprocessed) return MediaRecorder.AudioSource.UNPROCESSED
        }
        return MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> "SOURCE_$source"
    }

    private fun activeInputDevice(context: Context): String {
        val manager = getSystemService(AUDIO_SERVICE) as AudioManager
        val devices = if (Build.VERSION.SDK_INT >= 23) manager.getDevices(AudioManager.GET_DEVICES_INPUTS) else emptyArray()
        val active = devices.firstOrNull { it.isSource }
        if (active != null) {
            return when (active.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILT_IN"
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET -> "WIRED_3.5MM_OR_EXTERNAL"
                else -> "EXTERNAL_${active.type}"
            }
        }
        return "UNKNOWN"
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_node)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setOngoing(true)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun AudioRecord.stopSafely() {
        if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
    }

    companion object {
        const val EXTRA_RESTART_CAPTURE = "restart_capture"
        const val EXTRA_DEFER_CAPTURE = "defer_capture"
        const val EXTRA_START_REASON = "start_reason"
        const val CHANNEL_ID = "magora_node_capture"
        const val NOTIFICATION_ID = 1
        const val WHITELIST_WAIT_MILLIS = 5L * 60 * 1000
    }
}
