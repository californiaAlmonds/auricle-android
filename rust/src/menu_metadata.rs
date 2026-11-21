//! Multi-artist credit resolution and session-scoped LRU metadata cache.
//!
//! Ported from Windows Auricle (`core/menu_metadata.rs`) to keep artist credit
//! extraction, comma-preserving band names ("Earth, Wind & Fire"), and exact
//! browse-ID resolution consistent across platforms.

use std::collections::VecDeque;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use ytmapi_rs::auth::noauth::NoAuthToken;
use ytmapi_rs::common::{AlbumID, VideoID, YoutubeID};
use ytmapi_rs::parse::{ParsedSongArtist, SearchResultArtist};
use ytmapi_rs::query::search::ArtistsFilter;
use ytmapi_rs::query::{GetAlbumQuery, GetWatchPlaylistQuery, SearchQuery};
use ytmapi_rs::YtMusic;

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ArtistCredit {
    pub name: String,
    pub browse_id: String,
}

const CACHE_LIMIT: usize = 128;
static ARTISTS: OnceLock<Mutex<ArtistCache>> = OnceLock::new();

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Kind {
    Song,
    Album,
    Artist,
}

impl Kind {
    fn parse(kind: &str) -> Option<Self> {
        match kind.trim().to_ascii_lowercase().as_str() {
            "song" => Some(Self::Song),
            "album" => Some(Self::Album),
            "artist" => Some(Self::Artist),
            _ => None,
        }
    }
}

#[derive(Default)]
struct ArtistCache {
    entries: VecDeque<(Kind, String, Vec<ArtistCredit>)>,
}

impl ArtistCache {
    fn get(&mut self, kind: Kind, id: &str) -> Option<Vec<ArtistCredit>> {
        let position = self
            .entries
            .iter()
            .position(|entry| entry.0 == kind && entry.1 == id)?;
        let entry = self.entries.remove(position)?;
        let credits = entry.2.clone();
        self.entries.push_back(entry);
        Some(credits)
    }

    fn insert(&mut self, kind: Kind, id: &str, credits: Vec<ArtistCredit>) {
        let mut merged = self.get(kind, id).unwrap_or_default();
        merge_credits(&mut merged, credits);
        if merged.is_empty() {
            return;
        }
        if let Some(entry) = self
            .entries
            .back_mut()
            .filter(|entry| entry.0 == kind && entry.1 == id)
        {
            entry.2 = merged;
            return;
        }
        self.entries.push_back((kind, id.to_string(), merged));
        while self.entries.len() > CACHE_LIMIT {
            self.entries.pop_front();
        }
    }
}

fn artist_cache() -> &'static Mutex<ArtistCache> {
    ARTISTS.get_or_init(|| Mutex::new(ArtistCache::default()))
}

pub(crate) fn normalized_name(name: &str) -> String {
    name.split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

pub(crate) fn merge_credits(existing: &mut Vec<ArtistCredit>, credits: Vec<ArtistCredit>) {
    for credit in credits {
        let credit = ArtistCredit {
            name: credit.name.trim().to_string(),
            browse_id: credit.browse_id.trim().to_string(),
        };
        if credit.name.is_empty() && credit.browse_id.is_empty() {
            continue;
        }
        let matching = existing.iter_mut().find(|known| {
            if !credit.browse_id.is_empty() {
                known.browse_id == credit.browse_id
            } else {
                normalized_name(&known.name) == normalized_name(&credit.name)
            }
        });
        if let Some(known) = matching {
            if known.name.is_empty() && !credit.name.is_empty() {
                known.name = credit.name;
            }
        } else {
            existing.push(credit);
        }
    }
    let named_ids: Vec<String> = existing
        .iter()
        .filter(|credit| !credit.browse_id.is_empty() && !credit.name.is_empty())
        .map(|credit| normalized_name(&credit.name))
        .collect();
    existing.retain(|credit| {
        !credit.browse_id.is_empty() || !named_ids.contains(&normalized_name(&credit.name))
    });
}

pub fn parsed_credits(artists: &[ParsedSongArtist]) -> Vec<ArtistCredit> {
    let mut credits = Vec::new();
    merge_credits(
        &mut credits,
        artists
            .iter()
            .map(|artist| ArtistCredit {
                name: artist.name.clone(),
                browse_id: artist
                    .id
                    .as_ref()
                    .map(|id| id.get_raw().to_string())
                    .unwrap_or_default(),
            })
            .collect(),
    );
    credits
}

/// Merge structured API credits into the session-only, 128-entry LRU.
/// Kinds are Song, Album, or Artist (case-insensitive); IDs are case-sensitive.
pub fn register_artists(kind: &str, id: &str, credits: Vec<ArtistCredit>) {
    let Some(kind) = Kind::parse(kind) else {
        return;
    };
    let id = id.trim();
    if id.is_empty() || id == "native-prototype" {
        return;
    }
    if let Ok(mut cache) = artist_cache().lock() {
        cache.insert(kind, id, credits);
    }
}

/// Read and touch the LRU without network access. A blank browse_id is not navigable.
pub fn cached_artists(kind: &str, id: &str) -> Option<Vec<ArtistCredit>> {
    artist_cache()
        .lock()
        .ok()?
        .get(Kind::parse(kind)?, id.trim())
}

#[derive(Default, Debug)]
pub(crate) struct WatchMetadata {
    pub title: String,
    pub artists: Vec<ArtistCredit>,
    pub albums: Vec<(String, String)>,
}

fn exact_watch_rows<'response>(
    value: &'response Value,
    video_id: &str,
    rows: &mut Vec<&'response Value>,
) {
    match value {
        Value::Object(object) => {
            if let Some(row) = object.get("playlistPanelVideoRenderer") {
                if row.get("videoId").and_then(Value::as_str) == Some(video_id) {
                    rows.push(row);
                }
            }
            for child in object.values() {
                exact_watch_rows(child, video_id, rows);
            }
        }
        Value::Array(array) => {
            for child in array {
                exact_watch_rows(child, video_id, rows);
            }
        }
        _ => {}
    }
}

