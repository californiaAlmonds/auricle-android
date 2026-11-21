//! Stream extraction and URL caching for Android.
//!
//! Uses YouTube's Innertube API with VISIONOS (and IOS fallback) client identities
//! to extract direct audio stream URLs without requiring yt-dlp or browser sandboxes.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

const TTL_SECS: u64 = 6 * 3600; // 6 hours
const EXPIRE_SAFETY_MARGIN_SECS: u64 = 300; // 5 minutes
const MAX_CACHE_ENTRIES: usize = 256;

const VISIONOS_UA: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";
const IOS_UA: &str = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X; en_US)";

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct CachedStream {
    pub url: String,
    pub mime_type: String,
    pub user_agent: String,
    pub duration_secs: u32,
    pub is_hls: bool,
    pub fetched_at_unix: u64,
    #[serde(default)]
    pub expires_at_unix: u64,
}

#[derive(Serialize)]
pub struct StreamResolveResponse {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mime_type: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user_agent: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub duration_secs: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub is_hls: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub expires_at: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

static STREAM_CACHE: OnceLock<Mutex<HashMap<String, CachedStream>>> = OnceLock::new();
static VISITOR_DATA: OnceLock<Mutex<String>> = OnceLock::new();
static HTTP_CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
static DISK_WRITE_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

fn stream_cache() -> &'static Mutex<HashMap<String, CachedStream>> {
    STREAM_CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn visitor_data_store() -> &'static Mutex<String> {
    VISITOR_DATA.get_or_init(|| Mutex::new(String::new()))
}

fn disk_write_lock() -> &'static Mutex<()> {
    DISK_WRITE_LOCK.get_or_init(|| Mutex::new(()))
}

fn http_client() -> &'static reqwest::Client {
    HTTP_CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .cookie_store(true)
            .http2_max_header_list_size(64 * 1024)
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(30))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new())
    })
}

fn current_time_unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn parse_url_expire(url: &str) -> Option<u64> {
    if let Some((_, after_q)) = url.split_once('?') {
        let query = after_q.split('#').next().unwrap_or(after_q);
        for pair in query.split('&') {
            if let Some((k, v)) = pair.split_once('=') {
                if k == "expire" {
                    if let Ok(ts) = v.parse::<u64>() {
                        return Some(ts);
                    }
                }
            }
        }
    }
    let path_part = url.split(['?', '#']).next().unwrap_or(url);
    let segments: Vec<&str> = path_part.split('/').collect();
    for w in segments.windows(2) {
        if w[0] == "expire" {
            if let Ok(ts) = w[1].parse::<u64>() {
                return Some(ts);
            }
        }
    }
    None
}

fn compute_expires_at(url: &str, base_unix: u64) -> u64 {
    let default_exp = base_unix.saturating_add(TTL_SECS);
    match parse_url_expire(url) {
        Some(expire) => default_exp.min(expire.saturating_sub(EXPIRE_SAFETY_MARGIN_SECS)),
        None => default_exp,
    }
}

fn effective_expires_at(entry: &CachedStream) -> u64 {
    if entry.expires_at_unix > 0 {
        entry.expires_at_unix
    } else {
        compute_expires_at(&entry.url, entry.fetched_at_unix)
    }
}

fn prune_and_cap_cache(cache: &mut HashMap<String, CachedStream>, now: u64) {
    cache.retain(|_, v| now < effective_expires_at(v));
    while cache.len() > MAX_CACHE_ENTRIES {
        let oldest_key = cache
            .iter()
            .min_by_key(|(_, v)| (v.fetched_at_unix, effective_expires_at(v)))
            .map(|(k, _)| k.clone());
        match oldest_key {
            Some(k) => {
                cache.remove(&k);
            }
            None => break,
        }
    }
}

fn cache_file_path() -> Option<PathBuf> {
    crate::storage_dir().map(|dir| dir.join("url_cache.json"))
}

