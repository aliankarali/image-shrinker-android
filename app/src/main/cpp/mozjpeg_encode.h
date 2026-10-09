#ifndef SHRINKER_MOZJPEG_ENCODE_H
#define SHRINKER_MOZJPEG_ENCODE_H

#include <stddef.h>

/*
 * Encodes 8-bit RGBX pixels (alpha ignored) as a JPEG with mozjpeg, set up
 * exactly as the macOS app's `cjpeg [-quality N]` is.
 *
 * quality < 0 means "no -quality flag": mozjpeg's own default (75), which is
 * what the desktop app's Standard level passes.
 *
 * On success returns 0 and hands back a malloc'd buffer in *out that the
 * caller frees. On failure returns -1 with a message in error.
 */
int shrinker_mozjpeg_encode(const unsigned char *pixels, int width, int height, size_t stride,
                            int quality, const unsigned char *icc, unsigned int icc_length,
                            unsigned char **out, unsigned long *out_length,
                            char *error, size_t error_size);

#endif
