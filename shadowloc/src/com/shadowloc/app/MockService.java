package com.shadowloc.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 模拟定位前台服务：向系统的 gps / network provider 注入测试位置。
 * 支持三种模式：定点、路线、悬浮摇杆。
 */
public class MockService extends Service {

    private static final String TAG = "ShadowLoc";

    public static final String ACTION_START_FIXED = "com.shadowloc.app.action.START_FIXED";
    public static final String ACTION_START_ROUTE = "com.shadowloc.app.action.START_ROUTE";
    public static final String ACTION_STOP = "com.shadowloc.app.action.STOP";
    public static final String ACTION_SHOW_OVERLAY = "com.shadowloc.app.action.SHOW_OVERLAY";
    public static final String ACTION_HIDE_OVERLAY = "com.shadowloc.app.action.HIDE_OVERLAY";

    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";
    public static final String EXTRA_ROUTE = "route";
    public static final String EXTRA_SPEED_MS = "speed_ms";
    public static final String EXTRA_LOOP = "loop";
    public static final String EXTRA_INTERVAL = "interval_ms";
    public static final String EXTRA_OVERLAY = "overlay";

    public interface Callback {
        void onMockState(boolean running, String mode, double lat, double lng,
                         float speedMs, float bearing, String error);
    }

    private static final String CHANNEL_ID = "mock_running";
    private static final int NOTIF_ID = 0x51D0;
    private static final String FUSED_PROVIDER = "fused";
    private static final String[] CORE_PROVIDERS = {
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
    };
    private final List<String> activeProviders = new java.util.ArrayList<>();

    private static final CopyOnWriteArrayList<Callback> CALLBACKS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile MockService sInstance;

    public static void addCallback(Callback c) {
        if (c != null && !CALLBACKS.contains(c)) {
            CALLBACKS.add(c);
        }
    }

    public static void removeCallback(Callback c) {
        CALLBACKS.remove(c);
    }

    public static MockService get() {
        return sInstance;
    }

    public static boolean isRunning() {
        MockService s = sInstance;
        return s != null && s.running;
    }

    private LocationManager lm;
    private WindowManager wm;
    private PowerManager.WakeLock wakeLock;
    private HandlerThread thread;
    private Handler bg;

    private volatile boolean running;
    private volatile String mode = "定点";
    private volatile double lat;
    private volatile double lng;
    private volatile float bearing;
    private volatile float speedMs;
    private volatile float accuracy = 3.0f;
    private volatile String error;
    private volatile double altitude = 36.0;

    private long intervalMs = 1000;
    private boolean providersReady;
    private volatile float joyX;
    private volatile float joyY;
    private static final double JOY_MAX_SPEED = 8.0; // 摇杆满推时 m/s

    private RoutePlayer route;
    private long lastTickMs;
    private long lastNotifyMs;
    private long lastOverlayMs;

    private boolean overlayShown;
    private View overlayRoot;
    private TextView overlayCoord;
    private JoystickView joystick;
    private WindowManager.LayoutParams overlayLp;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShadowLoc::mock");
        wakeLock.setReferenceCounted(false);

        lat = Store.getLastLat(this);
        lng = Store.getLastLng(this);
        intervalMs = Store.getIntervalMs(this);

        createChannel();

