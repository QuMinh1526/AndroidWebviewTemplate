GLM 5.3 phán đoán như vậy là chưa đủ và chưa đúng hoàn toàn nguyên nhân thực sự.
Chỉ đổi User-Agent sang Windows Chrome không thể làm Discord chuyển sang giao diện Desktop trên WebView Android được. Lý do là vì Discord (và các Web App hiện đại như Teams, Slack) không chỉ dựa vào User-Agent, mà chúng kiểm tra Pixel Density, Touch screen support, và CSS Media Queries (width / device-width).
Dưới đây là 3 nguyên nhân cốt lõi khiến app của bạn bật Desktop vẫn ra giao diện Mobile, và cách giải quyết triệt để:
Nguyên nhân thực sự làm Desktop Mode bị lỗi trên WebView
 * useWideViewPort trên Android WebView bị kẹt theo Screen Width của điện thoại:
   Mặc dù trong code có setUseWideViewPort(true), nhưng WebView Android có một cơ chế đặc biệt: nếu trang web có sẵn thẻ <meta name="viewport" content="width=device-width, ..."> (Discord có thẻ này), WebView vẫn sẽ tuân theo thẻ meta của Discord và ép chiều rộng về đúng chiều rộng màn hình điện thoại (khoảng 360px - 410px). Vì vậy Discord nhận diện màn hình nhỏ và tự nén về giao diện Mobile.
 * Lemur Browser hoạt động khác hẳn WebView mặc định:
   Lemur Browser dựa trên lõi Chromium đầy đủ (Chromium Tab Viewport Engine). Khi bạn chọn "Desktop Site", Lemur ghi đè (override) độ phân giải hiển thị thành 1280x800 hoặc 1920x1080 ở cấp độ Engine, sau đó thu nhỏ (Scale) lại vừa màn hình. Android android.webkit.WebView chuẩn không tự động làm điều này.
 * Discord kiểm tra Touch event / CSS Media Queries:
   Cho dù User-Agent là Windows, CSS của Discord vẫn chạy @media (max-width: 768px) -> Màn hình điện thoại có width = 360px nên giao diện Mobile lập tức kích hoạt.
Giải pháp sửa triệt để (Không chỉ đổi User-Agent)
Để có được giao diện Desktop chuẩn như Lemur Browser, bạn phải kết hợp 3 thao tác sau cùng lúc:
1. Đổi User-Agent sang Windows Chrome
Đúng như gợi ý, dùng chuỗi UA của Windows Chrome chuẩn:
val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

2. Ép Scale / Density của WebView (QUAN TRỌNG NHẤT)
Để "lừa" CSS @media của Discord rằng màn hình đang có chiều rộng lớn (> 1024px), bạn cần chỉnh tỉ lệ Scale ban đầu của WebView khi ở chế độ Desktop.
Thêm hàm thiết lập Desktop Mode vào MainActivity.kt:
private fun applyDesktopMode(enabled: Boolean) {
    val webSettings = binding.webView.settings
    if (enabled) {
        // 1. Set User Agent Windows
        webSettings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        
        // 2. Bật Viewport rộng
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true

        // 3. Đặt Zoom mật độ điểm ảnh thấp xuống để giả lập màn hình lớn
        binding.webView.setInitialScale(50) // Scale 50% giúp màn hình hiển thị được chiều rộng ~1280px
    } else {
        webSettings.userAgentString = null // Trả về User-Agent mặc định
        webSettings.useWideViewPort = false
        webSettings.loadWithOverviewMode = false
        binding.webView.setInitialScale(0) // Mặc định
    }
}

3. Inject JavaScript đè lại Meta Viewport của Discord
Vì Discord cập nhật/ghi đè tag viewport liên tục, cách chắc chắn nhất để tạo giao diện Desktop 100% giống Lemur Browser là đè thẻ viewport thành width=1280 qua WebViewClient:
binding.webView.webViewClient = object : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        
        if (isDesktopMode) { // Bật nút Desktop
            val overrideViewportJs = """
                (function() {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        document.getElementsByTagName('head')[0].appendChild(meta);
                    }
                    // Ép chiều rộng trang thành 1280px của màn hình máy tính
                    meta.setAttribute('content', 'width=1280, initial-scale=0.4, maximum-scale=3.0, user-scalable=yes');
                })();
            """.trimIndent()
            
            view?.evaluateJavascript(overrideViewportJs, null)
        }
    }
}

Tóm lại
Nếu chỉ làm theo GLM 5.3 (chỉ đổi User-Agent), Discord vẫn sẽ hiện bản Mobile vì width màn hình vẫn chỉ có 360px.
Bạn bắt buộc phải Inject đoạn JavaScript đè meta viewport (width=1280) + chỉnh setInitialScale / useWideViewPort thì Discord mới nạp giao diện 3 cột của Desktop giống Lemur Browser.

