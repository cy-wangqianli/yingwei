package com.shadowloc.probe;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 独立的「位置探针」：只读取系统定位并显示，用于验证影位的模拟位置是否对所有 App 生效。
 * 和影位没有任何代码共享，也没有权限互通，是验证「系统级注入」的对照实验。
 */
public class MainActivity extends Activity {

    private static final int REQ = 7;

    private TextView tvMain;
    private TextView tvList;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private LocationManager lm;
    private final StringBuilder feed = new StringBuilder();

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            append(location);
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        ensurePermission();
        refresh();
    }

    private android.view.View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0B0F14"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText(R.string.probe_title);
        title.setTextColor(Color.parseColor("#E6EDF3"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText(R.string.probe_desc);
        desc.setTextColor(Color.parseColor("#8B98A9"));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        desc.setPadding(0, dp(6), 0, dp(16));
        root.addView(desc);

        tvMain = new TextView(this);
        tvMain.setText(R.string.probe_wait);
        tvMain.setTextColor(Color.parseColor("#3DDC97"));
        tvMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvMain.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvMain.setGravity(Gravity.START);
        tvMain.setBackgroundColor(Color.parseColor("#141A22"));
        tvMain.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(tvMain, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button refresh = new Button(this);
        refresh.setText(R.string.probe_refresh);
        refresh.setAllCaps(false);
        refresh.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        blp.topMargin = dp(12);
        root.addView(refresh, blp);

        TextView label = new TextView(this);
        label.setText("连续上报记录");
        label.setTextColor(Color.parseColor("#E6EDF3"));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(0, dp(20), 0, dp(6));
        root.addView(label);

        tvList = new TextView(this);
        tvList.setTextColor(Color.parseColor("#8B98A9"));
        tvList.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvList.setTypeface(Typeface.MONOSPACE);
        root.addView(tvList);

        return scroll;
    }

    private void ensurePermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ) {
            refresh();
        }
    }

    private void refresh() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            tvMain.setText("未授予定位权限");
            return;
        }
        Location best = null;
        StringBuilder sb = new StringBuilder();
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null) {
                    sb.append(String.format(Locale.US, "%s: %.6f, %.6f  mock=%s\n",
                            p, l.getLatitude(), l.getLongitude(), l.isFromMockProvider()));
                    if (best == null || l.getTime() > best.getTime()) {
                        best = l;
                    }
                }
            } catch (Exception e) {
                sb.append(p).append(": ").append(e.getMessage()).append('\n');
            }
        }
        if (best != null) {
            Log.i("LocProbe", String.format(Locale.US, "BEST provider=%s lat=%.6f lng=%.6f mock=%s",
                    best.getProvider(), best.getLatitude(), best.getLongitude(), best.isFromMockProvider()));
            show(best);
        } else {
            tvMain.setText("暂无系统位置缓存…\n（保持本页打开几秒）");
        }
        tvList.setText(sb.toString());
        startUpdates();
    }

    private void startUpdates() {
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000, 0, listener, Looper.getMainLooper());
                }
            }
        } catch (Exception ignore) {
        }
    }

    private void append(Location l) {
        Log.i("LocProbe", String.format(Locale.US, "UPDATE provider=%s lat=%.6f lng=%.6f acc=%.1f mock=%s",
                l.getProvider(), l.getLatitude(), l.getLongitude(), l.getAccuracy(), l.isFromMockProvider()));
        show(l);
        feed.insert(0, String.format(Locale.US, "%s  %s  %.6f,%.6f  ±%.0fm  mock=%s\n",
                fmt.format(new Date()), l.getProvider(), l.getLatitude(), l.getLongitude(),
                l.getAccuracy(), l.isFromMockProvider()));
        String[] lines = feed.toString().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(12, lines.length); i++) {
            sb.append(lines[i]).append('\n');
        }
        tvList.setText(sb.toString());
    }

    private void show(Location l) {
        tvMain.setText(String.format(Locale.US,
                "%.6f, %.6f\n%s · ±%.1f m\n由模拟定位注入：%s",
                l.getLatitude(), l.getLongitude(), l.getProvider(), l.getAccuracy(),
                l.isFromMockProvider() ? "是" : "否"));
    }

    @Override
    protected void onPause() {
        try {
            lm.removeUpdates(listener);
        } catch (Exception ignore) {
        }
        super.onPause();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
