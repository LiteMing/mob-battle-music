# MBM 音频生命周期规范 (Audio Lifecycle Spec)

版本: 1.0
状态: 生效中 (Normative)
适用: `nonamecrackers2.mobbattlemusic` 全部客户端播放相关代码

## 0. 阅读与修改规则

- 本文件是 MBM 音频行为的**唯一真相源 (Single Source of Truth)**。
- 代码与本文件冲突时，**以本文件为准**，修改代码而非修改本文件。
- 任何 AI/自动化 agent **只读本文件，禁止修改**。若实现过程中发现规范自相矛盾、
  缺失条款或与 Minecraft API 事实不符，**必须停止编码并向维护者提问**，
  不得自行"合理推断"后继续。
- 条款编号 `AUD-x` 永久稳定，只增不改。废弃条款标记 `[DEPRECATED]` 但保留编号。
- 所有涉及播放/暂停/静音/试听/打轴的 PR，描述中必须逐条列出本次满足的 `AUD-x`。

---

## 1. 术语

| 术语 | 定义 |
| --- | --- |
| 会话 (Session) | 客户端与某个世界的连接周期。从 `ClientLevel` 建立到断开为止。单人存档与远程服务器均视为会话。 |
| 通道 (Channel) | 一组互相独立、拥有各自音量与时钟的播放路径。MBM 恰有两条通道。 |
| 句柄 (Handle) | 一次具体播放的运行时对象，持有音源、增益、当前位置、来源引用。 |
| 时钟 (Clock) | 描述"当前播放到第几秒"的推进器。可被冻结、推进、重对齐。 |
| 打轴标记 (Marker) | 曲目时间轴上的命名时间点，用于与服务端脚本沟通。 |
| 失效 (Invalidation) | 播放中的条目在数据层被删除/禁用/重载，使句柄不再合法。 |

---

## 2. 通道模型

### AUD-1 通道数量固定为二

MBM 恰有且只有两条播放通道，不得新增第三条，不得合并：

| 通道 | 类名 | 绑定对象 | 时钟来源 | 打轴 |
| --- | --- | --- | --- | --- |
| 主播放 | `WorldPlaybackChannel` | 会话 (Session) | 服务端权威时钟 | 是 |
| 试听 | `PreviewChannel` | 客户端 GUI | 本地独立时钟 | 否 |

### AUD-2 主播放通道的生命周期绑定会话，而非 GUI

主播放的启停**只能**由会话状态、条目失效、播放条件三者驱动。
任何 GUI（含播放列表编辑器、暂停界面、聊天框、物品栏）的开关，
除了通过 `MbmSessionState` 间接影响会话状态外，不得直接影响主播放。

### AUD-3 试听通道完全隔离于世界状态

`PreviewChannel` 的实现中**不得出现**对以下任一对象的读取：
`ClientLevel`、`Minecraft#level`、`Minecraft#isPaused`、`MinecraftServer`、
`MbmSessionState`、任何服务端网络包。

试听在主菜单、单人、多人、暂停界面下行为完全一致：只受"用户是否点了试听/停止"控制。

### AUD-4 通道互不静默

启动试听**不得**暂停或静音主播放，反之亦然。两条通道可同时出声。
（音量平衡由用户在设置中各自调节，不做自动闪避 ducking。）

---

## 3. 会话状态机

### AUD-5 唯一判定入口

会话状态由 `MbmSessionState.evaluate()` 计算，每客户端 tick 一次，结果缓存。

**除 `MbmSessionState` 内部实现外，代码库中任何位置不得直接调用**
`Minecraft#isPaused()`、`Minecraft#hasSingleplayerServer()`、
`IntegratedServer#isPublished()`。
违反此条视为实现失败。（可用 grep 自查。）

### AUD-6 状态枚举

