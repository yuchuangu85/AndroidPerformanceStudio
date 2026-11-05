# Device Screencast

The Device Screencast module mirrors a connected Android device's screen inside the app and lets you control it with the mouse and keyboard. It is useful while inspecting UI behavior, reproducing interactions next to a profiler, or verifying frame problems on a real device.

## Start mirroring

1. Connect a device with USB debugging enabled and authorize the host.
2. Open **Device Screencast** from the home grid or the navigation rail.
3. Pick the device from the dropdown (use **刷新 / Refresh** to rescan) and click **开始投屏 / Start**.

The app pushes a small scrcpy server (about 100 KB) to the device's `/data/local/tmp`, creates an adb reverse tunnel, and starts mirroring. No app is installed on the device and no root is required. Stopping the session or closing the module removes the tunnel and the pushed file.

## Controls

- **Mouse**: click to tap, drag to swipe, wheel to scroll. Coordinates map to the mirrored canvas area (letterboxing is ignored).
- **Navigation**: 返回 / 主页 / 最近任务 buttons in the bottom bar inject Android's back, home, and recents keys.
- **Text**: type in the bottom input field and click **发送到设备 / Send to device** to write the device clipboard and paste it into the focused input field.

## Session states

| State | Meaning |
|---|---|
| Idle | No session; select a device and start. |
| Connecting | Push/tunnel/handshake in progress; cannot be interrupted. |
| Running | Mirroring; canvas shows the live screen. |
| Disconnected | The device or stream dropped; start again to reconnect. |
| Failed | Startup failed; the error message explains what went wrong (for example, an unauthorized device). |

## Troubleshooting

- **"选择设备" is empty**: run `adb devices` to confirm the device is listed as `device` (not `unauthorized`/`offline`), then click Refresh.
- **Startup fails with a server error**: another scrcpy instance may be using the device; stop it and retry.
- **Low frame rate on high-resolution devices**: mirroring uses software H.264 decoding; CPU usage scales with resolution. This is a known trade-off of the embedded decoder.

## License notice

Device Screencast embeds [`scrcpy-server`](https://github.com/Genymobile/scrcpy) (version 3.1), licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). The server jar and its license file are bundled with the application in `device-screencast/screencast-core/src/main/resources/scrcpy/`.
