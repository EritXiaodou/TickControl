将1.20.3引入的[tick](https://zh.minecraft.wiki/w/%E5%91%BD%E4%BB%A4/tick)指令在1.20.1-neoforge、1.20.1-forge、1.19.2-forge、1.18.2-forge、1.17.1-forge、1.16.5-forge、1.12.2-forge、1.7.10-forge上实现<br>
其中1.12.2包含两个两个版本:<br>
内置mixin与使用mixinbooter的版本<br>
两者可以同时添加，同时添加时内置mixin的版本惰性加载，mixinbooter优先加载<br>
如果你下载的是mixinbooter版本请一定要安装[mixinbooter](https://www.mcmod.cn/class/4010.html)作为前置,如果你下载的是内置mixin的版本可以无视这条<br>
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
