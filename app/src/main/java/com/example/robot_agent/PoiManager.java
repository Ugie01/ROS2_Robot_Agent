package com.example.robot_agent;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PoiManager {
    private static final String TAG = "PoiManager";
    private static final String POI_FILE_NAME = "poi_waypoints.json";

    public static class PoiItem {
        public String name;
        public float x;
        public float y;
        public float yaw;
        public float px;
        public float py;

        public PoiItem(String name, float x, float y, float yaw, float px, float py) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.yaw = yaw;
            this.px = px;
            this.py = py;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("name", name);
                obj.put("x", (double) x);
                obj.put("y", (double) y);
                obj.put("yaw", (double) yaw);
                obj.put("px", (double) px);
                obj.put("py", (double) py);
            } catch (Exception e) {
                Log.e(TAG, "POI 직렬화 예외: " + e.getMessage());
            }
            return obj;
        }

        public static PoiItem fromJson(JSONObject obj) {
            try {
                String name = obj.getString("name");
                float x = (float) obj.getDouble("x");
                float y = (float) obj.getDouble("y");
                float yaw = (float) obj.optDouble("yaw", 0.0);
                float px = (float) obj.optDouble("px", -1.0);
                float py = (float) obj.optDouble("py", -1.0);
                return new PoiItem(name, x, y, yaw, px, py);
            } catch (Exception e) {
                Log.e(TAG, "POI 역직렬화 예외: " + e.getMessage());
                return null;
            }
        }
    }

    private final Map<String, PoiItem> poiMap = Collections.synchronizedMap(new LinkedHashMap<>());

    public synchronized void addPoi(String name, float x, float y, float yaw, float px, float py) {
        if (name == null || name.trim().isEmpty()) return;
        poiMap.put(name.trim(), new PoiItem(name.trim(), x, y, yaw, px, py));
    }

    public synchronized void removePoi(String name) {
        if (name != null) {
            poiMap.remove(name.trim());
        }
    }

    public synchronized PoiItem getPoi(String name) {
        if (name == null) return null;
        return poiMap.get(name.trim());
    }

    public synchronized List<PoiItem> getAllPois() {
        return new ArrayList<>(poiMap.values());
    }

    public synchronized List<String> getPoiNames() {
        return new ArrayList<>(poiMap.keySet());
    }

    public synchronized boolean hasPoi(String name) {
        return name != null && poiMap.containsKey(name.trim());
    }

    public synchronized void clear() {
        poiMap.clear();
    }

    /**
     * 로컬 JSON 파일(poi_waypoints.json)로 저장
     */
    public synchronized boolean saveToFile(Context context) {
        try {
            JSONArray array = new JSONArray();
            for (PoiItem item : poiMap.values()) {
                array.put(item.toJson());
            }

            File file = new File(context.getFilesDir(), POI_FILE_NAME);
            try (FileOutputStream fos = new FileOutputStream(file);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(array.toString(2));
                writer.flush();
            }
            Log.d(TAG, "POI 파일 저장 성공: " + file.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "POI 파일 저장 실패", e);
            return false;
        }
    }

    /**
     * 로컬 JSON 파일에서 POI 불러오기
     */
    public synchronized boolean loadFromFile(Context context) {
        File file = new File(context.getFilesDir(), POI_FILE_NAME);
        if (!file.exists()) {
            Log.d(TAG, "저장된 POI 파일이 없습니다.");
            return false;
        }

        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            JSONArray array = new JSONArray(sb.toString());
            poiMap.clear();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                PoiItem item = PoiItem.fromJson(obj);
                if (item != null) {
                    poiMap.put(item.name, item);
                }
            }
            Log.d(TAG, "POI 파일 로드 완료 (" + poiMap.size() + "개)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "POI 파일 로드 실패", e);
            return false;
        }
    }

    /**
     * ROS 2 토픽(/save_named_poses) 발행용 JSON 포맷 변환
     */
    public synchronized String toJsonString() {
        try {
            JSONArray array = new JSONArray();
            for (PoiItem item : poiMap.values()) {
                array.put(item.toJson());
            }
            return array.toString();
        } catch (Exception e) {
            Log.e(TAG, "JSON 직렬화 오류", e);
            return "[]";
        }
    }
}