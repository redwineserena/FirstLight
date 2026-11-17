# NeoForge 1.20.1 模块说明（重要）

**结论：NeoForge 1.20.1 用 `neoforge/` 构建出的 `introvideo-neoforge-1.0.0.jar`
（和 Forge 版内容等价），不需要另做一套源码。**

## 为什么 `neoforge/` 和 `forge/` 几乎一样

NeoForge 1.20.1（47.1.x）是从 Forge 47.x fork 出来的，而且**没有改包名**：

- 包名仍然是 `net.minecraftforge.*`（`net.neoforged.*` 是 1.20.2+ 才改的）；
- 它自己在 `mods.toml` 里注册的 mod id 就是 **`forge`**；
- 运行时同样是 SRG 名称 + 同一套 Mixin（0.8.5）/ FML（javafml）。

所以对 1.20.1 来说，Forge 与 NeoForge 的模组是**二进制兼容**的：
针对 Forge 47.4.x 构建、只依赖原版类 + 稳定 FML API 的模组，可以直接在 NeoForge 1.20.1 上加载。

`neoforge/` 因此采用 ForgeGradle 6 工具链（`forge_version=47.4.23`），只改了产物名，
这样构建稳定、可复现；真正的“原生 NeoForge 工程”（ModDevGradle/NeoGradle + `net.neoforged.*`）
只在 1.20.2+ 才需要，等以后做 1.21 版本时再切换。

## 证据（可自行核对）

NeoForge 1.20.1 的 universal jar（`net.neoforged:forge:1.20.1-47.1.106-universal.jar`）里：

```toml
modLoader="javafml"
loaderVersion="[24,]"
...
[[mods]]
    modId="forge"
    displayName="NeoForge"
```

即：NeoForge 1.20.1 对外就是 mod id `forge`。

## 本模组为什么兼容

`common/` 里**没有引用任何 Forge/NeoForge 的 API**（入口类只用 `@Mod` 和 `ModList`），
Mixin 只拦截原版类：

- `net.minecraft.client.renderer.GameRenderer#render(FJZ)V`
- `net.minecraft.client.Minecraft#setScreen(...)`

因此 Forge 47.x 生成的 refmap（SRG 名 `m_109093_` / `m_91152_`）在 NeoForge 1.20.1 上同样有效。

## 已验证到什么程度

已通过（**静态验证 + 构建验证**）：

1. NeoForge 1.20.1 universal jar 的 `mods.toml`：`modId="forge"` ✔
2. 用 NeoGradle 7 拉取 `net.neoforged:forge:1.20.1-47.1.106`，跑完 NeoForm 的
   merge/rename 阶段，得到 NeoForge 补丁过的 SRG 命名 Minecraft jar，`javap` 确认：

   ```
   public void m_91152_(net.minecraft.client.gui.screens.Screen);   // Minecraft.setScreen
   public void m_109093_(float, long, boolean);                     // GameRenderer.render
   ```

   与本模组 `introvideo.refmap.json` 的目标完全一致 ✔
3. `neoforge/` 模块编译 / 打包通过，产物核对：`MixinConfigs` manifest、mixin refmap、
   `META-INF/mods.toml`（依赖 `forge` `[47,)`）、内置 JCodec 均在 jar 内 ✔

**未完成**：NeoForge 1.20.1 客户端实机启动。本机在 NeoGradle 的 `neoFormDecompile`
阶段因页面文件不足（`os::commit_memory ... error=1455`）失败——这是构建环境的
内存/页面文件问题，不是模组问题；Forge 与 Fabric 两端已经用同样的代码实机跑通。

## 如何在 NeoForge 上验证

装好 NeoForge 1.20.1 客户端，把 `dist/introvideo-neoforge-1.0.0.jar` 丢进 `mods/`，
启动后看 `.minecraft/logs/latest.log`：

```
[IntroVideo] booting on NeoForge 1.20.1 (compat build) 47.1.x
[IntroVideo] 拦截到主菜单，改为播放开场动画
[IntroVideo] 首帧已显示 (1280x720, ...)
```

出现这三行就说明跑通了。
