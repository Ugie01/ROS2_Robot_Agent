package com.example.robot_agent;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.tabs.TabLayout;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements ROS2NativeManager.ROS2DataListener, GeminiAgentManager.GeminiAgentListener {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 1001;

    private TabLayout tabLayout;
    private View tabDriveSensors, tabAiAgent;

    // 상단 공통 헤더
    private EditText etDomainId;
    private Spinner spnGlobalQos;
    private TextView tvStatus, tvBatteryBadge;
    private Button btnNodeToggle;

    private final String[] qosPresets = new String[]{
            "TransientLocal (맵 최적)",
            "Reliable (신뢰성)",
            "BestEffort (센서/속도)"
    };

    // Tab 1: 주행 & 센서
    private SeekBar sbMaxSpeed;
    private TextView tvMaxSpeedLabel;
    private Spinner spnCmdVelType;
    private LaserScanView laserScanView;
    private JoystickView joystickView;
    private TextView tvVelocity;
    private Button btnEmergencyStop;

    // Tab 2: Gemini AI 에이전트 & POI 자율주행
    private MapView mapViewAi;
    private TextView tvChatLog;
    private ScrollView svChatLog;
    private EditText etUserChat;
    private Button btnSendChat, btnVoiceStt, btnClearChat;
    private Button btnAddPoiMode, btnModeInitialPose, btnSavePoi, btnCancelNav, btnParamSettings;

    // 파라미터 튜닝 캐시
    private final Map<String, String> currentParamValues = new HashMap<>();

    // Controllers & Engines
    private ROS2NativeManager ros2Manager;
    private PoiManager poiManager;
    private GeminiAgentManager geminiAgentManager;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;

    private float curRobotX = 0f, curRobotY = 0f, curRobotYaw = 0f;
    private byte[] currentMapData;
    private int currentMapW = 0, currentMapH = 0;
    private float currentMapRes = 0.05f, currentMapOx = 0f, currentMapOy = 0f;

    private float linearX = 0f;
    private float angularZ = 0f;
    private boolean isDriving = false;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private float maxLinearVel = 0.5f;
    private float maxAngularVel = 1.0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupGlobalCrashGuard();

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initDefaultParams();

        poiManager = new PoiManager();
        poiManager.loadFromFile(this);

        geminiAgentManager = new GeminiAgentManager(BuildConfig.GEMINI_API_KEY, poiManager, this);
        ros2Manager = new ROS2NativeManager(this, this);

        initViews();
        setupTabs();
        setupDriveControls();
        setupAiAgentControls();

        initSpeechEngines();
        checkPermissionsAndStartWakeWord();

        btnNodeToggle.setOnClickListener(v -> {
            try {
                btnNodeToggle.setEnabled(false);
                if (!ros2Manager.isRunning()) {
                    btnNodeToggle.setText("연결 중...");
                    String domainStr = etDomainId.getText().toString().trim();
                    int domainId = 0;
                    if (!domainStr.isEmpty()) {
                        try {
                            domainId = Integer.parseInt(domainStr);
                        } catch (NumberFormatException nfe) {
                            showErrorDialog("도메인 ID 오류", "도메인 ID는 0 이상의 정수만 가능합니다.");
                            btnNodeToggle.setEnabled(true);
                            btnNodeToggle.setText("노드 시작");
                            return;
                        }
                    }
                    boolean useBestEffort = (spnGlobalQos.getSelectedItemPosition() == 2);
                    ros2Manager.startNode(domainId, useBestEffort);
                } else {
                    btnNodeToggle.setText("중지 중...");
                    ros2Manager.stopNode();
                }
            } catch (Throwable t) {
                showErrorDialog("노드 제어 오류", t.getMessage());
                btnNodeToggle.setEnabled(true);
            }
        });

        startPublishingLoop();
        startHeartbeatWatchdog();
    }

    private void initDefaultParams() {
        currentParamValues.put("/local_costmap/local_costmap.inflation_layer.inflation_radius", "0.20");
        currentParamValues.put("/local_costmap/local_costmap.inflation_layer.cost_scaling_factor", "3.00");
        currentParamValues.put("/global_costmap/global_costmap.inflation_layer.inflation_radius", "0.30");
        currentParamValues.put("/global_costmap/global_costmap.inflation_layer.cost_scaling_factor", "2.50");
        currentParamValues.put("/controller_server.FollowPath.desired_linear_vel", "0.25");
        currentParamValues.put("/controller_server.FollowPath.lookahead_dist", "0.60");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(WakeWordService.EXTRA_TRIGGER_STT, false)) {
            startSpeechRecognition();
        }
    }

    private void checkPermissionsAndStartWakeWord() {
        List<String> neededPermissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!neededPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toArray(new String[0]), PERMISSION_REQ_CODE);
        } else {
            startWakeWordService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startWakeWordService();
            } else {
                Toast.makeText(this, "마이크 및 알림 권한이 필요합니다.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startWakeWordService() {
        try {
            Intent serviceIntent = new Intent(this, WakeWordService.class);
            serviceIntent.setAction(WakeWordService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "WakeWordService 시작 실패", e);
        }
    }

    private void initSpeechEngines() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.KOREAN);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.KOREA);
                }
            }
        });

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    Toast.makeText(MainActivity.this, "🎤 한국어로 말씀하세요... (예: 식탁으로 가줘)", Toast.LENGTH_SHORT).show();
                }

                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}

                @Override
                public void onError(int error) {
                    Log.e(TAG, "STT 에러 코드: " + error);
                    Toast.makeText(MainActivity.this, "음성을 다시 말씀해 주세요.", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String voiceText = matches.get(0);
                        appendChatLog("👤 [사용자 (음성)] " + voiceText);
                        geminiAgentManager.sendUserMessage(voiceText);
                    }
                }

                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
    }

    private void speakText(String text) {
        if (tts != null && text != null && !text.trim().isEmpty()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UTTERANCE_ID");
        }
    }

    private void startSpeechRecognition() {
        runOnUiThread(() -> {
            if (speechRecognizer != null) {
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR");
                intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
                try {
                    speechRecognizer.startListening(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "STT 시작 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "STT 음성 인식을 지원하지 않는 기기입니다.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupGlobalCrashGuard() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e("ROS2_GUARD", "백그라운드 예외 발생: " + throwable.getMessage(), throwable);
            runOnUiThread(() -> showErrorDialog("시스템 예외 안내", throwable.getMessage()));
        });
    }

    public void showErrorDialog(String title, String message) {
        runOnUiThread(() -> {
            try {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(this)
                        .setTitle("⚠️ " + title)
                        .setMessage(message)
                        .setPositiveButton("확인", (dialog, which) -> dialog.dismiss())
                        .setCancelable(true)
                        .show();
            } catch (Throwable ignored) {}
        });
    }

    @Override
    public void onError(String title, String message) {
        showErrorDialog(title, message);
    }

    private void initViews() {
        tabLayout = findViewById(R.id.tabLayout);
        tabDriveSensors = findViewById(R.id.tabDriveSensors);
        tabAiAgent = findViewById(R.id.tabAiAgent);

        etDomainId = findViewById(R.id.etDomainId);
        spnGlobalQos = findViewById(R.id.spnGlobalQos);
        tvStatus = findViewById(R.id.tvStatus);
        tvBatteryBadge = findViewById(R.id.tvBatteryBadge);
        btnNodeToggle = findViewById(R.id.btnNodeToggle);

        ArrayAdapter<String> qosAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, qosPresets);
        qosAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnGlobalQos.setAdapter(qosAdapter);

        // Tab 1: 주행 & 센서
        sbMaxSpeed = findViewById(R.id.sbMaxSpeed);
        tvMaxSpeedLabel = findViewById(R.id.tvMaxSpeedLabel);
        spnCmdVelType = findViewById(R.id.spnCmdVelType);
        laserScanView = findViewById(R.id.laserScanView);
        joystickView = findViewById(R.id.joystickView);
        tvVelocity = findViewById(R.id.tvVelocity);
        btnEmergencyStop = findViewById(R.id.btnEmergencyStop);

        // Tab 2: Gemini AI 에이전트 & POI 맵
        mapViewAi = findViewById(R.id.mapViewAi);
        tvChatLog = findViewById(R.id.tvChatLog);
        svChatLog = findViewById(R.id.svChatLog);
        etUserChat = findViewById(R.id.etUserChat);
        btnSendChat = findViewById(R.id.btnSendChat);
        btnVoiceStt = findViewById(R.id.btnVoiceStt);
        btnClearChat = findViewById(R.id.btnClearChat);

        btnAddPoiMode = findViewById(R.id.btnAddPoiMode);
        btnModeInitialPose = findViewById(R.id.btnModeInitialPose);
        btnSavePoi = findViewById(R.id.btnSavePoi);
        btnCancelNav = findViewById(R.id.btnCancelNav);
        btnParamSettings = findViewById(R.id.btnParamSettings);
    }

    private void setupTabs() {
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                tabDriveSensors.setVisibility(View.GONE);
                tabAiAgent.setVisibility(View.GONE);

                if (tab.getPosition() == 0) {
                    tabDriveSensors.setVisibility(View.VISIBLE);
                } else if (tab.getPosition() == 1) {
                    tabAiAgent.setVisibility(View.VISIBLE);
                    refreshMapOnView(mapViewAi);
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void refreshMapOnView(MapView mv) {
        if (mv != null && currentMapData != null) {
            mv.updateMap(currentMapData, currentMapW, currentMapH, currentMapRes, currentMapOx, currentMapOy);
            mv.updateRobotPose(curRobotX, curRobotY, curRobotYaw);
            mv.setPois(poiManager.getAllPois());
        }
    }

    private void setupDriveControls() {
        sbMaxSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int validProgress = Math.max(10, progress);
                maxLinearVel = validProgress / 100.0f;
                maxAngularVel = maxLinearVel * 2.0f;
                tvMaxSpeedLabel.setText(String.format(Locale.getDefault(), "최대속도: %.2f m/s", maxLinearVel));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        if (spnCmdVelType != null) {
            String[] cmdVelOptions = new String[]{"geometry_msgs/Twist", "geometry_msgs/TwistStamped"};
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, cmdVelOptions) {
                @Override
                public View getView(int position, View convertView, android.view.ViewGroup parent) {
                    View view = super.getView(position, convertView, parent);
                    if (view instanceof TextView) {
                        ((TextView) view).setTextColor(Color.WHITE);
                        ((TextView) view).setTextSize(11);
                    }
                    return view;
                }
            };
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spnCmdVelType.setAdapter(adapter);

            spnCmdVelType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (position == 0) {
                        ros2Manager.setCmdVelType(ROS2NativeManager.CmdVelType.TWIST);
                    } else {
                        ros2Manager.setCmdVelType(ROS2NativeManager.CmdVelType.TWIST_STAMPED);
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        joystickView.setJoystickListener((xPercent, yPercent) -> {
            linearX = yPercent * maxLinearVel;
            angularZ = -xPercent * maxAngularVel;

            if (linearX == 0f && angularZ == 0f) {
                isDriving = false;
                ros2Manager.publishCmdVel(0f, 0f);
                tvVelocity.setText("Linear: 0.00 m/s | Angular: 0.00 rad/s (정지)");
            } else {
                isDriving = true;
                tvVelocity.setText(String.format(Locale.getDefault(), "Linear: %.2f m/s | Angular: %.2f rad/s", linearX, angularZ));
            }
        });

        btnEmergencyStop.setOnClickListener(v -> {
            linearX = 0f;
            angularZ = 0f;
            isDriving = false;
            ros2Manager.publishCmdVel(0f, 0f);
            tvVelocity.setText("Linear: 0.00 m/s | Angular: 0.00 rad/s (E-STOP)");
        });
    }

    private void setupAiAgentControls() {
        if (mapViewAi != null) {
            mapViewAi.setPois(poiManager.getAllPois());
            mapViewAi.setOnMapEventListener(new MapView.OnMapEventListener() {
                @Override
                public void onSingleGoalSelected(float worldX, float worldY, float yaw) {
                    ros2Manager.publishGoalPose(worldX, worldY, yaw);
                    Toast.makeText(MainActivity.this, String.format(Locale.getDefault(), "목표 지점 전송: (%.2f, %.2f)", worldX, worldY), Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onInitialPoseSelected(float worldX, float worldY, float yaw) {
                    // [2D Pose Estimate] 로봇 위치 및 방향 추정 전송
                    ros2Manager.publishInitialPose(worldX, worldY, yaw);
                    mapViewAi.clearAllMarkers();
                    Toast.makeText(MainActivity.this, String.format(Locale.getDefault(), "위치 추정(2D Pose) 적용: (%.2f, %.2f, %.0f°)", worldX, worldY, Math.toDegrees(yaw)), Toast.LENGTH_SHORT).show();
                    appendChatLog(String.format(Locale.getDefault(), "📍 [2D Pose 적용] X: %.2f, Y: %.2f, Yaw: %.0f°", worldX, worldY, Math.toDegrees(yaw)));
                }

                @Override
                public void onWaypointsUpdated(int count) {}

                @Override
                public void onPoiSelected(float worldX, float worldY, float yaw, float px, float py) {
                    showAddPoiDialog(worldX, worldY, yaw, px, py);
                }
            });
        }

        btnAddPoiMode.setOnClickListener(v -> {
            if (mapViewAi != null) {
                mapViewAi.setMode(MapView.MODE_ADD_POI);
                Toast.makeText(this, "지도에서 등록할 POI 지점과 방향을 터치&드래그하세요.", Toast.LENGTH_SHORT).show();
            }
        });

        btnModeInitialPose.setOnClickListener(v -> {
            if (mapViewAi != null) {
                mapViewAi.setMode(MapView.MODE_INITIAL_POSE);
                Toast.makeText(this, "지도에서 로봇의 실제 위치와 방향을 드래그하여 맞추세요. (/initialpose)", Toast.LENGTH_SHORT).show();
            }
        });

        btnSavePoi.setOnClickListener(v -> {
            boolean localSaved = poiManager.saveToFile(this);
            String jsonPayload = poiManager.toJsonString();
            ros2Manager.publishStringTopic("/save_named_poses", jsonPayload);

            String msg = (localSaved ? "로컬 파일(poi_waypoints.json) 저장 완료\n" : "로컬 저장 실패\n") +
                    "ROS 2 토픽 [/save_named_poses]로 백업 JSON 발행 완료 (" + poiManager.getAllPois().size() + "개)";
            showErrorDialog("POI 저장 및 ROS 2 백업", msg);
        });

        btnCancelNav.setOnClickListener(v -> {
            ros2Manager.cancelNavigation(curRobotX, curRobotY, curRobotYaw);
            if (mapViewAi != null) mapViewAi.clearAllMarkers();
            Toast.makeText(this, "자율주행 취소 명령 전송", Toast.LENGTH_SHORT).show();
            appendChatLog("🛑 [자율주행 취소됨]");
        });

        btnParamSettings.setOnClickListener(v -> showParamTuningDialog());

        btnSendChat.setOnClickListener(v -> {
            String text = etUserChat.getText().toString().trim();
            if (!text.isEmpty()) {
                etUserChat.setText("");
                appendChatLog("👤 [사용자] " + text);
                geminiAgentManager.sendUserMessage(text);
            }
        });

        btnVoiceStt.setOnClickListener(v -> startSpeechRecognition());

        btnClearChat.setOnClickListener(v -> {
            geminiAgentManager.clearHistory();
            tvChatLog.setText("[대화 기록이 초기화되었습니다.]\n");
            speakText("대화 기록이 초기화되었습니다.");
        });
    }

    /**
     * [복원]: Inflation Radius / Cost Scaling Factor / 속도 파라미터 튜닝 다이얼로그
     */
    private void showParamTuningDialog() {
        String[] paramList = new String[]{
                "/local_costmap/local_costmap.inflation_layer.inflation_radius",
                "/local_costmap/local_costmap.inflation_layer.cost_scaling_factor",
                "/global_costmap/global_costmap.inflation_layer.inflation_radius",
                "/global_costmap/global_costmap.inflation_layer.cost_scaling_factor",
                "/controller_server.FollowPath.desired_linear_vel",
                "/controller_server.FollowPath.lookahead_dist"
        };

        View dialogView = LayoutInflater.from(this).inflate(android.R.layout.simple_spinner_dropdown_item, null);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, paramList);
        spinner.setAdapter(adapter);

        EditText etValue = new EditText(this);
        etValue.setHint("값 입력 (예: 0.25)");
        etValue.setText(currentParamValues.getOrDefault(paramList[0], "0.20"));

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String key = paramList[position];
                etValue.setText(currentParamValues.getOrDefault(key, "0.0"));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(40, 20, 40, 20);
        container.addView(spinner);
        container.addView(etValue);

        new AlertDialog.Builder(this)
                .setTitle("⚙️ Nav2 파라미터 튜닝 (/param_set_cmd)")
                .setMessage("수정할 Costmap / Controller 파라미터를 선택하고 값을 입력하세요.")
                .setView(container)
                .setPositiveButton("적용 및 전송", (dialog, which) -> {
                    String selectedFullKey = spinner.getSelectedItem().toString();
                    String newVal = etValue.getText().toString().trim();
                    if (!newVal.isEmpty()) {
                        currentParamValues.put(selectedFullKey, newVal);

                        // Node와 Param 분리
                        int lastDot = selectedFullKey.lastIndexOf('.');
                        String node = selectedFullKey.substring(0, lastDot);
                        String param = selectedFullKey.substring(lastDot + 1);

                        try {
                            JSONObject json = new JSONObject();
                            json.put("node", node);
                            json.put("param", param);
                            json.put("value", newVal);

                            ros2Manager.publishStringTopic("/param_set_cmd", json.toString());
                            Toast.makeText(this, "파라미터 전송: " + param + " = " + newVal, Toast.LENGTH_SHORT).show();
                            appendChatLog(String.format("⚙️ [파라미터 적용] %s ➔ %s", param, newVal));
                        } catch (Exception e) {
                            showErrorDialog("전송 오류", e.getMessage());
                        }
                    }
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void showAddPoiDialog(float wx, float wy, float yaw, float px, float py) {
        EditText input = new EditText(this);
        input.setHint("장소 명칭 (예: 식탁, 주방, 충전소)");

        new AlertDialog.Builder(this)
                .setTitle("📍 신규 POI 등록")
                .setMessage(String.format(Locale.getDefault(), "좌표: X:%.2f, Y:%.2f, Yaw:%.0f°\n장소 이름을 입력하세요.", wx, wy, Math.toDegrees(yaw)))
                .setView(input)
                .setPositiveButton("저장", (dialog, which) -> {
                    String poiName = input.getText().toString().trim();
                    if (!poiName.isEmpty()) {
                        poiManager.addPoi(poiName, wx, wy, yaw, px, py);
                        poiManager.saveToFile(this);
                        if (mapViewAi != null) mapViewAi.setPois(poiManager.getAllPois());
                        Toast.makeText(this, "'" + poiName + "' 등록 완료", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void appendChatLog(String log) {
        tvChatLog.append("\n" + log + "\n");
        svChatLog.post(() -> svChatLog.fullScroll(View.FOCUS_DOWN));
    }

    // GeminiAgentListener 구현
    @Override
    public void onResponse(String textResponse) {
        appendChatLog("🤖 [터틀] " + textResponse);
        speakText(textResponse);
    }

    @Override
    public void onFunctionCall(String functionName, JSONObject arguments) {
        if ("navigate_to_poi".equals(functionName)) {
            String targetName = arguments.optString("target_name", "");
            PoiManager.PoiItem poi = poiManager.getPoi(targetName);

            JSONObject resultJson = new JSONObject();
            try {
                if (poi != null) {
                    ros2Manager.publishGoalPose(poi.x, poi.y, poi.yaw);
                    String feedback = targetName + "(으)로 이동을 시작합니다.";
                    appendChatLog("⚙️ [에이전트 실행] " + feedback + " (X:" + String.format(Locale.getDefault(), "%.2f", poi.x) + ", Y:" + String.format(Locale.getDefault(), "%.2f", poi.y) + ")");
                    speakText(feedback);

                    resultJson.put("status", "success");
                    resultJson.put("message", feedback);
                } else {
                    String feedback = "등록되지 않은 목적지입니다: " + targetName;
                    appendChatLog("⚠️ [에이전트 오류] " + feedback);
                    speakText(feedback);

                    resultJson.put("status", "failed");
                    resultJson.put("message", feedback);
                }
            } catch (Exception e) {
                Log.e(TAG, "함수 응답 JSON 생성 오류", e);
            }

            geminiAgentManager.sendFunctionResult(functionName, resultJson);
        }
    }

    @Override
    public void onGeminiError(String errorMessage) {
        runOnUiThread(() -> {
            appendChatLog("❌ [에이전트 오류] " + errorMessage);
            Toast.makeText(this, "Gemini 에러: " + errorMessage, Toast.LENGTH_SHORT).show();
        });
    }

    private void startPublishingLoop() {
        timerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (ros2Manager.isRunning() && isDriving) {
                    ros2Manager.publishCmdVel(linearX, angularZ);
                }
                timerHandler.postDelayed(this, 100);
            }
        }, 100);
    }

    private void startHeartbeatWatchdog() {
        timerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (ros2Manager.isRunning()) {
                    long elapsed = System.currentTimeMillis() - ros2Manager.getLastHeartbeatTime();
                    if (elapsed > 2500 && ros2Manager.getLastHeartbeatTime() > 0) {
                        isDriving = false;
                        ros2Manager.publishCmdVel(0f, 0f);
                        tvStatus.setText("● DDS 통신 두절 (Fail-Safe)");
                        tvStatus.setTextColor(Color.parseColor("#FF9800"));
                    } else if (elapsed <= 2500) {
                        tvStatus.setText("● DDS 정상 연결됨");
                        tvStatus.setTextColor(Color.GREEN);
                    }
                }
                timerHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void clearAllViewsAndState() {
        runOnUiThread(() -> {
            if (laserScanView != null) laserScanView.clearScan();
            if (joystickView != null) joystickView.resetJoystick();
            if (tvVelocity != null) tvVelocity.setText("Linear: 0.00 m/s | Angular: 0.00 rad/s (정지)");
            if (tvBatteryBadge != null) {
                tvBatteryBadge.setText("🔋 --% (--V)");
                tvBatteryBadge.setTextColor(Color.GRAY);
            }

            if (mapViewAi != null) mapViewAi.clearMap();

            currentMapData = null;
            currentMapW = 0;
            currentMapH = 0;
            curRobotX = 0f;
            curRobotY = 0f;
            curRobotYaw = 0f;
            linearX = 0f;
            angularZ = 0f;
            isDriving = false;
        });
    }

    @Override
    public void onCompressedImageReceived(Bitmap bitmap) {}

    @Override
    public void onLaserScanReceived(float[] ranges, float angleMin, float angleInc) {
        runOnUiThread(() -> {
            if (laserScanView != null && ros2Manager.isRunning()) {
                laserScanView.updateScan(ranges, angleMin, angleInc);
            }
        });
    }

    @Override
    public void onMapReceived(byte[] data, int width, int height, float resolution, float originX, float originY) {
        runOnUiThread(() -> {
            if (!ros2Manager.isRunning()) return;
            this.currentMapData = data;
            this.currentMapW = width;
            this.currentMapH = height;
            this.currentMapRes = resolution;
            this.currentMapOx = originX;
            this.currentMapOy = originY;

            if (mapViewAi != null) mapViewAi.updateMap(data, width, height, resolution, originX, originY);
        });
    }

    @Override
    public void onRobotPoseReceived(float worldX, float worldY, float yaw, boolean isAmcl) {
        runOnUiThread(() -> {
            if (!ros2Manager.isRunning()) return;

            // AMCL 포즈(지도 기준 위치)만 맵에 반영하여 odom 좌표 충돌 방지
            if (isAmcl) {
                curRobotX = worldX;
                curRobotY = worldY;
                curRobotYaw = yaw;

                if (mapViewAi != null) {
                    mapViewAi.updateRobotPose(worldX, worldY, yaw);
                }
            }
        });
    }

    @Override
    public void onBatteryStateReceived(float percentage, float voltage, boolean isCharging) {
        runOnUiThread(() -> {
            if (!ros2Manager.isRunning()) return;
            String statusStr = String.format(Locale.getDefault(), "%s %.0f%% (%.1fV)", isCharging ? "⚡" : "🔋", percentage, voltage);
            tvBatteryBadge.setText(statusStr);
            if (percentage > 50) tvBatteryBadge.setTextColor(Color.GREEN);
            else if (percentage > 20) tvBatteryBadge.setTextColor(Color.YELLOW);
            else tvBatteryBadge.setTextColor(Color.RED);
        });
    }

    @Override
    public void onCustomTopicReceived(String topic, String data) {}

    @Override
    public void onServiceResponseReceived(String serviceName, String result) {}

    @Override
    public void onStatusChanged(String status, boolean isRunning) {
        runOnUiThread(() -> {
            btnNodeToggle.setEnabled(true);
            tvStatus.setText("● " + status);

            if (spnCmdVelType != null) {
                spnCmdVelType.setEnabled(!isRunning);
            }

            if (isRunning) {
                tvStatus.setTextColor(Color.GREEN);
                btnNodeToggle.setText("노드 중지");
                btnNodeToggle.setBackgroundTintList(getColorStateList(android.R.color.holo_red_dark));
            } else {
                tvStatus.setTextColor(Color.RED);
                btnNodeToggle.setText("노드 시작");
                btnNodeToggle.setBackgroundTintList(getColorStateList(android.R.color.holo_green_dark));
                clearAllViewsAndState();
            }
        });
    }

    @Override
    public void onLog(String logMessage) {}

    @Override
    protected void onStop() {
        super.onStop();
        if (isFinishing()) {
            ros2Manager.stopNode();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timerHandler.removeCallbacksAndMessages(null);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        if (geminiAgentManager != null) {
            geminiAgentManager.shutdown();
        }
        ros2Manager.shutdown();
        clearAllViewsAndState();
    }
}