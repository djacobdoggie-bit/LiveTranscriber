package com.jacob.livetranscriber

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Live, fully offline transcription using Whisper (bundled inside the app).
 *
 * How it works:
 *  1. The mic is read continuously (16 kHz).
 *  2. A small voice detector (Silero VAD) cuts the audio into phrases at natural pauses.
 *  3. Each phrase is transcribed by Whisper on a background thread and appended with a timestamp.
 *  4. On Stop, the remaining phrases are finished and the transcript is saved to
 *     Downloads/LiveTranscriber as a .txt file.
 */
class MainActivity : Activity() {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val REQ_MIC = 1

        private val LANGUAGE_NAMES = arrayOf(
            "Tagalog / Taglish",
            "English",
            "Auto-detect (each phrase)"
        )
        private val LANGUAGE_CODES = arrayOf("tl", "en", "")

        // Phrases Whisper sometimes "hears" in noise or silence.
        private val HALLUCINATIONS = setOf(
            "thanks for watching", "thank you for watching", "thank you so much for watching",
            "please subscribe", "like and subscribe", "subscribe to my channel",
            "salamat sa panonood", "subtitles by the amara.org community", "you"
        )
    }

    private sealed class Job {
        class Segment(val startSample: Long, val samples: FloatArray) : Job()
        object Finish : Job()
        object Quit : Job()
    }

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var languageSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var partialText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var startStopButton: Button
    private lateinit var shareButton: Button

    // Speech engine (created once, on a background thread)
    @Volatile private var recognizer: OfflineRecognizer? = null
    private var recognizerConfig: OfflineRecognizerConfig? = null
    @Volatile private var vad: Vad? = null

    // Session state
    @Volatile private var recording = false
    private var finishing = false
    private var recordThread: Thread? = null
    private val jobs = LinkedBlockingQueue<Job>()
    private val pending = AtomicInteger(0)
    @Volatile private var hearingSpeech = false

    private var languageCode = "tl"
    private var sessionStartMs = 0L
    private var sessionStartDate = Date()
    private val transcript = StringBuilder()
    private var lastSavedFile: Uri? = null

    // -------------------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        languageSpinner = findViewById(R.id.languageSpinner)
        statusText = findViewById(R.id.statusText)
        transcriptText = findViewById(R.id.transcriptText)
        partialText = findViewById(R.id.partialText)
        scroll = findViewById(R.id.scroll)
        startStopButton = findViewById(R.id.startStopButton)
        shareButton = findViewById(R.id.shareButton)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, LANGUAGE_NAMES)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        languageSpinner.adapter = adapter

        startStopButton.isEnabled = false
        startStopButton.setOnClickListener {
            if (recording) stopSession() else startSessionWithPermission()
        }
        shareButton.setOnClickListener { shareLastFile() }

        statusText.text = "Loading speech model… (a few seconds)"
        Thread({ loadModels() }, "model-loader").start()
    }

    private fun loadModels() {
        try {
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.6f,   // pause length that ends a phrase
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 20.0f,   // Whisper handles up to ~30 s per phrase
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            )
            vad = Vad(assets, vadConfig)

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = "whisper/encoder.int8.onnx",
                        decoder = "whisper/decoder.int8.onnx",
                        language = languageCode,
                        task = "transcribe",
                        tailPaddings = 1000,
                    ),
                    tokens = "whisper/tokens.txt",
                    modelType = "whisper",
                    numThreads = 4,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            recognizer = OfflineRecognizer(assets, config)
            recognizerConfig = config

            Thread({ decodeLoop() }, "whisper-decoder").start()

            ui.post {
                statusText.text = "Ready. Works offline. Tap Start to begin."
                startStopButton.isEnabled = true
            }
        } catch (t: Throwable) {
            ui.post { statusText.text = "Couldn't load the speech model: ${t.message}" }
        }
    }

    override fun onDestroy() {
        recording = false
        ui.removeCallbacksAndMessages(null)
        try {
            recordThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        jobs.put(Job.Quit)
        super.onDestroy()
    }

    // -------------------------------------------------------------------------------------
    // Start / stop
    // -------------------------------------------------------------------------------------

    private fun startSessionWithPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startSession()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSession()
            } else {
                statusText.text = "Microphone permission is needed to transcribe."
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSession() {
        val rec = recognizer ?: return
        val v = vad ?: return
        if (finishing) return

        // Apply the chosen language
        languageCode = LANGUAGE_CODES[languageSpinner.selectedItemPosition]
        recognizerConfig?.let { cfg ->
            if (cfg.modelConfig.whisper.language != languageCode) {
                cfg.modelConfig.whisper.language = languageCode
                rec.setConfig(cfg)
            }
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val audio = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, SAMPLE_RATE * 2)
            )
        } catch (t: Throwable) {
            statusText.text = "Couldn't open the microphone: ${t.message}"
            return
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            statusText.text = "Couldn't open the microphone. Is another app using it?"
            return
        }

        v.reset()
        transcript.setLength(0)
        transcriptText.text = ""
        partialText.text = ""
        pending.set(0)
        hearingSpeech = false
        sessionStartMs = System.currentTimeMillis()
        sessionStartDate = Date(sessionStartMs)

        recording = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        languageSpinner.isEnabled = false
        shareButton.isEnabled = false
        startStopButton.text = "Stop"
        startStopButton.backgroundTintList = ColorStateList.valueOf(0xFFB91C1C.toInt())

        recordThread = Thread({ recordLoop(audio, v) }, "mic-reader").also { it.start() }
        ui.post(clockTick)
    }

    private fun stopSession() {
        if (!recording) return
        recording = false
        finishing = true
        startStopButton.isEnabled = false
        startStopButton.text = "Finishing…"
        updateStatus()
        // recordLoop() exits, flushes the last phrase, then queues Job.Finish
    }

    /** Called on the UI thread after every queued phrase has been transcribed. */
    private fun finishSession() {
        finishing = false
        ui.removeCallbacks(clockTick)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        languageSpinner.isEnabled = true
        startStopButton.isEnabled = true
        startStopButton.text = "Start"
        startStopButton.backgroundTintList = ColorStateList.valueOf(0xFF0F766E.toInt())
        partialText.text = ""

        if (transcript.isEmpty()) {
            statusText.text = "Stopped. Nothing was transcribed, so no file was saved."
        } else {
            saveTranscript()
        }
    }

    // -------------------------------------------------------------------------------------
    // Audio capture + voice detection (background thread)
    // -------------------------------------------------------------------------------------

    private fun recordLoop(audio: AudioRecord, v: Vad) {
        val buffer = ShortArray(512)
        try {
            audio.startRecording()
            while (recording) {
                val n = audio.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { buffer[it] / 32768.0f }
                v.acceptWaveform(samples)
                hearingSpeech = v.isSpeechDetected()
                drainVad(v)
            }
        } catch (t: Throwable) {
            ui.post { Toast.makeText(this, "Mic error: ${t.message}", Toast.LENGTH_LONG).show() }
        } finally {
            try {
                audio.stop()
            } catch (_: Throwable) {
            }
            audio.release()
            try {
                v.flush()
                drainVad(v)
            } catch (_: Throwable) {
            }
            hearingSpeech = false
            if (recording) {
                // Loop ended on an error while still "recording": stop cleanly.
                recording = false
                ui.post {
                    finishing = true
                    startStopButton.isEnabled = false
                    startStopButton.text = "Finishing…"
                }
            }
            jobs.put(Job.Finish)
        }
    }

    private fun drainVad(v: Vad) {
        while (!v.empty()) {
            val seg = v.front()
            v.pop()
            // Ignore tiny blips (clicks, coughs)
            if (seg.samples.size >= SAMPLE_RATE * 3 / 10) {
                pending.incrementAndGet()
                jobs.put(Job.Segment(seg.start, seg.samples))
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Whisper transcription (background thread)
    // -------------------------------------------------------------------------------------

    private fun decodeLoop() {
        while (true) {
            when (val job = jobs.take()) {
                is Job.Segment -> {
                    var text = ""
                    try {
                        val rec = recognizer
                        if (rec != null) {
                            val stream = rec.createStream()
                            try {
                                stream.acceptWaveform(job.samples, SAMPLE_RATE)
                                rec.decode(stream)
                                text = rec.getResult(stream).text
                            } finally {
                                stream.release()
                            }
                        }
                    } catch (t: Throwable) {
                        text = ""
                    }
                    pending.decrementAndGet()
                    val clean = cleanUp(text)
                    ui.post {
                        if (clean != null) appendLine(job.startSample, clean)
                        if (finishing) updateStatus()
                    }
                }

                is Job.Finish -> ui.post { finishSession() }

                is Job.Quit -> {
                    try {
                        vad?.release()
                        recognizer?.release()
                    } catch (_: Throwable) {
                    }
                    return
                }
            }
        }
    }

    private fun cleanUp(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.none { it.isLetterOrDigit() }) return null
        if ((s.startsWith("[") && s.endsWith("]")) || (s.startsWith("(") && s.endsWith(")"))) {
            return null // e.g. [Music], (applause)
        }
        val key = s.lowercase(Locale.ROOT).trim(' ', '.', '!', '?', ',', '…')
        if (key in HALLUCINATIONS) return null
        return s
    }

    // -------------------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------------------

    private fun appendLine(startSample: Long, text: String) {
        val offsetMs = startSample * 1000L / SAMPLE_RATE
        if (transcript.isNotEmpty()) transcript.append('\n')
        transcript.append('[').append(formatElapsed(offsetMs)).append("] ").append(text)
        transcriptText.text = transcript
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun languageLabel(): String =
        LANGUAGE_NAMES[LANGUAGE_CODES.indexOf(languageCode).coerceAtLeast(0)]

    private fun updateStatus() {
        val n = pending.get()
        if (recording) {
            val elapsed = formatElapsed(System.currentTimeMillis() - sessionStartMs)
            statusText.text = "● Recording  $elapsed   ·   ${languageLabel()}   ·   offline"
            partialText.text = when {
                hearingSpeech -> "🎙 hearing speech…"
                n > 0 -> "✍ transcribing ($n)…"
                else -> ""
            }
        } else if (finishing) {
            statusText.text = if (n > 0) "Finishing the last $n phrase(s)…" else "Saving…"
            partialText.text = ""
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            if (!recording) return
            updateStatus()
            ui.postDelayed(this, 500)
        }
    }

    private fun formatElapsed(ms: Long): String {
        val total = max(0L, ms / 1000)
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    // -------------------------------------------------------------------------------------
    // Saving and sharing
    // -------------------------------------------------------------------------------------

    private fun saveTranscript() {
        val end = Date()
        val fileFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val humanFmt = SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.US)
        val fileName = "transcript_${fileFmt.format(sessionStartDate)}.txt"

        val content = buildString {
            append("Live Transcript\n")
            append("Started:  ${humanFmt.format(sessionStartDate)}\n")
            append("Ended:    ${humanFmt.format(end)}\n")
            append("Duration: ${formatElapsed(end.time - sessionStartDate.time)}\n")
            append("Language: ${languageLabel()}\n")
            append("Engine:   Whisper small (on-device, offline)\n\n")
            append(transcript).append('\n')
        }

        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/LiveTranscriber"
                )
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create file")
            contentResolver.openOutputStream(uri).use { out ->
                if (out == null) throw IllegalStateException("Could not open file")
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            lastSavedFile = uri
            shareButton.isEnabled = true
            statusText.text = "Saved: Downloads/LiveTranscriber/$fileName"
            Toast.makeText(this, "Transcript saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            statusText.text = "Couldn't save the file (${e.message}). You can still copy the text above."
        }
    }

    private fun shareLastFile() {
        val uri = lastSavedFile ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Transcript")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share transcript"))
    }
}
