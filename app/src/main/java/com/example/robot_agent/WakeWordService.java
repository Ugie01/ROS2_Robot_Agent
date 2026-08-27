package com.example.robot_agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;
import org.json.JSONObject;

public class WakeWordService extends Service implements RecognitionListener {
    private static final String TAG = "WakeWordService";
    private static final String CHANNEL_ID = "wake_word_service_channel";
    private static final int NOTIFICATION_ID = 2001;

    public static final String ACTION_START = "ACTION_START_WAKE_WORD";
    public static final String ACTION_STOP = "ACTION_STOP_WAKE_WORD";
    public static final String ACTION_RESUME = "ACTION_RESUME_WAKE_WORD";
    public static final String ACTION_PAUSE = "ACTION_PAUSE_WAKE_WORD";
    public static final String EXTRA_TRIGGER_STT = "EXTRA_TRIGGER_STT";

    private Model model;
    private Recognizer recognizer; // 🛑 메모리 누수 방지용
    private SpeechService speechService;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initVoskModel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_STOP.equals(intent.getAction())) {
                stopContinuousListening();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            else if (ACTION_RESUME.equals(intent.getAction())) {
                // 🛑 구글 STT가 끝났으니, 다시 마이크 하드웨어 소유권 가져오기
                if (speechService == null && model != null) {
                    startVoskListening();
                    Log.d(TAG, "🔥 Vosk 마이크 완전히 새로 켜짐 (하드웨어 권한 획득)");
                }
            }
            else if (ACTION_PAUSE.equals(intent.getAction())) {
                // 🛑 수동 버튼을 눌렀을 때 마이크 하드웨어를 구글에게 완전히 넘기기
                if (speechService != null) {
                    speechService.cancel();
                    speechService.shutdown();
                    speechService = null;
                    Log.d(TAG, "⏸️ Vosk 마이크 완전히 끔 (구글 STT에 양보 완료)");
                }
            }
        }

        startAsForeground();
        return START_STICKY;
    }

    private void startAsForeground() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("터틀 핫워드 감지 서비스")
                .setContentText("'Hey Turtle' 상시 대기 중... (영어 모드)")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Turtle WakeWord Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("헤이 터틀 상시 음성 감지 채널");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void initVoskModel() {
        StorageService.unpack(this, "model-en", "model",
                (model) -> {
                    this.model = model;
                    startVoskListening();
                },
                (exception) -> Log.e(TAG, "Vosk 모델 로드 실패: " + exception.getMessage(), exception));
    }

    private void startVoskListening() {
        try {
            if (recognizer == null) {
                recognizer = new Recognizer(model, 16000.0f);
            }
            speechService = new SpeechService(recognizer, 16000.0f);
            speechService.startListening(this);
            Log.d(TAG, "🔥 Vosk 핫워드 감지 시작됨");
        } catch (IOException e) {
            Log.e(TAG, "Vosk SpeechService 시작 실패", e);
        }
    }

    @Override
    public void onPartialResult(String hypothesis) {
        try {
            JSONObject json = new JSONObject(hypothesis);
            String partialText = json.optString("partial", "");
            String cleanText = partialText.replace(" ", "").toLowerCase();

            if (cleanText.isEmpty()) return;

            if (cleanText.contains("heyturtle") ||
                    cleanText.contains("hayturtle") ||
                    cleanText.contains("heytotal") ||
                    cleanText.contains("heytaughther") ||
                    cleanText.contains("heytoldher") ||
                    cleanText.contains("heyher") ||
                    cleanText.contains("hey tata") ||
                    cleanText.contains("hateturtle")) {

                Log.d(TAG, "🔥 영어 호출어 감지 성공! 앱을 띄웁니다.");

                // 🛑 마이크 하드웨어 구글 STT에 완전히 양보
                if (speechService != null) {
                    speechService.cancel();
                    speechService.shutdown();
                    speechService = null;
                }

                triggerWakeAction();
            }
            else if (cleanText.length() > 30) {
                resetListening();
            }

        } catch (Exception e) {
            Log.e(TAG, "JSON 파싱 에러", e);
        }
    }

    @Override
    public void onResult(String hypothesis) { }

    @Override
    public void onFinalResult(String hypothesis) { }

    @Override
    public void onError(Exception exception) {
        Log.e(TAG, "Vosk 에러 발생", exception);
    }

    @Override
    public void onTimeout() { }

    private void triggerWakeAction() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            intent.putExtra(EXTRA_TRIGGER_STT, true);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "앱 포그라운드 전환 실패", e);
        }
    }

    private void resetListening() {
        if (speechService != null) {
            speechService.cancel();
            speechService.startListening(this);
        }
    }

    private void stopContinuousListening() {
        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
            speechService = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}