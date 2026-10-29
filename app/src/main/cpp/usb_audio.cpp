// Прямой вывод PCM на USB Audio Class 2 (async + явная обратная связь) через usbfs.
// fd берётся из UsbDeviceConnection; интерфейсы и альтернативная настройка выбираются в Kotlin.
#include <jni.h>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <poll.h>
#include <pthread.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <math.h>
#include <atomic>

#define TAG "UsbAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int kDataUrbs = 16;          // URB с данными
constexpr int kDataPkts = 32;          // пакетов на URB (1 пакет = 1 микрокадр = 125 мкс) -> 4 мс
constexpr int kFbUrbs = 4;             // URB обратной связи
constexpr int kFbPkts = 4;
constexpr size_t kRing = 1u << 23;     // 8 МиБ кольцевой буфер
constexpr size_t kMask = kRing - 1;
constexpr int kFrameBytes = 8;         // в кольце: 2 канала x int32; на шину уходит subslot (2/3/4 байта) на канал

struct Urb {
    usbdevfs_urb* u = nullptr;
    uint8_t* buf = nullptr;
    bool fb = false;
    bool submitted = false;
};

struct State {
    int fd = -1, dataEp = 0, fbEp = 0, maxPkt = 0, rate = 0, sub = 4, outFrame = 8;
    std::atomic<bool> running{false}, playing{false}, dead{false}, flushReq{false};
    pthread_t th{};
    bool thStarted = false;
    uint8_t* ring = nullptr;
    std::atomic<size_t> head{0}, tail{0};     // монотонные счётчики в байтах
    Urb data[kDataUrbs];
    Urb fb[kFbUrbs];
    uint32_t nominal = 0;                     // кадров на микрокадр, 16.16
    uint32_t fbQ16 = 0;
    uint32_t acc = 0;
    std::atomic<uint32_t> underruns{0};
    std::atomic<uint32_t> isoErrors{0};
    std::atomic<uint32_t> lastFbRaw{0};
};
State S;

usbdevfs_urb* allocUrb(int ep, int pkts, int bytes, uint8_t** bufOut) {
    size_t sz = sizeof(usbdevfs_urb) + (size_t)pkts * sizeof(usbdevfs_iso_packet_desc);
    auto* u = (usbdevfs_urb*)calloc(1, sz);
    *bufOut = (uint8_t*)calloc(1, (size_t)bytes);
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = (unsigned char)ep;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer = *bufOut;
    u->buffer_length = bytes;
    u->number_of_packets = pkts;
    return u;
}

void fillData(Urb& r) {
    usbdevfs_urb* u = r.u;
    uint8_t* p = r.buf;
    int total = 0;
    // статусы предыдущей отправки этого URB (при первой отправке нули)
    for (int i = 0; i < kDataPkts; i++) if (u->iso_frame_desc[i].status != 0) S.isoErrors++;
    const bool play = S.playing.load(std::memory_order_relaxed);
    if (S.flushReq.exchange(false))
        S.tail.store(S.head.load(std::memory_order_acquire), std::memory_order_release);
    const uint32_t maxFrames = (uint32_t)(S.maxPkt / S.outFrame);
    for (int i = 0; i < kDataPkts; i++) {
        S.acc += S.fbQ16;
        uint32_t frames = S.acc >> 16;
        S.acc &= 0xFFFF;
        if (frames > maxFrames) frames = maxFrames;
        size_t ringBytes = (size_t)frames * kFrameBytes;
        size_t outBytes = (size_t)frames * S.outFrame;
        size_t t = S.tail.load(std::memory_order_relaxed);
        size_t avail = S.head.load(std::memory_order_acquire) - t;
        if (play && avail >= ringBytes) {
            if (S.sub == 4) {
                size_t pos = t & kMask;
                size_t first = kRing - pos;
                if (first > ringBytes) first = ringBytes;
                memcpy(p, S.ring + pos, first);
                if (first < ringBytes) memcpy(p + first, S.ring, ringBytes - first);
            } else {
                uint8_t* q = p;
                for (uint32_t f = 0; f < frames; f++) {
                    for (int c = 0; c < 2; c++) {
                        uint32_t v = *(uint32_t*)(S.ring + ((t + (size_t)f * 8 + (size_t)c * 4) & kMask));
                        if (S.sub == 3) { *q++ = (v >> 8) & 255; *q++ = (v >> 16) & 255; *q++ = (v >> 24) & 255; }
                        else            { *q++ = (v >> 16) & 255; *q++ = (v >> 24) & 255; }
                    }
                }
            }
            S.tail.store(t + ringBytes, std::memory_order_release);
        } else {
            memset(p, 0, outBytes);
            if (play && outBytes) S.underruns++;
        }
        u->iso_frame_desc[i].length = (unsigned int)outBytes;
        u->iso_frame_desc[i].actual_length = 0;
        u->iso_frame_desc[i].status = 0;
        p += outBytes;
        total += (int)outBytes;
    }
    u->buffer_length = total;
}

