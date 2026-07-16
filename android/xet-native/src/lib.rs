use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;
use std::ptr::null_mut;
use std::sync::Once;

use jni::JNIEnv;
use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::jstring;
use serde::Serialize;
use xet::xet_session::{HeaderMap, HeaderValue, Sha256Policy, XetSessionBuilder, header};

static XET_INIT: Once = Once::new();

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct NativeUploadResult {
    source_path: String,
    xet_hash: String,
    sha256: String,
    file_size: u64,
}

fn initialize_xet(cache_dir: &str) {
    let cache_dir = cache_dir.to_owned();
    XET_INIT.call_once(move || {
        // Xet reads its process-wide cache and logging configuration when the first
        // session is created. Android gives each application a private cache root.
        unsafe {
            std::env::set_var("HF_HUB_DISABLE_TELEMETRY", "1");
            std::env::set_var("HF_XET_HIGH_PERFORMANCE", "1");
            std::env::set_var("HF_HOME", &cache_dir);
            std::env::set_var("HF_XET_CACHE", format!("{cache_dir}/xet"));
            std::env::set_var("HF_XET_LOG_FILE", format!("{cache_dir}/xet-upload.log"));
            std::env::set_var("TMPDIR", format!("{cache_dir}/tmp"));
        }
    });
}

fn java_string(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String, String> {
    env.get_string(&value)
        .map(|text| text.into())
        .map_err(|error| format!("JNI metni okunamadı: {error}"))
}

fn java_string_array(env: &mut JNIEnv<'_>, values: JObjectArray<'_>) -> Result<Vec<String>, String> {
    let length = env
        .get_array_length(&values)
        .map_err(|error| format!("JNI dosya listesi okunamadı: {error}"))?;
    let mut result = Vec::with_capacity(length as usize);
    for index in 0..length {
        let object = env
            .get_object_array_element(&values, index)
            .map_err(|error| format!("JNI dosya yolu alınamadı: {error}"))?;
        result.push(java_string(env, JString::from(object))?);
    }
    Ok(result)
}

fn upload_with_xet(
    refresh_url: String,
    hub_token: String,
    cache_dir: String,
    paths: Vec<String>,
) -> Result<String, String> {
    if refresh_url.trim().is_empty() {
        return Err("Xet write-token yenileme adresi boş.".to_string());
    }
    if hub_token.trim().is_empty() {
        return Err("Hugging Face tokenı boş.".to_string());
    }
    if paths.is_empty() {
        return Ok("[]".to_string());
    }

    initialize_xet(&cache_dir);

    let mut refresh_headers = HeaderMap::new();
    refresh_headers.insert(
        header::AUTHORIZATION,
        HeaderValue::from_str(&format!("Bearer {hub_token}"))
            .map_err(|error| format!("Xet yetkilendirme başlığı oluşturulamadı: {error}"))?,
    );
    refresh_headers.insert(
        header::USER_AGENT,
        HeaderValue::from_static("hf-storage-android/0.1.2"),
    );

    // Hub Authorization must only be sent to the token refresh route. CAS calls
    // use the short-lived Xet token fetched by the official client.
    let mut cas_headers = HeaderMap::new();
    cas_headers.insert(
        header::USER_AGENT,
        HeaderValue::from_static("hf-storage-android-xet/0.1.2"),
    );

    let session = XetSessionBuilder::new()
        .build()
        .map_err(|error| format!("Xet oturumu oluşturulamadı: {error}"))?;
    let commit = session
        .new_upload_commit()
        .map_err(|error| format!("Xet upload commit'i başlatılamadı: {error}"))?
        .with_token_refresh_url(refresh_url, refresh_headers)
        .with_custom_headers(cas_headers)
        .build_blocking()
        .map_err(|error| format!("Xet write token alınamadı: {error}"))?;

    let mut handles = Vec::with_capacity(paths.len());
    for path in &paths {
        let handle = commit
            .upload_from_path_blocking(PathBuf::from(path), Sha256Policy::Compute)
            .map_err(|error| format!("Xet dosya hazırlığı başarısız ({path}): {error}"))?;
        handles.push(handle);
    }

    // Finalization is idempotent and gives results in the same order as the
    // supplied Android files. commit_blocking then sends all missing chunks and
    // manifests to CAS as one Xet upload commit.
    let mut results = Vec::with_capacity(handles.len());
    for (source_path, handle) in paths.iter().zip(handles.iter()) {
        let metadata = handle
            .finalize_ingestion_blocking()
            .map_err(|error| format!("Xet chunk işlemi başarısız ({source_path}): {error}"))?;
        let sha256 = metadata
            .xet_info
            .sha256
            .clone()
            .ok_or_else(|| format!("Xet SHA-256 üretmedi: {source_path}"))?;
        let file_size = metadata
            .xet_info
            .file_size
            .ok_or_else(|| format!("Xet dosya boyutunu döndürmedi: {source_path}"))?;
        results.push(NativeUploadResult {
            source_path: source_path.clone(),
            xet_hash: metadata.xet_info.hash.clone(),
            sha256,
            file_size,
        });
    }

    commit
        .commit_blocking()
        .map_err(|error| format!("Xet CAS commit'i tamamlanamadı: {error}"))?;

    serde_json::to_string(&results).map_err(|error| format!("Xet sonucu kodlanamadı: {error}"))
}

fn panic_message(payload: Box<dyn std::any::Any + Send>) -> String {
    if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_string()
    } else {
        "Bilinmeyen Rust/Xet paniği".to_string()
    }
}

/// rustls-platform-verifier must receive the Android application Context before
/// Xet creates any HTTPS client. The verifier stores the JVM, Context and class
/// loader globally and then safely uses Android's system TrustManager from Xet's
/// background Rust threads.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_apexlions_hfstorage_mobile_data_XetNative_nativeInitialize<'caller>(
    mut unowned_env: jni22::EnvUnowned<'caller>,
    _class: jni22::objects::JClass<'caller>,
    context: jni22::objects::JObject<'caller>,
) {
    use jni22::errors::ThrowRuntimeExAndDefault;

    unowned_env
        .with_env(|env| -> jni22::errors::Result<()> {
            rustls_platform_verifier::android::init_with_env(env, context)?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_apexlions_hfstorage_mobile_data_XetNative_nativeUploadFiles(
    mut env: JNIEnv,
    _class: JClass,
    refresh_url: JString,
    hub_token: JString,
    cache_dir: JString,
    paths: JObjectArray,
) -> jstring {
    let execution = catch_unwind(AssertUnwindSafe(|| {
        let refresh_url = java_string(&mut env, refresh_url)?;
        let hub_token = java_string(&mut env, hub_token)?;
        let cache_dir = java_string(&mut env, cache_dir)?;
        let paths = java_string_array(&mut env, paths)?;
        upload_with_xet(refresh_url, hub_token, cache_dir, paths)
    }));

    let result = match execution {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            return null_mut();
        }
        Err(payload) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!("Xet native bileşeni beklenmeyen biçimde durdu: {}", panic_message(payload)),
            );
            return null_mut();
        }
    };

    match env.new_string(result) {
        Ok(value) => value.into_raw(),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", format!("Xet sonucu Android'e aktarılamadı: {error}"));
            null_mut()
        }
    }
}
