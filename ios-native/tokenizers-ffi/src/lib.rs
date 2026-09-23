//! C ABI over Hugging Face `tokenizers` for Loupe on iOS. See include/loupe_tokenizers.h.
//!
//! Mirrors backend-onnx's `HuggingFaceSubwordEncoder`: `encode(text, add_special_tokens=false)`
//! with truncation and padding switched off on the loaded tokenizer, so a tokenizer.json that
//! declares either cannot silently change a Laya sequence.

use std::ffi::{c_char, CStr, CString};
use std::ptr;
use tokenizers::Tokenizer;

pub struct LoupeTokenizer {
    inner: Tokenizer,
}

unsafe fn set_error(error: *mut *mut c_char, message: String) {
    if !error.is_null() {
        let clean = message.replace('\0', " ");
        *error = CString::new(clean).map(CString::into_raw).unwrap_or(ptr::null_mut());
    }
}

#[no_mangle]
pub unsafe extern "C" fn loupe_tok_from_file(path: *const c_char, error: *mut *mut c_char) -> *mut LoupeTokenizer {
    if path.is_null() {
        set_error(error, "path is null".into());
        return ptr::null_mut();
    }
    let path = match CStr::from_ptr(path).to_str() {
        Ok(p) => p,
        Err(e) => {
            set_error(error, format!("path is not UTF-8: {e}"));
            return ptr::null_mut();
        }
    };
    match Tokenizer::from_file(path) {
        Ok(mut tokenizer) => {
            if let Err(e) = tokenizer.with_truncation(None) {
                set_error(error, format!("could not disable truncation: {e}"));
                return ptr::null_mut();
            }
            tokenizer.with_padding(None);
            Box::into_raw(Box::new(LoupeTokenizer { inner: tokenizer }))
        }
        Err(e) => {
            set_error(error, format!("could not load {path}: {e}"));
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn loupe_tok_encode(
    tok: *const LoupeTokenizer,
    text: *const u8,
    len: usize,
    ids: *mut *mut i64,
    ids_len: *mut usize,
    error: *mut *mut c_char,
) -> i32 {
    if tok.is_null() || ids.is_null() || ids_len.is_null() || (text.is_null() && len != 0) {
        set_error(error, "null argument".into());
        return 1;
    }
    let bytes = if len == 0 { &[][..] } else { std::slice::from_raw_parts(text, len) };
    let text = match std::str::from_utf8(bytes) {
        Ok(t) => t,
        Err(e) => {
            set_error(error, format!("text is not UTF-8: {e}"));
            return 2;
        }
    };
    match (*tok).inner.encode(text, false) {
        Ok(encoding) => {
            let out: Box<[i64]> = encoding.get_ids().iter().map(|&id| id as i64).collect();
            *ids_len = out.len();
            *ids = Box::into_raw(out) as *mut i64;
            0
        }
        Err(e) => {
            set_error(error, format!("encode failed: {e}"));
            3
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn loupe_tok_ids_free(ids: *mut i64, ids_len: usize) {
    if !ids.is_null() {
        drop(Box::from_raw(ptr::slice_from_raw_parts_mut(ids, ids_len)));
    }
}

#[no_mangle]
pub unsafe extern "C" fn loupe_tok_string_free(s: *mut c_char) {
    if !s.is_null() {
        drop(CString::from_raw(s));
    }
}

#[no_mangle]
pub unsafe extern "C" fn loupe_tok_free(tok: *mut LoupeTokenizer) {
    if !tok.is_null() {
        drop(Box::from_raw(tok));
    }
}
