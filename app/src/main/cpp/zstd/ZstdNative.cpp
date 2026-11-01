// JNI bridge to zstd's own decoder, for restic's compressed repositories (backup/restic).
#include <jni.h>

#include <climits>
#include <string>
#include <vector>

#include "zstd.h"

namespace {

void throwIo(JNIEnv* env, const std::string& message) {
    jclass io = env->FindClass("java/io/IOException");
    if (io != nullptr) env->ThrowNew(io, message.c_str());
}

jbyteArray toArray(JNIEnv* env, const void* bytes, size_t size) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    if (out != nullptr) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), static_cast<const jbyte*>(bytes));
    return out;
}

// A frame that does not state its size: decoded in pieces, frame after frame.
jbyteArray decodeStream(JNIEnv* env, const std::vector<jbyte>& input) {
    ZSTD_DCtx* stream = ZSTD_createDCtx();
    if (stream == nullptr) {
        throwIo(env, "zstd: could not allocate a decoder");
        return nullptr;
    }
    std::vector<char> output;
    std::vector<char> piece(ZSTD_DStreamOutSize());
    ZSTD_inBuffer in{input.data(), input.size(), 0};
    size_t last = 0;
    while (in.pos < in.size) {
        ZSTD_outBuffer out{piece.data(), piece.size(), 0};
        last = ZSTD_decompressStream(stream, &out, &in);
        if (ZSTD_isError(last)) {
            std::string reason = ZSTD_getErrorName(last);
            ZSTD_freeDCtx(stream);
            throwIo(env, "zstd: " + reason);
            return nullptr;
        }
        output.insert(output.end(), piece.data(), piece.data() + out.pos);
        if (output.size() > INT_MAX - 8) {
            ZSTD_freeDCtx(stream);
            throwIo(env, "zstd: the decompressed data is too large for an array");
            return nullptr;
        }
    }
    // Drains what the decoder still holds; a frame cut short leaves it asking for more.
    while (last != 0) {
        ZSTD_outBuffer out{piece.data(), piece.size(), 0};
        size_t before = last;
        last = ZSTD_decompressStream(stream, &out, &in);
        if (ZSTD_isError(last) || (out.pos == 0 && last == before)) {
            ZSTD_freeDCtx(stream);
            throwIo(env, "zstd: the compressed data ends before its frame does");
            return nullptr;
        }
        output.insert(output.end(), piece.data(), piece.data() + out.pos);
    }
    ZSTD_freeDCtx(stream);
    return toArray(env, output.data(), output.size());
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_fayaz_otgmaster_backup_restic_ZstdNative_decompress(JNIEnv* env, jobject, jbyteArray src, jint offset,
                                                             jint length, jint expected) {
    std::vector<jbyte> input(static_cast<size_t>(length));
    env->GetByteArrayRegion(src, offset, length, input.data());
    if (env->ExceptionCheck()) return nullptr;

    size_t size;
    if (expected >= 0) {
        size = static_cast<size_t>(expected);
    } else {
        unsigned long long stated = ZSTD_getFrameContentSize(input.data(), input.size());
        if (stated == ZSTD_CONTENTSIZE_ERROR) {
            throwIo(env, "zstd: not a zstd frame");
            return nullptr;
        }
        if (stated == ZSTD_CONTENTSIZE_UNKNOWN) return decodeStream(env, input);
        if (stated > INT_MAX - 8) {
            throwIo(env, "zstd: the frame states " + std::to_string(stated) + " bytes, too large for an array");
            return nullptr;
        }
        size = static_cast<size_t>(stated);
    }
    std::vector<char> output(size);
    size_t produced = ZSTD_decompress(output.data(), size, input.data(), input.size());
    if (ZSTD_isError(produced)) {
        throwIo(env, std::string("zstd: ") + ZSTD_getErrorName(produced));
        return nullptr;
    }
    if (produced != size) {
        throwIo(env, "zstd gave " + std::to_string(produced) + " bytes, expected " + std::to_string(size));
        return nullptr;
    }
    return toArray(env, output.data(), size);
}
