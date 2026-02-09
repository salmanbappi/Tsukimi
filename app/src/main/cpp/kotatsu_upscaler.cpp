#include <jni.h>
#include <android/bitmap.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <algorithm>

// NCNN
#include "ncnn/net.h"
#include "ncnn/gpu.h"
#include "ncnn/mat.h"

#define TAG "NcnnUpscaler"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
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
static int g_scale = 4; // Real-ESRGAN x4

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
    net.opt.num_threads = 4;

    if (net.load_param(mgr, paramPath.c_str()) != 0 || net.load_model(mgr, binPath.c_str()) != 0) {
        LOGE("Failed to load model %s", modelName);
        env->ReleaseStringUTFChars(modelName_, modelName);
        return JNI_FALSE;
    }

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

    if (!g_initialized) return nullptr;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return nullptr;
    
    int w = info.width;
    int h = info.height;
    int out_w = w * scale;
    int out_h = h * scale;

    jclass bitmapCls = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmapMethod = env->GetStaticMethodID(bitmapCls, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configCls = env->FindClass("android/graphics/Bitmap$Config");
    jmethodID valueOfMethod = env->GetStaticMethodID(configCls, "valueOf", "(Ljava/lang/String;)Landroid/graphics/Bitmap$Config;");
    jobject config = env->CallStaticObjectMethod(configCls, valueOfMethod, env->NewStringUTF("ARGB_8888"));
    jobject newBitmap = env->CallStaticObjectMethod(bitmapCls, createBitmapMethod, out_w, out_h, config);

    if (!newBitmap) return nullptr;

    BitmapLock srcLock(env, bitmap);
    BitmapLock dstLock(env, newBitmap);

    if (!srcLock.pixels() || !dstLock.pixels()) return nullptr;

    ncnn::Mat in = ncnn::Mat::from_pixels((const unsigned char*)srcLock.pixels(), ncnn::Mat::PIXEL_RGBA2RGB, w, h);
    
    const int prepad = 10;
    ncnn::Mat out(out_w, out_h, sizeof(float) * 3, 3);

    int xtiles = (w + tileSize - 1) / tileSize;
    int ytiles = (h + tileSize - 1) / tileSize;

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

            int out_x0 = x0 * scale;
            int out_y0 = y0 * scale;
            int out_x1 = x1 * scale;
            int out_y1 = y1 * scale;

            int out_tile_x0 = (x0 - x0p) * scale;
            int out_tile_y0 = (y0 - y0p) * scale;
            int out_tile_x1 = out_tile_x0 + (x1 - x0) * scale;
            int out_tile_y1 = out_tile_y0 + (y1 - y0) * scale;

            ncnn::Mat tile_out_cut;
            copy_cut_border(tile_out, tile_out_cut, out_tile_y0, tile_out.h - out_tile_y1, out_tile_x0, tile_out.w - out_tile_x1);

            tile_out_cut.to_pixels_resize((unsigned char*)dstLock.pixels() + (out_y0 * out_w + out_x0) * 4, ncnn::Mat::PIXEL_RGB2RGBA, out_x1 - out_x0, out_y1 - out_y0, out_w * 4);
        }
    }

    return newBitmap;
}