pub fn load_disk_cache() {
    let path = match cache_file_path() {
        Some(p) => p,
        None => return,
    };
    if !path.exists() {
        return;
    }
    if let Ok(data) = std::fs::read_to_string(&path) {
        if let Ok(map) = serde_json::from_str::<HashMap<String, CachedStream>>(&data) {
            let now = current_time_unix();
            let mut valid = HashMap::new();
            for (k, mut v) in map {
                let exp = effective_expires_at(&v);
                if now < exp {
                    v.expires_at_unix = exp;
                    valid.insert(k, v);
                }
            }
            prune_and_cap_cache(&mut valid, now);
            if let Ok(mut cache) = stream_cache().lock() {
                *cache = valid;
            }
        }
    }
}

fn write_disk_cache_atomic(path: &Path, json_str: &str) {
    let tmp_path = path.with_file_name("url_cache.json.tmp");
    if std::fs::write(&tmp_path, json_str).is_ok() && std::fs::rename(&tmp_path, path).is_err() {
        let _ = std::fs::remove_file(&tmp_path);
    }
}

pub fn save_disk_cache() {
    let path = match cache_file_path() {
        Some(p) => p,
        None => return,
    };
    let _write_guard = disk_write_lock().lock().ok();
    let data = {
        let guard = stream_cache().lock().ok();
        match guard {
            Some(g) => serde_json::to_string(&*g).ok(),
            None => None,
        }
    };
    if let Some(json_str) = data {
        write_disk_cache_atomic(&path, &json_str);
    }
}

async fn save_disk_cache_async() {
    let _ = tokio::task::spawn_blocking(save_disk_cache).await;
}

pub fn clear_stream_cache() {
    let _write_guard = disk_write_lock().lock().ok();
    if let Ok(mut cache) = stream_cache().lock() {
        cache.clear();
    }
    if let Some(path) = cache_file_path() {
        let tmp_path = path.with_file_name("url_cache.json.tmp");
        let _ = std::fs::remove_file(&tmp_path);
        let _ = std::fs::remove_file(&path);
    }
}

async fn fetch_visitor_data(video_id: &str) -> Option<String> {
    let url = format!(
        "https://www.youtube.com/watch?v={}&bpctr=9999999999&has_verified=1",
        video_id
    );
    let client = http_client();
    let resp = client
        .get(&url)
        .header("User-Agent", VISIONOS_UA)
        .header("Cookie", "PREF=hl=en&tz=UTC; SOCS=CAI")
        .send()
        .await
        .ok()?;
    let text = resp.text().await.ok()?;

    if let Some(pos) = text.find("\"visitorData\":\"") {
        let after = &text[pos + "\"visitorData\":\"".len()..];
        if let Some(end) = after.find('"') {
            let raw = &after[..end];
            return Some(raw.to_string());
        }
    }
    None
}

