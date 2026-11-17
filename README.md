# FirstLight · 开场视频加载动画 — Minecraft 1.20.1

> 下载：[Releases](https://github.com/redwineserena/FirstLight/releases) · 问题反馈：[Issues](https://github.com/redwineserena/FirstLight/issues)

启动游戏时接管加载画面：**加载阶段**显示底图 +「正在加载中」（把原版 / Forge 的加载动画、红底白字工作室画面全部盖住），
**加载完成后**进入开场界面播放视频，并循环，底部提示「按任意键继续」；按键淡出进入主菜单。

进主菜单后还会弹一个**原版风格成就提示**（草方块图标 +「成就达成！/ Welcome to Minecraft」+ 原版「叮」音效）。

支持 **Forge / NeoForge / Fabric**（均为 Minecraft 1.20.1，同一份 `common` 源码构建）。

---

## 特性

- **纯 Java 播放器**：[JCodec](https://github.com/jcodec/jcodec) 0.2.5（BSD-2）解码 MP4/H.264，无需 ffmpeg / VLC，无 native 库
- **加载阶段**：底图 + 预渲染位图「正在加载中」（字体未加载时也不会变口口口）+ 进度条；超过 `loadingVideoAfterSeconds` 后视频提前在加载画面上播放
- **开场界面**：第一遍完整播放（0 → 结尾），之后每一遍跳过片头从 `loopStartAtSeconds` 循环，音画同步（Java Sound 按字节裁剪循环段）
- **窗口标题**：加载/开场期间标题随机显示短语（可在配置里改），进主菜单后恢复原版标题
- **欢迎成就**：原版 `ToastComponent` + 原版 toast 贴图 + 原版 `ui.toast.challenge_complete` 音效
- **视频自适应**：`cover` 铺满（16:10 等非常规屏幕不会出现黑边），可切换 `contain` / `stretch`
- **多语言**：内置 zh_cn / zh_tw / en_us

## 下载 & 安装

从 [Releases](https://github.com/redwineserena/FirstLight/releases) 下载对应加载器的 jar（或自行构建，见下文），放进整合包/实例的 `mods/` 目录：

| 加载器 | 文件 |
| --- | --- |
| Forge 1.20.1 | `introvideo-forge-1.0.0.jar` |
| NeoForge 1.20.1 | `introvideo-neoforge-1.0.0.jar`（NeoForge 1.20.1 与 Forge 47.x 同运行时，Forge 版也可直接用） |
| Fabric 1.20.1 | `introvideo-fabric-1.0.0.jar` |

首次启动会把内置的演示视频释放到 `config/intro_video/intro.mp4`（来源见下方「素材来源与版权说明」），
之后**直接替换这个文件**即可换成你自己的视频（不用重新打包）。

> 注意：内置视频/音频来自他人作品（见「素材来源与版权说明」），仅供演示；正式发布/商用请替换成你自己拥有版权或已获授权的视频。

## 配置

配置文件：`config/intro_video.json`（首次启动自动生成）。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 总开关 |
| `video` | `""` | 自定义视频路径（相对 `.minecraft`）；留空用内置视频（会释放到 `config/intro_video/intro.mp4`） |
| `startAtSeconds` | `0.0` | **第一遍**从视频第几秒开始 |
| `loopStartAtSeconds` | `5.0` | **循环重播**从第几秒开始（跳过片头，建议对齐关键帧附近的亮场） |
| `endAtSeconds` | `0.0` | 播放到第几秒结束并循环（`0` = 播到结尾） |
| `loop` | `true` | 循环播放直到按键 |
| `requireKeypress` | `true` | 是否必须按键才进主菜单 |
| `skip` | `"any_key"` | 跳过方式 |
| `promptDelaySeconds` | `0.25` | 加载完成后多久显示提示 |
| `autoContinueSeconds` | `0.0` | 自动继续（秒），0 = 不自动 |
| `fadeOutSeconds` | `0.5` | 淡出时长 |
| `backgroundColor` | `"#000000"` | 视频以外的底色 |
| `colorMatrix` | `"bt709"` | 颜色矩阵：`bt709` / `bt601` / `identity` |
| `fit` | `"cover"` | `cover`（铺满裁剪）/ `contain`（完整留边）/ `stretch`（拉伸） |
| `showLoadingText` | `true` | 加载阶段显示文字 |
| `showLoadingPoster` | `true` | 加载阶段显示底图 |
| `loadingPosterZoom` | `true` | 底图轻微缩放动画 |
| `showLoadingBar` | `true` | 加载阶段显示进度条 |
| `useBitmapLoadingText` | `true` | 字体未就绪时用预渲染位图显示「正在加载中」（不依赖游戏字体） |
| `showLoadingIndicator` | `false` | 字体未就绪时显示三个脉冲点（位图文字关闭时才会用到） |
| `loadingVideoAfterSeconds` | `3.0` | 加载超过几秒后提前在加载画面上播放视频 |
| `audio` | `true` | 是否播放视频音频 |
| `audioVolume` | `1.0` | 音量 |
| `audioBackend` | `"auto"` | `auto` / `minecraft` / `javasound` |
| `muteVanillaMusic` | `true` | 开场期间屏蔽原版背景音乐 |
| `loadingDots` | `"bottom"` | 「正在加载」后动态点的样式：`bottom` / `middle` / `fixed` / `none` |
| `showPrompt` | `true` | 是否显示「按任意键继续」 |
| `promptScale` | `0.8` | 提示文字大小 |
| `promptYOffset` | `16` | 提示文字距底部偏移 |
| `promptAnimation` | `"pop"` | `pop`（弹出+呼吸）/ `none` |
| `promptText` | `""` | 自定义提示语 |
| `loadingText` | `""` | 自定义加载文字 |
| `changeWindowTitle` | `true` | 加载/开场期间修改窗口标题 |
| `windowTitleText` | `""` | 加载阶段标题（留空随机短语） |
| `windowTitleScreenText` | `""` | 开场界面标题（留空随机短语） |
| `windowTitlePromptText` | `""` | 提示出现后的标题（留空 `Minecraft* 按任意键继续`） |
| `windowTitlePromptVanilla` | `false` | 提示出现后直接恢复原版标题（`true` 时不显示上面的自定义标题） |
| `windowTitlePhrases` | 若干句 | 标题随机短语池 |
| `showWelcomeToast` | `true` | 进主菜单后弹原版风格欢迎成就 |
| `welcomeToastTitle` | `"成就达成！"` | 成就小标题 |
| `welcomeToastText` | `"Welcome to Minecraft"` | 成就主文字 |
| `welcomeToastSeconds` | `5.0` | 成就停留秒数 |
| `debug` | `false` | 调试日志 / 帧缓冲截图 |
| `showLoopDebug` | `false` | 左上角显示循环调试数字 |
| `fpsOverride` | `0.0` | 强制帧率（0 = 自动） |

## 换成自己的视频

1. 把视频放到 `config/intro_video/intro.mp4`（或任意位置，用配置 `video` 指向它）
2. 视频格式要求见下节；如果你的视频开头/结尾有不需要的部分，用 `startAtSeconds` / `endAtSeconds` / `loopStartAtSeconds` 调整
3. 音频可以直接用视频自带音轨吗？——不行，模组音频来自模组内置的 OGG/WAV。要换成自己的音乐，替换
   `common/src/main/resources/assets/introvideo/sounds/intro.ogg`（和 `intro.wav`）后重新构建；
   或者把 `audio` 设为 `false` 只放视频


## 素材来源与版权说明

内置的开场视频与音频**不是本项目原创**，来源于 B 站视频：

- 《如果MC有入场动画..》 https://www.bilibili.com/video/BV1qvJR6iEsz/
- 该视频本身可能也是转载，**版权归原作者所有**

这里内置仅用于**演示这个模组的效果**。如果你要把它用于公开整合包/商业用途，请先获得原作者授权；
如果原作者不希望被转载，请开 Issue 或联系仓库作者删除。
</br>你也可以直接替换 `config/intro_video/intro.mp4` 为自己的视频，或把 `audio` 设为 `false`。


> 补充：模组的默认参数（`loopStartAtSeconds=5` 跳过片头、结尾黑场循环点等）是**针对这段演示视频调好**的。
> 换成你自己的视频后，建议按实际内容调整 `startAtSeconds` / `loopStartAtSeconds` / `endAtSeconds`。

## 视频格式建议

- 容器 **MP4**，视频 **H.264 (avc1)**，8bit 4:2:0（`yuv420p`）
- 建议 **1280x720 / 30fps**（1080p30 也能放，但解码占用更高）
- 不要用 B 帧过多的编码；不能是 H.265 / AV1 / VP9
- 音频可以是 AAC（模组用内置 WAV/OGG 播放，不依赖视频音轨）

## 自己构建

需要 **JDK 17**（`JAVA_HOME` 指向它）。

```bat
build-all.bat
```

产物在 `dist/`：

```
dist/introvideo-forge-1.0.0.jar
dist/introvideo-neoforge-1.0.0.jar
dist/introvideo-fabric-1.0.0.jar
```

- Forge 模块：ForgeGradle 6 + Forge 47.4.23
- NeoForge 模块：同 Forge 工具链（NeoForge 1.20.1 与 Forge 47.x 同一 SRG 运行时）
- Fabric 模块：Loom 1.7.4 + Loader 0.16.10

## 目录结构

```
common/     三个加载器共享的源代码与资源（视频/贴图/语言/音效）
forge/      Forge 1.20.1 模块
neoforge/   NeoForge 1.20.1 模块
fabric/     Fabric 1.20.1 模块
docs/       额外文档
dist/       构建产物（不入库）
```

## 已知限制

- 只支持 **Minecraft 1.20.1**（其他版本需要改 mixin 映射）
- 只支持 **H.264** 视频；H.265 / AV1 等无法解码
- 仓库内置的视频/音频是自制的**占位素材**（8 秒渐变 + 轻音），只用于让模组开箱能跑；正式使用请替换成你自己的视频
- NeoForge 1.20.1 与 Forge 47.x 共用 SRG 运行时，所以 NeoForge 版构建工具链与 Forge 相同

## 鸣谢

- [JCodec](https://github.com/jcodec/jcodec)（BSD-2）纯 Java H.264 解码
- Minecraft / Forge / NeoForge / Fabric 社区文档
