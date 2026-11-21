//! YouTube Music API queries, smart search, release ranking, artwork upscaling,
//! and DTOs for the Android native core.

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use ytmapi_rs::auth::noauth::NoAuthToken;
use ytmapi_rs::common::{AlbumID, ArtistChannelID, PlaylistID, Thumbnail, VideoID, YoutubeID};
use ytmapi_rs::parse::{
    BasicSearchResultCommunityPlaylist, GetAlbum, GetArtist, ParsedSongArtist, PlaylistItem,
    SearchResultAlbum, SearchResultArtist, SearchResultCommunityPlaylist,
    SearchResultFeaturedPlaylist, SearchResultPlaylist, SearchResultSong, SearchResults, TopResult,
    TopResultType,
};
use ytmapi_rs::query::search::{
    AlbumsFilter, ArtistsFilter, PlaylistsFilter, SongsFilter, SpellingMode,
};
use ytmapi_rs::query::{GetAlbumQuery, GetArtistQuery, GetSearchSuggestionsQuery, SearchQuery};
use ytmapi_rs::YtMusic;

use crate::menu_metadata::{self, ArtistCredit};
use crate::release_rank;

const YTM_CLIENT_TTL: Duration = Duration::from_secs(20 * 60);
const SEARCH_CACHE_LIMIT: usize = 24;
const SEARCH_CACHE_TTL: Duration = Duration::from_secs(120);

type CachedYtmClient = Option<(Instant, Arc<YtMusic<NoAuthToken>>)>;
static YTM_CLIENT: OnceLock<tokio::sync::RwLock<CachedYtmClient>> = OnceLock::new();

fn ytm_client_lock() -> &'static tokio::sync::RwLock<CachedYtmClient> {
    YTM_CLIENT.get_or_init(|| tokio::sync::RwLock::new(None))
}

/// Return a cached `YtMusic<NoAuthToken>` client (20-minute TTL, 64 KiB HTTP/2 header cap).
/// Uses `tokio::sync::RwLock` so no `std::sync` lock is ever held across `.await`.
pub async fn ytmusic() -> ytmapi_rs::Result<Arc<YtMusic<NoAuthToken>>> {
    {
        let guard = ytm_client_lock().read().await;
        if let Some((created_at, client)) = guard.as_ref() {
            if created_at.elapsed() < YTM_CLIENT_TTL {
                return Ok(Arc::clone(client));
            }
        }
    }

    let mut guard = ytm_client_lock().write().await;
    if let Some((created_at, client)) = guard.as_ref() {
        if created_at.elapsed() < YTM_CLIENT_TTL {
            return Ok(Arc::clone(client));
        }
    }

    let http = reqwest::Client::builder()
        .http2_max_header_list_size(64 * 1024)
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(25))
        .build()?;
    let client = ytmapi_rs::client::Client::new_from_reqwest_client(http);
    let ytm = ytmapi_rs::builder::YtMusicBuilder::new_with_client(client)
        .build()
        .await?;
    let arc = Arc::new(ytm);
    *guard = Some((Instant::now(), Arc::clone(&arc)));
    Ok(arc)
}

/// Drop the cached YTM client so the next call establishes a fresh session.
pub async fn invalidate_ytmusic() {
    let mut guard = ytm_client_lock().write().await;
    *guard = None;
}