async fn query_player(
    client_name: &str,
    video_id: &str,
    visitor_data: &str,
    high_quality: bool,
) -> Result<(String, String, String, u32, bool), String> {
    let client = http_client();

    let (c_name, c_ver, make, model, os_name, os_ver, ua, client_id_hdr) = if client_name == "IOS"
    {
        (
            "IOS",
            "20.10.4",
            "Apple",
            "iPhone16,2",
            "iPhone",
            "18.3.2.22D82",
            IOS_UA,
            "5",
        )
    } else {
        (
            "VISIONOS",
            "1.02",
            "Apple",
            "RealityDevice17,1",
            "visionOS",
            "26.5.23O471",
            VISIONOS_UA,
            "101",
        )
    };

    let body = serde_json::json!({
        "context": {
            "client": {
                "clientName": c_name,
                "clientVersion": c_ver,
                "deviceMake": make,
                "deviceModel": model,
                "userAgent": ua,
                "osName": os_name,
                "osVersion": os_ver,
                "hl": "en",
                "timeZone": "UTC",
                "utcOffsetMinutes": 0
            }
        },
        "videoId": video_id,
        "playbackContext": {
            "contentPlaybackContext": {
                "html5Preference": "HTML5_PREF_WANTS"
            }
        },
        "contentCheckOk": true,
        "racyCheckOk": true
    });

    let mut req = client
        .post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        .header("User-Agent", ua)
        .header("Content-Type", "application/json")
        .header("Origin", "https://www.youtube.com")
        .header("X-Youtube-Client-Name", client_id_hdr)
        .header("X-Youtube-Client-Version", c_ver)
        .json(&body);

    if !visitor_data.is_empty() {
        req = req.header("X-Goog-Visitor-Id", visitor_data);
    }

    let resp = req
        .send()
        .await
        .map_err(|e| format!("HTTP request failed: {e}"))?;

    let status = resp.status();
    if !status.is_success() {
        return Err(format!("HTTP error {status}"));
    }

    let json_val: serde_json::Value = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse player JSON: {e}"))?;

    let duration_secs = json_val
        .pointer("/videoDetails/lengthSeconds")
        .and_then(|v| v.as_str())
        .and_then(|s| s.parse::<u32>().ok())
        .or_else(|| {
            json_val
                .pointer("/videoDetails/lengthSeconds")
                .and_then(|v| v.as_u64())
                .map(|n| n as u32)
        })
        .unwrap_or(0);

    let formats = json_val
        .pointer("/streamingData/adaptiveFormats")
        .and_then(|v| v.as_array());

    if let Some(formats) = formats {
        let format_bitrate = |f: &serde_json::Value| -> u64 {
            f.get("averageBitrate")
                .and_then(|v| v.as_u64())
                .filter(|&b| b > 0)
                .or_else(|| f.get("bitrate").and_then(|v| v.as_u64()))
                .unwrap_or(0)
        };

        let audio_candidates: Vec<&serde_json::Value> = formats
            .iter()
            .filter(|f| {
                let mime = f.get("mimeType").and_then(|v| v.as_str()).unwrap_or("");
                let url = f.get("url").and_then(|v| v.as_str()).unwrap_or("");
                mime.starts_with("audio/") && !url.is_empty()
            })
            .collect();

        // 1. Pick audio/mp4 or audio/webm matching requested quality
        let best_audio = if high_quality {
            let best_preferred = audio_candidates
                .iter()
                .copied()
                .filter(|f| {
                    let mime = f.get("mimeType").and_then(|v| v.as_str()).unwrap_or("");
                    mime.starts_with("audio/mp4") || mime.starts_with("audio/webm")
                })
                .max_by_key(|f| {
                    let mime = f.get("mimeType").and_then(|v| v.as_str()).unwrap_or("");
                    (format_bitrate(f), u8::from(mime.starts_with("audio/mp4")))
                });

            // 2. Fallback to highest-bitrate available audio format
            best_preferred.or_else(|| {
                audio_candidates
                    .iter()
                    .copied()
                    .max_by_key(|f| format_bitrate(f))
            })
        } else {
            const TARGET_STD_BITRATE: u64 = 128_000;
            let best_preferred = audio_candidates
                .iter()
                .copied()
                .filter(|f| {
                    let mime = f.get("mimeType").and_then(|v| v.as_str()).unwrap_or("");
                    mime.starts_with("audio/mp4") || mime.starts_with("audio/webm")
                })
                .min_by_key(|f| {
                    let b = format_bitrate(f);
                    let mime = f.get("mimeType").and_then(|v| v.as_str()).unwrap_or("");
                    (
                        b == 0,
                        b.abs_diff(TARGET_STD_BITRATE),
                        b > TARGET_STD_BITRATE,
                        u8::from(!mime.starts_with("audio/mp4")),
                    )
                });

            best_preferred.or_else(|| {
                audio_candidates
                    .iter()
                    .copied()
                    .min_by_key(|f| {
                        let b = format_bitrate(f);
                        (b == 0, b.abs_diff(TARGET_STD_BITRATE), b > TARGET_STD_BITRATE)
                    })
            })
        };

        if let Some(f) = best_audio {
            if let Some(url) = f.get("url").and_then(|v| v.as_str()) {
                let mime = f
                    .get("mimeType")
                    .and_then(|v| v.as_str())
                    .unwrap_or("audio/mp4")
                    .to_string();
                return Ok((url.to_string(), mime, ua.to_string(), duration_secs, false));
            }
        }
    }

    // 3. Fallback to HLS
    if let Some(hls_url) = json_val
        .pointer("/streamingData/hlsManifestUrl")
        .and_then(|v| v.as_str())
    {
        return Ok((
            hls_url.to_string(),
            "application/x-mpegURL".to_string(),
            ua.to_string(),
            duration_secs,
            true,
        ));
    }

    let status = json_val
        .pointer("/playabilityStatus/status")
        .and_then(|v| v.as_str())
        .unwrap_or("UNKNOWN");
    let reason = json_val
        .pointer("/playabilityStatus/reason")
        .and_then(|v| v.as_str())
        .unwrap_or("No playable streams found");

    Err(format!("Playability status {status}: {reason}"))
}

