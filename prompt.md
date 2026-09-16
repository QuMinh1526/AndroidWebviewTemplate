# Prompt: Gộp về 1 pipeline Native C++ duy nhất + fix toàn bộ lỗi DSP

Dán nguyên văn phần dưới cho Claude Code / Codex / coding agent chạy trên project
`01.WebViewStarterTemplate` (package `com.webviewtemplate.webviewtemplate`).

---

## Bối cảnh

Project hiện có **2 pipeline xử lý âm thanh song song, trùng lặp**:

1. **Native C++ (Oboe)** — `app/src/main/cpp/AudioEngine.cpp/.h`, điều khiển qua
   `audio/AudioEngine.kt` → `audio/OboeEngine.kt` (JNI) → `audio/NativePcmBridge.kt`
   (JavascriptInterface) → AudioWorklet `native-pcm-source` trong
   `assets/audio_processor.js`.
2. **JS/WebAudio** — toàn bộ hàm `buildPipeline()`, `createGate()`,
   `createScriptProcessorGate()`, `createPitchNode()` và các node
   Gain/BiquadFilter/DynamicsCompressor/Convolver/Delay trong
   `assets/audio_processor.js`, được kích hoạt khi trang web gọi
   `getUserMedia()` và `NativePcmBridge.mode()` trả về `"off"`.

Cả 2 pipeline đang implement lại **cùng một bộ hiệu ứng** (Noise Gate, 10-band EQ,
Compressor, Reverb, Pitch Shifter, Echo, Gain) với logic khác nhau, và pipeline JS
có nhiều bug (liệt kê bên dưới). Yêu cầu: **xoá hẳn pipeline JS, chỉ giữ native
C++ làm nguồn xử lý DSP duy nhất**, JS chỉ còn nhiệm vụ nhận PCM đã xử lý và phát
lại (playback-only).

---

## Mục tiêu

1. Loại bỏ hoàn toàn code xử lý DSP bằng JavaScript/Web Audio API.
2. Mọi lời gọi `getUserMedia()` từ trang web đều phải đi qua native PCM
   (`NativePcmBridge` → AudioWorklet `native-pcm-source`), không còn nhánh
   fallback xử lý bằng JS.
3. Sửa tất cả các lỗi DSP đã phát hiện trong pipeline native (liệt kê ở Phần 2).
4. Giữ nguyên hành vi "privileged" (Shizuku/root injection) như hiện tại — nhánh
   này vốn đã không qua JS.

---

## Phần 1 — Xoá pipeline JS, chỉ giữ native

### 1.1. `app/src/main/assets/audio_processor.js`

- **Xoá hoàn toàn**: `buildPipeline()`, `createGate()`,
  `createScriptProcessorGate()`, `createPitchNode()`, và mọi biến/khai báo chỉ
  phục vụ chúng (`gainNodes`, `filters`, `compressor`, `gateNode`, `pitchNode`,
  `rawPath`, `processedPath`, `outputSelector`, `impulseResponse()`).
- **Xoá** các hàm setter thao tác trực tiếp lên AudioNode:
  `setNoiseGate`, `setEq`, `setCompressor`, `setReverb`, `setPitch`, `setEcho`,
  `setGain` (bản hiện tại) và các global `window.__setNoiseGate`,
  `window.__setEq`, `window.__setCompressor`, `window.__setReverb`,
  `window.__setPitch`, `window.__setEcho`, `window.__setGain`,
  `window.__setMicGain`, `window.__setAudioProcessingEnabled`.
  → Toàn bộ 7 hiệu ứng giờ chỉ được điều khiển từ phía Kotlin
  (`AudioEngine.kt.apply()` gọi thẳng JNI), JS không cần biết gì về tham số
  hiệu ứng nữa.
