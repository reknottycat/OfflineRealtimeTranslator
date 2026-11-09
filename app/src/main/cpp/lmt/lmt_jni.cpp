#include <jni.h>
#include "lmt_engine.hpp"

static void fail(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    auto type = env->FindClass("java/lang/IllegalStateException");
    if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}
static jbyteArray bytes(JNIEnv* env, const std::string& value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result && !value.empty()) env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte*>(value.data()));
    return result;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_bohanli_ruzhtranslator_translation_LmtNativeEngine_nativeCreate(JNIEnv* env, jobject, jstring path) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    std::string filename(chars);
    env->ReleaseStringUTFChars(path, chars);
    try { return reinterpret_cast<jlong>(new lmt::Engine(filename)); }
    catch (const std::exception& error) { fail(env, error.what()); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_bohanli_ruzhtranslator_translation_LmtNativeEngine_nativeTranslate(JNIEnv* env, jobject self, jlong handle, jbyteArray input) {
    if (!handle) { fail(env, "LMT engine is closed"); return nullptr; }
    auto engine = reinterpret_cast<lmt::Engine*>(handle);
    try {
        const jsize size = env->GetArrayLength(input);
        if (size > 32768) throw std::runtime_error("LMT input exceeds 32 KiB");
        std::string source(static_cast<size_t>(size), '\0');
        env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(source.data()));
        if (env->ExceptionCheck()) return nullptr;
        auto clazz = env->GetObjectClass(self);
        auto method = env->GetMethodID(clazz, "onNativeBytes", "([B)V");
        env->DeleteLocalRef(clazz);
        if (!method || env->ExceptionCheck()) return nullptr;
        auto output = engine->translate(source, [&](const std::string& chunk) {
            auto value = bytes(env, chunk);
            if (value) { env->CallVoidMethod(self, method, value); env->DeleteLocalRef(value); }
            if (env->ExceptionCheck()) throw std::runtime_error("LMT stream callback failed");
        });
        return bytes(env, output);
    } catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_bohanli_ruzhtranslator_translation_LmtNativeEngine_nativeCancel(JNIEnv*, jobject, jlong handle) {
    if (handle) reinterpret_cast<lmt::Engine*>(handle)->cancel();
}
extern "C" JNIEXPORT void JNICALL
Java_com_bohanli_ruzhtranslator_translation_LmtNativeEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<lmt::Engine*>(handle);
}