pub async fn resolve_stream(
    video_id: &str,
    force_refresh: bool,
    high_quality: bool,
) -> StreamResolveResponse {
    let now = current_time_unix();
    let cache_key = if high_quality {
        format!("{video_id}:hq")
    } else {
        format!("{video_id}:std")
    };

    if !force_refresh {
        let cached = {
            let guard = stream_cache().lock().ok();
            guard.and_then(|c| c.get(&cache_key).cloned())
        };
        if let Some(c) = cached {
            let exp = effective_expires_at(&c);
            if now < exp {
                return StreamResolveResponse {
                    ok: true,
                    url: Some(c.url),
                    mime_type: Some(c.mime_type),
                    user_agent: Some(c.user_agent),
                    duration_secs: Some(c.duration_secs),
                    is_hls: Some(c.is_hls),
                    expires_at: Some(exp),
                    error: None,
                };
            }
        }
    }

    let mut visitor = {
        let guard = visitor_data_store().lock().ok();
        guard.map(|v| v.clone()).unwrap_or_default()
    };

    if visitor.is_empty() || force_refresh {
        if let Some(v) = fetch_visitor_data(video_id).await {
            visitor = v.clone();
            if let Ok(mut g) = visitor_data_store().lock() {
                *g = v;
            }
        }
    }

    // Try VISIONOS first
    let mut result = query_player("VISIONOS", video_id, &visitor, high_quality).await;

    // Fallback to IOS without reusing a potentially rejected VISITOR_DATA from VISIONOS
    if result.is_err() {
        if !visitor.is_empty() {
            if let Ok(mut g) = visitor_data_store().lock() {
                if *g == visitor {
                    g.clear();
                }
            }
        }
        result = query_player("IOS", video_id, "", high_quality).await;
    }

    match result {
        Ok((url, mime_type, user_agent, duration_secs, is_hls)) => {
            let resolved_now = current_time_unix();
            let expires_at = compute_expires_at(&url, resolved_now);
            let item = CachedStream {
                url: url.clone(),
                mime_type: mime_type.clone(),
                user_agent: user_agent.clone(),
                duration_secs,
                is_hls,
                fetched_at_unix: resolved_now,
                expires_at_unix: expires_at,
            };

            if resolved_now < expires_at {
                if let Ok(mut c) = stream_cache().lock() {
                    c.insert(cache_key, item);
                    prune_and_cap_cache(&mut c, resolved_now);
                }
                save_disk_cache_async().await;
            }

            StreamResolveResponse {
                ok: true,
                url: Some(url),
                mime_type: Some(mime_type),
                user_agent: Some(user_agent),
                duration_secs: Some(duration_secs),
                is_hls: Some(is_hls),
                expires_at: Some(expires_at),
                error: None,
            }
        }
        Err(e) => {
            if force_refresh {
                let removed = stream_cache()
                    .lock()
                    .ok()
                    .map(|mut c| {
                        let a = c.remove(&cache_key).is_some();
                        let b = c.remove(video_id).is_some();
                        a || b
                    })
                    .unwrap_or(false);
                if removed {
                    save_disk_cache_async().await;
                }
            }
            StreamResolveResponse {
                ok: false,
                url: None,
                mime_type: None,
                user_agent: None,
                duration_secs: None,
                is_hls: None,
                expires_at: None,
                error: Some(e),
            }
        }
    }
}
