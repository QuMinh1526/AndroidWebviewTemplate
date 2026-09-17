Có, trang web sẽ bị mất trạng thái đăng nhập hoặc bị load lại làm văng ra ngoài nếu bạn bật/tắt nút Desktop Mode khi đang ở trong trang.
Lý do bị văng/mất đăng nhập:
 * Tải lại trang (Reload): Khi bấm nút Desktop, WebView bắt buộc phải gọi webView.reload() để gửi lại User-Agent mới lên Server. Khi đổi User-Agent giữa Mobile và Desktop, nhiều trang web (như TikTok, Facebook, Discord) coi đây là một thiết bị hoàn toàn khác và tự động xóa Session/Cookie để bảo vệ tài khoản.
 * Xung đột Cookie/Session: Bản Mobile và Bản Desktop của một số ứng dụng web lưu Session ở 2 đường dẫn khác nhau (ví dụ m.facebook.com vs [www.facebook.com](https://www.facebook.com)). Bật/tắt giữa chừng làm trình duyệt không đồng bộ được Token đăng nhập.
Cách dùng đúng và Giải pháp khắc phục:
1. Nguyên tắc sử dụng đúng
 * Nên bật nút Desktop Mode BẬT SẴN TRƯỚC khi tiến hành Đăng nhập.
 * Khi đã vào đến giao diện bên trong của Discord/TikTok rồi, hạn chế bấm bật/tắt liên tục nút Desktop.
2. Cấu hình Code để KHÔNG bị mất Cookie khi reload
Để giảm thiểu tối đa việc văng đăng nhập khi đổi chế độ, bạn cần đảm bảo WebView được bật tính năng lưu Cookie liên tục giữa các phiên (Persistent Cookies):
Thêm đoạn code này vào hàm khởi tạo WebView trong MainActivity.kt:
// Cho phép đồng bộ Cookie liên tục giữa các lần Reload / Đổi User-Agent
val cookieManager = CookieManager.getInstance()
cookieManager.setAcceptCookie(true)
cookieManager.setAcceptThirdPartyCookies(binding.webView, true)


