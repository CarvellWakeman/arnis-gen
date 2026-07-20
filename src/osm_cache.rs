//! Tiled on-disk cache for OpenStreetMap (Overpass) data.
//!
//! Region baking fetches a small area per region. Without caching, adjacent
//! region bakes each re-query Overpass for overlapping ground. This module snaps
//! fetches to a fixed geographic grid and caches each tile's raw Overpass JSON,
//! so a tile is fetched at most once and reused by every region bake that
//! overlaps it. A region's data is the merge of its covering tiles.
//!
//! Elevation and land cover already cache this way (see `elevation::cache` and
//! `land_cover`); this brings OSM in line so on-demand streaming stops hammering
//! Overpass for ground it already has.
//!
//! Tunables (env): `ARNIS_OSM_TILE_DEG` (grid size in degrees, default 0.02 ≈
//! 2 km) and `ARNIS_OSM_CACHE_TTL_DAYS` (freshness, default 7).

use crate::coordinate_system::geographic::LLBBox;
use crate::osm_parser::OsmData;
use crate::retrieve_data::fetch_overpass_raw;
use serde::Deserialize;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

const OSM_CACHE_DIR: &str = "arnis-osm-cache";
const DEFAULT_TILE_DEGREES: f64 = 0.02;
const DEFAULT_TTL_DAYS: u64 = 7;

/// Root cache directory (OS cache dir, falling back to `./`), mirroring the
/// elevation and land-cover caches.
fn cache_root() -> PathBuf {
    dirs::cache_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join(OSM_CACHE_DIR)
}

fn tile_degrees() -> f64 {
    std::env::var("ARNIS_OSM_TILE_DEG")
        .ok()
        .and_then(|s| s.parse::<f64>().ok())
        .filter(|v| v.is_finite() && *v > 0.0)
        .unwrap_or(DEFAULT_TILE_DEGREES)
}

fn ttl() -> Duration {
    let days = std::env::var("ARNIS_OSM_CACHE_TTL_DAYS")
        .ok()
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(DEFAULT_TTL_DAYS);
    Duration::from_secs(days.saturating_mul(24 * 60 * 60))
}

/// Inclusive tile indices covering `bbox` at grid size `t` (degrees). Tile
/// `(i, j)` spans longitude `[i*t, (i+1)*t)` and latitude `[j*t, (j+1)*t)`.
fn covering_tiles(bbox: &LLBBox, t: f64) -> Vec<(i64, i64)> {
    let i0 = (bbox.min().lng() / t).floor() as i64;
    let i1 = (bbox.max().lng() / t).floor() as i64;
    let j0 = (bbox.min().lat() / t).floor() as i64;
    let j1 = (bbox.max().lat() / t).floor() as i64;
    let mut tiles = Vec::new();
    for j in j0..=j1 {
        for i in i0..=i1 {
            tiles.push((i, j));
        }
    }
    tiles
}

/// Geographic bbox of tile `(i, j)`, or `None` if it falls outside valid lat/lng.
fn tile_bbox(i: i64, j: i64, t: f64) -> Option<LLBBox> {
    let min_lng = i as f64 * t;
    let min_lat = j as f64 * t;
    LLBBox::new(min_lat, min_lng, min_lat + t, min_lng + t).ok()
}

fn tile_cache_path(i: i64, j: i64, t: f64) -> PathBuf {
    // Encode the grid size in the path so changing it never reuses wrong-sized tiles.
    cache_root()
        .join(format!("t{t:.6}"))
        .join(format!("{i}_{j}.json"))
}

fn is_fresh(path: &Path) -> bool {
    let Ok(meta) = std::fs::metadata(path) else {
        return false;
    };
    let Ok(modified) = meta.modified() else {
        return false;
    };
    match SystemTime::now().duration_since(modified) {
        Ok(age) => age < ttl(),
        Err(_) => true, // future mtime (clock skew): treat as fresh
    }
}

