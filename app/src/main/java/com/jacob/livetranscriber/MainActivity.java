package com.jacob.livetranscriber;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Live, offline speech-to-text.
 *
 * Tap Start: the phone's own speech engine listens continuously and shows text live.
 * Tap Stop: the transcript is saved as a .txt file in Downloads/LiveTranscriber.
 *
 * Android's recognizer ends each "utterance" after a pause, so the app restarts it
 * automatically to keep listening until you press Stop.
 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 1;

    // Error codes added in newer Android versions (written as numbers so older phones are fine)
    private static final int ERROR_TOO_MANY_REQUESTS = 10;
    private static final int ERROR_SERVER_DISCONNECTED = 11;
    private static final int ERROR_LANGUAGE_NOT_SUPPORTED = 12;
    private static final int ERROR_LANGUAGE_UNAVAILABLE = 13;

    private static final String[] LANGUAGE_NAMES = {
            "Filipino / Taglish (fil-PH)",
            "English – Philippines (en-PH)",
            "English – US (en-US)"
    };
    private static final String[] LANGUAGE_CODES = {"fil-PH", "en-PH", "en-US"};

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Spinner languageSpinner;
    private TextView statusText;
    private TextView transcriptText;
    private TextView partialText;
    private ScrollView scroll;
    private Button startStopButton;
    private Button shareButton;

    private SpeechRecognizer recognizer;
    private boolean running = false;
    private boolean onDevice = false;
    private boolean forceStandard = false;
    private String languageCode = "fil-PH";

    private long sessionStartElapsed;
    private Date sessionStartDate;
    private long segmentStartElapsed = -1;
    private int consecutiveErrors = 0;

    private final StringBuilder transcript = new StringBuilder();
    private String pendingPartial = "";
    private Uri lastSavedFile;

    // ---------------------------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        languageSpinner = findViewById(R.id.languageSpinner);
        statusText = findViewById(R.id.statusText);
        transcriptText = findViewById(R.id.transcriptText);
        partialText = findViewById(R.id.partialText);
        scroll = findViewById(R.id.scroll);
        startStopButton = findViewById(R.id.startStopButton);
        shareButton = findViewById(R.id.shareButton);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, LANGUAGE_NAMES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);

        startStopButton.setOnClickListener(v -> {
            if (running) stopSession();
            else startSessionWithPermission();
        });
        shareButton.setOnClickListener(v -> shareLastFile());

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.setText("No speech engine found on this phone. Install or update the "
                    + "Google app (or 'Speech Recognition & Synthesis by Google') and try again.");
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------------
    // Start / stop
    // ---------------------------------------------------------------------------------------

    private void startSessionWithPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startSession();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSession();
            } else {
                statusText.setText("Microphone permission is needed to transcribe.");
            }
        }
    }

    private void startSession() {
        languageCode = LANGUAGE_CODES[languageSpinner.getSelectedItemPosition()];
        forceStandard = false;

        destroyRecognizer();
        createRecognizer();
        if (recognizer == null) {
            statusText.setText("Couldn't start the speech engine on this phone.");
            return;
        }

        transcript.setLength(0);
        pendingPartial = "";
        transcriptText.setText("");
        partialText.setText("");
        consecutiveErrors = 0;
        segmentStartElapsed = -1;
        sessionStartElapsed = SystemClock.elapsedRealtime();
        sessionStartDate = new Date();

        running = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        languageSpinner.setEnabled(false);
        shareButton.setEnabled(false);
        startStopButton.setText("Stop");
        startStopButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFFB91C1C));

        handler.post(clockTick);
        listen();
    }

    private void stopSession() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        commitPendingPartial();
        destroyRecognizer();

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        languageSpinner.setEnabled(true);
        startStopButton.setText("Start");
        startStopButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF0F766E));
        partialText.setText("");

        if (transcript.length() == 0) {
            statusText.setText("Stopped. Nothing was transcribed, so no file was saved.");
            return;
        }
        saveTranscript();
    }

    // ---------------------------------------------------------------------------------------
    // Speech recognizer
    // ---------------------------------------------------------------------------------------

    private void createRecognizer() {
        onDevice = false;
        try {
            if (!forceStandard && Build.VERSION.SDK_INT >= 31
                    && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                // Fully on-device: no internet needed.
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                onDevice = true;
            } else {
                // Standard engine, asked to prefer its offline language pack.
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            }
        } catch (Exception e) {
            recognizer = null;
            return;
        }
        recognizer.setRecognitionListener(listener);
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
            }
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
    }

    private Intent buildRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageCode);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // Ask the engine to wait a little longer before deciding a sentence is finished.
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L);
        return intent;
    }

    private void listen() {
        if (!running || recognizer == null) return;
        try {
            recognizer.startListening(buildRecognizerIntent());
        } catch (Exception e) {
            restartAfter(500, true);
        }
    }

    /** Restart listening after a short delay; optionally rebuild the recognizer first. */
    private void restartAfter(long delayMs, boolean rebuild) {
        handler.postDelayed(() -> {
            if (!running) return;
            if (rebuild) {
                destroyRecognizer();
                createRecognizer();
            }
            listen();
        }, delayMs);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            consecutiveErrors = 0;
        }

        @Override
        public void onBeginningOfSpeech() {
            segmentStartElapsed = SystemClock.elapsedRealtime();
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            String text = firstResult(partialResults);
            if (text != null && !text.trim().isEmpty()) {
                if (segmentStartElapsed < 0) segmentStartElapsed = SystemClock.elapsedRealtime();
                pendingPartial = text.trim();
                partialText.setText(pendingPartial);
                scrollToBottom();
            }
        }

        @Override
        public void onResults(Bundle results) {
            String text = firstResult(results);
            if (text != null && !text.trim().isEmpty()) {
                pendingPartial = "";
                appendLine(text.trim());
            } else {
                commitPendingPartial();
            }
            partialText.setText("");
            segmentStartElapsed = -1;
            if (running) restartAfter(50, false);
        }

        @Override
        public void onError(int error) {
            if (!running) return;
            // Don't lose words that were shown live but not finalized.
            commitPendingPartial();
            partialText.setText("");
            segmentStartElapsed = -1;

            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    // Just silence. Keep listening.
                    restartAfter(50, false);
                    return;

                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    stopWithMessage("Microphone permission was denied.");
                    return;

                case ERROR_LANGUAGE_NOT_SUPPORTED:
                case ERROR_LANGUAGE_UNAVAILABLE:
                    handleLanguageUnavailable();
                    return;

                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                case ERROR_SERVER_DISCONNECTED:
                    consecutiveErrors++;
                    if (consecutiveErrors >= 4) {
                        stopWithMessage("The offline language pack for " + languageCode
                                + " doesn't seem to be installed. See the setup tips,"
                                + " or try another language.");
                    } else {
                        restartAfter(1000, true);
                    }
                    return;

                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                case ERROR_TOO_MANY_REQUESTS:
                case SpeechRecognizer.ERROR_CLIENT:
                default:
                    consecutiveErrors++;
                    if (consecutiveErrors >= 10) {
                        stopWithMessage("The speech engine keeps failing (error " + error
                                + "). Stopped and saved what was captured.");
                    } else {
                        restartAfter(400, true);
                    }
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };

    private void handleLanguageUnavailable() {
        if (onDevice) {
            // The fully on-device engine doesn't have this language yet.
            if (Build.VERSION.SDK_INT >= 33 && recognizer != null) {
                try {
                    // Ask Android to download it for next time (needs internet once).
                    recognizer.triggerModelDownload(buildRecognizerIntent());
                } catch (Exception ignored) {
                }
            }
            Toast.makeText(this, "On-device " + languageCode + " isn't installed yet. "
                    + "Switching to the phone's regular speech engine.", Toast.LENGTH_LONG).show();
            forceStandard = true;
            restartAfter(300, true);
            return;
        }
        stopWithMessage("Offline " + languageCode + " isn't available on this phone. "
                + "Install it in the phone's offline speech settings, or pick another language.");
    }

    /** Stop and keep an explanation visible (saving a file would otherwise replace it). */
    private void stopWithMessage(String message) {
        stopSession();
        if (transcript.length() == 0) statusText.setText(message);
        else Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String firstResult(Bundle bundle) {
        if (bundle == null) return null;
        ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return null;
        return list.get(0);
    }

    // ---------------------------------------------------------------------------------------
    // Transcript handling
    // ---------------------------------------------------------------------------------------

    private void commitPendingPartial() {
        if (!pendingPartial.isEmpty()) {
            appendLine(pendingPartial);
            pendingPartial = "";
        }
    }

    private void appendLine(String text) {
        long at = segmentStartElapsed > 0 ? segmentStartElapsed : SystemClock.elapsedRealtime();
        String line = "[" + formatElapsed(at - sessionStartElapsed) + "] " + text;
        if (transcript.length() > 0) transcript.append('\n');
        transcript.append(line);
        transcriptText.setText(transcript);
        scrollToBottom();
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private static String formatElapsed(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d:%02d",
                totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60);
    }

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            statusText.setText("● Recording  " + formatElapsed(
                    SystemClock.elapsedRealtime() - sessionStartElapsed)
                    + "   ·   " + languageCode
                    + "   ·   " + (onDevice ? "on-device (offline)" : "phone speech engine"));
            handler.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------------------------------
    // Saving and sharing
    // ---------------------------------------------------------------------------------------

    private void saveTranscript() {
        Date end = new Date();
        SimpleDateFormat fileFmt = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        SimpleDateFormat humanFmt = new SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.US);
        String fileName = "transcript_" + fileFmt.format(sessionStartDate) + ".txt";

        String content = "Live Transcript\n"
                + "Started:  " + humanFmt.format(sessionStartDate) + "\n"
                + "Ended:    " + humanFmt.format(end) + "\n"
                + "Duration: " + formatElapsed(end.getTime() - sessionStartDate.getTime()) + "\n"
                + "Language: " + languageCode + "\n"
                + "Engine:   " + (onDevice ? "on-device (offline)" : "phone speech engine") + "\n"
                + "\n"
                + transcript + "\n";

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/LiveTranscriber");
            Uri uri = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Could not create file");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Could not open file");
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            lastSavedFile = uri;
            shareButton.setEnabled(true);
            statusText.setText("Saved: Downloads/LiveTranscriber/" + fileName);
            Toast.makeText(this, "Transcript saved", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            statusText.setText("Couldn't save the file (" + e.getMessage()
                    + "). You can still copy the text above.");
        }
    }

    private void shareLastFile() {
        if (lastSavedFile == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_STREAM, lastSavedFile);
        send.putExtra(Intent.EXTRA_SUBJECT, "Transcript");
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share transcript"));
    }
}
