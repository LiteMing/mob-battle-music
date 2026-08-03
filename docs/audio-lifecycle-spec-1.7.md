# Audio Lifecycle Spec（最终有效条款）

K7-C 整理版：本文件只含最终有效条款，不再追加互相覆盖的段落。历史修订记录
（旧版本原文）见 `audio-lifecycle-spec-changelog.md`，不得写回本文件。

## AUD-22 起播/恢复上报

客户端在起播与暂停恢复时向服务端上报播放事件（trackId、起播时刻、
session generation）。K7-B 起该上报是**纯锚通知**，不是时钟探针：
探针职责由独立的 ClockOffsetProbeRequest/Response 包承担，锚上报包内
没有任何 t1/t2/t3/t4 字段。服务端回锚确认包（PlaybackClockSyncPacket），
客户端仅作生成代数校验，**不得**因此 re-anchor（锚是本地自锚）。

## AUD-23 机器钟差校准已实现，跨客户端共同播放轴未实现

当前实现完成的是「客户端本地自锚 + 连接级机器钟差校准」。校准由专用
ClockOffsetProbeRequest/Response 对完成：有界 probe burst，每 60 秒窗口
至多 5 次请求（0/100/250/500/1000ms），最大网络负担每分钟 5 C2S + 5 S2C。
offset 状态为 UNKNOWN（无合法样本，offsetMillis() 返回 NaN，禁止当作 0.0）
/ PROVISIONAL（1-4 样本，取当前最小 RTT 样本）/ CALIBRATED（≥5 样本）。

探针响应（t4 在客户端网络线程、主线程排队前捕获）直接喂线程安全的
estimator，**绝对不得调用 MarkerClock.realign*()**。响应必须携带
connection/session nonce，旧连接或旧窗口的响应不得进入当前 estimator。

跨客户端的共享播放轴（多人 ±1s 共同轴）**未实现**：锚是每客户端自锚，
两个客户端之间没有共享 canonical start。连接 offset 只为未来 CUE canonical
server anchor 准备。任何声称「多人 ±1s 已完成」的文字均不成立。K7-B 完成前
不应进行同步验收；普通多人通常只有一个探针样本，offset 以 UNKNOWN/PROVISIONAL
存在，不得参与任何跨时钟域锚定。

## AUD-24 纠偏 seek

漂移超阈（|drift| > 1s）时经门控 seek 纠偏。重锚必须发生在输出实际到达
目标位置之后，锚点时刻取实测水位到达时刻，禁止使用动作投递时刻
（AUD-52 v1.2）。

## AUD-30 探针行格式

- `world.gain` 行追加 `trackTarget=<0.00~1.00> gateOwner=<none|track|seek>`
  ——让「谁在写包络」可观测。
- `world.filter` 行追加 `latency=<tick+buffer+ramp>ms`。
- `world.clock` 行追加 `state=<STOPPED|RUNNING|FROZEN> seeks=<n>
  sinceSeek=<ms> injectedTtl=<ms>`。

## AUD-38 交付物标准

走查表必须包含失败/边界路径。

## AUD-39 流程纪律

1. 注释与代码不符视同静默偏差。
2. 明知与现行 spec 冲突的改动，不得先落地再请求决策；必须先提出条款修订，
   获批后再实现。

## AUD-44 起播必须从零开始

新声源起播时，音轨包络必须无条件从 0 重启（`setTarget(0,0)` →
`setTarget(1,fadeIn)`），淡入时长为「门控登记的时长」，无登记时为播放器
默认 fadeTime。禁止依赖包络的历史值决定是否淡入。

判据：任意起播路径（首曲 / 门控换曲 / 自然播完重建 / stop 后重进）探针
`world.gain track=` 首帧必须 ≤0.05。

## AUD-45 目标未变早退只是优化，不是语义

`setTarget` 的早退仅适用于「意图声明」型调用。凡是契约要求「必须发生一次
确定时长的可闻渐变」的调用点，必须使用强制重启接口（`forceFade`），不得
依赖 `setTarget`。

## AUD-48 滤镜/链重建/水位

1. **链重建状态保留判定必须包含链长**：`preservedAll` 必须同时要求「逐位置
   参数相同」与「链长相等」。新链为旧链前缀/后缀时必须走交叉淡变。
2. **滤镜响应延迟必须可分解、可测**：MIX_IN_MILLIS / MIX_OUT_MILLIS /
   输出水位三者构成滤镜切换的听感延迟预算，探针分项输出，总预算上限
   300ms。输出水位自适应：初始 60ms，underrun 递增则 +20ms，上限 150ms。
3. **自适应水位必须双向**：underrun 判定排除暂停恢复、seek 后与播放起始
   的瞬态窗口（前 8 个缓冲块）；连续 10s 无 underrun 则 −10ms，下限 60ms。
   禁止只升不降的自适应。
4. **状态生命周期对齐**：自适应水位的所有协同状态（水位值、末次 underrun
   时刻、瞬态窗口基准）必须具有相同的生命周期作用域；underrun 瞬态窗口对
   paused 与 gamePaused 一视同仁。

## AUD-49 门控

1. **#7 包络写入权排他**：一个包络在被门控占用期间，只有门控可以写它。
   其他写入者（选择引擎的音量投影等）在检测到「当前门控占用的正是该包络」
   时必须为 no-op 并记日志。门控排入淡出必须用强制重启接口。
   判据：grep 所有 Envelope 的写入点（setTarget/forceFade/hardReset），
   逐处标注「门控占用时的行为」，不得存在「占用期间仍然写入」的路径。
