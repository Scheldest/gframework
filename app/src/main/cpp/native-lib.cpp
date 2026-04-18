#include <jni.h>
#include <string>
#include <cstring>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdio>

static const unsigned char SECRET[] = {0x3E, 0x3C, 0x3E, 0x3A, 0x3C, 0x3E, 0x3E, 0x37};
static const size_t LEN = 8;
static char g_path[512] = "/data/data/com.bluestacks.fpsoverlay/files/.v_stat";

extern "C" JNIEXPORT void JNICALL
Java_com_bluestacks_fpsoverlay_SupportService_initNative(JNIEnv* env, jobject thiz, jstring path) {
    if (path == nullptr) return;
    const char* n_path = env->GetStringUTFChars(path, nullptr);
    if (n_path != nullptr) {
        strncpy(g_path, n_path, 511);
        g_path[511] = '\0';
        env->ReleaseStringUTFChars(path, n_path);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bluestacks_fpsoverlay_CoreActivity_initNative(JNIEnv* env, jobject thiz, jstring path) {
    Java_com_bluestacks_fpsoverlay_SupportService_initNative(env, thiz, path);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bluestacks_fpsoverlay_SystemReceiver_initNative(JNIEnv* env, jobject thiz, jstring path) {
    Java_com_bluestacks_fpsoverlay_SupportService_initNative(env, thiz, path);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bluestacks_fpsoverlay_SupportService_setLockStatusNative(JNIEnv* env, jobject thiz, jboolean locked) {
    FILE* f = fopen(g_path, "wb");
    if (f) {
        unsigned char v = locked ? 0xBB : 0xAF;
        fwrite(&v, 1, 1, f);
        fflush(f);
        fsync(fileno(f));
        fclose(f);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bluestacks_fpsoverlay_SupportService_isLockedNative(JNIEnv* env, jobject thiz) {
    FILE* f = fopen(g_path, "rb");
    if (!f) return JNI_FALSE;
    unsigned char v = 0;
    size_t r = fread(&v, 1, 1, f);
    fclose(f);
    return (r == 1 && v == 0xBB) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bluestacks_fpsoverlay_SupportService_checkKey(JNIEnv* env, jobject thiz, jstring input) {
    if (input == nullptr) return JNI_FALSE;
    const char* n_in = env->GetStringUTFChars(input, nullptr);
    if (n_in == nullptr) return JNI_FALSE;
    size_t in_len = strlen(n_in);
    bool ok = (in_len == LEN);
    if (ok) {
        for (size_t i = 0; i < LEN; i++) {
            if ((unsigned char)(n_in[i] ^ 0x0E) != SECRET[i]) {
                ok = false;
                break;
            }
        }
    }
    if (ok) {
        Java_com_bluestacks_fpsoverlay_SupportService_setLockStatusNative(env, thiz, JNI_FALSE);
    }
    env->ReleaseStringUTFChars(input, n_in);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bluestacks_fpsoverlay_SupportService_checkStatus(JNIEnv* env, jobject thiz) {
    FILE* f = fopen(g_path, "rb");
    if (!f) return JNI_FALSE;
    unsigned char v;
    size_t r = fread(&v, 1, 1, f);
    fclose(f);
    return (r == 1 && v == 0xAF) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bluestacks_fpsoverlay_CoreActivity_checkStatus(JNIEnv* env, jobject thiz) {
    return Java_com_bluestacks_fpsoverlay_SupportService_checkStatus(env, thiz);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bluestacks_fpsoverlay_CoreActivity_isLockedNative(JNIEnv* env, jobject thiz) {
    return Java_com_bluestacks_fpsoverlay_SupportService_isLockedNative(env, thiz);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bluestacks_fpsoverlay_SystemReceiver_isLockedNative(JNIEnv* env, jobject thiz) {
    return Java_com_bluestacks_fpsoverlay_SupportService_isLockedNative(env, thiz);
}
