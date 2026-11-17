package com.syuto.introvideo.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.syuto.introvideo.IntroVideo;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 配置文件 {@code config/intro_video.json}。
 *
 * <p>所有字段都有默认值；文件不存在时会自动生成一份带注释说明的默认配置（注释写在 README 里，
 * JSON 本身不带注释）。解析失败时回退到默认值并保留原文件。</p>
 */
public final class IntroConfig {

    public static final String FILE_NAME = "firstlight.json";
    /** 旧版本配置文件（用于自动迁移到新的 firstlight.json）。 */
    public static final String LEGACY_FILE_NAME = "intro_video.json";

    /**
     * 配置版本（内部用）：老版本配置没有这个字段。
     * 读到没有版本的旧配置时，会把 startAtSeconds 重置为 0（完整播放），避免旧配置继续跳过片头。
     */
    public Integer configVersion;

    public enum SkipMode {
        /** 任意键/鼠标点击都可以跳过 */
        ANY_KEY,
        /** 只有 ESC 可以跳过 */
        ESC_ONLY,
        /** 不允许跳过 */
        DISABLED;

        static SkipMode parse(String raw) {
            if (raw == null) {
                return ANY_KEY;
            }
            switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "esc":
                case "esc_only":
                case "only_esc":
                    return ESC_ONLY;
                case "none":
                case "disabled":
                case "off":
                    return DISABLED;
                default:
                    return ANY_KEY;
            }
        }
    }

    /** 视频适配方式（见字段 {@link #fit}）。 */
    public enum FitMode {
        /** 铺满屏幕，超出部分裁掉（无黑边） */
        COVER,
        /** 完整显示，比例不一致时留黑边 */
        CONTAIN,
        /** 拉伸铺满 */
        STRETCH;

        static FitMode parse(String raw) {
            if (raw == null) {
                return COVER;
            }
            switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "contain":
                case "fit":
                case "letterbox":
                    return CONTAIN;
                case "stretch":
                case "fill":
                    return STRETCH;
                default:
                    return COVER;
            }
        }
    }

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    // ---------------------------------------------------------------- 配置字段

    /** 总开关。关掉后模组完全不介入，使用原版/Forge 加载界面。 */
    public boolean enabled = true;

    /**
     * 视频文件路径。留空则自动查找（推荐把视频放到 {@code .minecraft/config/intro_video/} 下，
     * 或者直接打进模组 jar 的 {@code assets/introvideo/intro.mp4}）。
     * 相对路径以 .minecraft 为基准。
     */
    public String video = "";

    /**
     * 从视频的第几秒开始播放（默认 5.0）。
     *
     * <p>你的视频开头 0~4.5 秒其实就是原版 Mojang 加载画面（红底 + 工作室 logo），
     * 默认 0（完整播放）；如果你不想看到这段，可以设成 5.0 跳过。</p>
     */
    public double startAtSeconds = 0.0;

    /**
     * 循环重播时从第几秒开始（默认 5.0）。
     *
     * <p>第一遍完整播放（含视频开头的 Mojang 工作室片头），之后每次循环都跳过这段片头，
     * 直接从约第 5 秒继续播。设成和 {@code startAtSeconds} 一样就不会跳。</p>
     */
    public double loopStartAtSeconds = 5.0;

    /**
     * 播放到视频第几秒就结束/回到循环（0 = 播到结尾）。
     * 你这视频最后约 7 秒是逐渐变黑的结尾，想跳过它就设 57.5。
     */
    public double endAtSeconds = 0.0;

    /** 视频放完后是否循环（加载没结束时持续播放）。 */
    public boolean loop = true;

    /** 视频播完后是否必须按键才进入主菜单。 */
    public boolean requireKeypress = true;

    /** 跳过方式：any_key / esc_only / disabled（只影响“视频还在播放时”的跳过，提示出现后任意键都可以继续）。 */
    public String skip = "any_key";

    /** 视频播完后，延迟多少秒才显示“按任意键继续”。 */
    public double promptDelaySeconds = 0.25;

    /** 大于 0 时：提示出现 N 秒后自动进入主菜单（不需要按键）。 */
    public double autoContinueSeconds = 0.0;

    /** 按键后淡出到主菜单的时长（秒）。 */
    public double fadeOutSeconds = 0.5;

    /** 黑边颜色，格式 #RRGGBB。 */
    public String backgroundColor = "#000000";

    /** 色彩矩阵：bt709（默认，HD 视频）/ bt601（老 SD 视频）。颜色明显偏色时才需要改。 */
    public String colorMatrix = "bt709";

    /**
     * 视频适配方式：
     * <ul>
     *   <li>{@code cover}（默认）——铺满屏幕、不留黑边，超出屏幕的部分被裁掉（16:10 屏幕也不会有上下黑条）；</li>
     *   <li>{@code contain}——完整显示视频，屏幕比例不一致时留黑边；</li>
     *   <li>{@code stretch}——拉伸铺满（会变形）。</li>
     * </ul>
     */
    public String fit = "cover";

    /** 加载阶段在视频底部显示一行“加载中…”提示。 */
    public boolean showLoadingText = true;

    /** 加载阶段显示视频第一帧（静止底图），而不是纯黑底。 */
    public boolean showLoadingPoster = true;

    /** 是否播放开场音频（内置 OGG，和视频循环段对齐，循环播放）。 */
    public boolean audio = true;

    /** 开场音频音量（0~1，最终还会乘游戏里的“音乐”音量）。 */
    public double audioVolume = 1.0;

    /**
     * 音频后端：auto（默认，优先 MC 声音系统，找不到就 Java Sound）/ minecraft / javasound。
     * 如果某个端听不到声音，可以设成 javasound 强制用 Java Sound 播放。
     */
    public String audioBackend = "auto";

    /** 开场视频期间屏蔽原版音乐（只屏蔽 MUSIC 这一类，不影响按钮/环境音效）。 */
    public boolean muteVanillaMusic = true;

    /**
     * 加载文字的动画点样式：bottom（默认，普通 . 1~3 个循环）/ middle（中间点 ·）/
     * ellipsis（固定 … ）/ none（不带点）。
     */
    public String loadingDots = "bottom";

    /** 加载底图轻微的呼吸缩放（让加载画面看起来不是“卡住”）。 */
    public boolean loadingPosterZoom = true;

    /** 加载阶段在底部显示一个“来回移动”的进度条（不确定进度，只是视觉效果）。 */
    public boolean showLoadingBar = true;

    /** 字体还没加载好、文字不能显示时，用一个纯图形的小圆点动画表示“正在加载”。 */
    public boolean showLoadingIndicator = false;

    /** 字体没就绪时，用预烘焙的位图文字显示“正在加载中”（不依赖游戏字体，不会口口口）。 */
    public boolean useBitmapLoadingText = true;

    /** 开场界面左上角显示调试信息（播放遍数 / 视频时间 / 当前亮度 / 黑屏时长），用来观察循环黑屏多久。 */
    public boolean showLoopDebug = false;

    /** 进入主界面后，是否在右上角弹一个“草方块 + Welcome to Minecraft”的成就式提示。 */
    public boolean showWelcomeToast = true;

    /** 欢迎提示的小标题行（默认“成就达成！”）。 */
    public String welcomeToastTitle = "成就达成！";

    /** 欢迎提示的主文字（默认“Welcome to Minecraft”）。 */
    public String welcomeToastText = "Welcome to Minecraft";

    /** 欢迎提示停留时长（秒，含滑入滑出）。 */
    public double welcomeToastSeconds = 5.0;

    /** 欢迎提示的音效 id（默认原版音符盒“叮”）。可换成 ui.toast.challenge_complete 等。 */
    public String welcomeToastSound = "minecraft:block.note_block.bell";

    /** 加载/开场期间把游戏窗口标题改成“正在加载 . .. ...”动画（系统字体，不依赖游戏字体）。 */
    public boolean changeWindowTitle = true;

    /** 加载阶段的窗口标题前缀；留空用 "Minecraft* 正在加载"（后面会自动加动态点）。 */
    public String windowTitleText = "";

    /** 加载完成、进入开场界面后的窗口标题；留空用 "Minecraft* 正在为您书写传奇"（后面自动加动态点）。 */
    public String windowTitleScreenText = "";

    /** 「按任意键继续」出现后的窗口标题；留空用 "Minecraft* 按任意键继续"。 */
    public String windowTitlePromptText = "";

    /** 「按任意键继续」出现后是否直接恢复成原版 Minecraft 标题（true 时上面的 promptText 不生效）。 */
    public boolean windowTitlePromptVanilla = false;

    /** 加载/开场期间的标题短语池（每次启动随机挑一句；会自动加 "Minecraft* " 前缀和动态点）。 */
    public String[] windowTitlePhrases = {
            "正在为您书写传奇",
            "正在为您唤醒世界",
            "正在为您展开史诗",
            "命运的齿轮正在转动",
            "新的冒险即将开始",
            "正在准备欢迎您的到来"
    };

    /**
     * 加载超过 N 秒后，直接在加载画面上开始播放视频（默认 3 秒）。
     * 模组多、加载慢的整合包用这个避免长时间停在一张静态图上；≤0 表示一直用底图，等开场界面再播。
     */
    public double loadingVideoAfterSeconds = 3.0;

    /** 是否显示“按任意键继续”。 */
    public boolean showPrompt = true;

    /** “按任意键继续”文字缩放（1.0 = 原大小，默认 0.8 稍小一点，避免挡画面）。 */
    public double promptScale = 0.8;

    /** “按任意键继续”距离屏幕底部的像素距离（GUI 字号，默认 16）。 */
    public int promptYOffset = 16;

    /** 提示文字动画：pop（默认：弹出 + 呼吸）/ pulse（只呼吸）/ none。 */
    public String promptAnimation = "pop";

    /** 自定义提示文字；留空则使用语言文件（中文默认“按任意键继续”）。 */
    public String promptText = "";

    /** 自定义加载文字；留空则使用语言文件（中文默认“正在加载…”）。 */
    public String loadingText = "";

    /** 开启后在日志里输出视频信息/解码耗时，方便排查。 */
    public boolean debug = false;

    /** 覆盖帧率（0 = 自动读取 MP4 信息）。有些容器时基异常时可以手动指定。 */
    public double fpsOverride = 0.0;

    // ---------------------------------------------------------------- 运行时字段

    private transient File file;

    private IntroConfig() {
    }

    // ---------------------------------------------------------------- 读写

    public static IntroConfig load(File gameDir) {
        File configDir = new File(gameDir, "config");
        File configFile = new File(configDir, FILE_NAME);
        // 旧版本（mod id 为 introvideo 时）的配置文件名是 intro_video.json，这里自动迁移
        File readFile = configFile;
        if (!configFile.isFile()) {
            File legacy = new File(configDir, LEGACY_FILE_NAME);
            if (legacy.isFile()) {
                readFile = legacy;
            }
        }
        IntroConfig config = null;
        if (readFile.isFile()) {
            try (BufferedReader reader = Files.newBufferedReader(readFile.toPath(), StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, IntroConfig.class);
            } catch (Exception e) {
                IntroVideo.LOGGER.warn("[IntroVideo] 读取 {} 失败，使用默认配置", readFile, e);
            }
        }
        if (config == null) {
            config = new IntroConfig();
        }
        config.file = configFile;

        int before = GSON.toJson(config).length();
        config.normalize();
        if (!configFile.isFile() || before != GSON.toJson(config).length()) {
            config.save();
            if (!readFile.equals(configFile)) {
                IntroVideo.LOGGER.info("[IntroVideo] 已把旧配置 {} 迁移为 {}", readFile.getName(), configFile.getName());
            }
        }
        return config;
    }

    /** 兜底：把非法值改回默认值。 */
    private void normalize() {
        if (skip == null) {
            skip = "any_key";
        }
        if (fit == null || fit.trim().isEmpty()) {
            fit = "cover";
        }
        if (colorMatrix == null || colorMatrix.trim().isEmpty()) {
            colorMatrix = "bt709";
        }
        if (backgroundColor == null || !backgroundColor.matches("#?[0-9a-fA-F]{6,8}")) {
            backgroundColor = "#000000";
        }
        if (configVersion == null || configVersion < 2) {
            // 旧配置（没有 configVersion）：把 startAtSeconds 归零，默认完整播放
            startAtSeconds = 0.0;
        }
        configVersion = 2;
        startAtSeconds = Math.max(0.0, startAtSeconds);
        loopStartAtSeconds = Math.max(0.0, loopStartAtSeconds);
        endAtSeconds = Math.max(0.0, endAtSeconds);
        audioVolume = Math.max(0.0, Math.min(1.0, audioVolume));
        if (audioBackend == null || audioBackend.trim().isEmpty()) {
            audioBackend = "auto";
        }
        loadingVideoAfterSeconds = Math.max(0.0, loadingVideoAfterSeconds);
        promptDelaySeconds = Math.max(0.0, promptDelaySeconds);
        promptScale = Math.max(0.4, Math.min(2.0, promptScale));
        promptYOffset = Math.max(0, promptYOffset);
        if (promptAnimation == null || promptAnimation.trim().isEmpty()) {
            promptAnimation = "pop";
        }
        autoContinueSeconds = Math.max(0.0, autoContinueSeconds);
        fadeOutSeconds = Math.max(0.0, fadeOutSeconds);
        fpsOverride = Math.max(0.0, fpsOverride);
    }

    public void save() {
        if (file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("无法创建目录 " + parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                writer.write(GSON.toJson(this));
                writer.newLine();
            }
        } catch (Exception e) {
            IntroVideo.LOGGER.warn("[IntroVideo] 保存 {} 失败", file, e);
        }
    }

    public File file() {
        return file;
    }

    public File configDir() {
        return file == null ? null : file.getParentFile();
    }

    // ---------------------------------------------------------------- 便捷方法

    public SkipMode skipMode() {
        return SkipMode.parse(skip);
    }

    public FitMode fitMode() {
        return FitMode.parse(fit);
    }

    /** 色彩矩阵（bt709 / bt601）。 */
    public com.syuto.introvideo.video.Yuv420Rgba.Matrix colorMatrixMode() {
        if (colorMatrix != null) {
            String v = colorMatrix.trim().toLowerCase(java.util.Locale.ROOT);
            if (v.equals("bt601") || v.equals("601") || v.equals("sd")) {
                return com.syuto.introvideo.video.Yuv420Rgba.Matrix.BT601;
            }
        }
        return com.syuto.introvideo.video.Yuv420Rgba.Matrix.BT709;
    }

    public long fadeOutMillis() {
        return (long) (Math.max(0.0, fadeOutSeconds) * 1000.0);
    }

    public long promptDelayMillis() {
        return (long) (Math.max(0.0, promptDelaySeconds) * 1000.0);
    }

    /** 第一遍从视频第几纳秒开始播放。 */
    public long startAtNanos() {
        return (long) (Math.max(0.0, startAtSeconds) * 1_000_000_000.0);
    }

    /** 循环重播时从第几纳秒开始。 */
    /** 播放结束点（纳秒）；0 表示播到视频结尾。 */
    public long endAtNanos() {
        return (long) (Math.max(0.0, endAtSeconds) * 1_000_000_000.0);
    }

    public long loopStartNanos() {
        return (long) (Math.max(0.0, loopStartAtSeconds) * 1_000_000_000.0);
    }

    public long autoContinueMillis() {
        return (long) (Math.max(0.0, autoContinueSeconds) * 1000.0);
    }

    /** 黑边颜色，0xAARRGGBB。 */
    public int backgroundColorArgb() {
        String value = backgroundColor == null ? "#000000" : backgroundColor.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        try {
            if (value.length() == 6) {
                return 0xFF000000 | Integer.parseInt(value, 16);
            }
            if (value.length() == 8) {
                return (int) Long.parseLong(value, 16);
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return 0xFF000000;
    }
}
