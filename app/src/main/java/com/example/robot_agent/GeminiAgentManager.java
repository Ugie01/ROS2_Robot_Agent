package com.example.robot_agent;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GeminiAgentManager {
    private static final String TAG = "GeminiAgentManager";

    // 구글 Gemini 공식 엔드포인트
    private static final String GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent";

    private static final String[] CANDIDATE_ENDPOINTS = new String[]{
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent",
            "https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash:generateContent"
    };

    private volatile String activeEndpoint = GEMINI_API_URL;

    public interface GeminiAgentListener {
        void onResponse(String textResponse);
        void onFunctionCall(String functionName, JSONObject arguments);
        void onGeminiError(String errorMessage);
    }

    private final String apiKey;
    private final PoiManager poiManager;
    private final GeminiAgentListener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<JSONObject> chatHistory = new ArrayList<>();

    public GeminiAgentManager(String rawApiKey, PoiManager poiManager, GeminiAgentListener listener) {
        this.apiKey = sanitizeApiKey(rawApiKey);
        this.poiManager = poiManager;
        this.listener = listener;
    }

    private String sanitizeApiKey(String rawKey) {
        if (rawKey == null) return "";
        String clean = rawKey.trim();
        if ((clean.startsWith("\"") && clean.endsWith("\"")) || (clean.startsWith("'") && clean.endsWith("'"))) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        return clean;
    }

    public void clearHistory() {
        synchronized (chatHistory) {
            chatHistory.clear();
        }
    }

    public void sendUserMessage(String userPrompt) {
        if (userPrompt == null || userPrompt.trim().isEmpty()) return;
        if (apiKey.isEmpty() || apiKey.equals("YOUR_GEMINI_API_KEY")) {
            postError("Gemini API Key가 local.properties에 설정되지 않았습니다.");
            return;
        }

        executor.execute(() -> {
            try {
                JSONObject userTurn = new JSONObject();
                userTurn.put("role", "user");
                JSONArray parts = new JSONArray();
                JSONObject textPart = new JSONObject();
                textPart.put("text", userPrompt);
                parts.put(textPart);
                userTurn.put("parts", parts);

                synchronized (chatHistory) {
                    chatHistory.add(userTurn);
                }

                executeGeminiRequestWithFallback();
            } catch (Exception e) {
                Log.e(TAG, "사용자 메시지 생성 예외", e);
                postError("메시지 생성 오류: " + e.getMessage());
            }
        });
    }

    /**
     * [중요 400 수정]: Gemini API는 함수 응답 시 role을 'user'로 지정해야 합니다.
     */
    public void sendFunctionResult(String functionName, JSONObject functionResponseContent) {
        executor.execute(() -> {
            try {
                JSONObject funcResponseTurn = new JSONObject();
                funcResponseTurn.put("role", "user"); // role: "function" -> "user" 로 수정

                JSONArray parts = new JSONArray();
                JSONObject part = new JSONObject();
                JSONObject functionResponse = new JSONObject();
                functionResponse.put("name", functionName);
                functionResponse.put("response", functionResponseContent);

                part.put("functionResponse", functionResponse);
                parts.put(part);
                funcResponseTurn.put("parts", parts);

                synchronized (chatHistory) {
                    chatHistory.add(funcResponseTurn);
                }

                executeGeminiRequestWithFallback();
            } catch (Exception e) {
                Log.e(TAG, "함수 응답 처리 예외", e);
                postError("함수 결과 처리 오류: " + e.getMessage());
            }
        });
    }

    private void executeGeminiRequestWithFallback() {
        if (tryCallApi(activeEndpoint)) return;

        for (String endpoint : CANDIDATE_ENDPOINTS) {
            if (endpoint.equals(activeEndpoint)) continue;
            Log.d(TAG, "대체 엔드포인트 시도: " + endpoint);
            if (tryCallApi(endpoint)) {
                activeEndpoint = endpoint;
                Log.d(TAG, "작동 엔드포인트 자동 전환 완료: " + activeEndpoint);
                return;
            }
        }
    }

    private boolean tryCallApi(String endpointUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpointUrl + "?key=" + apiKey);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            JSONObject requestPayload = buildRequestPayload();

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestPayload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            InputStream is = (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream();

            StringBuilder responseBuilder = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    responseBuilder.append(line);
                }
            }

            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                Log.w(TAG, "엔드포인트 404: " + endpointUrl);
                return false;
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                String errorDetail = responseBuilder.toString();
                Log.e(TAG, "Gemini API 오류 (Code " + responseCode + "): " + errorDetail);
                postError("Gemini 오류 (" + responseCode + "): " + extractErrorMessage(errorDetail));
                return true;
            }

            processGeminiResponse(responseBuilder.toString());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "통신 오류: " + endpointUrl, e);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private JSONObject buildRequestPayload() throws Exception {
        JSONObject payload = new JSONObject();

        // 1. 대화 히스토리
        JSONArray contentsArray = new JSONArray();
        synchronized (chatHistory) {
            for (JSONObject turn : chatHistory) {
                contentsArray.put(turn);
            }
        }
        payload.put("contents", contentsArray);

        // 2. System Instruction
        JSONObject systemInstruction = new JSONObject();
        JSONArray sysParts = new JSONArray();
        JSONObject sysTextPart = new JSONObject();
        sysTextPart.put("text", "당신은 자율주행 모바일 로봇의 지능형 비서 '터틀(Turtle)'입니다. " +
                "사용자가 특정 목적지로 이동하라는 명령을 내리면 반드시 'navigate_to_poi' 함수를 호출하세요. " +
                "일반 대화나 질문에는 한국어로 친절하고 간결하게 답변하세요.");
        sysParts.put(sysTextPart);
        systemInstruction.put("parts", sysParts);
        payload.put("systemInstruction", systemInstruction);

        // 3. Tool / Function Declaration
        JSONArray tools = new JSONArray();
        JSONObject toolObj = new JSONObject();
        JSONArray functionDeclarations = new JSONArray();

        JSONObject navFunction = new JSONObject();
        navFunction.put("name", "navigate_to_poi");
        navFunction.put("description", "등록된 특정 장소(POI)로 로봇을 자율주행 이동시킵니다.");

        JSONObject parameters = new JSONObject();
        parameters.put("type", "OBJECT");

        JSONObject properties = new JSONObject();
        JSONObject targetNameProp = new JSONObject();
        targetNameProp.put("type", "STRING");
        targetNameProp.put("description", "이동할 목적지 명칭 (예: 식탁, 주방, 거실 등)");

        List<String> poiNames = poiManager.getPoiNames();
        if (!poiNames.isEmpty()) {
            JSONArray enumValues = new JSONArray();
            for (String name : poiNames) {
                enumValues.put(name);
            }
            targetNameProp.put("enum", enumValues);
        }

        properties.put("target_name", targetNameProp);
        parameters.put("properties", properties);

        JSONArray required = new JSONArray();
        required.put("target_name");
        parameters.put("required", required);

        navFunction.put("parameters", parameters);
        functionDeclarations.put(navFunction);
        toolObj.put("functionDeclarations", functionDeclarations);
        tools.put(toolObj);

        payload.put("tools", tools);

        return payload;
    }

    private void processGeminiResponse(String jsonStr) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                postError("Gemini로부터 유효한 응답을 받지 못했습니다.");
                return;
            }

            JSONObject candidate = candidates.getJSONObject(0);
            JSONObject content = candidate.getJSONObject("content");
            JSONArray parts = content.getJSONArray("parts");

            synchronized (chatHistory) {
                chatHistory.add(content);
            }

            boolean isFunctionCall = false;
            StringBuilder textAccumulator = new StringBuilder();

            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);

                if (part.has("functionCall")) {
                    isFunctionCall = true;
                    JSONObject funcCall = part.getJSONObject("functionCall");
                    String funcName = funcCall.getString("name");
                    JSONObject args = funcCall.optJSONObject("args");
                    postFunctionCall(funcName, args != null ? args : new JSONObject());
                } else if (part.has("text")) {
                    textAccumulator.append(part.getString("text"));
                }
            }

            if (!isFunctionCall && textAccumulator.length() > 0) {
                postResponse(textAccumulator.toString());
            }

        } catch (Exception e) {
            Log.e(TAG, "Gemini 응답 파싱 실패", e);
            postError("응답 파싱 오류: " + e.getMessage());
        }
    }

    private String extractErrorMessage(String errorJson) {
        try {
            JSONObject obj = new JSONObject(errorJson);
            if (obj.has("error")) {
                JSONObject err = obj.getJSONObject("error");
                return err.optString("message", errorJson);
            }
        } catch (Exception ignored) {}
        return errorJson;
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
            if (listener != null) listener.onGeminiError(errorMsg);
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}