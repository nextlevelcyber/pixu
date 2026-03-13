# Phase D Go/No-Go Checklist

该清单用于发布前最后一公里判断，目标是把“能跑”升级为“可小资金实盘”。

## 1. 必须通过的门禁

- `Phase 1 gate` 通过（OMS 确定性 + 延迟阈值）
- `Phase C gate` 通过（启动 `openOrders` 重建 + REST 对账恢复 + 风控回滚）
- `Phase 6 smoke` 通过（4 进程拓扑健康检查）

建议命令：

```bash
./scripts/phase-d-pack.sh --with-phase6 --phase1-latency-file /tmp/oms-latency.json --phase1-recovery-file /tmp/oms-recovery.json
```

## 2. 性能证据（至少一份）

- 提供 `oms/latency` 快照，包含 `execToState`、`ackToState`、`quoteToAck` 的 p99/p999。
- 指标应与当前策略/交易对规模匹配，且阈值在发布标准内。

## 3. 稳定性证据（至少一份）

- 24h+ 演练报告（断连重连、乱序、重复回报、拒单场景）。
- OMS 重启恢复流程可复现，恢复后订单状态与交易所 `openOrders` 一致。
- 恢复期间 `QUOTE_TARGET` 被门禁阻断，恢复完成后再放行（可通过 `/api/v1/oms/recovery` 验证）。
- 若存在无法映射到本系统 `clientOrderId` 的外部挂单，判定恢复失败（需先清理外部挂单再重试恢复）。

## 4. 风险与变更控制

- 风控参数变更可回滚（`RiskGuard.snapshotLimits()/applyLimits()`）。
- 发布提交已冻结（tag 或 release branch），工作区无未审阅变更。
- 已知风险明确记录并有 owner 与处置计划。

## 5. 决策规则

- 任一门禁失败：**NO_GO**。
- 门禁通过但缺 24h 稳定性证据：**HOLD**（仅允许继续演练，不进实盘）。
- 门禁通过且证据齐全：**GO_CANDIDATE**（进入小资金灰度）。