2. **#8 待起播淡入时长必须会过期**：`pendingTrackFadeInMillis` 类的「留给
   下一次起播」的状态必须带登记时刻，超过 2000ms 未被消费即清除。禁止
   无限期悬挂的跨事件状态。
3. **#4 门控槽占用**：slot 忙时显式失败，调用方必须回滚本地状态；AUD-24
   路径的显式失败只需下一 tick 重新评估 drift，无状态可回滚。
4. **gate 所有权**：门控对淡出/淡入拥有排他所有权；淡出无条件重启
   （AUD-45 v1.2），即使另一写入者把 target 留在了同值。

## AUD-50 时钟推进与会话状态绑定（最终语义）

1. MarkerClock 仅在主通道状态为 PLAYING 且播放器 isPlaying() 为真时参与
   纠偏决策；其余状态（PAUSED / STOPPED / NO_WORLD）为 FROZEN。
   **MUTED 例外（最终语义）：MUTED 下时钟保持 RUNNING，纠偏正常参与**
   （静音期是纠偏的最优时机）。
2. `tick()` 必须自身首行检查 state，不得依赖调用方守卫。
3. **anchorValid 语义**（最终）：「本地播放位置可用于换算服务端时间」。
   仅以下事件清位：进入 PAUSED、stopMusic/invalidate、seek 门控开始
   （K7-A 后为 `captureAndInvalidateForSeek` 原子捕获）。MUTED 不得清位。
   时钟纠偏的前置条件为 state == RUNNING && anchorValid。
4. **重锚必须是显式请求**：`reanchorRequested` 仅由「PAUSED 离开」迁移沿
   （previous == PAUSED && target != PAUSED）置位，重锚执行后清位。重锚块
   的前置条件：非门控进行中、hasActiveTrack()、非 stopRequested、位置
   > 0、track 与当前播放一致。
5. **PAUSED 离开只允许一次本地 self-anchor realign**（K7-B）：
   `realignLocalSelfAnchor(trackId, localPosition, now)` —— 锚是本地自锚，
   不需要先转成服务端域再相减；clock probe 回包不得造成第二次 realign。
   禁止走 seek 纠偏。起播（beginPlayback）时在位置 0 自锚一次。
6. **纠偏 seek 失败锚恢复**（K7-A）：失败 seek 只能在原子 token
   （sourceVersion + trackId + session generation）仍与当前 source 一致时
   恢复旧锚；换曲、新锚、卸载、成功 seek 后 token 必然失效。

判据：单人 ESC 停留 20s 后恢复，全程日志中 AUD-24 seek 出现 0 次，
clock local self-anchor realign 出现 1 次（起播自锚另计）。

## AUD-51 门控动作禁止阻塞主线程

`finishGate` 中 `action.run()` 的时间预算为 ≤1ms，只允许状态写入与任务
投递。以下工作一律禁止出现在 action 同步路径中：SourceDataLine 的
open/close/stop、Thread.start()、文件与网络 I/O、音频解码、LOGGER.info
及以上级别日志。

判据：gatedTransition( 每个调用点必须在交付物中标注其 action 的最坏耗时
与依据。

凡把原本同步的状态变更改为异步投递，必须同时引入一个在调用方线程立即
可见的意图标志，并让所有相关谓词读取该标志。禁止让调用方通过观察异步
副作用来判断意图是否已生效。

## AUD-52 纠偏限频、迟滞、可放弃（最终语义）

1. **限频**：两次 seek 最小间隔 5000ms；seek 完成后 2000ms settle window
   内不测量 drift。
2. **按尝试计数**：计数只统计实际发起的纠偏尝试（速率限制写入在排队时刻
   即「尝试」）。被 settle window、最小间隔或上限拒绝的调用不得计数
   （抑制不是尝试）。**noCorrection（漂移回到容差）不清尝试窗口**——窗口
   仅由时间自然过期与 beginPlayback（换曲）清零。
3. **可放弃**：同一 trackId 连续 3 次尝试后 drift 仍超阈值，置
   `correctionDisabled` 并 LOGGER.warn，本曲不再纠偏。give-up 使用独立的
   correctionDisabled 布尔，**不得通过 setState(FROZEN) 表达，不得清
   anchorValid，不得触发重锚**。该标志仅由 beginPlayback（换曲）清除；
   带 backoff 的自动恢复见实现（恢复时清空频率窗口，防止立即重触发）。
4. **跨代时间戳必须带代号**：lastWatermarkReachedAtMillis 一类「本代首次
   到达」的时间戳必须与 playbackGeneration 一同发布，消费方必须校验代号；
   stop() 必须清零。

判据：任意场景下 AUD-24 seek 日志的相邻时间戳差 ≥5s。

## AUD-53 调试注入状态必须自动过期

injectedDriftSeconds 等调试注入值必须带登记时刻，60s 后自动归零；世界
卸载（WorldPlaybackChannel.reset()）时无条件归零。探针 injected= 追加
剩余有效期。

## AUD-54 环形缓冲字段集必须是单帧探针的超集

debug session 能输出的字段，ring 必须全部包含。格式化不得在持有采样锁
的情况下进行。
