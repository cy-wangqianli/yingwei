package com.shadowloc.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 路线播放器：给定折线路径与目标速度，按时间推进当前位置。纯 Java，可单测。
 */
public class RoutePlayer {

    private final List<double[]> pts = new ArrayList<>();
    private final List<Double> cum = new ArrayList<>();
    private double total;
    private double travelled;
    private double speedMs = 1.4;
    private boolean loop;
    private double bearing;

    public void setPoints(List<double[]> points) {
        pts.clear();
        cum.clear();
        total = 0;
        travelled = 0;
        if (points != null) {
            for (double[] p : points) {
                if (p != null && p.length >= 2) {
                    pts.add(new double[]{p[0], p[1]});
                }
            }
        }
        rebuild();
    }

    private void rebuild() {
        cum.clear();
        total = 0;
        if (!pts.isEmpty()) {
            cum.add(0.0);
        }
        for (int i = 1; i < pts.size(); i++) {
            double a[] = pts.get(i - 1);
            double b[] = pts.get(i);
            total += Geo.distance(a[0], a[1], b[0], b[1]);
            cum.add(total);
        }
    }

    public void setSpeed(double metersPerSecond) {
        this.speedMs = Math.max(0.05, metersPerSecond);
    }

    public double speedMs() {
        return speedMs;
    }

    public void setLoop(boolean v) {
        this.loop = v;
    }

    public boolean isLoop() {
        return loop;
    }

    public int count() {
        return pts.size();
    }

    public double totalMeters() {
        return total;
    }

    public double travelledMeters() {
        return travelled;
    }

    public double progress() {
        return total <= 0 ? 0 : Geo.clamp(travelled / total, 0, 1);
    }

    public boolean finished() {
        return total > 0 && travelled >= total;
    }

    public double bearing() {
        return bearing;
    }

    public void reset() {
        travelled = 0;
    }

    /**
     * 前进 dt 秒，返回新的 {lat, lng}。
     * 非循环模式下走到终点会停在最后一点，并把 finished 标记为 true。
     */
    public double[] advance(double dtSeconds) {
        if (pts.isEmpty()) {
            return new double[]{0, 0};
        }
        if (pts.size() == 1) {
            double[] only = pts.get(0);
            return new double[]{only[0], only[1]};
        }
        travelled += Math.max(0, speedMs) * Math.max(0, dtSeconds);
        if (loop) {
            if (total > 0) {
                travelled = travelled % total;
            }
        } else if (travelled > total) {
            travelled = total;
        }
        int idx = segmentIndex(travelled);
        if (idx < 0) {
            return new double[]{pts.get(pts.size() - 1)[0], pts.get(pts.size() - 1)[1]};
        }
        double[] a = pts.get(idx);
        double[] b = pts.get(idx + 1);
        double segStart = cum.get(idx);
        double segLen = Math.max(1e-6, cum.get(idx + 1) - segStart);
        double t = Geo.clamp((travelled - segStart) / segLen, 0, 1);
        double[] p = Geo.interpolate(a[0], a[1], b[0], b[1], t);
        bearing = Geo.bearing(p[0], p[1], b[0], b[1]);
        return p;
    }

    private int segmentIndex(double d) {
        for (int i = 0; i < cum.size() - 1; i++) {
            if (d <= cum.get(i + 1)) {
                return i;
            }
        }
        return cum.size() - 2;
    }
}
