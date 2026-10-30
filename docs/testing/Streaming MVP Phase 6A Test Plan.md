# Streaming MVP — Kiểm thử Stream Manager 6A

## Phạm vi

Stream Manager cho owner: tải/tạo phiên, sửa title/category, credential OBS, preview HLS, trạng thái reconnect và kết thúc. Trang watch viewer, browse, chat, donate và viewer count chưa thuộc đợt này. Chưa đánh dấu M6 hoàn tất.

Template Stitch được dùng làm tham chiếu bố cục; UI chạy bằng React, API thật và MediaMTX. Backend vẫn là nguồn quyết định trạng thái session.

## Chuẩn bị

- Chạy Gateway, User Service, Channel Service, Stream Service, PostgreSQL và MediaMTX theo cấu hình streaming local. Đăng nhập bằng tài khoản streamer có đủ điều kiện.
- MediaMTX và Gateway local đều cho phép origin `http://localhost:5173`. Nếu fixture giai đoạn 5 đang chiếm port, dừng fixture trước khi chạy web.
- Cấu hình `VITE_API_BASE_URL=http://localhost:8888` và `VITE_STREAM_HLS_BASE_URL=http://localhost:8898`; các giá trị này không phải secret.
- Dùng Node 26 để chạy web và bộ test mới. Trong `web-app`:

```bash
npm ci
npm run dev -- --host localhost --port 5173 --strictPort
```

Mở `http://localhost:5173/dashboard`. Không đưa cookie, stream key, JWT hoặc nội dung credential vào log, screenshot hay tài liệu.

## Nghiệm thu thủ công với OBS

1. Đăng nhập, mở Stream Manager. Chưa có session thì chọn category, nhập title và bấm **Create stream**. Xác nhận READY, OBS connection và key mặc định được che.
2. Copy server URL và stream key vào OBS → Settings → Stream → Custom. Phát H.264/AAC, keyframe interval 2 giây; bấm **Start Streaming** trong OBS.
3. Quan sát READY → LIVE. Preview hiển thị hình; bật tiếng để kiểm tra audio rồi mute lại để tránh feedback. HLS có độ trễ so với OBS.
4. Đổi title/category và lưu; xác nhận nguồn phát vẫn tiếp tục. Reload dashboard: session còn đó, preview mở lại; credential chỉ được cấp lại sau khi bấm **Get OBS credentials**.
5. Kiểm tra DevTools Network: GET metadata lặp khoảng 3 giây khi tab hiển thị, POST playback-access chỉ khi mở lại preview; HLS tới MediaMTX có Bearer, không có account cookie. Không chụp hay chia sẻ giá trị token.
6. Stop OBS: quan sát RECONNECTING và countdown. Kết nối lại trước deadline bằng key OBS còn hạn: session trở về LIVE, preview phát lại.
7. Stop OBS quá deadline: chờ backend xác nhận ENDED; preview dừng, credential bị xóa và cho tạo session mới. Reload lúc không có current sẽ hiển thị No active session.
8. Tạo session mới, thử **Cancel session** khi READY và **End stream** khi LIVE. Xác nhận dialog, trạng thái pending, 200 trước thông báo thành công và key cũ không phát lại được.
9. Thử lỗi mạng/503, clipboard bị từ chối, đăng xuất/đổi account và rời trang; kiểm tra UI báo lỗi phù hợp, không tự đánh dấu ENDED hoặc để player cũ chạy.
10. Kiểm tra màn hình nhỏ, bàn phím, dialog, autoplay và lỗi trình duyệt không hỗ trợ.

## Kiểm thử tự động

Trong `web-app`:

```bash
npm run build
npm run lint
npm test
npm run test:streaming
```

`test:streaming` dùng Vitest/React Testing Library, mock backend và Hls để kiểm tra hành vi UI, retry tạo phiên, giới hạn media URL, cleanup và response access đến muộn. Test mock không thay thế kiểm thử video thật.

## Kết quả đã thực hiện 2026-10-30

- Build và lint đạt; 13 test streaming đạt.
- Bộ `npm test` hiện còn 4 test category-preferences lỗi đã tồn tại trước thay đổi; không tính là đạt toàn bộ regression.
- Chrome headless + backend/Gateway/MediaMTX thật: tạo READY từ UI, phát video/audio H.264/AAC bằng FFmpeg, preview giải mã frame, PATCH title, reload và preview lại, OFFLINE → RECONNECTING → LIVE rồi End → ENDED. Một phiên khác được dừng quá deadline, chuyển ENDED và từ chối publish key cũ. Các session test đã được kết thúc.
- Media request mở HLS có Bearer; loader dùng `credentials: omit`, không gửi account cookie. Test kiểm tra URL ngoài origin/path bị từ chối và redirect bị chặn.
- MediaMTX 1.21.2 thường redirect master playlist để kiểm tra cookie. Vì player chủ động không gửi cookie và chặn redirect, player thêm `cookieCheck=1` vào master URL để MediaMTX dùng HLS session qua query; JWT vẫn chỉ ở header.
- Chưa kiểm thử OBS thật, xác nhận nghe audio thủ công, timeout reconnect bằng OBS hoặc các browser ngoài Chrome headless. Các mục này vẫn cần nghiệm thu trước khi đóng 6A.

## Đánh giá nghiệm thu bổ sung

Lần review ban đầu tái hiện hai lỗi: form quay về dữ liệu cũ khi PATCH thành công/GET lỗi và dialog chưa đóng khi polling nhận ENDED. Hai lỗi đã được sửa; bộ streaming hiện có 24 test đạt (13 test ban đầu và 11 case hồi quy). Chrome với API giả lập lỗi xác nhận giữ title đã lưu và đóng dialog khi kết thúc mà không gửi thêm End. Xem [Acceptance Review 6A](Streaming%20MVP%20Phase%206A%20Acceptance%20Review.md), mục 6. Nghiệm thu OBS/audio thủ công vẫn để mở.

## Regression cho hai lỗi đồng bộ UI

- PATCH thành công, chặn GET metadata tiếp theo bằng lỗi mạng hoặc 503: form phải giữ title/category đã lưu, có cảnh báo trạng thái; khi GET phục hồi vẫn đồng bộ đúng. PATCH lỗi phải giữ draft.
- Mở dialog End ở tab A rồi kết thúc phiên từ tab B hoặc chờ deadline: khi tab A nhận ENDED, dialog đóng, preview dừng và credential được xóa; tab A không tự gửi thêm End.
- GET lỗi trong lúc dialog đang mở chưa đủ để coi session là ENDED; dialog vẫn cho phép người dùng thử lại.