​
enum SessionState {
NO_WORLD,               // 无 ClientLevel（标题/多人列表/加载中）
SINGLEPLAYER_RUNNING,   // 单人，未开局域网，游戏逻辑运行中
SINGLEPLAYER_PAUSED,    // 单人，未开局域网，游戏逻辑已冻结
LAN_HOST,               // 单人存档已 publishServer，游戏逻辑持续运行
MULTIPLAYER,            // 连接远程服务器
DISCONNECTED            // 本 tick 刚失去 level，用于触发收尾
}

### AUD-7 判定顺序（严格自上而下，短路返回）

1. `mc.level == null`
   → 上一 tick 有 level 则 `DISCONNECTED`，否则 `NO_WORLD`
2. `mc.hasSingleplayerServer() == false`
   → `MULTIPLAYER`
3. `mc.getSingleplayerServer().isPublished() == true`
   → `LAN_HOST`
4. `mc.isPaused() == true`
   → `SINGLEPLAYER_PAUSED`
5. 其余
   → `SINGLEPLAYER_RUNNING`

### AUD-8 局域网降级原则

一旦存档 `publishServer`，即使玩家仍是唯一在线者、即使打开了暂停界面，
状态也**必须**是 `LAN_HOST`，其音频行为等同于 `MULTIPLAYER`。
理由：世界逻辑仍在推进，音乐停止会与世界事件脱节。

### AUD-9 状态迁移是边沿触发

音频动作在**状态发生变化的那一 tick**执行一次，不得每 tick 重复下发。
状态未变时，通道自行推进，不接受重复指令。

---

## 4. 三种停止语义

### AUD-10 pause / mute / stop 必须是三个独立方法

不得用其中之一实现另一者，不得用 `stop()` + 重新 `play()` 模拟 `pause()`。

| 动作 | 音频输出 | 时钟 | 句柄 | 恢复行为 |
| --- | --- | --- | --- | --- |
| `pause()` | 挂起 | **冻结** | 保留 | `resume()` 后从原位置继续，打轴连续 |
| `mute()` | 增益归零 | **继续推进** | 保留 | `unmute()` 后位置已前进，与世界同步 |
| `stop()` | 停止 | **归零** | 销毁 | 需重新 `play()` |

### AUD-11 mute 必须自管增益

不得依赖 Minecraft `SoundEngine` 的暂停机制实现 mute。
原版 `SoundEngine.tick(isGamePaused)` 会统一挂起通道，会连带冻结播放位置，
与 AUD-10 中 mute 的"时钟继续推进"矛盾。

实现要求：MBM 自行维护 `gain`，mute 时置 0 并保持音源 playing。
若 MBM 播放走原版 `SoundInstance`/`SoundEngine`，**必须先验证**在
`LAN_HOST` + 暂停界面下音源是否被引擎强制挂起；若被挂起，
则改为自管音源（独立 mixer / `AudioStream` 直驱），并在 PR 中说明结论。

### AUD-12 淡入淡出不改变语义

pause/mute 允许 100–200ms 淡出以避免爆音，但淡出期间时钟规则不变
（pause 立即冻结，mute 持续推进）。失效导致的 stop 见 AUD-19。

---

## 5. 行为矩阵

### AUD-13 主播放行为表

| 会话状态 | 窗口聚焦 | 主播放动作 |
| --- | --- | --- |
| `NO_WORLD` | — | `stop()` |
| `SINGLEPLAYER_RUNNING` | 任意 | `play()` / 维持 |
| `SINGLEPLAYER_PAUSED` | 任意 | `pause()` |
| `LAN_HOST` | 聚焦 | 维持播放 |
| `LAN_HOST` | 失焦或暂停界面 | `mute()` |
| `MULTIPLAYER` | 聚焦 | 维持播放 |
| `MULTIPLAYER` | 失焦或暂停界面 | `mute()` |
| `DISCONNECTED` | — | `stop()` + 清空全部句柄 |

### AUD-14 试听行为表

| 会话状态 | 试听通道 |
| --- | --- |
| 全部状态 | 不受影响 |

