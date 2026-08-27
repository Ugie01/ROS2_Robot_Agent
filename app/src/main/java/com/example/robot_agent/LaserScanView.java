package com.example.robot_agent;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class LaserScanView extends View {
    private Paint gridPaint, pointPaint;
    private float[] ranges;
    private float angleMin = -3.14159f;
    private float angleIncrement = 0.01745f;
    private float maxRange = 6.0f;

    public LaserScanView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(Color.DKGRAY);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(2f);

        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointPaint.setColor(Color.RED);
        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setStrokeWidth(4f);
    }

    public synchronized void updateScan(float[] newRanges, float angleMin, float angleIncrement) {
        if (newRanges != null) {
            if (this.ranges == null || this.ranges.length != newRanges.length) {
                this.ranges = new float[newRanges.length];
            }
            System.arraycopy(newRanges, 0, this.ranges, 0, newRanges.length);
        }
        this.angleMin = angleMin;
        this.angleIncrement = angleIncrement;
        postInvalidate();
    }

    public synchronized void clearScan() {
        this.ranges = null;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - 10f;

        canvas.drawCircle(cx, cy, radius * 0.33f, gridPaint);
        canvas.drawCircle(cx, cy, radius * 0.66f, gridPaint);
        canvas.drawCircle(cx, cy, radius, gridPaint);
        canvas.drawLine(cx, 0, cx, getHeight(), gridPaint);
        canvas.drawLine(0, cy, getWidth(), cy, gridPaint);

        float[] localRanges;
        float localAngleMin, localAngleInc;
        synchronized (this) {
            if (ranges == null || ranges.length == 0) return;
            localRanges = ranges.clone();
            localAngleMin = angleMin;
            localAngleInc = angleIncrement;
        }

        float scale = radius / maxRange;
        float currentAngle = localAngleMin;
        for (float r : localRanges) {
            if (!Float.isInfinite(r) && !Float.isNaN(r) && r > 0.05f && r <= maxRange) {
                float px = cx - (float) (r * Math.sin(currentAngle)) * scale;
                float py = cy - (float) (r * Math.cos(currentAngle)) * scale;
                canvas.drawPoint(px, py, pointPaint);
            }
            currentAngle += localAngleInc;
        }
    }
}