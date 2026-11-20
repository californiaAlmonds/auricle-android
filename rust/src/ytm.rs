//! YouTube Music API queries and DTOs for Android native shell.

use serde::{Deserialize, Serialize};
use ytmapi_rs::common::{AlbumID, ArtistChannelID, PlaylistID, Thumbnail, VideoID, YoutubeID};
use ytmapi_rs::parse::{
    GetAlbum, GetArtist, ParsedSongArtist, PlaylistItem, SearchResultPlaylist, SearchResultSong,
};
use ytmapi_rs::query::search::{AlbumsFilter, ArtistsFilter, PlaylistsFilter, SongsFilter};
use ytmapi_rs::query::{
    GetAlbumQuery, GetArtistQuery, GetSearchSuggestionsQuery, SearchQuery,
};
use ytmapi_rs::YtMusic;

pub async fn ytmusic() -> ytmapi_rs::Result<YtMusic<ytmapi_rs::auth::noauth::NoAuthToken>> {
    let http = reqwest::Client::builder()
        .http2_max_header_list_size(64 * 1024)
        .build()?;
    let client = ytmapi_rs::client::Client::new_from_reqwest_client(http);
    ytmapi_rs::builder::YtMusicBuilder::new_with_client(client)
        .build()
        .await
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
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct BrowseCardDto {
    pub id: String,
    pub kind: String, // "album" | "playlist" | "artist"
    pub title: String,
    pub subtitle: String,
    pub thumbnail_url: String,
    pub year: String,
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
    let parts: Vec<&str> = d.split(':').collect();
    match parts.len() {
        2 => {
            let m = parts[0].trim().parse::<u32>().unwrap_or(0);
            let s = parts[1].trim().parse::<u32>().unwrap_or(0);
            m * 60 + s
        }
        3 => {
            let h = parts[0].trim().parse::<u32>().unwrap_or(0);
            let m = parts[1].trim().parse::<u32>().unwrap_or(0);
            let s = parts[2].trim().parse::<u32>().unwrap_or(0);
            h * 3600 + m * 60 + s
        }
        _ => 0,
    }
}

pub fn pick_thumbnail_url(thumbs: &[Thumbnail], video_id: &str) -> String {
    if let Some(t) = thumbs.iter().max_by_key(|t| t.width.max(t.height)) {
        if !t.url.is_empty() {
            return t.url.clone();
        }
    }
    if !video_id.is_empty() {
        format!("https://i.ytimg.com/vi/{video_id}/mqdefault.jpg")
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
    let (album, album_id) = match s.album {
        Some(a) => (a.name, a.id.get_raw().to_string()),
        None => (String::new(), String::new()),
    };
    TrackDto {
        video_id: vid,
        title: s.title,
        artist: s.artist,
        album,
        album_id,
        artist_id: String::new(),
        duration_secs: parse_duration_secs(&s.duration),
        thumbnail_url: thumb,
    }
}

pub async fn get_home_feed() -> HomeFeedResponse {
    let mut api = match ytmusic().await {
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

    let trending_res = api.query(SearchQuery::new("Top Hits").with_filter(SongsFilter)).await;
    let quick_res = api.query(SearchQuery::new("Trending Music").with_filter(SongsFilter)).await;
    let albums_res = api.query(SearchQuery::new("New Releases").with_filter(AlbumsFilter)).await;
    let playlists_res = api.query(SearchQuery::new("Top Playlists").with_filter(PlaylistsFilter)).await;

    let trending: Vec<TrackDto> = trending_res
        .unwrap_or_default()
        .into_iter()
        .take(16)
        .map(song_to_track_dto)
        .collect();

    let quick_picks: Vec<TrackDto> = quick_res
        .unwrap_or_default()
        .into_iter()
        .take(16)
        .map(song_to_track_dto)
        .collect();

    let mut new_releases: Vec<BrowseCardDto> = Vec::new();

    if let Ok(albums) = albums_res {
        for a in albums.into_iter().take(8) {
            let id = a.album_id.get_raw().to_string();
            let thumb = pick_thumbnail_url(&a.thumbnails, "");
            new_releases.push(BrowseCardDto {
                id,
                kind: "album".to_string(),
                title: a.title,
                subtitle: a.artist,
                thumbnail_url: thumb,
                year: a.year,
            });
        }
    }

    if let Ok(playlists) = playlists_res {
        for p in playlists.into_iter().take(6) {
            match p {
                SearchResultPlaylist::Featured(f) => {
                    let id = f.playlist_id.get_raw().to_string();
                    let thumb = pick_thumbnail_url(&f.thumbnails, "");
                    new_releases.push(BrowseCardDto {
                        id,
                        kind: "playlist".to_string(),
                        title: f.title,
                        subtitle: f.author,
                        thumbnail_url: thumb,
                        year: String::new(),
                    });
                }
                SearchResultPlaylist::Community(c) => {
                    let id = c.playlist_id.get_raw().to_string();
                    let thumb = pick_thumbnail_url(&c.thumbnails, "");
                    new_releases.push(BrowseCardDto {
                        id,
                        kind: "playlist".to_string(),
                        title: c.title,
                        subtitle: c.author,
                        thumbnail_url: thumb,
                        year: String::new(),
                    });
                }
                _ => {}
            }
        }
    }

    let hero = trending.first().cloned().or_else(|| quick_picks.first().cloned());

    HomeFeedResponse {
        ok: true,
        hero,
        trending,
        quick_picks,
        new_releases,
        error: None,
    }
}

pub async fn search(query: &str, category: &str) -> SearchResponse {
    let mut api = match ytmusic().await {
        Ok(a) => a,
        Err(e) => {
            return SearchResponse {
                ok: false,
                corrected_query: String::new(),
                top: None,
                songs: Vec::new(),
                albums: Vec::new(),
                artists: Vec::new(),
                playlists: Vec::new(),
                error: Some(format!("Failed to connect to YTM: {e}")),
            };
        }
    };

    let cat = category.to_ascii_lowercase();

    let mut songs = Vec::new();
    let mut albums = Vec::new();
    let mut artists = Vec::new();
    let mut playlists = Vec::new();

    if cat == "songs" || cat == "all" {
        if let Ok(results) = api.query(SearchQuery::new(query).with_filter(SongsFilter)).await {
            songs = results.into_iter().map(song_to_track_dto).collect();
        }
    }

    if cat == "albums" || cat == "all" {
        if let Ok(results) = api.query(SearchQuery::new(query).with_filter(AlbumsFilter)).await {
            albums = results
                .into_iter()
                .map(|a| {
                    let id = a.album_id.get_raw().to_string();
                    let thumb = pick_thumbnail_url(&a.thumbnails, "");
                    BrowseCardDto {
                        id,
                        kind: "album".to_string(),
                        title: a.title,
                        subtitle: a.artist,
                        thumbnail_url: thumb,
                        year: a.year,
                    }
                })
                .collect();
        }
    }

    if cat == "artists" || cat == "all" {
        if let Ok(results) = api.query(SearchQuery::new(query).with_filter(ArtistsFilter)).await {
            artists = results
                .into_iter()
                .map(|a| {
                    let id = a.browse_id.get_raw().to_string();
                    let thumb = pick_thumbnail_url(&a.thumbnails, "");
                    ArtistCardDto {
                        id,
                        name: a.artist,
                        subscribers: a.subscribers.unwrap_or_default(),
                        thumbnail_url: thumb,
                    }
                })
                .collect();
        }
    }

    if cat == "playlists" || cat == "all" {
        if let Ok(results) = api.query(SearchQuery::new(query).with_filter(PlaylistsFilter)).await {
            for p in results {
                match p {
                    SearchResultPlaylist::Featured(f) => {
                        let id = f.playlist_id.get_raw().to_string();
                        let thumb = pick_thumbnail_url(&f.thumbnails, "");
                        playlists.push(BrowseCardDto {
                            id,
                            kind: "playlist".to_string(),
                            title: f.title,
                            subtitle: f.author,
                            thumbnail_url: thumb,
                            year: String::new(),
                        });
                    }
                    SearchResultPlaylist::Community(c) => {
                        let id = c.playlist_id.get_raw().to_string();
                        let thumb = pick_thumbnail_url(&c.thumbnails, "");
                        playlists.push(BrowseCardDto {
                            id,
                            kind: "playlist".to_string(),
                            title: c.title,
                            subtitle: c.author,
                            thumbnail_url: thumb,
                            year: String::new(),
                        });
                    }
                    _ => {}
                }
            }
        }
    }

    let top = if let Some(s) = songs.first() {
        Some(serde_json::json!({
            "kind": "song",
            "id": s.video_id,
            "title": s.title,
            "subtitle": s.artist,
            "artist": s.artist,
            "duration_secs": s.duration_secs,
            "thumbnail_url": s.thumbnail_url
        }))
    } else if let Some(a) = artists.first() {
        Some(serde_json::json!({
            "kind": "artist",
            "id": a.id,
            "title": a.name,
            "subtitle": a.subscribers,
            "artist": a.name,
            "duration_secs": 0,
            "thumbnail_url": a.thumbnail_url
        }))
    } else if let Some(al) = albums.first() {
        Some(serde_json::json!({
            "kind": "album",
            "id": al.id,
            "title": al.title,
            "subtitle": al.subtitle,
            "artist": al.subtitle,
            "duration_secs": 0,
            "thumbnail_url": al.thumbnail_url
        }))
    } else {
        None
    };

    SearchResponse {
        ok: true,
        corrected_query: String::new(),
        top,
        songs,
        albums,
        artists,
        playlists,
        error: None,
    }
}

pub async fn search_suggestions(query: &str) -> Result<Vec<String>, String> {
    if query.trim().is_empty() {
        return Ok(Vec::new());
    }
    let api = ytmusic().await.map_err(|e| e.to_string())?;
    let suggestions = api
        .query(GetSearchSuggestionsQuery::new(query))
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

    let tracks: Vec<TrackDto> = res
        .into_iter()
        .take(20)
        .map(|r| {
            let vid = r.video_id.get_raw().to_string();
            let thumb = pick_thumbnail_url(&r.thumbnails, &vid);
            TrackDto {
                video_id: vid,
                title: r.title,
                artist: r.author,
                album: String::new(),
                album_id: String::new(),
                artist_id: String::new(),
                duration_secs: parse_duration_secs(&r.duration),
                thumbnail_url: thumb,
            }
        })
        .collect();

    Ok(tracks)
}

pub async fn get_album(browse_id: &str) -> Result<serde_json::Value, String> {
    let mut api = ytmusic().await.map_err(|e| e.to_string())?;
    let mut album: GetAlbum = api
        .query(GetAlbumQuery::new(AlbumID::from_raw(browse_id)))
        .await
        .map_err(|e| e.to_string())?;

    album.tracks.sort_by_key(|t| t.track_no);
    let artist_names = credit_names(&album.artists);
    let artist_id = first_artist_id(&album.artists);
    let thumb = pick_thumbnail_url(&album.thumbnails, "");

    let tracks: Vec<TrackDto> = album
        .tracks
        .into_iter()
        .map(|t| {
            let vid = t.video_id.get_raw().to_string();
            let track_thumb = if thumb.is_empty() {
                format!("https://i.ytimg.com/vi/{vid}/mqdefault.jpg")
            } else {
                thumb.clone()
            };
            TrackDto {
                video_id: vid,
                title: t.title,
                artist: artist_names.clone(),
                album: album.title.clone(),
                album_id: browse_id.to_string(),
                artist_id: artist_id.clone(),
                duration_secs: parse_duration_secs(&t.duration),
                thumbnail_url: track_thumb,
            }
        })
        .collect();

    Ok(serde_json::json!({
        "ok": true,
        "id": browse_id,
        "title": album.title,
        "artist": artist_names,
        "artist_id": artist_id,
        "year": album.year,
        "thumbnail_url": thumb,
        "tracks": tracks
    }))
}

pub async fn get_artist(browse_id: &str) -> Result<serde_json::Value, String> {
    let mut api = ytmusic().await.map_err(|e| e.to_string())?;
    let artist: GetArtist = api
        .query(GetArtistQuery::new(ArtistChannelID::from_raw(browse_id)))
        .await
        .map_err(|e| e.to_string())?;

    let thumb = pick_thumbnail_url(&artist.thumbnails, "");

    let top_songs: Vec<TrackDto> = artist
        .top_releases
        .songs
        .into_iter()
        .flat_map(|s| s.results)
        .map(|song| {
            let artists = credit_names(&song.artists);
            let vid = song.video_id.get_raw().to_string();
            TrackDto {
                video_id: vid.clone(),
                title: song.title,
                artist: artists,
                album: song.album.name,
                album_id: song.album.id.get_raw().to_string(),
                artist_id: first_artist_id(&song.artists),
                duration_secs: 0,
                thumbnail_url: format!("https://i.ytimg.com/vi/{vid}/mqdefault.jpg"),
            }
        })
        .collect();

    let albums: Vec<BrowseCardDto> = artist
        .top_releases
        .albums
        .into_iter()
        .flat_map(|a| a.results)
        .map(|album| {
            let id = album.album_id.get_raw().to_string();
            let album_thumb = pick_thumbnail_url(&album.thumbnails, "");
            BrowseCardDto {
                id,
                kind: "album".to_string(),
                title: album.title,
                subtitle: format!("{} • {}", artist.name, album.year),
                thumbnail_url: album_thumb,
                year: album.year,
            }
        })
        .collect();

    let singles: Vec<BrowseCardDto> = artist
        .top_releases
        .singles
        .into_iter()
        .flat_map(|s| s.results)
        .map(|single| {
            let id = single.album_id.get_raw().to_string();
            let single_thumb = pick_thumbnail_url(&single.thumbnails, "");
            BrowseCardDto {
                id,
                kind: "album".to_string(),
                title: single.title,
                subtitle: format!("{} • {}", artist.name, single.year),
                thumbnail_url: single_thumb,
                year: single.year,
            }
        })
        .collect();

    Ok(serde_json::json!({
        "ok": true,
        "id": browse_id,
        "name": artist.name,
        "subscribers": artist.subscribers.unwrap_or_default(),
        "description": artist.description.unwrap_or_default(),
        "thumbnail_url": thumb,
        "top_songs": top_songs,
        "albums": albums,
        "singles": singles
    }))
}

pub async fn get_playlist(playlist_id: &str) -> Result<serde_json::Value, String> {
    let api = ytmusic().await.map_err(|e| e.to_string())?;
    let items = api
        .get_playlist_tracks(PlaylistID::from_raw(playlist_id))
        .await
        .map_err(|e| e.to_string())?;

    let mut tracks: Vec<TrackDto> = Vec::new();
    for item in items {
        match item {
            PlaylistItem::Song(track) => {
                let vid = track.video_id.get_raw().to_string();
                let track_thumb = pick_thumbnail_url(&track.thumbnails, &vid);
                tracks.push(TrackDto {
                    video_id: vid,
                    title: track.title,
                    artist: credit_names(&track.artists),
                    album: track.album.name,
                    album_id: track.album.id.get_raw().to_string(),
                    artist_id: first_artist_id(&track.artists),
                    duration_secs: parse_duration_secs(&track.duration),
                    thumbnail_url: track_thumb,
                });
            }
            PlaylistItem::Video(v) => {
                let vid = v.video_id.get_raw().to_string();
                let track_thumb = pick_thumbnail_url(&v.thumbnails, &vid);
                tracks.push(TrackDto {
                    video_id: vid,
                    title: v.title,
                    artist: v.channel_name,
                    album: String::new(),
                    album_id: String::new(),
                    artist_id: v.channel_id.get_raw().to_string(),
                    duration_secs: parse_duration_secs(&v.duration),
                    thumbnail_url: track_thumb,
                });
            }
            _ => {}
        }
    }

    let thumb = tracks
        .first()
        .map(|t| t.thumbnail_url.clone())
        .unwrap_or_default();
    let track_count = tracks.len() as u32;

    Ok(serde_json::json!({
        "ok": true,
        "id": playlist_id,
        "title": "Playlist",
        "author": "YouTube Music",
        "description": "",
        "thumbnail_url": thumb,
        "track_count": track_count,
        "tracks": tracks
    }))
}