试听仅在以下情况停止：用户点击停止、切换试听目标、关闭播放列表编辑器、
被试听条目本身被删除（见 AUD-20）、曲目自然播完。

### AUD-15 "失焦"的判定

失焦 = `mc.isWindowActive() == false` 或当前 `Screen` 是暂停界面。
该判定同样收敛在 `MbmSessionState` 内，不得散落各处。

### AUD-16 尊重原版音量设置

主播放与试听均受 `SoundSource.MUSIC`（或 MBM 自定义类别）主音量约束。
用户把音量拉到 0 等价于静音，但**不改变**通道状态与时钟。

---

## 6. 资源失效

### AUD-17 句柄必须携带来源引用

​
record SourceRef(
String playlistId,   // 如 "mobbattlemusic:server/idle/rule/mobbattlemusic/default"
String entryKey,     // 条目稳定标识，不得用列表下标
int revision         // 所属 playlist 的修订号
) {}

`entryKey` 必须在增删排序后保持稳定，禁止使用数组下标作为标识。

### AUD-18 修订号

每个 playlist 持有单调递增 `revision`。以下操作必须 `revision++`：
条目增删、条目启用/禁用、条目 URL 变更、播放顺序模式变更、
playlist 整体重载、服务端下发覆盖。

### AUD-19 失效即停

主播放每 tick 校验活跃句柄：
`handle.sourceRef.revision != currentRevision(playlistId)` 或条目已不存在/已禁用
→ 立即 `stop()`，**不做淡出**，并在同 tick 内按正常选曲逻辑决定是否续播下一曲。

时限要求：从数据变更到出声停止，**不超过 1 秒**。

### AUD-20 失效同样作用于试听

若被试听的条目被删除，试听 `stop()`。这是 AUD-3 的唯一例外，
且判定源是 GUI 侧的数据模型，不是世界状态，因此不违反隔离原则。

### AUD-21 服务端删除必须下发

服务端侧 playlist 变更（命令、脚本、配置重载）必须向客户端广播失效通知，
不得依赖客户端下次拉取时才发现。

---

## 7. 打轴时钟

### AUD-22 权威时钟

主播放时钟以服务端为权威。服务端在开始播放时下发
`(trackId, startEpochMillis, serverEpochMillis)`，客户端据此计算本地播放位置。

### AUD-23 同步精度目标

多人环境下目标精度为 **±1 秒**。不追求 tick 级精确。

### AUD-24 校时与纠偏

- 校时包周期：每 5 秒一次（可配置）。
- `|drift| <= 1s`：不干预。
- `1s < |drift| <= 3s`：以 ±5% 播放速率软纠偏，禁止 seek。
- `|drift| > 3s`：直接 seek 到目标位置。

### AUD-25 状态对时钟的影响

- `mute` 期间时钟持续推进，`unmute` 后位置应已与世界同步。
- `pause` 期间时钟冻结；`resume` 时**必须**向服务端请求一次校时并按 AUD-24 纠偏。
  （单人暂停时服务端同样冻结，通常 drift 为 0，但不得假设。）
- `DISCONNECTED` 时钟销毁。

### AUD-26 试听不接时钟

`PreviewChannel` 使用本地单调时钟，不接收校时包，不上报进度。
编辑器中的标记预览仅为可视化，不代表服务端行为。

### AUD-27 标记触发只在主播放

打轴标记的触发事件（通知服务端脚本）**只能**由 `WorldPlaybackChannel` 发出。
试听触发标记会造成脚本误触发，属严重缺陷。
标记在 mute 期间**照常触发**（世界在运行），在 pause 期间**不触发**。

---

## 8. 命名契约

### AUD-28 以下标识符为契约，不得重命名或合并

