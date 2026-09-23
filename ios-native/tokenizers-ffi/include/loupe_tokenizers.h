/*
 * C ABI of ios-native/tokenizers-ffi (Hugging Face `tokenizers` 0.21.4, the version DJL 0.38.0
 * wraps). Encode only, with special tokens, truncation and padding OFF -- the same settings as
 * backend-onnx's HuggingFaceSubwordEncoder. No function here touches the network.
 */
#ifndef LOUPE_TOKENIZERS_H
#define LOUPE_TOKENIZERS_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct LoupeTokenizer LoupeTokenizer;

/*
 * Loads a tokenizer.json from a NUL-terminated UTF-8 path. Returns NULL on failure; when `error`
 * is non-NULL it then receives a message the caller frees with loupe_tok_string_free.
 */
LoupeTokenizer *loupe_tok_from_file(const char *path, char **error);

/*
 * Encodes `len` bytes of UTF-8 `text` without special tokens, truncation or padding. On success
 * returns 0 and stores a malloc'd-by-Rust array in *ids (free with loupe_tok_ids_free) and its
 * length in *ids_len. On failure returns non-zero and, when `error` is non-NULL, a message.
 */
int32_t loupe_tok_encode(const LoupeTokenizer *tok, const uint8_t *text, size_t len,
                         int64_t **ids, size_t *ids_len, char **error);

void loupe_tok_ids_free(int64_t *ids, size_t ids_len);
void loupe_tok_string_free(char *s);
void loupe_tok_free(LoupeTokenizer *tok);

#ifdef __cplusplus
}
#endif

#endif