fn text_runs(value: &Value) -> String {
    if let Some(text) = value.get("simpleText").and_then(Value::as_str) {
        return text.to_string();
    }
    value
        .get("runs")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|run| run.get("text").and_then(Value::as_str))
        .collect()
}

pub(crate) fn parse_watch_metadata(
    response: &Value,
    video_id: &str,
) -> Result<WatchMetadata, String> {
    if video_id.is_empty() {
        return Err("Missing song video ID".into());
    }
    let mut rows = Vec::new();
    exact_watch_rows(response, video_id, &mut rows);
    if rows.is_empty() {
        return Err(format!(
            "Watch metadata did not contain requested video {video_id}"
        ));
    }
    let mut metadata = WatchMetadata::default();
    for row in rows {
        if metadata.title.is_empty() {
            metadata.title = text_runs(&row["title"]);
        }
        for field in ["longBylineText", "shortBylineText"] {
            let Some(runs) = row[field].get("runs").and_then(Value::as_array) else {
                continue;
            };
            let mut in_artist_section = true;
            for run in runs {
                let name = run.get("text").and_then(Value::as_str).unwrap_or("").trim();
                let endpoint = run.pointer("/navigationEndpoint/browseEndpoint");
                let browse_id = endpoint
                    .and_then(|endpoint| endpoint.get("browseId"))
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .trim();
                let page_type = endpoint
                    .and_then(|endpoint| {
                        endpoint.pointer(
                            "/browseEndpointContextSupportedConfigs/browseEndpointContextMusicConfig/pageType",
                        )
                    })
                    .and_then(Value::as_str)
                    .unwrap_or("");
                if page_type == "MUSIC_PAGE_TYPE_ALBUM"
                    || (page_type.is_empty() && browse_id.starts_with("MPRE"))
                {
                    metadata
                        .albums
                        .push((browse_id.to_string(), name.to_string()));
                    in_artist_section = false;
                } else if page_type == "MUSIC_PAGE_TYPE_ARTIST"
                    || (page_type.is_empty() && browse_id.starts_with("UC"))
                {
                    merge_credits(
                        &mut metadata.artists,
                        vec![ArtistCredit {
                            name: name.to_string(),
                            browse_id: browse_id.to_string(),
                        }],
                    );
                } else if matches!(name, "\u{2022}" | "\u{b7}") {
                    in_artist_section = false;
                } else if field == "longBylineText"
                    && in_artist_section
                    && endpoint.is_none()
                    && !matches!(name, "" | "," | "&" | ";" | "/")
                {
                    merge_credits(
                        &mut metadata.artists,
                        vec![ArtistCredit {
                            name: name.to_string(),
                            browse_id: String::new(),
                        }],
                    );
                }
            }
        }
    }
    Ok(metadata)
}