​
MbmSessionState              // 唯一状态判定入口
.evaluate() / .current() / .isFocused()
SessionState                 // 枚举，成员见 AUD-6
WorldPlaybackChannel         // 主播放通道
PreviewChannel               // 试听通道
PlaybackHandle               // 播放句柄，持有 SourceRef
SourceRef                    // 见 AUD-17
MarkerClock                  // 打轴时钟
.freeze() / .advance(dt) / .realign(serverPos)

通道公共接口固定为：
​
void play(...);
void pause();
void resume();
void mute();
void unmute();
void stop();

### AUD-29 禁止项清单

1. `MbmSessionState` 之外调用 `Minecraft#isPaused()` / `hasSingleplayerServer()` / `isPublished()` / `isWindowActive()`
2. `PreviewChannel` 中引用任何世界或网络对象（AUD-3）
3. 用 `stop()+play()` 模拟 `pause()/resume()`
4. 用列表下标作为 `entryKey`
5. 在 GUI 类中直接操作 `WorldPlaybackChannel`（必须经由服务/事件层）
6. 为"临时修复"添加布尔开关绕过状态机

---

## 9. 调试探针

### AUD-30 `/mbm debug session`

客户端命令，输出当前完整音频状态，格式固定为逐行 `key=value`：

​
session=LAN_HOST focused=false paused=false published=true
world.state=MUTED gain=0.00 track=mobbattlemusic:xxx pos=87.42s
world.clock=RUNNING drift=+0.31s lastSync=2.1s_ago
world.ref=playlist=mobbattlemusic:default entry=e_7f3a rev=12
preview.state=PLAYING pos=13.05s track=<url>
handles=1 orphaned=0
markers.fired=3 markers.next=chorus@92.0s

### AUD-31 探针是验收依据

后续所有 PR 的验收以该命令输出为准，不以"听起来对"为准。
探针本身必须先于行为改动落地。

---

## 10. 标准验收场景

### AUD-32 回归清单（每个音频相关 PR 必跑）

| # | 场景 | 期望 |
| --- | --- | --- |
| S1 | 单人存档，开暂停界面 5 秒后关闭 | 音乐暂停；恢复后位置连续（约 = 暂停前位置） |
| S2 | 单人存档，Alt+Tab 切出 5 秒 | 音乐暂停（单人失焦即暂停界面语义） |
| S3 | 单人存档 → 开启局域网 → 开暂停界面 5 秒 | 音乐**静音不暂停**；关闭后位置已前进约 5 秒 |
| S4 | 局域网开放中，Alt+Tab 切出 5 秒 | 同 S3 |
| S5 | 连接远程服务器，开暂停界面 | 静音；位置继续推进 |
| S6 | 主菜单 | 无主播放；试听可正常播放 |
| S7 | 播放中在编辑器删除当前曲目 | ≤1 秒内停止，按规则续播下一曲或静默 |
| S8 | 播放中禁用当前曲目 | 同 S7 |
| S9 | 主播放进行中点试听 | 两者同时出声，主播放不受影响 |
| S10 | 试听进行中开暂停界面 | 试听继续播放 |
| S11 | 退出存档回主菜单 | 主播放停止，`handles=0`，`orphaned=0` |
| S12 | 断线重连 | 无残留句柄，重新按条件起播 |
| S13 | 多人，打轴标记 92s，全程静音跨越该点 | 标记照常触发一次 |
| S14 | 单人，暂停界面跨越标记时间点 | 不触发；恢复后到达该点时触发 |
| S15 | 人为注入 5s drift | 观察到一次 seek，之后 drift ≤1s |

---

## 11. 变更流程

### AUD-33
新增行为必须先在本文件加条款，再实现。PR 中"顺手加的行为"若无对应条款，视为超范围，应退回。

### AUD-34
实施顺序固定为三阶段，不得合并：
1. **P1 观测**：`MbmSessionState` + `/mbm debug session`，零行为改动
2. **P2 通道**：通道分离 + pause/mute/stop 三分 + 行为矩阵
3. **P3 数据与时钟**：失效机制 + 打轴时钟同步