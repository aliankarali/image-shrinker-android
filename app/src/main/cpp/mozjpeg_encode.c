#include "mozjpeg_encode.h"

#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "jpeglib.h"

struct error_manager {
    struct jpeg_error_mgr pub;
    jmp_buf jump;
    char message[JMSG_LENGTH_MAX];
};

static void on_error(j_common_ptr cinfo) {
    struct error_manager *err = (struct error_manager *) cinfo->err;
    (*cinfo->err->format_message)(cinfo, err->message);
    longjmp(err->jump, 1);
}

/* Warnings are not failures; cjpeg only prints them. */
static void on_message(j_common_ptr cinfo, int level) {
    (void) cinfo;
    (void) level;
}

/*
 * cjpeg's set_quality_ratings() (rdswitch.c) for a single integer value. In
 * the v6b API build, cjpeg scales its copy of the base tables by
 * (int) jpeg_float_quality_scaling(q), which for whole numbers equals the
 * library's jpeg_set_quality(q) with force_baseline off. Then — mozjpeg's
 * addition — less chroma subsampling at high qualities (4:2:2 from 80, 4:4:4
 * from 90).
 */
static void set_quality(j_compress_ptr cinfo, int quality) {
    jpeg_set_quality(cinfo, quality, FALSE);

    int h, v;
    if (quality >= 90) {
        h = 1; v = 1;
    } else if (quality >= 80) {
        h = 2; v = 1;
    } else {
        return;
    }
    cinfo->comp_info[0].h_samp_factor = h;
    cinfo->comp_info[0].v_samp_factor = v;
    for (int ci = 1; ci < cinfo->num_components; ci++) {
        cinfo->comp_info[ci].h_samp_factor = 1;
        cinfo->comp_info[ci].v_samp_factor = 1;
    }
}

/* Lives on the heap so its contents are well defined after a longjmp. */
struct destination {
    unsigned char *buffer;
    unsigned long length;
};

int shrinker_mozjpeg_encode(const unsigned char *pixels, int width, int height, size_t stride,
                            int quality, const unsigned char *icc, unsigned int icc_length,
                            unsigned char **out, unsigned long *out_length,
                            char *error, size_t error_size) {
    struct jpeg_compress_struct cinfo;
    struct error_manager err;
    struct destination *dest = calloc(1, sizeof(*dest));
    if (dest == NULL) {
        snprintf(error, error_size, "out of memory");
        return -1;
    }

    cinfo.err = jpeg_std_error(&err.pub);
    err.pub.error_exit = on_error;
    err.pub.emit_message = on_message;
    err.message[0] = '\0';

    if (setjmp(err.jump)) {
        jpeg_destroy_compress(&cinfo);
        free(dest->buffer);
        free(dest);
        snprintf(error, error_size, "%s", err.message[0] ? err.message : "mozjpeg failed");
        return -1;
    }

    jpeg_create_compress(&cinfo);
    jpeg_mem_dest(&cinfo, &dest->buffer, &dest->length);

    cinfo.image_width = (JDIMENSION) width;
    cinfo.image_height = (JDIMENSION) height;
    cinfo.input_components = 4;
    cinfo.in_color_space = JCS_EXT_RGBX;

    /* The same order as cjpeg: mozjpeg's defaults (progressive, trellis
       quantisation, optimised scans), then -quality if given, then the simple
       progression cjpeg re-applies because the defaults turned it on. */
    jpeg_set_defaults(&cinfo);
    if (quality >= 0) set_quality(&cinfo, quality);
    jpeg_simple_progression(&cinfo);

    jpeg_start_compress(&cinfo, TRUE);
    if (icc != NULL && icc_length > 0) {
        jpeg_write_icc_profile(&cinfo, icc, icc_length);
    }
    while (cinfo.next_scanline < cinfo.image_height) {
        JSAMPROW row = (JSAMPROW) (pixels + (size_t) cinfo.next_scanline * stride);
        jpeg_write_scanlines(&cinfo, &row, 1);
    }
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);

    *out = dest->buffer;
    *out_length = dest->length;
    free(dest);
    return 0;
}
