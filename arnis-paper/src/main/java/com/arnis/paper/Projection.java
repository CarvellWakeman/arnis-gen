package com.arnis.paper;

/**
 * Web Mercator projection with a fixed origin, mirroring arnis's Rust
 * {@code WebMercatorProjection} / {@code CoordTransformer::with_web_mercator_origin}.
 *
 * <p>Because the plugin and the bake share the same origin, scale, and formula,
 * {@code /arnis goto} lands a player exactly where the corresponding real-world
 * coordinate was baked. The origin maps to Minecraft {@code (0,0)}; east is +x
 * and north is -z, and the final cast truncates toward zero to match Rust's
 * {@code as i32}.
 */
public final class Projection {

    /** Mean Earth radius (WGS84 spherical approximation) — must match the Rust value. */
    private static final double EARTH_RADIUS = 6_371_000.0;

    private final double originLat;
    private final double originLon;
    private final double scale;
    private final double cosLatRef;
    private final double zOffset;

    public Projection(double originLat, double originLon, double scale) {
        this.originLat = originLat;
        this.originLon = originLon;
        this.scale = scale;
        this.cosLatRef = Math.cos(Math.toRadians(originLat));
        // z_offset chosen so forward(originLat, _) yields z = 0 (matches Rust).
        this.zOffset = EARTH_RADIUS
                * Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(originLat) / 2.0))
                * scale;
    }

    /** Real-world (lat, lon) in degrees to Minecraft block coordinates {@code [x, z]}. */
    public int[] forward(double lat, double lon) {
        double x = EARTH_RADIUS * Math.toRadians(lon - originLon) * cosLatRef * scale;
        double z = -EARTH_RADIUS
                * Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(lat) / 2.0))
                * scale
                + zOffset;
        return new int[] {(int) x, (int) z};
    }
}
