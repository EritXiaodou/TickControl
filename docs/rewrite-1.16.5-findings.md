# 1.16.5 重写依据(崩溃根因,已由真实崩溃报告证实)

来源:`E:\.minecraft\versions\1.16.5-Forge_36.2.42\crash-reports\crash-2026-10-07_05.11.49-fml.txt`

## 崩溃原文

```
Critical injection failure: @Inject annotation on tickcontrol$prepareTick
could not find any targets matching 'tick' in net.minecraft.server.MinecraftServer.
No refMap loaded. [PREINJECT Applicator Phase -> tickcontrol.mixins.json:MinecraftServerMixinDev
-> Prepare Injections -> handler$zza000$tickcontrol$prepareTick(...)]
```

## 三个独立结论

> ⚠️ **本节第 1 条与「重写要点」的前两条,已被第二次崩溃推翻。**
> 先读文末的「追加:第二次崩溃的真正根因」,那里才是实测确认的结论。

1. **`method = "tick"` 在 1.16.5 的任何名字域里都不存在。**
   - `official` 映射下该方法是 `func_71217_p`(1.16.5 官方名就是 SRG 名)。
   - `snapshot`(MCP)映射下才叫 `tick`。
   - 原工程用 `snapshot`,所以 `"tick"` 当时是对的;改成 `official` 后被我误伤。
   - **`MinecraftServerMixinSrg` 里我写的 `func_71217_p` 反而是对的**——也就是说两个变体
     应当用同一批名字,双变体机制在 1.16.5 + official 映射下是多余的。

2. **加载的是 `MinecraftServerMixinDev`(Dev 变体)**,栈帧为
   `MixinLaunchPluginLegacy.processClass`。
   ⇒ `TickControlMixinPlugin.shouldApplyMixin` 的判据在 1.16.5 下选错了变体。
   `-Dtickcontrol.naming=dev` 只加在 dev 运行参数里,正式启动没有它,
   所以插件走了「猜名字域」的分支并猜错。

3. **`required: true` + `injectors.defaultRequire: 1`** 让这个失败变成致命错误
   (客户端启动即崩),而不是一条警告。

## 重写要点

- **不要再做 Dev/Srg 双变体。** 1.16.5 用 `official` 映射时,开发域与生产域同名(都是 SRG 名),
  一个 mixin 就够,彻底消除选错变体的可能。
- 目标名用 `func_71217_p`(单刻)、`func_240802_v_`(主循环)、`func_71190_q`(世界循环)、
  `func_72835_b`(WorldServer.tick),全部已在生产 `forge-1.16.5-36.2.34-srg.jar` 中核验存在。
- 目标名要按「正式 jar 的实际名字域」来写,并用产物核验,不要凭 dev 环境推断。
- `tickcontrol.mixins.json`:`compatibilityLevel` 必须是 **`JAVA_8`**(1.16.5 是 Java 8),
  `minVersion` **不要超过 0.8.4**(1.16.5 自带 Mixin 0.8.4)。
- `MixinConfigs` 必须走 jar manifest:1.16.5 的开发环境加载 `build/resources` 目录、
  没有 manifest,所以**只有正式 jar 才能生效**——这正好符合「禁止 dev 测试」的要求。

---

# 追加:第二次崩溃的真正根因(实测确定)

第二次崩溃报告(`crash-2026-10-07_05.21.03-fml.txt`)与第一次**报错完全相同**:

```
Critical injection failure: @Inject annotation on tickcontrol$prepareTick
could not find any targets matching 'tick' in net.minecraft.server.MinecraftServer.
No refMap loaded.
```

但此时 jar 里的注解**已确认**是 `func_240802_v_` / `tick` /
`updateTimeLightAndEntities`。所以问题不在"名字写错了",而在
**我判断错了运行时的名字域**。

## 实测:三个名字域

对这个实例**实际加载的 jar** 直接 `javap`:

| 方法 | vanilla `1.16.5-Forge_36.2.42.jar`(启动器加载的那个) | Forge `forge-1.16.5-36.2.42-client.jar` |
| --- | --- | --- |
| 主循环 | **不存在** | `func_240802_v_` |
| 单刻 | **`a`** | `func_71217_p` |
| 世界循环 | **`b`** | `func_71190_q` |
| `ServerWorld` 类 | **不存在** | `func_72835_b` |

结论:**这个"专用测试实例"不是正式安装**——启动器加载的是**原版混淆 jar**,
Forge 的补丁与重命名在运行时另外叠加。

而 MCP 名(`tick` / `updateTimeLightAndEntities`)在运行时**两个阶段里都不存在**,
它们是 **ForgeGradle 开发 classpath 专有的命名**。

## 为什么同一份 mixin 里一个目标能解析、一个不能

- `func_240802_v_` 是 **Forge 自己新增**的方法 → 两个阶段都用 SRG 名,能解析。
- `tick` / `updateTimeLightAndEntities` 是 **MCP 开发名** → 运行时不成立,解析失败。

报错只提 `tick`,正是因为 `func_240802_v_` 先解析成功了。

## 正确做法(已落地并核验)

**Mixin 的 `method` / `target` 必须写运行时真实存在的名字,即 SRG 名:**

```
func_240802_v_     主循环
func_71217_p       单刻
func_71190_q       世界循环
func_72835_b       ServerWorld.tick
```

四个名字已逐个用 `javap` 对着**实例加载的 jar** 核验;部署后的 jar 里四者齐备,
且旧名 `tick` / `updateTimeLightAndEntities` 已彻底消失。

## 教训(方法论,最重要的一条)

> **校验 mixin 目标名,必须对着运行时真正加载的那个 jar,
> 不能对着 ForgeGradle 的开发产物。**

我此前是用 ForgeGradle 的 **开发 mapped jar** 去核验的,得到 `tick` /
`updateTimeLightAndEntities` 这两个**开发名**,并据此写下"1.16.5 开发域与生产域同名"
的结论——**这个结论是错的**,两轮崩溃都源于它。

这与本项目 README 里早已记录的另一条是同一类问题:
"`@Shadow` 在生产环境不可靠、改用反射"——本质都是**开发域与运行时域不同名**。

## 相关事实(便于后续版本复用)

- 1.16.5 的 Mixin 由 Forge 提供,版本 **0.8.4**;
  `tickcontrol.mixins.json` 的 `minVersion` 不能超过它。
- 1.16.5 的 Mixin 配置**只能**通过 jar manifest 的 `MixinConfigs` 发现
  (`MixinPlatformAgentMinecraftForge` 读容器 manifest);
  `mods.toml` 的 `[[mixins]]` 是 1.20.5+ 写法,在 1.16.5 上会被静默拒绝。
- `compatibilityLevel` 必须是 `JAVA_8`(1.16.5 的类版本是 52)。
- 本工程的 Mixin 全部带 `remap = false` 且**没有 refmap**,所以注解里的名字
  不会被重映射 —— 这正是"必须直接写运行时名"的原因。