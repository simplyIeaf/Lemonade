use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr::null_mut;

fn normalize_bytes(input: &[u8]) -> Result<Vec<u8>, String> {
    let dom = rbx_binary::from_reader(input).map_err(|e| format!("rbx-dom could not read the place: {e}"))?;
    let mut out = Vec::with_capacity(input.len());
    rbx_binary::to_writer(&mut out, &dom, dom.root().children()).map_err(|e| format!("rbx-dom could not write the place: {e}"))?;
    Ok(out)
}

fn run(env: &mut JNIEnv, input: &JByteArray) -> Result<Vec<u8>, String> {
    let bytes = env.convert_byte_array(input).map_err(|e| e.to_string())?;
    match catch_unwind(AssertUnwindSafe(|| normalize_bytes(&bytes))) {
        Ok(r) => r,
        Err(_) => Err("rbx-dom crashed while writing the place".to_string()),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_leaf_lemonade_format_RbxDomNative_normalize<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    input: JByteArray<'l>,
) -> jbyteArray {
    match run(&mut env, &input) {
        Ok(bytes) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => null_mut(),
        },
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            null_mut()
        }
    }
}

fn property_type(class: &str, prop: &str) -> Option<String> {
    let db = rbx_reflection_database::get().unwrap_or_else(|_| rbx_reflection_database::get_bundled());
    let c = db.classes.get(class)?;
    let p = db.superclasses_iter(c).find_map(|c| c.properties.get(prop))?;
    match &p.data_type {
        rbx_reflection::DataType::Value(t) => Some(format!("{:?}", t)),
        _ => None,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_leaf_lemonade_format_RbxDomNative_propertyType<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    class_name: JString<'l>,
    prop_name: JString<'l>,
) -> jstring {
    let class: String = match env.get_string(&class_name) {
        Ok(s) => s.into(),
        Err(_) => return null_mut(),
    };
    let prop: String = match env.get_string(&prop_name) {
        Ok(s) => s.into(),
        Err(_) => return null_mut(),
    };
    let found = catch_unwind(AssertUnwindSafe(|| property_type(&class, &prop))).ok().flatten();
    match found {
        Some(t) => env.new_string(t).map(|s| s.into_raw()).unwrap_or(null_mut()),
        None => null_mut(),
    }
}
