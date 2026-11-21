//! Synced & plain lyrics fetching (YTM timed -> LRCLIB synced -> YTM/LRCLIB plain)
//! plus timestamp-preserving Romanization & English translation.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use ytmapi_rs::common::{VideoID, YoutubeID};
use ytmapi_rs::error::ErrorKind;
use ytmapi_rs::query::{GetLyricsIDQuery, GetLyricsQuery};

const MAX_LYRICS_BODY_BYTES: usize = 512 * 1024;
const MAX_CACHE_ENTRIES: usize = 64;
const SYNCED_GTX_CHUNK_BYTES: usize = 2400;
const PLAIN_GTX_CHUNK_BYTES: usize = 2800;

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LyricLineDto {
    pub primary: String,
    pub secondary: String,
    pub is_spacer: bool,
    pub time_ms: u64,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct LyricsResponse {
    pub ok: bool,
    pub available: bool,
    pub source: String,
    pub is_synced: bool,
    pub is_already_en: bool,
    pub has_romanization: bool,
    pub lines: Vec<LyricLineDto>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Clone, Debug, Default)]
pub struct GtxSentence {
    pub orig: String,
    pub trans: String,
    pub src_translit: String,
}

#[derive(Clone, Debug, Default)]
struct CachedRawLyrics {
    available: bool,
    source: String,
    is_synced: bool,
    lrclib_tried: bool,
    lines: Vec<LyricLineDto>,
    plain_text: String,
}

#[derive(Clone, Debug, Default)]
pub struct CachedTranslatedLyrics {
    pub is_already_en: bool,
    pub has_romanization: bool,
    pub is_synced: bool,
    pub english_lines: Vec<LyricLineDto>,
    pub romanized_lines: Vec<LyricLineDto>,
}

static RAW_LYRICS_CACHE: OnceLock<Mutex<HashMap<String, CachedRawLyrics>>> = OnceLock::new();
static TRANS_LYRICS_CACHE: OnceLock<Mutex<HashMap<String, CachedTranslatedLyrics>>> =
    OnceLock::new();
static TRANSLATE_CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
static SYNCED_HTTP_CLIENT: OnceLock<reqwest::Client> = OnceLock::new();

fn raw_lyrics_cache() -> &'static Mutex<HashMap<String, CachedRawLyrics>> {
    RAW_LYRICS_CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn trans_lyrics_cache() -> &'static Mutex<HashMap<String, CachedTranslatedLyrics>> {
    TRANS_LYRICS_CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn translate_client() -> &'static reqwest::Client {
    TRANSLATE_CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .http2_max_header_list_size(64 * 1024)
            .connect_timeout(Duration::from_secs(12))
            .timeout(Duration::from_secs(18))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new())
    })
}

fn synced_http_client() -> &'static reqwest::Client {
    SYNCED_HTTP_CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .user_agent("Auricle/0.4.0-RC (https://github.com/californiaAlmonds/auricle-android)")
            .http2_max_header_list_size(64 * 1024)
            .connect_timeout(Duration::from_secs(4))
            .timeout(Duration::from_secs(6))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new())
    })
}

async fn read_capped_bytes(mut resp: reqwest::Response, max_bytes: usize) -> Result<Vec<u8>, String> {
    if let Some(len) = resp.content_length() {
        if len as usize > max_bytes {
            return Err(format!("Response exceeds {max_bytes} byte limit"));
        }
    }
    let mut buf = Vec::new();
    while let Some(chunk) = resp.chunk().await.map_err(|e| e.to_string())? {
        if buf.len().saturating_add(chunk.len()) > max_bytes {
            return Err(format!("Response exceeded {max_bytes} byte cap"));
        }
        buf.extend_from_slice(&chunk);
    }
    Ok(buf)
}

pub fn has_non_latin_script(text: &str) -> bool {
    text.chars()
        .any(|c| !c.is_ascii() && c.is_alphabetic() && (c as u32) > 0x024F)
}

// ============================================================================
// YTM Native Timed Lyrics Parsing & Fetch
// ============================================================================

fn parse_u64_json(val: &serde_json::Value) -> Option<u64> {
    val.as_u64()
        .or_else(|| val.as_str().and_then(|s| s.trim().parse::<u64>().ok()))
}

fn find_timed_lyrics_array<'a>(val: &'a serde_json::Value) -> Option<&'a Vec<serde_json::Value>> {
    match val {
        serde_json::Value::Object(map) => {
            if let Some(arr) = map.get("timedLyricsData").and_then(serde_json::Value::as_array) {
                return Some(arr);
            }
            map.values().find_map(find_timed_lyrics_array)
        }
        serde_json::Value::Array(arr) => arr.iter().find_map(find_timed_lyrics_array),
        _ => None,
    }
}

fn find_source_message(val: &serde_json::Value) -> Option<String> {
    match val {
        serde_json::Value::Object(map) => {
            if let Some(msg) = map.get("sourceMessage").and_then(serde_json::Value::as_str) {
                let t = msg.trim();
                if !t.is_empty() {
                    return Some(t.to_string());
                }
            }
            map.values().find_map(find_source_message)
        }
        serde_json::Value::Array(arr) => arr.iter().find_map(find_source_message),
        _ => None,
    }
}

pub fn parse_ytm_timed_lyrics(val: &serde_json::Value) -> Option<(Vec<LyricLineDto>, String)> {
    let arr = find_timed_lyrics_array(val)?;
    let mut lines: Vec<LyricLineDto> = Vec::with_capacity(arr.len());
    for item in arr {
        let text = item
            .get("lyricLine")
            .and_then(serde_json::Value::as_str)
            .unwrap_or("")
            .trim();
        if text.is_empty() {
            continue;
        }
        let start_ms = item
            .pointer("/cueRange/startTimeMilliseconds")
            .and_then(parse_u64_json)
            .or_else(|| item.get("startTimeMilliseconds").and_then(parse_u64_json))?;
        lines.push(LyricLineDto {
            primary: text.to_string(),
            secondary: String::new(),
            is_spacer: false,
            time_ms: start_ms,
        });
    }
    if lines.len() < 2 {
        return None;
    }
    lines.sort_by_key(|l| l.time_ms);
    let min_ts = lines.first()?.time_ms;
    let max_ts = lines.last()?.time_ms;
    if max_ts <= min_ts {
        return None;
    }
    let source = find_source_message(val).unwrap_or_else(|| "YouTube Music".to_string());
    Some((lines, source))
}

