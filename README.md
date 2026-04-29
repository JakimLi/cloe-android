# Cloe Android

Cloe 的 Android 悬浮窗客户端。通过 Tailscale 组网连接 PC 端的 Bridge，远程展示 Cloe 的 GIF 动画。

## 架构

```
Hermes Agent (PC) → Bridge (PC:19851) → Tailscale → Android App (悬浮窗)
```

- GIF 打包在 APK 内（`assets/gifs/`），不依赖网络加载
- WebSocket 仅传输 action 指令（几十字节 JSON）
- Tailscale split tunnel，不影响手机正常网络

## 构建

```bash
./gradlew assembleDebug --no-daemon
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 手机装 Tailscale，登录与 PC 同一账号
2. 安装 APK，授予悬浮窗权限
3. 输入 PC 的 Tailscale IP（如 `100.x.x.x`），点连接
4. PC 端启动 Cloe Desktop（bridge 自动监听 `0.0.0.0:19850`）

## 功能

- ✅ 悬浮窗显示 GIF 动画
- ✅ idle 随机循环（8-15秒切换）
- ✅ working 模式（敲键盘）
- ✅ 拖动定位
- ✅ 点击缩成小圆点（退下），点击圆点展开（召唤）
- ✅ 自动重连 WebSocket
- 🔲 speak 动画音频播放
- 🔲 断线重连通知
