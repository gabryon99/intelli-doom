#include "doomgeneric.h"
#include "me_gabryon_doomed_DoomPanel.h"

#include <vector>

#include <cstring>
#include <cassert>

static JNIEnv *current_env = nullptr;
static jobject doom_panel = nullptr;

static jmethodID init_id = nullptr;
static jmethodID draw_id = nullptr;
static jmethodID sleep_id = nullptr;
static jmethodID get_ticks_id = nullptr;
static jmethodID get_key_id = nullptr;
static jmethodID set_window_title_id = nullptr;

std::vector<std::string> convert_java_list_to_vector(JNIEnv *env, const jint argc,  jobject list) {
    if (list == nullptr) return {};

    jclass list_class = env->FindClass("java/util/List");
    jmethodID get_method = env->GetMethodID(list_class, "get", "(I)Ljava/lang/Object;");
    std::vector<std::string> result;
    result.reserve(argc);

    for (jint i = 0; i < argc; i++) {
        // Get String object from List
        const auto str = static_cast<jstring>(env->CallObjectMethod(list, get_method, i));
        if (str == nullptr) {
            fprintf(stderr, "[error] :: Failed to get string at index %d\n", i);
            continue;
        }

        // Convert Java string to C-string
        const char *str_chars = env->GetStringUTFChars(str, nullptr);
        if (str_chars == nullptr) {
            fprintf(stderr, "Failed to get UTF chars for string at index %d\n", i);
            continue;
        }

        // Copy the Java string into a std::string
        std::string str_copy(str_chars);
        result.push_back(str_copy);

        // Release the Java-string
        env->ReleaseStringUTFChars(str, str_chars);
        env->DeleteLocalRef(str);
    }

    return result;
}

JNIEXPORT void JNICALL
Java_me_gabryon_doomed_DoomPanel_create(JNIEnv *env, jobject obj, jint argc, jobject argv) {
    // At the moment, we avoid double initialization. Even though it can be possible
    // to run several Doom instances in different panels...
    assert(current_env == nullptr && doom_panel == nullptr);

    current_env = env;
    doom_panel = env->NewGlobalRef(obj);

    jclass clazz = current_env->GetObjectClass(doom_panel);
    // We cache the method ids for the methods we are interested in, only once.
    init_id = current_env->GetMethodID(clazz, "init", "()V");
    draw_id = current_env->GetMethodID(clazz, "drawFrame", "(Ljava/nio/ByteBuffer;)V");
    sleep_id = current_env->GetMethodID(clazz, "sleepMs", "(J)V");
    get_ticks_id = current_env->GetMethodID(clazz, "getTickMs", "()J");
    get_key_id = current_env->GetMethodID(clazz, "getKey", "()I");
    set_window_title_id = current_env->GetMethodID(clazz, "setWindowTitle", "(Ljava/lang/String;)V");

    fprintf(stdin, "[info] :: starting doomgeneric...\n");

    char **argv_array = nullptr;

    if (argc > 0 && argv != nullptr) {
        // Get List methods
        jclass list_class = env->FindClass("java/util/List");
        jmethodID get_method = env->GetMethodID(list_class, "get", "(I)Ljava/lang/Object;");

        // Allocate array of char pointers
        argv_array = new char *[argc];

        // Convert each String to C-string
        for (jint i = 0; i < argc; i++) {
            // Get String object from List
            jstring str = (jstring) env->CallObjectMethod(argv, get_method, i);
            if (str == nullptr) {
                fprintf(stderr, "[error] :: Failed to get string at index %d\n", i);
                continue;
            }

            // Convert Java string to C-string
            const char *str_chars = env->GetStringUTFChars(str, nullptr);
            if (str_chars == nullptr) {
                fprintf(stderr, "Failed to get UTF chars for string at index %d\n", i);
                continue;
            }

            // Allocate and copy string
            argv_array[i] = strdup(str_chars);

            // Release the Java string
            env->ReleaseStringUTFChars(str, str_chars);
            env->DeleteLocalRef(str);
        }
    }

    if (argv_array != nullptr) {
        for (int i = 0; i < argc; i++) {
            fprintf(stderr, "[info] :: argv[%d] = %s\n", i, argv_array[i]);
        }

    }
    doomgeneric_Create(argc, argv_array);
}

JNIEXPORT void JNICALL
Java_me_gabryon_doomed_DoomPanel_tick(JNIEnv *env, jobject obj) {
    doomgeneric_Tick();
}

#ifdef __cplusplus
extern "C" {
#endif

void DG_Init() {
    current_env->CallVoidMethod(doom_panel, init_id);
}

void DG_DrawFrame() {
    // We need to copy the buffer from doomgeneric into the ByteBuffer...
    static jclass bb_clazz = nullptr;
    static jmethodID allocate_direct = nullptr;
    static jobject buffer = nullptr;

    if (bb_clazz == nullptr) {
        bb_clazz = current_env->FindClass("java/nio/ByteBuffer");
        if (bb_clazz == nullptr) {
            fprintf(stderr, "[error] :: Failed to find class java/nio/ByteBuffer\n");
            return;
        }
    }

    if (allocate_direct == nullptr) {
        allocate_direct = current_env->GetStaticMethodID(bb_clazz, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
        if (allocate_direct == nullptr) {
            fprintf(stderr, "[error] :: Failed to get method ID for allocateDirect\n");
            return;
        }
    }

    if (buffer == nullptr) {
        fprintf(stderr, "[info] :: allocating direct buffer\n");
        buffer = current_env->CallStaticObjectMethod(bb_clazz, allocate_direct,
                                                     DOOMGENERIC_RESX * DOOMGENERIC_RESY * sizeof(pixel_t));
        buffer = current_env->NewGlobalRef(buffer);
        fprintf(stderr, "[info] :: allocating direct buffer ✅\n");
    }

    if (void *direct_buffer = current_env->GetDirectBufferAddress(buffer); direct_buffer != nullptr) {
        memcpy(direct_buffer, DG_ScreenBuffer, DOOMGENERIC_RESX * DOOMGENERIC_RESY * sizeof(pixel_t));
        // Get the rewind method ID (you should cache this like the other method IDs)
        static jmethodID rewind_id = nullptr;
        if (rewind_id == nullptr) {
            rewind_id = current_env->GetMethodID(bb_clazz, "rewind", "()Ljava/nio/Buffer;");
            if (rewind_id == nullptr) {
                fprintf(stderr, "[error] :: Failed to get method ID for rewind\n");
                return;
            }
        }

        // Rewind the buffer
        current_env->CallObjectMethod(buffer, rewind_id);

        current_env->CallVoidMethod(doom_panel, draw_id, buffer);
    } else {
        fprintf(stderr, "[error] :: failed to get direct buffer address\n");
    }
}

void DG_SleepMs(uint32_t ms) {
    current_env->CallVoidMethod(doom_panel, sleep_id, static_cast<jlong>(ms));
}

uint32_t DG_GetTicksMs() {
    return current_env->CallLongMethod(doom_panel, get_ticks_id);
}

int DG_GetKey(int *pressed, unsigned char *key) {
    jint key_data = current_env->CallIntMethod(doom_panel, get_key_id);
    fprintf(stderr, "[info] :: getKey returned key code: %d\n", key_data);
    if (key_data == 0) return 0;
    *pressed = key_data >> 8;
    *key = key_data & 0xFF;
    return 1;
}

void DG_SetWindowTitle(const char *title) {
    current_env->CallVoidMethod(doom_panel, set_window_title_id, current_env->NewStringUTF(title));
}


#ifdef __cplusplus
}
#endif