async fn fetch_ytm_timed_lyrics(lyrics_id: &str) -> Option<(Vec<LyricLineDto>, String)> {
    if lyrics_id.trim().is_empty() {
        return None;
    }
    let body = serde_json::json!({
        "context": {
            "client": {
                "clientName": "ANDROID_MUSIC",
                "clientVersion": "7.21.50"
            }
        },
        "browseId": lyrics_id
    });
    let resp = synced_http_client()
        .post("https://music.youtube.com/youtubei/v1/browse")
        .json(&body)
        .send()
        .await
        .ok()?;
    if !resp.status().is_success() {
        return None;
    }
    let bytes = read_capped_bytes(resp, MAX_LYRICS_BODY_BYTES).await.ok()?;
    let val: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    parse_ytm_timed_lyrics(&val)
}

// ============================================================================
// LRC Parsing & LRCLIB Fallback
// ============================================================================

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LrclibRecord {
    #[serde(default)]
    pub track_name: String,
    #[serde(default)]
    pub artist_name: String,
    #[serde(default)]
    pub album_name: Option<String>,
    #[serde(default)]
    pub duration: Option<f64>,
    #[serde(default)]
    pub synced_lyrics: Option<String>,
    #[serde(default)]
    pub plain_lyrics: Option<String>,
}

fn parse_lrc_timestamp_ms(tag: &str) -> Option<u64> {
    let tag = tag.trim();
    let (min_str, rest) = tag.split_once(':')?;
    if min_str.is_empty() || !min_str.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    let mins: u64 = min_str.parse().ok()?;
    let (sec_str, frac_str) = if let Some((s, f)) = rest.split_once('.') {
        (s, Some(f))
    } else if let Some((s, f)) = rest.split_once(':') {
        (s, Some(f))
    } else {
        (rest, None)
    };
    if sec_str.is_empty() || !sec_str.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    let secs: u64 = sec_str.parse().ok()?;
    let frac_ms: u64 = match frac_str {
        None => 0,
        Some(f) => {
            let digits: String = f.chars().take_while(|c| c.is_ascii_digit()).collect();
            match digits.len() {
                0 => 0,
                1 => digits.parse::<u64>().ok()? * 100,
                2 => digits.parse::<u64>().ok()? * 10,
                _ => digits[..3].parse::<u64>().ok()?,
            }
        }
    };
    Some(mins.saturating_mul(60_000).saturating_add(secs.saturating_mul(1000)).saturating_add(frac_ms))
}

/// Parse standard & multi-tag LRC text (`[mm:ss.xx]`, `[mm:ss.xxx]`, `[offset:ms]`).
pub fn parse_lrc(lrc: &str) -> Vec<LyricLineDto> {
    let mut offset_ms: i64 = 0;
    let mut raw_entries: Vec<(u64, String)> = Vec::new();

    for raw_line in lrc.replace("\r\n", "\n").lines() {
        let mut cursor = raw_line.trim();
        if cursor.is_empty() {
            continue;
        }
        let mut timestamps: Vec<u64> = Vec::new();

        while cursor.starts_with('[') {
            let Some(close_idx) = cursor.find(']') else {
                break;
            };
            let inner = &cursor[1..close_idx];
            if let Some(ts) = parse_lrc_timestamp_ms(inner) {
                timestamps.push(ts);
                cursor = &cursor[close_idx + 1..];
            } else if let Some((tag_key, tag_val)) = inner.split_once(':') {
                let key_lower = tag_key.trim().to_ascii_lowercase();
                if key_lower == "offset" {
                    if let Ok(parsed) = tag_val.trim().parse::<i64>() {
                        offset_ms = parsed;
                    }
                    cursor = &cursor[close_idx + 1..];
                } else if matches!(
                    key_lower.as_str(),
                    "ar" | "ti" | "al" | "by" | "au" | "length" | "re" | "ve" | "la"
                ) {
                    cursor = &cursor[close_idx + 1..];
                } else {
                    break;
                }
            } else {
                break;
            }
        }

        if timestamps.is_empty() {
            continue;
        }
        let text = cursor.trim();
        if text.is_empty() {
            continue;
        }
        for ts in timestamps {
            raw_entries.push((ts, text.to_string()));
        }
    }

    let mut lines: Vec<LyricLineDto> = raw_entries
        .into_iter()
        .map(|(ts, text)| {
            // Standard LRC offset: positive offset shifts lyrics earlier (subtracts from timestamp).
            let adjusted = (ts as i64).saturating_sub(offset_ms).max(0) as u64;
            LyricLineDto {
                primary: text,
                secondary: String::new(),
                is_spacer: false,
                time_ms: adjusted,
            }
        })
        .collect();

    lines.sort_by_key(|l| l.time_ms);
    lines
}

/// Strip movie/edition/video noise from song titles before querying LRCLIB.
pub fn strip_title_noise(title: &str) -> String {
    let before_pipe = title.split('|').next().unwrap_or(title);
    let chars: Vec<char> = before_pipe.chars().collect();
    let mut out = String::with_capacity(before_pipe.len());
    let mut i = 0;
    while i < chars.len() {
        let open = chars[i];
        let close = match open {
            '(' => Some(')'),
            '[' => Some(']'),
            _ => None,
        };
        if let Some(closing) = close {
            let mut j = i + 1;
            while j < chars.len() && chars[j] != closing {
                j += 1;
            }
            if j < chars.len() {
                let inner: String = chars[i + 1..j].iter().collect();
                let lower = inner.to_ascii_lowercase();
                let is_noise = lower.starts_with("from ")
                    || lower.contains("official")
                    || lower.contains("video")
                    || lower.contains("audio")
                    || lower.contains("lyric")
                    || lower.contains("feat.")
                    || lower.contains("feat ")
                    || lower.contains("ft.")
                    || lower.contains("ft ")
                    || lower.contains("with ")
                    || lower.contains("full song")
                    || lower.contains("remaster");
                if is_noise {
                    out.push(' ');
                    i = j + 1;
                    continue;
                }
            }
        }
        out.push(open);
        i += 1;
    }
    out.split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .trim_matches(|c: char| matches!(c, '-' | '\u{2013}' | '\u{2014}' | ' '))
        .to_string()
}

fn primary_artist_name(artist: &str) -> String {
    let credits = crate::menu_metadata::split_fallback_artists(artist);
    credits
        .first()
        .map(|c| c.name.clone())
        .unwrap_or_else(|| artist.trim().to_string())
}

fn norm_match(s: &str) -> String {
    crate::release_rank::title_key(&strip_title_noise(s))
}

fn artist_tokens_overlap(query_artist: &str, cand_artist: &str) -> bool {
    let qa = crate::release_rank::title_key(query_artist);
    let ca = crate::release_rank::title_key(cand_artist);
    if qa.is_empty() || ca.is_empty() {
        return true;
    }
    if qa == ca || ca.contains(&qa) || qa.contains(&ca) {
        return true;
    }
    if strsim::normalized_damerau_levenshtein(&qa, &ca) >= 0.65 {
        return true;
    }
    let cand_tokens: Vec<&str> = ca.split_whitespace().filter(|w| w.len() >= 2).collect();
    qa.split_whitespace()
        .filter(|w| w.len() >= 2)
        .any(|qt| cand_tokens.iter().any(|ct| *ct == qt))
}

