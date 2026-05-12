# Rust Transport Semantics Hardening Design

日期：2026-05-11

状态：Implemented milestone 3

## Goal

把 Bedrock Rust transport 的 ordering、backpressure、failure semantics 明确成代码契约，并让当前 in-proc channel 对齐该契约。

## Scope

本里程碑只修改 `bedrock-rs-transport` 和文档。不实现 Aeron IPC/UDP adapter，不引入外部依赖，不修改 MD reconstruction 行为。

## Design

新增 transport semantics 类型：

- `TransportMode`：`InProc`、`AeronIpc`、`AeronUdp`
- `OrderingGuarantee`：第一阶段只声明 `FifoPerPublisher`
- `BackpressurePolicy`：第一阶段只声明 `RejectWhenFull`
- `FailureSemantics`：第一阶段只声明 `ExplicitClose`
- `TransportSemantics`：组合上述字段，并标记是否 non-blocking

`InProcChannel::semantics()` 返回：

- mode：`InProc`
- ordering：`FifoPerPublisher`
- backpressure：`RejectWhenFull`
- failure：`ExplicitClose`
- non_blocking：`true`

新增 close 语义：

- `close()` 将 channel 标记为 closed。
- closed 后 `publish` 返回 `PublishError::Closed`。
- closed 后 `poll` 仍会先 drain 已经在队列里的事件。
- closed 且队列为空时，`poll` 返回 `PollError::Closed`。

这样上层可以区分三种状态：

- `Ok(Some(event))`：有事件。
- `Ok(None)`：channel open 但暂时无事件。
- `Err(PollError::Closed)`：channel 关闭且没有剩余事件。

## Non-Goals

- 不实现 multi-producer queue。
- 不实现 Aeron binding。
- 不定义 channel naming、stream id、session id。
- 不定义 cross-machine loss recovery。
- 不改变 MD fixture contract。

## Verification

成功标准：

- in-proc FIFO 测试通过。
- full queue backpressure 测试通过。
- closed publish 测试通过。
- closed drain then poll closed 测试通过。
- semantics descriptor 测试通过。
- `cd bedrock-rs && cargo fmt`
- `cd bedrock-rs && cargo test`