- **Sửa `navigator.mediaDevices.getUserMedia` override**: bỏ nhánh
  `else return originalGetUserMedia(requested).then(buildPipeline)`. Chỉ còn 2
  nhánh:
  - `nativeMode === "privileged"` → trả thẳng `originalGetUserMedia(requested)`
    (như cũ).
  - Mọi trường hợp còn lại (kể cả hiện tại đang là `"off"`) → luôn gọi
    `nativeGetUserMedia(constraints)`. Nếu native PCM chưa sẵn sàng, reject rõ
    ràng bằng lỗi (không được âm thầm rơi về xử lý JS nữa) và log ra
    console để dev biết native stack chưa start thay vì che giấu bằng fallback.
- **Giữ nguyên**: `createNativePcmStream()`, `nativeGetUserMedia()`,
  `decodeFloat32()`, AudioWorklet `native-pcm-source` (đây chính là phần
  playback-only cần giữ).
- `window.__setNativeAudioMode`, `window.__setNativeAudioRoute`,
  `window.__destroyAudioProcessor` giữ nguyên (dùng để bật/tắt route
  privileged), nhưng dọn lại phần dọn dẹp `audioContext`/`gainNodes` cho khớp
  vì các biến JS-pipeline đã bị xoá.

### 1.2. `app/src/main/java/.../MainActivity.kt`

- Trong `applyAudioSettings()`: xoá toàn bộ đoạn `evaluateJavascript` gọi
  `window.__setAudioProcessingEnabled/__setNoiseGate/__setEq/__setCompressor/
  __setReverb/__setPitch/__setEcho/__setGain`. Chỉ giữ lại
  `if (nativeStackStarted) AudioEngine.shared.apply(s)`.
- Đảm bảo native stack (`AudioEngine.shared.start()`) được start **trước** khi
  WebView có thể gọi `getUserMedia()`, và `NativePcmBridge` route mode được set
  thành `"software"` (hoặc `"privileged"` tuỳ cấu hình người dùng chọn) ngay khi
  service khởi động — không còn để mặc định là `"off"`.
- Kiểm tra lại toàn bộ nơi còn `safeEvaluateJavascript(webView,
  "window.__setNativeAudioRoute...")` — giữ nguyên, không liên quan tới DSP.

### 1.3. Các file khác

- `app/src/main/assets/script.js`, `index.html`: rà soát xem có gọi trực tiếp
  `window.__setEq`/`__setGain`/... từ trang demo không; nếu có, xoá hoặc thay
  bằng gọi qua `NativePcmBridge`/kênh mới ở mục 1.4.
- `service/AudioProcessingService.kt`, `service/SoftwareLoopback.kt`,
  `service/VirtualMicService.kt`: không đổi logic, chỉ đảm bảo chúng không phụ
  thuộc gì vào pipeline JS đã xoá.

### 1.4. (Tuỳ chọn nhưng khuyến nghị) Cho phép trang web tự điều khiển hiệu ứng

Nếu trang HTML/JS trong WebView cần tự set tham số hiệu ứng (không chỉ nhận từ
Compose UI native), thêm method mới vào `NativePcmBridge.kt`:

```kotlin
@JavascriptInterface
fun setParam(effectId: Int, paramId: Int, value: Float) {
    runCatching { AudioEngine.shared.setParam(effectId, paramId, value) }
}
```

và một hàm tương ứng `setParam()` trong `AudioEngine.kt` forward thẳng xuống
`oboe.setParam(effectId, paramId, value)`. Đây là con đường **duy nhất** để
JS ảnh hưởng tới DSP — không tạo AudioNode xử lý âm thanh nào trong JS nữa.

---

## Phần 2 — Fix lỗi trong pipeline native (sau khi đã gộp về 1 pipeline)

### Bug 1 — Sai đơn vị GAIN giữa Kotlin UI và native

