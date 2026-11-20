//! Lyrics fetching and Romanization / English translation module.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use serde::{Deserialize, Serialize};
use ytmapi_rs::common::{VideoID, YoutubeID};
use ytmapi_rs::query::{GetLyricsIDQuery, GetLyricsQuery};

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct LyricLineDto {
    pub primary: String,
    pub secondary: String,
    pub is_spacer: bool,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct LyricsResponse {
    pub ok: bool,
    pub available: bool,
    pub source: String,
    pub is_already_en: bool,
    pub lines: Vec<LyricLineDto>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Clone, Debug, Default)]
struct CachedRawLyrics {
    available: bool,
    text: String,
    source: String,
}

#[derive(Clone, Debug, Default)]
struct CachedEnglishLyrics {
    is_already_en: bool,
    lines: Vec<LyricLineDto>,
}

static RAW_LYRICS_CACHE: OnceLock<Mutex<HashMap<String, CachedRawLyrics>>> = OnceLock::new();
static EN_LYRICS_CACHE: OnceLock<Mutex<HashMap<String, CachedEnglishLyrics>>> = OnceLock::new();

fn raw_lyrics_cache() -> &'static Mutex<HashMap<String, CachedRawLyrics>> {
    RAW_LYRICS_CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn en_lyrics_cache() -> &'static Mutex<HashMap<String, CachedEnglishLyrics>> {
    EN_LYRICS_CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn has_non_latin_script(text: &str) -> bool {
    text.chars()
        .any(|c| !c.is_ascii() && c.is_alphabetic() && (c as u32) > 0x024F)
}

pub fn parse_gtx_chunk_json(val: &serde_json::Value) -> Option<(String, String, String)> {
    let src_lang = val.get("src").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let sentences = val.get("sentences")?.as_array()?;
    let mut translated_chunk = String::new();
    let mut romanized_chunk = String::new();
    for s in sentences {
        if let Some(trans) = s.get("trans").and_then(|v| v.as_str()) {
            translated_chunk.push_str(trans);
        }
        if let Some(translit) = s.get("src_translit").and_then(|v| v.as_str()) {
            romanized_chunk.push_str(translit);
        }
    }
    Some((src_lang, translated_chunk, romanized_chunk))
}

