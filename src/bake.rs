//! Region-bake mode: the on-demand backend for the server-side terrain generator.
//!
//! Instead of building a full world from a geographic bounding box, this renders a
//! single Minecraft region (`512x512` blocks) on demand and writes just its
//! `r.rx.rz.mca` file into an existing world directory. A server-side mod invokes
//! this per region as players explore.
//!
//! The coordinate frame is anchored by a fixed Web Mercator `--origin`, shared by
//! every bake, so independent region bakes line up seamlessly. The target region's
//! block bounds are inverse-projected through that origin to derive the geographic
//! area to fetch; a configurable `--bake-margin` of surrounding blocks is fetched
//! and rendered for cross-boundary context (roads/rivers/relations spanning the
//! edge) but only the target region is committed to disk.

use crate::args::Args;
use crate::coordinate_system::geographic::LLBBox;
use crate::data_processing::{self, GenerationOptions};
use crate::ground;
use crate::osm_parser;
use crate::projection::{Projection, ProjectionKind, WebMercatorProjection};
use crate::world_editor::WorldFormat;
use colored::Colorize;

/// Blocks per Minecraft region along one axis (32 chunks * 16 blocks).
const REGION_BLOCKS: i32 = 512;

/// Entry point for `--bake-region`. Expects `args.bake_region`, `args.origin`, and
/// `args.path` to be set (enforced by `args::validate_args`). Mutates `args.bbox`
/// to the region-derived area before running generation, then writes only the
/// target region's `.mca` and prints a machine-readable result line.
pub fn run(mut args: Args) {
    let (rx, rz) = args.bake_region.expect("bake_region set (validated)");
    let (origin_lat, origin_lon) = args.origin.expect("origin set (validated)");
    let world_dir = args.path.clone().expect("output-dir set (validated)");
    let margin = args.bake_margin.max(0);

    let fetch_bbox = match region_fetch_bbox(rx, rz, margin, origin_lat, origin_lon, args.scale) {
        Ok(b) => b,
        Err(e) => fail(rx, rz, &format!("failed to derive bbox for region: {e}")),
    };
    let (min_lat, min_lon, max_lat, max_lon) = (
        fetch_bbox.min().lat(),
        fetch_bbox.min().lng(),
        fetch_bbox.max().lat(),
        fetch_bbox.max().lng(),
    );

    // Drive the rest of the pipeline through the region-derived bbox and the fixed
    // Web Mercator origin so terrain and objects land at absolute region coords.
    args.bbox = Some(fetch_bbox);
    args.projection = ProjectionKind::WebMercator;

    println!(
        "{} Baking region {},{}  (fetch bbox {:.6},{:.6},{:.6},{:.6}, margin {})",
        "[bake]".bold(),
        rx,
        rz,
        min_lat,
        min_lon,
        max_lat,
        max_lon,
        margin
    );

    // Fetch elevation/land cover and (unless terrain-only) OSM objects.
    let mut ground = ground::generate_ground_data(&args);

    let skip_objects = args.skip_objects();
    let raw_data = if skip_objects {
        osm_parser::OsmData::empty()
    } else {
        let fetched = match &args.file {
            Some(file) => crate::retrieve_data::fetch_data_from_file(file),
            // Tiled disk cache so adjacent region bakes reuse Overpass fetches.
            None => crate::osm_cache::fetch_osm_tiled(
                fetch_bbox,
                args.debug,
                args.downloader.as_str(),
            ),
        };
        match fetched {
            Ok(d) => d,
            Err(e) => fail(rx, rz, &format!("failed to fetch OSM data: {e}")),
        }
    };

    // Parse with the fixed origin so nodes project to absolute region coordinates.
    let (mut parsed_elements, xzbbox, outline_suppression, part_groups) =
        osm_parser::parse_osm_data_with_origin(
            raw_data,
            fetch_bbox,
            args.scale,
            args.debug,
            ProjectionKind::WebMercator,
            Some((origin_lat, origin_lon)),
        );

    // Same pre-generation preprocessing the normal pipeline applies, minus the
    // per-world/transform steps (map transforms and rotation would break the
    // shared coordinate frame and are intentionally skipped in bake mode).
    parsed_elements.sort_by_key(osm_parser::get_priority);
    ground.apply_osm_water_override(&parsed_elements, &xzbbox);
    ground.apply_bridge_land_cover_repair(&parsed_elements, &xzbbox, args.scale);

    let element_count = parsed_elements.len();

    let options = GenerationOptions {
        path: world_dir.clone(),
        format: WorldFormat::JavaAnvil,
        level_name: None,
        spawn_point: None,
        luanti_game: None,
        ground_level: args.ground_level,
        bake_target_region: Some((rx, rz)),
    };

    match data_processing::generate_world_with_options(
        parsed_elements,
        xzbbox,
        fetch_bbox,
        ground,
        &args,
        options,
        outline_suppression,
        part_groups,
    ) {
        Ok(_) => {
            let region_file = world_dir
                .join("region")
                .join(format!("r.{rx}.{rz}.mca"));
            emit_result(&format!(
                "{{\"status\":\"ok\",\"region\":[{rx},{rz}],\"region_file\":{},\"element_count\":{element_count}}}",
                json_str(&region_file.to_string_lossy())
            ));
        }
        Err(e) => fail(rx, rz, &format!("generation failed: {e}")),
    }
}

