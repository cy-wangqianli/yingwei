package com.shadowloc.app;

/**
 * 纯数学工具：经纬度距离、方位角、按米偏移。不依赖 Android，可直接在 JVM 上单测。
 */
public final class Geo {

    public static final double EARTH_R = 6371000.0;
    private static final double METERS_PER_DEG_LAT = 111320.0;

    private Geo() {
    }

    /** 两点间大圆距离（米）。 */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_R * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** 从点1指向点2的方位角（0=正北，顺时针，0~360）。 */
    public static double bearing(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360.0) % 360.0;
    }

    /** 以某点为中心，向东 eastM 米、向北 northM 米后的坐标，返回 {lat, lng}。 */
    public static double[] offset(double lat, double lng, double eastM, double northM) {
        double dLat = northM / METERS_PER_DEG_LAT;
        double cosLat = Math.cos(Math.toRadians(lat));
        if (Math.abs(cosLat) < 1e-6) {
            cosLat = 1e-6;
        }
        double dLng = eastM / (METERS_PER_DEG_LAT * cosLat);
        double nLat = clamp(lat + dLat, -89.9, 89.9);
        double nLng = wrapLng(lng + dLng);
        return new double[]{nLat, nLng};
    }

    /** 线性插值，t 在 [0,1]。 */
    public static double[] interpolate(double lat1, double lng1, double lat2, double lng2, double t) {
        double k = clamp(t, 0, 1);
        return new double[]{lat1 + (lat2 - lat1) * k, lng1 + (lng2 - lng1) * k};
    }

    public static boolean validLat(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && v >= -90 && v <= 90;
    }

    public static boolean validLng(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && v >= -180 && v <= 180;
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static double wrapLng(double lng) {
        double x = lng;
        while (x > 180) {
            x -= 360;
        }
        while (x < -180) {
            x += 360;
        }
        return x;
    }
}
