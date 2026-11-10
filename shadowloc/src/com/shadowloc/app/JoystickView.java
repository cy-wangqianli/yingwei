package com.shadowloc.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** 自绘虚拟摇杆：拖动返回 -1..1 的归一化向量（x=东，y=北）。 */
public class JoystickView extends View {

    public interface Listener {
        void onMove(float x, float y);
    }

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float knobX;
    private float knobY;
    private float radius = 60f;
    private float centerX;
    private float centerY;
    private Listener listener;

    public JoystickView(Context context) {
        this(context, null);
    }

    public JoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(false);
        basePaint.setColor(0xE610161E);
        basePaint.setStyle(Paint.Style.FILL);
        ringPaint.setColor(0x8C3DDC97);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1.5f));
        tickPaint.setColor(0x553DDC97);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(dp(1f));
        knobPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(0x8C8B98A9);
        textPaint.setTextSize(dp(10f));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public float getKnobX() {
        return knobX;
    }

    public float getKnobY() {
        return knobY;
    }

    public void reset() {
        knobX = 0f;
        knobY = 0f;
        invalidate();
        if (listener != null) {
            listener.onMove(0f, 0f);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        radius = Math.min(w, h) / 2f - dp(16f);
        if (radius < dp(24f)) {
            radius = Math.max(24f, Math.min(w, h) / 2f - dp(4f));
        }
        knobPaint.setShader(new RadialGradient(centerX, centerY, Math.max(1f, radius * 0.42f),
                new int[]{0xFF7CF3C0, 0xFF3DDC97, 0xFF1F9E6D}, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.drawCircle(centerX, centerY, radius, basePaint);
        c.drawCircle(centerX, centerY, radius - dp(1f), ringPaint);
        c.drawCircle(centerX, centerY, radius * 0.55f, tickPaint);

        // 四向刻度
        for (int i = 0; i < 4; i++) {
            double ang = Math.PI / 2 * i;
            float x1 = (float) (centerX + Math.cos(ang) * (radius - dp(9f)));
            float y1 = (float) (centerY + Math.sin(ang) * (radius - dp(9f)));
            float x2 = (float) (centerX + Math.cos(ang) * (radius - dp(2f)));
            float y2 = (float) (centerY + Math.sin(ang) * (radius - dp(2f)));
            c.drawLine(x1, y1, x2, y2, tickPaint);
        }

        float kx = centerX + knobX * (radius - dp(20f));
        float ky = centerY + knobY * (radius - dp(20f));
        c.drawCircle(kx, ky, dp(19f), knobPaint);
        c.drawCircle(kx, ky, dp(6f), tickPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - centerX;
                float dy = e.getY() - centerY;
                float max = Math.max(1f, radius - dp(20f));
                double len = Math.sqrt(dx * dx + dy * dy);
                float nx;
                float ny;
                if (len > max) {
                    nx = (float) (dx / len);
                    ny = (float) (dy / len);
                } else {
                    nx = dx / max;
                    ny = dy / max;
                }
                // 留一点死区，避免手抖
                if (Math.abs(nx) < 0.06f) {
                    nx = 0f;
                }
                if (Math.abs(ny) < 0.06f) {
                    ny = 0f;
                }
                knobX = nx;
                knobY = ny;
                invalidate();
                if (listener != null) {
                    listener.onMove(nx, -ny); // 屏幕 y 向下，地理北为上
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                reset();
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
