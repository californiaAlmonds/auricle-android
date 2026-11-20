//! `auricle_core` cdylib: JNI bridge and runtime for Auricle Android.

pub mod lyrics;
pub mod party;
pub mod stream;
pub mod ytm;

use std::path::PathBuf;
use std::sync::OnceLock;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

static STORAGE_DIR: OnceLock<PathBuf> = OnceLock::new();
static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
static INIT_GUARD: OnceLock<()> = OnceLock::new();

pub fn storage_dir() -> Option<&'static PathBuf> {
    STORAGE_DIR.get()
}

pub fn rt() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .enable_all()
            .build()
            .expect("Failed to create shared tokio runtime")
    })
}

fn get_jstring(env: &mut JNIEnv, jstr: JString) -> String {
    if jstr.is_null() {
        return String::new();
    }
    env.get_string(&jstr)
        .map(|s| s.into())
        .unwrap_or_default()
}

fn to_jstring<'a>(env: &mut JNIEnv<'a>, s: &str) -> JString<'a> {
    env.new_string(s)
        .unwrap_or_else(|_| env.new_string("").expect("JNI empty string allocation"))
}

// ============================================================================
// JNI Exports for com.auricle.app.bridge.AuricleNative
// ============================================================================

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    context: JObject,
    storage_dir_jstr: JString,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        INIT_GUARD.get_or_init(|| {
            let path_str = get_jstring(&mut env, storage_dir_jstr);
            if !path_str.is_empty() {
                let p = PathBuf::from(path_str);
                let _ = std::fs::create_dir_all(&p);
                let _ = STORAGE_DIR.set(p);
            }

            // Initialize rustls-platform-verifier on Android via jni 0.22 raw handles
            #[cfg(target_os = "android")]
            unsafe {
                let raw_env = env.get_raw() as *mut jni22::sys::JNIEnv;
                let raw_ctx = context.as_raw() as jni22::sys::jobject;
                let mut unowned = jni22::EnvUnowned::from_raw(raw_env);
                let _ = unowned.with_env(|e22| {
                    let ctx22 = jni22::objects::JObject::from_raw(e22, raw_ctx);
                    rustls_platform_verifier::android::init_with_env(e22, ctx22)
                });
            }

            // Load existing stream URL disk cache
            stream::load_disk_cache();
        });
        JNI_TRUE
    }));

    match result {
        Ok(v) => v,
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativeGetHomeFeed<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
) -> JString<'a> {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let resp = rt().block_on(ytm::get_home_feed());
        serde_json::to_string(&resp).unwrap_or_else(|e| {
            serde_json::json!({ "ok": false, "error": e.to_string() }).to_string()
        })
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "ok": false, "error": "panic in nativeGetHomeFeed" }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativeSearch<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    query: JString,
    category: JString,
) -> JString<'a> {
    let q = get_jstring(&mut env, query);
    let cat = get_jstring(&mut env, category);

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let resp = rt().block_on(ytm::search(&q, &cat));
        serde_json::to_string(&resp).unwrap_or_else(|e| {
            serde_json::json!({ "ok": false, "error": e.to_string() }).to_string()
        })
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "ok": false, "error": "panic in nativeSearch" }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativeSearchSuggestions<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    query: JString,
) -> JString<'a> {
    let q = get_jstring(&mut env, query);

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        match rt().block_on(ytm::search_suggestions(&q)) {
            Ok(suggs) => serde_json::json!({ "ok": true, "suggestions": suggs }).to_string(),
            Err(e) => serde_json::json!({ "ok": false, "error": e, "suggestions": [] }).to_string(),
        }
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "ok": false, "error": "panic in nativeSearchSuggestions" }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativeGetWatchPlaylist<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    video_id: JString,
) -> JString<'a> {
    let vid = get_jstring(&mut env, video_id);

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        match rt().block_on(ytm::get_watch_playlist(&vid)) {
            Ok(tracks) => serde_json::json!({ "ok": true, "tracks": tracks }).to_string(),
            Err(e) => serde_json::json!({ "ok": false, "error": e, "tracks": [] }).to_string(),
        }
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "ok": false, "error": "panic in nativeGetWatchPlaylist" }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativeGetAlbum<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    browse_id: JString,
) -> JString<'a> {
    let id = get_jstring(&mut env, browse_id);

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        match rt().block_on(ytm::get_album(&id)) {
            Ok(val) => val.to_string(),
            Err(e) => serde_json::json!({ "ok": false, "error": e }).to_string(),
        }
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "ok": false, "error": "panic in nativeGetAlbum" }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativeGetArtist<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    browse_id: JString,
) -> JString<'a> {
    let id = get_jstring(&mut env, browse_id);

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        match rt().block_on(ytm::get_artist(&id)) {
            Ok(val) => val.to_string(),
            Err(e) => serde_json::json!({ "ok": false, "error": e }).to_string(),
        }
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "ok": false, "error": "panic in nativeGetArtist" }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativeGetPlaylist<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    playlist_id: JString,
) -> JString<'a> {
    let id = get_jstring(&mut env, playlist_id);

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        match rt().block_on(ytm::get_playlist(&id)) {
            Ok(val) => val.to_string(),
            Err(e) => serde_json::json!({ "ok": false, "error": e }).to_string(),
        }
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "ok": false, "error": "panic in nativeGetPlaylist" }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativeGetLyrics<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    video_id: JString,
    lang_mode: JString,
) -> JString<'a> {
    let vid = get_jstring(&mut env, video_id);
    let mode = get_jstring(&mut env, lang_mode);

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let resp = rt().block_on(lyrics::get_lyrics(&vid, &mode));
        serde_json::to_string(&resp).unwrap_or_else(|e| {
            serde_json::json!({ "ok": false, "error": e.to_string() }).to_string()
        })
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "ok": false, "error": "panic in nativeGetLyrics" }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativeResolveStream<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    video_id: JString,
    force_refresh: jboolean,
) -> JString<'a> {
    let vid = get_jstring(&mut env, video_id);
    let force = force_refresh == JNI_TRUE;

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let resp = rt().block_on(stream::resolve_stream(&vid, force));
        serde_json::to_string(&resp).unwrap_or_else(|e| {
            serde_json::json!({ "ok": false, "error": e.to_string() }).to_string()
        })
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "ok": false, "error": "panic in nativeResolveStream" }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativePartyStart<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    display_name: JString,
    initial_state_json: JString,
) -> JString<'a> {
    let dn = get_jstring(&mut env, display_name);
    let seed_str = get_jstring(&mut env, initial_state_json);

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let seed: party::PartySeedState = serde_json::from_str(&seed_str).unwrap_or_default();
        let snap = party::party_manager().start_party(dn, seed);
        serde_json::to_string(&snap).unwrap_or_else(|e| {
            serde_json::json!({ "status": "error", "is_active": false, "error_text": e.to_string() }).to_string()
        })
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "status": "error", "is_active": false, "error_text": "panic in nativePartyStart" }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativePartyJoin<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    invite_input: JString,
    display_name: JString,
) -> JString<'a> {
    let invite = get_jstring(&mut env, invite_input);
    let dn = get_jstring(&mut env, display_name);

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let snap = party::party_manager().join_party(invite, dn);
        serde_json::to_string(&snap).unwrap_or_else(|e| {
            serde_json::json!({ "status": "error", "is_active": false, "error_text": e.to_string() }).to_string()
        })
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "status": "error", "is_active": false, "error_text": "panic in nativePartyJoin" }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativePartyLeave<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
) -> JString<'a> {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let snap = party::party_manager().leave_party();
        serde_json::to_string(&snap).unwrap_or_else(|e| {
            serde_json::json!({ "status": "off", "is_active": false, "error_text": e.to_string() }).to_string()
        })
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "status": "off", "is_active": false, "error_text": "panic in nativePartyLeave" }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativePartyGetSnapshot<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
) -> JString<'a> {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let snap = party::party_manager().snapshot();
        serde_json::to_string(&snap).unwrap_or_else(|e| {
            serde_json::json!({ "status": "error", "is_active": false, "error_text": e.to_string() }).to_string()
        })
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "status": "error", "is_active": false, "error_text": "panic in nativePartyGetSnapshot" }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativePartyPollRemoteUpdate<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
) -> JString<'a> {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let upd = party::party_manager().poll_remote_update();
        serde_json::to_string(&upd).unwrap_or_else(|_| {
            serde_json::json!({ "has_update": false, "target_position_ms": 0 }).to_string()
        })
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "has_update": false, "target_position_ms": 0 }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativePartySendAction<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    action_json: JString,
) -> JString<'a> {
    let action_str = get_jstring(&mut env, action_json);

    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        match serde_json::from_str::<party::PartyAction>(&action_str) {
            Ok(action) => {
                party::party_manager().send_action(action, false);
                let snap = party::party_manager().snapshot();
                serde_json::to_string(&snap).unwrap_or_default()
            }
            Err(e) => serde_json::json!({ "ok": false, "error": format!("Invalid PartyAction JSON: {e}") }).to_string(),
        }
    }));

    let json_str = match result {
        Ok(s) => s,
        Err(_) => serde_json::json!({ "ok": false, "error": "panic in nativePartySendAction" }).to_string(),
    };
    to_jstring(&mut env, &json_str)
}

#[no_mangle]
pub extern "C" fn Java_com_auricle_app_bridge_AuricleNative_nativePartyGetTargetPositionMs(
    mut env: JNIEnv,
    _class: JClass,
    video_id: JString,
) -> jlong {
    let vid = get_jstring(&mut env, video_id);
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        party::party_manager()
            .target_position_ms(&vid)
            .map(|ms| ms as i64)
            .unwrap_or(-1i64)
    }));

    match result {
        Ok(v) => v,
        Err(_) => -1i64,
    }
}
