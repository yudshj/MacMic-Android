// Thin JNI wrapper around libopus (statically linked). 48 kHz mono, 20 ms frames.
#include <jni.h>
#include <stdint.h>
#include "opus.h"

JNIEXPORT jlong JNICALL
Java_com_macmic_micserver_Opus_nativeCreate(JNIEnv *env, jclass cls, jint rate, jint ch, jint bitrate) {
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(rate, ch, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || !enc) return 0;
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(8));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(enc, OPUS_SET_VBR(1));
    return (jlong)(intptr_t)enc;
}

JNIEXPORT jint JNICALL
Java_com_macmic_micserver_Opus_nativeEncode(JNIEnv *env, jclass cls, jlong h, jshortArray pcm,
                                            jint frameSize, jbyteArray out, jint outOff) {
    OpusEncoder *enc = (OpusEncoder *)(intptr_t)h;
    if (!enc) return -1;
    jsize outLen = (*env)->GetArrayLength(env, out);
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    int n = opus_encode(enc, (const opus_int16 *)in, frameSize, (unsigned char *)o + outOff, outLen - outOff);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, n > 0 ? 0 : JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL
Java_com_macmic_micserver_Opus_nativeDestroy(JNIEnv *env, jclass cls, jlong h) {
    if (h) opus_encoder_destroy((OpusEncoder *)(intptr_t)h);
}

JNIEXPORT jstring JNICALL
Java_com_macmic_micserver_Opus_nativeVersion(JNIEnv *env, jclass cls) {
    return (*env)->NewStringUTF(env, opus_get_version_string());
}