void handleFb(Urb& r) {
    usbdevfs_urb* u = r.u;
    uint8_t* p = r.buf;
    for (int i = 0; i < kFbPkts; i++) {
        unsigned al = u->iso_frame_desc[i].actual_length;
        if (u->iso_frame_desc[i].status == 0 && al >= 3) {
            uint32_t v = (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16);
            if (al >= 4) v |= (uint32_t)p[3] << 24;
            S.lastFbRaw = v;
            double nom = (double)S.nominal, per = 0;
            if (v > nom * 0.88 && v < nom * 1.12) per = v;                           // кадров на микрокадр (16.16)
            else if (v > nom * 8 * 0.88 && v < nom * 8 * 1.12) per = v / 8.0;       // кадров на мс (16.16)
            if (per > 0) S.fbQ16 = (uint32_t)(((double)S.fbQ16 * 7.0 + per) / 8.0);
        }
        p += 4;
    }
}

void* reaper(void*) {
    setpriority(PRIO_PROCESS, (id_t)syscall(SYS_gettid), -19);   // аудио-приоритет (если разрешено)
    pollfd pfd{};
    pfd.fd = S.fd;
    pfd.events = POLLOUT;
    while (S.running.load()) {
        int pr = poll(&pfd, 1, 50);
        if (pr < 0 && errno != EINTR) { S.dead = true; break; }
        if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) { S.dead = true; break; }
        while (S.running.load()) {
            usbdevfs_urb* done = nullptr;
            if (ioctl(S.fd, USBDEVFS_REAPURBNDELAY, &done) < 0) {
                if (errno == ENODEV) S.dead = true;
                break;
            }
            Urb* ur = (Urb*)done->usercontext;
            if (ur->fb) handleFb(*ur); else fillData(*ur);
            if (ioctl(S.fd, USBDEVFS_SUBMITURB, done) < 0) {
                ur->submitted = false;
                if (errno == ENODEV) S.dead = true;
            }
        }
        if (S.dead.load()) break;
    }
    return nullptr;
}

void teardown() {
    int outstanding = 0;
    for (auto& r : S.data) if (r.u && r.submitted) { ioctl(S.fd, USBDEVFS_DISCARDURB, r.u); outstanding++; }
    for (auto& r : S.fb) if (r.u && r.submitted) { ioctl(S.fd, USBDEVFS_DISCARDURB, r.u); outstanding++; }
    int idle = 0;
    while (outstanding > 0 && idle < 100) {
        usbdevfs_urb* d = nullptr;
        if (ioctl(S.fd, USBDEVFS_REAPURBNDELAY, &d) == 0) { outstanding--; idle = 0; }
        else if (errno == EAGAIN) { usleep(1000); idle++; }
        else break;
    }
    for (auto& r : S.data) { free(r.u); free(r.buf); r = Urb(); }
    for (auto& r : S.fb) { free(r.u); free(r.buf); r = Urb(); }
}