pub fn build_english_lyrics(
    original_text: &str,
    src_lang: &str,
    translated_text: &str,
    romanized_text: &str,
) -> CachedEnglishLyrics {
    let original_text = original_text.replace("\r\n", "\n");
    let translated_text = translated_text.replace("\r\n", "\n");
    let romanized_text = romanized_text.replace("\r\n", "\n");

    let non_latin = has_non_latin_script(&original_text);
    if !non_latin
        && (src_lang.eq_ignore_ascii_case("en")
            || src_lang.to_ascii_lowercase().starts_with("en-")
            || (src_lang.eq_ignore_ascii_case("en")
                && original_text.trim().eq_ignore_ascii_case(translated_text.trim()))
            || (src_lang.is_empty()
                && original_text.trim().eq_ignore_ascii_case(translated_text.trim())))
    {
        return CachedEnglishLyrics {
            is_already_en: true,
            lines: Vec::new(),
        };
    }

    let mut raw_lines = Vec::new();
    if non_latin && !romanized_text.trim().is_empty() {
        let orig_lines: Vec<&str> = original_text.split('\n').collect();
        let rom_lines: Vec<&str> = romanized_text.split('\n').collect();
        let trans_lines: Vec<&str> = translated_text.split('\n').collect();
        let count = orig_lines.len().max(rom_lines.len()).max(trans_lines.len());

        for idx in 0..count {
            let o = orig_lines.get(idx).map(|s| s.trim()).unwrap_or("");
            let r = rom_lines.get(idx).map(|s| s.trim()).unwrap_or("");
            let t = trans_lines.get(idx).map(|s| s.trim()).unwrap_or("");

            if o.is_empty() && r.is_empty() && t.is_empty() {
                if !raw_lines
                    .last()
                    .map(|l: &LyricLineDto| l.is_spacer)
                    .unwrap_or(false)
                {
                    raw_lines.push(LyricLineDto {
                        primary: String::new(),
                        secondary: String::new(),
                        is_spacer: true,
                    });
                }
            } else {
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
                raw_lines.push(LyricLineDto {
                    primary,
                    secondary,
                    is_spacer: false,
                });
            }
        }
    } else {
        for line in translated_text.split('\n') {
            let t = line.trim();
            if t.is_empty() {
                if !raw_lines
                    .last()
                    .map(|l: &LyricLineDto| l.is_spacer)
                    .unwrap_or(false)
                {
                    raw_lines.push(LyricLineDto {
                        primary: String::new(),
                        secondary: String::new(),
                        is_spacer: true,
                    });
                }
            } else {
                raw_lines.push(LyricLineDto {
                    primary: t.to_string(),
                    secondary: String::new(),
                    is_spacer: false,
                });
            }
        }
    }

    let mut start = 0;
    while start < raw_lines.len() && raw_lines[start].is_spacer {
        start += 1;
    }
    let mut end = raw_lines.len();
    while end > start && raw_lines[end - 1].is_spacer {
        end -= 1;
    }

    let lines = raw_lines[start..end].to_vec();
    CachedEnglishLyrics {
        is_already_en: false,
        lines,
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
            let lines: Vec<&str> = stanza.split('\n').collect();
            for line in lines {
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

pub async fn translate_lyrics_to_english(original: &str) -> Result<CachedEnglishLyrics, String> {
    let normalized = original.replace("\r\n", "\n");
    if normalized.trim().is_empty() {
        return Ok(CachedEnglishLyrics {
            is_already_en: false,
            lines: Vec::new(),
        });
    }

    let chunks = split_stanza_chunks(&normalized, 2800);
    let client = reqwest::Client::new();
    let url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&dt=rm&dj=1";

    let mut all_trans_chunks = Vec::new();
    let mut all_rom_chunks = Vec::new();
    let mut detected_lang = String::new();

    for chunk in chunks {
        let resp = client
            .post(url)
            .form(&[("q", chunk.as_str())])
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

        let (src_lang, trans_chunk, rom_chunk) = parse_gtx_chunk_json(&json_val)
            .ok_or_else(|| "Failed to parse translation sentences".to_string())?;

        if detected_lang.is_empty()
            || (detected_lang == "en" && !src_lang.is_empty() && src_lang != "en")
        {
            detected_lang = src_lang;
        }

        all_trans_chunks.push(trans_chunk);
        all_rom_chunks.push(rom_chunk);
    }

    let translated_text = all_trans_chunks.join("\n\n");
    let romanized_text = all_rom_chunks.join("\n\n");

    Ok(build_english_lyrics(
        &normalized,
        &detected_lang,
        &translated_text,
        &romanized_text,
    ))
}

pub async fn get_lyrics(video_id: &str, lang_mode: &str) -> LyricsResponse {
    let cached_raw = {
        let guard = raw_lyrics_cache().lock().ok();
        guard.and_then(|c| c.get(video_id).cloned())
    };

    let raw = match cached_raw {
        Some(r) => r,
        None => {
            let api = match crate::ytm::ytmusic().await {
                Ok(a) => a,
                Err(e) => {
                    return LyricsResponse {
                        ok: false,
                        available: false,
                        source: String::new(),
                        is_already_en: false,
                        lines: Vec::new(),
                        error: Some(format!("YTM API error: {e}")),
                    };
                }
            };

            let lyrics_id = match api
                .query(GetLyricsIDQuery::new(VideoID::from_raw(video_id)))
                .await
            {
                Ok(id) => id,
                Err(_) => {
                    let r = CachedRawLyrics {
                        available: false,
                        text: String::new(),
                        source: String::new(),
                    };
                    if let Ok(mut c) = raw_lyrics_cache().lock() {
                        c.insert(video_id.to_string(), r.clone());
                    }
                    return LyricsResponse {
                        ok: true,
                        available: false,
                        source: String::new(),
                        is_already_en: false,
                        lines: Vec::new(),
                        error: None,
                    };
                }
            };

            if lyrics_id.get_raw().is_empty() {
                let r = CachedRawLyrics {
                    available: false,
                    text: String::new(),
                    source: String::new(),
                };
                if let Ok(mut c) = raw_lyrics_cache().lock() {
                    c.insert(video_id.to_string(), r.clone());
                }
                return LyricsResponse {
                    ok: true,
                    available: false,
                    source: String::new(),
                    is_already_en: false,
                    lines: Vec::new(),
                    error: None,
                };
            }

            match api.query(GetLyricsQuery::new(lyrics_id)).await {
                Ok(lyrics) => {
                    let text = lyrics.lyrics.trim().to_string();
                    let r = if !text.is_empty() {
                        CachedRawLyrics {
                            available: true,
                            text: lyrics.lyrics,
                            source: lyrics.source,
                        }
                    } else {
                        CachedRawLyrics {
                            available: false,
                            text: String::new(),
                            source: String::new(),
                        }
                    };
                    if let Ok(mut c) = raw_lyrics_cache().lock() {
                        c.insert(video_id.to_string(), r.clone());
                    }
                    r
                }
                Err(_) => {
                    let r = CachedRawLyrics {
                        available: false,
                        text: String::new(),
                        source: String::new(),
                    };
                    if let Ok(mut c) = raw_lyrics_cache().lock() {
                        c.insert(video_id.to_string(), r.clone());
                    }
                    return LyricsResponse {
                        ok: true,
                        available: false,
                        source: String::new(),
                        is_already_en: false,
                        lines: Vec::new(),
                        error: None,
                    };
                }
            }
        }
    };

    if !raw.available {
        return LyricsResponse {
            ok: true,
            available: false,
            source: String::new(),
            is_already_en: false,
            lines: Vec::new(),
            error: None,
        };
    }

    let is_english_mode = lang_mode.eq_ignore_ascii_case("english")
        || lang_mode.eq_ignore_ascii_case("translated")
        || lang_mode.eq_ignore_ascii_case("en");

    if !is_english_mode {
        // Return original lyrics lines
        let mut lines = Vec::new();
        for line in raw.text.lines() {
            let t = line.trim();
            if t.is_empty() {
                if !lines.last().map(|l: &LyricLineDto| l.is_spacer).unwrap_or(false) {
                    lines.push(LyricLineDto {
                        primary: String::new(),
                        secondary: String::new(),
                        is_spacer: true,
                    });
                }
            } else {
                lines.push(LyricLineDto {
                    primary: t.to_string(),
                    secondary: String::new(),
                    is_spacer: false,
                });
            }
        }
        return LyricsResponse {
            ok: true,
            available: true,
            source: raw.source,
            is_already_en: false,
            lines,
            error: None,
        };
    }

    // English / Romanized translation mode
    let cached_en = {
        let guard = en_lyrics_cache().lock().ok();
        guard.and_then(|c| c.get(video_id).cloned())
    };

    let en = match cached_en {
        Some(e) => e,
        None => match translate_lyrics_to_english(&raw.text).await {
            Ok(e) => {
                if let Ok(mut c) = en_lyrics_cache().lock() {
                    c.insert(video_id.to_string(), e.clone());
                }
                e
            }
            Err(err) => {
                return LyricsResponse {
                    ok: false,
                    available: true,
                    source: raw.source,
                    is_already_en: false,
                    lines: Vec::new(),
                    error: Some(err),
                };
            }
        },
    };

    if en.is_already_en {
        let mut lines = Vec::new();
        for line in raw.text.lines() {
            let t = line.trim();
            if t.is_empty() {
                if !lines.last().map(|l: &LyricLineDto| l.is_spacer).unwrap_or(false) {
                    lines.push(LyricLineDto {
                        primary: String::new(),
                        secondary: String::new(),
                        is_spacer: true,
                    });
                }
            } else {
                lines.push(LyricLineDto {
                    primary: t.to_string(),
                    secondary: String::new(),
                    is_spacer: false,
                });
            }
        }
        LyricsResponse {
            ok: true,
            available: true,
            source: raw.source,
            is_already_en: true,
            lines,
            error: None,
        }
    } else {
        LyricsResponse {
            ok: true,
            available: true,
            source: raw.source,
            is_already_en: false,
            lines: en.lines,
            error: None,
        }
    }
}