async fn watch_metadata(
    api: &YtMusic<NoAuthToken>,
    video_id: &str,
) -> Result<WatchMetadata, String> {
    let response = api
        .json_query(GetWatchPlaylistQuery::new_from_video_id(VideoID::from_raw(
            video_id,
        )))
        .await
        .map_err(|error| format!("Watch metadata: {error}"))?;
    let response = serde_json::to_value(response).map_err(|error| error.to_string())?;
    parse_watch_metadata(&response, video_id)
}

/// Split plain fallback artist strings on `, `, ` & `, ` feat. `, ` ft. `, ` featuring `, ` x `
/// (case-insensitive for keyword delimiters) into unlinked `ArtistCredit` entries.
pub fn split_fallback_artists(artist_text: &str) -> Vec<ArtistCredit> {
    let trimmed = artist_text.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }

    const DELIMS: &[&str] = &[
        ", ",
        " & ",
        " featuring ",
        " feat. ",
        " ft. ",
        " x ",
    ];

    let mut parts: Vec<String> = Vec::new();
    let mut start = 0usize;
    let mut i = 0usize;
    let char_indices: Vec<(usize, char)> = trimmed.char_indices().collect();
    let total_len = trimmed.len();

    while i < char_indices.len() {
        let byte_pos = char_indices[i].0;
        let rest = &trimmed[byte_pos..];
        let matched_len = DELIMS.iter().find_map(|delim| {
            let dlen = delim.len();
            if rest.len() >= dlen
                && rest.is_char_boundary(dlen)
                && rest[..dlen].eq_ignore_ascii_case(delim)
            {
                Some(dlen)
            } else {
                None
            }
        });
        if let Some(dlen) = matched_len {
            let seg = trimmed[start..byte_pos].trim();
            if !seg.is_empty() {
                parts.push(seg.to_string());
            }
            let next_byte = byte_pos + dlen;
            start = next_byte;
            while i < char_indices.len() && char_indices[i].0 < next_byte {
                i += 1;
            }
            continue;
        }
        i += 1;
    }

    if start < total_len {
        let tail = trimmed[start..].trim();
        if !tail.is_empty() {
            parts.push(tail.to_string());
        }
    }

    let mut credits = Vec::new();
    merge_credits(
        &mut credits,
        parts
            .into_iter()
            .map(|name| ArtistCredit {
                name,
                browse_id: String::new(),
            })
            .collect(),
    );
    credits
}

fn match_artist_browse_id(artists: &[SearchResultArtist], name: &str) -> Option<String> {
    let target = normalized_name(name);
    if target.is_empty() {
        return None;
    }

    let mut exact: Vec<(&str, bool)> = Vec::new();
    for artist in artists {
        let id = artist.browse_id.get_raw().trim();
        if id.is_empty() {
            continue;
        }
        if normalized_name(&artist.artist) == target {
            if !exact.iter().any(|(known, _)| *known == id) {
                let has_subs = artist
                    .subscribers
                    .as_deref()
                    .is_some_and(|s| !s.trim().is_empty());
                exact.push((id, has_subs));
            }
        }
    }

    if exact.len() == 1 {
        return Some(exact[0].0.to_string());
    }
    if exact.len() > 1 {
        let with_subs: Vec<&str> = exact
            .iter()
            .filter_map(|(id, has_subs)| has_subs.then_some(*id))
            .collect();
        if with_subs.len() == 1 {
            return Some(with_subs[0].to_string());
        }
    }
    None
}

/// Search `ArtistsFilter` for an exact normalized-name match and return its browse ID.
pub async fn resolve_artist_browse_id(name: &str) -> Option<String> {
    let trimmed = name.trim();
    if trimmed.is_empty() {
        return None;
    }
    let api = crate::ytm::ytmusic().await.ok()?;
    let results = tokio::time::timeout(
        Duration::from_secs(6),
        api.query(SearchQuery::new(trimmed).with_filter(ArtistsFilter)),
    )
    .await
    .ok()?
    .ok()?;
    match_artist_browse_id(&results, trimmed)
}

async fn enrich_unlinked_credits(credits: &mut [ArtistCredit]) {
    let unlinked_indices: Vec<usize> = credits
        .iter()
        .enumerate()
        .filter(|(_, c)| c.browse_id.is_empty() && !c.name.trim().is_empty())
        .map(|(idx, _)| idx)
        .collect();

    if unlinked_indices.is_empty() {
        return;
    }

    // Resolve single-artist credit directly, or up to 3 unlinked credits when none are linked yet.
    let should_enrich =
        credits.len() == 1 || credits.iter().all(|c| c.browse_id.is_empty());
    if !should_enrich {
        return;
    }

    let targets: Vec<(usize, String)> = unlinked_indices
        .into_iter()
        .take(3)
        .map(|idx| (idx, credits[idx].name.clone()))
        .collect();

    for (idx, name) in targets {
        if let Some(bid) = resolve_artist_browse_id(&name).await {
            credits[idx].browse_id = bid;
        }
    }
}