/// Fetch OSM data for `bbox` via the tiled disk cache. Each covering grid tile is
/// fetched from Overpass at most once; adjacent region bakes reuse shared tiles.
pub fn fetch_osm_tiled(
    bbox: LLBBox,
    debug: bool,
    download_method: &str,
) -> Result<OsmData, Box<dyn std::error::Error>> {
    let t = tile_degrees();
    let tiles = covering_tiles(&bbox, t);

    let mut datasets: Vec<OsmData> = Vec::with_capacity(tiles.len());
    let mut hits = 0usize;
    let mut misses = 0usize;

    for (i, j) in &tiles {
        let path = tile_cache_path(*i, *j, t);
        let json = if is_fresh(&path) {
            match std::fs::read_to_string(&path) {
                Ok(s) => {
                    hits += 1;
                    s
                }
                Err(_) => {
                    misses += 1;
                    fetch_and_cache_tile(*i, *j, t, download_method, &path)?
                }
            }
        } else {
            misses += 1;
            fetch_and_cache_tile(*i, *j, t, download_method, &path)?
        };

        let mut de = serde_json::Deserializer::from_str(&json);
        match OsmData::deserialize(&mut de) {
            Ok(data) => datasets.push(data),
            Err(e) => {
                // A corrupt cache entry must not be fatal: drop it so it refetches.
                eprintln!("Warning: ignoring unreadable OSM cache tile {i},{j}: {e}");
                let _ = std::fs::remove_file(&path);
            }
        }
    }

    println!(
        "  OSM cache: {} tile(s) — {hits} reused, {misses} fetched",
        tiles.len()
    );
    if debug {
        println!("  OSM cache dir: {}", cache_root().display());
    }

    Ok(OsmData::merged(datasets))
}

/// Fetch one tile from Overpass and write it to the cache (temp file + rename so
/// a crash mid-write can't leave a truncated entry). Returns the raw JSON.
fn fetch_and_cache_tile(
    i: i64,
    j: i64,
    t: f64,
    download_method: &str,
    path: &Path,
) -> Result<String, Box<dyn std::error::Error>> {
    let bbox = tile_bbox(i, j, t).ok_or_else(|| format!("invalid OSM tile bbox {i},{j}"))?;
    let json = fetch_overpass_raw(bbox, download_method)?;
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let tmp = path.with_extension("json.tmp");
    if std::fs::write(&tmp, json.as_bytes()).is_ok() {
        let _ = std::fs::rename(&tmp, path);
    }
    Ok(json)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn covering_tiles_single_tile_for_small_bbox() {
        // A ~640 m box well inside one 0.02° tile maps to exactly one tile.
        let bbox = LLBBox::new(51.5110, -0.1160, 51.5158, -0.1067).unwrap();
        let tiles = covering_tiles(&bbox, 0.02);
        assert_eq!(tiles.len(), 1);
    }

    #[test]
    fn covering_tiles_spans_boundary() {
        // A box straddling a 0.02° gridline in longitude covers two columns.
        let bbox = LLBBox::new(51.5110, -0.1010, 51.5158, -0.0990).unwrap();
        let tiles = covering_tiles(&bbox, 0.02);
        assert!(tiles.len() >= 2, "expected boundary span, got {tiles:?}");
    }

    #[test]
    fn adjacent_small_bakes_share_a_tile() {
        // Two nearby region bakes that both fall in the same tile resolve to the
        // same cache path — the reuse the cache is built for.
        let a = LLBBox::new(51.5110, -0.1160, 51.5158, -0.1120).unwrap();
        let b = LLBBox::new(51.5112, -0.1155, 51.5160, -0.1118).unwrap();
        let ta = covering_tiles(&a, 0.02);
        let tb = covering_tiles(&b, 0.02);
        assert_eq!(ta, tb);
        assert_eq!(
            tile_cache_path(ta[0].0, ta[0].1, 0.02),
            tile_cache_path(tb[0].0, tb[0].1, 0.02)
        );
    }
}
