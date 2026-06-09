# Astro Rise/Set Calculator - GPL Swiss Ephemeris Version

Maven Java 25 Swing application for calculating sunrise, sunset, moonrise and moonset for a latitude/longitude, observer height, time zone and date in `dd/mm/yyyy` format.

## What changed in this fixed version

- Swiss Ephemeris is included as a normal Maven dependency, not hidden behind an optional profile.
- The UI always selects Swiss Ephemeris.
- The previous `geographic position has not been set` error is avoided.
- The code no longer passes `SEFLG_TOPOCTR` into `swe_calc_ut`, because some Java Swiss Ephemeris builds can throw that error even after `swe_set_topo()`.
- Instead, it asks Swiss Ephemeris for high precision apparent geocentric equatorial Sun/Moon positions and then applies its own WGS-84 topocentric/parallax correction using the entered latitude, longitude and observer height.
- Atmospheric refraction is deliberately disabled, as requested.
- Observer height is used twice where appropriate: in the topocentric observer vector and in the geometric dip of the sea horizon.
- Each rise/set result now includes an estimated uncertainty in seconds, such as `±5 s estimated`.

## Build

```bash
mvn clean package
```

Run the shaded jar:

```bash
java -jar target/astro-rise-set-swiss-gpl-3.2.0.jar
```

## Eclipse

Import as an existing Maven project, or import the folder directly. `.project` and `.classpath` are included.

## Accuracy note

Swiss Ephemeris gives much better Sun/Moon positions than the original approximation, including planetary/lunar perturbations in the ephemeris solution. The program searches the horizon crossing to the nearest second and now displays a conservative estimated timing uncertainty for each event. That estimate converts a small assumed angular/model error into seconds using the local vertical motion of the Sun or Moon at the horizon crossing. Near polar/grazing events, the uncertainty can become much larger. True real-world rise/set times are still not guaranteed to one physical second unless you also know the real local horizon, pressure, temperature and refraction. This version intentionally assumes no atmosphere.

## Licence

This generated application is intended for the GPL route because it depends on the GPL-compatible Swiss Ephemeris Java port.