/// The geographic bbox to fetch for baking region `(rx, rz)`, expanded by
/// `margin` blocks of halo, under the fixed Web Mercator origin.
///
/// Pure and deterministic: the result depends only on the region, margin, and
/// origin/scale — never on any previously-seen bbox. That is what lets
/// independently-baked regions line up seamlessly.
fn region_fetch_bbox(
    rx: i32,
    rz: i32,
    margin: i32,
    origin_lat: f64,
    origin_lon: f64,
    scale: f64,
) -> Result<LLBBox, String> {
    // Target region block bounds (inclusive), expanded by the halo margin.
    let fetch_min_x = rx * REGION_BLOCKS - margin;
    let fetch_max_x = rx * REGION_BLOCKS + (REGION_BLOCKS - 1) + margin;
    let fetch_min_z = rz * REGION_BLOCKS - margin;
    let fetch_max_z = rz * REGION_BLOCKS + (REGION_BLOCKS - 1) + margin;

    // Inverse-project all four corners through the fixed origin and envelope them,
    // so the box stays valid regardless of hemisphere/orientation.
    let proj = WebMercatorProjection::new(origin_lat, origin_lon, scale);
    let corners = [
        (fetch_min_x, fetch_min_z),
        (fetch_min_x, fetch_max_z),
        (fetch_max_x, fetch_min_z),
        (fetch_max_x, fetch_max_z),
    ];
    let mut min_lat = f64::INFINITY;
    let mut max_lat = f64::NEG_INFINITY;
    let mut min_lon = f64::INFINITY;
    let mut max_lon = f64::NEG_INFINITY;
    for (x, z) in corners {
        let (lat, lon) = proj.inverse(x as f64, z as f64);
        min_lat = min_lat.min(lat);
        max_lat = max_lat.max(lat);
        min_lon = min_lon.min(lon);
        max_lon = max_lon.max(lon);
    }

    LLBBox::new(min_lat, min_lon, max_lat, max_lon)
}

/// Print the machine-readable result line the server-side mod parses.
fn emit_result(json: &str) {
    println!("ARNIS_BAKE_RESULT {json}");
}

/// Emit an error result and exit non-zero. Never returns.
fn fail(rx: i32, rz: i32, message: &str) -> ! {
    eprintln!("{} {}", "Error:".red().bold(), message);
    emit_result(&format!(
        "{{\"status\":\"error\",\"region\":[{rx},{rz}],\"error\":{}}}",
        json_str(message)
    ));
    std::process::exit(1);
}

/// Minimal JSON string escaping for the two string fields we emit (paths, messages).
fn json_str(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const OLAT: f64 = 40.0;
    const OLON: f64 = -75.0;

    #[test]
    fn fetch_bbox_is_deterministic() {
        // Same inputs must always yield the same box (the seamless-bake guarantee).
        let a = region_fetch_bbox(3, -2, 64, OLAT, OLON, 1.0).unwrap();
        let b = region_fetch_bbox(3, -2, 64, OLAT, OLON, 1.0).unwrap();
        assert_eq!(a, b);
    }

    #[test]
    fn origin_region_corner_maps_to_origin() {
        // Region (0,0) with no margin covers blocks [0,511]. Block (0,0) inverse-
        // projects to the origin: north (z=0) is the max latitude, west (x=0) the
        // min longitude.
        let b = region_fetch_bbox(0, 0, 0, OLAT, OLON, 1.0).unwrap();
        assert!((b.max().lat() - OLAT).abs() < 1e-6, "max lat {}", b.max().lat());
        assert!((b.min().lng() - OLON).abs() < 1e-6, "min lng {}", b.min().lng());
        // The region spans southward and eastward from the origin corner.
        assert!(b.min().lat() < OLAT);
        assert!(b.max().lng() > OLON);
    }

    #[test]
    fn larger_margin_widens_bbox() {
        let small = region_fetch_bbox(1, 1, 0, OLAT, OLON, 1.0).unwrap();
        let large = region_fetch_bbox(1, 1, 128, OLAT, OLON, 1.0).unwrap();
        assert!(large.min().lat() < small.min().lat());
        assert!(large.max().lat() > small.max().lat());
        assert!(large.min().lng() < small.min().lng());
        assert!(large.max().lng() > small.max().lng());
    }

    #[test]
    fn adjacent_regions_are_contiguous_in_longitude() {
        // With no margin, region (1,0) begins one block east of where region (0,0)
        // ends, so its west edge sits just east of region (0,0)'s east edge.
        let r0 = region_fetch_bbox(0, 0, 0, OLAT, OLON, 1.0).unwrap();
        let r1 = region_fetch_bbox(1, 0, 0, OLAT, OLON, 1.0).unwrap();
        assert!(r1.min().lng() > r0.max().lng());
        // The gap between them is about one block (~1 / (meters-per-degree)).
        let gap_m = (r1.min().lng() - r0.max().lng()).to_radians() * 6_371_000.0 * OLAT.to_radians().cos();
        assert!(gap_m < 3.0, "adjacent-region longitude gap too large: {gap_m} m");
    }
}
