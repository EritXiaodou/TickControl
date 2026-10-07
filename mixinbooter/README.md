# TickControl for 1.12.2 —— MixinBooter 变体

这是 **1.12.2 的第二份产物**,与 `forge-1.12.2/` **共用同一套源码**
(`common-1.12.2/{java,resources}`),差别**只在 Mixin 的提供方式**:

| | `forge-1.12.2`(自包含) | `forge-1.12.2-MixinBooter`(本工程) |
| --- | --- | --- |
| jar 体积 | 924 KB | **45 KB** |
| 打包 Mixin 库 | 是(585 个 `org/spongepowered` 条目) | **否** |
| 运行时前置 | 无 | **需要安装 MixinBooter** |
| mixin 配置注册 | `FMLCorePlugin` + `TweakClass` + `TickControlCore` 手工 `MixinBootstrap.init()` | manifest 的 **`MixinConfigs`**,由 MixinBooter 读取 |
| 与其它自带 Mixin 的模组共存 | 可能冲突(各带一份 Mixin) | 由 MixinBooter 统一提供,冲突消除 |

## 构建

与 `forge-1.12.2` 完全相同的工具链组合(**这个组合是实测出来的,别改**):

```
JAVA_HOME = C:\Program Files\Java\jdk8u312-b07
Gradle    = <repo>\.tools\gradle-4.9        (4.10.3 会在 reobfJar 阶段失败)
命令      = gradle --project-dir forge-1.12.2-MixinBooter \
                   --gradle-user-home <repo>\.gradle-home build
```

产物:`forge-1.12.2-MixinBooter/build/libs/tickcontrol-forge-1.12.2-mixinbooter-1.0.0-mixinbooter.jar`

## 安装(重要:必须先装 MixinBooter)

本变体**不含 Mixin 库**。若实例里没有 MixinBooter,模组**不会工作**
(mixin 配置无人注册,`/tick` 命令可能注册但注入全部不生效)。

1. 安装 **MixinBooter**(1.12.2 对应版本,**11.17** 为写作时的最新版;
   见 <https://www.curseforge.com/minecraft/mc-mods/mixin-booter> 或
   <https://modrinth.com/mod/mixinbooter>)到 `mods\`;
2. 再把本 jar 放进 `mods\`;
3. **不要**与 `forge-1.12.2` 那份自包含 jar 同时安装 —— 两者 `modid` 相同
   (`tickcontrol`),会互相冲突。二选一。

产物已放在 `.build\mixinbooter\`。

## 官方依据

MixinBooter 的 README 明确支持两种注册方式,本工程用第一种(无需任何 loader 类):

> **`MixinConfigs` manifest attribute**: no loader class needed. Add a comma-separated
> list of your mixin configuration names to your jar's manifest. MixinBooter reads it
> straight from the jar's manifest and registers them.

同时 `forge-1.12.2/build.gradle` 里那套 `FMLCorePlugin` / `FMLCorePluginContainsFMLMod` /
`ForceLoadAsMod` / `TweakClass` **在本变体中全部不需要**,已从 manifest 移除;
`TickControlCore`(自带的 coremod 引导类)也通过 `java.exclude` 排除出编译产物,
避免出现"两个 Mixin 引导"。

## 产物核验

```
entries = 38
org/spongepowered entries = 0        <- Mixin 未打包
TickControlCore present   = False    <- 已排除
manifest: MixinConfigs: tickcontrol.mixins.json
          (无 FMLCorePlugin / 无 TweakClass)
```

另外会多出一个由 Mixin 注解处理器**自动生成**的
`cleanmix_version_compatibility.json`(内容是本模组各 mixin 类声明兼容的 CleanMix
版本)。自包含那份没有这个文件,因为它的注解处理器不是 CleanMix 的。这是预期的,
不要误删。

## 状态

**已构建、产物已端到端核验,但尚未在游戏里跑过。**

核验内容(不只是"能构建"):

| 检查 | 结果 |
| --- | --- |
| manifest `MixinConfigs` | `tickcontrol.mixins.json` |
| Mixin 库是否被打包 | **否**(`org/spongepowered` 条目 = 0) |
| 配置指向的 3 个类是否都在 jar 里 | 是(`MinecraftServerMixinDev` / `...Srg` / `TickControlMixinPlugin`) |
| `TickControlCore` 是否已排除 | 是(避免两个 Mixin 引导) |
| `mcmod.info` 依赖声明 | `["mixinbooter"]` |
| 强制名字域开关 | 仍在,失败时可 `-Dtickcontrol.naming=dev` |

请先测自包含版本;确认可用后再装 MixinBooter 测本变体。
两者功能应当完全一致 —— **源码是同一份**,差别只在 Mixin 由谁提供。

### 若本变体没生效,第一件事是查名字域

`TickControlMixinPlugin` 需要判断运行在 SRG(生产)还是 MCP(开发)域。
日志里的探针**读不到类字节**(只拿到 30 字节存根),于是回退成"默认按 SRG 处理"。
自包含版本下这个默认恰好正确;MixinBooter 的类加载器不同,**这个默认未必成立**。

启动日志里会出现其中一行,据此判断:

```
[tickcontrol] mixin plugin loaded; naming domain = SRG (production)
[tickcontrol] mixin plugin loaded; naming domain = MCP (development)
```

若注入没生效且域判定可疑,**不需要改代码**,加启动参数强制即可:

```
-Dtickcontrol.naming=dev     强制 MCP(开发)域
-Dtickcontrol.naming=srg     强制 SRG(生产)域
```

(FML 参数示例:`-DlegacyLaunchArgs=` 或直接在启动器 JVM 参数里加。)
