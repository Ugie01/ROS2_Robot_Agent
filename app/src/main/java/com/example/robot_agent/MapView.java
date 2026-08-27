package com.example.robot_agent;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class MapView extends View {

    public static final int MODE_SINGLE_GOAL = 0;
    public static final int MODE_WAYPOINTS = 1;
    public static final int MODE_INITIAL_POSE = 2;
    public static final int MODE_ADD_POI = 3;

    private int currentMode = MODE_SINGLE_GOAL;

    private Bitmap cachedMapBitmap;
    private int[] cachedPixels;
    private int prevWidth = 0, prevHeight = 0;
    private float resolution = 0.05f;
    private float originX = 0f, originY = 0f;
    private int mapWidth = 0, mapHeight = 0;

    private float robotWorldX = 0f, robotWorldY = 0f, robotYaw = 0f;
    private boolean hasRobotPose = false;

    private float goalWorldX = 0f, goalWorldY = 0f, goalYaw = 0f;
    private float goalPixelX = -1f, goalPixelY = -1f;
    private boolean hasGoal = false;
    private boolean isDraggingHeading = false;

    private float initPoseWorldX = 0f, initPoseWorldY = 0f, initPoseYaw = 0f;
    private float initPosePixelX = -1f, initPosePixelY = -1f;
    private boolean hasInitPose = false;

    // POI 추가 임시 좌표
    private float poiTempWorldX = 0f, poiTempWorldY = 0f, poiTempYaw = 0f;
    private float poiTempPixelX = -1f, poiTempPixelY = -1f;
    private boolean hasPoiTemp = false;

    public static class Waypoint {
        public float x, y, yaw;
        public float px, py;
        public Waypoint(float x, float y, float yaw, float px, float py) {
            this.x = x; this.y = y; this.yaw = yaw;
            this.px = px; this.py = py;
        }
    }
    private final List<Waypoint> waypointList = new ArrayList<>();
    private final List<PoiManager.PoiItem> poiList = new ArrayList<>();

    private Paint goalPaint, goalHeadingPaint, robotPaint, robotHeadingPaint;
    private Paint initPosePaint, initPoseHeadingPaint;
    private Paint textPaint, waypointPaint, pathLinePaint;
    private Paint poiPaint, poiHeadingPaint, poiLabelPaint, poiBgPaint;
    private final Matrix matrix = new Matrix();

    public interface OnMapEventListener {
        void onSingleGoalSelected(float worldX, float worldY, float yaw);
        void onInitialPoseSelected(float worldX, float worldY, float yaw);
        void onWaypointsUpdated(int count);
        void onPoiSelected(float worldX, float worldY, float yaw, float px, float py);
    }
    private OnMapEventListener mapEventListener;

    public MapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    private void initPaints() {
        goalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        goalPaint.setColor(Color.parseColor("#FF1744"));
        goalPaint.setStyle(Paint.Style.FILL);

        goalHeadingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        goalHeadingPaint.setColor(Color.parseColor("#FF5252"));
        goalHeadingPaint.setStrokeWidth(4f);

        initPosePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        initPosePaint.setColor(Color.parseColor("#00E676"));
        initPosePaint.setStyle(Paint.Style.FILL);

        initPoseHeadingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        initPoseHeadingPaint.setColor(Color.parseColor("#B9F6CA"));
        initPoseHeadingPaint.setStrokeWidth(5f);

        robotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        robotPaint.setColor(Color.parseColor("#00E5FF"));
        robotPaint.setStyle(Paint.Style.FILL);

        robotHeadingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        robotHeadingPaint.setColor(Color.parseColor("#76FF03"));
        robotHeadingPaint.setStrokeWidth(4f);

        waypointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        waypointPaint.setColor(Color.parseColor("#FFD600"));
        waypointPaint.setStyle(Paint.Style.FILL);

        pathLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pathLinePaint.setColor(Color.parseColor("#FFD600"));
        pathLinePaint.setStrokeWidth(3f);
        pathLinePaint.setStyle(Paint.Style.STROKE);
        pathLinePaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));

        poiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        poiPaint.setColor(Color.parseColor("#E040FB"));
        poiPaint.setStyle(Paint.Style.FILL);

        poiHeadingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        poiHeadingPaint.setColor(Color.parseColor("#EA80FC"));
        poiHeadingPaint.setStrokeWidth(4f);

        poiLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        poiLabelPaint.setColor(Color.WHITE);
        poiLabelPaint.setTextSize(22f);
        poiLabelPaint.setFakeBoldText(true);

        poiBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        poiBgPaint.setColor(Color.parseColor("#884A148C"));
        poiBgPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(24f);
        textPaint.setFakeBoldText(true);
    }

    public void setOnMapEventListener(OnMapEventListener listener) {
        this.mapEventListener = listener;
    }

    public void setMode(int mode) {
        this.currentMode = mode;
        clearAllMarkers();
    }

    public void setPois(List<PoiManager.PoiItem> pois) {
        synchronized (poiList) {
            poiList.clear();
            if (pois != null) {
                poiList.addAll(pois);
            }
        }
        postInvalidate();
    }

    public void clearAllMarkers() {
        hasGoal = false;
        hasInitPose = false;
        hasPoiTemp = false;
        isDraggingHeading = false;
        waypointList.clear();
        if (mapEventListener != null) mapEventListener.onWaypointsUpdated(0);
        postInvalidate();
    }

    public synchronized void clearMap() {
        this.cachedMapBitmap = null;
        this.cachedPixels = null;
        this.prevWidth = 0;
        this.prevHeight = 0;
        this.mapWidth = 0;
        this.mapHeight = 0;
        this.hasRobotPose = false;
        this.robotWorldX = 0f;
        this.robotWorldY = 0f;
        this.robotYaw = 0f;
        clearAllMarkers();
        postInvalidate();
    }

    public synchronized void removeFirstWaypoint() {
        if (!waypointList.isEmpty()) {
            waypointList.remove(0);
            if (mapEventListener != null) {
                mapEventListener.onWaypointsUpdated(waypointList.size());
            }
            postInvalidate();
        }
    }

    public List<Waypoint> getWaypoints() {
        return waypointList;
    }

    public synchronized void updateMap(byte[] data, int width, int height, float resolution, float originX, float originY) {
        this.mapWidth = width;
        this.mapHeight = height;
        this.resolution = resolution;
        this.originX = originX;
        this.originY = originY;

        int totalPixels = width * height;
        if (cachedPixels == null || prevWidth != width || prevHeight != height) {
            cachedPixels = new int[totalPixels];
            cachedMapBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            prevWidth = width;
            prevHeight = height;
        }

        // RViz2와 완벽 동기화되는 표준 OccupancyGrid 픽셀 매핑
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int srcIdx = (height - 1 - y) * width + x;
                if (srcIdx < data.length) {
                    byte val = data[srcIdx];
                    int color;
                    if (val == -1) color = 0xFF2E2E2E;      // 미탐색 (어두운 회색)
                    else if (val == 0) color = 0xFFE0E0E0;   // 주행 가능 영역 (밝은 회색)
                    else if (val == 100) color = 0xFF000000; // 장애물/벽 (검은색)
                    else color = 0xFFFF5722;                 // Costmap 영역

                    cachedPixels[y * width + x] = color;
                }
            }
        }
        cachedMapBitmap.setPixels(cachedPixels, 0, width, 0, 0, width, height);
        postInvalidate();
    }

    public synchronized void updateRobotPose(float worldX, float worldY, float yaw) {
        this.robotWorldX = worldX;
        this.robotWorldY = worldY;
        this.robotYaw = yaw;
        this.hasRobotPose = true;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (cachedMapBitmap != null) {
            float scaleX = (float) getWidth() / cachedMapBitmap.getWidth();
            float scaleY = (float) getHeight() / cachedMapBitmap.getHeight();
            float scale = Math.min(scaleX, scaleY);

            float dx = (getWidth() - cachedMapBitmap.getWidth() * scale) / 2f;
            float dy = (getHeight() - cachedMapBitmap.getHeight() * scale) / 2f;

            matrix.reset();
            matrix.postScale(scale, scale);
            matrix.postTranslate(dx, dy);
            canvas.drawBitmap(cachedMapBitmap, matrix, null);

            // 1. 등록된 POI 마커 및 라벨 렌더링
            synchronized (poiList) {
                for (PoiManager.PoiItem poi : poiList) {
                    float px = poi.px;
                    float py = poi.py;
                    if (px < 0 || py < 0) {
                        if (resolution > 0) {
                            px = (poi.x - originX) / resolution;
                            py = mapHeight - 1 - (poi.y - originY) / resolution;
                        }
                    }
                    if (px >= 0 && py >= 0) {
                        float sx = dx + px * scale;
                        float sy = dy + py * scale;

                        canvas.drawCircle(sx, sy, 11f, poiPaint);
                        float hx = sx + (float) (25f * Math.cos(poi.yaw));
                        float hy = sy - (float) (25f * Math.sin(poi.yaw));
                        canvas.drawLine(sx, sy, hx, hy, poiHeadingPaint);

                        String label = "🚩 " + poi.name;
                        float textWidth = poiLabelPaint.measureText(label);
                        canvas.drawRect(sx - textWidth / 2f - 8, sy - 40, sx + textWidth / 2f + 8, sy - 14, poiBgPaint);
                        canvas.drawText(label, sx - textWidth / 2f, sy - 20, poiLabelPaint);
                    }
                }
            }

            // 2. 다중 경유지(Waypoints)
            if (!waypointList.isEmpty()) {
                float lastX = -1, lastY = -1;
                for (int i = 0; i < waypointList.size(); i++) {
                    Waypoint wp = waypointList.get(i);
                    float sx = dx + wp.px * scale;
                    float sy = dy + wp.py * scale;

                    if (lastX != -1) {
                        canvas.drawLine(lastX, lastY, sx, sy, pathLinePaint);
                    }
                    lastX = sx;
                    lastY = sy;

                    canvas.drawCircle(sx, sy, 13f, waypointPaint);
                    canvas.drawText(String.valueOf(i + 1), sx - 6, sy + 8, textPaint);
                }
            }

            // 3. 단일 목표(Goal) 마커
            if (hasGoal && goalPixelX >= 0 && goalPixelY >= 0) {
                float sx = dx + goalPixelX * scale;
                float sy = dy + goalPixelY * scale;

                canvas.drawCircle(sx, sy, 12f, goalPaint);
                float hx = sx + (float) (35f * Math.cos(goalYaw));
                float hy = sy - (float) (35f * Math.sin(goalYaw));
                canvas.drawLine(sx, sy, hx, hy, goalHeadingPaint);
                canvas.drawCircle(hx, hy, 4f, goalHeadingPaint);
            }

            // 4. 2D Pose Estimate (초기 위치 추정)
            if (hasInitPose && initPosePixelX >= 0 && initPosePixelY >= 0) {
                float sx = dx + initPosePixelX * scale;
                float sy = dy + initPosePixelY * scale;

                canvas.drawCircle(sx, sy, 14f, initPosePaint);
                float hx = sx + (float) (40f * Math.cos(initPoseYaw));
                float hy = sy - (float) (40f * Math.sin(initPoseYaw));
                canvas.drawLine(sx, sy, hx, hy, initPoseHeadingPaint);
                canvas.drawCircle(hx, hy, 5f, initPoseHeadingPaint);
            }

            // 5. POI 임시 터치 마커
            if (hasPoiTemp && poiTempPixelX >= 0 && poiTempPixelY >= 0) {
                float sx = dx + poiTempPixelX * scale;
                float sy = dy + poiTempPixelY * scale;

                canvas.drawCircle(sx, sy, 14f, poiPaint);
                float hx = sx + (float) (35f * Math.cos(poiTempYaw));
                float hy = sy - (float) (35f * Math.sin(poiTempYaw));
                canvas.drawLine(sx, sy, hx, hy, poiHeadingPaint);
            }

            // 6. 로봇 현재 위치 (RViz2 /odom, /amcl_pose와 동기화)
            if (hasRobotPose && resolution > 0) {
                // X, Y 좌표가 뒤바뀐 경우 교정
                float rx = (robotWorldX - originX) / resolution;
                float ry = mapHeight - 1 - (robotWorldY - originY) / resolution;

                float sx = dx + rx * scale;
                float sy = dy + ry * scale;

                canvas.drawCircle(sx, sy, 12f, robotPaint);

                // ROS Yaw는 반시계방향(+) 기준이므로 화면 Canvas Y축(아래로 갈수록 증가)과 맞추기 위해 sin에 마이너스 적용
                float hx = sx + (float) (28f * Math.cos(robotYaw));
                float hy = sy - (float) (28f * Math.sin(robotYaw));
                canvas.drawLine(sx, sy, hx, hy, robotHeadingPaint);
                canvas.drawCircle(hx, hy, 4f, robotHeadingPaint);
            }
        } else {
            canvas.drawText("맵 데이터 대기 중 (/map)...", 40, getHeight() / 2f, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (cachedMapBitmap == null) return super.onTouchEvent(event);

        float scaleX = (float) getWidth() / cachedMapBitmap.getWidth();
        float scaleY = (float) getHeight() / cachedMapBitmap.getHeight();
        float scale = Math.min(scaleX, scaleY);
        float dx = (getWidth() - cachedMapBitmap.getWidth() * scale) / 2f;
        float dy = (getHeight() - cachedMapBitmap.getHeight() * scale) / 2f;

        float touchX = (event.getX() - dx) / scale;
        float touchY = (event.getY() - dy) / scale;

        if (touchX < 0 || touchX >= mapWidth || touchY < 0 || touchY >= mapHeight) {
            return super.onTouchEvent(event);
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (currentMode == MODE_WAYPOINTS) {
                    float gridY = mapHeight - 1 - touchY;
                    float wx = originX + touchX * resolution;
                    float wy = originY + gridY * resolution;
                    waypointList.add(new Waypoint(wx, wy, 0f, touchX, touchY));
                    if (mapEventListener != null) {
                        mapEventListener.onWaypointsUpdated(waypointList.size());
                    }
                    invalidate();
                } else if (currentMode == MODE_INITIAL_POSE) {
                    initPosePixelX = touchX;
                    initPosePixelY = touchY;
                    float gridY = mapHeight - 1 - touchY;
                    initPoseWorldX = originX + touchX * resolution;
                    initPoseWorldY = originY + gridY * resolution;
                    initPoseYaw = 0f;
                    hasInitPose = true;
                    isDraggingHeading = true;
                    invalidate();
                } else if (currentMode == MODE_ADD_POI) {
                    poiTempPixelX = touchX;
                    poiTempPixelY = touchY;
                    float gridY = mapHeight - 1 - touchY;
                    poiTempWorldX = originX + touchX * resolution;
                    poiTempWorldY = originY + gridY * resolution;
                    poiTempYaw = 0f;
                    hasPoiTemp = true;
                    isDraggingHeading = true;
                    invalidate();
                } else {
                    goalPixelX = touchX;
                    goalPixelY = touchY;
                    float gridY = mapHeight - 1 - touchY;
                    goalWorldX = originX + touchX * resolution;
                    goalWorldY = originY + gridY * resolution;
                    goalYaw = 0f;
                    hasGoal = true;
                    isDraggingHeading = true;
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isDraggingHeading) {
                    if (currentMode == MODE_INITIAL_POSE) {
                        float dX = touchX - initPosePixelX;
                        float dY = -(touchY - initPosePixelY);
                        if (Math.hypot(dX, dY) > 5) {
                            initPoseYaw = (float) Math.atan2(dY, dX);
                            invalidate();
                        }
                    } else if (currentMode == MODE_ADD_POI) {
                        float dX = touchX - poiTempPixelX;
                        float dY = -(touchY - poiTempPixelY);
                        if (Math.hypot(dX, dY) > 5) {
                            poiTempYaw = (float) Math.atan2(dY, dX);
                            invalidate();
                        }
                    } else if (currentMode == MODE_SINGLE_GOAL) {
                        float dX = touchX - goalPixelX;
                        float dY = -(touchY - goalPixelY);
                        if (Math.hypot(dX, dY) > 5) {
                            goalYaw = (float) Math.atan2(dY, dX);
                            invalidate();
                        }
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (isDraggingHeading) {
                    isDraggingHeading = false;
                    if (currentMode == MODE_INITIAL_POSE && mapEventListener != null) {
                        mapEventListener.onInitialPoseSelected(initPoseWorldX, initPoseWorldY, initPoseYaw);
                    } else if (currentMode == MODE_ADD_POI && mapEventListener != null) {
                        mapEventListener.onPoiSelected(poiTempWorldX, poiTempWorldY, poiTempYaw, poiTempPixelX, poiTempPixelY);
                        hasPoiTemp = false;
                    } else if (currentMode == MODE_SINGLE_GOAL && mapEventListener != null) {
                        mapEventListener.onSingleGoalSelected(goalWorldX, goalWorldY, goalYaw);
                    }
                    invalidate();
                }
                return true;
        }
        return super.onTouchEvent(event);
    }
}