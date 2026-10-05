将1.20.3引入的[tick](https://zh.minecraft.wiki/w/%E5%91%BD%E4%BB%A4/tick)指令在1.20.1-neoforge、1.20.1-forge、1.19.2-forge、1.18.2-forge上实现<br>
查询当前的游戏刻流逝状态及目标游戏刻速率，以及有关游戏刻的性能数据，包括每游戏刻的平均用时和用时的百分位数。<br>
<summary><code>tick query</code></summary><br>
设置目标游戏刻速率。<br>
<summary><code>tick rate <rate></code></summary><br>
冻结游戏刻的流逝和所有游戏元素，玩家及玩家骑乘的实体除外。<br>
<summary><code>tick freeze</code></summary><br>
取消冻结游戏刻。<br>
<summary><code>tick unfreeze</code></summary><br>
取消冻结后，进行步进特定数量的游戏刻，然后恢复冻结。仅能在已冻结的情况下使用。<br>
<summary><code>tick step [time]</code></summary><br>
停止正在进行的游戏刻步进，并重新冻结游戏。<br>
<summary><code>tick step stop</code></summary><br>
快进。忽略目标游戏刻速率，使游戏刻的流逝尽可能快，并在指定时间后恢复至此命令执行之前的游戏刻流逝状态。恢复后游戏会显示此过程中的游戏刻性能信息。<br>
<summary><code>tick sprint [time]</code></summary><br>
停止正在进行的快进。<br>
<summary><code>tick sprint stop</code></summary><br>
