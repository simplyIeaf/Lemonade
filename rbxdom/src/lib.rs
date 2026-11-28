use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;
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
