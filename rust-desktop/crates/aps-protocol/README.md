# aps-protocol

通过 `#[path]` 使用位于
`desktop-viewer/memory-profiler/hprof-engine-rust/src/wire.rs` 的现有类型化值编解码器源码，
提供有界的工作进程传输信封。它不引入 HPROF 分发逻辑，不把旧引擎请求重新解释为新协议，
也不声称兼容旧的 `version/id/op` 信封。
`aps-contracts` 当前存在传递性的 HPROF 依赖，唯一目的是复用摘要实现。

## 结构定义

Request 恰好包含 `protocolVersion`、`requestId`、`sourceDigest`、`sessionId`、
`generation`、`operation` 和值为映射的 `args`。Response 恰好包含
`protocolVersion`、`requestId`、`sourceDigest`、`sessionId`、`generation`、`status`、
`payload` 和 `error`。协议版本为 1；代数值必须在非负 i64 范围内。
摘要统一为 64 字符的 SHA-256 小写十六进制。类型化 ID 有长度限制。
未知、缺失或类型错误的信封字段，以及重复的原始映射键都会被安全拒绝。
`Response::validate_for` 检查所有关联字段，而不只是请求 ID。

操作名是有界标识符，不是允许列表。每个操作处理器都应提供 `OperationSchema`，
用于拒绝未知、缺失或类型错误的参数，然后自行执行值、授权和资源检查。
通过信封校验的映射不等于获得执行该操作的权限。

## 分帧与预算

帧由四字节大端字节长度及其后的一个类型化 Value 组成。默认限制为：编码字节数 4 MiB、
字符串 256 KiB、值数量 100,000、深度 32；调用方可在现有编解码器的硬限制内进一步收窄。
读取会在分配帧体之前拒绝超限长度。旧编解码器应用其硬解码限制；配置的更小字符串、
数量和深度限制，会在有界解码后立即检查，而不是在其内部分配期间检查。
编码会在编码或写入之前计算精确的字节预算。I/O 失败可能留下部分字节：
应关闭该通道，不要向已部分写入的帧中重试。

`GenerationGuard` 拒绝身份不匹配、过期代数和重复请求 ID，每代最多接受 4096 个 ID。
推进代数需要显式的单调递增状态；它不会取消旧工作进程。

`CancellationState` 是按请求划分的原子协作式生命周期。请求取消后，只有执行方确认，
请求才会被标记为已取消。无论是该标记还是已取消响应，都不能证明操作系统层面的终止、
资源清理、权限授予、凭据访问或沙箱强制约束已经发生。
这些仍由主机运行器和操作所有者负责。

测试已编写但尚未执行，遵循用户要求先完成重写的指示。