/// Resolve structured artist credits for a song, album, or artist.
///
/// 1. Checks the 128-entry LRU cache first; returns immediately if linked credits exist.
/// 2. Fetches from YouTube Music (`GetWatchPlaylistQuery` for `"song"`, `GetAlbumQuery` for `"album"`).
/// 3. Merges & caches credits.
/// 4. Falls back to splitting `fallback_artist_text` when structured credits are unavailable.
/// 5. Enriches unlinked single/fallback credits via `resolve_artist_browse_id`.
pub async fn resolve_artists(
    kind: &str,
    id: &str,
    fallback_artist_text: &str,
) -> Result<Vec<ArtistCredit>, String> {
    let parsed_kind =
        Kind::parse(kind).ok_or_else(|| format!("Unsupported metadata kind: {kind}"))?;
    let id = id.trim();

    if parsed_kind == Kind::Artist {
        if !id.is_empty() && id != "native-prototype" {
            let known = cached_artists(kind, id).unwrap_or_default();
            let name = known
                .iter()
                .find(|credit| credit.browse_id == id && !credit.name.is_empty())
                .or_else(|| known.iter().find(|credit| credit.browse_id.is_empty()))
                .map(|credit| credit.name.clone())
                .filter(|n| !n.is_empty())
                .unwrap_or_else(|| fallback_artist_text.trim().to_string());
            let credits = vec![ArtistCredit {
                name,
                browse_id: id.to_string(),
            }];
            register_artists(kind, id, credits.clone());
            return Ok(credits);
        }
        let fallback_name = fallback_artist_text.trim();
        if fallback_name.is_empty() {
            return Err("Missing artist ID".into());
        }
        let browse_id = resolve_artist_browse_id(fallback_name)
            .await
            .unwrap_or_default();
        let credits = vec![ArtistCredit {
            name: fallback_name.to_string(),
            browse_id: browse_id.clone(),
        }];
        if !browse_id.is_empty() {
            register_artists("artist", &browse_id, credits.clone());
        }
        return Ok(credits);
    }

    // 1. Check LRU cache first; return immediately if we already have linked credits.
    if !id.is_empty() && id != "native-prototype" {
        if let Some(cached) = cached_artists(kind, id) {
            if cached.iter().any(|c| !c.browse_id.is_empty()) {
                return Ok(cached);
            }
        }
    }

    // 2. Fetch structured credits from YouTube Music.
    let mut fetch_err: Option<String> = None;
    if !id.is_empty() && id != "native-prototype" {
        let fetched = async {
            let api = crate::ytm::ytmusic()
                .await
                .map_err(|error| format!("Artist metadata client: {error}"))?;
            match parsed_kind {
                Kind::Song => Ok(watch_metadata(&api, id).await?.artists),
                Kind::Album => {
                    let album = api
                        .query(GetAlbumQuery::new(AlbumID::from_raw(id)))
                        .await
                        .map_err(|error| format!("Album artist credits: {error}"))?;
                    Ok(parsed_credits(&album.artists))
                }
                Kind::Artist => unreachable!(),
            }
        }
        .await;

        match fetched {
            Ok(credits) => {
                register_artists(kind, id, credits);
            }
            Err(err) => {
                fetch_err = Some(err);
            }
        }
    }

    // 3. Read merged credits from cache.
    let mut credits = if !id.is_empty() && id != "native-prototype" {
        cached_artists(kind, id).unwrap_or_default()
    } else {
        Vec::new()
    };

    // 4. Fallback to splitting `fallback_artist_text` when still empty.
    if credits.is_empty() && !fallback_artist_text.trim().is_empty() {
        credits = split_fallback_artists(fallback_artist_text);
    }

    // 5. Resolve browse_id for unlinked credits where applicable.
    if !credits.is_empty() {
        enrich_unlinked_credits(&mut credits).await;
        if !id.is_empty() && id != "native-prototype" {
            register_artists(kind, id, credits.clone());
        }
        return Ok(credits);
    }

    Err(fetch_err.unwrap_or_else(|| format!("No artist credits available for {kind} {id}")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use ytmapi_rs::common::ArtistChannelID;

    fn credit(name: &str, id: &str) -> ArtistCredit {
        ArtistCredit {
            name: name.into(),
            browse_id: id.into(),
        }
    }

    fn run(name: &str, id: &str, page_type: &str) -> Value {
        json!({"text": name, "navigationEndpoint": {"browseEndpoint": {
            "browseId": id,
            "browseEndpointContextSupportedConfigs": {
                "browseEndpointContextMusicConfig": {"pageType": page_type}
            }
        }}})
    }

    fn watch_row(video_id: &str, runs: Vec<Value>) -> Value {
        json!({"playlistPanelVideoRenderer": {
            "videoId": video_id, "title": {"runs": [{"text": "Requested song"}]},
            "longBylineText": {"runs": runs}
        }})
    }

    #[test]
    fn watch_selects_exact_video_not_first_related() {
        let response = json!({"contents": [
            watch_row("nearby", vec![run("Wrong artist", "UCwrong", "MUSIC_PAGE_TYPE_ARTIST")]),
            watch_row("wanted", vec![
                run("Right artist", "UCright", "MUSIC_PAGE_TYPE_ARTIST"),
                run("Right album", "MPREright", "MUSIC_PAGE_TYPE_ALBUM")
            ])
        ]});
        let metadata = parse_watch_metadata(&response, "wanted").unwrap();
        assert_eq!(metadata.artists, vec![credit("Right artist", "UCright")]);
        assert_eq!(
            metadata.albums,
            vec![("MPREright".into(), "Right album".into())]
        );
        assert!(parse_watch_metadata(&response, "absent").is_err());
        assert!(parse_watch_metadata(&response, "WANTED").is_err());
    }

    #[test]
    fn watch_keeps_all_structured_contributors_and_literal_commas() {
        let response = watch_row(
            "wanted",
            vec![
                run("Earth, Wind & Fire", "UCband", "MUSIC_PAGE_TYPE_ARTIST"),
                json!({"text": ", "}),
                run("Guest", "UCguest", "MUSIC_PAGE_TYPE_ARTIST"),
                json!({"text": " & "}),
                json!({"text": "Unlinked, Actual Name"}),
                json!({"text": " \u{2022} "}),
                run("Record", "MPRErecord", "MUSIC_PAGE_TYPE_ALBUM"),
                json!({"text": " \u{2022} "}),
                json!({"text": "2022"}),
            ],
        );
        assert_eq!(
            parse_watch_metadata(&response, "wanted").unwrap().artists,
            vec![
                credit("Earth, Wind & Fire", "UCband"),
                credit("Guest", "UCguest"),
                credit("Unlinked, Actual Name", "")
            ]
        );
    }

    #[test]
    fn short_display_byline_is_not_an_individual_credit() {
        let mut response = watch_row(
            "wanted",
            vec![
                run("First", "UCfirst", "MUSIC_PAGE_TYPE_ARTIST"),
                json!({"text": ", "}),
                run("Second", "UCsecond", "MUSIC_PAGE_TYPE_ARTIST"),
            ],
        );
        response["playlistPanelVideoRenderer"]["shortBylineText"] = json!({
            "runs": [{"text": "First, & Second"}]
        });
        assert_eq!(
            parse_watch_metadata(&response, "wanted").unwrap().artists,
            vec![credit("First", "UCfirst"), credit("Second", "UCsecond")]
        );
        response["playlistPanelVideoRenderer"]["longBylineText"] = Value::Null;
        assert!(parse_watch_metadata(&response, "wanted")
            .unwrap()
            .artists
            .is_empty());
    }

    #[test]
    fn borrowed_album_artist_mapping_preserves_optional_ids() {
        let artists = vec![
            ParsedSongArtist {
                name: "Earth, Wind & Fire".into(),
                id: Some(ArtistChannelID::from_raw("UCband")),
            },
            ParsedSongArtist {
                name: "Guest".into(),
                id: Some(ArtistChannelID::from_raw("UCguest")),
            },
            ParsedSongArtist {
                name: "Unlinked".into(),
                id: None,
            },
        ];
        assert_eq!(
            parsed_credits(&artists),
            vec![
                credit("Earth, Wind & Fire", "UCband"),
                credit("Guest", "UCguest"),
                credit("Unlinked", "")
            ]
        );
        assert_eq!(artists.len(), 3);
    }

    #[test]
    fn merging_deduplicates_without_losing_names_or_case_sensitive_ids() {
        let mut credits = Vec::new();
        merge_credits(
            &mut credits,
            vec![
                credit(" Guest ", ""),
                credit("", "UCguest"),
                credit("Guest", "UCguest"),
                credit("guest", ""),
                credit("", ""),
                credit("Guest", "ucguest"),
                credit("Other", "UCother"),
            ],
        );
        merge_credits(
            &mut credits,
            vec![credit("", "UCguest"), credit("OTHER", ""), credit("", "")],
        );
        assert_eq!(
            credits,
            vec![
                credit("Guest", "UCguest"),
                credit("Guest", "ucguest"),
                credit("Other", "UCother")
            ]
        );
        assert!(credits.iter().all(|credit| !credit.browse_id.is_empty()));
    }

    #[test]
    fn cache_merges_and_keeps_only_128_least_recently_used_entries() {
        let mut cache = ArtistCache::default();
        for index in 0..CACHE_LIMIT {
            cache.insert(
                Kind::Song,
                &index.to_string(),
                vec![credit("Artist", "UCartist")],
            );
        }
        assert!(cache.get(Kind::Song, "0").is_some());
        cache.insert(Kind::Song, "new", vec![credit("New", "UCnew")]);
        assert_eq!(cache.entries.len(), CACHE_LIMIT);
        assert!(cache.get(Kind::Song, "1").is_none());
        cache.insert(
            Kind::Song,
            "0",
            vec![credit("", "UCartist"), credit("Guest", "UCguest")],
        );
        assert_eq!(
            cache.get(Kind::Song, "0").unwrap(),
            vec![credit("Artist", "UCartist"), credit("Guest", "UCguest")]
        );
        assert!(cache.get(Kind::Album, "0").is_none());
        cache.insert(Kind::Song, "empty", vec![ArtistCredit::default()]);
        assert!(cache.get(Kind::Song, "empty").is_none());
        assert_eq!(cache.entries.len(), CACHE_LIMIT);
    }

    #[test]
    fn fallback_splitting_handles_common_delimiters_and_deduplicates() {
        assert_eq!(
            split_fallback_artists("Arijit Singh, Shreya Ghoshal & Pritam"),
            vec![
                credit("Arijit Singh", ""),
                credit("Shreya Ghoshal", ""),
                credit("Pritam", ""),
            ]
        );
        assert_eq!(
            split_fallback_artists("The Weeknd feat. Daft Punk ft. Gesaffelstein"),
            vec![
                credit("The Weeknd", ""),
                credit("Daft Punk", ""),
                credit("Gesaffelstein", ""),
            ]
        );
        assert_eq!(
            split_fallback_artists("Karan Aujla x Ikky featuring Divine, karan aujla"),
            vec![
                credit("Karan Aujla", ""),
                credit("Ikky", ""),
                credit("Divine", ""),
            ]
        );
        // Should not split on 'x' inside a word.
        assert_eq!(
            split_fallback_artists("Exile & Rex Orange County"),
            vec![credit("Exile", ""), credit("Rex Orange County", "")]
        );
        assert!(split_fallback_artists("   ").is_empty());
    }

    #[test]
    fn match_artist_browse_id_requires_exact_normalized_name() {
        let results: Vec<SearchResultArtist> = serde_json::from_value(json!([
            {
                "artist": "Arijit Singh",
                "subscribers": "10M monthly audience",
                "browse_id": "UCarijit",
                "thumbnails": []
            },
            {
                "artist": "Arijit Singh Unplugged",
                "subscribers": "50K",
                "browse_id": "UCother",
                "thumbnails": []
            }
        ]))
        .unwrap();

        assert_eq!(
            match_artist_browse_id(&results, "  arijit   singh "),
            Some("UCarijit".into())
        );
        assert_eq!(match_artist_browse_id(&results, "Arijit"), None);
    }

    #[test]
    fn artist_self_fast_path_needs_no_network() {
        register_artists(
            "Artist",
            "UC-android-self-test",
            vec![credit("Known artist", "UC-android-self-test")],
        );
        let runtime = tokio::runtime::Builder::new_current_thread()
            .build()
            .unwrap();
        let credits = runtime
            .block_on(resolve_artists("Artist", "UC-android-self-test", ""))
            .unwrap();
        assert_eq!(
            credits,
            vec![credit("Known artist", "UC-android-self-test")]
        );
        assert!(runtime
            .block_on(resolve_artists("Playlist", "id", ""))
            .is_err());
    }
}
