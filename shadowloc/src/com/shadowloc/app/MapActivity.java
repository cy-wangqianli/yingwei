package com.shadowloc.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * 地图选点：默认使用内置离线底图（Natural Earth 海岸线/国界 + GeoNames 城市），
 * 可在底部切到在线 OSM 瓦片看街道。
 */
public class MapActivity extends Activity {

    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";
    public static final String EXTRA_RESULT_LAT = "result_lat";
    public static final String EXTRA_RESULT_LNG = "result_lng";

    private WebView web;
    private TextView tvPick;
    private TextView tvTileFail;
    private TextView tvOffline;
    private TextView tvOnline;
    private double lat;
    private double lng;
    private boolean offline = true;
    private boolean webReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        lat = getIntent().getDoubleExtra(EXTRA_LAT, Store.getLastLat(this));
        lng = getIntent().getDoubleExtra(EXTRA_LNG, Store.getLastLng(this));

        tvPick = findViewById(R.id.tvPick);
        tvTileFail = findViewById(R.id.tvTileFail);
        tvOffline = findViewById(R.id.tvOffline);
        tvOnline = findViewById(R.id.tvOnline);
        web = findViewById(R.id.webView);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        if (Build.VERSION.SDK_INT < 30) {
            s.setAllowFileAccessFromFileURLs(true);
        }
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.setBackgroundColor(Color.parseColor("#0B0F14"));
        web.addJavascriptInterface(new Bridge(), "Android");

        tvOffline.setOnClickListener(v -> switchMode(true));
        tvOnline.setOnClickListener(v -> switchMode(false));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnHere).setOnClickListener(v -> goHere());
        findViewById(R.id.btnUse).setOnClickListener(v -> {
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT_LAT, lat);
            data.putExtra(EXTRA_RESULT_LNG, lng);
            setResult(RESULT_OK, data);
            finish();
        });

        setChips();
        updatePick();
        loadMap();
    }

    private void loadMap() {
        String url = String.format(Locale.US,
                "file:///android_asset/map.html?lat=%.6f&lng=%.6f&z=%d", lat, lng, offline ? 4 : 15);
        web.loadUrl(url);
    }

    private void switchMode(boolean toOffline) {
        if (offline == toOffline) {
            return;
        }
        offline = toOffline;
        setChips();
        if (webReady) {
            web.evaluateJavascript("setMode('" + (offline ? "offline" : "online") + "');", null);
        } else {
            loadMap();
        }
        if (!offline) {
            getWindow().getDecorView().postDelayed(() -> tvTileFail.setVisibility(View.GONE), 1500);
        }
    }

    private void setChips() {
        tvOffline.setTextColor(getColor(offline ? R.color.brand : R.color.text_dim));
        tvOffline.setTypeface(null, offline ? Typeface.BOLD : Typeface.NORMAL);
        tvOffline.setBackground(chipBg(offline));
        tvOnline.setTextColor(getColor(offline ? R.color.text_dim : R.color.brand));
        tvOnline.setTypeface(null, offline ? Typeface.NORMAL : Typeface.BOLD);
        tvOnline.setBackground(chipBg(!offline));
    }

    private GradientDrawable chipBg(boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(9));
        if (selected) {
            d.setColor(0xFF123A2B);
            d.setStroke(dp(1), 0xFF2FC98A);
        } else {
            d.setColor(Color.TRANSPARENT);
            d.setStroke(0, Color.TRANSPARENT);
        }
        return d;
    }

    private void goHere() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) {
            toast(R.string.map_no_location);
            return;
        }
        Location best = null;
        for (String p : new String[]{"gps", "network", "fused"}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            } catch (Exception ignore) {
            }
        }
        if (best == null) {
            toast(R.string.map_no_location);
            return;
        }
        lat = best.getLatitude();
        lng = best.getLongitude();
        updatePick();
        if (webReady) {
            web.evaluateJavascript(String.format(Locale.US, "setHere(%.6f,%.6f);", lat, lng), null);
        }
        if (best.isFromMockProvider()) {
            toast("当前位置来自模拟定位");
        }
    }

    private void updatePick() {
        tvPick.setText(String.format(Locale.US, "%.6f, %.6f", lat, lng));
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class Bridge {
        @JavascriptInterface
        public void onPick(String la, String ln) {
            try {
                double a = Double.parseDouble(la);
                double o = Double.parseDouble(ln);
                if (Geo.validLat(a) && Geo.validLng(o)) {
                    lat = a;
                    lng = o;
                    runOnUiThread(MapActivity.this::updatePick);
                }
            } catch (Exception ignore) {
            }
        }

        @JavascriptInterface
        public void onTileError() {
            runOnUiThread(() -> {
                offline = true;
                setChips();
                tvTileFail.setVisibility(View.VISIBLE);
            });
        }

        @JavascriptInterface
        public void onReady() {
            runOnUiThread(() -> {
                webReady = true;
                tvTileFail.setVisibility(View.GONE);
            });
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
