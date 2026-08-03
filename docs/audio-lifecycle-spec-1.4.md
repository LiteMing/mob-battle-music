### AUD-30 [修订 v1.3] 探针格式增补

`world.*` 段增加以下字段：

```
world.pos audible=87.42s decoded=87.74s outLatency=320ms
world.gain final=0.62 track=1.00 mute=0.62 seek=1.00
world.line buffer=88200B fill=56448B watermark=21168B
world.filter active=[underwater_low_pass] mix=1.00 target=1.00
```

- `audible` 为听感位置（AUD-47），`decoded` 为已解码写入位置，`outLatency` 为二者之差
- `gain` 各列为 AUD-44 各条包络的当前值，`final` 为乘积（不含 master/music）
- `mix` 为滤镜干湿混合当前值，`target` 为目标值

所有既有条款中"位置"一词，一律指 audible。

### AUD-44 [新增] 增益包络分离

最终增益必须由多条相互独立的包络相乘构成：

```
finalGain = masterVolume × musicVolume × trackEnv × muteEnv × seekEnv
```

| 包络 | 语义 | 触发源 |
| --- | --- | --- |
| trackEnv | 曲目淡入淡出 | 换曲、起播、停止 |
| muteEnv | 会话静音 | AUD-41 的 MUTED 目标 |
| seekEnv | 校时 seek 门控 | AUD-24 |

禁止：
- 任何布尔值直接决定最终增益（现有 `finalVolume = gameMuted ? 0.0F : ...` 属违反）
- 两个以上意图共用同一个增益变量（现有 `currentVolume` 同时承担曲目淡变与 seek 门控，属违反）
- 采样级清零作为静音手段，除非对应包络当前值已 ≤ 0.001

每条包络的形状固定为 `(current, target, startValue, startMillis, durationMillis)`，
逼近方式为线性插值。

内置音效腿同构：`applyMuteToSoundEngineTracks()` 施加的音量必须为
`muteEnv.current`，不得为常量 0.0F。两条腿共用同一个 `muteEnv` 实例。

### AUD-45 [新增] 包络的收敛语义与线程归属

- AUD-41 的每 tick 收敛下发的是包络的 **target**，不是 current
- current 只能由播放线程推进；tick 线程写 current 一律视为违反
- 设置器（`setMutedForGame` 等）只写标志/target，不得调用任何计算或应用
  增益的方法
- 是否启动一次新的渐变，判据为 `newTarget != env.target`，该判断在包络
  内部完成，不得上浮到收敛器中变成边沿判断（否则退回 AUD-9 v1.2 的错误）
- 增益计算入口在整个进程中有且只有一处，位于播放线程主循环内
- 现有 `setMutedForGame()` → `updateVolumeOutput()` → `updateVolumeWithFade()`
  使 tick 线程与播放线程并发读改写渐变状态，属违反

### AUD-46 [新增] 统一门控过渡原语

所有"需要在静音状态下执行"的动作走同一原语：

```
gatedTransition(fadeOutMillis, action, fadeInMillis)
```

语义固定为：淡出 → 轮询确认增益 ≤ 0.001（不得使用固定 tick 等待）→ 执行
action → 淡入。这是 AUD-24 v1.2 已验证的范式，换曲与静音必须复用，不得
各自实现。

淡变时长按场景配置，且必须非对称：

| 场景 | 淡出 | 淡入 |
| --- | --- | --- |
| idle → aggressive | 200ms | 150ms |
| aggressive → idle | 1200ms | 800ms |
| aggressive → aggressive | 300ms | 300ms |
| 会话静音 / 解除 | 200ms | 200ms |
| seek 门控 | 120ms | 120ms |

例外（强制）：AUD-19 失效停止不走本原语，保持无淡出即停。任何使
AUD-19 停止时延增加的实现均为违反。

### AUD-47 [新增] 听感位置

- 位置查询必须返回听感位置，由 `SourceDataLine.getLongFramePosition()` 推导，
  并叠加 seek/起播偏移
- 禁止以已写入字节数（playedPcmBytes）作为位置来源
- 解码位置作为独立诊断量保留，仅供探针与缓冲诊断使用
- `MarkerClock`、AUD-24 漂移计算、AUD-27 标记计数、AUD-19 时延取证，
  全部使用听感位置
- 现有 `getPositionMillis()` 返回写入位置，系统性超前于听感 200–500ms，属违反

### AUD-48 [新增] 输出水位与滤镜过渡

**输出水位**

- 音频行按目标水位写入，默认 120ms，不得填满缓冲区
- 缓冲区容量与目标水位分离配置；容量保留 underrun 余量，水位决定实际延迟
- `line.open()` 后必须记录 `getBufferSize()` 至日志（已有），并在探针
  `world.line` 行输出容量、当前填充、目标水位

**滤镜过渡**

- 滤镜生效必须经干湿交叉混合：`out = dry × (1 - mix) + wet × mix`
- `mix` 为一条包络，入水 150ms、出水 350ms（可配），禁止在一个缓冲块内
  完成全量切换
- 滤镜链重建时，同类型同参数的处理器必须保留内部状态（biquad 的
  x1/x2/y1/y2、Lofi 的 counters/held）；无法保留时须在新旧链之间做
  ≥20ms 交叉淡变

### AUD-32 [追加] 新增实测场景 S21–S26

| 编号 | 场景 | 判据 |
| --- | --- | --- |
| S21 | LAN 下按 Esc | 增益在 200±40ms 内平滑降至 0，无 click；探针 mute 列连续变化 |
| S22 | LAN 下关闭 Esc 界面 | 增益在 200±40ms 内平滑升回，无 click |
| S23 | idle → aggressive 切换 | 旧曲淡出 200ms 后才起新曲；探针可见 track 列先降后升 |
| S24 | aggressive → idle 切换 | 淡出 1200ms，明显长于 S23 |
| S25 | 潜入水中 / 浮出水面 | filter.mix 在 150ms/350ms 内平滑过渡；无咔哒 |
| S26 | 播放中读取探针 | outLatency 稳定在 100–160ms 区间；decoded - audible 与之一致 |

AUD-32 追加说明：S7、S8、S15 因坐标系变更（AUD-47）需重跑，此前证据作废。
