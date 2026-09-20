# Network Profiler

## 功能介绍

Network Profiler 通过 Android Agent 采集 HTTP/HTTPS 请求活动，也支持导入 HAR 文件。它用于定位请求延迟、失败、连接复用、TLS、响应大小和并发变化。

## 详细设计方案

```text
OkHttp/EventListener Agent 或 HAR import
  -> versioned network protocol
  -> network model
  -> parser/normalizer
  -> timeline + request detail + summary
  -> redacted export / artifact
```

### 模块职责

- `network-profiler/android-agent-network`：设备端 Agent、事件缓冲、脱敏和 socket server。
- `network-agent-protocol`：命令、响应、cursor、token 和 event codec。
- `capture-network`：ADB forward、session、poll 和 artifact 拉取。
- `parser-har`：HAR 导入。
- `analysis-network`：请求时间、连接复用、并发和失败统计。
- `network-app` / `presentation`：实时会话、请求列表和详情。
- `network-export-adapters`：HAR/JSON/CSV 等导出。

## 使用方案

### Agent 模式

1. 将 Agent 以 debug 依赖加入目标 App。
2. 启动 App 并选择设备/进程。
3. 开始 Network Capture。
4. 执行业务操作，等待事件轮询。
5. 查看请求时间线和详情。

### HAR 模式

1. 选择 HAR 文件。
2. 导入并等待 parser 完成。
3. 按 URL、方法、状态、时间和连接过滤。
4. 导出归一化结果。

## 输出与限制

- 默认应进行 URL、Header、Query 和 Body 脱敏；原始网络证据不能默认当作安全可分享数据。
- Agent 模式依赖目标 App 的 debug 接入；未接入时使用 HAR 或其他系统级 trace。
- 网络时间和设备单调时钟的映射必须保留，不能直接将主机时间与设备时间混用。
