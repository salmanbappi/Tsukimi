#include <jni.h>
#include <android/bitmap.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <algorithm>

// NCNN
#include "net.h"
#include "gpu.h"
#include "mat.h"

#define TAG "NcnnUpscaler"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

class BitmapLock {
public:
    BitmapLock(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap), pixels_(nullptr) {
        if (AndroidBitmap_lockPixels(env_, bitmap_, &pixels_) < 0) {
            pixels_ = nullptr;
        }
    }
    ~BitmapLock() {
        if (pixels_) {
            AndroidBitmap_unlockPixels(env_, bitmap_);
        }
    }
    void* pixels() const { return pixels_; }
private:
    JNIEnv* env_;
    jobject bitmap_;
    void* pixels_;
};

static ncnn::Net net;
static bool g_initialized = false;

extern "C" JNIEXPORT jboolean JNICALL
Java_org_koitharu_kotatsu_reader_domain_NcnnUpscaler_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jobject assetManager,
        jstring modelName_) {

    if (g_initialized) return JNI_TRUE;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) return JNI_FALSE;

    const char* modelName = env->GetStringUTFChars(modelName_, 0);
    std::string paramPath = std::string(modelName) + ".param";
    std::string binPath = std::string(modelName) + ".bin";

    ncnn::create_gpu_instance();

    net.opt.use_vulkan_compute = true;
    net.opt.use_fp16_packed = true;
    net.opt.use_fp16_storage = true;
    net.opt.use_fp16_arithmetic = true;
    net.opt.lightmode = true;
    net.opt.num_threads = 4;

    // Check if GPU is available
    int gpu_count = ncnn::get_gpu_count();
    if (gpu_count <= 0) {
        LOGW("No GPU detected, falling back to CPU");
        net.opt.use_vulkan_compute = false;
    }

    if (net.load_param(mgr, paramPath.c_str()) != 0 || net.load_model(mgr, binPath.c_str()) != 0) {
        LOGE("Failed to load model %s from assets", modelName);
        env->ReleaseStringUTFChars(modelName_, modelName);
        return JNI_FALSE;
    }

    LOGD("NCNN model %s loaded successfully. GPU: %s", modelName, net.opt.use_vulkan_compute ? "ON" : "OFF");
    env->ReleaseStringUTFChars(modelName_, modelName);
    g_initialized = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_koitharu_kotatsu_reader_domain_NcnnUpscaler_nativeUpscale(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jint scale,
        jint tileSize,
        jint denoise) {

    if (!g_initialized) {
        LOGE("nativeUpscale: NCNN not initialized");
        return nullptr;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return nullptr;
    
    int w = info.width;
    int h = info.height;
    // Note: The model Real-ESRGAN x4 ALWAYS scales by 4 internally.
    const int model_scale = 4;
    
    // Output size as requested by user (usually 2x)
    int out_w = w * scale;
    int out_h = h * scale;

    LOGD("Upscaling %dx%d -> %dx%d (Model internal: x%d)", w, h, out_w, out_h, model_scale);

    jclass bitmapCls = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmapMethod = env->GetStaticMethodID(bitmapCls, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configCls = env->FindClass("android/graphics/Bitmap$Config");
    jmethodID valueOfMethod = env->GetStaticMethodID(configCls, "valueOf", "(Ljava/lang/String;)Landroid/graphics/Bitmap$Config;");
    jobject config = env->CallStaticObjectMethod(configCls, valueOfMethod, env->NewStringUTF("ARGB_8888"));
    jobject newBitmap = env->CallStaticObjectMethod(bitmapCls, createBitmapMethod, out_w, out_h, config);

    if (!newBitmap) {
        LOGE("Failed to create output bitmap %dx%d", out_w, out_h);
        return nullptr;
    }

    BitmapLock srcLock(env, bitmap);
    BitmapLock dstLock(env, newBitmap);

    if (!srcLock.pixels() || !dstLock.pixels()) {
        LOGE("Failed to lock pixels");
        return nullptr;
    }

    ncnn::Mat in = ncnn::Mat::from_pixels((const unsigned char*)srcLock.pixels(), ncnn::Mat::PIXEL_RGBA2RGB, w, h);
    
    const int prepad = 10;
    int xtiles = (w + tileSize - 1) / tileSize;
    int ytiles = (h + tileSize - 1) / tileSize;

    LOGD("Processing %dx%d tiles", xtiles, ytiles);

    for (int y = 0; y < ytiles; y++) {
        for (int x = 0; x < xtiles; x++) {
            int x0 = x * tileSize;
            int y0 = y * tileSize;
            int x1 = std::min(x0 + tileSize, w);
            int y1 = std::min(y0 + tileSize, h);

            int x0p = std::max(x0 - prepad, 0);
            int y0p = std::max(y0 - prepad, 0);
            int x1p = std::min(x1 + prepad, w);
            int y1p = std::min(y1 + prepad, h);

            ncnn::Mat tile_in;
            copy_cut_border(in, tile_in, y0p, h - y1p, x0p, w - x1p);

            ncnn::Extractor ex = net.create_extractor();
            ncnn::Mat tile_out;
            ex.input("data", tile_in);
            ex.extract("output", tile_out);

            // Coordinates in the 4x upscaled space
            int out_tile_x0 = (x0 - x0p) * model_scale;
            int out_tile_y0 = (y0 - y0p) * model_scale;
            int out_tile_x1 = out_tile_x0 + (x1 - x0) * model_scale;
            int out_tile_y1 = out_tile_y0 + (y1 - y0) * model_scale;

            ncnn::Mat tile_out_cut;
            copy_cut_border(tile_out, tile_out_cut, out_tile_y0, tile_out.h - out_tile_y1, out_tile_x0, tile_out.w - out_tile_x1);

            // Final coordinates in the target (e.g. 2x) space
            int final_x0 = x0 * scale;
            int final_y0 = y0 * scale;
            int final_w = (x1 - x0) * scale;
            int final_h = (y1 - y0) * scale;

            // Resize and copy to destination pixels
            tile_out_cut.to_pixels_resize((unsigned char*)dstLock.pixels() + (final_y0 * out_w + final_x0) * 4, ncnn::Mat::PIXEL_RGB2RGBA, final_w, final_h, out_w * 4);
        }
    }

    LOGD("Upscale complete");
    return newBitmap;
}