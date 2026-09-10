package com.damage.factcheckhud;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.rokid.security.glass3.open.sdk.GlassSdk;
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback;
import com.rokid.security.system.server.IClientCallback;
import com.rokid.security.system.server.aichat.listener.AiChatListener;
import com.rokid.security.system.server.asr.listener.SpeechCallback;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int MIC_PERMISSION = 41;
    private static final String CLIENT_ID = "FactCheckHUD";
    private static final int GREEN = Color.rgb(64, 255, 94);
    private static final int DIM_GREEN = Color.rgb(36, 160, 55);
    private static final long MIC_SCENE_SETTLE_MS = 3200L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder aiBuffer = new StringBuilder();

    private LinearLayout root;
    private TextView statusView;
    private TextView transcriptView;
    private TextView verdictView;
    private TextView detailView;
    private TextView footerView;

    private boolean sdkReady = false;
    private boolean sessionActive = false;
    private boolean checking = false;
    private long sessionToken = 0L;

    private final IClientCallback clientCallback = new IClientCallback.Stub() {
        @Override
        public void onReady() {
            main.post(() -> {
                sdkReady = true;
                if (!sessionActive) {
                    showReady();
                }
            });
        }
    };

    private final IServiceConnectionCallback sdkConnectionCallback =
        new IServiceConnectionCallback() {
            @Override
            public void onServiceConnected() {
                try {
                    GlassSdk.registerClient(CLIENT_ID, clientCallback);
                } catch (Throwable error) {
                    showSdkError("Rokid client registration failed: " + safeMessage(error));
                }
            }

            @Override
            public void onServiceDisconnected() {
                sdkReady = false;
                showSdkError("Rokid system service disconnected.");
            }

            @Override
            public void onBindingDied() {
                sdkReady = false;
                showSdkError("Rokid system service binding died. Tap to retry.");
            }
        };

    private final SpeechCallback speechCallback = new SpeechCallback.Stub() {
        @Override
        public void onStart() {
            main.post(() -> {
                if (sessionActive && !checking) {
                    statusView.setText("● LISTENING");
                    footerView.setText("ROKID ASR • TAP TO PAUSE");
                }
            });
        }

        @Override
        public void onIntermediateVad(String content) {
            main.post(() -> {
                if (!sessionActive || checking) {
                    return;
                }
                String clean = cleanText(content);
                if (!clean.isEmpty()) {
                    statusView.setText("● HEARING CLAIM");
                    transcriptView.setText("“" + clean + "”");
                }
            });
        }

        @Override
        public void onAsrComplete(String content) {
            main.post(() -> handleFinalSpeech(content));
        }

        @Override
        public void onError(int code) {
            main.post(() -> {
                if (!sessionActive || checking) {
                    return;
                }
                statusView.setText("● ASR RETRY");
                detailView.setText("Rokid ASR error " + code + ". Retrying…");
                scheduleAsrRestart(900L);
            });
        }

        @Override
        public void onServiceConnectState(boolean connect) {
            main.post(() -> {
                if (!sessionActive || checking) {
                    return;
                }
                if (connect) {
                    statusView.setText("● ROKID ASR CONNECTED");
                } else {
                    statusView.setText("● ASR OFFLINE");
                    detailView.setText("Rokid speech service is not connected.");
                }
            });
        }

        @Override
        public void onAsrCompleteWithIntent(String content, int intent, String intentJson) {
            main.post(() -> handleFinalSpeech(content));
        }
    };

    private final AiChatListener aiChatListener = new AiChatListener.Stub() {
        @Override
        public void onContinuousModeUpdate(
            boolean continuousMode,
            long timeout,
            boolean keepSessionActive
        ) {
            // FactCheck HUD controls its own continuous ASR loop.
        }

        @Override
        public void onAiChatAnswer(
            String answer,
            boolean isFinish,
            String contentType,
            String sessionId
        ) {
            main.post(() -> {
                if (!sessionActive || !checking) {
                    return;
                }

                appendAiChunk(answer);
                if (isFinish) {
                    finishFactCheck(aiBuffer.toString());
                }
            });
        }

        @Override
        public void onError(int code, String message) {
            main.post(() -> {
                if (!sessionActive) {
                    return;
                }
                checking = false;
                statusView.setText("● ROKID AI ERROR");
                verdictView.setText("[?] UNVERIFIED");
                detailView.setText(
                    "Rokid AI error " + code + ": " + compact(cleanText(message), 100)
                );
                footerView.setText("RETRYING LISTENING…");
                scheduleAsrRestart(1400L);
            });
        }

        @Override
        public void onAiTakePhoto(String filePath) {
            // This app never requests camera/vision actions.
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        buildHud();
        root.setOnClickListener(v -> {
            if (sessionActive) {
                stopSession();
            } else {
                ensurePermissionAndStart();
            }
        });

        showConnecting();
        initRokidSdk();
    }

    private void buildHud() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(16));
        root.setBackgroundColor(Color.BLACK);

        TextView title = text("FACTCHECK HUD", 19, true, Gravity.CENTER);
        root.addView(title, params(-1, -2, 0f, 0));

        statusView = text("● CONNECTING", 14, true, Gravity.CENTER);
        root.addView(statusView, params(-1, -2, 0f, 8));

        transcriptView = text("Connecting to Rokid services…", 16, false, Gravity.START);
        transcriptView.setMinLines(4);
        root.addView(transcriptView, params(-1, 0, 1f, 18));

        verdictView = text("", 25, true, Gravity.CENTER);
        verdictView.setMinLines(2);
        root.addView(verdictView, params(-1, -2, 0f, 12));

        detailView = text("", 15, false, Gravity.CENTER);
        detailView.setMinLines(2);
        root.addView(detailView, params(-1, -2, 0f, 8));

        footerView = text("ROKID NATIVE AI", 11, false, Gravity.CENTER);
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

    private void initRokidSdk() {
        try {
            if (GlassSdk.isReady()) {
                GlassSdk.registerClient(CLIENT_ID, clientCallback);
            } else {
                GlassSdk.bindSecurityService(getApplicationContext(), sdkConnectionCallback);
            }
        } catch (Throwable error) {
            showSdkError("Rokid SDK initialization failed: " + safeMessage(error));
        }
    }

    private void showConnecting() {
        statusView.setText("● CONNECTING");
        transcriptView.setText("Connecting to Rokid ASR + AI Chat…");
        verdictView.setText("");
        detailView.setText("No external API key is used.");
        footerView.setText("ROKID NATIVE AI");
    }

    private void showReady() {
        statusView.setText("● READY");
        transcriptView.setText("Tap once to listen for a factual claim.");
        verdictView.setText("");
        detailView.setText("Rokid ASR → Rokid AI Chat");
        footerView.setText("TAP TO START");
    }

    private void showSdkError(String message) {
        main.post(() -> {
            statusView.setText("● ROKID SDK UNAVAILABLE");
            transcriptView.setText("Could not connect to the built-in Rokid service.");
            verdictView.setText("[?] ROKID SERVICE");
            detailView.setText(compact(message, 130));
            footerView.setText("TAP TO RETRY");
        });
    }

    private void ensurePermissionAndStart() {
        if (!sdkReady) {
            showConnecting();
            initRokidSdk();
            return;
        }

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
        try {
            if (GlassSdk.getGlassAsrService() == null) {
                showSdkError("Rokid ASR service is not exposed on this firmware.");
                return;
            }
            if (GlassSdk.getGlassAiChatService() == null) {
                showSdkError("Rokid AI Chat service is not exposed on this firmware.");
                return;
            }
        } catch (Throwable error) {
            showSdkError(safeMessage(error));
            return;
        }

        sessionToken++;
        sessionActive = true;
        checking = false;
        aiBuffer.setLength(0);

        statusView.setText("● SETTING MIC");
        transcriptView.setText("Preparing Rokid omnidirectional conversation microphone…");
        verdictView.setText("");
        detailView.setText("Listening will begin automatically.");
        footerView.setText("TAP TO PAUSE");

        try {
            if (GlassSdk.getGlassDeviceService() != null) {
                GlassSdk.getGlassDeviceService().switchMicScene(3);
            }
            GlassSdk.getGlassAiChatService().startAiChat(false);
        } catch (Throwable error) {
            showSdkError("Could not start Rokid voice/AI service: " + safeMessage(error));
            sessionActive = false;
            return;
        }

        final long token = sessionToken;
        main.postDelayed(() -> {
            if (sessionActive && !checking && token == sessionToken) {
                startRokidAsr();
            }
        }, MIC_SCENE_SETTLE_MS);
    }

    private void stopSession() {
        sessionToken++;
        sessionActive = false;
        checking = false;
        main.removeCallbacksAndMessages(null);

        try {
            if (GlassSdk.getGlassAsrService() != null) {
                GlassSdk.getGlassAsrService().stopSpeech();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (GlassSdk.getGlassAiChatService() != null) {
                GlassSdk.getGlassAiChatService().endAiChat();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (GlassSdk.getGlassDeviceService() != null) {
                GlassSdk.getGlassDeviceService().switchMicScene(0);
            }
        } catch (Throwable ignored) {
        }

        statusView.setText("● PAUSED");
        transcriptView.setText("Fact checking is paused.");
        verdictView.setText("");
        detailView.setText("Rokid microphone scene restored to wearer-focused mode.");
        footerView.setText("TAP TO RESUME");
    }

    private void startRokidAsr() {
        if (!sessionActive || checking) {
            return;
        }

        try {
            if (GlassSdk.getGlassAsrService() == null) {
                showSdkError("Rokid ASR service became unavailable.");
                return;
            }

            GlassSdk.getGlassAsrService().stopSpeech();
            statusView.setText("● CONNECTING ASR");
            transcriptView.setText("Waiting for a factual claim…");
            verdictView.setText("");
            detailView.setText("");
            footerView.setText("ROKID ASR • TAP TO PAUSE");
            GlassSdk.getGlassAsrService().startSpeech(speechCallback);
        } catch (SecurityException error) {
            statusView.setText("● ROKID AUTH REQUIRED");
            verdictView.setText("[?] ASR AUTH");
            detailView.setText("Check the Rokid/Lingmou account and system authorization.");
            footerView.setText("TAP TO PAUSE");
        } catch (Throwable error) {
            statusView.setText("● ASR ERROR");
            detailView.setText(compact(safeMessage(error), 120));
            scheduleAsrRestart(1000L);
        }
    }

    private void scheduleAsrRestart(long delayMs) {
        final long token = sessionToken;
        main.postDelayed(() -> {
            if (sessionActive && !checking && token == sessionToken) {
                startRokidAsr();
            }
        }, delayMs);
    }

    private void handleFinalSpeech(String content) {
        if (!sessionActive || checking) {
            return;
        }

        String claim = cleanText(content);
        if (claim.isEmpty()) {
            scheduleAsrRestart(350L);
            return;
        }

        checking = true;
        try {
            if (GlassSdk.getGlassAsrService() != null) {
                GlassSdk.getGlassAsrService().stopSpeech();
            }
        } catch (Throwable ignored) {
        }
        checkClaim(claim);
    }

    private void checkClaim(String claim) {
        aiBuffer.setLength(0);
        statusView.setText("● CHECKING");
        transcriptView.setText("“" + claim + "”");
        verdictView.setText("…");
        detailView.setText("Checking with Rokid AI…");
        footerView.setText("TAP TO PAUSE");

        String prompt =
            "You are a concise live fact checker. Fact-check this spoken claim: \"" +
            claim +
            "\". Use current information and online capabilities available to you when " +
            "needed. Return exactly one line: STATUS|brief explanation. STATUS must be " +
            "VERIFIED, CONTRADICTED, or UNVERIFIED. VERIFIED means reliable evidence " +
            "supports the claim. CONTRADICTED means reliable evidence shows it is false. " +
            "UNVERIFIED means evidence is insufficient, ambiguous, opinion, prediction, " +
            "or not a checkable factual claim. Keep the explanation under 14 words and " +
            "do not use another | character.";

        try {
            if (GlassSdk.getGlassAiChatService() == null) {
                throw new IllegalStateException("Rokid AI Chat service unavailable");
            }
            GlassSdk.getGlassAiChatService().toAiChat(prompt, aiChatListener);
        } catch (Throwable error) {
            checking = false;
            statusView.setText("● ROKID AI ERROR");
            verdictView.setText("[?] UNVERIFIED");
            detailView.setText(compact(safeMessage(error), 120));
            footerView.setText("RETRYING LISTENING…");
            scheduleAsrRestart(1400L);
        }
    }

    private void appendAiChunk(String answer) {
        String chunk = answer == null ? "" : answer;
        if (chunk.isEmpty()) {
            return;
        }

        String current = aiBuffer.toString();
        if (chunk.startsWith(current) && chunk.length() >= current.length()) {
            aiBuffer.setLength(0);
            aiBuffer.append(chunk);
        } else {
            aiBuffer.append(chunk);
        }
    }

    private void finishFactCheck(String raw) {
        Verdict result = parseVerdict(raw);
        statusView.setText("● RESULT");
        verdictView.setText(result.displayVerdict());
        detailView.setText(result.explanation);
        footerView.setText("RESUMING ROKID ASR…");

        final long token = sessionToken;
        main.postDelayed(() -> {
            if (sessionActive && token == sessionToken) {
                checking = false;
                startRokidAsr();
            }
        }, 2200L);
    }

    private Verdict parseVerdict(String raw) {
        String cleaned = cleanText(raw)
            .replace("```", "")
            .replace("**", "")
            .trim();

        String upper = cleaned.toUpperCase(Locale.US);
        String status = "UNVERIFIED";
        if (upper.contains("CONTRADICTED")) {
            status = "CONTRADICTED";
        } else if (upper.contains("VERIFIED")) {
            status = "VERIFIED";
        } else if (upper.contains("UNVERIFIED")) {
            status = "UNVERIFIED";
        }

        String explanation = cleaned;
        int pipe = cleaned.indexOf('|');
        if (pipe >= 0 && pipe + 1 < cleaned.length()) {
            explanation = cleaned.substring(pipe + 1).trim();
        }
        explanation = compact(explanation, 100);
        if (explanation.isEmpty()) {
            explanation = "Rokid AI did not provide enough evidence.";
        }

        return new Verdict(status, explanation);
    }

    private String cleanText(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private String compact(String value, int max) {
        String clean = cleanText(value);
        if (clean.length() <= max) {
            return clean;
        }
        return clean.substring(0, Math.max(0, max - 1)).trim() + "…";
    }

    private String safeMessage(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message.trim();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sessionActive) {
            stopSession();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (GlassSdk.getGlassAsrService() != null) {
                GlassSdk.getGlassAsrService().stopSpeech();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (GlassSdk.getGlassAiChatService() != null) {
                GlassSdk.getGlassAiChatService().endAiChat();
            }
        } catch (Throwable ignored) {
        }
        try {
            GlassSdk.release();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
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
