#include <android/bitmap.h>
#include <jni.h>
#include <stdlib.h>

#include "mozjpeg_encode.h"

static void throw_new(JNIEnv *env, const char *class_name, const char *message) {
    jclass cls = (*env)->FindClass(env, class_name);
    if (cls != NULL) (*env)->ThrowNew(env, cls, message);
}

/* MozJpeg.nativeEncode(bitmap: Bitmap, quality: Int, icc: ByteArray?): ByteArray */
JNIEXPORT jbyteArray JNICALL
Java_io_github_aliankarali_imageshrinker_engine_MozJpeg_nativeEncode(JNIEnv *env, jclass cls,
                                                                     jobject bitmap, jint quality,
                                                                     jbyteArray icc) {
    (void) cls;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throw_new(env, "java/lang/IllegalArgumentException", "could not read the bitmap");
        return NULL;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        throw_new(env, "java/lang/IllegalArgumentException", "mozjpeg needs an ARGB_8888 bitmap");
        return NULL;
    }

    jbyte *icc_bytes = NULL;
    jsize icc_length = 0;
    if (icc != NULL) {
        icc_length = (*env)->GetArrayLength(env, icc);
        icc_bytes = (*env)->GetByteArrayElements(env, icc, NULL);
    }

    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        if (icc_bytes != NULL) (*env)->ReleaseByteArrayElements(env, icc, icc_bytes, JNI_ABORT);
        throw_new(env, "java/lang/IllegalArgumentException", "could not lock the bitmap's pixels");
        return NULL;
    }

    unsigned char *jpeg = NULL;
    unsigned long jpeg_length = 0;
    char error[256];
    int status = shrinker_mozjpeg_encode(pixels, (int) info.width, (int) info.height, info.stride,
                                         quality, (const unsigned char *) icc_bytes,
                                         (unsigned int) icc_length, &jpeg, &jpeg_length,
                                         error, sizeof(error));

    AndroidBitmap_unlockPixels(env, bitmap);
    if (icc_bytes != NULL) (*env)->ReleaseByteArrayElements(env, icc, icc_bytes, JNI_ABORT);

    if (status != 0) {
        throw_new(env, "java/io/IOException", error);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize) jpeg_length);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) jpeg_length, (const jbyte *) jpeg);
    }
    free(jpeg);
    return result;
}
