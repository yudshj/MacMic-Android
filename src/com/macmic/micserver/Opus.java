package com.macmic.micserver;

/** Thin wrapper over statically linked libopus (libmicserver.so). Not thread-safe per instance. */
final class Opus implements AutoCloseable {
    static { System.loadLibrary("micserver"); }

    private static native long nativeCreate(int sampleRate, int channels, int bitrate);
    private static native int nativeEncode(long h, short[] pcm, int frameSize, byte[] out, int outOff);
    private static native void nativeDestroy(long h);
    static native String nativeVersion();

    private long handle;

    Opus(int sampleRate, int channels, int bitrate) {
        handle = nativeCreate(sampleRate, channels, bitrate);
        if (handle == 0) throw new IllegalStateException("opus_encoder_create failed");
    }

    /** Encodes one frame into out[outOff..]; returns encoded byte count (>0) or throws. */
    int encode(short[] pcm, int frameSize, byte[] out, int outOff) {
        int n = nativeEncode(handle, pcm, frameSize, out, outOff);
        if (n <= 0) throw new IllegalStateException("opus_encode error " + n);
        return n;
    }

    @Override public void close() {
        if (handle != 0) { nativeDestroy(handle); handle = 0; }
    }
}
