#include "OMXVideoCodec.h"
#include <Log.h>

#include <unistd.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cinttypes>
#include <chrono>
#include <sstream>
#include <iomanip>

OMXVideoCodec::OMXVideoCodec()
    : omxSource_()
    , omxDecoder_(nullptr)
    , decoderThreadInitialized_(false)
    , decoderThreadQuitFlag_(false)
    , fps_(0) {
}

OMXVideoCodec::~OMXVideoCodec() {
    if (Log::isVerbose()) Log_v("destructor");

    if (Log::isDebug()) Log_d("delete decoder");
    delete omxDecoder_;
    omxDecoder_ = nullptr;

    omxSource_.clear();
    nativeWindow_.clear();
}

status_t OMXVideoCodec::init(int fps){
    fps_ = fps;
    if (Log::isInfo()) Log_i("FPS %d", fps_);
    // Create decoder thread and wait until ready
    if (Log::isInfo()) Log_i("start omxDecoderThread");

    omxDecoderThread_ = std::thread(&OMXVideoCodec::init_omxDecoderThread, this);

    std::unique_lock<std::mutex> codeclock(codecMutex_);
    while (!decoderThreadInitialized_){
        omxDecoderCond_.wait(codeclock);
    }
    codeclock.unlock();
    if (Log::isInfo()) Log_i("omxDecoderThread ready");

    return omxDecoder_->getStatus();
}

void OMXVideoCodec::shutdown(){
    decoderThreadQuitFlag_.store(true);
    if (decoderThreadInitialized_) {
        if (Log::isInfo()) Log_i("Waiting for app thread to finish...");

        if (omxSource_.get() != nullptr) {
            if (Log::isDebug()) Log_d("Stop omxSource");
            omxSource_->stop();
        }

        if (omxDecoderThread_.joinable()){
            if (Log::isDebug()) Log_d("joining decoder thread");
            omxDecoderThread_.join();
        }

        decoderThreadInitialized_ = false;
    }

    if (nativeWindow_.get() != nullptr) {
        if (Log::isDebug()) Log_d("release native window");
        nativeWindow_.clear();
    }

    if (Log::isInfo()) Log_i("all done");
}

void OMXVideoCodec::init_omxDecoderThread() {
    if (Log::isInfo()) Log_i("omxDecoderThread begins");

    omxSource_ = new OMXSource(screenSize_.width, screenSize_.height, fps_);

    if (Log::isDebug()) Log_d("Created OMXSource %p", omxSource_.get());

    omxDecoder_ = new OMXDecoder();
    omxDecoder_->setNativeWindow(nativeWindow_);
    omxDecoder_->setSource(omxSource_.get());
    status_t initStatus = omxDecoder_->init();

    std::unique_lock<std::mutex> codeclock(codecMutex_);
    decoderThreadInitialized_ = true;
    codeclock.unlock();
    omxDecoderCond_.notify_one();

    if (initStatus != OK) {
        Log_e("omxDecoder init failed %d", initStatus);
        return;
    }

    while (!decoderThreadQuitFlag_.load()) {
        status_t ret = omxDecoder_->read();
        if (Log::isVerbose()) Log_v("omxDecoder_ read ret = %d", ret);
        // Negative INFO_FORMAT_CHANGED/INFO_DISCONTINUITY statuses are normal; exit only on terminal read failures.
        if (ret == ERROR_END_OF_STREAM || ret == DEAD_OBJECT || ret == NO_INIT ||
            ret == static_cast<status_t>(UNKNOWN_ERROR)) {
            if (Log::isWarn()) Log_w("omxDecoder_ read stopped with status %d", ret);
            break;
        }
    }

    if (Log::isInfo()) Log_i("omxDecoderThread ends");

    if (Log::isDebug()) Log_d("Stop omxDecoder");
    omxDecoder_->stop();
}

void OMXVideoCodec::setNativeWindow(ANativeWindow* nativeWindow, int height, int width){
    if (Log::isDebug()) Log_d("set native window size w: %d, h: %d", width, height);
    nativeWindow_ = nativeWindow;
    if (nativeWindow != nullptr) {
        // ANativeWindow_fromSurface acquires a ref and sp<ANativeWindow> adds a second; release the raw ref so sp owns it.
        ANativeWindow_release(nativeWindow);
    }
    screenSize_.width = width;
    screenSize_.height = height;
}

void OMXVideoCodec::setSps(common::DataConstBuffer &b){
    if (omxSource_.get() == nullptr) {
        return;
    }
    if (Log::isDebug()) Log_d("sps packet len %d", b.size);
    omxSource_->getFormat()->setData(kKeyAVCC, kTypeAVCC, b.cdata, b.size);
}

OMXSource::Pointer OMXVideoCodec::source() {
    return omxSource_.get();
}

OMXDecoder::Pointer OMXVideoCodec::decoder() {
    return omxDecoder_;
}

void OMXVideoCodec::queueBuffer(common::DataConstBuffer& buffer, int64_t timestamp) {
    if (decoderThreadQuitFlag_.load() || omxSource_.get() == nullptr){
        if(Log::isVerbose()) Log_v("decoder is stopping, return");
        return;
    }

    auto* mBuffer = new MediaBuffer(buffer.size);
    if (Log::isVerbose()) Log_v("create buffer %p", mBuffer);
    memcpy(mBuffer->data(), buffer.cdata, buffer.size);
    mBuffer->set_range(0, (size_t) buffer.size);
    mBuffer->meta_data()->clear();
    mBuffer->meta_data()->setInt64(kKeyTime, timestamp);

    omxSource_->queueBuffer(mBuffer);
}
