# Audio Lifecycle Spec — Changelog（历史版本，非当前有效条款）

K7-C 起，被覆盖/修订的旧版本段落移入本文件，仅作历史存档。当前有效条款
一律以 `audio-lifecycle-spec-1.7.md` 正文为准，本文件内容不得视为现行规范。

## AUD-50 v1.0（原版）

「MarkerClock 仅在主通道状态为 PLAYING 且播放器 isPlaying() 为真时参与纠偏决策；
其余状态（PAUSED / MUTED / STOPPED / NO_WORLD）必须为 FROZEN。tick() 必须自身
首行检查 state，不得依赖调用方守卫。

从 PAUSED→PLAYING、MUTED→PLAYING 的迁移，必须以 realign(trackId, 本地位置
反推的锚点, now) 重锚，禁止走 seek 纠偏。

判据：单人 ESC 停留 60s 后恢复，全程日志中 AUD-24 seek 出现 0 次，
clock realign 出现 1 次。」

被 v1.1/v1.2 修订：MUTED 不再是 FROZEN；迁移沿改由显式 reanchorRequested
承载。

## AUD-50 v1.1（修订）

「禁止在同一函数中先无条件收敛某状态、再检测该状态的迁移沿。凡需要『仅在
进入某状态时执行一次』的动作，必须由独立的显式布尔状态承载（如
anchorValid），且该状态的置位/清位点必须在交付物中逐处列出。

时钟纠偏的前置条件为 state == RUNNING && anchorValid。」

被 v1.2 修订（anchorValid 语义细化）。

## AUD-50 v1.2（修订与追加）

「anchorValid 的语义定义为『本地播放位置可用于换算服务端时间』。仅以下
事件清位：进入 PAUSED、stopMusic/invalidate、seek 门控开始。MUTED 不得
清位，且 MUTED 下时钟保持 RUNNING、纠偏正常参与（静音期是纠偏的最优
时机）。」

「新增 reanchorRequested，仅由『PAUSED → PLAYING』迁移沿置位，重锚执行后
清位。重锚块的前置条件另加：非门控进行中、hasActiveTrack()、非
stopRequested、localPosition > 0。」

后续修订：迁移沿扩展为「PAUSED 离开」（previous == PAUSED && target !=
PAUSED），覆盖 PAUSED→MUTED；重锚语义改为本地 self-anchor（K7-B）。

## AUD-52 v1.2（修订）

「限频计数器只统计实际完成的纠偏动作；被 settle window、最小间隔或上限
拒绝的调用不得计数。漂移回到容差内（noCorrection）必须将连续计数清零。

纠偏的重锚必须发生在输出实际到达目标位置之后，锚点时刻取实测水位到达
时刻，禁止使用动作投递时刻。」

「noCorrection 必须将连续计数清零」一条被 K7-C 修订撤销：尝试窗口按实际
发起尝试计数，noCorrection 不清窗口。

## AUD-52 v1.3（修订）

「give-up 使用独立的 correctionDisabled 布尔，不得通过 setState(FROZEN)
表达，不得清 anchorValid，不得触发重锚。该标志仅由 beginPlayback（换曲）
清除。」

## AUD-51（追加）

「凡把原本同步的状态变更改为异步投递，必须同时引入一个在调用方线程立即
可见的意图标志，并让所有相关谓词读取该标志。禁止让调用方通过观察异步
副作用来判断意图是否已生效。」

## AUD-48 v1.4/v1.5/v1.6 与 AUD-30 v1.7/v1.8、AUD-49 #7/#8、AUD-44、
## AUD-45、AUD-38、AUD-39、AUD-53、AUD-54 追加

以上条款的追加/修订已直接并入最终有效条款（audio-lifecycle-spec-1.7.md），
无独立历史差异需要保留。