pub fn select_best_lrclib_candidate<'a>(
    candidates: &'a [LrclibRecord],
    clean_title: &str,
    primary_artist: &str,
    duration_secs: u32,
) -> Option<&'a LrclibRecord> {
    let norm_query = norm_match(clean_title);
    if norm_query.is_empty() {
        return None;
    }

    let mut best: Option<(f64, &'a LrclibRecord)> = None;
    for cand in candidates {
        let Some(ref synced) = cand.synced_lyrics else {
            continue;
        };
        if synced.trim().is_empty() {
            continue;
        }
        let parsed = parse_lrc(synced);
        if parsed.len() < 2 {
            continue;
        }

        // Duration guard: within 5 seconds when both are known.
        let mut dur_bonus = 0.0;
        if duration_secs > 0 {
            if let Some(cdur) = cand.duration.filter(|d| *d > 0.0) {
                let diff = (cdur - duration_secs as f64).abs();
                if diff > 5.0 {
                    continue;
                }
                dur_bonus = (5.0 - diff) * 0.02;
            }
        }

        // Title similarity guard.
        let norm_cand = norm_match(&cand.track_name);
        if norm_cand.is_empty() {
            continue;
        }
        let sim = strsim::normalized_damerau_levenshtein(&norm_query, &norm_cand);
        let substr_ok = norm_query.chars().count() >= 3
            && norm_cand.chars().count() >= 3
            && (norm_cand.contains(&norm_query) || norm_query.contains(&norm_cand));
        if sim < 0.65 && !substr_ok {
            continue;
        }

        // Artist token overlap guard.
        if !primary_artist.trim().is_empty()
            && !artist_tokens_overlap(primary_artist, &cand.artist_name)
        {
            continue;
        }

        let score = sim + dur_bonus;
        if best.as_ref().map_or(true, |(best_score, _)| score > *best_score) {
            best = Some((score, cand));
        }
    }

    best.map(|(_, c)| c)
}

struct LrclibOutcome {
    synced_lines: Option<Vec<LyricLineDto>>,
    plain_text: Option<String>,
}

async fn fetch_lrclib(
    title: &str,
    artist: &str,
    album: &str,
    duration_secs: u32,
) -> LrclibOutcome {
    let clean_title = strip_title_noise(title);
    if clean_title.is_empty() {
        return LrclibOutcome {
            synced_lines: None,
            plain_text: None,
        };
    }
    let primary_artist = primary_artist_name(artist);
    let clean_album = strip_title_noise(album);
    let client = synced_http_client();
    let mut fallback_plain: Option<String> = None;

    // 1. Exact lookup via `/api/get` using safe `.query(...)` pairs.
    let mut get_params: Vec<(&str, String)> = vec![("track_name", clean_title.clone())];
    if !primary_artist.is_empty() {
        get_params.push(("artist_name", primary_artist.clone()));
    }
    if !clean_album.is_empty() {
        get_params.push(("album_name", clean_album));
    }
    if duration_secs > 0 {
        get_params.push(("duration", duration_secs.to_string()));
    }

    if let Ok(resp) = client
        .get("https://lrclib.net/api/get")
        .query(&get_params)
        .send()
        .await
    {
        if resp.status().is_success() {
            if let Ok(bytes) = read_capped_bytes(resp, MAX_LYRICS_BODY_BYTES).await {
                if let Ok(rec) = serde_json::from_slice::<LrclibRecord>(&bytes) {
                    if let Some(ref synced) = rec.synced_lyrics {
                        let parsed = parse_lrc(synced);
                        if parsed.len() >= 2 {
                            return LrclibOutcome {
                                synced_lines: Some(parsed),
                                plain_text: rec.plain_lyrics,
                            };
                        }
                    }
                    if let Some(plain) = rec.plain_lyrics.filter(|p| !p.trim().is_empty()) {
                        fallback_plain = Some(plain);
                    }
                }
            }
        }
    }

    // 2. Search fallback via `/api/search` (track_name + artist_name, then q).
    let mut search_attempts: Vec<Vec<(&str, String)>> = Vec::new();
    if !primary_artist.is_empty() {
        search_attempts.push(vec![
            ("track_name", clean_title.clone()),
            ("artist_name", primary_artist.clone()),
        ]);
        search_attempts.push(vec![(
            "q",
            format!("{clean_title} {primary_artist}"),
        )]);
    } else {
        search_attempts.push(vec![("q", clean_title.clone())]);
    }

    for params in search_attempts {
        let Ok(resp) = client
            .get("https://lrclib.net/api/search")
            .query(&params)
            .send()
            .await
        else {
            continue;
        };
        if !resp.status().is_success() {
            continue;
        }
        let Ok(bytes) = read_capped_bytes(resp, MAX_LYRICS_BODY_BYTES).await else {
            continue;
        };
        let Ok(records) = serde_json::from_slice::<Vec<LrclibRecord>>(&bytes) else {
            continue;
        };
        if let Some(best) =
            select_best_lrclib_candidate(&records, &clean_title, &primary_artist, duration_secs)
        {
            if let Some(ref synced) = best.synced_lyrics {
                let parsed = parse_lrc(synced);
                if parsed.len() >= 2 {
                    return LrclibOutcome {
                        synced_lines: Some(parsed),
                        plain_text: best.plain_lyrics.clone().or(fallback_plain),
                    };
                }
            }
        }
        if fallback_plain.is_none() {
            if let Some(plain_rec) = records.iter().find(|r| {
                r.plain_lyrics
                    .as_deref()
                    .is_some_and(|p| !p.trim().is_empty())
                    && strsim::normalized_damerau_levenshtein(
                        &norm_match(&clean_title),
                        &norm_match(&r.track_name),
                    ) >= 0.70
                    && (primary_artist.is_empty()
                        || artist_tokens_overlap(&primary_artist, &r.artist_name))
            }) {
                fallback_plain = plain_rec.plain_lyrics.clone();
            }
        }
    }

    LrclibOutcome {
        synced_lines: None,
        plain_text: fallback_plain,
    }
}

// ============================================================================
// Google `gtx` Translation & Romanization (Timestamp-Preserving + Plain)
// ============================================================================

