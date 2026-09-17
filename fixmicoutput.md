Hai lỗi này xảy ra khá phổ biến khi xử lý Real-time Audio trên Android WebView. Dưới đây là nguyên nhân và giải pháp sửa triệt để cả 2 vấnize:
1. Sửa lỗi Mic không thu liền (Chỉ nghe đoạn cuối)
Nguyên nhân:
 * Chế độ tiết kiệm năng lượng/VAD (Voice Activity Detection) của Android: Khi trang web gọi Mic, luồng ghi âm Native bị khởi động chậm (Buffer Delay) hoặc bị Android ép "ngủ" trong vài giây đầu để tiết kiệm pin.
 * Xung đột Audio Record Buffer: Trong mã nguồn xử lý âm thanh/Stream của bạn, kích thước bộ nhớ đệm (Buffer Size) quá lớn hoặc cơ chế xả đệm (Flush/Push) bị nghẽn ở các giây đầu, dẫn đến việc âm thanh bị tích lại và chỉ phát ra ở cuối đoạn.
Cách khắc phục:
Bước 1: Giữ luồng Mic luôn hoạt động (Wake Lock & Low Latency)
Bật thuộc tính giảm độ trễ tối đa cho WebView trong MainActivity.kt:
// Bật chế độ đàm thoại thực tế để Android ưu tiên Hardware Mic ngay lập tức
val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
audioManager.isSpeakerphoneOn = true

Bước 2: Tắt tính năng tự ngắt Mic/Noise Gate trong Script JS
Cho phép luồng âm thanh truyền đi liên tục (Continuous Stream) thay vì đợi nhận diện giọng nói bằng cách cập nhật đoạn Script Inject Audio:
(function() {
    if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
        var origGetUserMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
        
        navigator.mediaDevices.getUserMedia = function(constraints) {
            if (constraints && constraints.audio) {
                var audioConstraints = {
                    echoCancellation: true,
                    noiseSuppression: false, // Tắt Noise Suppress mặc định vì nó hay bị nhầm tiếng người ở vài giây đầu là tiếng ồn
                    autoGainControl: true,
                    latency: 0, // Đưa độ trễ về 0 để thu ngay lập tức
                    channelCount: 1
                };
                
                if (typeof constraints.audio === 'object') {
                    Object.assign(constraints.audio, audioConstraints);
                } else {
                    constraints.audio = audioConstraints;
                }
            }
            return origGetUserMedia(constraints);
        };
    }
})();

2. Sửa lỗi Facebook/Mọi web bắt cấp quyền Mic lại liên tục
Nguyên nhân:
Mỗi khi bạn load lại trang hoặc chuyển URL (ví dụ từ facebook.com sang m.facebook.com), WebView coi đó là một Origin (nguồn) mới. Nếu bạn chỉ gọi request.grant(...) tạm thời mà không lưu lại bộ nhớ cấp quyền (Permission Origin Store) của WebView, trang web sẽ bắt bạn bấm cho phép lại ở lần tiếp theo.
Cách khắc phục:
Bước 1: Bật tính năng nhớ quyền Web trong WebSettings & CookieManager
Thêm dòng này vào hàm cài đặt WebView trong MainActivity.kt:
val webSettings = binding.webView.settings

// Cho phép lưu trữ bộ nhớ ứng dụng và vị trí/quyền
webSettings.domStorageEnabled = true
webSettings.databaseEnabled = true

// Đồng bộ lưu trữ quyền giữa các phiên làm việc
val cookieManager = CookieManager.getInstance()
cookieManager.setAcceptCookie(true)
cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

Bước 2: Tự động Duyệt & Nhớ Origin trong WebChromeClient
Sửa lại hàm onPermissionRequest để cấp quyền trực tiếp cho Origin (Domain) của trang web đó:
binding.webView.webChromeClient = object : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest?) {
        runOnUiThread {
            request?.let {
                // Tự động grant toàn bộ tài nguyên (Mic, Cam) mà Domain này yêu cầu
                it.grant(it.resources)
            }
        }
    }

    // Tự động hủy yêu cầu nếu trang web đóng giữa chừng để tránh treo Memory Leak
    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
        super.onPermissionRequestCanceled(request)
    }
}

Tóm tắt công việc cần làm:
 * Trong JS Inject: Chỉnh noiseSuppression: false và latency: 0 để Mic bắt tiếng ngay từ giây đầu tiên.
 * Trong Android Native: Thêm audioManager.mode = AudioManager.MODE_IN_COMMUNICATION để ép chip âm thanh hoạt động tức thì.
 * Trong WebView Settings: Bật domStorageEnabled = true để Facebook nhớ trạng thái cấp quyền Mic cho các lần sau.