        thread = new HandlerThread("shadowloc-tick");
        thread.start();
        bg = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdownNow();
            return START_NOT_STICKY;
        }
        if (ACTION_HIDE_OVERLAY.equals(action)) {
            MAIN.post(this::hideOverlay);
            return START_STICKY;
        }
        if (ACTION_SHOW_OVERLAY.equals(action)) {
            MAIN.post(this::showOverlayIfPossible);
            return START_STICKY;
        }

        // 启动类命令：先把前台通知挂上，避免系统判定超时
        ensureForeground(buildNotification("正在启动..."));

        if (ACTION_START_FIXED.equals(action)) {
            lat = intent.getDoubleExtra(EXTRA_LAT, lat);
            lng = intent.getDoubleExtra(EXTRA_LNG, lng);
            if (intent.hasExtra(EXTRA_INTERVAL)) {
                intervalMs = Math.max(200, Math.min(10000, intent.getLongExtra(EXTRA_INTERVAL, intervalMs)));
                Store.setIntervalMs(this, intervalMs);
            }
            mode = "定点";
            route = null;
            joyX = 0;
            joyY = 0;
            speedMs = 0;
            Store.setLastPoint(this, lat, lng);
            startEngine();
        } else if (ACTION_START_ROUTE.equals(action)) {
            String json = intent.getStringExtra(EXTRA_ROUTE);
            List<double[]> pts = Store.routeFromJson(json == null ? "" : json);
            if (pts.size() < 2) {
                fail("路线至少需要 2 个路标点");
                return START_NOT_STICKY;
            }
            RoutePlayer rp = new RoutePlayer();
            rp.setPoints(pts);
            double sp = intent.getDoubleExtra(EXTRA_SPEED_MS, 1.4);
            rp.setSpeed(sp);
            rp.setLoop(intent.getBooleanExtra(EXTRA_LOOP, false));
            route = rp;
            mode = "路线";
            joyX = 0;
            joyY = 0;
            lat = pts.get(0)[0];
            lng = pts.get(0)[1];
            Store.setLastPoint(this, lat, lng);
            startEngine();
        }

        if (intent.getBooleanExtra(EXTRA_OVERLAY, false)) {
            MAIN.post(this::showOverlayIfPossible);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (bg != null) {
            bg.removeCallbacks(tick);
        }
        hideOverlay();
        removeProviders();
        releaseWake();
        if (thread != null) {
            thread.quitSafely();
        }
        sInstance = null;
        super.onDestroy();
    }

    // ====================== 引擎 ======================

    private void startEngine() {
        if (running) {
            lastTickMs = 0;
            updateNotification();
            notifyState();
            return;
        }
        if (!ensureProviders()) {
            fail(error != null ? error : "无法创建模拟位置通道");
            return;
        }
        error = null;
        running = true;
        lastTickMs = 0;
        lastNotifyMs = 0;
        acquireWake();
        bg.removeCallbacks(tick);
        bg.post(tick);
        updateNotification();
        notifyState();
    }

    private boolean ensureProviders() {
        if (providersReady) {
            return true;
        }
        activeProviders.clear();
        boolean coreOk = false;
        for (String p : CORE_PROVIDERS) {
            try {
                // 清理上次异常退出（如被系统强杀）残留的 test provider
                lm.removeTestProvider(p);
            } catch (Exception ignore) {
            }
            try {
                addProvider(p);
                activeProviders.add(p);
                coreOk = true;
            } catch (SecurityException se) {
                error = "没有模拟位置权限：请到「开发者选项 → 选择模拟位置信息应用」中选择「影位」";
                Log.w(TAG, "mock permission missing", se);
                activeProviders.clear();
                return false;
            } catch (Exception e) {
                Log.w(TAG, "add test provider failed: " + p, e);
            }
        }
        if (!coreOk) {
            error = "创建模拟位置通道失败，请确认已授予定位权限";
            return false;
        }
        // fused 是尽力而为：多数 ROM 上它由 GMS/厂商定位服务托管，注册不上就跳过
        try {
            addProvider(FUSED_PROVIDER);
            activeProviders.add(FUSED_PROVIDER);
        } catch (Exception ignore) {
            Log.d(TAG, "fused test provider unavailable, skip");
        }
        providersReady = true;
        return true;
    }

    private void addProvider(String name) {
        try {
            lm.addTestProvider(name, false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
        } catch (IllegalArgumentException already) {
            // provider 已存在（重复启动），直接复用
        }
        lm.setTestProviderEnabled(name, true);
    }

    private void removeProviders() {
        if (lm == null) {
            return;
        }
        for (String p : new java.util.ArrayList<>(activeProviders)) {
            try {
                lm.setTestProviderEnabled(p, false);
            } catch (Exception ignore) {
            }
            try {
                lm.removeTestProvider(p);
            } catch (Exception ignore) {
            }
        }
        activeProviders.clear();
        providersReady = false;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            double dt = lastTickMs == 0 ? intervalMs / 1000.0 : (now - lastTickMs) / 1000.0;
            dt = Geo.clamp(dt, 0.05, 3.0);
            lastTickMs = now;
            step(dt);
            boolean ok = pushLocation();
            if (now - lastOverlayMs > 500) {
                lastOverlayMs = now;
                updateOverlayText();
            }
            if (now - lastNotifyMs > 3000) {
                lastNotifyMs = now;
                updateNotification();
            }
            notifyState();
            if (ok && running) {
                bg.postDelayed(this, intervalMs);
            }
        }
    };

    private void step(double dt) {
        accuracy = 2.0f + (float) (Math.random() * 3.0f);
        altitude = 30.0 + Math.random() * 15.0;

        if ("路线".equals(mode) && route != null) {
            double[] p = route.advance(dt);
            lat = p[0];
            lng = p[1];
            bearing = (float) route.bearing();
            speedMs = (float) route.speedMs();
            if (route.finished() && !route.isLoop()) {
                speedMs = 0f;
                mode = "路线（已到终点）";
            }
            return;
        }

        if ("摇杆".equals(mode)) {
            double mx = joyX;
            double my = joyY;
            double mag = Math.min(1.0, Math.sqrt(mx * mx + my * my));
            if (mag > 0.02) {
                double[] p = Geo.offset(lat, lng, mx * JOY_MAX_SPEED * dt, my * JOY_MAX_SPEED * dt);
                lat = p[0];
                lng = p[1];
                speedMs = (float) (JOY_MAX_SPEED * mag);
                bearing = (float) ((Math.toDegrees(Math.atan2(mx, my)) + 360.0) % 360.0);
            } else {
                speedMs = 0f;
            }
            return;
        }

        speedMs = 0f;
    }

    private boolean pushLocation() {
        long nowWall = System.currentTimeMillis();
        long nowNano = SystemClock.elapsedRealtimeNanos();
        for (String p : new java.util.ArrayList<>(activeProviders)) {
            Location loc = new Location(p);
            loc.setLatitude(lat);
            loc.setLongitude(lng);
            loc.setAltitude(altitude);
            loc.setAccuracy(accuracy);
            loc.setSpeed(speedMs);
            loc.setBearing(bearing);
            loc.setTime(nowWall);
            if (Build.VERSION.SDK_INT >= 17) {
                loc.setElapsedRealtimeNanos(nowNano);
            }
            if (Build.VERSION.SDK_INT >= 26) {
                loc.setVerticalAccuracyMeters(1.5f);
                loc.setSpeedAccuracyMetersPerSecond(0.2f);
                loc.setBearingAccuracyDegrees(1.0f);
            }
            if (Build.VERSION.SDK_INT >= 31) {
                loc.setMock(true);
            }
            Bundle extras = new Bundle();
            extras.putInt("satellites", 12);
            loc.setExtras(extras);
            try {
                lm.setTestProviderLocation(p, loc);
            } catch (IllegalArgumentException missing) {
                // provider 被系统回收，重建一次
                providersReady = false;
                if (!ensureProviders()) {
                    fail(error != null ? error : "模拟位置通道失效");
                    return false;
                }
                try {
                    lm.setTestProviderLocation(p, loc);
                } catch (Exception e) {
                    Log.w(TAG, "push retry failed", e);
                }
            } catch (SecurityException se) {
                fail("模拟位置权限被撤销，请重新在开发者选项中授权");
                return false;
            } catch (Exception e) {
                Log.w(TAG, "setTestProviderLocation failed", e);
            }
        }
        return true;
    }

    private void fail(String reason) {
        error = reason;
        running = false;
        bg.removeCallbacks(tick);
        MAIN.post(() -> {
            hideOverlay();
            notifyState();
            if (isForeground) {
                updateNotificationError(reason);
                MAIN.postDelayed(() -> {
                    stopForeground(true);
                    stopSelf();
                }, 2500);
            } else {
                stopSelf();
            }
        });
    }

    private boolean isForeground;

    private void ensureForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, n);
            }
            isForeground = true;
        } catch (Exception e) {
            Log.w(TAG, "startForeground with type failed, fallback", e);
            try {
                startForeground(NOTIF_ID, n);
                isForeground = true;
            } catch (Exception e2) {
                Log.w(TAG, "startForeground fallback failed", e2);
            }
        }
    }

    private void shutdownNow() {
        MAIN.post(() -> {
            running = false;
            if (bg != null) {
                bg.removeCallbacks(tick);
            }
            hideOverlay();
            removeProviders();
            releaseWake();
            stopForeground(true);
            isForeground = false;
            notifyState();
            stopSelf();
        });
    }

    private void acquireWake() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(30 * 60 * 1000L);
            }
        } catch (Exception ignore) {
        }
    }

    private void releaseWake() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignore) {
        }
    }

    // ====================== 通知 ======================

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "模拟定位运行状态",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("显示当前模拟的位置与运行状态");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        if (nm != null) {
            nm.createNotificationChannel(ch);
        }
    }

    private String summary() {
        return String.format(Locale.US, "%s · %.5f, %.5f", mode, lat, lng);
    }

    private PendingIntent stopPending() {
        Intent i = new Intent(this, MockService.class).setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, 1, i, flags);
    }

    private PendingIntent openPending() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 2, i, flags);
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("影位 · 模拟定位运行中")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openPending())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(0xFF3DDC97);
        android.app.Notification.Action action = new android.app.Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notif), "停止", stopPending()).build();
        b.addAction(action);
        return b.build();
    }

    private void updateNotification() {
        if (!isForeground) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        String text = summary();
        if (speedMs > 0.1f) {
            text += String.format(Locale.US, " · %.1f km/h", speedMs * 3.6);
        }
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    private void updateNotificationError(String reason) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Notification n = buildNotification(reason);
        nm.notify(NOTIF_ID, n);
    }

    // ====================== 悬浮窗摇杆 ======================

    private void showOverlayIfPossible() {
        showOverlay();
    }

    private void showOverlay() {
        if (overlayShown) {
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no overlay permission");
            return;
        }
        try {
            FrameLayout root = new FrameLayout(this);

            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(10), dp(10), dp(10), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE6141A22);
            bg.setCornerRadius(dp(20));
            bg.setStroke(dp(1), 0x663DDC97);
            panel.setBackground(bg);

            joystick = new JoystickView(this);
            panel.addView(joystick, new LinearLayout.LayoutParams(dp(126), dp(126)));
            joystick.setListener((x, y) -> {
                joyX = x;
                joyY = y;
                if ((Math.abs(x) > 0.02f || Math.abs(y) > 0.02f) && !"摇杆".equals(mode)) {
                    mode = "摇杆";
                    route = null;
                }
            });

            overlayCoord = new TextView(this);
            overlayCoord.setTextSize(9f);
            overlayCoord.setTextColor(0xFF8B98A9);
            overlayCoord.setTypeface(Typeface.MONOSPACE);
            overlayCoord.setGravity(Gravity.CENTER);
            panel.addView(overlayCoord);

            FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            root.addView(panel, panelLp);

            TextView close = new TextView(this);
            close.setText("×");
            close.setTextSize(18f);
            close.setTextColor(0xFF8B98A9);
            close.setGravity(Gravity.CENTER);
            GradientDrawable closeBg = new GradientDrawable();
            closeBg.setColor(0xE6141A22);
            closeBg.setCornerRadius(dp(12));
            closeBg.setStroke(dp(1), 0x33FFFFFF);
            close.setBackground(closeBg);
            FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(dp(24), dp(24));
            closeLp.gravity = Gravity.TOP | Gravity.END;
            closeLp.rightMargin = dp(-2);
            closeLp.topMargin = dp(-2);
            root.addView(close, closeLp);
            close.setOnClickListener(v -> {
                hideOverlay();
                Store.setOverlayWanted(this, false);
            });

            root.setOnTouchListener(new View.OnTouchListener() {
                private float downX;
                private float downY;
                private int startX;
                private int startY;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX();
                            downY = e.getRawY();
                            startX = overlayLp.x;
                            startY = overlayLp.y;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            overlayLp.x = startX + (int) (e.getRawX() - downX);
                            overlayLp.y = startY + (int) (e.getRawY() - downY);
                            try {
                                wm.updateViewLayout(root, overlayLp);
                            } catch (Exception ignore) {
                            }
                            return true;
                        default:
                            return false;
                    }
                }
            });

            overlayLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            overlayLp.gravity = Gravity.TOP | Gravity.LEFT;
            overlayLp.x = dp(14);
            overlayLp.y = dp(260);

            wm.addView(root, overlayLp);
            overlayRoot = root;
            overlayShown = true;
            updateOverlayText();
        } catch (Exception e) {
            Log.w(TAG, "show overlay failed", e);
            overlayShown = false;
        }
    }

    private void hideOverlay() {
        if (!overlayShown || overlayRoot == null) {
            overlayShown = false;
            return;
        }
        try {
            wm.removeView(overlayRoot);
        } catch (Exception ignore) {
        }
        overlayRoot = null;
        overlayCoord = null;
        joystick = null;
        overlayShown = false;
    }

    private void updateOverlayText() {
        if (!overlayShown || overlayCoord == null) {
            return;
        }
        overlayCoord.setText(String.format(Locale.US, "%.5f\n%.5f", lat, lng));
    }

    // ====================== 状态回调 ======================

    private void notifyState() {
        final boolean r = running;
        final String m = mode;
        final double la = lat;
        final double lo = lng;
        final float sp = speedMs;
        final float br = bearing;
        final String er = error;
        MAIN.post(() -> {
            for (Callback cb : CALLBACKS) {
                try {
                    cb.onMockState(r, m, la, lo, sp, br, er);
                } catch (Exception ignore) {
                }
            }
        });
    }

    // ====================== 供界面读取 ======================

    public boolean isMocking() {
        return running;
    }

    public String currentMode() {
        return mode;
    }

    public double currentLat() {
        return lat;
    }

    public double currentLng() {
        return lng;
    }

    public float currentSpeedMs() {
        return speedMs;
    }

    /** 让摇杆模式从指定点开始。 */
    public void prepareJoystick() {
        if (!"摇杆".equals(mode)) {
            mode = "摇杆";
            route = null;
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