pub fn parse_gtx_chunk_json(val: &serde_json::Value) -> Option<(String, Vec<GtxSentence>)> {
    let src_lang = val
        .get("src")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let sentences_arr = val.get("sentences")?.as_array()?;
    let mut raw_sentences = Vec::with_capacity(sentences_arr.len());
    for s in sentences_arr {
        let orig = s
            .get("orig")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .replace("\r\n", "\n");
        let trans = s
            .get("trans")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .replace("\r\n", "\n");
        let src_translit = s
            .get("src_translit")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .replace("\r\n", "\n");
        raw_sentences.push(GtxSentence {
            orig,
            trans,
            src_translit,
        });
    }
    Some((src_lang, normalize_gtx_chunk_sentences(&raw_sentences)))
}

#[derive(Clone, Debug, Default)]
struct PairedLine {
    orig: String,
    trans: String,
    rom: String,
}

fn reconstruct_paired_lines(sentences: &[GtxSentence]) -> Vec<PairedLine> {
    let mut lines: Vec<PairedLine> = Vec::new();
    let mut cur = PairedLine::default();
    let mut has_inline_rom = false;
    let mut summary_rom = String::new();

    for s in sentences {
        let is_translit_only =
            s.orig.is_empty() && s.trans.is_empty() && !s.src_translit.is_empty();
        if is_translit_only {
            if !summary_rom.is_empty() && !summary_rom.ends_with('\n') {
                summary_rom.push('\n');
            }
            summary_rom.push_str(&s.src_translit);
            continue;
        }

        if !s.src_translit.trim().is_empty() {
            has_inline_rom = true;
        }

        let orig_parts: Vec<&str> = s.orig.split('\n').collect();
        let trans_parts: Vec<&str> = s.trans.split('\n').collect();
        let rom_parts: Vec<&str> = s.src_translit.split('\n').collect();
        let count = orig_parts.len().max(trans_parts.len()).max(rom_parts.len());

        for idx in 0..count {
            if idx > 0 {
                lines.push(std::mem::take(&mut cur));
            }
            if let Some(o) = orig_parts.get(idx) {
                cur.orig.push_str(o);
            }
            if let Some(t) = trans_parts.get(idx) {
                cur.trans.push_str(t);
            }
            if let Some(r) = rom_parts.get(idx) {
                cur.rom.push_str(r);
            }
        }
    }

    if !(cur.orig.is_empty() && cur.trans.is_empty() && cur.rom.is_empty()) {
        lines.push(cur);
    }

    if !summary_rom.is_empty() {
        let rom_lines: Vec<&str> = summary_rom.split('\n').collect();
        if lines.is_empty() {
            for r in rom_lines {
                lines.push(PairedLine {
                    orig: String::new(),
                    trans: String::new(),
                    rom: r.to_string(),
                });
            }
        } else if !has_inline_rom {
            for (idx, line) in lines.iter_mut().enumerate() {
                if let Some(r) = rom_lines.get(idx) {
                    line.rom = (*r).to_string();
                }
            }
        } else {
            for (idx, line) in lines.iter_mut().enumerate() {
                if line.rom.trim().is_empty() {
                    if let Some(r) = rom_lines.get(idx) {
                        line.rom = (*r).to_string();
                    }
                }
            }
        }
    }

    lines
}

fn normalize_gtx_chunk_sentences(raw_sentences: &[GtxSentence]) -> Vec<GtxSentence> {
    let paired = reconstruct_paired_lines(raw_sentences);
    let len = paired.len();
    paired
        .into_iter()
        .enumerate()
        .map(|(idx, p)| {
            let suffix = if idx + 1 < len { "\n" } else { "" };
            GtxSentence {
                orig: format!("{}{}", p.orig, suffix),
                trans: format!("{}{}", p.trans, suffix),
                src_translit: format!("{}{}", p.rom, suffix),
            }
        })
        .collect()
}

fn split_synced_chunks<'a>(lines: &'a [LyricLineDto], max_bytes: usize) -> Vec<&'a [LyricLineDto]> {
    let mut chunks = Vec::new();
    let mut start = 0usize;
    let mut cur_bytes = 0usize;

    for (idx, line) in lines.iter().enumerate() {
        let add = if idx == start {
            line.primary.len()
        } else {
            1 + line.primary.len()
        };
        if idx > start && cur_bytes + add > max_bytes {
            chunks.push(&lines[start..idx]);
            start = idx;
            cur_bytes = line.primary.len();
        } else {
            cur_bytes += add;
        }
    }
    if start < lines.len() {
        chunks.push(&lines[start..]);
    }
    chunks
}

/// Align reconstructed `gtx` paired lines back onto the original synced `LyricLineDto` timestamps
/// so EVERY translated/romanized line preserves its exact `time_ms`.
fn align_chunk_paired_lines(
    chunk: &[LyricLineDto],
    paired: &[PairedLine],
) -> Vec<(u64, String, PairedLine)> {
    let mut out = Vec::with_capacity(chunk.len());
    for (idx, orig_line) in chunk.iter().enumerate() {
        let p = if idx < paired.len() {
            if idx + 1 == chunk.len() && paired.len() > chunk.len() {
                // Merge any overflow lines onto the final line of this chunk so no lyrics are dropped.
                let merged_orig = paired[idx..]
                    .iter()
                    .map(|l| l.orig.trim())
                    .filter(|s| !s.is_empty())
                    .collect::<Vec<_>>()
                    .join(" ");
                let merged_trans = paired[idx..]
                    .iter()
                    .map(|l| l.trans.trim())
                    .filter(|s| !s.is_empty())
                    .collect::<Vec<_>>()
                    .join(" ");
                let merged_rom = paired[idx..]
                    .iter()
                    .map(|l| l.rom.trim())
                    .filter(|s| !s.is_empty())
                    .collect::<Vec<_>>()
                    .join(" ");
                PairedLine {
                    orig: merged_orig,
                    trans: merged_trans,
                    rom: merged_rom,
                }
            } else {
                paired[idx].clone()
            }
        } else {
            PairedLine {
                orig: orig_line.primary.clone(),
                trans: orig_line.primary.clone(),
                rom: String::new(),
            }
        };
        out.push((orig_line.time_ms, orig_line.primary.clone(), p));
    }
    out
}

