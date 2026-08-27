package com.example.robot_agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.porcupine.PorcupineManager;
import ai.picovoice.porcupine.PorcupineManagerCallback;

public class WakeWordService extends Service {
    private static final String TAG = "WakeWordService";
    private static final String CHANNEL_ID = "wake_word_service_channel";
    private static final int NOTIFICATION_ID = 2001;

    public static final String ACTION_START = "ACTION_START_WAKE_WORD";
    public static final String ACTION_STOP = "ACTION_STOP_WAKE_WORD";
    public static final String EXTRA_TRIGGER_STT = "EXTRA_TRIGGER_STT";

    // Picovoice Console 발급 AccessKey
    private static final String PICOVOICE_ACCESS_KEY = "YOUR_PICOVOICE_ACCESS_KEY";

    private PorcupineManager porcupineManager;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopWakeWordEngine();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startAsForeground();
        initPorcupine();

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
                .setContentText("'헤이 터틀' 음성 명령 대기 중...")
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
            channel.setDescription("헤이 터틀 상시 음성 인식 서비스 채널");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void initPorcupine() {
        if (porcupineManager != null) return;

        try {
            PorcupineManagerCallback callback = keywordIndex -> {
                Log.d(TAG, "핫워드 감지됨! Index: " + keywordIndex);
                triggerWakeAction();
            };

            // 커스텀 키워드 모델(.ppn)이 있을 경우 setKeywordPath 사용, 기본 내장 키워드 PORCUPINE fallback 지원
            porcupineManager = new PorcupineManager.Builder()
                    .setAccessKey(PICOVOICE_ACCESS_KEY)
                    .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE) // 또는 .setKeywordPath("hey_turtle.ppn")
                    .setSensitivity(0.7f)
                    .build(getApplicationContext(), callback);

            porcupineManager.start();
            Log.d(TAG, "Porcupine 핫워드 감지 엔진 시작 완료");

        } catch (Exception e) {
            Log.e(TAG, "Porcupine 초기화 실패: " + e.getMessage(), e);
        }
    }

    private void triggerWakeAction() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            intent.putExtra(EXTRA_TRIGGER_STT, true);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "앱 포그라운드 전환 오류", e);
        }
    }

    private void stopWakeWordEngine() {
        if (porcupineManager != null) {
            try {
                porcupineManager.stop();
                porcupineManager.delete();
            } catch (Exception e) {
                Log.e(TAG, "Porcupine 해제 오류", e);
            }
            porcupineManager = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopWakeWordEngine();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}