// Pont JNI entre Kotlin (NativeStableDiffusion.kt) et stable-diffusion.cpp.
// API vérifiée directement contre include/stable-diffusion.h et l'exemple
// officiel examples/cli/main.cpp du dépôt leejet/stable-diffusion.cpp.

#include <jni.h>
#include <string>
#include <algorithm>
#include <android/log.h>
#include "stable-diffusion.h"

#define LOG_TAG "JarvisSD"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    sd_ctx_t* g_ctx = nullptr;
    std::string g_loaded_model_path;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jarvis_assistant_NativeStableDiffusion_loadModel(JNIEnv* env, jobject /* this */, jstring modelPathJ) {
    const char* pathChars = env->GetStringUTFChars(modelPathJ, nullptr);
    std::string pathStr(pathChars);
    env->ReleaseStringUTFChars(modelPathJ, pathChars);

    if (g_ctx != nullptr && g_loaded_model_path == pathStr) {
        return JNI_TRUE; // déjà chargé
    }

    if (g_ctx != nullptr) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
    }

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = pathStr.c_str();
    params.n_threads = std::max(1, sd_get_num_physical_cores());

    LOGI("Chargement du modèle Stable Diffusion : %s (%d threads)", pathStr.c_str(), params.n_threads);
    g_ctx = new_sd_ctx(&params);

    if (g_ctx == nullptr) {
        LOGE("Échec du chargement du modèle Stable Diffusion");
        return JNI_FALSE;
    }

    g_loaded_model_path = pathStr;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jarvis_assistant_NativeStableDiffusion_generate(
    JNIEnv* env, jobject /* this */, jstring promptJ, jint width, jint height, jint steps) {

    if (g_ctx == nullptr) return nullptr;

    const char* promptChars = env->GetStringUTFChars(promptJ, nullptr);
    std::string prompt(promptChars);
    env->ReleaseStringUTFChars(promptJ, promptChars);

    sd_img_gen_params_t genParams;
    sd_img_gen_params_init(&genParams);
    genParams.prompt = prompt.c_str();
    genParams.negative_prompt = "";
    genParams.width = width;
    genParams.height = height;
    genParams.sample_params.sample_steps = steps;
    genParams.seed = -1; // aléatoire à chaque génération
    genParams.batch_count = 1;

    sd_image_t* images = nullptr;
    int numImages = 0;

    LOGI("Génération d'image : %dx%d, %d étapes", width, height, steps);
    bool ok = generate_image(g_ctx, &genParams, &images, &numImages);

    if (!ok || images == nullptr || numImages == 0 || images[0].data == nullptr) {
        LOGE("Échec de la génération d'image");
        if (images != nullptr) free_sd_images(images, numImages);
        return nullptr;
    }

    sd_image_t img = images[0];
    size_t dataSize = static_cast<size_t>(img.width) * img.height * img.channel;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(dataSize));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(dataSize), reinterpret_cast<jbyte*>(img.data));

    free_sd_images(images, numImages);

    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jarvis_assistant_NativeStableDiffusion_getChannelCount(JNIEnv* /* env */, jobject /* this */) {
    // sd.cpp produit toujours des images RGB (3 canaux) en sortie standard.
    return 3;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_assistant_NativeStableDiffusion_unload(JNIEnv* /* env */, jobject /* this */) {
    if (g_ctx != nullptr) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
    }
    g_loaded_model_path.clear();
}
