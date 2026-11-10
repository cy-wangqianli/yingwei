package com.shadowloc.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 本地持久化：上次坐标、参数、收藏点、路线。 */
public final class Store {

    private static final String FILE = "shadowloc";
    public static final double DEFAULT_LAT = 39.908722;
    public static final double DEFAULT_LNG = 116.397499;

    public static final class Spot {
        public final String name;
        public final double lat;
        public final double lng;

        public Spot(String name, double lat, double lng) {
            this.name = name;
            this.lat = lat;
            this.lng = lng;
        }
    }

    private Store() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static double d(SharedPreferences p, String key, double def) {
        try {
            return Double.parseDouble(p.getString(key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    public static double getLastLat(Context c) {
        return d(p(c), "last_lat", DEFAULT_LAT);
    }

    public static double getLastLng(Context c) {
        return d(p(c), "last_lng", DEFAULT_LNG);
    }

    public static void setLastPoint(Context c, double lat, double lng) {
        p(c).edit().putString("last_lat", String.valueOf(lat)).putString("last_lng", String.valueOf(lng)).apply();
    }

    public static long getIntervalMs(Context c) {
        return p(c).getLong("interval", 1000L);
    }

    public static void setIntervalMs(Context c, long ms) {
        p(c).edit().putLong("interval", Math.max(200, Math.min(10000, ms))).apply();
    }

    public static double getSpeedKmh(Context c) {
        return d(p(c), "speed_kmh", 5.0);
    }

    public static void setSpeedKmh(Context c, double v) {
        p(c).edit().putString("speed_kmh", String.valueOf(v)).apply();
    }

    public static boolean getLoop(Context c) {
        return p(c).getBoolean("loop", false);
    }

    public static void setLoop(Context c, boolean v) {
        p(c).edit().putBoolean("loop", v).apply();
    }

    public static boolean getOverlayWanted(Context c) {
        return p(c).getBoolean("overlay", false);
    }

    public static void setOverlayWanted(Context c, boolean v) {
        p(c).edit().putBoolean("overlay", v).apply();
    }

    // ===== 收藏点 =====

    public static List<Spot> getSpots(Context c) {
        List<Spot> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(p(c).getString("spots", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Spot(o.optString("name", "地点"), o.getDouble("lat"), o.getDouble("lng")));
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    public static void setSpots(Context c, List<Spot> spots) {
        JSONArray arr = new JSONArray();
        try {
            for (Spot s : spots) {
                JSONObject o = new JSONObject();
                o.put("name", s.name);
                o.put("lat", s.lat);
                o.put("lng", s.lng);
                arr.put(o);
            }
        } catch (Exception ignore) {
        }
        p(c).edit().putString("spots", arr.toString()).apply();
    }

    // ===== 路线 =====

    public static List<double[]> getRoute(Context c) {
        List<double[]> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(p(c).getString("route", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONArray pair = arr.getJSONArray(i);
                out.add(new double[]{pair.getDouble(0), pair.getDouble(1)});
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    public static void setRoute(Context c, List<double[]> route) {
        JSONArray arr = new JSONArray();
        try {
            for (double[] p : route) {
                JSONArray pair = new JSONArray();
                pair.put(p[0]);
                pair.put(p[1]);
                arr.put(pair);
            }
        } catch (Exception ignore) {
        }
        p(c).edit().putString("route", arr.toString()).apply();
    }

    public static String routeToJson(List<double[]> route) {
        JSONArray arr = new JSONArray();
        try {
            for (double[] p : route) {
                JSONArray pair = new JSONArray();
                pair.put(p[0]);
                pair.put(p[1]);
                arr.put(pair);
            }
        } catch (Exception ignore) {
        }
        return arr.toString();
    }

    public static List<double[]> routeFromJson(String json) {
        List<double[]> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONArray pair = arr.getJSONArray(i);
                out.add(new double[]{pair.getDouble(0), pair.getDouble(1)});
            }
        } catch (Exception ignore) {
        }
        return out;
    }
}