- **Vị trí**: `ui/MicSettingsSheet.kt` slider `"GAIN"` dùng range `0f..5f`
  (đơn vị "x", unity = 1.0). `AudioEngine.kt: apply()` gửi thẳng
  `oboe.setParam(5, 0, settings.gain)`. Nhưng `AudioEngine.h` comment ghi
  `gain{0.0f}; // UI range 0..10000` và `AudioEngine.cpp::dsp_gain()` tính:
  ```cpp
  const float slider = std::clamp(params_.gain.load(), 0.0f, 10000.0f);
  const float multiplier = 1.0f + (slider / 10000.0f) * (kMaxGainMultiplier - 1.0f);
  ```
  → với input thực tế 0..5, multiplier chỉ đạt tối đa **~1.0045x**, gain gần
  như vô tác dụng dù kéo max.
- **Fix bắt buộc**: đồng bộ 1 thang đo duy nhất. Chọn phương án: **native nhận
  trực tiếp hệ số nhân "x" giống UI** (đơn giản, dễ hiểu hơn thang 0-10000):
  - Trong `AudioEngine.h`: đổi comment và default:
    ```cpp
    std::atomic<float> gain{1.0f}; // linear multiplier, UI range 0..kMaxGainMultiplier (unity = 1.0)
    ```
  - Trong `AudioEngine.cpp::dsp_gain()`:
    ```cpp
    void AudioEngine::dsp_gain(float* buf, int32_t frames) {
        const float multiplier = std::clamp(
            params_.gain.load(std::memory_order_relaxed), 0.0f, kMaxGainMultiplier);
        for (int i = 0; i < frames; i++)
            buf[i] = softClip(buf[i] * multiplier); // xem Bug 2 bên dưới
    }
    ```
  - `kMaxGainMultiplier` giữ ở mức hợp lý, ví dụ `5.0f` (khớp đúng UI hiện tại
    `0f..5f`), không cần 10.0f nữa.
  - Trong `EffectParams::EffectParams()` (constructor), đảm bảo
    `gain.store(1.0f)` nếu không dùng default member initializer ở trên.
  - Kiểm tra `preset/PresetManager.kt` (nếu còn dùng) và mọi nơi khác đọc/ghi
    giá trị `gain` mặc định — đảm bảo default = `1f`, không phải `0f`.

### Bug 2 — GAIN hard-clip gây méo tiếng nặng

- **Vị trí**: `dsp_gain()` hiện dùng `std::clamp(buf[i]*multiplier, -1.0f,
  1.0f)` — clip cứng, sinh hài âm bậc cao (harsh digital clipping) ngay khi tín
  hiệu vượt 0dBFS.
- **Fix**: thay clip cứng bằng soft-clip (ví dụ `tanh`) để méo êm hơn khi gain
  cao, hoặc thêm limiter nhẹ trước khi clamp:
  ```cpp
  static inline float softClip(float x) {
      // êm hơn hard clip, vẫn giới hạn biên độ về khoảng (-1, 1)
      return std::tanh(x);
  }
  ```
  Áp dụng `softClip()` sau khi nhân `multiplier` trong `dsp_gain()`.

### Bug 3 — Noise Gate chattering (nếu còn dùng logic JS cũ) — đã tự hết sau
Phần 1, nhưng rà soát thêm native gate:

- **Vị trí**: `dsp_noiseGate()` trong `AudioEngine.cpp` — logic envelope
  attack/release đã đúng, **không cần sửa DSP**, nhưng kiểm tra default
  `gateThresholdDb = -45f` (Kotlin) có hợp lý với mic thực tế không; nếu người
  dùng vẫn nghe gate đóng/mở lộ liễu, cân nhắc thêm hysteresis nhỏ (~2-3dB)
  giữa ngưỡng mở và ngưỡng đóng để tránh dao động quanh threshold.

### Bug 4 — Echo: wet/feedback mặc định hơi mạnh

- **Vị trí**: `dsp_echo()`:
  ```cpp
  const float feedback = intensity * 0.7f;
  const float wet = intensity * 0.5f;
  const int delaySamples = ... kEchoDelayMs = 250 ...
  ```
