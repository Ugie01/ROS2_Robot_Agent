package com.example.robot_agent;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class JoystickView extends View {
    private Paint basePaint, handlePaint;
    private float centerX, centerY, baseRadius, handleRadius, handleX, handleY;

    public interface JoystickListener {
        void onJoystickMoved(float xPercent, float yPercent);
    }
    private JoystickListener listener;

    public JoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setColor(Color.DKGRAY);
        basePaint.setStyle(Paint.Style.FILL);

        handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handlePaint.setColor(Color.CYAN);
        handlePaint.setStyle(Paint.Style.FILL);
    }

    public void setJoystickListener(JoystickListener listener) {
        this.listener = listener;
    }

    public void resetJoystick() {
        handleX = centerX;
        handleY = centerY;
        if (listener != null) listener.onJoystickMoved(0f, 0f);
        postInvalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        baseRadius = Math.min(w, h) / 3f;
        handleRadius = baseRadius / 3f;
        handleX = centerX;
        handleY = centerY;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);
        canvas.drawCircle(handleX, handleY, handleRadius, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - centerX;
                float dy = event.getY() - centerY;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);

                if (distance < baseRadius) {
                    handleX = event.getX();
                    handleY = event.getY();
                } else {
                    float ratio = baseRadius / distance;
                    handleX = centerX + dx * ratio;
                    handleY = centerY + dy * ratio;
                }

                float xPercent = (handleX - centerX) / baseRadius;
                float yPercent = -(handleY - centerY) / baseRadius;
                if (listener != null) listener.onJoystickMoved(xPercent, yPercent);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handleX = centerX;
                handleY = centerY;
                if (listener != null) listener.onJoystickMoved(0f, 0f);
                break;
        }
        invalidate();
        return true;
    }
}