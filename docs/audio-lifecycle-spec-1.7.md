### AUD-44【追加 v1.1】起播必须从零开始

新声源起播时，音轨包络必须无条件从 0 重启（`setTarget(0,0)` →
`setTarget(1,fadeIn)`），淡入时长为"门控登记的时长"，无登记时为播放器默认
fadeTime。禁止依赖包络的历史值决定是否淡入。

判据：任意起播路径（首曲 / 门控换曲 / 自然播完重建 / stop 后重进）探针
`world.gain track=` 首帧必须 ≤0.05。

### AUD-45【修订 v1.2】"目标未变即早退"只是优化，不是语义

`setTarget` 的早退仅适用于"意图声明"型调用。凡是契约要求"必须发生一次
确定时长的可闻渐变"的调用点，必须使用强制重启接口（`forceFade`），不得
依赖 `setTarget`。

理由记录：v1.1 让门控用 setTarget 排淡出，当另一写入者已把 target 置为
同值时门控的时长被静默吞掉。

### AUD-49 #7【新增】包络写入权排他

一个包络在被门控占用期间，只有门控可以写它。其他写入者（选择引擎的音量
投影等）在检测到"当前门控占用的正是该包络"时必须为 no-op 并记日志。
门控排入淡出必须用强制重启接口。

判据：grep 所有 Envelope 的写入点（setTarget/forceFade/hardReset），逐处
标注"门控占用时的行为"，不得存在"占用期间仍然写入"的路径。

### AUD-49 #8【新增】待起播淡入时长必须会过期

`pendingTrackFadeInMillis` 类的"留给下一次起播"的状态必须带登记时刻，
超过 2000ms 未被消费即清除。禁止无限期悬挂的跨事件状态。

### AUD-48【追加 v1.3】链重建的状态保留判定必须包含链长

`preservedAll` 必须同时要求"逐位置参数相同"与"链长相等"。新链为旧链
前缀/后缀时必须走交叉淡变。

### AUD-30【追加 v1.7】

`world.gain` 行追加 `trackTarget=<0.00~1.00> gateOwner=<none|track|seek>`
——让"谁在写包络"可观测。这是 M12 这类问题唯一能被实测抓到的方式。

### AUD-50【新增】时钟推进必须与会话状态绑定

"MarkerClock 仅在主通道状态为 PLAYING 且播放器 isPlaying() 为真时参与纠偏决策；其余状态（PAUSED / MUTED / STOPPED / NO_WORLD）必须为 FROZEN。	ick() 必须自身首行检查 state，不得依赖调用方守卫。

从 PAUSED→PLAYING、MUTED→PLAYING 的迁移，必须以 ealign(trackId, 本地位置反推的锚点, now) 重锚，禁止走 seek 纠偏。

判据：单人 ESC 停留 60s 后恢复，全程日志中 AUD-24 seek 出现 0 次，clock realign 出现 1 次。

### AUD-51【新增】门控动作禁止阻塞主线程

inishGate 中 ction.run() 的时间预算为 ≤1ms，只允许状态写入与任务投递。以下工作一律禁止出现在 action 同步路径中：SourceDataLine 的 open/close/stop、Thread.start()、文件与网络 I/O、音频解码、LOGGER.info 及以上级别日志。

判据：gatedTransition( 每个调用点必须在交付物中标注其 action 的最坏耗时与依据。

### AUD-52【新增】纠偏必须限频、迟滞、可放弃

两次 seek 最小间隔 5000ms；seek 完成后 2000ms settle window 内不测量 drift；同一 	rackId 连续 3 次 seek 后 drift 仍超阈值，则置 FROZEN 并 LOGGER.warn，本曲不再纠偏。

判据：任意场景下 AUD-24 seek 日志的相邻时间戳差 ≥5s。

### AUD-53【新增】调试注入状态必须自动过期

injectedDriftSeconds 等调试注入值必须带登记时刻，60s 后自动归零；世界卸载（WorldPlaybackChannel.reset()）时无条件归零。探针 injected= 追加剩余有效期。

### AUD-48【追加 v1.4】滤镜响应延迟必须可分解、可测

MIX_IN_MILLIS / MIX_OUT_MILLIS / 输出水位三者构成滤镜切换的听感延迟预算，必须在探针中分项输出，总预算上限 300ms。输出水位必须自适应：初始 60ms，检测到 underruns 递增则 +20ms，上限 150ms。

### AUD-30【追加 v1.8】"world.filter 行追加 latency=<tick+buffer+ramp>ms；world.clock 行追加 state=<STOPPED|RUNNING|FROZEN> seeks=<n> sinceSeek=<ms> injectedTtl=<ms>。
