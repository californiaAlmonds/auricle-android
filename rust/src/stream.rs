//! Stream extraction and URL caching for Android.
//!
//! Uses YouTube's Innertube API with VISIONOS (and IOS fallback) client identities
//! to extract direct audio stream URLs without requiring yt-dlp or browser sandboxes.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

const TTL_SECS: u64 = 6 * 3600; // 6 hours

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
    pub error: Option<String>,
}

static STREAM_CACHE: OnceLock<Mutex<HashMap<String, CachedStream>>> = OnceLock::new();
static VISITOR_DATA: OnceLock<Mutex<String>> = OnceLock::new();
static HTTP_CLIENT: OnceLock<reqwest::Client> = OnceLock::new();

fn stream_cache() -> &'static Mutex<HashMap<String, CachedStream>> {
    STREAM_CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn visitor_data_store() -> &'static Mutex<String> {
    VISITOR_DATA.get_or_init(|| Mutex::new(String::new()))
}

fn http_client() -> &'static reqwest::Client {
    HTTP_CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .cookie_store(true)
            .http2_max_header_list_size(64 * 1024)
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
            for (k, v) in map {
                if now.saturating_sub(v.fetched_at_unix) < TTL_SECS {
                    valid.insert(k, v);
                }
            }
            if let Ok(mut cache) = stream_cache().lock() {
                *cache = valid;
            }
        }
    }
}

pub fn save_disk_cache() {
    let path = match cache_file_path() {
        Some(p) => p,
        None => return,
    };
    let data = {
        let guard = stream_cache().lock().ok();
        match guard {
            Some(g) => serde_json::to_string(&*g).ok(),
            None => None,
        }
    };
    if let Some(json_str) = data {
        let _ = std::fs::write(path, json_str);
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
        // 1. Prefer itag == 140 (audio/mp4)
        for f in formats {
            let itag = f.get("itag").and_then(|v| v.as_u64()).unwrap_or(0);
            if itag == 140 {
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

        // 2. Best audio/mp4 or audio/webm
        for f in formats {
            let mime = f.get("mimeType").and_then(|v| v.as_str()).unwrap_or("");
            if mime.starts_with("audio/mp4") || mime.starts_with("audio/webm") {
                if let Some(url) = f.get("url").and_then(|v| v.as_str()) {
                    return Ok((url.to_string(), mime.to_string(), ua.to_string(), duration_secs, false));
                }
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

pub async fn resolve_stream(video_id: &str, force_refresh: bool) -> StreamResolveResponse {
    let now = current_time_unix();

    if !force_refresh {
        let cached = {
            let guard = stream_cache().lock().ok();
            guard.and_then(|c| c.get(video_id).cloned())
        };
        if let Some(c) = cached {
            if now.saturating_sub(c.fetched_at_unix) < TTL_SECS {
                return StreamResolveResponse {
                    ok: true,
                    url: Some(c.url),
                    mime_type: Some(c.mime_type),
                    user_agent: Some(c.user_agent),
                    duration_secs: Some(c.duration_secs),
                    is_hls: Some(c.is_hls),
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
    let mut result = query_player("VISIONOS", video_id, &visitor).await;

    // Fallback to IOS
    if result.is_err() {
        result = query_player("IOS", video_id, &visitor).await;
    }

    match result {
        Ok((url, mime_type, user_agent, duration_secs, is_hls)) => {
            let item = CachedStream {
                url: url.clone(),
                mime_type: mime_type.clone(),
                user_agent: user_agent.clone(),
                duration_secs,
                is_hls,
                fetched_at_unix: now,
            };

            if let Ok(mut c) = stream_cache().lock() {
                c.insert(video_id.to_string(), item);
            }
            save_disk_cache();

            StreamResolveResponse {
                ok: true,
                url: Some(url),
                mime_type: Some(mime_type),
                user_agent: Some(user_agent),
                duration_secs: Some(duration_secs),
                is_hls: Some(is_hls),
                error: None,
            }
        }
        Err(e) => StreamResolveResponse {
            ok: false,
            url: None,
            mime_type: None,
            user_agent: None,
            duration_secs: None,
            is_hls: None,
            error: Some(e),
        },
    }
}
