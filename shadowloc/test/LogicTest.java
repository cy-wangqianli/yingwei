import com.shadowloc.app.Geo;
import com.shadowloc.app.RoutePlayer;

import java.util.ArrayList;
import java.util.List;

/** 纯 JVM 逻辑自测：不依赖 Android，可直接 javac + java 运行。 */
public class LogicTest {

    private static int failed = 0;

    public static void main(String[] args) {
        testDistance();
        testBearing();
        testOffset();
        testRoutePlayer();
        testRouteLoop();
        testInterpolate();
        if (failed == 0) {
            System.out.println("ALL TESTS PASSED");
        } else {
            System.out.println(failed + " TEST(S) FAILED");
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) {
            failed++;
        }
    }

    private static void near(String name, double actual, double expected, double tol) {
        check(name + " (actual=" + actual + ", expected=" + expected + ")", Math.abs(actual - expected) <= tol);
    }

    private static void testDistance() {
        // 同经度差 0.01 度纬度 ≈ 1112 m
        double d = Geo.distance(39.9042, 116.4074, 39.9142, 116.4074);
        near("distance 0.01deg lat", d, 1112.0, 8.0);
        near("distance same point", Geo.distance(1, 1, 1, 1), 0, 0.001);
    }

    private static void testBearing() {
        near("bearing north", Geo.bearing(39.90, 116.40, 39.91, 116.40), 0, 0.6);
        near("bearing east", Geo.bearing(39.90, 116.40, 39.90, 116.41), 90, 0.6);
        near("bearing south", Geo.bearing(39.91, 116.40, 39.90, 116.40), 180, 0.6);
        near("bearing west", Geo.bearing(39.90, 116.41, 39.90, 116.40), 270, 0.6);
    }

    private static void testOffset() {
        double[] north = Geo.offset(39.9042, 116.4074, 0, 1000);
        near("offset north 1000m lat", north[0], 39.9042 + 1000 / 111320.0, 0.0001);
        near("offset north keeps lng", north[1], 116.4074, 1e-6);

        double[] east = Geo.offset(39.9042, 116.4074, 1000, 0);
        near("offset east keeps lat", east[0], 39.9042, 1e-6);
        check("offset east 1000m lng grows", east[1] > 116.4074);
        // 往返：东移 1000 米再量距离
        near("offset east distance", Geo.distance(39.9042, 116.4074, east[0], east[1]), 1000, 6);

        double[] clamped = Geo.offset(89.99, 0, 0, 100000);
        check("offset clamps latitude", clamped[0] <= 89.9 && clamped[0] >= -89.9);
    }

    private static void testInterpolate() {
        double[] mid = Geo.interpolate(39.0, 116.0, 40.0, 117.0, 0.5);
        near("interpolate lat", mid[0], 39.5, 1e-9);
        near("interpolate lng", mid[1], 116.5, 1e-9);
        double[] over = Geo.interpolate(39.0, 116.0, 40.0, 117.0, 1.7);
        near("interpolate clamps t", over[0], 40.0, 1e-9);
    }

    private static void testRoutePlayer() {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{39.9042, 116.4074});
        pts.add(new double[]{39.9142, 116.4074}); // 约 1112 m 北
        RoutePlayer rp = new RoutePlayer();
        rp.setPoints(pts);
        rp.setSpeed(1.0);
        near("route total", rp.totalMeters(), 1112.0, 8.0);
        check("route count", rp.count() == 2);
        double[] p1 = rp.advance(100);
        near("route after 100s lat", p1[0], 39.9042 + (100 / 1112.0) * 0.01, 0.0003);
        near("route bearing north", rp.bearing(), 0, 1.0);
        rp.advance(2000);
        check("route finished", rp.finished());
        near("route not beyond end", rp.travelledMeters(), rp.totalMeters(), 0.001);
        near("route progress capped", rp.progress(), 1.0, 1e-9);

        RoutePlayer single = new RoutePlayer();
        List<double[]> one = new ArrayList<>();
        one.add(new double[]{1, 2});
        single.setPoints(one);
        double[] only = single.advance(1);
        near("single point lat", only[0], 1, 1e-9);
        near("single point lng", only[1], 2, 1e-9);
    }

    private static void testRouteLoop() {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{39.9042, 116.4074});
        pts.add(new double[]{39.9142, 116.4074});
        RoutePlayer rp = new RoutePlayer();
        rp.setPoints(pts);
        rp.setSpeed(10.0);
        rp.setLoop(true);
        for (int i = 0; i < 200; i++) {
            rp.advance(1);
            if (rp.travelledMeters() > rp.totalMeters()) {
                check("loop wraps travelled", false);
                return;
            }
        }
        check("loop wraps travelled", true);
        check("loop never finishes", !rp.finished());
    }
}
