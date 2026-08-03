### AUD-20 [修订 v1.1] 失效同样作用于试听 —— 依赖方向必须外→内

v1.0 中"这是 AUD-3 的唯一例外"一句**作废**。AUD-3 无例外。

`PreviewChannel` 不得引用 `MusicTracksManager`、playlist 数据结构或任何
失效通知机制。实现方式固定为：

- `PreviewChannel` 暴露只读 `String currentPreviewKey()`（无试听时返回 null）
- 由 GUI / 数据层在条目删除、禁用、URL 变更时比对该 key，
  命中则调用 `PreviewChannel.stop()`

即：试听不知道自己为什么被停，只知道被停了。

### AUD-24 [修订 v1.1] 取消速率纠偏档

三档改两档：

- `|drift| <= 1s`：不干预
- `|drift| > 1s`：seek 到目标位置，seek 前后各 120ms 淡出/淡入以避免爆音

理由：AUD-23 目标为 ±1 秒，速率纠偏（PCM 重采样）的实现成本与回归风险
与该目标不成比例。

**禁止**实现任何形式的播放速率纠偏。已写入的 `rateCorrection` 决策分支、
相关日志与常量一并删除，不得保留为"待启用"。

校时周期固定 5 秒。v1.0 中"（可配置）"一句作废，不做配置项。

### AUD-28 [修订 v1.1] 命名契约更正

`MarkerClock` 契约签名以实现现状为准：

​
void setState(ClockState state);
void realign(String trackId, long startEpochMillis, long serverEpochMillis);

理由：`realign` 携带 `trackId` 可防止跨曲目错误对时，优于 v1.0 的单参形式。
v1.0 中 `freeze()` / `advance(dt)` / `realign(serverPos)` 三个签名作废。
**禁止**为满足字面契约添加转发空壳方法。

`PreviewChannel` 豁免 `pause/resume/mute/unmute`（AUD-14 语义下无意义）。
其契约接口固定为：

​
void play(...);
void stop();
String currentPreviewKey();
boolean isPlaying();

### AUD-35 [新增 v1.1] 通道隔离必须是结构性的

两条通道**不得**共用同一个 track / handle 容器再靠布尔标志
（如 `isPreview()`）区分归属。各通道持有独立集合。

`mute()` / `stop()` / 失效校验的作用域由**集合归属**决定，
不得由 `if (!track.isPreview())` 之类的条件判定决定。

现有 `applyMuteToSoundEngineTracks` 中的 `isPreview()` 排除逻辑
属于违反本条，必须重构为两个独立集合。

理由：条件判定式隔离在后续任何一次遍历新增时都会静默失效，
而这正是本项目原始缺陷的形状。

### AUD-36 [新增 v1.1] 事件顺序不得作为正确性依据

不得依赖 Forge / Minecraft 事件回调之间的相对执行顺序
（例如"onClientTick 注册顺序保证失效校验先于续播"）来保证行为正确。

需要顺序保证时，必须在 MBM 自己的单一 tick 入口内显式编排调用顺序，
并在该入口处以注释说明顺序依赖的原因。

### AUD-37 [新增 v1.1] 提交规约

- **一个任务一个提交**。任务完成即提交，不得累积多阶段成果后一次性提交。
- 构建 / 环境 / 依赖改动必须独立成提交，不得与行为改动混合。
- 二进制文件（如 Gradle 发行包、jar、zip）不得入库，必须列入 `.gitignore`。
- 文档文件名统一为大写 `AGENTS.md`。
- 提交信息格式固定：

​
<type>(audio): <一句话说明>
Spec: AUD-x, AUD-y, AUD-z
Verified: S1, S3, S7

### AUD-38 [新增 v1.1] 证据规约

- "上一轮已验证" **不得**作为本轮证据。
- AUD-11（引擎是否强制挂起音源）与 AUD-19（失效停止时延）的结论
  必须每轮重新验证并附探针输出。
- 行为矩阵相关场景（S1–S6）必须附探针实际输出，
  口头确认不构成 AUD-31 意义上的验收证据。