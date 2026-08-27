package com.example.robot_agent;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OllamaAgentManager {
    private static final String TAG = "OllamaAgentManager";
    private static final String MODEL_NAME = "turtle_agent:latest";
    private static final String OLLAMA_URL = BuildConfig.OLLAMA_URL;

    public interface OllamaAgentListener {
        void onResponse(String textResponse);
        void onFunctionCall(String functionName, JSONObject arguments);
        void onError(String errorMessage);
    }

    private final PoiManager poiManager;
    private final OllamaAgentListener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<JSONObject> chatHistory = new ArrayList<>();
    private final OkHttpClient httpClient;

    public OllamaAgentManager(PoiManager poiManager, OllamaAgentListener listener) {
        this.poiManager = poiManager;
        this.listener = listener;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public void clearHistory() {
        synchronized (chatHistory) {
            chatHistory.clear();
        }
    }

    public void sendUserMessage(String userPrompt) {
        if (userPrompt == null || userPrompt.trim().isEmpty()) return;

        executor.execute(() -> {
            try {
                JSONObject userTurn = new JSONObject();
                userTurn.put("role", "user");
                userTurn.put("content", userPrompt);

                synchronized (chatHistory) {
                    chatHistory.add(userTurn);
                }

                executeChat();
            } catch (Exception e) {
                Log.e(TAG, "사용자 메시지 생성 오류", e);
                postError("메시지 생성 오류: " + e.getMessage());
            }
        });
    }

    public void sendFunctionResult(String functionName, JSONObject functionResponseContent) {
        executor.execute(() -> {
            try {
                JSONObject toolTurn = new JSONObject();
                toolTurn.put("role", "tool");
                toolTurn.put("content", functionResponseContent.toString());

                synchronized (chatHistory) {
                    chatHistory.add(toolTurn);
                }
            } catch (Exception e) {
                Log.e(TAG, "함수 응답 처리 예외", e);
                postError("함수 결과 처리 오류: " + e.getMessage());
            }
        });
    }

    private void executeChat() {
        try {
            JSONObject payload = buildRequestPayload();
            RequestBody body = RequestBody.create(
                    payload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url(OLLAMA_URL)
                    .post(body)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Ollama 통신 실패", e);
                    postError("서버 연결 실패: PC에 Ollama가 켜져있는지 확인하세요.");
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        postError("Ollama 오류 (" + response.code() + ")");
                        return;
                    }
                    if (response.body() != null) {
                        processOllamaResponse(response.body().string());
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "요청 전송 예외", e);
            postError("요청 전송 중 오류 발생: " + e.getMessage());
        }
    }

    private JSONObject buildRequestPayload() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", MODEL_NAME);
        payload.put("stream", false);

        JSONArray messagesArray = new JSONArray();

        // 1. 마지막 사용자 입력 가져오기
        String lastUserPrompt = "";
        synchronized (chatHistory) {
            for (int i = chatHistory.size() - 1; i >= 0; i--) {
                JSONObject turn = chatHistory.get(i);
                if ("user".equals(turn.optString("role"))) {
                    lastUserPrompt = turn.optString("content", "");
                    break;
                }
            }
        }

        List<String> currentPois = poiManager.getPoiNames();
        String poiListStr = currentPois.isEmpty() ? "없음" : String.join(", ", currentPois);

        // 2. 스마트 의도 판별: 사용자가 등록된 POI 이름을 말했거나, 이동 관련 표현을 사용했는지 확인
        boolean isNavigationIntent = checkNavigationIntent(lastUserPrompt, currentPois);

        // 3. 상황별 시스템 프롬프트 및 도구(Tools) 주입
        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");

        if (isNavigationIntent) {
            // 이동 의도일 때의 프롬프트 (등록된 장소 vs 미등록 장소 규칙 명시)
            systemMsg.put("content",
                    "당신은 자율주행 모바일 로봇 비서 '터틀(Turtle)'입니다.\n" +
                            "현재 지도에 등록된 목적지 목록: [" + poiListStr + "]\n\n" +
                            "[규칙]\n" +
                            "1. 사용자가 요청한 목적지가 [등록된 목적지 목록]에 존재하면: 'navigate_to_poi' 도구를 호출하세요.\n" +
                            "2. 사용자가 요청한 목적지가 목록에 없거나, 등록된 목적지가 하나도 없는 경우: 절대 도구를 호출하지 말고, '해당 장소는 지도에 설정되어 있지 않습니다. 먼저 맵에서 POI를 설정해 주세요.'라고 답변하세요."
            );
            messagesArray.put(systemMsg);

            // 이동 의도일 때만 Tools 전달
            JSONArray tools = new JSONArray();
            JSONObject toolObj = new JSONObject();
            toolObj.put("type", "function");

            JSONObject function = new JSONObject();
            function.put("name", "navigate_to_poi");
            function.put("description", "로봇을 등록된 목적지로 이동시킵니다.");

            JSONObject parameters = new JSONObject();
            parameters.put("type", "object");

            JSONObject properties = new JSONObject();
            JSONObject targetNamesProp = new JSONObject();
            targetNamesProp.put("type", "array");

            JSONObject items = new JSONObject();
            items.put("type", "string");
            if (!currentPois.isEmpty()) {
                JSONArray enumValues = new JSONArray();
                for (String name : currentPois) enumValues.put(name);
                items.put("enum", enumValues);
            }
            targetNamesProp.put("items", items);

            properties.put("target_names", targetNamesProp);
            parameters.put("properties", properties);

            JSONArray required = new JSONArray();
            required.put("target_names");
            parameters.put("required", required);

            function.put("parameters", parameters);
            toolObj.put("function", function);
            tools.put(toolObj);

            payload.put("tools", tools);
        } else {
            // 일상 대화일 때의 프롬프트 (도구 제외)
            systemMsg.put("content",
                    "당신은 자율주행 모바일 로봇 비서 '터틀(Turtle)'입니다. " +
                            "사용자와의 일상 대화에 한국어로 친절하고 명확하게(1~2문장) 대답하세요."
            );
            messagesArray.put(systemMsg);
        }

        // 최근 6개 턴 히스토리 추가
        synchronized (chatHistory) {
            int startIdx = Math.max(0, chatHistory.size() - 6);
            for (int i = startIdx; i < chatHistory.size(); i++) {
                messagesArray.put(chatHistory.get(i));
            }
        }
        payload.put("messages", messagesArray);

        return payload;
    }

    /**
     * 유연한 이동 의도 판별기:
     * 1) 등록된 POI 이름이 포함되어 있거나
     * 2) 다양한 형태의 이동 동사/조사가 포함되어 있는 경우 true 반환
     */
    private boolean checkNavigationIntent(String text, List<String> currentPois) {
        if (text == null || text.trim().isEmpty()) return false;
        String cleaned = text.trim();

        // 1. 등록된 POI 이름 중 하나라도 문장에 들어있는 경우 (예: "식탁", "주방 가자")
        for (String poi : currentPois) {
            if (cleaned.contains(poi)) return true;
        }

        // 2. 다양한 이동 관련 키워드/동사/조사 패턴 매칭
        String[] moveKeywords = {
                "가줘", "가라", "가자", "가고", "갈래", "갈게", "가자고", "가라", "가요",
                "이동", "출발", "안내", "와줘", "와라", "들러", "도착", "순찰",
                "데려다", "데려가", "보내줘", "향해", "어디로", "위치로", "복귀"
        };
        for (String kw : moveKeywords) {
            if (cleaned.contains(kw)) return true;
        }

        // 3. '장소로', '곳으로' 형태의 조사 매칭
        return cleaned.matches(".*(로|으로|에)\\s*(가|갈|출발|이동|안내|와).*");
    }

    private void processOllamaResponse(String jsonStr) {
        try {
            Log.d(TAG, "Ollama 원본 응답: " + jsonStr);

            JSONObject root = new JSONObject(jsonStr);
            JSONObject message = root.getJSONObject("message");

            synchronized (chatHistory) {
                chatHistory.add(message);
            }

            // 1. Tool Call이 정상적으로 들어왔는지 확인
            if (message.has("tool_calls") && !message.isNull("tool_calls")) {
                JSONArray toolCalls = message.getJSONArray("tool_calls");
                for (int i = 0; i < toolCalls.length(); i++) {
                    JSONObject toolCall = toolCalls.getJSONObject(i);
                    JSONObject function = toolCall.getJSONObject("function");
                    String funcName = function.getString("name");
                    JSONObject args = function.optJSONObject("arguments");

                    if (args != null) {
                        JSONArray sanitizedTargets = extractTargetNames(args);
                        if (sanitizedTargets.length() > 0) {
                            JSONObject cleanArgs = new JSONObject();
                            cleanArgs.put("target_names", sanitizedTargets);
                            postFunctionCall(funcName, cleanArgs);
                            return;
                        }
                    }
                }
            }

            // 2. 일반 텍스트 응답 처리 (일상 대화 및 미등록 장소 안내 멘트)
            if (message.has("content") && !message.isNull("content")) {
                String text = message.getString("content").trim();
                if (!text.isEmpty()) {
                    postResponse(text);
                    return;
                }
            }

            postResponse("명령을 명확하게 이해하지 못했습니다. 다시 말씀해 주세요.");

        } catch (Exception e) {
            Log.e(TAG, "Ollama 응답 파싱 실패", e);
            postError("응답 파싱 오류: " + e.getMessage());
        }
    }

    private JSONArray extractTargetNames(JSONObject args) {
        JSONArray result = new JSONArray();

        JSONArray arr = args.optJSONArray("target_names");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String val = arr.optString(i, "").replaceAll("[\\[\\]'\"\\s]", "").trim();
                if (!val.isEmpty()) result.put(val);
            }
            if (result.length() > 0) return result;
        }

        String strVal = args.optString("target_names", "");
        if (strVal.isEmpty()) {
            strVal = args.optString("target_name", "");
        }

        if (!strVal.isEmpty()) {
            String cleaned = strVal.replaceAll("[\\[\\]'\"]", "").trim();
            String[] tokens = cleaned.split("[,\\s]+");
            for (String token : tokens) {
                String target = token.trim();
                if (!target.isEmpty()) {
                    result.put(target);
                }
            }
        }

        return result;
    }

    private void postResponse(String text) {
        mainHandler.post(() -> {
            if (listener != null) listener.onResponse(text);
        });
    }

    private void postFunctionCall(String name, JSONObject args) {
        mainHandler.post(() -> {
            if (listener != null) listener.onFunctionCall(name, args);
        });
    }

    private void postError(String errorMsg) {
        mainHandler.post(() -> {
            if (listener != null) listener.onError(errorMsg);
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}