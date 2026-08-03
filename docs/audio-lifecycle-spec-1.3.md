### AUD-9 [修订 v1.3] 幂等收敛，取代边沿触发

v1.0 的"音频动作只在状态变化的那一 tick 执行一次"**作废**。该表述导致
任何遗漏的迁移组合成为不可恢复的永久错误状态（已实测：PAUSED → LAN_HOST）。

改为：

- 每客户端 tick 计算目标通道状态 `target = f(session, focused)`（AUD-41）
- 每 tick 调用 `converge(target)`，该函数**无条件下发目标状态所需的全部
  播放器标志**，不比较当前状态、不做增量判断
- `converge` 必须幂等：重复调用与调用一次结果相同，且无重复副作用
- 边沿判定**仅**允许用于日志输出与真正的一次性副作用
  （如 `reportPlaybackStart`），不得用于决定是否下发状态

**禁止**任何形式的"只在状态变化时才施加音频状态"的实现。

### AUD-41 [新增 v1.3] 目标状态表与收敛动作表

**目标状态** `target = f(session, focused)`：

| session | focused | target |
| --- | --- | --- |
| NO_WORLD / DISCONNECTED | 任意 | STOPPED |
| SINGLEPLAYER_PAUSED | 任意 | PAUSED |
| SINGLEPLAYER_RUNNING | 任意 | PLAYING |
| LAN_HOST / MULTIPLAYER | true | PLAYING |
| LAN_HOST / MULTIPLAYER | false | MUTED |

**收敛动作**：每 tick 按目标无条件下发下列全部四项，不得省略任何一项：

| target | gamePaused | gameMuted | mainPlaybackMuted | MarkerClock |
| --- | --- | --- | --- | --- |
| STOPPED | false | false | false | invalidate |
| PAUSED | **true** | false | false | FROZEN |
| PLAYING | **false** | false | false | RUNNING |
| MUTED | **false** | **true** | **true** | RUNNING |

注意 MUTED 的 `gamePaused` 必须显式为 `false`——这正是 v1.2 实现中
`unmute()` 遗漏 `resumeFromGame()` 导致死锁的位置。

### AUD-42 [新增 v1.3] 播放器标志复位契约

`gamePaused` / `gameMuted` 是**通道意图的投影**，不是播放器的自有状态。
因此：

1. `StreamMusicPlayer.startPlayback()` 必须在起播时复位
   `gamePaused = false; gameMuted = false;`
   新一代播放不得继承上一代的暂停/静音意图。
   起播后由通道在同 tick 内按 AUD-41 重新施加目标状态。
2. `StreamMusicPlayer.stop()` 必须复位 `gamePaused = false; gameMuted = false;`
3. `resumeFromGame()` / `pauseForGame()` / `setMutedForGame()` **不得**带
   `playing` 守卫。标志的写入必须无条件；`line.start()` / `line.stop()`
   这类硬件操作才允许带存活性判断。
4. `WorldPlaybackChannel.stop()` 中，标志复位必须在 `stopMusic()`
   **之前或与之无关地**执行，不得依赖 `stopMusic()` 之后仍满足守卫条件。

**通用规则**：任何"清理状态"的方法，其守卫条件不得包含会被清理动作本身
置为 false 的变量。

### AUD-43 [新增 v1.3] 存活性谓词与发声谓词必须分离

`isPlaying()`（= `playing && !paused && !gamePaused`）表达的是"当前是否出声"，
**不得**用于判断"轨道是否仍存活"。

`syncHandle()` 等观测逻辑必须使用独立的存活性谓词
（如 `hasActiveTrack()` = `playing`，与暂停/静音无关）。
把"被暂停"误判为"已结束"会导致句柄被清除、通道锁死在 STOPPED。

### AUD-32 [追加 v1.3] 新增回归场景

| # | 场景 | 期望 |
| --- | --- | --- |
| S16 | 播放中 Esc → **在暂停界面内点"对局域网开放"** | 音乐立即恢复出声并转为 LAN_HOST 语义；`gamePaused=false` |
| S17 | 播放中 Esc → 保存并退出 → 重进同一存档 | 重进后音乐能正常起播 |
| S18 | 播放中 Esc → 保存并退出 → 进入**另一个**存档 | 同 S17 |
| S19 | 播放中 Esc → 保存并退出 → 连接远程服务器 | 正常起播，且为 MULTIPLAYER 语义 |
| S20 | 暂停界面停留期间当前曲目自然播完 → 关闭暂停界面 | 通道不锁死，下一曲正常起播 |

S16–S20 为**迁移组合**场景。AUD-41 目标状态表的每一对可达迁移
（含经由 PAUSED 的中转）都必须至少被一个场景覆盖。