async fn maybe_invalidate_on_err(err_text: &str) {
    let lower = err_text.to_ascii_lowercase();
    if lower.contains("401")
        || lower.contains("403")
        || lower.contains("unauthorized")
        || lower.contains("forbidden")
        || lower.contains("header")
        || lower.contains("json")
        || lower.contains("parse")
    {
        invalidate_ytmusic().await;
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct TrackDto {
    pub video_id: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub album_id: String,
    pub artist_id: String,
    pub duration_secs: u32,
    pub thumbnail_url: String,
    #[serde(default)]
    pub artwork_url: String,
    #[serde(default)]
    pub artists: Vec<ArtistCredit>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct BrowseCardDto {
    pub id: String,
    pub kind: String, // "album" | "playlist" | "artist"
    pub item_type: String,
    pub title: String,
    pub subtitle: String,
    pub thumbnail_url: String,
    pub year: String,
    #[serde(default)]
    pub artists: Vec<ArtistCredit>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct ArtistCardDto {
    pub id: String,
    pub name: String,
    pub subscribers: String,
    pub thumbnail_url: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct HomeFeedResponse {
    pub ok: bool,
    pub hero: Option<TrackDto>,
    pub trending: Vec<TrackDto>,
    pub quick_picks: Vec<TrackDto>,
    pub new_releases: Vec<BrowseCardDto>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct SearchResponse {
    pub ok: bool,
    pub corrected_query: String,
    pub top: Option<serde_json::Value>,
    pub songs: Vec<TrackDto>,
    pub albums: Vec<BrowseCardDto>,
    pub artists: Vec<ArtistCardDto>,
    pub playlists: Vec<BrowseCardDto>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

pub fn parse_duration_secs(d: &str) -> u32 {
    let parts: Vec<&str> = d.trim().split(':').collect();
    if !matches!(parts.len(), 2 | 3) {
        return 0;
    }
    parts
        .iter()
        .enumerate()
        .try_fold(0u32, |total, (index, part)| {
            let value = part.trim().parse::<u32>().ok()?;
            if index > 0 && value >= 60 {
                return None;
            }
            total.checked_mul(60)?.checked_add(value)
        })
        .unwrap_or(0)
}

/// Upgrade Google/YouTube thumbnail URLs to crisp square high-resolution variants.
pub fn upgrade_thumbnail_url(url: &str, target_px: u32) -> String {
    let trimmed = url.trim();
    if trimmed.is_empty() {
        return String::new();
    }

    let lower = trimmed.to_ascii_lowercase();
    let host_part = lower
        .split_once("://")
        .map(|(_, rest)| rest.split('/').next().unwrap_or(""))
        .unwrap_or("");

    if host_part.contains("googleusercontent.com") || host_part.contains("ggpht.com") {
        let suffix = format!("=w{target_px}-h{target_px}-l90-rj");
        if let Some(last_slash) = trimmed.rfind('/') {
            let tail = &trimmed[last_slash..];
            // Find `=w<digit>` or `=s<digit>` parameter suffix on the final path segment.
            let mut size_eq_offset: Option<usize> = None;
            for (idx, _) in tail.match_indices('=') {
                let after = &tail[idx + 1..];
                let mut chars = after.chars();
                let first = chars.next();
                let second = chars.next();
                if matches!(first, Some('w' | 'W' | 's' | 'S'))
                    && second.is_some_and(|c| c.is_ascii_digit())
                {
                    size_eq_offset = Some(last_slash + idx);
                    break;
                }
            }
            if let Some(eq_pos) = size_eq_offset {
                return format!("{}{}", &trimmed[..eq_pos], suffix);
            }
            if !tail.contains('=') && !tail.contains('?') {
                return format!("{trimmed}{suffix}");
            }
        }
        return trimmed.to_string();
    }

    if lower.contains("i.ytimg.com/vi/") {
        let base = trimmed.split('?').next().unwrap_or(trimmed);
        let base_lower = base.to_ascii_lowercase();
        let replacement = if target_px >= 640 {
            "/sddefault.jpg"
        } else {
            "/hqdefault.jpg"
        };
        if base_lower.ends_with("/default.jpg") {
            let prefix = &base[..base.len() - "/default.jpg".len()];
            return format!("{prefix}{replacement}");
        }
        if base_lower.ends_with("/mqdefault.jpg") {
            let prefix = &base[..base.len() - "/mqdefault.jpg".len()];
            return format!("{prefix}{replacement}");
        }
    }

    trimmed.to_string()
}

pub fn pick_thumbnail_url(thumbs: &[Thumbnail], video_id: &str) -> String {
    if let Some(t) = thumbs.iter().max_by_key(|t| t.width.max(t.height)) {
        if !t.url.trim().is_empty() {
            return upgrade_thumbnail_url(&t.url, 544);
        }
    }
    if !video_id.trim().is_empty() {
        format!("https://i.ytimg.com/vi/{}/hqdefault.jpg", video_id.trim())
    } else {
        String::new()
    }
}

pub fn pick_artwork_url(thumbs: &[Thumbnail], video_id: &str) -> String {
    if let Some(t) = thumbs.iter().max_by_key(|t| t.width.max(t.height)) {
        if !t.url.trim().is_empty() {
            return upgrade_thumbnail_url(&t.url, 1080);
        }
    }
    if !video_id.trim().is_empty() {
        format!("https://i.ytimg.com/vi/{}/hqdefault.jpg", video_id.trim())
    } else {
        String::new()
    }
}

fn credit_names(artists: &[ParsedSongArtist]) -> String {
    artists
        .iter()
        .map(|a| a.name.as_str())
        .filter(|n| !n.trim().is_empty())
        .collect::<Vec<_>>()
        .join(", ")
}

fn first_artist_id(artists: &[ParsedSongArtist]) -> String {
    artists
        .first()
        .and_then(|a| a.id.as_ref())
        .map(|id| id.get_raw().to_string())
        .unwrap_or_default()
}

fn song_to_track_dto(s: SearchResultSong) -> TrackDto {
    let vid = s.video_id.get_raw().to_string();
    let thumb = pick_thumbnail_url(&s.thumbnails, &vid);
    let artwork = pick_artwork_url(&s.thumbnails, &vid);
    let (album, album_id) = match s.album {
        Some(a) => (a.name, a.id.get_raw().to_string()),
        None => (String::new(), String::new()),
    };
    let artists = menu_metadata::cached_artists("song", &vid)
        .filter(|c| !c.is_empty())
        .unwrap_or_else(|| menu_metadata::split_fallback_artists(&s.artist));
    let artist_id = artists
        .iter()
        .find(|a| !a.browse_id.is_empty())
        .map(|a| a.browse_id.clone())
        .unwrap_or_default();
    if artists.iter().any(|a| !a.browse_id.is_empty()) {
        menu_metadata::register_artists("song", &vid, artists.clone());
    }
    TrackDto {
        video_id: vid,
        title: s.title,
        artist: s.artist,
        album,
        album_id,
        artist_id,
        duration_secs: parse_duration_secs(&s.duration),
        thumbnail_url: thumb,
        artwork_url: artwork,
        artists,
    }
}

fn current_year_approx() -> i32 {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    1970 + (secs / 31_556_952) as i32
}

fn is_valid_home_track(t: &TrackDto) -> bool {
    if t.video_id.trim().is_empty() || t.title.trim().is_empty() {
        return false;
    }
    if release_rank::is_junk_track_or_compilation(&t.title, &t.artist) {
        return false;
    }
    if t.duration_secs > 15 * 60 {
        return false;
    }
    if t.duration_secs > 0 && t.duration_secs < 45 {
        return false;
    }
    true
}

pub async fn get_home_feed() -> HomeFeedResponse {
    let api = match ytmusic().await {
        Ok(a) => a,
        Err(e) => {
            return HomeFeedResponse {
                ok: false,
                hero: None,
                trending: Vec::new(),
                quick_picks: Vec::new(),
                new_releases: Vec::new(),
                error: Some(format!("Failed to connect to YTM: {e}")),
            };
        }
    };

    let current_year = current_year_approx();
    let recent_album_q = format!("New Releases {current_year}");

    let (songs_primary, songs_secondary, albums_year_res, albums_new_res, playlists_res) =
        tokio::join!(
            api.query(SearchQuery::new("New Music").with_filter(SongsFilter)),
            api.query(SearchQuery::new("Pop Music").with_filter(SongsFilter)),
            api.query(SearchQuery::new(recent_album_q).with_filter(AlbumsFilter)),
            api.query(SearchQuery::new("New Releases").with_filter(AlbumsFilter)),
            api.query(SearchQuery::new("New Music").with_filter(PlaylistsFilter)),
        );

    if let (Err(e1), Err(e2), Err(e3), Err(e4), Err(e5)) = (
        &songs_primary,
        &songs_secondary,
        &albums_year_res,
        &albums_new_res,
        &playlists_res,
    ) {
        let combined = format!("All home feed queries failed: {e1}; {e2}; {e3}; {e4}; {e5}");
        maybe_invalidate_on_err(&combined).await;
        return HomeFeedResponse {
            ok: false,
            hero: None,
            trending: Vec::new(),
            quick_picks: Vec::new(),
            new_releases: Vec::new(),
            error: Some(combined),
        };
    }

    let mut seen_ids: HashSet<String> = HashSet::new();
    let mut seen_titles: HashSet<String> = HashSet::new();

    let mut dedup_push = |raw_songs: Vec<SearchResultSong>, limit: usize| -> Vec<TrackDto> {
        let mut out = Vec::new();
        for song in raw_songs {
            let dto = song_to_track_dto(song);
            if !is_valid_home_track(&dto) {
                continue;
            }
            let tkey = release_rank::title_key(&dto.title);
            if !seen_ids.insert(dto.video_id.clone()) {
                continue;
            }
            if !tkey.is_empty() && !seen_titles.insert(tkey) {
                continue;
            }
            out.push(dto);
            if out.len() >= limit {
                break;
            }
        }
        out
    };

    let mut trending_pool = dedup_push(songs_primary.unwrap_or_default(), 17);
    let mut quick_picks = dedup_push(songs_secondary.unwrap_or_default(), 16);

    // Pick a strong featured Hero track and ensure `trending` does NOT duplicate it.
    let hero = if !trending_pool.is_empty() {
        Some(trending_pool.remove(0))
    } else if !quick_picks.is_empty() {
        Some(quick_picks.remove(0))
    } else {
        None
    };

    let trending: Vec<TrackDto> = trending_pool.into_iter().take(16).collect();

    // Collect, deduplicate, and filter album candidates for New Releases.
    let mut raw_albums: Vec<(i32, BrowseCardDto)> = Vec::new();
    let mut seen_album_ids: HashSet<String> = HashSet::new();
    let mut seen_album_titles: HashSet<String> = HashSet::new();

    for res in [albums_year_res, albums_new_res] {
        if let Ok(albums) = res {
            for a in albums {
                let id = a.album_id.get_raw().trim().to_string();
                if id.is_empty() || !seen_album_ids.insert(id.clone()) {
                    continue;
                }
                if release_rank::is_junk_track_or_compilation(&a.title, &a.artist) {
                    continue;
                }
                let tkey = release_rank::title_key(&a.title);
                if !tkey.is_empty() && !seen_album_titles.insert(tkey) {
                    continue;
                }
                let year_num: i32 = a.year.trim().parse().unwrap_or(0);
                let thumb = pick_thumbnail_url(&a.thumbnails, "");
                let artists = menu_metadata::split_fallback_artists(&a.artist);
                raw_albums.push((
                    year_num,
                    BrowseCardDto {
                        id,
                        kind: "album".to_string(),
                        item_type: "album".to_string(),
                        title: a.title,
                        subtitle: a.artist,
                        thumbnail_url: thumb,
                        year: a.year,
                        artists,
                    },
                ));
            }
        }
    }

    // Prefer genuinely recent releases (current_year - 1), relaxing to current_year - 2 if sparse.
    let mut filtered_albums: Vec<(i32, BrowseCardDto)> = raw_albums
        .iter()
        .filter(|(y, _)| *y >= current_year - 1)
        .cloned()
        .collect();
    if filtered_albums.len() < 4 {
        filtered_albums = raw_albums
            .iter()
            .filter(|(y, _)| *y >= current_year - 2)
            .cloned()
            .collect();
    }
    if filtered_albums.len() < 4 {
        filtered_albums = raw_albums;
    }
    // Newest first (unknown years = 0 sink to the bottom).
    filtered_albums.sort_by(|a, b| b.0.cmp(&a.0));

    let mut new_releases: Vec<BrowseCardDto> = filtered_albums
        .into_iter()
        .take(10)
        .map(|(_, card)| card)
        .collect();

    // Append a few clean curated playlists (filtering out junk/compilation spam titles).
    if let Ok(playlists) = playlists_res {
        let mut seen_pl: HashSet<String> = HashSet::new();
        let mut added_pl = 0usize;
        for p in playlists {
            if added_pl >= 4 {
                break;
            }
            let (id, title, author, thumbs) = match p {
                SearchResultPlaylist::Featured(f) => {
                    (f.playlist_id.get_raw().to_string(), f.title, f.author, f.thumbnails)
                }
                SearchResultPlaylist::Community(c) => {
                    (c.playlist_id.get_raw().to_string(), c.title, c.author, c.thumbnails)
                }
                _ => continue,
            };
            if id.trim().is_empty() || !seen_pl.insert(id.clone()) {
                continue;
            }
            if release_rank::is_junk_track_or_compilation(&title, &author) {
                continue;
            }
            let thumb = pick_thumbnail_url(&thumbs, "");
            new_releases.push(BrowseCardDto {
                id,
                kind: "playlist".to_string(),
                item_type: "playlist".to_string(),
                title,
                subtitle: author,
                thumbnail_url: thumb,
                year: String::new(),
                artists: Vec::new(),
            });
            added_pl += 1;
        }
    }

    HomeFeedResponse {
        ok: true,
        hero,
        trending,
        quick_picks,
        new_releases,
        error: None,
    }
}

// ============================================================================
// Smart Search (ported from Windows Auricle core/search.rs)
// ============================================================================

struct SearchCacheEntry {
    key: (String, String),
    inserted: Instant,
    response: SearchResponse,
}

static SEARCH_CACHE: OnceLock<Mutex<VecDeque<SearchCacheEntry>>> = OnceLock::new();

fn search_cache() -> &'static Mutex<VecDeque<SearchCacheEntry>> {
    SEARCH_CACHE.get_or_init(|| Mutex::new(VecDeque::new()))
}

fn search_cache_get(norm_query: &str, category: &str) -> Option<SearchResponse> {
    let mut guard = search_cache().lock().ok()?;
    let now = Instant::now();
    guard.retain(|e| now.saturating_duration_since(e.inserted) < SEARCH_CACHE_TTL);
    let pos = guard
        .iter()
        .position(|e| e.key.0 == norm_query && e.key.1 == category)?;
    let entry = guard.remove(pos)?;
    let resp = entry.response.clone();
    guard.push_back(entry);
    Some(resp)
}

fn search_cache_put(norm_query: String, category: String, response: SearchResponse) {
    if let Ok(mut guard) = search_cache().lock() {
        let now = Instant::now();
        guard.retain(|e| {
            !(e.key.0 == norm_query && e.key.1 == category)
                && now.saturating_duration_since(e.inserted) < SEARCH_CACHE_TTL
        });
        guard.push_back(SearchCacheEntry {
            key: (norm_query, category),
            inserted: now,
            response,
        });
        while guard.len() > SEARCH_CACHE_LIMIT {
            guard.pop_front();
        }
    }
}

fn normalize_ws(value: &str) -> String {
    value
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

fn join_label(parts: &[&str], separator: &str) -> String {
    parts
        .iter()
        .map(|part| part.trim())
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>()
        .join(separator)
}

#[derive(Clone, Debug, Default)]
struct SearchEntry {
    kind: String, // "Artist" | "Album" | "Song" | "Playlist"
    id: String,
    title: String,
    subtitle: String,
    artist: String,
    album: String,
    album_id: String,
    artist_id: String,
    duration_secs: u32,
    thumbnail_url: String,
    artwork_url: String,
    year: String,
    artists: Vec<ArtistCredit>,
}

#[derive(Clone, Debug, Default)]
struct SearchPage {
    top: Option<SearchEntry>,
    corrected_query: String,
    songs: Vec<SearchEntry>,
    artists: Vec<SearchEntry>,
    albums: Vec<SearchEntry>,
    playlists: Vec<SearchEntry>,
}

#[derive(Default)]
struct TopIdentity {
    kind: String,
    id: String,
    title: String,
    artist: String,
    album: String,
    year: String,
    subtitle: String,
    thumbnail_url: String,
    artwork_url: String,
    music_video: bool,
    artist_credits: Vec<String>,
    structured_artists: Vec<ArtistCredit>,
}

enum Resolution {
    Missing,
    Unique(SearchEntry),
    Ambiguous,
}

fn provider_correction(
    query: &str,
    json: &serde_json::Value,
    allowed: bool,
) -> Option<(String, bool)> {
    if !allowed {
        return None;
    }
    fn visit(query: &str, value: &serde_json::Value) -> Option<(String, bool)> {
        match value {
            serde_json::Value::Object(object) => {
                for (renderer, applied) in [
                    ("showingResultsForRenderer", true),
                    ("didYouMeanRenderer", false),
                ] {
                    if let Some(correction) = object.get(renderer) {
                        let paths = if applied {
                            [
                                "/correctedQueryEndpoint/searchEndpoint/query",
                                "/correctedQuery/runs/0/navigationEndpoint/searchEndpoint/query",
                            ]
                        } else {
                            [
                                "/navigationEndpoint/searchEndpoint/query",
                                "/correctedQueryEndpoint/searchEndpoint/query",
                            ]
                        };
                        for path in paths {
                            if let Some(text) =
                                correction.pointer(path).and_then(serde_json::Value::as_str)
                            {
                                let text =
                                    text.split_whitespace().collect::<Vec<_>>().join(" ");
                                if !text.is_empty() && normalize_ws(&text) != normalize_ws(query) {
                                    return Some((text, applied));
                                }
                            }
                        }
                    }
                }
                object.values().find_map(|value| visit(query, value))
            }
            serde_json::Value::Array(values) => values.iter().find_map(|value| visit(query, value)),
            _ => None,
        }
    }
    visit(query, json)
}

fn closest_correction(query: &str, suggestions: &[String]) -> Option<String> {
    let original = normalize_ws(query);
    if original.chars().count() < 4 || original.chars().count() > 128 {
        return None;
    }
    let mut best: Option<(f64, String)> = None;
    for suggestion in suggestions {
        let candidate = normalize_ws(suggestion);
        if candidate == original
            || candidate.is_empty()
            || candidate.split_whitespace().count() != original.split_whitespace().count()
            || candidate.chars().count() > 128
        {
            continue;
        }
        let similarity = strsim::normalized_damerau_levenshtein(&original, &candidate);
        let distance = strsim::damerau_levenshtein(&original, &candidate);
        if similarity >= 0.80
            && distance <= 2
            && best.as_ref().map_or(true, |(score, _)| similarity > *score)
        {
            best = Some((
                similarity,
                suggestion.split_whitespace().collect::<Vec<_>>().join(" "),
            ));
        }
    }
    best.map(|(_, suggestion)| suggestion)
}

fn metadata_groups(runs: &[serde_json::Value]) -> Vec<String> {
    runs.split(|run| run["text"].as_str().map(str::trim) == Some("\u{2022}"))
        .map(|group| {
            group
                .iter()
                .filter_map(|run| run["text"].as_str())
                .collect::<String>()
                .trim()
                .to_string()
        })
        .filter(|group| !group.is_empty())
        .collect()
}

fn extract_artist_credits_from_runs(runs: &[serde_json::Value]) -> Vec<ArtistCredit> {
    let mut credits = Vec::new();
    for run in runs {
        let endpoint = run.pointer("/navigationEndpoint/browseEndpoint");
        let page_type = endpoint
            .and_then(|ep| {
                ep.pointer(
                    "/browseEndpointContextSupportedConfigs/browseEndpointContextMusicConfig/pageType",
                )
            })
            .and_then(serde_json::Value::as_str);
        if page_type == Some("MUSIC_PAGE_TYPE_ARTIST") {
            let name = run["text"].as_str().unwrap_or("").trim().to_string();
            let browse_id = endpoint
                .and_then(|ep| ep.get("browseId"))
                .and_then(serde_json::Value::as_str)
                .unwrap_or("")
                .trim()
                .to_string();
            if !name.is_empty() {
                credits.push(ArtistCredit { name, browse_id });
            }
        }
    }
    credits
}

fn extract_raw_song_artists(value: &serde_json::Value) {
    match value {
        serde_json::Value::Object(obj) => {
            if let Some(row) = obj.get("musicResponsiveListItemRenderer") {
                let vid = row
                    .pointer("/playlistItemData/videoId")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or("");
                if !vid.is_empty() {
                    if let Some(runs) = row
                        .pointer("/flexColumns/1/musicResponsiveListItemFlexColumnRenderer/text/runs")
                        .and_then(serde_json::Value::as_array)
                    {
                        let credits = extract_artist_credits_from_runs(runs);
                        if !credits.is_empty() {
                            menu_metadata::register_artists("song", vid, credits);
                        }
                    }
                }
            }
            for child in obj.values() {
                extract_raw_song_artists(child);
            }
        }
        serde_json::Value::Array(arr) => {
            for child in arr {
                extract_raw_song_artists(child);
            }
        }
        _ => {}
    }
}

fn card_audio_song(card: &serde_json::Value) -> Option<SearchResultSong> {
    let endpoint = card.pointer("/title/runs/0/navigationEndpoint/watchEndpoint")?;
    if endpoint
        .pointer("/watchEndpointMusicSupportedConfigs/watchEndpointMusicConfig/musicVideoType")
        .and_then(serde_json::Value::as_str)
        != Some("MUSIC_VIDEO_TYPE_ATV")
    {
        return None;
    }
    let id = endpoint["videoId"]
        .as_str()
        .filter(|id| !id.trim().is_empty())?;
    let title = card
        .pointer("/title/runs")
        .and_then(serde_json::Value::as_array)?
        .iter()
        .filter_map(|run| run["text"].as_str())
        .collect::<String>();
    let runs = card
        .pointer("/subtitle/runs")
        .and_then(serde_json::Value::as_array)
        .cloned()
        .unwrap_or_default();
    let credits = extract_artist_credits_from_runs(&runs);
    if !credits.is_empty() {
        menu_metadata::register_artists("song", id, credits.clone());
    }
    let mut artist = credits
        .iter()
        .map(|c| c.name.as_str())
        .collect::<Vec<_>>()
        .join(", ");
    let duration = runs
        .iter()
        .filter_map(|run| run["text"].as_str())
        .find(|text| parse_duration_secs(text) > 0)
        .unwrap_or_default();
    if artist.is_empty() {
        artist = metadata_groups(&runs).get(1).cloned().unwrap_or_default();
    }
    let thumbnails = card
        .pointer("/thumbnail/musicThumbnailRenderer/thumbnail/thumbnails")
        .cloned()
        .unwrap_or_else(|| serde_json::json!([]));
    serde_json::from_value(serde_json::json!({
        "title": title, "artist": artist, "album": null, "duration": duration,
        "plays": "", "explicit": "NotExplicit", "video_id": id, "thumbnails": thumbnails,
    }))
    .ok()
}

fn extract_card_previews(card: &serde_json::Value, results: &mut SearchResults) {
    for child in card["contents"].as_array().into_iter().flatten() {
        if let Some(row) = child.get("musicResponsiveListItemRenderer") {
            extract_list_item(row, Some(card), results);
        }
    }
}

fn extract_list_item(
    row: &serde_json::Value,
    card: Option<&serde_json::Value>,
    results: &mut SearchResults,
) {
    let Some(title_runs) = row
        .pointer("/flexColumns/0/musicResponsiveListItemFlexColumnRenderer/text/runs")
        .and_then(serde_json::Value::as_array)
    else {
        return;
    };
    let Some(metadata) = row
        .pointer("/flexColumns/1/musicResponsiveListItemFlexColumnRenderer/text/runs")
        .and_then(serde_json::Value::as_array)
    else {
        return;
    };
    let title = title_runs
        .iter()
        .filter_map(|run| run["text"].as_str())
        .collect::<String>();
    if title.trim().is_empty() {
        return;
    }
    let label_hint = metadata
        .first()
        .and_then(|run| run["text"].as_str())
        .unwrap_or_default();
    let endpoints: Vec<_> = title_runs
        .iter()
        .filter_map(|run| run.get("navigationEndpoint"))
        .chain(
            [
                row.get("navigationEndpoint"),
                row.get("onTap"),
                row.pointer(
                    "/overlay/musicItemThumbnailOverlayRenderer/content/musicPlayButtonRenderer/playNavigationEndpoint",
                ),
            ]
            .into_iter()
            .flatten(),
        )
        .collect();
    let browse_run = |page_type: &str| {
        metadata.iter().find(|run| {
            run.pointer(
                "/navigationEndpoint/browseEndpoint/browseEndpointContextSupportedConfigs/browseEndpointContextMusicConfig/pageType",
            )
            .and_then(serde_json::Value::as_str)
                == Some(page_type)
        })
    };
    let structured_artists = extract_artist_credits_from_runs(metadata);
    let mut artist = structured_artists
        .iter()
        .map(|c| c.name.as_str())
        .collect::<Vec<_>>()
        .join(", ");
    if artist.is_empty()
        && card
            .and_then(|card| card.pointer("/subtitle/runs/0/text"))
            .and_then(serde_json::Value::as_str)
            == Some("Artist")
    {
        artist = card
            .and_then(|card| card.pointer("/title/runs"))
            .and_then(serde_json::Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(|run| run["text"].as_str())
            .collect();
    }
    let thumbnails = row
        .pointer("/thumbnail/musicThumbnailRenderer/thumbnail/thumbnails")
        .cloned()
        .unwrap_or_else(|| serde_json::json!([]));
    if let Some(browse) = [row.get("navigationEndpoint"), row.get("onTap")]
        .into_iter()
        .flatten()
        .chain(title_runs.iter().filter_map(|run| run.get("navigationEndpoint")))
        .find_map(|endpoint| endpoint.get("browseEndpoint"))
    {
        let Some(id) = browse["browseId"]
            .as_str()
            .filter(|id| !id.trim().is_empty())
        else {
            return;
        };
        let page_type = browse
            .pointer(
                "/browseEndpointContextSupportedConfigs/browseEndpointContextMusicConfig/pageType",
            )
            .and_then(serde_json::Value::as_str);
        match page_type {
            Some("MUSIC_PAGE_TYPE_ALBUM") | None
                if page_type.is_some() || matches!(label_hint, "Album" | "Single" | "EP") =>
            {
                let album_type = if matches!(label_hint, "Album" | "Single" | "EP") {
                    label_hint
                } else {
                    "Album"
                };
                let year = metadata
                    .iter()
                    .filter_map(|run| run["text"].as_str())
                    .find(|text| text.len() == 4 && text.bytes().all(|b| b.is_ascii_digit()))
                    .unwrap_or_default();
                if !structured_artists.is_empty() {
                    menu_metadata::register_artists("album", id, structured_artists);
                }
                if let Ok(album) = serde_json::from_value(serde_json::json!({
                    "title": title, "artist": artist, "year": year, "explicit": "NotExplicit",
                    "album_id": id, "album_type": album_type, "thumbnails": thumbnails,
                })) {
                    results.albums.push(album);
                }
            }
            Some("MUSIC_PAGE_TYPE_ARTIST") => {
                let subscribers = metadata
                    .iter()
                    .filter_map(|run| run["text"].as_str().map(str::trim))
                    .find(|text| !text.is_empty() && !matches!(*text, "Artist" | "\u{2022}" | "-" | "|"))
                    .map(str::to_string);
                if let Ok(artist_item) = serde_json::from_value(serde_json::json!({
                    "artist": title, "subscribers": subscribers, "browse_id": id, "thumbnails": thumbnails,
                })) {
                    results.artists.push(artist_item);
                }
            }
            Some("MUSIC_PAGE_TYPE_PLAYLIST") => {
                let groups = metadata_groups(metadata);
                let is_count = |text: &str| {
                    [" song", " songs", " view", " views"]
                        .iter()
                        .any(|suffix| text.ends_with(suffix))
                };
                let songs = groups
                    .iter()
                    .find(|text| text.ends_with(" song") || text.ends_with(" songs"))
                    .cloned()
                    .unwrap_or_default();
                let author = groups
                    .iter()
                    .find(|text| text.as_str() != "Playlist" && !is_count(text))
                    .cloned()
                    .unwrap_or_default();
                if let Ok(playlist) = serde_json::from_value(serde_json::json!({
                    "title": title, "author": author, "songs": songs, "playlist_id": id, "thumbnails": thumbnails,
                })) {
                    results.featured_playlists.push(playlist);
                }
            }
            _ => {}
        }
        return;
    }

    // Enforce MUSIC_VIDEO_TYPE_ATV on song rows.
    let video_types: Vec<_> = endpoints
        .iter()
        .filter_map(|endpoint| {
            endpoint
                .pointer(
                    "/watchEndpoint/watchEndpointMusicSupportedConfigs/watchEndpointMusicConfig/musicVideoType",
                )
                .and_then(serde_json::Value::as_str)
        })
        .chain(
            [
                row.pointer("/playlistItemData/musicVideoType"),
                row.pointer(
                    "/playlistItemData/watchEndpointMusicSupportedConfigs/watchEndpointMusicConfig/musicVideoType",
                ),
            ]
            .into_iter()
            .flatten()
            .filter_map(serde_json::Value::as_str),
        )
        .collect();
    if video_types
        .iter()
        .any(|&video_type| video_type != "MUSIC_VIDEO_TYPE_ATV")
        || (video_types.is_empty() && label_hint != "Song")
    {
        return;
    }
    let id = row
        .pointer("/playlistItemData/videoId")
        .and_then(serde_json::Value::as_str)
        .filter(|id| !id.trim().is_empty())
        .or_else(|| {
            endpoints.iter().find_map(|endpoint| {
                endpoint
                    .pointer("/watchEndpoint/videoId")
                    .and_then(serde_json::Value::as_str)
                    .filter(|id| !id.trim().is_empty())
            })
        });
    let Some(id) = id else { return };
    if !structured_artists.is_empty() {
        menu_metadata::register_artists("song", id, structured_artists);
    }
    let album = browse_run("MUSIC_PAGE_TYPE_ALBUM").and_then(|run| {
        Some(serde_json::json!({
            "name": run["text"].as_str()?,
            "id": run.pointer("/navigationEndpoint/browseEndpoint/browseId")?.as_str()?,
        }))
    });
    let duration = metadata
        .iter()
        .filter_map(|run| run["text"].as_str())
        .find(|text| parse_duration_secs(text) > 0)
        .unwrap_or_default();
    if let Ok(song) = serde_json::from_value(serde_json::json!({
        "title": title, "artist": artist, "album": album, "duration": duration,
        "plays": "", "explicit": "NotExplicit", "video_id": id, "thumbnails": thumbnails,
    })) {
        results.songs.push(song);
    }
}

fn card_endpoint<'card>(card: &'card serde_json::Value, kind: &str) -> Option<&'card str> {
    let paths: &[&str] = match kind {
        "Song" | "Video" => &[
            "/title/runs/0/navigationEndpoint/watchEndpoint/videoId",
            "/onTap/watchEndpoint/videoId",
            "/playButton/musicPlayButtonRenderer/playNavigationEndpoint/watchEndpoint/videoId",
        ],
        "Playlist" => &[
            "/title/runs/0/navigationEndpoint/browseEndpoint/browseId",
            "/onTap/browseEndpoint/browseId",
            "/title/runs/0/navigationEndpoint/watchPlaylistEndpoint/playlistId",
            "/title/runs/0/navigationEndpoint/watchEndpoint/playlistId",
            "/onTap/watchPlaylistEndpoint/playlistId",
            "/onTap/watchEndpoint/playlistId",
            "/playButton/musicPlayButtonRenderer/playNavigationEndpoint/watchPlaylistEndpoint/playlistId",
            "/playButton/musicPlayButtonRenderer/playNavigationEndpoint/watchEndpoint/playlistId",
        ],
        _ => &[
            "/title/runs/0/navigationEndpoint/browseEndpoint/browseId",
            "/onTap/browseEndpoint/browseId",
        ],
    };
    paths.iter().find_map(|path| {
        card.pointer(path)
            .and_then(serde_json::Value::as_str)
            .filter(|id| !id.trim().is_empty())
    })
}

fn is_music_video_card(card: &serde_json::Value) -> bool {
    [
        "/title/runs/0/navigationEndpoint",
        "/onTap",
        "/playButton/musicPlayButtonRenderer/playNavigationEndpoint",
    ]
    .iter()
    .any(|path| {
        matches!(
            card.pointer(path)
                .and_then(|endpoint| {
                    endpoint.pointer(
                        "/watchEndpoint/watchEndpointMusicSupportedConfigs/watchEndpointMusicConfig/musicVideoType",
                    )
                })
                .and_then(serde_json::Value::as_str),
            Some("MUSIC_VIDEO_TYPE_OMV" | "MUSIC_VIDEO_TYPE_ATV")
        )
    })
}

fn top_identity(top: &TopResult) -> Option<TopIdentity> {
    let kind = match top.result_type.as_ref()? {
        TopResultType::Artist => "Artist",
        TopResultType::Album(_) => "Album",
        TopResultType::Song => "Song",
        TopResultType::Playlist => "Playlist",
        _ => return None,
    };
    if normalize_ws(&top.result_name).is_empty() {
        return None;
    }
    Some(TopIdentity {
        kind: kind.into(),
        id: String::new(),
        title: top.result_name.clone(),
        artist: top.artist.clone().unwrap_or_default(),
        album: top.album.clone().unwrap_or_default(),
        year: top.year.clone().unwrap_or_default(),
        subtitle: if kind == "Artist" {
            top.subscribers.clone().unwrap_or_default()
        } else {
            join_label(
                &[
                    top.artist.as_deref().unwrap_or_default(),
                    top.year.as_deref().unwrap_or_default(),
                ],
                " - ",
            )
        },
        thumbnail_url: pick_thumbnail_url(&top.thumbnails, ""),
        artwork_url: pick_artwork_url(&top.thumbnails, ""),
        music_video: false,
        artist_credits: Vec::new(),
        structured_artists: Vec::new(),
    })
}

fn card_identity(results: &SearchResults, card: Option<&serde_json::Value>) -> Option<TopIdentity> {
    let top = results.top_results.first()?;
    let card = card.filter(|card| {
        card.pointer("/title/runs/0/text")
            .and_then(serde_json::Value::as_str)
            .map(|title| normalize_ws(title) == normalize_ws(&top.result_name))
            .unwrap_or(false)
    });
    let mut identity = top_identity(top).or_else(|| {
        if !matches!(top.result_type, None | Some(TopResultType::Video)) {
            return None;
        }
        let card = card?;
        let video_id = match card_endpoint(card, "Video") {
            Some(id) => id,
            None if top.result_type == Some(TopResultType::Video) => "",
            None => return None,
        };
        if top.result_type.is_none() {
            if let Some(song) = results
                .songs
                .iter()
                .find(|song| song.video_id.get_raw() == video_id)
            {
                return Some(TopIdentity {
                    kind: "Song".into(),
                    id: video_id.into(),
                    title: song.title.clone(),
                    artist: song.artist.clone(),
                    ..Default::default()
                });
            }
        }
        Some(TopIdentity {
            kind: "Video".into(),
            id: video_id.into(),
            title: top.result_name.clone(),
            artist: top.artist.clone().unwrap_or_default(),
            music_video: is_music_video_card(card),
            ..Default::default()
        })
    });
    if let (Some(identity), Some(card)) = (identity.as_mut(), card) {
        let title = card
            .pointer("/title/runs/0/text")
            .and_then(serde_json::Value::as_str)
            .unwrap_or_default();
        if normalize_ws(title) == normalize_ws(&identity.title) {
            identity.id = card_endpoint(card, &identity.kind)
                .unwrap_or_default()
                .to_string();
            if let Some(runs) = card
                .pointer("/subtitle/runs")
                .and_then(serde_json::Value::as_array)
            {
                let metadata = runs
                    .iter()
                    .skip(2)
                    .filter_map(|run| run["text"].as_str())
                    .collect::<String>();
                if !metadata.trim().is_empty() {
                    identity.subtitle = metadata.trim().into();
                }
                if matches!(identity.kind.as_str(), "Album" | "Song" | "Video") {
                    identity.structured_artists = extract_artist_credits_from_runs(runs);
                    identity.artist_credits = identity
                        .structured_artists
                        .iter()
                        .map(|c| c.name.clone())
                        .collect();
                    if identity.artist.is_empty() {
                        identity.artist = identity.artist_credits.join(", ");
                    }
                }
                if identity.kind == "Album" && identity.year.is_empty() {
                    identity.year = runs
                        .iter()
                        .rev()
                        .filter_map(|run| run["text"].as_str())
                        .find(|text| text.len() == 4 && text.bytes().all(|b| b.is_ascii_digit()))
                        .unwrap_or_default()
                        .into();
                }
            }
        }
    }
    identity
}

fn parse_unfiltered_results(
    query: &str,
    mut json: serde_json::Value,
) -> Result<(SearchResults, Option<TopIdentity>), String> {
    extract_raw_song_artists(&json);
    let mut card_metadata = None;
    let mut previews = SearchResults::default();
    if let Some(sections) = json
        .pointer_mut(
            "/contents/tabbedSearchResultsRenderer/tabs/0/tabRenderer/content/sectionListRenderer/contents",
        )
        .and_then(serde_json::Value::as_array_mut)
    {
        for section in sections {
            if let Some(card) = section.get_mut("musicCardShelfRenderer") {
                extract_card_previews(card, &mut previews);
                if card_metadata.is_none() {
                    card_metadata = Some(card.clone());
                }
                if let Some(card) = card.as_object_mut() {
                    card.remove("contents");
                }
            } else if let Some(row) = section
                .pointer("/itemSectionRenderer/contents/0/musicResponsiveListItemRenderer")
            {
                extract_list_item(row, None, &mut previews);
            }
        }
    }
    let search_query = SearchQuery::new(query);
    let mut results = ytmapi_rs::parse::ProcessedResult {
        query: &search_query,
        source: String::new(),
        json: serde_json::from_value(json).map_err(|error| error.to_string())?,
    }
    .parse_into::<SearchResults>()
    .map_err(|error| error.to_string())?;
    results.songs.extend(previews.songs);
    results.albums.extend(previews.albums);
    results.artists.extend(previews.artists);
    results.featured_playlists.extend(previews.featured_playlists);
    let identity = card_identity(&results, card_metadata.as_ref());
    if identity
        .as_ref()
        .is_some_and(|identity| identity.kind == "Song")
    {
        if let Some(song) = card_metadata.as_ref().and_then(card_audio_song) {
            if !results
                .songs
                .iter()
                .any(|existing| existing.video_id == song.video_id)
            {
                results.songs.insert(0, song);
            }
        }
    }
    Ok((results, identity))
}

fn song_entry(song: SearchResultSong) -> SearchEntry {
    let vid = song.video_id.get_raw().to_string();
    let (album, album_id) = song
        .album
        .map(|album| (album.name, album.id.get_raw().to_string()))
        .unwrap_or_default();
    let artists = menu_metadata::cached_artists("song", &vid)
        .filter(|c| !c.is_empty())
        .unwrap_or_else(|| menu_metadata::split_fallback_artists(&song.artist));
    let artist_id = artists
        .iter()
        .find(|a| !a.browse_id.is_empty())
        .map(|a| a.browse_id.clone())
        .unwrap_or_default();
    SearchEntry {
        kind: "Song".into(),
        id: vid.clone(),
        subtitle: join_label(&[&song.artist, &album], " - "),
        title: song.title,
        artist: song.artist,
        album,
        album_id,
        artist_id,
        duration_secs: parse_duration_secs(&song.duration),
        thumbnail_url: pick_thumbnail_url(&song.thumbnails, &vid),
        artwork_url: pick_artwork_url(&song.thumbnails, &vid),
        year: String::new(),
        artists,
    }
}

fn artist_entry(artist: SearchResultArtist) -> SearchEntry {
    let id = artist.browse_id.get_raw().to_string();
    let credits = vec![ArtistCredit {
        name: artist.artist.clone(),
        browse_id: id.clone(),
    }];
    menu_metadata::register_artists("artist", &id, credits.clone());
    SearchEntry {
        kind: "Artist".into(),
        id: id.clone(),
        title: artist.artist.clone(),
        artist: artist.artist,
        artist_id: id,
        subtitle: artist.subscribers.unwrap_or_default(),
        thumbnail_url: pick_thumbnail_url(&artist.thumbnails, ""),
        artwork_url: pick_artwork_url(&artist.thumbnails, ""),
        artists: credits,
        ..Default::default()
    }
}

fn album_entry(album: SearchResultAlbum) -> SearchEntry {
    let id = album.album_id.get_raw().to_string();
    let artists = menu_metadata::cached_artists("album", &id)
        .filter(|c| !c.is_empty())
        .unwrap_or_else(|| menu_metadata::split_fallback_artists(&album.artist));
    SearchEntry {
        kind: "Album".into(),
        id: id.clone(),
        album_id: id,
        album: album.title.clone(),
        subtitle: album.artist.clone(),
        title: album.title,
        artist: album.artist,
        year: album.year,
        thumbnail_url: pick_thumbnail_url(&album.thumbnails, ""),
        artwork_url: pick_artwork_url(&album.thumbnails, ""),
        artists,
        ..Default::default()
    }
}

fn featured_playlist_entry(playlist: SearchResultFeaturedPlaylist) -> SearchEntry {
    SearchEntry {
        kind: "Playlist".into(),
        id: playlist.playlist_id.get_raw().into(),
        title: playlist.title,
        subtitle: playlist.author,
        thumbnail_url: pick_thumbnail_url(&playlist.thumbnails, ""),
        artwork_url: pick_artwork_url(&playlist.thumbnails, ""),
        ..Default::default()
    }
}

fn community_playlist_entry(playlist: SearchResultCommunityPlaylist) -> SearchEntry {
    SearchEntry {
        kind: "Playlist".into(),
        id: playlist.playlist_id.get_raw().into(),
        title: playlist.title,
        subtitle: playlist.author,
        thumbnail_url: pick_thumbnail_url(&playlist.thumbnails, ""),
        artwork_url: pick_artwork_url(&playlist.thumbnails, ""),
        ..Default::default()
    }
}

fn deduplicate_entries(entries: &mut Vec<SearchEntry>) {
    let mut seen = HashSet::new();
    entries.retain(|entry| !entry.id.trim().is_empty() && seen.insert(entry.id.clone()));
}

fn unfiltered_page(results: SearchResults) -> SearchPage {
    let mut page = SearchPage {
        songs: results.songs.into_iter().map(song_entry).collect(),
        artists: results.artists.into_iter().map(artist_entry).collect(),
        albums: results.albums.into_iter().map(album_entry).collect(),
        playlists: results
            .featured_playlists
            .into_iter()
            .map(featured_playlist_entry)
            .chain(
                results
                    .community_playlists
                    .into_iter()
                    .filter_map(|playlist| match playlist {
                        BasicSearchResultCommunityPlaylist::Playlist(playlist) => {
                            Some(community_playlist_entry(playlist))
                        }
                        _ => None,
                    }),
            )
            .collect(),
        ..Default::default()
    };
    deduplicate_entries(&mut page.songs);
    deduplicate_entries(&mut page.artists);
    deduplicate_entries(&mut page.albums);
    deduplicate_entries(&mut page.playlists);
    page
}

fn page_entries(page: &SearchPage) -> impl Iterator<Item = &SearchEntry> {
    page.artists
        .iter()
        .chain(&page.albums)
        .chain(&page.songs)
        .chain(&page.playlists)
}

fn endpoint_id<'id>(kind: &str, id: &'id str) -> &'id str {
    if kind == "Playlist" {
        id.strip_prefix("VL").unwrap_or(id)
    } else {
        id
    }
}

fn unique_match<'entry>(candidates: impl Iterator<Item = &'entry SearchEntry>) -> Resolution {
    let mut identities = HashSet::new();
    let mut result = None;
    for entry in candidates.filter(|entry| !entry.id.trim().is_empty()) {
        if identities.insert((&entry.kind, endpoint_id(&entry.kind, &entry.id))) {
            if result.is_some() {
                return Resolution::Ambiguous;
            }
            result = Some(entry.clone());
        }
    }
    result
        .map(Resolution::Unique)
        .unwrap_or(Resolution::Missing)
}

fn matches_identity(identity: &TopIdentity, entry: &SearchEntry) -> bool {
    if entry.kind != identity.kind {
        return false;
    }
    if !identity.id.is_empty() {
        return endpoint_id(&entry.kind, &entry.id) == endpoint_id(&identity.kind, &identity.id);
    }
    !normalize_ws(&identity.title).is_empty()
        && normalize_ws(&entry.title) == normalize_ws(&identity.title)
        && (identity.artist.trim().is_empty()
            || normalize_ws(&entry.artist) == normalize_ws(&identity.artist))
        && (identity.album.trim().is_empty()
            || normalize_ws(&entry.album) == normalize_ws(&identity.album))
        && (identity.year.trim().is_empty()
            || normalize_ws(&entry.year) == normalize_ws(&identity.year))
}

fn resolve_identity(identity: &TopIdentity, page: &SearchPage) -> Resolution {
    unique_match(page_entries(page).filter(|entry| matches_identity(identity, entry)))
}

fn provider_entry(identity: &TopIdentity) -> Option<SearchEntry> {
    if identity.id.trim().is_empty()
        || identity.title.trim().is_empty()
        || !matches!(identity.kind.as_str(), "Artist" | "Album" | "Playlist")
    {
        return None;
    }
    let artists = if identity.kind == "Artist" {
        vec![ArtistCredit {
            name: identity.title.clone(),
            browse_id: identity.id.clone(),
        }]
    } else if !identity.structured_artists.is_empty() {
        identity.structured_artists.clone()
    } else {
        menu_metadata::split_fallback_artists(&identity.artist)
    };
    Some(SearchEntry {
        kind: identity.kind.clone(),
        id: identity.id.clone(),
        title: identity.title.clone(),
        subtitle: identity.subtitle.clone(),
        thumbnail_url: identity.thumbnail_url.clone(),
        artwork_url: identity.artwork_url.clone(),
        artist: if identity.kind == "Artist" {
            identity.title.clone()
        } else {
            identity.artist.clone()
        },
        artist_id: if identity.kind == "Artist" {
            identity.id.clone()
        } else {
            artists
                .iter()
                .find(|a| !a.browse_id.is_empty())
                .map(|a| a.browse_id.clone())
                .unwrap_or_default()
        },
        album: if identity.kind == "Album" {
            identity.title.clone()
        } else {
            identity.album.clone()
        },
        album_id: if identity.kind == "Album" {
            identity.id.clone()
        } else {
            String::new()
        },
        year: identity.year.clone(),
        artists,
        ..Default::default()
    })
}

fn song_title_clean(value: &str) -> String {
    let value = value.trim();
    for (opening, closing) in [('(', ')'), ('[', ']')] {
        if let Some(body) = value.strip_suffix(closing) {
            if let Some((title, suffix)) = body.rsplit_once(opening) {
                if matches!(
                    normalize_ws(suffix).as_str(),
                    "official video"
                        | "official music video"
                        | "official audio"
                        | "music video"
                        | "audio"
                ) {
                    return normalize_ws(title);
                }
            }
        }
    }
    normalize_ws(value)
}

fn has_artist_credit(credits: &str, artist: &str) -> bool {
    let credits = normalize_ws(credits);
    let artist = normalize_ws(artist);
    !artist.is_empty()
        && (credits == artist
            || credits
                .split(',')
                .flat_map(|credit| credit.split(" & "))
                .any(|credit| credit.trim() == artist))
}

fn resolve_music_video(
    identity: &TopIdentity,
    page: &SearchPage,
    query: &str,
) -> Option<SearchEntry> {
    if !identity.music_video {
        return None;
    }
    let songs = || {
        page.songs
            .iter()
            .filter(|song| song.kind == "Song" && !song.id.trim().is_empty())
    };
    if let Resolution::Unique(song) = unique_match(songs().filter(|song| song.id == identity.id)) {
        return Some(song);
    }
    let provider_title = song_title_clean(&identity.title);
    let artist = normalize_ws(&identity.artist);
    let query_title = song_title_clean(query);
    let query = normalize_ws(query);
    if !artist.is_empty() {
        let artist_credits: Vec<_> = if identity.artist_credits.is_empty() {
            vec![artist]
        } else {
            identity
                .artist_credits
                .iter()
                .map(|a| normalize_ws(a))
                .collect()
        };
        if let Some(song) = songs().find(|song| {
            let title = song_title_clean(&song.title);
            !title.is_empty()
                && artist_credits.iter().any(|artist| {
                    has_artist_credit(&song.artist, artist)
                        && (query == format!("{title} {artist}")
                            || query == format!("{artist} {title}"))
                })
        }) {
            return Some(song.clone());
        }
        let matching: Vec<_> = songs()
            .filter(|song| {
                let title = song_title_clean(&song.title);
                !title.is_empty()
                    && (title == provider_title || title == query_title)
                    && artist_credits
                        .iter()
                        .any(|artist| has_artist_credit(&song.artist, artist))
            })
            .collect();
        let artists: HashSet<_> = matching
            .iter()
            .map(|song| normalize_ws(&song.artist))
            .collect();
        return if artists.len() == 1 {
            matching.first().map(|song| (*song).clone())
        } else {
            None
        };
    }
    if query_title.is_empty() {
        return None;
    }
    match unique_match(songs().filter(|song| song_title_clean(&song.title) == query_title)) {
        Resolution::Unique(song) => Some(song),
        _ => None,
    }
}

fn resolve_top(
    identity: Option<&TopIdentity>,
    page: &SearchPage,
    query: &str,
) -> Option<SearchEntry> {
    if let Some(identity) = identity {
        if identity.kind == "Video" {
            if !identity.id.is_empty() || identity.music_video {
                return resolve_music_video(identity, page, query);
            }
        } else {
            match resolve_identity(identity, page) {
                Resolution::Unique(entry) => return Some(entry),
                Resolution::Ambiguous => return None,
                Resolution::Missing => {}
            }
            if let Some(entry) = provider_entry(identity) {
                return Some(entry);
            }
        }
    }
    let query = normalize_ws(query);
    if query.is_empty() {
        return None;
    }
    match unique_match(page_entries(page).filter(|entry| normalize_ws(&entry.title) == query)) {
        Resolution::Unique(entry) => {
            let contradicts_top = identity
                .map(|identity| {
                    identity.kind == entry.kind
                        && normalize_ws(&identity.title) == normalize_ws(&entry.title)
                        && !matches_identity(identity, &entry)
                })
                .unwrap_or(false);
            if contradicts_top {
                None
            } else {
                Some(entry)
            }
        }
        _ => None,
    }
}

fn fallback_top_entry(page: &SearchPage, query: &str) -> Option<SearchEntry> {
    let nq = normalize_ws(query);
    if !nq.is_empty() {
        if let Some(artist) = page
            .artists
            .iter()
            .find(|a| !a.id.is_empty() && normalize_ws(&a.title) == nq)
        {
            return Some(artist.clone());
        }
        if let Some(album) = page
            .albums
            .iter()
            .find(|a| !a.id.is_empty() && normalize_ws(&a.title) == nq)
        {
            return Some(album.clone());
        }
    }
    page.songs
        .iter()
        .find(|s| !s.id.trim().is_empty())
        .cloned()
        .or_else(|| page.artists.first().cloned())
        .or_else(|| page.albums.first().cloned())
        .or_else(|| page.playlists.first().cloned())
}

fn entry_to_top_json(entry: &SearchEntry) -> serde_json::Value {
    let kind_lower = entry.kind.to_ascii_lowercase();
    let video_id = if kind_lower == "song" {
        entry.id.as_str()
    } else {
        ""
    };
    let artist_id = if kind_lower == "artist" {
        entry.id.as_str()
    } else {
        entry.artist_id.as_str()
    };
    let album_id = if kind_lower == "album" {
        entry.id.as_str()
    } else {
        entry.album_id.as_str()
    };
    serde_json::json!({
        "kind": kind_lower,
        "id": entry.id,
        "video_id": video_id,
        "title": entry.title,
        "subtitle": entry.subtitle,
        "artist": entry.artist,
        "album": entry.album,
        "album_id": album_id,
        "artist_id": artist_id,
        "duration_secs": entry.duration_secs,
        "thumbnail_url": entry.thumbnail_url,
        "artwork_url": entry.artwork_url,
        "year": entry.year,
        "artists": entry.artists,
    })
}

fn entry_to_track_dto(e: SearchEntry) -> TrackDto {
    TrackDto {
        video_id: e.id,
        title: e.title,
        artist: e.artist,
        album: e.album,
        album_id: e.album_id,
        artist_id: e.artist_id,
        duration_secs: e.duration_secs,
        thumbnail_url: e.thumbnail_url,
        artwork_url: e.artwork_url,
        artists: e.artists,
    }
}

fn entry_to_album_dto(e: SearchEntry) -> BrowseCardDto {
    BrowseCardDto {
        id: e.id,
        kind: "album".to_string(),
        item_type: "album".to_string(),
        title: e.title,
        subtitle: if e.artist.is_empty() {
            e.subtitle
        } else {
            e.artist
        },
        thumbnail_url: e.thumbnail_url,
        year: e.year,
        artists: e.artists,
    }
}

fn entry_to_artist_dto(e: SearchEntry) -> ArtistCardDto {
    ArtistCardDto {
        id: e.id,
        name: e.title,
        subscribers: e.subtitle,
        thumbnail_url: e.thumbnail_url,
    }
}

fn entry_to_playlist_dto(e: SearchEntry) -> BrowseCardDto {
    BrowseCardDto {
        id: e.id,
        kind: "playlist".to_string(),
        item_type: "playlist".to_string(),
        title: e.title,
        subtitle: e.subtitle,
        thumbnail_url: e.thumbnail_url,
        year: String::new(),
        artists: Vec::new(),
    }
}

fn filter_search_entries(page: &mut SearchPage, query: &str) {
    let allow_compilations = release_rank::query_requests_compilation(query);
    let qkey = release_rank::title_key(query);

    page.songs.retain(|s| {
        if s.id.trim().is_empty() {
            return false;
        }
        if s.duration_secs > 20 * 60 {
            return false;
        }
        if !allow_compilations
            && release_rank::title_key(&s.title) != qkey
            && release_rank::is_junk_track_or_compilation(&s.title, &s.artist)
        {
            return false;
        }
        true
    });

    page.albums.retain(|a| {
        if a.id.trim().is_empty() {
            return false;
        }
        if !allow_compilations
            && release_rank::title_key(&a.title) != qkey
            && release_rank::is_junk_track_or_compilation(&a.title, &a.artist)
        {
            return false;
        }
        true
    });

    deduplicate_entries(&mut page.songs);
    deduplicate_entries(&mut page.albums);
    deduplicate_entries(&mut page.artists);
    deduplicate_entries(&mut page.playlists);
}

async fn fetch_filtered_songs(
    api: &YtMusic<NoAuthToken>,
    query: &str,
) -> Result<Vec<SearchEntry>, String> {
    api.query(SearchQuery::new(query).with_filter(SongsFilter))
        .await
        .map(|res| res.into_iter().map(song_entry).collect())
        .map_err(|e| e.to_string())
}

async fn fetch_filtered_albums(
    api: &YtMusic<NoAuthToken>,
    query: &str,
) -> Result<Vec<SearchEntry>, String> {
    api.query(SearchQuery::new(query).with_filter(AlbumsFilter))
        .await
        .map(|res| res.into_iter().map(album_entry).collect())
        .map_err(|e| e.to_string())
}

async fn fetch_filtered_artists(
    api: &YtMusic<NoAuthToken>,
    query: &str,
) -> Result<Vec<SearchEntry>, String> {
    api.query(SearchQuery::new(query).with_filter(ArtistsFilter))
        .await
        .map(|res| res.into_iter().map(artist_entry).collect())
        .map_err(|e| e.to_string())
}

async fn fetch_filtered_playlists(
    api: &YtMusic<NoAuthToken>,
    query: &str,
) -> Result<Vec<SearchEntry>, String> {
    api.query(SearchQuery::new(query).with_filter(PlaylistsFilter))
        .await
        .map(|res| {
            res.into_iter()
                .filter_map(|p| match p {
                    SearchResultPlaylist::Featured(f) => Some(featured_playlist_entry(f)),
                    SearchResultPlaylist::Community(c) => Some(community_playlist_entry(c)),
                    _ => None,
                })
                .collect()
        })
        .map_err(|e| e.to_string())
}

async fn search_unfiltered_all(
    api: &YtMusic<NoAuthToken>,
    query: &str,
    allow_correction: bool,
) -> Result<(SearchPage, Option<String>), String> {
    let raw = api
        .json_query(SearchQuery::new(query).with_spelling_mode(if allow_correction {
            SpellingMode::WithSuggestions
        } else {
            SpellingMode::ExactMatch
        }))
        .await
        .map_err(|e| e.to_string())?;
    let json = serde_json::to_value(raw).map_err(|e| e.to_string())?;
    let correction = provider_correction(query, &json, allow_correction);
    if let Some((effective, false)) = &correction {
        return Ok((SearchPage::default(), Some(effective.clone())));
    }
    let effective_q = correction
        .as_ref()
        .map(|(effective, _)| effective.as_str())
        .unwrap_or(query);
    let (results, identity) = parse_unfiltered_results(effective_q, json)?;
    let mut page = unfiltered_page(results);
    page.corrected_query = correction
        .as_ref()
        .map(|(effective, _)| effective.clone())
        .unwrap_or_default();

    let need_songs = page.songs.len() < 8
        || (page.songs.is_empty()
            && identity
                .as_ref()
                .is_some_and(|id| id.kind == "Video" && id.music_video));
    let need_albums = page.albums.is_empty()
        || identity
            .as_ref()
            .is_some_and(|id| id.kind == "Album" && id.id.is_empty());
    let need_artists = page.artists.is_empty()
        || identity
            .as_ref()
            .is_some_and(|id| id.kind == "Artist" && id.id.is_empty());
    let need_playlists = page.playlists.is_empty();

    // Supplement sparse sections concurrently in a single round-trip.
    let (s_res, al_res, ar_res, pl_res) = tokio::join!(
        async {
            if need_songs {
                fetch_filtered_songs(api, effective_q).await.ok()
            } else {
                None
            }
        },
        async {
            if need_albums {
                fetch_filtered_albums(api, effective_q).await.ok()
            } else {
                None
            }
        },
        async {
            if need_artists {
                fetch_filtered_artists(api, effective_q).await.ok()
            } else {
                None
            }
        },
        async {
            if need_playlists {
                fetch_filtered_playlists(api, effective_q).await.ok()
            } else {
                None
            }
        },
    );

    if let Some(more_songs) = s_res {
        page.songs.extend(more_songs);
    }
    if let Some(more_albums) = al_res {
        page.albums.extend(more_albums);
    }
    if let Some(more_artists) = ar_res {
        page.artists.extend(more_artists);
    }
    if let Some(more_playlists) = pl_res {
        page.playlists.extend(more_playlists);
    }

    filter_search_entries(&mut page, effective_q);

    page.top = resolve_top(identity.as_ref(), &page, effective_q)
        .or_else(|| fallback_top_entry(&page, effective_q));

    Ok((page, None))
}

pub async fn search(query: &str, category: &str) -> SearchResponse {
    let trimmed_q = query.trim();
    if trimmed_q.is_empty() {
        return SearchResponse {
            ok: true,
            ..Default::default()
        };
    }

    let cat = category.trim().to_ascii_lowercase();
    let cat_norm = match cat.as_str() {
        "songs" | "albums" | "artists" | "playlists" => cat,
        _ => "all".to_string(),
    };
    let norm_q = normalize_ws(trimmed_q);

    if let Some(cached) = search_cache_get(&norm_q, &cat_norm) {
        return cached;
    }

    let api = match ytmusic().await {
        Ok(a) => a,
        Err(e) => {
            return SearchResponse {
                ok: false,
                error: Some(format!("Failed to connect to YTM: {e}")),
                ..Default::default()
            };
        }
    };

    let mut page = SearchPage::default();

    if cat_norm == "all" {
        match search_unfiltered_all(&api, trimmed_q, true).await {
            Ok((first_page, retry_query)) => {
                if let Some(effective) = retry_query {
                    match search_unfiltered_all(&api, &effective, false).await {
                        Ok((mut corrected_page, _)) => {
                            corrected_page.corrected_query = effective;
                            page = corrected_page;
                        }
                        Err(e) => {
                            maybe_invalidate_on_err(&e).await;
                            return SearchResponse {
                                ok: false,
                                error: Some(format!("Search retry failed: {e}")),
                                ..Default::default()
                            };
                        }
                    }
                } else {
                    page = first_page;
                    let has_any = page.top.is_some()
                        || !page.songs.is_empty()
                        || !page.artists.is_empty()
                        || !page.albums.is_empty()
                        || !page.playlists.is_empty();
                    if !has_any && page.corrected_query.is_empty() {
                        if let Ok(suggs) = search_suggestions(trimmed_q).await {
                            if let Some(effective) = closest_correction(trimmed_q, &suggs) {
                                if let Ok((mut corrected_page, _)) =
                                    search_unfiltered_all(&api, &effective, false).await
                                {
                                    if corrected_page.top.is_some()
                                        || !corrected_page.songs.is_empty()
                                        || !corrected_page.artists.is_empty()
                                    {
                                        corrected_page.corrected_query = effective;
                                        page = corrected_page;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Err(e) => {
                // Fallback to parallel filtered queries if unfiltered parsing ever fails.
                maybe_invalidate_on_err(&e).await;
                let (s_res, al_res, ar_res, pl_res) = tokio::join!(
                    fetch_filtered_songs(&api, trimmed_q),
                    fetch_filtered_albums(&api, trimmed_q),
                    fetch_filtered_artists(&api, trimmed_q),
                    fetch_filtered_playlists(&api, trimmed_q),
                );
                if let (Err(e1), Err(e2), Err(e3), Err(e4)) = (&s_res, &al_res, &ar_res, &pl_res) {
                    return SearchResponse {
                        ok: false,
                        error: Some(format!("Search query failed: {e1}; {e2}; {e3}; {e4}")),
                        ..Default::default()
                    };
                }
                page.songs = s_res.unwrap_or_default();
                page.albums = al_res.unwrap_or_default();
                page.artists = ar_res.unwrap_or_default();
                page.playlists = pl_res.unwrap_or_default();
                filter_search_entries(&mut page, trimmed_q);
                page.top = fallback_top_entry(&page, trimmed_q);
            }
        }
    } else {
        let res = match cat_norm.as_str() {
            "songs" => fetch_filtered_songs(&api, trimmed_q).await.map(|v| {
                page.songs = v;
            }),
            "albums" => fetch_filtered_albums(&api, trimmed_q).await.map(|v| {
                page.albums = v;
            }),
            "artists" => fetch_filtered_artists(&api, trimmed_q).await.map(|v| {
                page.artists = v;
            }),
            "playlists" => fetch_filtered_playlists(&api, trimmed_q).await.map(|v| {
                page.playlists = v;
            }),
            _ => Ok(()),
        };
        if let Err(e) = res {
            maybe_invalidate_on_err(&e).await;
            return SearchResponse {
                ok: false,
                error: Some(format!("Search query failed: {e}")),
                ..Default::default()
            };
        }
        filter_search_entries(&mut page, trimmed_q);
        page.top = fallback_top_entry(&page, trimmed_q);
    }

    let response = SearchResponse {
        ok: true,
        corrected_query: page.corrected_query,
        top: page.top.as_ref().map(entry_to_top_json),
        songs: page.songs.into_iter().map(entry_to_track_dto).collect(),
        albums: page.albums.into_iter().map(entry_to_album_dto).collect(),
        artists: page.artists.into_iter().map(entry_to_artist_dto).collect(),
        playlists: page
            .playlists
            .into_iter()
            .map(entry_to_playlist_dto)
            .collect(),
        error: None,
    };

    search_cache_put(norm_q, cat_norm, response.clone());
    response
}

fn suggest_http_client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .http2_max_header_list_size(64 * 1024)
            .connect_timeout(Duration::from_secs(6))
            .timeout(Duration::from_secs(8))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new())
    })
}

async fn fetch_suggestions_direct(query: &str) -> Result<Vec<String>, String> {
    let body = serde_json::json!({
        "input": query,
        "context": {
            "client": {
                "clientName": "WEB_REMIX",
                "clientVersion": "1.20240101.01.00"
            }
        }
    });
    let resp = suggest_http_client()
        .post("https://music.youtube.com/youtubei/v1/music/get_search_suggestions")
        .header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36",
        )
        .json(&body)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("HTTP {}", resp.status()));
    }
    let val: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    let mut seen = HashSet::new();
    if let Some(sections) = val.get("contents").and_then(|c| c.as_array()) {
        for section in sections {
            if let Some(items) = section
                .pointer("/searchSuggestionsSectionRenderer/contents")
                .and_then(|c| c.as_array())
            {
                for item in items {
                    if let Some(runs) = item
                        .pointer("/searchSuggestionRenderer/suggestion/runs")
                        .and_then(|r| r.as_array())
                    {
                        let text: String = runs
                            .iter()
                            .filter_map(|r| r.get("text").and_then(|t| t.as_str()))
                            .collect();
                        let trimmed = text.trim().to_string();
                        if !trimmed.is_empty() && seen.insert(trimmed.to_lowercase()) {
                            out.push(trimmed);
                            if out.len() >= 10 {
                                return Ok(out);
                            }
                        }
                    }
                }
            }
        }
    }
    Ok(out)
}

pub async fn search_suggestions(query: &str) -> Result<Vec<String>, String> {
    let trimmed_q = query.trim();
    if trimmed_q.is_empty() {
        return Ok(Vec::new());
    }
    if let Ok(direct) = fetch_suggestions_direct(trimmed_q).await {
        if !direct.is_empty() {
            return Ok(direct);
        }
    }
    let api = ytmusic().await.map_err(|e| e.to_string())?;
    let suggestions = api
        .query(GetSearchSuggestionsQuery::new(trimmed_q))
        .await
        .map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    for s in suggestions {
        let text: String = s.runs.into_iter().map(|r| r.take_text()).collect();
        let trimmed = text.trim().to_string();
        if !trimmed.is_empty() {
            out.push(trimmed);
        }
    }
    Ok(out)
}

pub async fn get_watch_playlist(video_id: &str) -> Result<Vec<TrackDto>, String> {
    let api = ytmusic().await.map_err(|e| e.to_string())?;
    let res = api
        .get_watch_playlist_from_video_id(VideoID::from_raw(video_id))
        .await
        .map_err(|e| e.to_string())?;

    let mut seen: HashSet<String> = HashSet::new();
    let mut tracks: Vec<TrackDto> = Vec::new();

    for r in res {
        let vid = r.video_id.get_raw().trim().to_string();
        if vid.is_empty() || !seen.insert(vid.clone()) {
            continue;
        }
        let duration_secs = parse_duration_secs(&r.duration);
        if duration_secs > 15 * 60 {
            continue;
        }
        if release_rank::is_junk_track_or_compilation(&r.title, &r.author) {
            continue;
        }
        let thumb = pick_thumbnail_url(&r.thumbnails, &vid);
        let artwork = pick_artwork_url(&r.thumbnails, &vid);
        let artists = menu_metadata::cached_artists("song", &vid)
            .filter(|c| !c.is_empty())
            .unwrap_or_else(|| menu_metadata::split_fallback_artists(&r.author));
        let artist_id = artists
            .iter()
            .find(|a| !a.browse_id.is_empty())
            .map(|a| a.browse_id.clone())
            .unwrap_or_default();
        tracks.push(TrackDto {
            video_id: vid,
            title: r.title,
            artist: r.author,
            album: String::new(),
            album_id: String::new(),
            artist_id,
            duration_secs,
            thumbnail_url: thumb,
            artwork_url: artwork,
            artists,
        });
        if tracks.len() >= 25 {
            break;
        }
    }

    Ok(tracks)
}

pub async fn get_album(browse_id: &str) -> Result<serde_json::Value, String> {
    let api = ytmusic().await.map_err(|e| e.to_string())?;
    let mut album: GetAlbum = match api
        .query(GetAlbumQuery::new(AlbumID::from_raw(browse_id)))
        .await
    {
        Ok(a) => a,
        Err(e) => {
            let msg = e.to_string();
            maybe_invalidate_on_err(&msg).await;
            return Err(msg);
        }
    };

    album.tracks.sort_by_key(|t| t.track_no);
    let artist_names = credit_names(&album.artists);
    let artist_id = first_artist_id(&album.artists);
    let album_credits = menu_metadata::parsed_credits(&album.artists);
    menu_metadata::register_artists("album", browse_id, album_credits.clone());

    let thumb = pick_thumbnail_url(&album.thumbnails, "");
    let artwork = pick_artwork_url(&album.thumbnails, "");

    let tracks: Vec<TrackDto> = album
        .tracks
        .into_iter()
        .map(|t| {
            let vid = t.video_id.get_raw().to_string();
            let track_thumb = if thumb.is_empty() {
                pick_thumbnail_url(&[], &vid)
            } else {
                thumb.clone()
            };
            let track_art = if artwork.is_empty() {
                pick_artwork_url(&[], &vid)
            } else {
                artwork.clone()
            };
            if !album_credits.is_empty() {
                menu_metadata::register_artists("song", &vid, album_credits.clone());
            }
            TrackDto {
                video_id: vid,
                title: t.title,
                artist: artist_names.clone(),
                album: album.title.clone(),
                album_id: browse_id.to_string(),
                artist_id: artist_id.clone(),
                duration_secs: parse_duration_secs(&t.duration),
                thumbnail_url: track_thumb,
                artwork_url: track_art,
                artists: album_credits.clone(),
            }
        })
        .collect();

    Ok(serde_json::json!({
        "ok": true,
        "id": browse_id,
        "title": album.title,
        "artist": artist_names,
        "artist_id": artist_id,
        "artists": album_credits,
        "year": album.year,
        "thumbnail_url": thumb,
        "artwork_url": artwork,
        "tracks": tracks
    }))
}

fn extract_best_thumb_from_json(val: &serde_json::Value) -> Option<String> {
    let arr = val
        .pointer("/thumbnail/musicThumbnailRenderer/thumbnail/thumbnails")
        .and_then(serde_json::Value::as_array)?;
    arr.iter()
        .filter_map(|t| {
            let url = t.get("url")?.as_str()?.trim();
            if url.is_empty() {
                return None;
            }
            let w = t.get("width").and_then(serde_json::Value::as_u64).unwrap_or(0);
            let h = t.get("height").and_then(serde_json::Value::as_u64).unwrap_or(0);
            Some((w.max(h), url.to_string()))
        })
        .max_by_key(|(dim, _)| *dim)
        .map(|(_, url)| url)
}

fn collect_list_item_thumbs(val: &serde_json::Value, map: &mut HashMap<String, String>) {
    match val {
        serde_json::Value::Object(obj) => {
            if let Some(row) = obj.get("musicResponsiveListItemRenderer") {
                let vid = row
                    .pointer("/playlistItemData/videoId")
                    .and_then(serde_json::Value::as_str)
                    .or_else(|| {
                        row.pointer(
                            "/flexColumns/0/musicResponsiveListItemFlexColumnRenderer/text/runs/0/navigationEndpoint/watchEndpoint/videoId",
                        )
                        .and_then(serde_json::Value::as_str)
                    })
                    .or_else(|| {
                        row.pointer(
                            "/overlay/musicItemThumbnailOverlayRenderer/content/musicPlayButtonRenderer/playNavigationEndpoint/watchEndpoint/videoId",
                        )
                        .and_then(serde_json::Value::as_str)
                    });
                if let Some(vid) = vid.filter(|v| !v.trim().is_empty()) {
                    if let Some(url) = extract_best_thumb_from_json(row) {
                        map.entry(vid.trim().to_string()).or_insert(url);
                    }
                }
            }
            for child in obj.values() {
                collect_list_item_thumbs(child, map);
            }
        }
        serde_json::Value::Array(arr) => {
            for child in arr {
                collect_list_item_thumbs(child, map);
            }
        }
        _ => {}
    }
}

pub async fn get_artist(browse_id: &str) -> Result<serde_json::Value, String> {
    let api = ytmusic().await.map_err(|e| e.to_string())?;

    // Fetch raw JSON so we can extract crisp square thumbnails from `musicResponsiveListItemRenderer`
    // for top songs instead of falling back to low-res `mqdefault.jpg`.
    let raw = match api
        .json_query(GetArtistQuery::new(ArtistChannelID::from_raw(browse_id)))
        .await
    {
        Ok(r) => r,
        Err(e) => {
            let msg = e.to_string();
            maybe_invalidate_on_err(&msg).await;
            return Err(msg);
        }
    };
    let json_val = serde_json::to_value(raw).map_err(|e| e.to_string())?;

    let mut song_thumb_map: HashMap<String, String> = HashMap::new();
    collect_list_item_thumbs(&json_val, &mut song_thumb_map);

    let artist_query = GetArtistQuery::new(ArtistChannelID::from_raw(browse_id));
    let artist: GetArtist = ytmapi_rs::parse::ProcessedResult {
        query: &artist_query,
        source: String::new(),
        json: serde_json::from_value(json_val).map_err(|e| e.to_string())?,
    }
    .parse_into::<GetArtist>()
    .map_err(|e| e.to_string())?;

    let thumb = pick_thumbnail_url(&artist.thumbnails, "");
    let artwork = pick_artwork_url(&artist.thumbnails, "");

    let self_credit = vec![ArtistCredit {
        name: artist.name.clone(),
        browse_id: browse_id.to_string(),
    }];
    menu_metadata::register_artists("artist", browse_id, self_credit.clone());

    // Build album/single thumbnail lookup & release_rank candidates.
    let mut album_thumb_map: HashMap<String, String> = HashMap::new();
    let mut raw_candidates: Vec<release_rank::Candidate> = Vec::new();

    for album in artist
        .top_releases
        .albums
        .into_iter()
        .flat_map(|a| a.results)
    {
        let id = album.album_id.get_raw().trim().to_string();
        if id.is_empty() {
            continue;
        }
        if let Some(best) = album
            .thumbnails
            .iter()
            .max_by_key(|t| t.width.max(t.height))
            .filter(|t| !t.url.trim().is_empty())
        {
            album_thumb_map
                .entry(id.clone())
                .or_insert_with(|| best.url.clone());
        }
        raw_candidates.push(release_rank::Candidate {
            id,
            title: album.title,
            year: album.year,
            from_albums: true,
            from_singles: false,
        });
    }

    for single in artist
        .top_releases
        .singles
        .into_iter()
        .flat_map(|s| s.results)
    {
        let id = single.album_id.get_raw().trim().to_string();
        if id.is_empty() {
            continue;
        }
        if let Some(best) = single
            .thumbnails
            .iter()
            .max_by_key(|t| t.width.max(t.height))
            .filter(|t| !t.url.trim().is_empty())
        {
            album_thumb_map
                .entry(id.clone())
                .or_insert_with(|| best.url.clone());
        }
        raw_candidates.push(release_rank::Candidate {
            id,
            title: single.title,
            year: single.year,
            from_albums: false,
            from_singles: true,
        });
    }

    // Filter top_songs: drop junk/compilations and duplicates, attach high-res thumbnails & credits.
    let mut seen_song_ids: HashSet<String> = HashSet::new();
    let mut top_songs: Vec<TrackDto> = Vec::new();
    for song in artist
        .top_releases
        .songs
        .into_iter()
        .flat_map(|s| s.results)
    {
        let vid = song.video_id.get_raw().trim().to_string();
        if vid.is_empty() || !seen_song_ids.insert(vid.clone()) {
            continue;
        }
        let artists_str = credit_names(&song.artists);
        if release_rank::is_junk_track_or_compilation(&song.title, &artists_str) {
            continue;
        }
        let album_id = song.album.id.get_raw().to_string();
        let raw_thumb = song_thumb_map
            .get(&vid)
            .or_else(|| album_thumb_map.get(&album_id))
            .map(String::as_str);

        let (song_thumb, song_art) = match raw_thumb {
            Some(url) if !url.trim().is_empty() => (
                upgrade_thumbnail_url(url, 544),
                upgrade_thumbnail_url(url, 1080),
            ),
            _ => (
                format!("https://i.ytimg.com/vi/{vid}/hqdefault.jpg"),
                format!("https://i.ytimg.com/vi/{vid}/hqdefault.jpg"),
            ),
        };

        let mut credits = menu_metadata::parsed_credits(&song.artists);
        if credits.is_empty() {
            credits = self_credit.clone();
        }
        menu_metadata::register_artists("song", &vid, credits.clone());

        let primary_artist_id = first_artist_id(&song.artists);
        let artist_id = if primary_artist_id.is_empty() {
            browse_id.to_string()
        } else {
            primary_artist_id
        };

        top_songs.push(TrackDto {
            video_id: vid,
            title: song.title,
            artist: if artists_str.is_empty() {
                artist.name.clone()
            } else {
                artists_str
            },
            album: song.album.name,
            album_id,
            artist_id,
            duration_secs: 0,
            thumbnail_url: song_thumb,
            artwork_url: song_art,
            artists: credits,
        });
    }

    // Dedupe, classify (dropping compilations!), and rank albums & singles by top_songs popularity.
    let deduped = release_rank::dedupe(raw_candidates);
    let mut album_cands: Vec<release_rank::Candidate> = Vec::new();
    let mut single_cands: Vec<release_rank::Candidate> = Vec::new();
    for cand in deduped {
        match release_rank::classify(&cand, &artist.name, None) {
            release_rank::ReleaseKind::Album => album_cands.push(cand),
            release_rank::ReleaseKind::Single => single_cands.push(cand),
            release_rank::ReleaseKind::Compilation => {}
        }
    }

    let top_album_ids: Vec<String> = top_songs.iter().map(|s| s.album_id.clone()).collect();
    let sr = release_rank::song_ranks(&top_album_ids);
    let empty_plays: HashMap<String, u64> = HashMap::new();

    let to_card = |c: &release_rank::Candidate, item_type: &str| -> BrowseCardDto {
        let card_thumb = album_thumb_map
            .get(&c.id)
            .map(|u| upgrade_thumbnail_url(u, 544))
            .unwrap_or_default();
        let subtitle = if c.year.trim().is_empty() {
            artist.name.clone()
        } else {
            format!("{} • {}", artist.name, c.year)
        };
        BrowseCardDto {
            id: c.id.clone(),
            kind: "album".to_string(),
            item_type: item_type.to_string(),
            title: c.title.clone(),
            subtitle,
            thumbnail_url: card_thumb,
            year: c.year.clone(),
            artists: self_credit.clone(),
        }
    };

    let albums: Vec<BrowseCardDto> = release_rank::rank(&album_cands, &sr, &empty_plays)
        .into_iter()
        .map(|c| to_card(c, "album"))
        .collect();

    let singles: Vec<BrowseCardDto> = release_rank::rank(&single_cands, &sr, &empty_plays)
        .into_iter()
        .map(|c| to_card(c, "single"))
        .collect();

    Ok(serde_json::json!({
        "ok": true,
        "id": browse_id,
        "name": artist.name,
        "subscribers": artist.subscribers.unwrap_or_default(),
        "description": artist.description.unwrap_or_default(),
        "thumbnail_url": thumb,
        "artwork_url": artwork,
        "top_songs": top_songs,
        "albums": albums,
        "singles": singles
    }))
}

pub async fn get_playlist(playlist_id: &str) -> Result<serde_json::Value, String> {
    let api = ytmusic().await.map_err(|e| e.to_string())?;
    let (details_res, items_res) = tokio::join!(
        api.get_playlist_details(PlaylistID::from_raw(playlist_id)),
        api.get_playlist_tracks(PlaylistID::from_raw(playlist_id))
    );
    let items = match items_res {
        Ok(i) => i,
        Err(e) => {
            let msg = e.to_string();
            maybe_invalidate_on_err(&msg).await;
            return Err(msg);
        }
    };

    let mut tracks: Vec<TrackDto> = Vec::new();
    for item in items {
        match item {
            PlaylistItem::Song(track) => {
                let vid = track.video_id.get_raw().to_string();
                let track_thumb = pick_thumbnail_url(&track.thumbnails, &vid);
                let track_art = pick_artwork_url(&track.thumbnails, &vid);
                let credits = menu_metadata::parsed_credits(&track.artists);
                if !credits.is_empty() {
                    menu_metadata::register_artists("song", &vid, credits.clone());
                }
                tracks.push(TrackDto {
                    video_id: vid,
                    title: track.title,
                    artist: credit_names(&track.artists),
                    album: track.album.name,
                    album_id: track.album.id.get_raw().to_string(),
                    artist_id: first_artist_id(&track.artists),
                    duration_secs: parse_duration_secs(&track.duration),
                    thumbnail_url: track_thumb,
                    artwork_url: track_art,
                    artists: credits,
                });
            }
            PlaylistItem::Video(v) => {
                let vid = v.video_id.get_raw().to_string();
                let track_thumb = pick_thumbnail_url(&v.thumbnails, &vid);
                let track_art = pick_artwork_url(&v.thumbnails, &vid);
                let ch_id = v.channel_id.get_raw().to_string();
                let credits = if !v.channel_name.trim().is_empty() {
                    let c = vec![ArtistCredit {
                        name: v.channel_name.clone(),
                        browse_id: ch_id.clone(),
                    }];
                    menu_metadata::register_artists("song", &vid, c.clone());
                    c
                } else {
                    Vec::new()
                };
                tracks.push(TrackDto {
                    video_id: vid,
                    title: v.title,
                    artist: v.channel_name,
                    album: String::new(),
                    album_id: String::new(),
                    artist_id: ch_id,
                    duration_secs: parse_duration_secs(&v.duration),
                    thumbnail_url: track_thumb,
                    artwork_url: track_art,
                    artists: credits,
                });
            }
            _ => {}
        }
    }

    let (title, author, description, details_thumb, details_art) = match details_res {
        Ok(d) => {
            let t = if d.title.trim().is_empty() {
                "Playlist".to_string()
            } else {
                d.title
            };
            let a = if d.author.trim().is_empty() {
                "YouTube Music".to_string()
            } else {
                d.author
            };
            let desc = d.description.unwrap_or_default();
            let th = pick_thumbnail_url(&d.thumbnails, "");
            let art = pick_artwork_url(&d.thumbnails, "");
            (t, a, desc, th, art)
        }
        Err(_) => (
            "Playlist".to_string(),
            "YouTube Music".to_string(),
            String::new(),
            String::new(),
            String::new(),
        ),
    };

    let thumb = if !details_thumb.is_empty() {
        details_thumb
    } else {
        tracks
            .first()
            .map(|t| t.thumbnail_url.clone())
            .unwrap_or_default()
    };
    let artwork = if !details_art.is_empty() {
        details_art
    } else {
        tracks
            .first()
            .map(|t| t.artwork_url.clone())
            .unwrap_or_default()
    };
    let track_count = tracks.len() as u32;

    Ok(serde_json::json!({
        "ok": true,
        "id": playlist_id,
        "title": title,
        "author": author,
        "description": description,
        "thumbnail_url": thumb,
        "artwork_url": artwork,
        "track_count": track_count,
        "tracks": tracks
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn upgrade_thumbnail_url_rewrites_google_and_ytimg_sizes() {
        assert_eq!(
            upgrade_thumbnail_url(
                "https://lh3.googleusercontent.com/abc123=w60-h60-l90-rj",
                544
            ),
            "https://lh3.googleusercontent.com/abc123=w544-h544-l90-rj"
        );
        assert_eq!(
            upgrade_thumbnail_url(
                "https://yt3.ggpht.com/xyz789=s120-c-k-c0x00ffffff-no-rj",
                1080
            ),
            "https://yt3.ggpht.com/xyz789=w1080-h1080-l90-rj"
        );
        assert_eq!(
            upgrade_thumbnail_url("https://lh3.googleusercontent.com/bare_token", 544),
            "https://lh3.googleusercontent.com/bare_token=w544-h544-l90-rj"
        );
        assert_eq!(
            upgrade_thumbnail_url("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", 544),
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        );
        assert_eq!(
            upgrade_thumbnail_url(
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg?sqp=signed",
                1080
            ),
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/sddefault.jpg"
        );
        assert_eq!(upgrade_thumbnail_url("", 544), "");
    }

    #[test]
    fn pick_thumbnail_and_artwork_fallback_to_hqdefault_not_mqdefault() {
        let thumb = pick_thumbnail_url(&[], "abc123XYZ00");
        assert_eq!(thumb, "https://i.ytimg.com/vi/abc123XYZ00/hqdefault.jpg");
        let art = pick_artwork_url(&[], "abc123XYZ00");
        assert_eq!(art, "https://i.ytimg.com/vi/abc123XYZ00/hqdefault.jpg");
    }

    #[test]
    fn provider_correction_handles_showing_results_and_did_you_mean() {
        let dym = serde_json::json!({
            "contents": [{
                "didYouMeanRenderer": {
                    "navigationEndpoint": { "searchEndpoint": { "query": "arijit singh" } }
                }
            }]
        });
        assert_eq!(
            provider_correction("arihit singh", &dym, true),
            Some(("arijit singh".into(), false))
        );
        assert_eq!(provider_correction("arihit singh", &dym, false), None);

        let srf = serde_json::json!({
            "showingResultsForRenderer": {
                "correctedQueryEndpoint": { "searchEndpoint": { "query": "arijit singh" } }
            }
        });
        assert_eq!(
            provider_correction("arihit singh", &srf, true),
            Some(("arijit singh".into(), true))
        );
    }

    #[test]
    fn top_json_emits_lowercase_kind_for_artist_and_song() {
        let artist_entry = SearchEntry {
            kind: "Artist".into(),
            id: "UCarijit".into(),
            title: "Arijit Singh".into(),
            subtitle: "10M monthly audience".into(),
            artist: "Arijit Singh".into(),
            artist_id: "UCarijit".into(),
            thumbnail_url: "https://lh3.googleusercontent.com/a=w544-h544-l90-rj".into(),
            artwork_url: "https://lh3.googleusercontent.com/a=w1080-h1080-l90-rj".into(),
            artists: vec![ArtistCredit {
                name: "Arijit Singh".into(),
                browse_id: "UCarijit".into(),
            }],
            ..Default::default()
        };
        let v = entry_to_top_json(&artist_entry);
        assert_eq!(v["kind"], "artist");
        assert_eq!(v["id"], "UCarijit");
        assert_eq!(v["artist_id"], "UCarijit");
        assert_eq!(v["video_id"], "");
        assert_eq!(v["artwork_url"], "https://lh3.googleusercontent.com/a=w1080-h1080-l90-rj");
    }
}