fn build_synced_translated(
    aligned: &[(u64, String, PairedLine)],
    src_lang: &str,
) -> CachedTranslatedLyrics {
    let full_orig = aligned
        .iter()
        .map(|(_, o, _)| o.as_str())
        .collect::<Vec<_>>()
        .join("\n");
    let full_trans = aligned
        .iter()
        .map(|(_, _, p)| p.trans.trim())
        .collect::<Vec<_>>()
        .join("\n");
    let has_rom_text = aligned.iter().any(|(_, _, p)| !p.rom.trim().is_empty());
    let non_latin = has_non_latin_script(&full_orig);

    if !non_latin
        && (src_lang.eq_ignore_ascii_case("en")
            || src_lang.to_ascii_lowercase().starts_with("en-")
            || (src_lang.is_empty() && full_orig.trim().eq_ignore_ascii_case(full_trans.trim())))
    {
        let orig_lines: Vec<LyricLineDto> = aligned
            .iter()
            .map(|(time_ms, orig, _)| LyricLineDto {
                primary: orig.clone(),
                secondary: String::new(),
                is_spacer: false,
                time_ms: *time_ms,
            })
            .collect();
        return CachedTranslatedLyrics {
            is_already_en: true,
            has_romanization: false,
            is_synced: true,
            english_lines: orig_lines.clone(),
            romanized_lines: orig_lines,
        };
    }

    let use_rom = non_latin && has_rom_text;
    let mut english_lines = Vec::with_capacity(aligned.len());
    let mut romanized_lines = Vec::with_capacity(aligned.len());

    for (time_ms, orig, p) in aligned {
        let r = p.rom.trim();
        let t = p.trans.trim();
        let o = orig.trim();

        if use_rom {
            let en_primary = if !r.is_empty() {
                r.to_string()
            } else if !t.is_empty() {
                t.to_string()
            } else {
                o.to_string()
            };
            let en_secondary = if !t.is_empty() && !t.eq_ignore_ascii_case(&en_primary) {
                t.to_string()
            } else {
                String::new()
            };
            english_lines.push(LyricLineDto {
                primary: en_primary,
                secondary: en_secondary,
                is_spacer: false,
                time_ms: *time_ms,
            });
            romanized_lines.push(LyricLineDto {
                primary: if !r.is_empty() {
                    r.to_string()
                } else {
                    o.to_string()
                },
                secondary: String::new(),
                is_spacer: false,
                time_ms: *time_ms,
            });
        } else {
            english_lines.push(LyricLineDto {
                primary: if !t.is_empty() {
                    t.to_string()
                } else {
                    o.to_string()
                },
                secondary: String::new(),
                is_spacer: false,
                time_ms: *time_ms,
            });
            romanized_lines.push(LyricLineDto {
                primary: o.to_string(),
                secondary: String::new(),
                is_spacer: false,
                time_ms: *time_ms,
            });
        }
    }

    CachedTranslatedLyrics {
        is_already_en: false,
        has_romanization: use_rom,
        is_synced: true,
        english_lines,
        romanized_lines,
    }
}

fn push_plain_spacer(lines: &mut Vec<LyricLineDto>) {
    if !lines.last().map(|l| l.is_spacer).unwrap_or(false) {
        lines.push(LyricLineDto {
            primary: String::new(),
            secondary: String::new(),
            is_spacer: true,
            time_ms: 0,
        });
    }
}

fn trim_edge_spacers(mut raw_lines: Vec<LyricLineDto>) -> Vec<LyricLineDto> {
    let mut start = 0;
    while start < raw_lines.len() && raw_lines[start].is_spacer {
        start += 1;
    }
    let mut end = raw_lines.len();
    while end > start && raw_lines[end - 1].is_spacer {
        end -= 1;
    }
    raw_lines.drain(start..end).collect()
}

fn build_plain_translated(
    original_text: &str,
    src_lang: &str,
    sentences: &[GtxSentence],
) -> CachedTranslatedLyrics {
    let original_text = original_text.replace("\r\n", "\n");
    let paired_lines = reconstruct_paired_lines(sentences);
    let mut translated_text = String::new();
    let mut has_rom_text = false;
    for (idx, line) in paired_lines.iter().enumerate() {
        if idx > 0 {
            translated_text.push('\n');
        }
        translated_text.push_str(&line.trans);
        if !line.rom.trim().is_empty() {
            has_rom_text = true;
        }
    }

    let non_latin = has_non_latin_script(&original_text);
    if !non_latin
        && (src_lang.eq_ignore_ascii_case("en")
            || src_lang.to_ascii_lowercase().starts_with("en-")
            || (src_lang.is_empty()
                && original_text
                    .trim()
                    .eq_ignore_ascii_case(translated_text.trim())))
    {
        let plain = plain_text_to_lines(&original_text);
        return CachedTranslatedLyrics {
            is_already_en: true,
            has_romanization: false,
            is_synced: false,
            english_lines: plain.clone(),
            romanized_lines: plain,
        };
    }

    let use_rom = non_latin && has_rom_text;
    let mut en_lines = Vec::with_capacity(paired_lines.len());
    let mut rom_lines = Vec::with_capacity(paired_lines.len());

    for line in &paired_lines {
        let o = line.orig.trim();
        let r = line.rom.trim();
        let t = line.trans.trim();

        if o.is_empty() && r.is_empty() && t.is_empty() {
            push_plain_spacer(&mut en_lines);
            push_plain_spacer(&mut rom_lines);
        } else if use_rom {
            let primary = if !r.is_empty() {
                r.to_string()
            } else {
                t.to_string()
            };
            let secondary = if !t.is_empty() && !t.eq_ignore_ascii_case(&primary) {
                t.to_string()
            } else {
                String::new()
            };
            en_lines.push(LyricLineDto {
                primary,
                secondary,
                is_spacer: false,
                time_ms: 0,
            });
            rom_lines.push(LyricLineDto {
                primary: if !r.is_empty() {
                    r.to_string()
                } else {
                    o.to_string()
                },
                secondary: String::new(),
                is_spacer: false,
                time_ms: 0,
            });
        } else {
            if !t.is_empty() {
                en_lines.push(LyricLineDto {
                    primary: t.to_string(),
                    secondary: String::new(),
                    is_spacer: false,
                    time_ms: 0,
                });
            } else {
                push_plain_spacer(&mut en_lines);
            }
            if !o.is_empty() {
                rom_lines.push(LyricLineDto {
                    primary: o.to_string(),
                    secondary: String::new(),
                    is_spacer: false,
                    time_ms: 0,
                });
            } else {
                push_plain_spacer(&mut rom_lines);
            }
        }
    }

    CachedTranslatedLyrics {
        is_already_en: false,
        has_romanization: use_rom,
        is_synced: false,
        english_lines: trim_edge_spacers(en_lines),
        romanized_lines: trim_edge_spacers(rom_lines),
    }
}

