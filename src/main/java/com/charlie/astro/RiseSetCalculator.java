package com.charlie.astro;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class RiseSetCalculator {
    private static final int SCAN_STEP_SECONDS = 60;
    private static final double EARTH_RADIUS_METRES = 6378137.0;
    private static final double SUN_RADIUS_DEGREES = 0.266563;
    private static final double MOON_RADIUS_KM = 1737.4;
    private static final double AU_KM = 149597870.7;
    private static final DateTimeFormatter LOCAL_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss z");
    private static final DateTimeFormatter UTC_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private RiseSetCalculator() {}

    public static EngineResult engine(boolean preferSwiss, String ephePath, double lat, double lon, double heightMetres) {
        if (preferSwiss) {
            try {
                BodyPositionProvider swiss = new SwissEphemerisProvider(ephePath, lat, lon, heightMetres);
                return new EngineResult(swiss, "Engine: GPL Swiss Ephemeris found. Refraction is OFF. Swiss geocentric positions plus WGS-84 topocentric/parallax correction and observer height are used.");
            } catch (Throwable ex) {
                BodyPositionProvider fallback = new ApproximateProvider();
                return new EngineResult(fallback, "Engine: GPL Swiss Ephemeris was requested but not found/usable: " + ex.getMessage()
                        + ". Falling back to built-in approximation.");
            }
        }
        return new EngineResult(new ApproximateProvider(), "Engine: built-in approximation. Refraction is OFF. Height is included as a geometric dip of horizon.");
    }

    public static List<EventResult> calculate(LocalDate date, double lat, double lon, double heightMetres, ZoneId zone,
                                              BodyPositionProvider provider) {
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        double horizonDip = horizonDipDegrees(heightMetres);

        List<Crossing> crossings = new ArrayList<>();
        crossings.addAll(findCrossings("Sun", start, end, lat, lon, horizonDip, provider));
        crossings.addAll(findCrossings("Moon", start, end, lat, lon, horizonDip, provider));
        crossings.sort(Comparator.comparing(Crossing::instant));

        List<EventResult> results = new ArrayList<>();
        for (Crossing c : crossings) {
            ZonedDateTime local = c.instant().atZone(zone);
            String accuracy = estimatedAccuracy(c.body(), c.instant(), lat, lon, horizonDip, provider);
            results.add(new EventResult(c.body(), c.event(), local.format(LOCAL_FMT), UTC_FMT.format(c.instant()), provider.name(), accuracy, c.note()));
        }
        addNoEventRowsIfNeeded(results, "Sun", start, lat, lon, horizonDip, provider);
        addNoEventRowsIfNeeded(results, "Moon", start, lat, lon, horizonDip, provider);
        return results;
    }

    private static List<Crossing> findCrossings(String body, Instant start, Instant end, double lat, double lon,
                                                double horizonDip, BodyPositionProvider provider) {
        List<Crossing> out = new ArrayList<>();
        Instant t1 = start;
        double y1 = apparentLimbAltitude(body, t1, lat, lon, provider) + horizonDip;
        while (t1.isBefore(end)) {
            Instant t2 = t1.plusSeconds(SCAN_STEP_SECONDS);
            if (t2.isAfter(end)) t2 = end;
            double y2 = apparentLimbAltitude(body, t2, lat, lon, provider) + horizonDip;
            if (Double.isFinite(y1) && Double.isFinite(y2) && (y1 == 0.0 || y1 * y2 < 0.0)) {
                Instant root = bisectToNearestSecond(body, t1, t2, lat, lon, horizonDip, provider);
                double before = apparentLimbAltitude(body, root.minusSeconds(2), lat, lon, provider) + horizonDip;
                double after = apparentLimbAltitude(body, root.plusSeconds(2), lat, lon, provider) + horizonDip;
                String event = after > before ? "Rise" : "Set";
                out.add(new Crossing(body, event, root, "Geometric upper-limb crossing, no atmospheric refraction, rounded to nearest second."));
            }
            t1 = t2;
            y1 = y2;
        }
        return out;
    }

    private static Instant bisectToNearestSecond(String body, Instant lo, Instant hi, double lat, double lon,
                                                 double horizonDip, BodyPositionProvider provider) {
        double yLo = apparentLimbAltitude(body, lo, lat, lon, provider) + horizonDip;
        long a = lo.getEpochSecond();
        long b = hi.getEpochSecond();
        while (b - a > 1) {
            long m = Math.floorDiv(a + b, 2);
            double yMid = apparentLimbAltitude(body, Instant.ofEpochSecond(m), lat, lon, provider) + horizonDip;
            if (yLo == 0.0 || yLo * yMid <= 0.0) {
                b = m;
            } else {
                a = m;
                yLo = yMid;
            }
        }
        Instant ia = Instant.ofEpochSecond(a);
        Instant ib = Instant.ofEpochSecond(b);
        double ya = Math.abs(apparentLimbAltitude(body, ia, lat, lon, provider) + horizonDip);
        double yb = Math.abs(apparentLimbAltitude(body, ib, lat, lon, provider) + horizonDip);
        return yb < ya ? ib : ia;
    }


    /**
     * Gives a practical, deliberately conservative estimate of the timing uncertainty.
     * It is not a certification against USNO/JPL Horizons; it converts a small assumed
     * angular modelling error into seconds using the local altitude rate at the crossing.
     * Near grazing high-latitude crossings, the vertical rate can become very small, so
     * the timing uncertainty legitimately grows.
     */
    private static String estimatedAccuracy(String body, Instant root, double lat, double lon,
                                            double horizonDip, BodyPositionProvider provider) {
        double yBefore = apparentLimbAltitude(body, root.minusSeconds(30), lat, lon, provider) + horizonDip;
        double yAfter = apparentLimbAltitude(body, root.plusSeconds(30), lat, lon, provider) + horizonDip;
        double altitudeRateDegPerSecond = Math.abs((yAfter - yBefore) / 60.0);
        if (!Double.isFinite(altitudeRateDegPerSecond) || altitudeRateDegPerSecond < 1.0e-7) {
            return "± >300 s (grazing/near-polar)";
        }

        boolean swiss = provider.name().toLowerCase(Locale.ROOT).contains("swiss");
        // Conservative angular error budget for this application layer, not for the
        // Swiss Ephemeris itself.  It covers omitted Earth orientation details, the
        // simplified topocentric correction, no local horizon model, and event solving.
        double angularErrorDeg;
        if (swiss) {
            angularErrorDeg = body.equals("Sun") ? 0.0010 : 0.0030;
        } else {
            angularErrorDeg = body.equals("Sun") ? 0.0300 : 0.1500;
        }

        double modelSeconds = angularErrorDeg / altitudeRateDegPerSecond;
        double totalSeconds = Math.hypot(modelSeconds, 0.6); // root rounded to nearest second

        double min = body.equals("Sun") ? (swiss ? 2.0 : 60.0) : (swiss ? 5.0 : 180.0);
        double max = body.equals("Sun") ? (swiss ? 120.0 : 900.0) : (swiss ? 300.0 : 1800.0);
        long rounded = Math.round(clamp(totalSeconds, min, max));
        return "±" + rounded + " s estimated";
    }

    private static double apparentLimbAltitude(String body, Instant instant, double lat, double lon, BodyPositionProvider provider) {
        BodyPosition p = provider.position(body, instant, lat, lon);
        double centreAltitude = altitude(p.raDegrees(), p.decDegrees(), instant, lat, lon);
        double semiDiameter = body.equals("Sun") ? SUN_RADIUS_DEGREES : moonSemiDiameterDegrees(p.distanceAu());
        return centreAltitude + semiDiameter;
    }

    private static void addNoEventRowsIfNeeded(List<EventResult> results, String body, Instant sample,
                                               double lat, double lon, double horizonDip, BodyPositionProvider provider) {
        boolean has = results.stream().anyMatch(r -> r.body().equals(body));
        if (has) return;
        double y = apparentLimbAltitude(body, sample.plusSeconds(12 * 3600), lat, lon, provider) + horizonDip;
        String note = y >= 0 ? "No rise/set on this local date; body remains above the geometric horizon."
                             : "No rise/set on this local date; body remains below the geometric horizon.";
        results.add(new EventResult(body, "No event", "—", "—", provider.name(), "not applicable", note));
    }

    private static double horizonDipDegrees(double heightMetres) {
        if (heightMetres <= 0) return 0.0;
        return Math.toDegrees(Math.acos(EARTH_RADIUS_METRES / (EARTH_RADIUS_METRES + heightMetres)));
    }

    private static double moonSemiDiameterDegrees(double distanceAu) {
        if (distanceAu <= 0 || !Double.isFinite(distanceAu)) return 0.2725;
        return Math.toDegrees(Math.asin(MOON_RADIUS_KM / (distanceAu * AU_KM)));
    }

    private static double altitude(double raDeg, double decDeg, Instant instant, double latDeg, double lonDeg) {
        double lst = localSiderealTimeDegrees(instant, lonDeg);
        double hourAngle = fixAngle(lst - raDeg);
        if (hourAngle > 180.0) hourAngle -= 360.0;
        double sinAlt = sinDeg(latDeg) * sinDeg(decDeg) + cosDeg(latDeg) * cosDeg(decDeg) * cosDeg(hourAngle);
        return Math.toDegrees(Math.asin(clamp(sinAlt, -1.0, 1.0)));
    }

    private static double localSiderealTimeDegrees(Instant instant, double lonDeg) {
        double jd = julianDate(instant);
        double T = (jd - 2451545.0) / 36525.0;
        double gmst = 280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * T * T - T * T * T / 38710000.0;
        return fixAngle(gmst + lonDeg);
    }

    static double julianDate(Instant instant) {
        return 2440587.5 + instant.getEpochSecond() / 86400.0 + instant.getNano() / 86400_000_000_000.0;
    }

    public interface BodyPositionProvider {
        BodyPosition position(String body, Instant instant, double latDeg, double lonDeg);
        String name();
    }

    public record BodyPosition(double raDegrees, double decDegrees, double distanceAu) {}
    public record EventResult(String body, String event, String localTime, String utcTime, String engine, String estimatedAccuracy, String note) {}
    public record EngineResult(BodyPositionProvider provider, String description) {}
    private record Crossing(String body, String event, Instant instant, String note) {}

    public static final class SwissEphemerisProvider implements BodyPositionProvider {
        private static final double EARTH_EQUATORIAL_RADIUS_METRES = 6378137.0;
        private static final double EARTH_FLATTENING = 1.0 / 298.257223563;
        private static final double EARTH_E2 = EARTH_FLATTENING * (2.0 - EARTH_FLATTENING);
        private static final double METRES_PER_AU = AU_KM * 1000.0;

        private final Object swiss;
        private final Method calcUt;
        private final Method setTopocentric;
        private final int sun;
        private final int moon;
        private final int flags;
        private final double heightMetres;

        SwissEphemerisProvider(String ephePath, double lat, double lon, double heightMetres) throws Exception {
            Class<?> swissClass;
            Class<?> constClass;
            try {
                swissClass = Class.forName("de.thmac.swisseph.SwissEph");
                constClass = Class.forName("de.thmac.swisseph.SweConst");
            } catch (ClassNotFoundException ex) {
                swissClass = Class.forName("swisseph.SwissEph");
                constClass = Class.forName("swisseph.SweConst");
            }
            swiss = swissClass.getConstructor().newInstance();
            if (!ephePath.isBlank()) {
                try {
                    Method path = swissClass.getMethod("swe_set_ephe_path", String.class);
                    path.invoke(swiss, ephePath);
                } catch (NoSuchMethodException ignored) {
                }
            }

            /*
             * We still call swe_set_topo when the Java port provides it, but we do NOT set
             * SEFLG_TOPOCTR in swe_calc_ut. Some Java Swiss Ephemeris builds can report
             * "geographic position has not been set" even after swe_set_topo() when the
             * topocentric flag is used through reflection. To avoid that failure and keep the
             * result accurate for rise/set work, we ask Swiss Ephemeris for high precision
             * apparent GEOCENTRIC equatorial coordinates, then apply the topocentric parallax
             * correction ourselves with a WGS-84 observer vector. This fixes the dialog box
             * error and still includes observer height.
             */
            Method topo = null;
            try {
                topo = swissClass.getMethod("swe_set_topo", double.class, double.class, double.class);
                topo.invoke(swiss, lon, lat, heightMetres);
            } catch (NoSuchMethodException ignored) {
            }
            setTopocentric = topo;
            calcUt = swissClass.getMethod("swe_calc_ut", double.class, int.class, int.class, double[].class, StringBuffer.class);
            sun = intConst(constClass, "SE_SUN", 0);
            moon = intConst(constClass, "SE_MOON", 1);
            int swieph = intConst(constClass, "SEFLG_SWIEPH", 2);
            int speed = intConst(constClass, "SEFLG_SPEED", 256);
            int equatorial = intConst(constClass, "SEFLG_EQUATORIAL", 2048);
            flags = swieph | speed | equatorial;
            this.heightMetres = heightMetres;
        }

        @Override public BodyPosition position(String body, Instant instant, double latDeg, double lonDeg) {
            try {
                if (setTopocentric != null) setTopocentric.invoke(swiss, lonDeg, latDeg, heightMetres);
                double[] xx = new double[6];
                StringBuffer serr = new StringBuffer();
                int ipl = body.equals("Sun") ? sun : moon;
                Object ret = calcUt.invoke(swiss, julianDate(instant), ipl, flags, xx, serr);
                if (ret instanceof Number n && n.intValue() < 0) throw new IllegalStateException(serr.toString());

                // Swiss gives apparent geocentric RA/Dec/distance in AU. Convert to a vector.
                double ra = Math.toRadians(fixAngle(xx[0]));
                double dec = Math.toRadians(xx[1]);
                double distAu = Math.abs(xx[2]);
                double bodyX = distAu * Math.cos(dec) * Math.cos(ra);
                double bodyY = distAu * Math.cos(dec) * Math.sin(ra);
                double bodyZ = distAu * Math.sin(dec);

                // WGS-84 observer vector, rotated from Earth-fixed to equatorial inertial axes.
                double[] obs = observerVectorAu(instant, latDeg, lonDeg, heightMetres);

                // Topocentric vector from observer to body.
                double tx = bodyX - obs[0];
                double ty = bodyY - obs[1];
                double tz = bodyZ - obs[2];
                double range = Math.sqrt(tx * tx + ty * ty + tz * tz);
                double topoRa = fixAngle(Math.toDegrees(Math.atan2(ty, tx)));
                double topoDec = Math.toDegrees(Math.asin(tz / range));
                return new BodyPosition(topoRa, topoDec, range);
            } catch (Exception ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                throw new IllegalStateException("Swiss Ephemeris calculation failed: " + cause.getMessage(), cause);
            }
        }

        @Override public String name() { return "GPL Swiss Ephemeris + WGS-84 topocentric correction"; }

        private static double[] observerVectorAu(Instant instant, double latDeg, double lonDeg, double heightMetres) {
            double lat = Math.toRadians(latDeg);
            double lon = Math.toRadians(lonDeg);
            double sinLat = Math.sin(lat);
            double cosLat = Math.cos(lat);
            double n = EARTH_EQUATORIAL_RADIUS_METRES / Math.sqrt(1.0 - EARTH_E2 * sinLat * sinLat);
            double xEcef = (n + heightMetres) * cosLat * Math.cos(lon);
            double yEcef = (n + heightMetres) * cosLat * Math.sin(lon);
            double zEcef = (n * (1.0 - EARTH_E2) + heightMetres) * sinLat;

            double theta = Math.toRadians(localSiderealTimeDegrees(instant, 0.0));
            double xEci = Math.cos(theta) * xEcef - Math.sin(theta) * yEcef;
            double yEci = Math.sin(theta) * xEcef + Math.cos(theta) * yEcef;
            double zEci = zEcef;
            return new double[] { xEci / METRES_PER_AU, yEci / METRES_PER_AU, zEci / METRES_PER_AU };
        }

        private static int intConst(Class<?> clazz, String field, int fallback) {
            try {
                Field f = clazz.getField(field);
                return ((Number) f.get(null)).intValue();
            } catch (Exception ex) {
                return fallback;
            }
        }
    }

    public static final class ApproximateProvider implements BodyPositionProvider {
        @Override public BodyPosition position(String body, Instant instant, double latDeg, double lonDeg) {
            double jd = julianDate(instant);
            return body.equals("Sun") ? sunEquatorial(jd) : moonEquatorial(jd);
        }
        @Override public String name() { return "Built-in approximation"; }
    }

    private static BodyPosition sunEquatorial(double jd) {
        double n = jd - 2451545.0;
        double meanLong = fixAngle(280.460 + 0.9856474 * n);
        double meanAnom = fixAngle(357.528 + 0.9856003 * n);
        double lambda = fixAngle(meanLong + 1.915 * sinDeg(meanAnom) + 0.020 * sinDeg(2 * meanAnom));
        double epsilon = 23.439 - 0.0000004 * n;
        double ra = fixAngle(Math.toDegrees(Math.atan2(cosDeg(epsilon) * sinDeg(lambda), cosDeg(lambda))));
        double dec = Math.toDegrees(Math.asin(sinDeg(epsilon) * sinDeg(lambda)));
        return new BodyPosition(ra, dec, 1.0);
    }

    private static BodyPosition moonEquatorial(double jd) {
        double d = jd - 2451543.5;
        double N = fixAngle(125.1228 - 0.0529538083 * d);
        double i = 5.1454;
        double w = fixAngle(318.0634 + 0.1643573223 * d);
        double a = 60.2666;
        double e = 0.054900;
        double M = fixAngle(115.3654 + 13.0649929509 * d);
        double E = solveKeplerDegrees(M, e);
        double xv = a * (cosDeg(E) - e);
        double yv = a * (Math.sqrt(1.0 - e * e) * sinDeg(E));
        double v = Math.toDegrees(Math.atan2(yv, xv));
        double r = Math.sqrt(xv * xv + yv * yv);
        double xh = r * (cosDeg(N) * cosDeg(v + w) - sinDeg(N) * sinDeg(v + w) * cosDeg(i));
        double yh = r * (sinDeg(N) * cosDeg(v + w) + cosDeg(N) * sinDeg(v + w) * cosDeg(i));
        double zh = r * (sinDeg(v + w) * sinDeg(i));
        double lonecl = Math.toDegrees(Math.atan2(yh, xh));
        double latecl = Math.toDegrees(Math.atan2(zh, Math.sqrt(xh * xh + yh * yh)));
        double Ms = fixAngle(356.0470 + 0.9856002585 * d);
        double Ls = fixAngle(280.460 + 0.98564736 * d);
        double Lm = fixAngle(N + w + M);
        double D = fixAngle(Lm - Ls);
        double F = fixAngle(Lm - N);
        lonecl += -1.274 * sinDeg(M - 2 * D) + 0.658 * sinDeg(2 * D) - 0.186 * sinDeg(Ms)
                -0.059 * sinDeg(2 * M - 2 * D) -0.057 * sinDeg(M - 2 * D + Ms)
                +0.053 * sinDeg(M + 2 * D) +0.046 * sinDeg(2 * D - Ms) +0.041 * sinDeg(M - Ms)
                -0.035 * sinDeg(D) -0.031 * sinDeg(M + Ms) -0.015 * sinDeg(2 * F - 2 * D)
                +0.011 * sinDeg(M - 4 * D);
        latecl += -0.173 * sinDeg(F - 2 * D) -0.055 * sinDeg(M - F - 2 * D)
                -0.046 * sinDeg(M + F - 2 * D) +0.033 * sinDeg(F + 2 * D) +0.017 * sinDeg(2 * M + F);
        double epsilon = 23.4393 - 3.563E-7 * d;
        double xe = r * cosDeg(lonecl) * cosDeg(latecl);
        double ye = r * (sinDeg(lonecl) * cosDeg(latecl) * cosDeg(epsilon) - sinDeg(latecl) * sinDeg(epsilon));
        double ze = r * (sinDeg(lonecl) * cosDeg(latecl) * sinDeg(epsilon) + sinDeg(latecl) * cosDeg(epsilon));
        double ra = fixAngle(Math.toDegrees(Math.atan2(ye, xe)));
        double dec = Math.toDegrees(Math.atan2(ze, Math.sqrt(xe * xe + ye * ye)));
        double distanceAu = r * 6378.14 / AU_KM;
        return new BodyPosition(ra, dec, distanceAu);
    }

    private static double solveKeplerDegrees(double meanAnomalyDeg, double eccentricity) {
        double M = Math.toRadians(meanAnomalyDeg);
        double E = M;
        for (int n = 0; n < 12; n++) E -= (E - eccentricity * Math.sin(E) - M) / (1.0 - eccentricity * Math.cos(E));
        return Math.toDegrees(E);
    }

    private static double sinDeg(double x) { return Math.sin(Math.toRadians(x)); }
    private static double cosDeg(double x) { return Math.cos(Math.toRadians(x)); }
    private static double fixAngle(double degrees) {
        double v = degrees % 360.0;
        return v < 0 ? v + 360.0 : v;
    }
    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
}
