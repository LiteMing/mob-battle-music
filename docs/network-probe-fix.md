# 修复联机时钟探针重复处理

联机客户端每分钟接收一组 5 个时钟探针回包。原来的网络线程回调没有设置
`setPacketHandled(true)`，Forge 因而继续走原版分支：回包再次在 Render 线程
执行，重复增加校准样本，并打印 `Unknown custom packet identifier: mobbattlemusic:main`。
修复前实例日志的采样快照中有 145 条此警告、145 次 Netty 采样和 145 次 Render 采样。

两个实际注册的探针回调现在都在提前返回前标记包已处理。t2、t4 仍在网络线程
捕获；响应携带的连接对象、nonce 和窗口有效期共同决定是否接受样本。校验、
采样、窗口切换及断线清理共享同一把锁，发送操作在锁外进行。

协议版本升级为 17，覆盖已经加入的 `MBMDebugPacket`。客户端和服务端均需
更新；缺少 MBM 的对端仍可连接。探针日志的 RTT 公式也已与 estimator 对齐。

## 满足条款

- AUD-22：专用探针对承担校准，起播/恢复上报仍为纯锚通知。
- AUD-23：每 60 秒窗口最多 5 次请求，按 0/100/250/500/1000ms 预留槽位；
  t4 在网络线程进入同步区前捕获；旧连接、旧 nonce 和到期窗口的响应被丢弃；
  新窗口及断线原子清空 estimator，UNKNOWN 保持 NaN，5 个有效样本才校准。
- AUD-38：回归覆盖正常、丢弃、无 sender、断线、换连接、换窗口、延迟 tick、
  到期后无 tick，以及响应与 reset 的两种并发顺序。
- AUD-39：删除将此警告归因于 Forge 版本或单人 LocalConnection 的错误注释，
  并修正探针 RTT 日志公式。
- AUD-50 第 5 条：探针仅写连接级 estimator，不调用 MarkerClock 的 realign 或 seek。

状态读取点：

| 字段 | 全部生产读取点 |
| --- | --- |
| `activeConnection` | `prepareBurst` 检查断开和连接切换；`handleProbeResponse` 校验回包连接 |
| `windowStartMillis` | `prepareBurst` 检查窗口和槽位时间；`handleProbeResponse` 检查窗口有效期 |
| `probesSentThisWindow` | `prepareBurst` 计算未发送的槽位并限制总数 |
| `currentNonce` | `prepareBurst` 为新窗口递增并写入发送预约；`handleProbeResponse` 校验；`reset` 递增失效 |
| `ProbeBurst` 四个分量 | `tick` 使用 nonce 组包、firstSlot/limit 遍历发送、windowAgeMillis 输出诊断 |

上述可变字段统一受锁保护；新增状态没有布尔复用或无人消费的枚举。

## 验收

按本次确认的范围，以 `audio-lifecycle-spec-1.7.md` 和网络回归/构建验证交付。
原指定规范路径及现行文件中的 AUD-29/AUD-32 缺失；本次未修改规范。
未执行游戏内音频验收，因此没有修复后的 `/mbm debug session` 实机输出。

Java 17.0.15、Forge 47.4.16，执行 `gradlew --offline --console=plain check jarJar`。
`networkRegressionTest` 使用真实 Forge 编解码、事件总线和注册回调；单独的
测试入口不启动游戏。实际输出：

```text
PASS both registered probe consumers mark packets handled
PASS discarded replies are handled without sampling
PASS burst slots and the 60-second limit
PASS a delayed tick cannot exceed five requests
PASS reset rejects replies before the next tick
PASS reconnect rejects an old connection and nonce
PASS window rollover clears calibration and rejects old replies
PASS expired replies are rejected without a main-thread tick
PASS five dispatched replies yield five calibration samples
PASS reset wins a race with a queued response
PASS reset waits for an already validated sample
PASS protocol 17 is required while missing peers remain optional
MBM network regression checks: 12 passed
BUILD SUCCESSFUL in 23s
```

阈值触发覆盖：逐一验证槽位前 1ms 与到达槽位时的差异；验证第 5 次之后不再
预留发送；验证 59999ms 不换窗、60000ms 换窗；网络线程在窗口到期但主线程尚未
tick 时也拒绝回包。并发测试分别让 reset 先完成，以及让 reset 等待已通过校验
的 sample 完成，两种顺序最终都保持 UNKNOWN/NaN。

完整构建日志位于 `build/reports/network-fix-build.log`。`jarJar` 与 `reobfJarJar`
均完成，产物为 `mobbattlemusic-1.20.1-1.1.1.2-20260914-081041-all.jar`。