- **Fix đề xuất** (không bắt buộc, nhưng nên làm để đỡ "nghe lạ giọng"):
  - Giảm trần `wet` xuống ví dụ `intensity * 0.35f` để tiếng echo không to
    gần bằng tiếng gốc.
  - Giảm trần `feedback` xuống `intensity * 0.55f` để tránh vọng nhiều lần.
  - Thêm 1 bộ lọc low-pass đơn giản (1-pole) trên tín hiệu hồi tiếp
    (feedback path) để tiếng lặp lại nghe "ấm" hơn thay vì kim loại/the thé,
    giống các delay pedal thực tế:
    ```cpp
    // trong echo state: thêm 1 biến float echoFilterState_ = 0.0f;
    // trong dsp_echo(), trước khi ghi vào echoBuffer_:
    echoFilterState_ = echoFilterState_ * 0.5f + delayed * 0.5f; // 1-pole LPF nhẹ
    echoBuffer_[echoWritePos_] = dry + echoFilterState_ * feedback;
    ```
  - Giữ nguyên `kEchoDelayMs = 250` (delay đơn quãng này là đặc tính của hiệu
    ứng "echo" rõ ràng, không phải bug) — có thể cân nhắc cho phép chỉnh delay
    time qua 1 param mới nếu muốn linh hoạt hơn, nhưng không bắt buộc.

### Bug 5 — Rà soát toàn bộ `nativeSetParam` (JNI) sau khi đổi thang GAIN

- **Vị trí**: `AudioEngine.cpp`, khối `extern "C" { ... Java_..._nativeSetParam
  ... }`, `case 5:` (Gain). Đảm bảo không còn đoạn code nào giả định thang
  0..10000 sót lại (comment cũ, giá trị mặc định cũ...).
- Cập nhật lại `OboeEngine.kt` (JNI wrapper) và mọi doc/comment liên quan tới
  param id 5 để ghi đúng đơn vị mới (`0..kMaxGainMultiplier`, unity = 1.0).

---

## Tiêu chí nghiệm thu (test checklist)

Sau khi hoàn tất, kiểm tra:

- [ ] `grep -rn "buildPipeline\|createScriptProcessorGate\|createGate(\|createPitchNode" app/src/main/assets/` → không còn kết quả.
- [ ] `grep -rn "window.__set" app/src/main/java` → không còn `evaluateJavascript` gọi các hàm DSP JS cũ (chỉ còn `__setNativeAudioMode`/`__setNativeAudioRoute` nếu giữ).
- [ ] Bật mic trong app, xác nhận `NativePcmBridge.isRunning()` trả `true` và audio nghe được là audio đã qua `AudioEngine.cpp` (không còn đường nào dùng browser mic thô + JS xử lý).
- [ ] Gate: nói nhỏ dần quanh ngưỡng threshold, không nghe tiếng "rè/lụp bụp" do đóng mở gate.
- [ ] EQ: kéo từng band, nghe rõ thay đổi tần số tương ứng, không bị vỡ tiếng khi boost nhiều band cùng lúc ở mức vừa phải.
- [ ] Compressor: nói to/nhỏ, nghe được hiệu ứng nén, không kêu "bơm" (pumping) quá mức ở release ngắn.
- [ ] Reverb: bật/tắt và chỉnh Mix **trong lúc mic đang chạy** (không cần restart mic) → nghe được thay đổi ngay lập tức.
- [ ] Pitch: chỉnh semitone, giọng đổi cao/thấp mượt, ít artifact.
- [ ] Echo: bật lên nghe được tiếng lặp rõ ràng nhưng không lấn át giọng gốc; chỉnh amount thấy amount tăng/giảm đúng theo % đã đặt.
- [ ] Gain: kéo từ 0 → 5x, nghe âm lượng tăng dần đều, tới gần max có nén nhẹ (soft clip) chứ không vỡ tiếng gắt.
- [ ] Tắt/mở lại app, thoát pipeline, không còn AudioContext/AudioWorklet nào của JS-pipeline cũ bị treo lại (kiểm tra qua `chrome://inspect` remote debug WebView, tab Console không còn log liên quan `createGate`/`buildPipeline`).

