use std::collections::HashMap;

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JObject, JObjectArray, JString};
use jni::signature::{Primitive, ReturnType};
use jni::sys::jboolean;
use jni::{JValue, jni_sig, jni_str};

use crate::error::NativeError;
use crate::jni_progress::JniProgressCallback;
use crate::repository::{
    check_repository, create_snapshot, create_snapshot_with_progress, forget_snapshot,
    get_version, init_repository, list_snapshots_db, prune_repository, repository_exists,
    restore_snapshot, restore_snapshot_with_progress, validate_repository,
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
    include_glob: JString<'local>,
    callback: JObject<'local>,
) {
    unowned_env
        .with_env(|env| -> Result<(), NativeError> {
            let options = string_arrays_to_map(env, &option_keys, &option_values)?;
            let repository_path = repository_path.to_string();
            let password = password.to_string();
            let snapshot_id = snapshot_id.to_string();
            let destination_path = destination_path.to_string();

            // 空字符串视为「不过滤」(None);非空则作为 include glob(!前缀语义在 native 侧处理)
            let include_glob_owned = include_glob.to_string();
            let include_glob = if include_glob_owned.is_empty() {
                None
            } else {
                Some(include_glob_owned.as_str())
            };

            if callback.as_raw().is_null() {
                // 无回调:走带 glob 的进度版但用 noop 回调,或退回旧的无统计版本。
                // 这里退回旧版(不回传 plan 统计),保持与无进度调用方兼容。
                restore_snapshot(
                    &repository_path,
                    &password,
                    &snapshot_id,
                    &destination_path,
                    &options,
                )
                .map_err(NativeError::from)?;
            } else {
                let vm = env.get_java_vm()?;
                let progress_callback = env.new_global_ref(&callback)?;
                let progress_callback = JniProgressCallback::new(env, vm, progress_callback)?;

                let stats = restore_snapshot_with_progress(
                    &repository_path,
                    &password,
                    &snapshot_id,
                    &destination_path,
                    &options,
                    include_glob,
                    progress_callback,
                )
                .map_err(NativeError::from)?;

                // 规划阶段统计一次性回传:ICallback.onRestorePlan(long,long,long,long)
                let callback_class = env.get_object_class(&callback)?;
                let on_restore_plan = env.get_method_id(
                    &callback_class,
                    jni_str!("onRestorePlan"),
                    jni_sig!("(JJJJ)V"),
                )?;
                let args = [
                    JValue::Long(stats.files_total as i64).as_jni(),
                    JValue::Long(stats.bytes_total as i64).as_jni(),
                    JValue::Long(stats.files_skipped as i64).as_jni(),
                    JValue::Long(stats.bytes_skipped as i64).as_jni(),
                ];
                // SAFETY: onRestorePlan 以精确签名 (JJJJ)V 解析,类型与实参一致。
                unsafe {
                    env.call_method_unchecked(
                        &callback,
                        on_restore_plan,
                        ReturnType::Primitive(Primitive::Void),
                        &args,
                    )?;
                }
            }

            Ok(())
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