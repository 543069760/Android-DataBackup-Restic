use std::collections::HashMap;

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JObject, JObjectArray, JString};
use jni::sys::jboolean;

use crate::error::NativeError;
use crate::jni_progress::JniProgressCallback;
use crate::repository::{
    check_repository, create_snapshot, create_snapshot_with_progress, forget_snapshot,
    get_version, init_repository, list_snapshots_db, prune_repository, repository_exists,
    restore_snapshot, validate_repository,
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xayah_libnative_Rustic_nativeInitLogger<'local>(
    _unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xayah_libnative_Rustic_nativeGetVersion<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
) -> JString<'local> {
    unowned_env
        .with_env(|env| -> Result<JString<'local>, NativeError> {
            let version = get_version().map_err(NativeError::from)?;
            env.new_string(version).map_err(NativeError::from)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xayah_libnative_Rustic_nativeInitRepository<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    repository_path: JString<'local>,
    password: JString<'local>,
    option_keys: JObjectArray<'local, JString<'local>>,
    option_values: JObjectArray<'local, JString<'local>>,
) {
    unowned_env
        .with_env(|env| -> Result<(), NativeError> {
            let options = string_arrays_to_map(env, &option_keys, &option_values)?;
            init_repository(&repository_path.to_string(), &password.to_string(), &options)
                .map_err(NativeError::from)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xayah_libnative_Rustic_nativeRepositoryExists<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    repository_path: JString<'local>,
    option_keys: JObjectArray<'local, JString<'local>>,
    option_values: JObjectArray<'local, JString<'local>>,
) -> jboolean {
    unowned_env
        .with_env(|env| -> Result<jboolean, NativeError> {
            let options = string_arrays_to_map(env, &option_keys, &option_values)?;
            repository_exists(&repository_path.to_string(), &options)
                .map(jboolean::from)
                .map_err(NativeError::from)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xayah_libnative_Rustic_nativeValidateRepository<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    repository_path: JString<'local>,
    password: JString<'local>,
    option_keys: JObjectArray<'local, JString<'local>>,
    option_values: JObjectArray<'local, JString<'local>>,
) {
    unowned_env
        .with_env(|env| -> Result<(), NativeError> {
            let options = string_arrays_to_map(env, &option_keys, &option_values)?;
            validate_repository(&repository_path.to_string(), &password.to_string(), &options)
                .map_err(NativeError::from)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xayah_libnative_Rustic_nativeCreateSnapshot<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    repository_path: JString<'local>,
    password: JString<'local>,
    option_keys: JObjectArray<'local, JString<'local>>,
    option_values: JObjectArray<'local, JString<'local>>,
    source_paths: JObjectArray<'local, JString<'local>>,
    tags: JObjectArray<'local, JString<'local>>,
    callback: JObject<'local>,
) -> JString<'local> {
    unowned_env
        .with_env(|env| -> Result<JString<'local>, NativeError> {
            let options = string_arrays_to_map(env, &option_keys, &option_values)?;
            let source_paths = string_array_to_vec(env, &source_paths)?;
            let tags = string_array_to_vec(env, &tags)?;
            let repository_path = repository_path.to_string();
            let password = password.to_string();
            let snapshot_id = if callback.as_raw().is_null() {
                create_snapshot(&repository_path, &password, &source_paths, &tags, &options)
                    .map_err(NativeError::from)?
            } else {
                let vm = env.get_java_vm()?;
                let callback = env.new_global_ref(&callback)?;
                let callback = JniProgressCallback::new(env, vm, callback)?;
                create_snapshot_with_progress(
                    &repository_path,
                    &password,
                    &source_paths,
                    &tags,
                    &options,
                    callback,
                )
                .map_err(NativeError::from)?
            };

            env.new_string(snapshot_id).map_err(NativeError::from)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xayah_libnative_Rustic_nativeRestoreSnapshot<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    repository_path: JString<'local>,
    password: JString<'local>,
    option_keys: JObjectArray<'local, JString<'local>>,
    option_values: JObjectArray<'local, JString<'local>>,
    snapshot_id: JString<'local>,
    destination_path: JString<'local>,
) {
    unowned_env
        .with_env(|env| -> Result<(), NativeError> {
            let options = string_arrays_to_map(env, &option_keys, &option_values)?;
            restore_snapshot(
                &repository_path.to_string(),
                &password.to_string(),
                &snapshot_id.to_string(),
                &destination_path.to_string(),
                &options,
            )
            .map_err(NativeError::from)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xayah_libnative_Rustic_nativeCheckRepository<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    repository_path: JString<'local>,
    password: JString<'local>,
    option_keys: JObjectArray<'local, JString<'local>>,
    option_values: JObjectArray<'local, JString<'local>>,
) {
    unowned_env
        .with_env(|env| -> Result<(), NativeError> {
            let options = string_arrays_to_map(env, &option_keys, &option_values)?;
            check_repository(&repository_path.to_string(), &password.to_string(), &options)
                .map_err(NativeError::from)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xayah_libnative_Rustic_nativeForgetSnapshot<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    repository_path: JString<'local>,
    password: JString<'local>,
    option_keys: JObjectArray<'local, JString<'local>>,
    option_values: JObjectArray<'local, JString<'local>>,
    snapshot_id: JString<'local>,
) {
    unowned_env
        .with_env(|env| -> Result<(), NativeError> {
            let options = string_arrays_to_map(env, &option_keys, &option_values)?;
            forget_snapshot(
                &repository_path.to_string(),
                &password.to_string(),
                &options,
                &snapshot_id.to_string(),
            )
            .map_err(NativeError::from)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xayah_libnative_Rustic_nativePruneRepository<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    repository_path: JString<'local>,
    password: JString<'local>,
    option_keys: JObjectArray<'local, JString<'local>>,
    option_values: JObjectArray<'local, JString<'local>>,
    max_unused: JString<'local>,
) {
    unowned_env
        .with_env(|env| -> Result<(), NativeError> {
            let options = string_arrays_to_map(env, &option_keys, &option_values)?;
            prune_repository(
                &repository_path.to_string(),
                &password.to_string(),
                &options,
                &max_unused.to_string(),
            )
            .map_err(NativeError::from)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xayah_libnative_Rustic_nativeListSnapshotsDb<'local>(
    mut unowned_env: EnvUnowned<'local>,
    _this: JObject<'local>,
    repository_path: JString<'local>,
    password: JString<'local>,
    option_keys: JObjectArray<'local, JString<'local>>,
    option_values: JObjectArray<'local, JString<'local>>,
    db_path: JString<'local>,
) {
    unowned_env
        .with_env(|env| -> Result<(), NativeError> {
            let options = string_arrays_to_map(env, &option_keys, &option_values)?;
            list_snapshots_db(
                &repository_path.to_string(),
                &password.to_string(),
                &options,
                &db_path.to_string(),
            )
            .map_err(NativeError::from)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn string_array_to_vec<'local>(
    env: &mut jni::Env<'local>,
    array: &JObjectArray<'local, JString<'local>>,
) -> Result<Vec<String>, NativeError> {
    (0..array.len(env)?)
        .map(|index| {
            let value: JString<'local> = array.get_element(env, index)?;
            Ok(value.to_string())
        })
        .collect()
}

fn string_arrays_to_map<'local>(
    env: &mut jni::Env<'local>,
    keys: &JObjectArray<'local, JString<'local>>,
    values: &JObjectArray<'local, JString<'local>>,
) -> Result<HashMap<String, String>, NativeError> {
    let keys = string_array_to_vec(env, keys)?;
    let values = string_array_to_vec(env, values)?;
    Ok(keys.into_iter().zip(values).collect())
}