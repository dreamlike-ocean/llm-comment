#include <jni.h>

/* Gluon JavaFX 21 静态库声明 JNI 1.6；静态 JNI 从 Java 8 起要求至少声明 1.8。
 * 保留原初始化函数及其失败返回值，只提升成功时的版本声明。对应库内符号在副本中改名。
 * 参见 JNI 规范：https://docs.oracle.com/en/java/javase/25/docs/specs/jni/invocation.html
 */
#define JNI_VERSION_BRIDGE(name) \
    extern jint original_JNI_OnLoad_##name(JavaVM *, void *); \
    JNIEXPORT jint JNICALL JNI_OnLoad_##name(JavaVM *vm, void *reserved) { \
        jint version = original_JNI_OnLoad_##name(vm, reserved); \
        return version > 0 && version < JNI_VERSION_1_8 ? JNI_VERSION_1_8 : version; \
    }

JNI_VERSION_BRIDGE(prism_es2)
JNI_VERSION_BRIDGE(prism_sw)
JNI_VERSION_BRIDGE(glass)
JNI_VERSION_BRIDGE(glassgtk3)
JNI_VERSION_BRIDGE(javafx_font)
JNI_VERSION_BRIDGE(javafx_font_freetype)
JNI_VERSION_BRIDGE(javafx_font_pango)
JNI_VERSION_BRIDGE(javafx_iio)
