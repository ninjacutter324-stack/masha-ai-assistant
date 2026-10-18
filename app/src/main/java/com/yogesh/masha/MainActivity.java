package com.yogesh.masha;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String GROQ_API_KEY = "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";
    private static final String PREFS_NAME = "MashaMemoryPrefs";
    private static final String MEMORY_KEY = "chat_history";

    private LinearLayout chatContainer;
    private ScrollView chatScrollView;
    private EditText etMessage;
    private Button btnSend, btnMic;
    private TextView tvStatus;

    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private OkHttpClient httpClient;
    private SharedPreferences prefs;

    private JSONArray conversationHistory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        chatContainer = findViewById(R.id.chatContainer);
        chatScrollView = findViewById(R.id.chatScrollView);
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);
        btnMic = findViewById(R.id.btnMic);
        tvStatus = findViewById(R.id.tvStatus);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        httpClient = new OkHttpClient();

        loadMemory();
        requestAppPermissions();
        initTTS();
        initSpeechRecognizer();

        btnSend.setOnClickListener(v -> {
            String text = etMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                handleUserQuery(text);
                etMessage.setText("");
            }
        });

        btnMic.setOnClickListener(v -> startVoiceListening());

        // Startup: clean greeting bubble
        addMessageBubble("Masha", "Hey Yogesh! Main online hoon. Aaj kya plan hai? Bolo ya type karo! ✨", false);
    }

    private void requestAppPermissions() {
        String[] permissions = {Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA};
        ArrayList<String> toRequest = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), 101);
        }
    }

    private void initTTS() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("hi", "IN"));
                tts.setPitch(1.15f);
                tts.setSpeechRate(1.05f);
            }
        });
    }

    private void initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { tvStatus.setText("● LISTENING..."); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { tvStatus.setText("● THINKING..."); }
                @Override public void onError(int error) { tvStatus.setText("● READY"); }

                @Override
                public void onResults(Bundle results) {
                    tvStatus.setText("● READY");
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        handleUserQuery(matches.get(0));
                    }
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
    }

    private void startVoiceListening() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolo, Masha sun rahi hai...");
        speechRecognizer.startListening(intent);
    }

    private void handleUserQuery(String query) {
        addMessageBubble("You", query, true);

        // Flashlight toggle
        String q = query.toLowerCase();
        if (q.contains("torch on") || q.contains("flashlight on") || q.contains("light on") || q.contains("torch chalu")) {
            toggleTorch(true);
            String reply = "Flashlight chalu kar di hai, sir! 💡";
            addMessageBubble("Masha", reply, false);
            speak(reply);
            return;
        } else if (q.contains("torch off") || q.contains("flashlight off") || q.contains("light off") || q.contains("torch band")) {
            toggleTorch(false);
            String reply = "Flashlight band kar di! ✨";
            addMessageBubble("Masha", reply, false);
            speak(reply);
            return;
        }

        callGroqAPI(query);
    }

    private void toggleTorch(boolean state) {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm != null) {
                String id = cm.getCameraIdList()[0];
                cm.setTorchMode(id, state);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadMemory() {
        String saved = prefs.getString(MEMORY_KEY, "");
        conversationHistory = new JSONArray();
        try {
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", "You are M.A.S.H.A (or Masha), a super smart, witty, joyful, and friendly AI assistant companion created for Yogesh Yadav. " +
                    "Your personality is lively, cheerful, playful, and fun like E.D.I.T.H from Spider-Man, but with a cute, slightly talkative (bak-bak/chatty) vibe. " +
                    "Never sound like a boring, cold robotic tactical tool. You love talking, making fun remarks, motivating Yogesh for his studies and dreams, and cracking lighthearted jokes. " +
                    "Speak naturally in Hinglish (mix of Hindi and English in Latin script). Keep replies conversational, expressive, and engaging with emojis.");
            conversationHistory.put(sysMsg);

            if (!saved.isEmpty()) {
                JSONArray old = new JSONArray(saved);
                for (int i = 0; i < old.length(); i++) {
                    conversationHistory.put(old.get(i));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveMemory() {
        try {
            JSONArray trimmed = new JSONArray();
            int start = Math.max(1, conversationHistory.length() - 12);
            for (int i = start; i < conversationHistory.length(); i++) {
                trimmed.put(conversationHistory.get(i));
            }
            prefs.edit().putString(MEMORY_KEY, trimmed.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void callGroqAPI(String userText) {
        tvStatus.setText("● THINKING...");
        try {
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userText);
            conversationHistory.put(userMsg);

            JSONObject reqBody = new JSONObject();
            reqBody.put("model", "llama-3.3-70b-versatile");
            reqBody.put("messages", conversationHistory);
            reqBody.put("max_tokens", 350);

            RequestBody body = RequestBody.create(
                    reqBody.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + GROQ_API_KEY)
                    .post(body)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        tvStatus.setText("● ERROR");
                        addMessageBubble("Masha", "Network issue lag raha hai, ek baar internet check karo na!", false);
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String respStr = response.body().string();
                        JSONObject json = new JSONObject(respStr);
                        String reply = json.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content");

                        JSONObject assistantMsg = new JSONObject();
                        assistantMsg.put("role", "assistant");
                        assistantMsg.put("content", reply);
                        conversationHistory.put(assistantMsg);
                        saveMemory();

                        new Handler(Looper.getMainLooper()).post(() -> {
                            tvStatus.setText("● READY");
                            addMessageBubble("Masha", reply, false);
                            speak(reply);
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addMessageBubble(String sender, String text, boolean isUser) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(24, 16, 24, 16);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 8, 0, 8);

        if (isUser) {
            params.gravity = Gravity.END;
            tv.setBackgroundColor(0xFF1F2E45);
            tv.setTextColor(0xFF00E5FF);
        } else {
            params.gravity = Gravity.START;
            tv.setBackgroundColor(0xFF151D2A);
            tv.setTextColor(0xFFE0E6ED);
        }

        tv.setLayoutParams(params);
        chatContainer.addView(tv);
        chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void speak(String text) {
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        super.onDestroy();
    }
}