fn split_stanza_chunks(text: &str, max_bytes: usize) -> Vec<String> {
    let mut chunks = Vec::new();
    let stanzas: Vec<&str> = text.split("\n\n").collect();
    let mut cur = String::new();
    for stanza in stanzas {
        if stanza.len() > max_bytes {
            if !cur.is_empty() {
                chunks.push(cur.clone());
                cur.clear();
            }
            for line in stanza.split('\n') {
                if !cur.is_empty() && cur.len() + 1 + line.len() > max_bytes {
                    chunks.push(cur.clone());
                    cur.clear();
                }
                if !cur.is_empty() {
                    cur.push('\n');
                }
                cur.push_str(line);
            }
            if !cur.is_empty() {
                chunks.push(cur.clone());
                cur.clear();
            }
        } else {
            if !cur.is_empty() && cur.len() + 2 + stanza.len() > max_bytes {
                chunks.push(cur.clone());
                cur.clear();
            }
            if !cur.is_empty() {
                cur.push_str("\n\n");
            }
            cur.push_str(stanza);
        }
    }
    if !cur.is_empty() {
        chunks.push(cur);
    }
    chunks
}

fn plain_text_to_lines(text: &str) -> Vec<LyricLineDto> {
    let mut lines = Vec::new();
    for line in text.replace("\r\n", "\n").lines() {
        let t = line.trim();
        if t.is_empty() {
            push_plain_spacer(&mut lines);
        } else {
            lines.push(LyricLineDto {
                primary: t.to_string(),
                secondary: String::new(),
                is_spacer: false,
                time_ms: 0,
            });
        }
    }
    trim_edge_spacers(lines)
}

async fn gtx_translate_chunk(chunk: &str) -> Result<(String, Vec<GtxSentence>), String> {
    let url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&dt=rm&dj=1";
    let resp = translate_client()
        .post(url)
        .form(&[("q", chunk)])
        .send()
        .await
        .map_err(|e| format!("Translation request failed: {e}"))?;
    if !resp.status().is_success() {
        return Err("Translation service returned error status".to_string());
    }
    let json_val: serde_json::Value = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse translation JSON: {e}"))?;
    parse_gtx_chunk_json(&json_val)
        .ok_or_else(|| "Failed to parse translation sentences".to_string())
}

async fn translate_synced_lines(lines: &[LyricLineDto]) -> Result<CachedTranslatedLyrics, String> {
    let non_spacers: Vec<LyricLineDto> = lines
        .iter()
        .filter(|l| !l.is_spacer && !l.primary.trim().is_empty())
        .cloned()
        .collect();
    if non_spacers.is_empty() {
        return Ok(CachedTranslatedLyrics {
            is_synced: true,
            ..Default::default()
        });
    }

    let chunks = split_synced_chunks(&non_spacers, SYNCED_GTX_CHUNK_BYTES);
    let mut aligned_all: Vec<(u64, String, PairedLine)> = Vec::with_capacity(non_spacers.len());
    let mut detected_lang = String::new();

    for chunk in chunks {
        let joined = chunk
            .iter()
            .map(|l| l.primary.as_str())
            .collect::<Vec<_>>()
            .join("\n");
        let (src_lang, chunk_sentences) = gtx_translate_chunk(&joined).await?;
        if detected_lang.is_empty()
            || (detected_lang == "en" && !src_lang.is_empty() && src_lang != "en")
        {
            detected_lang = src_lang;
        }
        let paired = reconstruct_paired_lines(&chunk_sentences);
        aligned_all.extend(align_chunk_paired_lines(chunk, &paired));
    }

    Ok(build_synced_translated(&aligned_all, &detected_lang))
}

async fn translate_plain_text(original: &str) -> Result<CachedTranslatedLyrics, String> {
    let normalized = original.replace("\r\n", "\n");
    if normalized.trim().is_empty() {
        return Ok(CachedTranslatedLyrics::default());
    }

    let chunks = split_stanza_chunks(&normalized, PLAIN_GTX_CHUNK_BYTES);
    let mut all_sentences: Vec<GtxSentence> = Vec::new();
    let mut detected_lang = String::new();

    for (chunk_idx, chunk) in chunks.iter().enumerate() {
        let (src_lang, chunk_sentences) = gtx_translate_chunk(chunk).await?;
        if detected_lang.is_empty()
            || (detected_lang == "en" && !src_lang.is_empty() && src_lang != "en")
        {
            detected_lang = src_lang;
        }
        if chunk_idx > 0 {
            all_sentences.push(GtxSentence {
                orig: "\n\n".to_string(),
                trans: "\n\n".to_string(),
                src_translit: "\n\n".to_string(),
            });
        }
        all_sentences.extend(chunk_sentences);
    }

    Ok(build_plain_translated(
        &normalized,
        &detected_lang,
        &all_sentences,
    ))
}

fn cache_raw_insert(video_id: &str, entry: CachedRawLyrics) {
    if let Ok(mut c) = raw_lyrics_cache().lock() {
        if c.len() >= MAX_CACHE_ENTRIES && !c.contains_key(video_id) {
            c.clear();
        }
        c.insert(video_id.to_string(), entry);
    }
}

fn cache_trans_insert(video_id: &str, entry: CachedTranslatedLyrics) {
    if let Ok(mut c) = trans_lyrics_cache().lock() {
        if c.len() >= MAX_CACHE_ENTRIES && !c.contains_key(video_id) {
            c.clear();
        }
        c.insert(video_id.to_string(), entry);
    }
}

// ============================================================================
// Public Entrypoints
// ============================================================================

pub async fn get_lyrics(video_id: &str, lang_mode: &str) -> LyricsResponse {
    get_lyrics_synced(video_id, "", "", "", 0, lang_mode).await
}

