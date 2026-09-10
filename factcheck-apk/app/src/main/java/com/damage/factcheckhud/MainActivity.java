package com.damage.factcheckhud;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int MIC_PERMISSION = 41;
    private static final String PREFS = "factcheck";
    private static final String KEY_API = "gemini_api_key";
    private static final String MODEL = "gemini-3.8-flash";
    private static final String API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/" +
        MODEL + ":generateContent";
    private static final int GREEN = Color.rgb(64, 255, 94);
    private static final int DIM_GREEN = Color.rgb(36, 160, 55);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private LinearLayout root;
    private TextView statusView;
    private TextView transcriptView;
    private TextView verdictView;
    private TextView detailView;
    private TextView footerView;

    private SpeechRecognizer recognizer;
    private boolean sessionActive = false;
    private boolean checking = false;
    private long sessionToken = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        buildHud();

        root.setOnClickListener(v -> {
            if (apiKey().isEmpty()) {
                showApiKeyDialog();
            } else if (sessionActive) {
                stopSession();
            } else {
                ensurePermissionAndStart();
            }
        });
        root.setOnLongClickListener(v -> {
            showApiKeyDialog();
            return true;
        });

        if (apiKey().isEmpty()) {
            showNeedsKey();
        } else {
            showReady();
        }
    }

    private void buildHud() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(16));
        root.setBackgroundColor(Color.BLACK);

        TextView title = text("FACTCHECK HUD", 19, true, Gravity.CENTER);
        root.addView(title, params(-1, -2, 0f, 0));

        statusView = text("● READY", 14, true, Gravity.CENTER);
        root.addView(statusView, params(-1, -2, 0f, 8));

        transcriptView = text("Tap to begin.", 16, false, Gravity.START);
        transcriptView.setMinLines(4);
        root.addView(transcriptView, params(-1, 0, 1f, 18));

        verdictView = text("", 25, true, Gravity.CENTER);
        verdictView.setMinLines(2);
        root.addView(verdictView, params(-1, -2, 0f, 12));

        detailView = text("", 15, false, Gravity.CENTER);
        detailView.setMinLines(2);
        root.addView(detailView, params(-1, -2, 0f, 8));

        footerView = text("TAP TO START • HOLD FOR SETUP", 11, false, Gravity.CENTER);
        footerView.setTextColor(DIM_GREEN);
        root.addView(footerView, params(-1, -2, 0f, 8));

        setContentView(root);
    }

    private TextView text(String value, int sp, boolean bold, int gravity) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(GREEN);
        view.setGravity(gravity);
        view.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        view.setLineSpacing(0f, 1.08f);
        return view;
    }

    private LinearLayout.LayoutParams params(
        int width,
        int height,
        float weight,
        int topDp
    ) {
        LinearLayout.LayoutParams result =
            new LinearLayout.LayoutParams(width, height, weight);
        result.topMargin = dp(topDp);
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String apiKey() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_API, "")
            .trim();
    }

    private void showNeedsKey() {
        statusView.setText("● SETUP REQUIRED");
        transcriptView.setText(
            "Native APK port is installed.\n\n" +
            "Tap to enter a Gemini API key for fact-checking."
        );
        verdictView.setText("[?] API KEY NEEDED");
        detailView.setText("The key is stored only in this app's private preferences.");
        footerView.setText("TAP FOR KEY • HOLD TO CHANGE LATER");
    }

    private void showReady() {
        statusView.setText("● READY");
        transcriptView.setText("Tap once to begin listening for factual claims.");
        verdictView.setText("");
        detailView.setText("");
        footerView.setText("TAP TO START • HOLD FOR SETUP");
    }

    private void showApiKeyDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Gemini API key");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setInputType(
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );

        String existing = apiKey();
        if (!existing.isEmpty()) {
            input.setText(existing);
            input.setSelection(existing.length());
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("FactCheck HUD setup")
            .setMessage("Enter the Gemini API key used for live fact-checking.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear", null)
            .setPositiveButton("Save", null)
            .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String key = input.getText().toString().trim();
                if (key.isEmpty()) {
                    Toast.makeText(this, "Enter an API key.", Toast.LENGTH_SHORT).show();
                    return;
                }
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_API, key)
                    .apply();
                dialog.dismiss();
                showReady();
            });

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                stopSession();
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .remove(KEY_API)
                    .apply();
                dialog.dismiss();
                showNeedsKey();
            });
        });

        dialog.show();
    }

    private void ensurePermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startSession();
        } else {
            requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                MIC_PERMISSION
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MIC_PERMISSION) {
            return;
        }

        if (grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startSession();
        } else {
            statusView.setText("● MICROPHONE DENIED");
            verdictView.setText("[?] CANNOT LISTEN");
            detailView.setText("Microphone permission is required.");
        }
    }

    private void startSession() {
        if (apiKey().isEmpty()) {
            showNeedsKey();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.setText("● SPEECH SERVICE MISSING");
            transcriptView.setText(
                "Android did not expose a speech-recognition service on this device."
            );
            verdictView.setText("[?] UNAVAILABLE");
            detailView.setText("This build needs a Rokid-specific speech fallback.");
            footerView.setText("TAP TO RETRY • HOLD FOR SETUP");
            return;
        }

        sessionToken++;
        sessionActive = true;
        checking = false;

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new HudRecognitionListener());
        }

        startRecognizer();
    }

    private void stopSession() {
        sessionToken++;
        sessionActive = false;
        checking = false;
        main.removeCallbacksAndMessages(null);

        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Throwable ignored) {
            }
        }

        statusView.setText("● PAUSED");
        verdictView.setText("");
        detailView.setText("");
        footerView.setText("TAP TO RESUME • HOLD FOR SETUP");
    }

    private void startRecognizer() {
        if (!sessionActive || checking || recognizer == null) {
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

        statusView.setText("● LISTENING");
        transcriptView.setText("Waiting for a factual claim…");
        verdictView.setText("");
        detailView.setText("");
        footerView.setText("TAP TO PAUSE • HOLD FOR SETUP");

        try {
            recognizer.startListening(intent);
        } catch (Throwable error) {
            showRecognizerError(error.getMessage());
            scheduleRestart(900L);
        }
    }

    private void scheduleRestart(long delayMs) {
        final long token = sessionToken;
        main.postDelayed(() -> {
            if (sessionActive && !checking && token == sessionToken) {
                startRecognizer();
            }
        }, delayMs);
    }

    private void checkClaim(String claim) {
        if (claim == null || claim.trim().isEmpty()) {
            scheduleRestart(350L);
            return;
        }

        checking = true;
        final long token = sessionToken;
        final String cleanClaim = claim.trim();

        statusView.setText("● CHECKING");
        transcriptView.setText("“" + cleanClaim + "”");
        verdictView.setText("…");
        detailView.setText("Checking current sources with Google Search…");
        footerView.setText("TAP TO PAUSE");

        worker.execute(() -> {
            try {
                String raw = callGemini(cleanClaim);
                Verdict result = parseVerdict(raw);

                main.post(() -> {
                    if (!sessionActive || token != sessionToken) {
                        return;
                    }

                    statusView.setText("● RESULT");
                    transcriptView.setText("“" + cleanClaim + "”");
                    verdictView.setText(result.displayVerdict());
                    detailView.setText(result.explanation);
                    footerView.setText("RESUMING LISTENING…");

                    main.postDelayed(() -> {
                        if (sessionActive && token == sessionToken) {
                            checking = false;
                            startRecognizer();
                        }
                    }, 2200L);
                });
            } catch (Throwable error) {
                main.post(() -> {
                    if (!sessionActive || token != sessionToken) {
                        return;
                    }

                    checking = false;
                    statusView.setText("● CHECK FAILED");
                    verdictView.setText("[?] UNVERIFIED");
                    detailView.setText(compact(
                        error.getMessage() == null
                            ? error.getClass().getSimpleName()
                            : error.getMessage(),
                        150
                    ));
                    footerView.setText("RETRYING…");
                    scheduleRestart(1200L);
                });
            }
        });
    }

    private String callGemini(String claim) throws Exception {
        String prompt =
            "Fact-check this spoken claim: \"" + claim + "\". " +
            "Use Google Search whenever it can improve accuracy or freshness. " +
            "Return exactly one line with two pipe-separated fields: " +
            "STATUS|brief explanation. STATUS must be VERIFIED, CONTRADICTED, " +
            "or UNVERIFIED. VERIFIED means supported by reliable evidence; " +
            "CONTRADICTED means reliable evidence shows it is false; " +
            "UNVERIFIED means evidence is insufficient, ambiguous, opinion, " +
            "prediction, or not a checkable factual claim. Keep the explanation " +
            "under 14 words and do not use the | character in it.";

        JSONObject payload = new JSONObject()
            .put("contents", new JSONArray().put(
                new JSONObject().put("parts", new JSONArray().put(
                    new JSONObject().put("text", prompt)
                ))
            ))
            .put("tools", new JSONArray().put(
                new JSONObject().put("google_search", new JSONObject())
            ))
            .put("generationConfig", new JSONObject()
                .put("temperature", 0.1)
                .put("maxOutputTokens", 120)
                .put("thinkingConfig", new JSONObject().put("thinkingLevel", "low"))
            );

        HttpURLConnection connection =
            (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("x-goog-api-key", apiKey());

        byte[] request =
            payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(request.length);
        connection.getOutputStream().write(request);

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
        String body = readFully(stream);
        connection.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException(
                "Gemini HTTP " + code + ": " + compact(body, 170)
            );
        }

        JSONObject root = new JSONObject(body);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IllegalStateException("Gemini returned no result.");
        }

        JSONObject content =
            candidates.getJSONObject(0).optJSONObject("content");
        if (content == null) {
            throw new IllegalStateException("Gemini response had no content.");
        }

        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) {
            throw new IllegalStateException("Gemini response had no text.");
        }

        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            String piece = parts.getJSONObject(i).optString("text", "");
            if (!piece.isEmpty()) {
                if (answer.length() > 0) {
                    answer.append(' ');
                }
                answer.append(piece);
            }
        }

        if (answer.length() == 0) {
            throw new IllegalStateException("Gemini returned an empty result.");
        }

        return answer.toString().trim();
    }

    private String readFully(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }

        try (InputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private Verdict parseVerdict(String raw) {
        String cleaned = raw
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace("```", "")
            .trim();

        String[] fields = cleaned.split("\\|", 2);
        if (fields.length < 2) {
            return new Verdict("UNVERIFIED", compact(cleaned, 110));
        }

        String status = fields[0].trim().toUpperCase(Locale.US);
        if (!status.equals("VERIFIED")
            && !status.equals("CONTRADICTED")
            && !status.equals("UNVERIFIED")) {
            status = "UNVERIFIED";
        }

        return new Verdict(status, compact(fields[1], 120));
    }

    private String compact(String text, int maxLength) {
        if (text == null) {
            return "";
        }

        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }

        return normalized.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private void showRecognizerError(String message) {
        statusView.setText("● SPEECH ERROR");
        verdictView.setText("[?] UNVERIFIED");
        detailView.setText(compact(
            message == null ? "Speech recognition failed." : message,
            140
        ));
        footerView.setText("RETRYING…");
    }

    @Override
    protected void onPause() {
        if (sessionActive) {
            stopSession();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sessionActive = false;
        sessionToken++;
        main.removeCallbacksAndMessages(null);

        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Throwable ignored) {
            }
            recognizer = null;
        }

        worker.shutdownNow();
        super.onDestroy();
    }

    private final class HudRecognitionListener implements RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle params) {
            statusView.setText("● LISTENING");
        }

        @Override
        public void onBeginningOfSpeech() {
            statusView.setText("● HEARING CLAIM");
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
            statusView.setText("● PROCESSING SPEECH");
        }

        @Override
        public void onError(int error) {
            if (!sessionActive || checking) {
                return;
            }

            if (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                scheduleRestart(350L);
                return;
            }

            showRecognizerError("Speech recognizer error " + error);
            scheduleRestart(900L);
        }

        @Override
        public void onResults(Bundle results) {
            if (!sessionActive || checking) {
                return;
            }

            ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

            if (matches == null || matches.isEmpty()) {
                scheduleRestart(350L);
                return;
            }

            checkClaim(matches.get(0));
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            if (!sessionActive || checking) {
                return;
            }

            ArrayList<String> matches =
                partialResults.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                );

            if (matches != null && !matches.isEmpty()) {
                transcriptView.setText(matches.get(0));
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    }

    private static final class Verdict {
        final String status;
        final String explanation;

        Verdict(String status, String explanation) {
            this.status = status;
            this.explanation = explanation;
        }

        String displayVerdict() {
            switch (status) {
                case "VERIFIED":
                    return "[✓] VERIFIED";
                case "CONTRADICTED":
                    return "[X] CONTRADICTED";
                default:
                    return "[?] UNVERIFIED";
            }
        }
    }
}
