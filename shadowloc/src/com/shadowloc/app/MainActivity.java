package com.shadowloc.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity implements MockService.Callback {

    private static final int REQ_PERMS = 101;
    private static final int REQ_MAP = 202;
    private static final int REQ_OVERLAY = 303;

    private static final Pattern COORD_PAIR = Pattern.compile(
            "(-?\\d{1,3}(?:\\.\\d+)?)\\s*[,，]\\s*(-?\\d{1,3}(?:\\.\\d+)?)");
    private static final Pattern KEYED_PAIR = Pattern.compile(
            "(?:lat|latitude|纬度)\\s*[=:：]\\s*(-?\\d{1,3}(?:\\.\\d+)?)[^\\d-]{0,40}?"
                    + "(?:lon|lng|longitude|经度)\\s*[=:：]\\s*(-?\\d{1,3}(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KEYED_PAIR2 = Pattern.compile(
            "(?:lon|lng|longitude|经度)\\s*[=:：]\\s*(-?\\d{1,3}(?:\\.\\d+)?)[^\\d-]{0,40}?"
                    + "(?:lat|latitude|纬度)\\s*[=:：]\\s*(-?\\d{1,3}(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);

    private EditText etLat;
    private EditText etLng;
    private EditText etSpeed;
    private TextView tvState;
    private TextView tvMode;
    private TextView tvCoord;
    private TextView tvDetail;
    private TextView tvSpotCount;
    private TextView tvRouteInfo;
    private TextView tvVersion;
    private View dot;
    private Button btnToggle;
    private Button btnMap;
    private Button btnApply;
    private Button btnReal;
    private Button btnPaste;
    private Button btnSaveSpot;
    private Button btnAddWp;
    private Button btnClearRoute;
    private Button btnDemoRoute;
    private Button btnPlayRoute;
    private Button btnDevOptions;
    private Button btnCheck;
    private Switch switchOverlay;
    private LinearLayout spotList;
    private LinearLayout routeList;

    private final List<Store.Spot> spots = new ArrayList<>();
    private final List<double[]> route = new ArrayList<>();
    private boolean running;
    private String pendingAction;
    private String errorShown;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etLat = findViewById(R.id.etLat);
        etLng = findViewById(R.id.etLng);
        etSpeed = findViewById(R.id.etSpeed);
        tvState = findViewById(R.id.tvState);
        tvMode = findViewById(R.id.tvMode);
        tvCoord = findViewById(R.id.tvCoord);
        tvDetail = findViewById(R.id.tvDetail);
        tvSpotCount = findViewById(R.id.tvSpotCount);
        tvRouteInfo = findViewById(R.id.tvRouteInfo);
        tvVersion = findViewById(R.id.tvVersion);
        dot = findViewById(R.id.dot);
        btnToggle = findViewById(R.id.btnToggle);
        btnMap = findViewById(R.id.btnMap);
        btnApply = findViewById(R.id.btnApply);
        btnReal = findViewById(R.id.btnReal);
        btnPaste = findViewById(R.id.btnPaste);
        btnSaveSpot = findViewById(R.id.btnSaveSpot);
        btnAddWp = findViewById(R.id.btnAddWp);
        btnClearRoute = findViewById(R.id.btnClearRoute);
        btnDemoRoute = findViewById(R.id.btnDemoRoute);
        btnPlayRoute = findViewById(R.id.btnPlayRoute);
        btnDevOptions = findViewById(R.id.btnDevOptions);
        btnCheck = findViewById(R.id.btnCheck);
        switchOverlay = findViewById(R.id.switchOverlay);
        spotList = findViewById(R.id.spotList);
        routeList = findViewById(R.id.routeList);

        try {
            tvVersion.setText("v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignore) {
        }

        double lat = Store.getLastLat(this);
        double lng = Store.getLastLng(this);
        etLat.setText(String.format(Locale.US, "%.6f", lat));
        etLng.setText(String.format(Locale.US, "%.6f", lng));
        etSpeed.setText(String.format(Locale.US, "%.1f", Store.getSpeedKmh(this)));
        spots.addAll(Store.getSpots(this));
        route.addAll(Store.getRoute(this));
        switchOverlay.setChecked(Store.getOverlayWanted(this) && Settings.canDrawOverlays(this));
        renderSpots();
        renderRoute();

        btnToggle.setOnClickListener(v -> {
            if (running) {
                stopMocking();
            } else {
                double[] p = currentPointOrDefault();
                if (p != null) {
                    startFixed(p[0], p[1]);
                }
            }
        });
        btnApply.setOnClickListener(v -> {
            double[] p = readInput();
            if (p != null) {
                startFixed(p[0], p[1]);
            }
        });
        btnMap.setOnClickListener(v -> openMap());
        btnReal.setOnClickListener(v -> useRealLocation());
        btnPaste.setOnClickListener(v -> parseClipboard());
        btnSaveSpot.setOnClickListener(v -> promptSaveSpot());
        btnAddWp.setOnClickListener(v -> addWaypoint());
        btnClearRoute.setOnClickListener(v -> {
            route.clear();
            Store.setRoute(this, route);
            renderRoute();
        });
        btnDemoRoute.setOnClickListener(v -> buildDemoRoute());
        btnPlayRoute.setOnClickListener(v -> startRoute());
        btnDevOptions.setOnClickListener(v -> openDevOptions());
        btnCheck.setOnClickListener(v -> showSelfCheck());

        switchOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !Settings.canDrawOverlays(this)) {
                switchOverlay.setChecked(false);
                Store.setOverlayWanted(this, false);
                toast(R.string.toast_overlay_denied);
                requestOverlayPermission();
                return;
            }
            Store.setOverlayWanted(this, isChecked);
            Intent i = new Intent(this, MockService.class)
                    .setAction(isChecked ? MockService.ACTION_SHOW_OVERLAY : MockService.ACTION_HIDE_OVERLAY);
            try {
                startService(i);
            } catch (Exception ignore) {
            }
        });

        ensureRuntimePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MockService.addCallback(this);
        MockService svc = MockService.get();
        if (svc != null) {
            onMockState(svc.isMocking(), svc.currentMode(), svc.currentLat(), svc.currentLng(),
                    svc.currentSpeedMs(), 0f, null);
        }
        boolean overlayWanted = Store.getOverlayWanted(this) && Settings.canDrawOverlays(this);
        if (switchOverlay.isChecked() != overlayWanted) {
            switchOverlay.setChecked(overlayWanted);
        }
        if (overlayWanted && MockService.isRunning()) {
            try {
                startService(new Intent(this, MockService.class).setAction(MockService.ACTION_SHOW_OVERLAY));
            } catch (Exception ignore) {
            }
        }
    }

    @Override
    protected void onPause() {
        MockService.removeCallback(this);
        super.onPause();
    }

    // ======================== 状态回调 ========================

    @Override
    public void onMockState(boolean isRunning, String mode, double lat, double lng,
                            float speedMs, float bearing, String error) {
        running = isRunning;
        tvState.setText(isRunning ? R.string.status_running : R.string.status_idle);
        tvState.setTextColor(getColor(isRunning ? R.color.brand : R.color.text_dim));
        tvMode.setText(mode == null ? "—" : mode);
        if (isRunning || lat != 0) {
            tvCoord.setText(String.format(Locale.US, "%.6f, %.6f", lat, lng));
            tvDetail.setText(String.format(Locale.US, "速度 %.1f km/h · 方向 %.0f°",
                    speedMs * 3.6, bearing));
        }
        btnToggle.setText(isRunning ? R.string.btn_stop : R.string.btn_start);
        btnToggle.setBackgroundResource(isRunning ? R.drawable.bg_btn_danger : R.drawable.bg_btn_primary);
        btnToggle.setTextColor(getColor(isRunning ? R.color.danger_on : R.color.brand_on));
        dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(isRunning ? R.color.brand : R.color.text_faint)));

        if (error != null && !error.isEmpty() && !error.equals(errorShown)) {
            errorShown = error;
            showMockError(error);
        }
        if (!isRunning) {
            tvMode.setText(mode == null || mode.isEmpty() ? "—" : mode);
        }
    }

    private void showMockError(String error) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.status_error);
        b.setMessage(error);
        b.setPositiveButton(R.string.btn_dev_options, (d, w) -> openDevOptions());
        b.setNegativeButton(R.string.dlg_cancel, null);
        try {
            b.show();
        } catch (Exception ignore) {
        }
    }

    // ======================== 启动 / 停止 ========================

    private void startFixed(double lat, double lng) {
        if (!ensureMockPermission()) {
            return;
        }
        if (!hasLocationPermission()) {
            pendingAction = "fixed:" + lat + "," + lng;
            ensureRuntimePermissions();
            return;
        }
        Store.setLastPoint(this, lat, lng);
        etLat.setText(String.format(Locale.US, "%.6f", lat));
        etLng.setText(String.format(Locale.US, "%.6f", lng));
        Intent i = new Intent(this, MockService.class)
                .setAction(MockService.ACTION_START_FIXED)
                .putExtra(MockService.EXTRA_LAT, lat)
                .putExtra(MockService.EXTRA_LNG, lng)
                .putExtra(MockService.EXTRA_INTERVAL, Store.getIntervalMs(this))
                .putExtra(MockService.EXTRA_OVERLAY, overlayWantedNow());
        startServiceCompat(i);
        toast(R.string.toast_starting);
    }

    private void startRoute() {
        if (route.size() < 2) {
            toast(R.string.toast_route_short);
            return;
        }
        if (!ensureMockPermission()) {
            return;
        }
        if (!hasLocationPermission()) {
            pendingAction = "route";
            ensureRuntimePermissions();
            return;
        }
        double kmh = 5.0;
        try {
            kmh = Double.parseDouble(etSpeed.getText().toString().trim());
        } catch (Exception ignore) {
        }
        kmh = Geo.clamp(kmh, 0.5, 80);
        Store.setSpeedKmh(this, kmh);
        boolean loop = ((CheckBox) findViewById(R.id.cbLoop)).isChecked();
        Store.setLoop(this, loop);

        Intent i = new Intent(this, MockService.class)
                .setAction(MockService.ACTION_START_ROUTE)
                .putExtra(MockService.EXTRA_ROUTE, Store.routeToJson(route))
                .putExtra(MockService.EXTRA_SPEED_MS, kmh / 3.6)
                .putExtra(MockService.EXTRA_LOOP, loop)
                .putExtra(MockService.EXTRA_INTERVAL, Store.getIntervalMs(this))
                .putExtra(MockService.EXTRA_OVERLAY, overlayWantedNow());
        startServiceCompat(i);
        toast(R.string.toast_starting);
    }

    private void stopMocking() {
        try {
            startService(new Intent(this, MockService.class).setAction(MockService.ACTION_STOP));
        } catch (Exception ignore) {
        }
        toast(R.string.toast_stopped);
    }

    private void startServiceCompat(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            Log.e("ShadowLoc", "start failed", e);
            toast("启动失败：" + e.getMessage());
        }
    }

    private boolean overlayWantedNow() {
        return Store.getOverlayWanted(this) && Settings.canDrawOverlays(this);
    }

    // ======================== 权限 ========================

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureRuntimePermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    /** 预检「模拟位置信息应用」授权：拿不到权限时直接引导，而不是等启动后报错。 */
    private boolean ensureMockPermission() {
        if (MockService.isRunning() || mockPermissionLikely()) {
            return true;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("还差一步：模拟位置授权");
        b.setMessage("系统要求先在「开发者选项 → 选择模拟位置信息应用」里选中「影位」，本应用才能向系统上报位置。\n\n点下面按钮直接跳到开发者选项。");
        b.setPositiveButton(R.string.btn_dev_options, (d, w) -> openDevOptions());
        b.setNegativeButton(R.string.dlg_cancel, null);
        b.show();
        return false;
    }

    private boolean mockPermissionLikely() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            return false;
        }
        try {
            lm.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false,
                    true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            // 预检成功：立刻撤掉，避免影响真实定位
            try {
                lm.removeTestProvider(LocationManager.GPS_PROVIDER);
            } catch (Exception ignore) {
            }
            return true;
        } catch (SecurityException se) {
            return false;
        } catch (IllegalArgumentException already) {
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private void requestOverlayPermission() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
        } catch (Exception e) {
            toast("无法打开悬浮窗设置：" + e.getMessage());
        }
    }

    private void openDevOptions() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 26) {
            i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        } else {
            i = new Intent(Settings.ACTION_SETTINGS);
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("请手动进入：设置 → 开发者选项");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) {
            return;
        }
        String pending = pendingAction;
        pendingAction = null;
        if (pending == null) {
            return;
        }
        if (pending.startsWith("fixed:")) {
            String[] parts = pending.substring(6).split(",");
            if (parts.length == 2 && hasLocationPermission()) {
                startFixed(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
            }
        } else if ("route".equals(pending) && hasLocationPermission()) {
            startRoute();
        } else if ("real".equals(pending) && hasLocationPermission()) {
            useRealLocation();
        }
    }

    // ======================== 坐标输入 ========================

    private double[] readInput() {
        try {
            double lat = Double.parseDouble(etLat.getText().toString().trim());
            double lng = Double.parseDouble(etLng.getText().toString().trim());
            if (!Geo.validLat(lat) || !Geo.validLng(lng)) {
                throw new NumberFormatException();
            }
            return new double[]{lat, lng};
        } catch (Exception e) {
            toast(R.string.toast_bad_coord);
            return null;
        }
    }

    private double[] currentPointOrDefault() {
        MockService svc = MockService.get();
        if (svc != null && svc.isMocking()) {
            return new double[]{svc.currentLat(), svc.currentLng()};
        }
        try {
            double lat = Double.parseDouble(etLat.getText().toString().trim());
            double lng = Double.parseDouble(etLng.getText().toString().trim());
            if (Geo.validLat(lat) && Geo.validLng(lng)) {
                return new double[]{lat, lng};
            }
        } catch (Exception ignore) {
        }
        return new double[]{Store.getLastLat(this), Store.getLastLng(this)};
    }

    // ======================== 真实位置 / 剪贴板 ========================

    private void useRealLocation() {
        if (!hasLocationPermission()) {
            pendingAction = "real";
            ensureRuntimePermissions();
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            return;
        }
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            } catch (Exception ignore) {
            }
        }
        if (best != null) {
            applyReal(best);
            return;
        }
        toast(R.string.toast_wait_real);
        try {
            String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            final boolean[] done = {false};
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (!done[0]) {
                        done[0] = true;
                        applyReal(location);
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            ui.postDelayed(() -> {
                if (!done[0]) {
                    toast(R.string.toast_real_fail);
                }
            }, 9000);
        } catch (Exception e) {
            toast(R.string.toast_real_fail);
        }
    }

    private void applyReal(Location l) {
        etLat.setText(String.format(Locale.US, "%.6f", l.getLatitude()));
        etLng.setText(String.format(Locale.US, "%.6f", l.getLongitude()));
        toast("已填入真实位置");
    }

    private void parseClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            toast(R.string.toast_clip_empty);
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast(R.string.toast_clip_empty);
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null) {
            toast(R.string.toast_clip_empty);
            return;
        }
        double[] p = findCoords(text.toString());
        if (p == null) {
            toast(R.string.toast_clip_empty);
            return;
        }
        etLat.setText(String.format(Locale.US, "%.6f", p[0]));
        etLng.setText(String.format(Locale.US, "%.6f", p[1]));
        toast("已解析：" + String.format(Locale.US, "%.6f, %.6f", p[0], p[1]));
    }

    static double[] findCoords(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = COORD_PAIR.matcher(text);
        while (m.find()) {
            try {
                double lat = Double.parseDouble(m.group(1));
                double lng = Double.parseDouble(m.group(2));
                if (Geo.validLat(lat) && Geo.validLng(lng)) {
                    return new double[]{lat, lng};
                }
            } catch (Exception ignore) {
            }
        }
        m = KEYED_PAIR.matcher(text);
        while (m.find()) {
            try {
                double lat = Double.parseDouble(m.group(1));
                double lng = Double.parseDouble(m.group(2));
                if (Geo.validLat(lat) && Geo.validLng(lng)) {
                    return new double[]{lat, lng};
                }
            } catch (Exception ignore) {
            }
        }
        m = KEYED_PAIR2.matcher(text);
        while (m.find()) {
            try {
                double lng = Double.parseDouble(m.group(1));
                double lat = Double.parseDouble(m.group(2));
                if (Geo.validLat(lat) && Geo.validLng(lng)) {
                    return new double[]{lat, lng};
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    // ======================== 收藏点 ========================

    private void promptSaveSpot() {
        final double[] p = currentPointOrDefault();
        if (p == null) {
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText("地点 " + (spots.size() + 1));
        input.setSelectAllOnFocus(true);
        input.setTextColor(getColor(R.color.text));
        input.setHintTextColor(getColor(R.color.text_faint));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_spot_name)
                .setView(wrap)
                .setPositiveButton(R.string.dlg_add, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = "地点 " + (spots.size() + 1);
                    }
                    spots.add(new Store.Spot(name, p[0], p[1]));
                    Store.setSpots(this, spots);
                    renderSpots();
                    toast(R.string.toast_saved);
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    private void renderSpots() {
        spotList.removeAllViews();
        tvSpotCount.setText(String.valueOf(spots.size()));
        if (spots.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText(R.string.spots_empty);
            hint.setTextColor(getColor(R.color.text_faint));
            hint.setTextSize(12);
            hint.setPadding(0, dp(6), 0, dp(6));
            spotList.addView(hint);
            return;
        }
        for (int i = 0; i < spots.size(); i++) {
            final Store.Spot s = spots.get(i);
            View row = buildRow(s.name,
                    String.format(Locale.US, "%.6f, %.6f", s.lat, s.lng),
                    v -> startFixed(s.lat, s.lng),
                    i);
            spotList.addView(row);
        }
    }

    private void deleteSpot(int index) {
        if (index < 0 || index >= spots.size()) {
            return;
        }
        Store.Spot s = spots.get(index);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dlg_delete_spot, s.name))
                .setPositiveButton(R.string.dlg_ok, (d, w) -> {
                    spots.remove(index);
                    Store.setSpots(this, spots);
                    renderSpots();
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    // ======================== 路线 ========================

    private void addWaypoint() {
        double[] p = currentPointOrDefault();
        if (p == null) {
            return;
        }
        route.add(new double[]{p[0], p[1]});
        Store.setRoute(this, route);
        renderRoute();
    }

    private void buildDemoRoute() {
        double[] c = currentPointOrDefault();
        if (c == null) {
            return;
        }
        double radius = 180;
        int n = 8;
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double ang = Math.toRadians(i * 360.0 / n);
            double east = Math.sin(ang) * radius;
            double north = Math.cos(ang) * radius;
            pts.add(Geo.offset(c[0], c[1], east, north));
        }
        pts.add(new double[]{pts.get(0)[0], pts.get(0)[1]});
        route.clear();
        route.addAll(pts);
        Store.setRoute(this, route);
        renderRoute();
        toast("已生成绕当前点一圈的示例路线");
    }

    private void renderRoute() {
        routeList.removeAllViews();
        double total = 0;
        for (int i = 1; i < route.size(); i++) {
            total += Geo.distance(route.get(i - 1)[0], route.get(i - 1)[1], route.get(i)[0], route.get(i)[1]);
        }
        tvRouteInfo.setText(String.format(Locale.US, "%d 点 · %s", route.size(), distanceText(total)));
        if (route.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText(R.string.route_empty);
            hint.setTextColor(getColor(R.color.text_faint));
            hint.setTextSize(12);
            hint.setPadding(0, dp(6), 0, dp(6));
            routeList.addView(hint);
            return;
        }
        for (int i = 0; i < route.size(); i++) {
            final int idx = i;
            double[] p = route.get(i);
            String sub = String.format(Locale.US, "%.6f, %.6f", p[0], p[1]);
            if (i > 0) {
                double d = Geo.distance(route.get(i - 1)[0], route.get(i - 1)[1], p[0], p[1]);
                sub += "  ·  " + distanceText(d);
            }
            View row = buildRow("第 " + (i + 1) + " 点", sub, v -> startFixed(p[0], p[1]), i);
            routeList.addView(row);
        }
    }

    private void deleteWaypoint(int index) {
        if (index < 0 || index >= route.size()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dlg_delete_wp, index + 1))
                .setPositiveButton(R.string.dlg_ok, (d, w) -> {
                    route.remove(index);
                    Store.setRoute(this, route);
                    renderRoute();
                })
                .setNegativeButton(R.string.dlg_cancel, null)
                .show();
    }

    private static String distanceText(double meters) {
        if (meters >= 1000) {
            return String.format(Locale.US, "%.2f km", meters / 1000);
        }
        return String.format(Locale.US, "%.0f m", meters);
    }

    // ======================== 通用行视图 ========================

    private View buildRow(String title, String subtitle, View.OnClickListener onBody, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_row);
        row.setPadding(dp(12), dp(10), dp(9), dp(10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(8);
        row.setLayoutParams(rlp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        body.setLayoutParams(blp);

        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(getColor(R.color.text));
        t1.setTextSize(14);

        TextView t2 = new TextView(this);
        t2.setText(subtitle);
        t2.setTextColor(getColor(R.color.text_faint));
        t2.setTextSize(11);
        t2.setTypeface(Typeface.MONOSPACE);
        t2.setPadding(0, dp(3), 0, 0);

        body.addView(t1);
        body.addView(t2);
        body.setOnClickListener(onBody);
        row.setOnClickListener(onBody);
        row.addView(body);

        TextView del = new TextView(this);
        del.setText("×");
        del.setTextSize(20);
        del.setTextColor(getColor(R.color.text_faint));
        del.setPadding(dp(14), dp(2), dp(10), dp(4));
        del.setOnClickListener(v -> {
            boolean isSpot = spotList.indexOfChild(row) >= 0;
            if (isSpot) {
                deleteSpot(index);
            } else {
                deleteWaypoint(index);
            }
        });
        row.addView(del);
        return row;
    }

    // ======================== 地图 / 自检 ========================

    private void openMap() {
        double[] p = currentPointOrDefault();
        Intent i = new Intent(this, MapActivity.class);
        if (p != null) {
            i.putExtra(MapActivity.EXTRA_LAT, p[0]);
            i.putExtra(MapActivity.EXTRA_LNG, p[1]);
        }
        startActivityForResult(i, REQ_MAP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MAP && resultCode == RESULT_OK && data != null) {
            double lat = data.getDoubleExtra(MapActivity.EXTRA_RESULT_LAT, Double.NaN);
            double lng = data.getDoubleExtra(MapActivity.EXTRA_RESULT_LNG, Double.NaN);
            if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                etLat.setText(String.format(Locale.US, "%.6f", lat));
                etLng.setText(String.format(Locale.US, "%.6f", lng));
                startFixed(lat, lng);
            }
        } else if (requestCode == REQ_OVERLAY) {
            boolean can = Settings.canDrawOverlays(this);
            if (can) {
                switchOverlay.setChecked(true);
                Store.setOverlayWanted(this, true);
                if (MockService.isRunning()) {
                    try {
                        startService(new Intent(this, MockService.class).setAction(MockService.ACTION_SHOW_OVERLAY));
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    private void showSelfCheck() {
        boolean fine = hasLocationPermission();
        boolean notify = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean overlay = Settings.canDrawOverlays(this);
        boolean mockOk = mockPermissionLikely() || MockService.isRunning();
        String msg = "定位权限：" + (fine ? "已授予" : "未授予") + "\n"
                + "通知权限：" + (notify ? "已授予" : "未授予（模拟仍在运行，但通知不可见）") + "\n"
                + "悬浮窗权限：" + (overlay ? "已授予" : "未授予") + "\n"
                + "模拟位置授权：" + (mockOk ? "可用" : "未授权，需在开发者选项中选中本应用") + "\n\n"
                + "当前状态：" + (running ? "模拟运行中" : "未运行");
        new AlertDialog.Builder(this)
                .setTitle(R.string.perm_title)
                .setMessage(msg)
                .setPositiveButton(R.string.btn_dev_options, (d, w) -> openDevOptions())
                .setNegativeButton(R.string.dlg_ok, null)
                .show();
    }

    // ======================== 小工具 ========================

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class Log {
        static void e(String tag, String msg, Throwable t) {
            android.util.Log.e(tag, msg, t);
        }
    }
}