inline int32_t conv(const uint8_t* p, int enc) {
    switch (enc) {
        case 1: return (int32_t)((uint32_t)(uint16_t)(p[0] | (p[1] << 8)) << 16);                          // PCM16
        case 2: return (int32_t)(((uint32_t)p[0] << 8) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 24)); // PCM24
        case 3: { int32_t v; memcpy(&v, p, 4); return v; }                                                  // PCM32
        default: {                                                                                          // float
            float f; memcpy(&f, p, 4);
            double d = (double)f * 2147483648.0;
            if (d >= 2147483647.0) return INT32_MAX;
            if (d <= -2147483648.0) return INT32_MIN;
            return (int32_t)lrint(d);
        }
    }
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_usbplayer_UsbDirect_nativeStart(JNIEnv*, jobject, jint fd, jint dataEp, jint fbEp, jint maxPkt, jint rate, jint subslot) {
    if (S.running.load()) return JNI_FALSE;
    if (!S.ring) S.ring = (uint8_t*)malloc(kRing);
    if (!S.ring) return JNI_FALSE;
    S.fd = fd; S.dataEp = dataEp; S.fbEp = fbEp; S.maxPkt = maxPkt; S.rate = rate;
    S.sub = (subslot == 2 || subslot == 3) ? subslot : 4; S.outFrame = 2 * S.sub;
    S.nominal = (uint32_t)(((uint64_t)rate << 16) / 8000);
    S.fbQ16 = S.nominal; S.acc = 0;
    S.head = 0; S.tail = 0; S.dead = false; S.flushReq = false; S.underruns = 0; S.isoErrors = 0; S.lastFbRaw = 0;

    for (auto& r : S.data) {
        r.fb = false;
        r.u = allocUrb(dataEp, kDataPkts, kDataPkts * maxPkt, &r.buf);
        r.u->usercontext = &r;
        fillData(r);
        if (ioctl(fd, USBDEVFS_SUBMITURB, r.u) < 0) { LOGE("submit data urb: errno %d", errno); teardown(); return JNI_FALSE; }
        r.submitted = true;
    }
    if (fbEp) for (auto& r : S.fb) {
        r.fb = true;
        r.u = allocUrb(fbEp, kFbPkts, 4 * kFbPkts, &r.buf);
        r.u->usercontext = &r;
        for (int i = 0; i < kFbPkts; i++) r.u->iso_frame_desc[i].length = 4;
        if (ioctl(fd, USBDEVFS_SUBMITURB, r.u) < 0) { LOGE("submit fb urb: errno %d", errno); teardown(); return JNI_FALSE; }
        r.submitted = true;
    }
    S.running = true;
    if (pthread_create(&S.th, nullptr, reaper, nullptr) != 0) { S.running = false; teardown(); return JNI_FALSE; }
    S.thStarted = true;
    LOGI("started: rate %d, subslot %d, ep out 0x%02x, fb 0x%02x, maxPkt %d", rate, S.sub, dataEp, fbEp, maxPkt);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_usbplayer_UsbDirect_nativeStop(JNIEnv*, jobject) {
    S.running = false;
    if (S.thStarted) { pthread_join(S.th, nullptr); S.thStarted = false; }
    teardown();
}

// buf — direct ByteBuffer с исходными PCM-кадрами; enc: 1=PCM16, 2=PCM24, 3=PCM32, 4=float.
// Возвращает принятое число исходных байт (кратно кадру), 0 — буфер полон, -1 — устройство недоступно.
JNIEXPORT jint JNICALL
Java_com_example_usbplayer_UsbDirect_nativeWrite(JNIEnv* env, jobject, jobject buf, jint off, jint len, jint enc, jint ch) {
    if (!S.running.load() || S.dead.load() || ch < 1) return -1;
    auto* base = (uint8_t*)env->GetDirectBufferAddress(buf);
    if (!base) return -1;
    const uint8_t* src = base + off;
    const int sb = enc == 1 ? 2 : enc == 2 ? 3 : 4;
    const int sf = sb * ch;
    size_t frames = (size_t)len / sf;
    size_t h = S.head.load(std::memory_order_relaxed);
    size_t freeBytes = kRing - (h - S.tail.load(std::memory_order_acquire));
    size_t maxFrames = freeBytes / kFrameBytes;
    if (frames > maxFrames) frames = maxFrames;
    if (!frames) return 0;
    for (size_t f = 0; f < frames; f++) {
        const uint8_t* fr = src + f * sf;
        int32_t l = conv(fr, enc);
        int32_t r = ch > 1 ? conv(fr + sb, enc) : l;
        *(int32_t*)(S.ring + (h & kMask)) = l;
        *(int32_t*)(S.ring + ((h + 4) & kMask)) = r;
        h += kFrameBytes;
    }
    S.head.store(h, std::memory_order_release);
    return (jint)(frames * sf);
}

JNIEXPORT void JNICALL
Java_com_example_usbplayer_UsbDirect_nativeSetPlaying(JNIEnv*, jobject, jboolean p) { S.playing = p; }

JNIEXPORT void JNICALL
Java_com_example_usbplayer_UsbDirect_nativeFlush(JNIEnv*, jobject) {
    if (!S.running.load()) return;
    S.flushReq = true;
    for (int i = 0; i < 100 && S.flushReq.load() && S.running.load(); i++) usleep(500);
}

JNIEXPORT jint JNICALL
Java_com_example_usbplayer_UsbDirect_nativeBuffered(JNIEnv*, jobject) {
    return (jint)(S.head.load() - S.tail.load());
}

JNIEXPORT jstring JNICALL
Java_com_example_usbplayer_UsbDirect_nativeInfo(JNIEnv* env, jobject) {
    char b[160];
    size_t fill = S.head.load() - S.tail.load();
    double ms = S.rate ? (double)(fill / kFrameBytes) * 1000.0 / S.rate : 0;
    snprintf(b, sizeof b, "feedback %.4f (ном. %.4f) кадр/мкадр · пропуски %u · ошибки USB %u · буфер %.0f мс%s",
             S.fbQ16 / 65536.0, S.nominal / 65536.0, S.underruns.load(), S.isoErrors.load(), ms, S.dead.load() ? " · УСТРОЙСТВО НЕДОСТУПНО" : "");
    return env->NewStringUTF(b);
}

}  // extern "C"