pub async fn get_lyrics_synced(
    video_id: &str,
    title: &str,
    artist: &str,
    album: &str,
    duration_secs: u32,
    lang_mode: &str,
) -> LyricsResponse {
    let has_meta = !title.trim().is_empty();

    let cached_raw = raw_lyrics_cache()
        .lock()
        .ok()
        .and_then(|c| c.get(video_id).cloned());

    // Reuse cache unless we previously only had unsynced/unavailable without metadata and now have metadata.
    let raw = match cached_raw {
        Some(r) if r.is_synced || r.lrclib_tried || !has_meta => r,
        existing => {
            let mut ytm_lyrics_id = None;
            let mut ytm_fatal_error: Option<String> = None;

            if let Ok(api) = crate::ytm::ytmusic().await {
                match api
                    .query(GetLyricsIDQuery::new(VideoID::from_raw(video_id)))
                    .await
                {
                    Ok(id) => {
                        if !id.get_raw().is_empty() {
                            ytm_lyrics_id = Some(id);
                        }
                    }
                    Err(e) => {
                        let msg = e.to_string();
                        if !matches!(e.into_kind(), ErrorKind::JsonParsing(_)) {
                            ytm_fatal_error = Some(format!("Lyrics ID query failed: {msg}"));
                        }
                    }
                }
            } else if !has_meta {
                return LyricsResponse {
                    ok: false,
                    available: false,
                    error: Some("YTM API client unavailable".to_string()),
                    ..Default::default()
                };
            }

            // 1. Try YTM Native Timed Lyrics first.
            let mut resolved: Option<CachedRawLyrics> = None;
            if let Some(ref lid) = ytm_lyrics_id {
                if let Some((timed_lines, src)) = fetch_ytm_timed_lyrics(lid.get_raw()).await {
                    let plain_text = timed_lines
                        .iter()
                        .map(|l| l.primary.as_str())
                        .collect::<Vec<_>>()
                        .join("\n");
                    resolved = Some(CachedRawLyrics {
                        available: true,
                        source: src,
                        is_synced: true,
                        lrclib_tried: false,
                        lines: timed_lines,
                        plain_text,
                    });
                }
            }

            // 2. Try LRCLIB Synced Fallback if YTM timed was unavailable and metadata was provided.
            let mut lrclib_plain: Option<String> = None;
            let mut lrclib_tried = false;
            if resolved.is_none() && has_meta {
                lrclib_tried = true;
                let lrclib = fetch_lrclib(title, artist, album, duration_secs).await;
                if let Some(synced_lines) = lrclib.synced_lines {
                    let plain_text = synced_lines
                        .iter()
                        .map(|l| l.primary.as_str())
                        .collect::<Vec<_>>()
                        .join("\n");
                    resolved = Some(CachedRawLyrics {
                        available: true,
                        source: "LRCLIB".to_string(),
                        is_synced: true,
                        lrclib_tried: true,
                        lines: synced_lines,
                        plain_text,
                    });
                } else {
                    lrclib_plain = lrclib.plain_text;
                }
            }

            // 3. Fallback to YTM Plain-Text Lyrics (or existing cached plain, or LRCLIB plainLyrics).
            if resolved.is_none() {
                if let Some(prev) = existing.filter(|e| e.available && !e.lines.is_empty()) {
                    resolved = Some(CachedRawLyrics {
                        lrclib_tried: prev.lrclib_tried || lrclib_tried,
                        ..prev
                    });
                } else if let Some(lid) = ytm_lyrics_id {
                    if let Ok(api) = crate::ytm::ytmusic().await {
                        match api.query(GetLyricsQuery::new(lid)).await {
                            Ok(lyrics) if !lyrics.lyrics.trim().is_empty() => {
                                let lines = plain_text_to_lines(&lyrics.lyrics);
                                resolved = Some(CachedRawLyrics {
                                    available: !lines.is_empty(),
                                    source: if lyrics.source.trim().is_empty() {
                                        "YouTube Music".to_string()
                                    } else {
                                        lyrics.source
                                    },
                                    is_synced: false,
                                    lrclib_tried,
                                    lines,
                                    plain_text: lyrics.lyrics,
                                });
                            }
                            Ok(_) => {}
                            Err(e) => {
                                let msg = e.to_string();
                                if !matches!(e.into_kind(), ErrorKind::JsonParsing(_)) {
                                    ytm_fatal_error = Some(format!("Lyrics fetch failed: {msg}"));
                                }
                            }
                        }
                    }
                }
            }

            if resolved.is_none() {
                if let Some(plain) = lrclib_plain.filter(|p| !p.trim().is_empty()) {
                    let lines = plain_text_to_lines(&plain);
                    resolved = Some(CachedRawLyrics {
                        available: !lines.is_empty(),
                        source: "LRCLIB".to_string(),
                        is_synced: false,
                        lrclib_tried: true,
                        lines,
                        plain_text: plain,
                    });
                }
            }

            let final_raw = match resolved {
                Some(r) => r,
                None => {
                    if let Some(err) = ytm_fatal_error {
                        return LyricsResponse {
                            ok: false,
                            available: false,
                            error: Some(err),
                            ..Default::default()
                        };
                    }
                    CachedRawLyrics {
                        available: false,
                        source: String::new(),
                        is_synced: false,
                        lrclib_tried,
                        lines: Vec::new(),
                        plain_text: String::new(),
                    }
                }
            };

            // Invalidate any stale translation cache if we upgraded from unsynced to synced.
            if final_raw.is_synced {
                if let Ok(mut tc) = trans_lyrics_cache().lock() {
                    if tc.get(video_id).is_some_and(|t| !t.is_synced) {
                        tc.remove(video_id);
                    }
                }
            }
            cache_raw_insert(video_id, final_raw.clone());
            final_raw
        }
    };

    if !raw.available {
        return LyricsResponse {
            ok: true,
            available: false,
            ..Default::default()
        };
    }

    let is_english_mode = lang_mode.eq_ignore_ascii_case("english")
        || lang_mode.eq_ignore_ascii_case("translated")
        || lang_mode.eq_ignore_ascii_case("en");
    let is_romanized_mode =
        lang_mode.eq_ignore_ascii_case("romanized") || lang_mode.eq_ignore_ascii_case("rom");

    if !is_english_mode && !is_romanized_mode {
        return LyricsResponse {
            ok: true,
            available: true,
            source: raw.source,
            is_synced: raw.is_synced,
            is_already_en: false,
            has_romanization: false,
            lines: raw.lines,
            error: None,
        };
    }

    let cached_trans = trans_lyrics_cache()
        .lock()
        .ok()
        .and_then(|c| c.get(video_id).cloned())
        .filter(|t| t.is_synced == raw.is_synced);

    let trans = match cached_trans {
        Some(t) => t,
        None => {
            let computed = if raw.is_synced {
                translate_synced_lines(&raw.lines).await
            } else {
                translate_plain_text(&raw.plain_text).await
            };
            match computed {
                Ok(t) => {
                    cache_trans_insert(video_id, t.clone());
                    t
                }
                Err(err) => {
                    return LyricsResponse {
                        ok: false,
                        available: true,
                        source: raw.source,
                        is_synced: raw.is_synced,
                        is_already_en: false,
                        has_romanization: false,
                        lines: raw.lines,
                        error: Some(err),
                    };
                }
            }
        }
    };

    let selected_lines = if is_romanized_mode {
        if trans.is_already_en {
            raw.lines
        } else {
            trans.romanized_lines
        }
    } else if trans.is_already_en {
        raw.lines
    } else {
        trans.english_lines
    };

    LyricsResponse {
        ok: true,
        available: true,
        source: raw.source,
        is_synced: raw.is_synced,
        is_already_en: trans.is_already_en,
        has_romanization: trans.has_romanization,
        lines: selected_lines,
        error: None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn parse_lrc_handles_multi_tags_centisecs_millisecs_metadata_and_offset() {
        let lrc = "\
[ar:Arijit Singh]
[ti:Kesariya]
[al:Brahmastra]
[by:Auricle]
[offset:+500]
[00:12.50][01:02.250]Kesariya tera ishq hai piya
[00:18.00]Rang jaaun jo main haath lagaaun
[00:25.00]
";
        let lines = parse_lrc(lrc);
        assert_eq!(lines.len(), 3);
        // 12500 - 500 = 12000
        assert_eq!(lines[0].time_ms, 12_000);
        assert_eq!(lines[0].primary, "Kesariya tera ishq hai piya");
        // 18000 - 500 = 17500
        assert_eq!(lines[1].time_ms, 17_500);
        assert_eq!(lines[1].primary, "Rang jaaun jo main haath lagaaun");
        // 62250 - 500 = 61750
        assert_eq!(lines[2].time_ms, 61_750);
        assert_eq!(lines[2].primary, "Kesariya tera ishq hai piya");
    }

    #[test]
    fn parse_lrc_clamps_negative_offset_result_at_zero() {
        let lrc = "[offset:+5000]\n[00:01.00]Opening line\n[00:10.00]Second line";
        let lines = parse_lrc(lrc);
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[0].time_ms, 0);
        assert_eq!(lines[1].time_ms, 5_000);
    }

    #[test]
    fn parse_ytm_timed_lyrics_extracts_nested_timed_array() {
        let payload = json!({
            "contents": {
                "elementRenderer": {
                    "timedLyricsModel": {
                        "lyricsData": {
                            "sourceMessage": "Source: Musixmatch",
                            "timedLyricsData": [
                                {
                                    "lyricLine": "First line",
                                    "cueRange": { "startTimeMilliseconds": "10400", "endTimeMilliseconds": "14000" }
                                },
                                {
                                    "lyricLine": "   ",
                                    "cueRange": { "startTimeMilliseconds": "14100" }
                                },
                                {
                                    "lyricLine": "Second line",
                                    "cueRange": { "startTimeMilliseconds": 15200 }
                                }
                            ]
                        }
                    }
                }
            }
        });
        let (lines, source) = parse_ytm_timed_lyrics(&payload).expect("valid timed lyrics");
        assert_eq!(source, "Source: Musixmatch");
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[0].time_ms, 10_400);
        assert_eq!(lines[0].primary, "First line");
        assert_eq!(lines[1].time_ms, 15_200);
        assert_eq!(lines[1].primary, "Second line");
    }

    #[test]
    fn strip_title_noise_cleans_movie_and_video_tags() {
        assert_eq!(
            strip_title_noise("Kesariya (From \"Brahmastra\") [Official Video]"),
            "Kesariya"
        );
        assert_eq!(
            strip_title_noise("Tum Hi Ho | Aashiqui 2 | Arijit Singh"),
            "Tum Hi Ho"
        );
        assert_eq!(
            strip_title_noise("Heeriye (feat. Arijit Singh)"),
            "Heeriye"
        );
    }

    #[test]
    fn lrclib_candidate_selection_enforces_duration_title_and_artist() {
        let candidates = vec![
            LrclibRecord {
                track_name: "Kesariya".into(),
                artist_name: "Arijit Singh".into(),
                album_name: Some("Brahmastra".into()),
                duration: Some(300.0), // too far from 268s (> 5s)
                synced_lyrics: Some("[00:05.00]Line 1\n[00:10.00]Line 2".into()),
                plain_lyrics: None,
            },
            LrclibRecord {
                track_name: "Kesariya".into(),
                artist_name: "Wrong Singer".into(),
                album_name: None,
                duration: Some(268.0),
                synced_lyrics: Some("[00:05.00]Line 1\n[00:10.00]Line 2".into()),
                plain_lyrics: None,
            },
            LrclibRecord {
                track_name: "Kesariya".into(),
                artist_name: "Pritam, Arijit Singh".into(),
                album_name: Some("Brahmastra".into()),
                duration: Some(269.5), // within 5s of 268s
                synced_lyrics: Some("[00:12.00]Mujhko itna bataye koyi\n[00:18.00]Kaise tujhse dil na lagaye koyi".into()),
                plain_lyrics: None,
            },
        ];

        let best = select_best_lrclib_candidate(&candidates, "Kesariya", "Arijit Singh", 268)
            .expect("should match third candidate");
        assert_eq!(best.artist_name, "Pritam, Arijit Singh");
    }

    #[test]
    fn synced_gtx_alignment_preserves_exact_timestamps_and_romanization() {
        let orig_synced = vec![
            LyricLineDto {
                primary: "केसरिया तेरा इश्क़ है पिया".into(),
                secondary: String::new(),
                is_spacer: false,
                time_ms: 12_000,
            },
            LyricLineDto {
                primary: "रंग जाऊं जो मैं हाथ लगाऊं".into(),
                secondary: String::new(),
                is_spacer: false,
                time_ms: 18_500,
            },
        ];
        let gtx_json = json!({
            "src": "hi",
            "sentences": [
                {
                    "trans": "Saffron is your love, beloved\n",
                    "orig": "केसरिया तेरा इश्क़ है पिया\n"
                },
                {
                    "trans": "I get colored when I touch it",
                    "orig": "रंग जाऊं जो मैं हाथ लगाऊं"
                },
                {
                    "src_translit": "kesariya tera ishq hai piya\nrang jaaun jo main haath lagaaun"
                }
            ]
        });

        let (src_lang, sentences) = parse_gtx_chunk_json(&gtx_json).unwrap();
        let paired = reconstruct_paired_lines(&sentences);
        let aligned = align_chunk_paired_lines(&orig_synced, &paired);
        let built = build_synced_translated(&aligned, &src_lang);

        assert!(built.is_synced);
        assert!(built.has_romanization);
        assert!(!built.is_already_en);

        assert_eq!(built.english_lines.len(), 2);
        assert_eq!(built.english_lines[0].time_ms, 12_000);
        assert_eq!(built.english_lines[0].primary, "kesariya tera ishq hai piya");
        assert_eq!(built.english_lines[0].secondary, "Saffron is your love, beloved");
        assert_eq!(built.english_lines[1].time_ms, 18_500);
        assert_eq!(built.english_lines[1].primary, "rang jaaun jo main haath lagaaun");
        assert_eq!(built.english_lines[1].secondary, "I get colored when I touch it");

        assert_eq!(built.romanized_lines.len(), 2);
        assert_eq!(built.romanized_lines[0].time_ms, 12_000);
        assert_eq!(built.romanized_lines[0].primary, "kesariya tera ishq hai piya");
        assert_eq!(built.romanized_lines[0].secondary, "");
        assert_eq!(built.romanized_lines[1].time_ms, 18_500);
        assert_eq!(built.romanized_lines[1].primary, "rang jaaun jo main haath lagaaun");
        assert_eq!(built.romanized_lines[1].secondary, "");
    }
}
