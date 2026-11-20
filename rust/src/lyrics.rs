//! Lyrics fetching and Romanization / English translation module.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use ytmapi_rs::common::{VideoID, YoutubeID};
use ytmapi_rs::error::ErrorKind;
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
pub struct GtxSentence {
    pub orig: String,
    pub trans: String,
    pub src_translit: String,
}

#[derive(Clone, Debug, Default)]
struct CachedRawLyrics {
    available: bool,
    text: String,
    source: String,
}

#[derive(Clone, Debug, Default)]
pub struct CachedEnglishLyrics {
    pub is_already_en: bool,
    pub lines: Vec<LyricLineDto>,
}

static RAW_LYRICS_CACHE: OnceLock<Mutex<HashMap<String, CachedRawLyrics>>> = OnceLock::new();
static EN_LYRICS_CACHE: OnceLock<Mutex<HashMap<String, CachedEnglishLyrics>>> = OnceLock::new();
static TRANSLATE_CLIENT: OnceLock<reqwest::Client> = OnceLock::new();

fn raw_lyrics_cache() -> &'static Mutex<HashMap<String, CachedRawLyrics>> {
    RAW_LYRICS_CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn en_lyrics_cache() -> &'static Mutex<HashMap<String, CachedEnglishLyrics>> {
    EN_LYRICS_CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn translate_client() -> &'static reqwest::Client {
    TRANSLATE_CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(20))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new())
    })
}

pub fn has_non_latin_script(text: &str) -> bool {
    text.chars()
        .any(|c| !c.is_ascii() && c.is_alphabetic() && (c as u32) > 0x024F)
}

pub fn parse_gtx_chunk_json(val: &serde_json::Value) -> Option<(String, Vec<GtxSentence>)> {
    let src_lang = val.get("src").and_then(|v| v.as_str()).unwrap_or("").to_string();
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

#[derive(Default)]
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

fn push_paired_line(raw_lines: &mut Vec<LyricLineDto>, line: &PairedLine, use_rom: bool) {
    let o = line.orig.trim();
    let r = line.rom.trim();
    let t = line.trans.trim();

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
        raw_lines.push(LyricLineDto {
            primary,
            secondary,
            is_spacer: false,
        });
    } else if !t.is_empty() {
        raw_lines.push(LyricLineDto {
            primary: t.to_string(),
            secondary: String::new(),
            is_spacer: false,
        });
    } else if !raw_lines
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
}

pub fn build_english_lyrics(
    original_text: &str,
    src_lang: &str,
    sentences: &[GtxSentence],
) -> CachedEnglishLyrics {
    let original_text = original_text.replace("\r\n", "\n");
    let paired_lines = reconstruct_paired_lines(sentences);
    let mut translated_text = String::new();
    let mut has_romanization = false;
    for (idx, line) in paired_lines.iter().enumerate() {
        if idx > 0 {
            translated_text.push('\n');
        }
        translated_text.push_str(&line.trans);
        if !line.rom.trim().is_empty() {
            has_romanization = true;
        }
    }

    let non_latin = has_non_latin_script(&original_text);
    if !non_latin
        && (src_lang.eq_ignore_ascii_case("en")
            || src_lang.to_ascii_lowercase().starts_with("en-")
            || (src_lang.is_empty()
                && original_text.trim().eq_ignore_ascii_case(translated_text.trim())))
    {
        return CachedEnglishLyrics {
            is_already_en: true,
            lines: Vec::new(),
        };
    }

    let use_rom = non_latin && has_romanization;
    let mut raw_lines = Vec::with_capacity(paired_lines.len());
    for line in &paired_lines {
        push_paired_line(&mut raw_lines, line, use_rom);
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
    let client = translate_client();
    let url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&dt=rm&dj=1";

    let mut all_sentences: Vec<GtxSentence> = Vec::new();
    let mut detected_lang = String::new();

    for (chunk_idx, chunk) in chunks.iter().enumerate() {
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

        let (src_lang, chunk_sentences) = parse_gtx_chunk_json(&json_val)
            .ok_or_else(|| "Failed to parse translation sentences".to_string())?;

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

    Ok(build_english_lyrics(
        &normalized,
        &detected_lang,
        &all_sentences,
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
                Err(e) => {
                    let err_msg = e.to_string();
                    if matches!(e.into_kind(), ErrorKind::JsonParsing(_)) {
                        let r = CachedRawLyrics {
                            available: false,
                            text: String::new(),
                            source: String::new(),
                        };
                        if let Ok(mut c) = raw_lyrics_cache().lock() {
                            c.insert(video_id.to_string(), r);
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
                    return LyricsResponse {
                        ok: false,
                        available: false,
                        source: String::new(),
                        is_already_en: false,
                        lines: Vec::new(),
                        error: Some(format!("Lyrics ID query failed: {err_msg}")),
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
                Err(e) => {
                    let err_msg = e.to_string();
                    if matches!(e.into_kind(), ErrorKind::JsonParsing(_)) {
                        let r = CachedRawLyrics {
                            available: false,
                            text: String::new(),
                            source: String::new(),
                        };
                        if let Ok(mut c) = raw_lyrics_cache().lock() {
                            c.insert(video_id.to_string(), r);
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
                    return LyricsResponse {
                        ok: false,
                        available: false,
                        source: String::new(),
                        is_already_en: false,
                        lines: Vec::new(),
                        error: Some(format!("Lyrics fetch failed: {err_msg}")),
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
