package com.syuto.introvideo.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.syuto.introvideo.IntroVideo;
import com.syuto.introvideo.video.VideoPlayer;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.lwjgl.opengl.GL11;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 客户端单例，负责整个开场视频的状态机：
 *
 * <pre>
 *   OFF ──初始化成功──&gt; LOADING ──原版要显示 TitleScreen 时拦截──&gt; SCREEN ──按键/播完──&gt; DONE
 *    └──没有视频/解码失败──────────────────────────────────────────────┘（保持原版加载界面）
 * </pre>
 *
 * <p>Mixin 只需要调用两个入口：</p>
 * <ul>
 *   <li>{@link #onEndOfFrame(float)}：每帧最后调用，用来在“加载阶段”盖住原版/Forge 加载动画；</li>
 *   <li>{@link #shouldReplaceFirstScreen(Screen)}：原版要显示主菜单时返回 true，替换成
 *       {@link IntroVideoScreen}。</li>
 * </ul>
 */
public final class IntroVideoClient {

    public enum Phase {
        /** 未介入（没有视频、被禁用、或解码失败） */
        OFF,
        /** 正在加载，视频覆盖在加载界面之上 */
        LOADING,
        /** 已进入开场动画界面，等待视频播完 / 按键 */
        SCREEN,
        /** 已经进入主菜单，不再干预 */
        DONE
    }

    private static final ResourceLocation TEXTURE_LOCATION = new ResourceLocation(IntroVideo.MOD_ID, "video_frame");
    private static final ResourceLocation POSTER_LOCATION = new ResourceLocation(IntroVideo.MOD_ID, "poster");
    private static final ResourceLocation LOADING_TEXT_LOCATION = new ResourceLocation(IntroVideo.MOD_ID, "loading_text");

    private static IntroVideoClient instance;
    private static boolean renderErrorLogged;
    private static boolean fontsReloaded;
    private static final java.util.Set<String> LOGGED_TEXTS = new java.util.HashSet<>();

    private IntroConfig config;
    private VideoPlayer player;
    private VideoTexture texture;
    private VideoTexture posterTexture;
    private VideoTexture loadingTextTexture;
    private ByteBuffer posterPixels;
    private int posterWidth;
    private int posterHeight;
    private ByteBuffer loadingTextPixels;
    private int loadingTextWidth;
    private int loadingTextHeight;
    private final IntroVideoAudio audio = new IntroVideoAudio();
    private Phase phase = Phase.OFF;
    private long loadingStartedAt;
    private boolean initTried;
    private boolean textureRegistered;
    private File gameDir;

    private IntroVideoClient() {
    }

    public static IntroVideoClient get() {
        if (instance == null) {
            instance = new IntroVideoClient();
        }
        return instance;
    }

    // ---------------------------------------------------------------- 初始化

    /**
     * 提前初始化（在模组加载阶段就调用，比 Minecraft 第一次渲染更早）：
     * 这样解码线程能提前跑起来，主窗口一出现基本就有视频/黑底可画，
     * 避免看到原版加载动画。
     */
    public void preInit(Path dir) {
        init(dir == null ? null : dir.toFile());
    }

    private void ensureInit() {
        if (initTried) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        init(minecraft == null ? null : minecraft.gameDirectory);
    }

    private void init(File gameDir) {
        if (initTried) {
            return;
        }
        initTried = true;
        if (gameDir == null) {
            phase = Phase.OFF;
            return;
        }
        this.gameDir = gameDir;
        try {
            config = IntroConfig.load(gameDir);
            if (!config.enabled) {
                IntroVideo.LOGGER.info("[IntroVideo] 配置中已禁用，使用原版加载界面");
                phase = Phase.OFF;
                return;
            }
            File video = VideoLocator.locate(config, gameDir);
            if (video == null) {
                IntroVideo.LOGGER.info("[IntroVideo] 没有找到视频文件（可放到 config/intro_video/intro.mp4），使用原版加载界面");
                phase = Phase.OFF;
                return;
            }
            IntroVideo.LOGGER.info("[IntroVideo] 使用视频: {} ({} MB)", video.getAbsolutePath(), video.length() / 1024 / 1024);
            player = new VideoPlayer(video, config.loop, config.debug, config.fpsOverride,
                    config.colorMatrixMode(), config.startAtNanos(), config.loopStartNanos(), config.endAtNanos());
            if (config.startAtSeconds > 0.0) {
                IntroVideo.LOGGER.info("[IntroVideo] 从视频第 {} 秒开始播放（跳过开头 {} 秒）",
                        config.startAtSeconds, config.startAtSeconds);
            }
            player.start();
            phase = Phase.LOADING;
            loadingStartedAt = Util.getMillis();
            chosenTitlePhrase = pickTitlePhrase();
            loadPosterResource();
            loadLoadingTextResource();
            IntroVideo.LOGGER.info("[IntroVideo] 进入加载阶段（视频解码线程已启动）");
            if (config.debug) {
                startLoadingWatchdog();
            }
        } catch (Throwable t) {
            fail("初始化开场视频失败", t);
        }
    }

    /**
     * 读取打包在 jar 里的加载底图（assets/introvideo/poster.png）。
     * 只是一张静态图，不依赖视频解码，所以加载阶段第一帧就能显示，不会黑屏。
     */
    private void loadPosterResource() {
        if (posterPixels != null) {
            return;
        }
        try (InputStream in = IntroVideoClient.class.getResourceAsStream("/assets/introvideo/poster.png")) {
            if (in == null) {
                return;
            }
            BufferedImage image = ImageIO.read(in);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return;
            }
            posterWidth = image.getWidth();
            posterHeight = image.getHeight();
            ByteBuffer pixels = ByteBuffer.allocateDirect(posterWidth * posterHeight * 4);
            for (int y = 0; y < posterHeight; y++) {
                for (int x = 0; x < posterWidth; x++) {
                    int argb = image.getRGB(x, y);
                    pixels.put((byte) (argb >> 16));
                    pixels.put((byte) (argb >> 8));
                    pixels.put((byte) argb);
                    pixels.put((byte) 0xFF);
                }
            }
            pixels.flip();
            posterPixels = pixels;
            IntroVideo.LOGGER.info("[IntroVideo] 已加载加载底图 poster.png ({}x{})", posterWidth, posterHeight);
        } catch (Exception e) {
            IntroVideo.LOGGER.warn("[IntroVideo] 读取 poster.png 失败（加载阶段将显示黑底）", e);
        }
    }

    /** 渲染线程：把底图上传成 GL 纹理（只做一次）。 */
    private void ensurePosterTexture() {
        if (posterTexture != null || posterPixels == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        posterTexture = new VideoTexture(POSTER_LOCATION, posterWidth, posterHeight);
        minecraft.getTextureManager().register(POSTER_LOCATION, posterTexture);
        posterTexture.upload(posterPixels);
    }

    // ---------------------------------------------------------------- 主界面欢迎提示

    /** 开场结束、进入主界面后，用原版 toast 系统弹一次“成就达成”，并播放原版“叮”音效。 */
    private void tickWelcomeToast() {
        if (config == null || !config.showWelcomeToast || welcomeToastShown) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || isActive() || !(minecraft.screen instanceof TitleScreen)) {
            return;
        }
        welcomeToastShown = true;
        try {
            int seconds = (int) Math.max(1.0, config.welcomeToastSeconds);
            WelcomeToast toast = new WelcomeToast(
                    Component.literal(config.welcomeToastTitle == null || config.welcomeToastTitle.isEmpty()
                            ? "成就达成！" : config.welcomeToastTitle),
                    Component.literal(config.welcomeToastText == null || config.welcomeToastText.isEmpty()
                            ? "Welcome to Minecraft" : config.welcomeToastText),
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GRASS_BLOCK),
                    seconds * 1000L);
            minecraft.getToasts().addToast(toast);
            // 原版成就/挑战完成音效（就是那一声“叮”）
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F));
            IntroVideo.LOGGER.info("[IntroVideo] 弹出原版成就提示: {} / {}",
                    config.welcomeToastTitle, config.welcomeToastText);
        } catch (Throwable t) {
            IntroVideo.LOGGER.warn("[IntroVideo] 弹出欢迎提示失败", t);
        }
    }

    /** 统计“进入下一遍”到“下一遍第一帧显示”之间的等待时间（ms）。 */
    private void tickTransitionStats() {
        if (player == null) {
            return;
        }
        int pass = player.passIndex();
        if (pass != lastObservedPass) {
            lastObservedPass = pass;
            if (pass > 0) {
                transitionStartMs = Util.getMillis();
                lastTransitionMs = -1L;
            }
        }
        if (transitionStartMs >= 0L && lastTransitionMs < 0L) {
            var frame = player.currentFrame();
            if (frame != null && frame.ptsNanos >= player.currentPassStartNanos()) {
                lastTransitionMs = Util.getMillis() - transitionStartMs;
                transitionStartMs = -1L;
            } else if (Util.getMillis() - transitionStartMs > 15000L) {
                transitionStartMs = -1L;
            }
        }
    }

    /** 采样当前帧亮度，并统计“连续黑屏”的时长（用于观察循环处黑多久）。 */
    private void updateLumaStats(ByteBuffer rgba) {
        try {
            int limit = rgba.limit();
            if (limit < 12) {
                return;
            }
            ByteBuffer dup = rgba.duplicate();
            dup.rewind();
            long sum = 0L;
            int count = 0;
            for (int i = 0; i + 2 < limit; i += 300) {
                int r = dup.get(i) & 0xFF;
                int g = dup.get(i + 1) & 0xFF;
                int b = dup.get(i + 2) & 0xFF;
                sum += (r * 299 + g * 587 + b * 114) / 1000;
                count++;
            }
            if (count == 0) {
                return;
            }
            currentLuma = (float) (sum / (double) count);
            long now = Util.getMillis();
            if (currentLuma < 18.0F) {
                if (blackStartedAt < 0L) {
                    blackStartedAt = now;
                }
            } else if (blackStartedAt >= 0L) {
                lastBlackMillis = now - blackStartedAt;
                blackStartedAt = -1L;
            }
        } catch (Throwable ignored) {
            // 调试统计失败不影响播放
        }
    }

    /** 调试信息行（开场界面左上角显示；只在循环切换时计时，第一遍播放期间不会显示“黑屏”）。 */
    public java.util.List<String> debugOverlayLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (player == null) {
            return lines;
        }
        lines.add("循环遍数: " + (player.passIndex() + 1));
        long ptsMs = player.currentFrame() == null ? 0L : player.currentFrame().ptsNanos / 1_000_000L;
        lines.add(String.format(java.util.Locale.ROOT, "视频: %.1fs / %.1fs",
                ptsMs / 1000.0, player.durationNanos() / 1_000_000_000.0));
        if (transitionStartMs >= 0L && lastTransitionMs < 0L) {
            lines.add("切换等待中: " + (Util.getMillis() - transitionStartMs) + " ms");
        } else if (lastTransitionMs >= 0L) {
            lines.add("上次循环切换等待: " + lastTransitionMs + " ms");
        } else {
            lines.add("上次循环切换等待: 还没发生过");
        }
        return lines;
    }

    /** 读取“正在加载中”的位图文字（字体没加载时也能显示，不会口口口）。 */
    private void loadLoadingTextResource() {
        if (loadingTextPixels != null) {
            return;
        }
        try (InputStream in = IntroVideoClient.class.getResourceAsStream("/assets/introvideo/loading_text.png")) {
            if (in == null) {
                return;
            }
            BufferedImage image = ImageIO.read(in);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return;
            }
            loadingTextWidth = image.getWidth();
            loadingTextHeight = image.getHeight();
            ByteBuffer pixels = ByteBuffer.allocateDirect(loadingTextWidth * loadingTextHeight * 4);
            for (int y = 0; y < loadingTextHeight; y++) {
                for (int x = 0; x < loadingTextWidth; x++) {
                    int argb = image.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    pixels.put((byte) (argb >> 16));
                    pixels.put((byte) (argb >> 8));
                    pixels.put((byte) argb);
                    pixels.put((byte) a);
                }
            }
            pixels.flip();
            loadingTextPixels = pixels;
            IntroVideo.LOGGER.info("[IntroVideo] 已加载加载文字位图 loading_text.png ({}x{})",
                    loadingTextWidth, loadingTextHeight);
        } catch (Exception e) {
            IntroVideo.LOGGER.warn("[IntroVideo] 读取 loading_text.png 失败", e);
        }
    }

    /** 渲染线程：把“正在加载中”位图上传成纹理。 */
    private void ensureLoadingTextTexture() {
        if (loadingTextTexture != null || loadingTextPixels == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        loadingTextTexture = new VideoTexture(LOADING_TEXT_LOCATION, loadingTextWidth, loadingTextHeight);
        minecraft.getTextureManager().register(LOADING_TEXT_LOCATION, loadingTextTexture);
        loadingTextTexture.upload(loadingTextPixels);
    }

    /** 画“正在加载中”位图文字（不需要游戏字体）。 */
    private void drawBitmapLoadingText(GuiGraphics graphics, int width, int height) {
        ensureLoadingTextTexture();
        if (loadingTextTexture == null) {
            return;
        }
        // 和之前字体文字差不多大小（默认字体行高约 9~10，不要做太大）
        int targetHeight = 10;
        int drawWidth = Math.max(1, loadingTextWidth * targetHeight / Math.max(1, loadingTextHeight));
        int x = width / 2 - drawWidth / 2;
        int y = height - 30;
        float pulse = 0.8F + 0.2F * (float) Math.sin(Util.getMillis() / 400.0);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, pulse);
        graphics.blit(LOADING_TEXT_LOCATION, x, y, drawWidth, targetHeight, 0.0F, 0.0F,
                loadingTextWidth, loadingTextHeight, loadingTextWidth, loadingTextHeight);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** 调试用：如果 45 秒后还停在加载阶段，把所有线程栈打出来，方便排查卡死。 */
    private void startLoadingWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(45_000L);
            } catch (InterruptedException e) {
                return;
            }
            if (phase != Phase.LOADING) {
                return;
            }
            StringBuilder builder = new StringBuilder("[IntroVideo] 加载已超过 45 秒，仍在 LOADING 阶段。");
            Minecraft minecraft = Minecraft.getInstance();
            builder.append(" screen=").append(minecraft.screen == null ? "null" : minecraft.screen.getClass().getSimpleName());
            builder.append(" overlay=").append(minecraft.getOverlay() == null ? "null"
                    : minecraft.getOverlay().getClass().getName());
            if (player != null) {
                builder.append(" hasFrame=").append(player.hasFrame())
                        .append(" failed=").append(player.isFailed())
                        .append(" size=").append(player.width()).append('x').append(player.height());
            }
            builder.append(System.lineSeparator()).append("All threads:");
            for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                builder.append(System.lineSeparator()).append("  [").append(thread.getName())
                        .append(" / ").append(thread.getState()).append(']');
                StackTraceElement[] stack = entry.getValue();
                for (int i = 0; i < stack.length && i < 6; i++) {
                    builder.append(System.lineSeparator()).append("      at ").append(stack[i]);
                }
            }
            IntroVideo.LOGGER.warn(builder.toString());
        }, "IntroVideo-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void fail(String message, Throwable t) {
        if (!renderErrorLogged) {
            renderErrorLogged = true;
            IntroVideo.LOGGER.warn("[IntroVideo] {}（将使用原版加载界面）", message, t);
        }
        phase = Phase.OFF;
        closePlayer();
    }

    private void closePlayer() {
        restoreWindowTitle();
        audio.stop();
        if (player != null) {
            player.close();
            player = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
            textureRegistered = false;
        }
    }

    // ---------------------------------------------------------------- 状态查询

    public Phase phase() {
        return phase;
    }

    public boolean isActive() {
        return phase == Phase.LOADING || phase == Phase.SCREEN;
    }

    public IntroConfig config() {
        return config;
    }

    public VideoPlayer player() {
        return player;
    }

    public Component loadingComponent() {
        if (config != null && config.loadingText != null && !config.loadingText.isEmpty()) {
            return Component.literal(config.loadingText);
        }
        return translatedOr("firstlight.loading", "正在加载", "Loading");
    }

    public Component promptComponent() {
        if (config != null && config.promptText != null && !config.promptText.isEmpty()) {
            return Component.literal(config.promptText);
        }
        return translatedOr("firstlight.prompt", "按任意键继续", "Press any key to continue");
    }

    /**
     * 取翻译。
     *
     * <p>加载画面前期，语言资源可能已经好了，但**字体里的中文字形还没加载**（资源重载还没走到字体那一步）：
     * 这时画中文会变成一排“口口口”。所以这里除了判断语言是否就绪，还要判断中文字形是否可用
     * （缺字形时原版会用“方框”代替，宽度是 6；真正的 unifont 中文字宽是 9）。
     * 没就绪就用英文（纯 ASCII 永远有字形），就绪后再用中文兜底 / 正常翻译。</p>
     */
    private static Component translatedOr(String key, String fallbackZh, String fallbackEn) {
        Component result = translatedOr0(key, fallbackZh, fallbackEn);
        boolean debug = instance != null && instance.config != null && instance.config.debug;
        String text = result.getString();
        if (LOGGED_TEXTS.add(text)) {
            IntroVideo.LOGGER.info("[IntroVideo] 当前文案: {}（字体就绪={}）", text, fontsReloaded);
        }
        return result;
    }

    private static Component translatedOr0(String key, String fallbackZh, String fallbackEn) {
        try {
            net.minecraft.locale.Language language = net.minecraft.locale.Language.getInstance();
            String translated = language.getOrDefault(key);
            if (translated != null && !translated.equals(key)) {
                if (cjkFontReady()) {
                    return Component.translatable(key);
                }
                // 语言有翻译但字体还没准备好中文字形，先用英文顶一下，避免口口口
                return Component.literal(fallbackEn);
            }
            // 用一条原版翻译判断语言资源是否加载完成（加载完成前 getOrDefault 只会返回键名）
            boolean languageReady = !language.getOrDefault("menu.singleplayer").equals("menu.singleplayer");
            return Component.literal(languageReady && cjkFontReady() ? fallbackZh : fallbackEn);
        } catch (Throwable ignored) {
            return Component.literal(fallbackEn);
        }
    }

    /**
     * 当前字体能不能正常显示这段文字：纯 ASCII 永远可以；
     * 含非 ASCII（中文/日文/符号）则需要中文字形已经加载好。
     * 用来避免加载阶段/开场界面出现“口口口”。
     */
    public static boolean canRenderText(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return cjkFontReady();
            }
        }
        return true;
    }

    /** 字体（含中文字形）已经加载完成（由 FontManager Mixin 回调）。 */
    public void onFontsReloaded() {
        if (!fontsReloaded) {
            fontsReloaded = true;
            IntroVideo.LOGGER.info("[IntroVideo] 字体加载完成（中文字形可用）");
        }
    }

    /**
     * 中文字形是否已经加载好。
     *
     * <p>只认 {@code FontManagerMixin} 的“字体加载完成”回调；回调没触发就当作没就绪
     * （此时加载阶段直接隐藏文字，绝不显示“口口口”）。进入开场界面时字体必定已加载，
     * 那里会把标志补上。</p>
     */
    private static boolean cjkFontReady() {
        if (fontsReloaded) {
            return true;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return false;
            }
            // 备用判断：正常中文字形宽度 9，缺失字形（包括私有区 U+E000）宽度 6。
            // 中文字形加载好时两者宽度不同；都没加载好时宽度相同。
            int cjk = minecraft.font.width("\u6b63");
            int missing = minecraft.font.width("\uE000");
            return cjk > 0 && cjk != missing;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 是否可以显示“按任意键继续”了。
     *
     * <p>按需求：**加载一结束就提示**（此时本界面已经被创建、视频在背后继续循环播放），
     * 不需要等视频播完。视频会一直循环，直到按键/自动继续。</p>
     */
    public boolean isPromptReady() {
        return phase == Phase.SCREEN && player != null && !player.isFailed();
    }

    // ---------------------------------------------------------------- 加载阶段（Mixin 调用）

    // ---- 调试：把当前帧缓冲导出成 PNG，用于验证“视频到底有没有画到屏幕上” ----
    private int debugFrameCounter;
    private int debugDumps;
    private int fitLoggedWidth = -1;
    private int fitLoggedHeight = -1;
    private boolean overlayLogged;
    private int earlyFrameLogs;
    private int loadingFrameCount;
    private int loadingDumps;

    /** 调试用：每 300 帧（约 5 秒）导出一张当前帧缓冲的 PNG（最多 16 张）。 */    public void debugDumpFrame() {
        if (config == null || !config.debug || debugDumps >= 16) {
            return;
        }
        debugFrameCounter++;
        if (debugFrameCounter < 300 || debugFrameCounter % 300 != 0) {
            return;
        }
        debugDumps++;
        debugDumpNow("screen");
    }

    /** 立即把当前帧缓冲导出成 PNG（{@code tag} 只用于文件名）。 */
    private void debugDumpNow(String tag) {
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getWindow().getWidth();
        int height = minecraft.getWindow().getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        pixels.rewind();
        String screen = minecraft.screen == null ? "null" : minecraft.screen.getClass().getSimpleName();
        int counter = tag.startsWith("loading") ? loadingFrameCount : debugFrameCounter;
        File output = new File(minecraft.gameDirectory, "intro_dump_" + tag + "_" + counter
                + "_" + screen + ".png");
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int r = pixels.get() & 0xFF;
                    int g = pixels.get() & 0xFF;
                    int b = pixels.get() & 0xFF;
                    int a = pixels.get() & 0xFF;
                    row[x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                image.setRGB(0, height - 1 - y, width, 1, row, 0, width);
            }
            ImageIO.write(image, "png", output);
            long pts = player != null && player.currentFrame() != null ? player.currentFrame().ptsNanos : -1L;
            IntroVideo.LOGGER.info("[IntroVideo] 调试截图已保存: {}（pts={}ms）", output, pts / 1_000_000L);
        } catch (Throwable t) {
            IntroVideo.LOGGER.warn("[IntroVideo] 调试截图失败", t);
        }
    }

    /** {@code GameRenderer.render(...)} 的 TAIL 调用：在加载界面之上绘制视频。 */
    public void onEndOfFrame(float partialTick) {
        tickWindowTitle();
        tickTransitionStats();
        tickAudio();
        tickWelcomeToast();
        if (!initTried) {
            ensureInit();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (config != null && config.debug) {
            loadingFrameCount++;
            if (earlyFrameLogs < 12) {
                earlyFrameLogs++;
                IntroVideo.LOGGER.info("[IntroVideo] 渲染帧#{} phase={} screen={} level={} overlay={} hasFrame={}",
                        earlyFrameLogs, phase,
                        minecraft.screen == null ? "null" : minecraft.screen.getClass().getSimpleName(),
                        minecraft.level,
                        minecraft.getOverlay() == null ? "null" : minecraft.getOverlay().getClass().getSimpleName(),
                        player != null && player.hasFrame());
            }
            // loading 截图放在覆盖绘制之后（见下方），这里只记录帧数
        }
        if (phase != Phase.LOADING) {
            return;
        }
        if (minecraft.screen != null || minecraft.level != null) {
            return;
        }
        if (player == null || player.isFailed()) {
            // 视频不可用：不遮挡原版加载界面
            phase = Phase.OFF;
            closePlayer();
            return;
        }
        // 如果这一帧已经在“加载界面渲染完之后”画过了（IntroVideoOverlay），这里不再重复画
        boolean alreadyDrawn = drawnInOverlayThisFrame;
        drawnInOverlayThisFrame = false;
        try {
            if (!alreadyDrawn) {
                if (!overlayLogged) {
                    overlayLogged = true;
                    if (config != null && config.debug) {
                        IntroVideo.LOGGER.info("[IntroVideo] 主窗口第一帧开始覆盖绘制（黑底/加载文字）");
                    }
                }
                // 加载阶段只画黑底 + “正在加载…”，视频等开场界面出现后再从 startAt 正常播放
                drawFullscreenOverlay(minecraft, true, false);
            }
            if (config != null && config.debug && loadingDumps < 6 && loadingFrameCount >= new int[]{10, 20, 30, 60, 90, 120}[loadingDumps]) {
                loadingDumps++;
                debugDumpNow("loading" + loadingDumps);
            }
            debugDumpFrame();
        } catch (Throwable t) {
            if (!renderErrorLogged) {
                renderErrorLogged = true;
                IntroVideo.LOGGER.warn("[IntroVideo] 绘制加载阶段视频失败", t);
            }
            phase = Phase.OFF;
            closePlayer();
        }
    }

    // ---------------------------------------------------------------- 加载界面包裹（Mixin 调用）

    private boolean drawnInOverlayThisFrame;

    /** 由 {@code Minecraft.setOverlay} 的 Mixin 调用：把加载界面包一层，渲染完它后立刻盖我们的画面。 */
    public Overlay wrapOverlay(Overlay overlay) {
        if (overlay == null || overlay instanceof IntroVideoOverlay) {
            return overlay;
        }
        if (phase != Phase.LOADING && phase != Phase.SCREEN) {
            return overlay;
        }
        if (player == null || player.isFailed() || config == null || !config.enabled) {
            return overlay;
        }
        IntroVideo.LOGGER.info("[IntroVideo] 已包裹加载界面 {}（视频会在它渲染之后覆盖）",
                overlay.getClass().getSimpleName());
        return new IntroVideoOverlay(overlay);
    }

    /** 由 {@link IntroVideoOverlay} 在原加载界面渲染完之后调用（同一帧、同一个投影）。 */
    public void renderOnTopOfLoadingOverlay() {
        // 加载阶段（以及刚切到开场界面的那一两帧）都盖住；level != null 时说明已经进世界了，别再盖
        if (phase != Phase.LOADING && phase != Phase.SCREEN) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level != null) {
            return;
        }
        if (player == null || player.isFailed()) {
            return;
        }
        try {
            // 加载阶段默认显示底图+加载文字；加载超过 loadingVideoAfterSeconds 后直接在加载画面上开始播视频，
            // 避免整合包加载慢时长时间停在静态图上。视频时钟从这一刻开始，进开场界面后继续（不会重播/跳帧）。
            boolean playVideo = config != null && config.loadingVideoAfterSeconds > 0.0
                    && (Util.getMillis() - loadingStartedAt) >= (long) (config.loadingVideoAfterSeconds * 1000.0);
            drawFullscreenOverlay(minecraft, true, playVideo);
            drawnInOverlayThisFrame = true;
            if (!overlayLogged) {
                overlayLogged = true;
                if (config != null && config.debug) {
                    IntroVideo.LOGGER.info("[IntroVideo] 已在加载界面渲染之后覆盖绘制（底图/加载文字）");
                }
            }
        } catch (Throwable t) {
            if (!renderErrorLogged) {
                renderErrorLogged = true;
                IntroVideo.LOGGER.warn("[IntroVideo] 覆盖加载界面失败", t);
            }
        }
    }

    /**
     * 加载阶段绘制：自己重建 GUI 投影（Forge/NeoForge 的加载界面会把投影矩阵改成帧缓冲坐标系，
     * 不重置的话视频会被画到错误的位置/尺寸）。
     *
     * @param showVideo 是否绘制视频画面；加载阶段传 false（只画黑底+文字，视频到开场界面再开始）
     */
    private void drawFullscreenOverlay(Minecraft minecraft, boolean showLoadingText, boolean showVideo) {
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();

        Matrix4f projection = new Matrix4f().setOrtho(0.0F, (float) width, (float) height, 0.0F, 1000.0F, 21000.0F);
        RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
        PoseStack poseStack = RenderSystem.getModelViewStack();
        poseStack.pushPose();
        poseStack.setIdentity();
        // 关键：原版 GUI 投影是用 near=1000 / far=21000 的 ortho + 把 modelview 往 -11000 平移的，
        // 少了这一步，z=0 的 GUI 顶点会落在裁剪范围之外 → 画了也看不见（这正是加载阶段
        // 一直露出原版红色加载画面的原因）。
        poseStack.translate(0.0F, 0.0F, -11000.0F);
        RenderSystem.applyModelViewMatrix();

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
        try {
            int background = config == null ? 0xFF000000 : config.backgroundColorArgb();
            if (showVideo) {
                uploadNewFrame();
                drawVideo(graphics, width, height, background);
            } else {
                drawLoadingPoster(graphics, width, height, background);
            }

            if (showLoadingText && config != null && config.showLoadingText) {
                int y = height - 30;
                boolean customText = config.loadingText != null && !config.loadingText.isEmpty();
                // 字体（含 ASCII）在资源重载完成前可能一个字形都没有，画出来全是“口口口”。
                // 所以加载阶段在字体加载完成回调之前，一律不显示文字（只留底图+进度条），
                // 回调触发后才显示。这样无论中英文都不会出现方框。
                if (!fontsReloaded) {
                    if (config.useBitmapLoadingText) {
                        drawBitmapLoadingText(graphics, width, height);
                    } else if (config.showLoadingIndicator) {
                        drawActivityDots(graphics, width, height);
                    }
                } else {
                    if (!customText) {
                        // 默认文字 + 动态点。基线文字固定居中，点只占右侧预留位置：
                        // 出现/消失时整行不抖动，点的样式由 loadingDots 决定（中间 · / 底部 . / 固定 …）。
                        String style = config.loadingDots == null ? "bottom" : config.loadingDots.trim().toLowerCase(java.util.Locale.ROOT);
                        String base = loadingComponent().getString();
                        int baseWidth = minecraft.font.width(base);
                        String glyph;
                        int count;
                        int slotWidth;
                        switch (style) {
                            case "none":
                                glyph = "";
                                count = 0;
                                slotWidth = 0;
                                break;
                            case "bottom":
                                glyph = ".";
                                count = 1 + (int) ((Util.getMillis() / 350L) % 3L);
                                slotWidth = minecraft.font.width("...");
                                break;
                            case "ellipsis":
                                glyph = "…";
                                count = 1;
                                slotWidth = minecraft.font.width("…");
                                break;
                            case "middle":
                            default:
                                glyph = "·";
                                count = 1 + (int) ((Util.getMillis() / 350L) % 3L);
                                slotWidth = minecraft.font.width("···");
                                break;
                        }
                        int x = width / 2 - (baseWidth + slotWidth) / 2;
                        graphics.drawString(minecraft.font, base, x, y, 0xFFFFFFFF, true);
                        if (!glyph.isEmpty()) {
                            graphics.drawString(minecraft.font, glyph.repeat(count), x + baseWidth, y, 0xFFFFFFFF, true);
                        }
                    } else {
                        Component text = loadingComponent();
                        int textWidth = minecraft.font.width(text);
                        graphics.drawString(minecraft.font, text, width / 2 - textWidth / 2, y, 0xFFFFFFFF, true);
                    }
                }
            }
            if (config != null && config.showLoadingBar) {
                drawLoadingBar(graphics, width, height);
            }
        } finally {
            graphics.flush();
            poseStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** 字体加载完成前用的纯图形加载动画（3 个脉冲小方块，不依赖任何字形）。 */
    private void drawActivityDots(GuiGraphics graphics, int width, int height) {
        long now = Util.getMillis();
        int size = 3;
        int gap = 5;
        int total = 3 * size + 2 * gap;
        int x0 = width / 2 - total / 2;
        int y = height - 32;
        for (int i = 0; i < 3; i++) {
            float pulse = 0.5F + 0.5F * (float) Math.sin(now / 220.0 - i * 0.9);
            int alpha = (int) (60 + 195 * pulse) & 0xFF;
            graphics.fill(x0 + i * (size + gap), y, x0 + i * (size + gap) + size, y + size,
                    (alpha << 24) | 0xFFFFFF);
        }
    }

    /** 加载阶段底部的“不确定进度”滑动条（纯视觉效果，表示没有卡住）。 */
    private void drawLoadingBar(GuiGraphics graphics, int width, int height) {
        int barWidth = Math.min(240, Math.max(80, width / 2));
        int barHeight = 3;
        int x = (width - barWidth) / 2;
        int y = height - 16;
        graphics.fill(x - 1, y - 1, x + barWidth + 1, y + barHeight + 1, 0x66000000);
        graphics.fill(x, y, x + barWidth, y + barHeight, 0x22FFFFFF);
        int segment = Math.max(24, barWidth / 5);
        int travel = barWidth + segment;
        int offset = (int) ((Util.getMillis() / 10L) % travel);
        int left = Math.max(x, x - segment + offset);
        int right = Math.min(x + barWidth, x - segment + offset + segment);
        if (right > left) {
            graphics.fill(left, y, right, y + barHeight, 0xCCFFFFFF);
        }
    }

    // ---------------------------------------------------------------- 开场动画界面（Screen 调用）

    private boolean posterUploaded;

    /** 底图带一点轻微的缩放呼吸，看起来不是一张“卡住的图片”。 */
    private void drawPosterWithZoom(GuiGraphics graphics, int width, int height, int background) {
        float zoom = 1.0F;
        if (config != null && config.loadingPosterZoom) {
            double phase = (Util.getMillis() % 6000L) / 6000.0 * Math.PI * 2.0;
            zoom = (float) (1.03 + 0.025 * Math.sin(phase));
        }
        if (Math.abs(zoom - 1.0F) < 1.0E-4F) {
            drawImage(graphics, POSTER_LOCATION, posterWidth, posterHeight, width, height, background);
            return;
        }
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(width / 2.0F, height / 2.0F, 0.0F);
        pose.scale(zoom, zoom, 1.0F);
        pose.translate(-width / 2.0F, -height / 2.0F, 0.0F);
        drawImage(graphics, POSTER_LOCATION, posterWidth, posterHeight, width, height, background);
        pose.popPose();
    }

    /**
     * 加载阶段的底图：优先显示打包的静态图 poster.png（不依赖解码，立刻就有）；
     * 没有的话退回视频首帧；再没有就是纯黑底。
     */
    private void drawLoadingPoster(GuiGraphics graphics, int width, int height, int background) {
        if (config != null && config.showLoadingPoster) {
            ensurePosterTexture();
            if (posterTexture != null) {
                drawPosterWithZoom(graphics, width, height, background);
                return;
            }
            if (player != null && !player.isFailed()) {
                if (!posterUploaded) {
                    VideoPlayer.Frame preview = player.previewFrame();
                    if (preview != null) {
                        ensureTexture();
                        if (texture != null) {
                            texture.upload(preview.rgba);
                            posterUploaded = true;
                        }
                    }
                }
                if (posterUploaded) {
                    drawVideo(graphics, width, height, background);
                    return;
                }
            }
        }
        graphics.fill(0, 0, width, height, background);
    }

    /** 由 {@link IntroVideoScreen} 调用：黑底 + 当前视频帧。 */
    public void renderInScreen(GuiGraphics graphics, int width, int height) {

        if (player == null) {
            graphics.fill(0, 0, width, height, 0xFF000000);
            return;
        }
        uploadNewFrame();
        drawVideo(graphics, width, height, 0xFF000000);
    }

    private boolean firstFrameLogged;
    private int audioRetries;
    private long audioLastAttemptAt;
    private long audioStartedAt;
    private boolean audioStatusLogged;
    private boolean loopAudioStarted;
    private boolean vanillaMusicCleared;

    private void uploadNewFrame() {
        if (player == null) {
            return;
        }
        VideoPlayer.Frame frame = player.advance(System.nanoTime());
        if (frame == null) {
            return;
        }
        ensureTexture();
        if (texture != null) {
            texture.upload(frame.rgba);
        }
        updateLumaStats(frame.rgba);
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            IntroVideo.LOGGER.info("[IntroVideo] 首帧已显示 ({}x{}, pts={}ms)",
                    player.width(), player.height(), frame.ptsNanos / 1_000_000L);
            if (config != null && config.audio) {
                startAudio();
            }
        }
    }

    /** 启动开场音频，并把原版音乐停掉（只停 MUSIC 这一类，不影响音效）。 */
    private void startAudio() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || config == null || !config.audio) {
            return;
        }
        try {
            // 只停音乐这一类声音，按钮音效/环境音不受影响
            minecraft.getSoundManager().stop(null, net.minecraft.sounds.SoundSource.MUSIC);
        } catch (Throwable ignored) {
            // 声音系统还没完全就绪时忽略
        }
        audio.start((float) config.audioVolume, config.audioBackend);
        audioRetries = 0;
        audioStartedAt = Util.getMillis();
        audioLastAttemptAt = audioStartedAt;
        audioStatusLogged = false;
    }

    /**
     * 每帧调用：开场视频期间停掉原版音乐；并在开场音频没播起来时重试几次
     * （声音引擎在启动早期可能还没就绪，太早播放会被丢掉）。
     */
    public void tickAudio() {
        if (config == null || !config.audio || !isActive()) {
            return;
        }
        long now = Util.getMillis();
        if (!vanillaMusicCleared) {
            vanillaMusicCleared = true;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) {
                try {
                    minecraft.getSoundManager().stop(null, net.minecraft.sounds.SoundSource.MUSIC);
                } catch (Throwable ignored) {
                }
            }
        }
        if (!firstFrameLogged) {
            return;
        }
        if (!audioStatusLogged && now - audioStartedAt > 1500L) {
            audioStatusLogged = true;
            IntroVideo.LOGGER.info("[IntroVideo] 开场音频状态：{}（后端 {}）",
                    audio.isPlaying() ? "播放中" : "未在播放（继续重试）", audio.backend());
        }
        // 视频进入第二遍（循环重播）后：音频也切到“跳过片头”的版本，和画面保持一致
        if (!loopAudioStarted && player != null && player.passIndex() >= 1) {
            long passStart = player.currentPassStartNanos();
            if (passStart > 0L) {
                loopAudioStarted = true;
                double skipSeconds = passStart / 1_000_000_000.0;
                audio.startLoopPart((float) config.audioVolume, skipSeconds, config.endAtSeconds);
                audioStatusLogged = false;
                audioStartedAt = now;
                audioLastAttemptAt = now;
                IntroVideo.LOGGER.info("[IntroVideo] 进入循环重播：画面/音频都从第 {} 秒开始", skipSeconds);
            }
        }
        // 只有走 MC 声音系统时才需要重试（声音引擎可能比视频晚几百毫秒启动）；
        // Java Sound 后端在后台线程里自己加载，不用重试。
        boolean shouldRetry = !loopAudioStarted && "minecraft".equals(audio.backend())
                && !audio.isPlaying()
                && audioRetries < 24
                && now - audioLastAttemptAt >= 600L
                && now - audioStartedAt < 15000L;
        if (shouldRetry) {
            audioRetries++;
            audioLastAttemptAt = now;
            audio.start((float) config.audioVolume, config.audioBackend);
        }
    }

    /** 开场视频期间是否屏蔽原版音乐（Mixin 调用）。 */
    public boolean suppressVanillaMusic() {
        return config != null && config.muteVanillaMusic && isActive();
    }

    // ---------------------------------------------------------------- 窗口标题动画

    private boolean titleCustomized;
    private volatile boolean promptVisible;
    private String chosenTitlePhrase;
    private float currentLuma = 255.0F;
    private long blackStartedAt = -1L;
    private long lastBlackMillis = -1L;
    private int lastObservedPass = -1;
    private long welcomeToastStartMs = -1L;
    private boolean welcomeToastShown;
    private long transitionStartMs = -1L;
    private long lastTransitionMs = -1L;
    private long lastTitleUpdateMs;

    /** 加载/开场期间把游戏窗口标题改成“加载中 . .. ...”动画（用系统字体，不依赖游戏字体）。 */
    public void tickWindowTitle() {
        if (!isActive() || config == null || !config.changeWindowTitle) {
            restoreWindowTitle();
            return;
        }
        long now = Util.getMillis();
        if (now - lastTitleUpdateMs < 280L) {
            return;
        }
        lastTitleUpdateMs = now;
        int dots = (int) ((now / 280L) % 4L);
        String base;
        if (phase == Phase.SCREEN) {
            // 加载完成进入开场界面后：恢复默认 Minecraft 标题（不再显示“按任意键继续”之类）
            if (config.windowTitlePromptVanilla) {
                restoreWindowTitle();
                return;
            }
            if (!promptVisible) {
                base = "Minecraft* " + (chosenTitlePhrase == null ? "正在为您书写传奇" : chosenTitlePhrase);
            } else {
                base = config.windowTitlePromptText == null || config.windowTitlePromptText.isEmpty()
                        ? "Minecraft* 按任意键继续" : config.windowTitlePromptText;
            }
        } else {
            base = "Minecraft* " + (chosenTitlePhrase == null ? "正在为您书写传奇" : chosenTitlePhrase);
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) {
                minecraft.getWindow().setTitle(base + ".".repeat(dots));
                titleCustomized = true;
            }
        } catch (Throwable ignored) {
            // 窗口标题设置失败不影响视频
        }
    }

    /** 从短语池里随机挑一句作为本次启动的标题短语。 */
    private String pickTitlePhrase() {
        try {
            if (config != null && config.windowTitlePhrases != null
                    && config.windowTitlePhrases.length > 0) {
                String[] pool = config.windowTitlePhrases;
                return pool[java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.length)];
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "正在为您书写传奇";
    }

    /** 由 IntroVideoScreen 在第一次画出「按任意键继续」时调用。 */
    public void notifyPromptVisible() {
        promptVisible = true;
    }

    /** 恢复原版窗口标题（进入主菜单/禁用/失败时调用）。 */
    public void restoreWindowTitle() {
        if (!titleCustomized) {
            return;
        }
        titleCustomized = false;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) {
                minecraft.updateTitle();
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private void ensureTexture() {
        if (texture != null || player == null) {
            return;
        }
        int width = player.width();
        int height = player.height();
        if (width <= 0 || height <= 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        texture = new VideoTexture(TEXTURE_LOCATION, width, height);
        if (!textureRegistered) {
            minecraft.getTextureManager().register(TEXTURE_LOCATION, texture);
            textureRegistered = true;
        }
    }

    /** 画黑底 + 视频。三种适配方式：cover（默认，铺满无黑边）/ contain（完整显示有黑边）/ stretch（拉伸）。 */
    private void drawVideo(GuiGraphics graphics, int width, int height, int background) {
        if (texture == null || player == null) {
            graphics.fill(0, 0, width, height, background);
            return;
        }
        drawImage(graphics, TEXTURE_LOCATION, player.width(), player.height(), width, height, background);
    }

    /** 按 fit 配置把一张纹理铺到当前窗口（cover/contain/stretch），窗口多大就铺多大。 */
    private void drawImage(GuiGraphics graphics, ResourceLocation location, int imageWidth, int imageHeight,
                           int width, int height, int background) {
        graphics.fill(0, 0, width, height, background);
        if (imageWidth <= 0 || imageHeight <= 0) {
            return;
        }
        IntroConfig.FitMode fit = config == null ? IntroConfig.FitMode.COVER : config.fitMode();
        double scale;
        switch (fit) {
            case STRETCH:
                scale = Double.NaN; // 直接拉满
                break;
            case CONTAIN:
                scale = Math.min(width / (double) imageWidth, height / (double) imageHeight);
                break;
            case COVER:
            default:
                scale = Math.max(width / (double) imageWidth, height / (double) imageHeight);
                break;
        }
        final int drawWidth;
        final int drawHeight;
        if (Double.isNaN(scale)) {
            drawWidth = width;
            drawHeight = height;
        } else {
            drawWidth = Math.max(1, (int) Math.round(imageWidth * scale));
            drawHeight = Math.max(1, (int) Math.round(imageHeight * scale));
        }
        // cover 模式下画面会比屏幕大，超出部分由 GL 视口自动裁掉（上下/左右黑边消失）
        int x = (width - drawWidth) / 2;
        int y = (height - drawHeight) / 2;
        if (config != null && config.debug && (width != fitLoggedWidth || height != fitLoggedHeight)) {
            fitLoggedWidth = width;
            fitLoggedHeight = height;
            IntroVideo.LOGGER.info("[IntroVideo] 画面适配 {}：屏幕 {}x{}，素材 {}x{}，绘制 {}x{} @ ({},{})",
                    fit, width, height, imageWidth, imageHeight, drawWidth, drawHeight, x, y);
        }
        graphics.blit(location, x, y, drawWidth, drawHeight, 0.0F, 0.0F,
                imageWidth, imageHeight, imageWidth, imageHeight);
    }

    // ---------------------------------------------------------------- 主菜单拦截（Mixin 调用）

    /**
     * 原版加载完成后会 {@code setScreen(new TitleScreen(true))}；这里返回 true 表示要插入开场动画。
     */
    public boolean shouldReplaceFirstScreen(Screen screen) {
        if (phase != Phase.LOADING) {
            return false;
        }
        if (!(screen instanceof TitleScreen) && !(screen instanceof AccessibilityOnboardingScreen)) {
            return false;
        }
        if (player == null || player.isFailed()) {
            // 视频不可用：不要挡住玩家进主菜单
            phase = Phase.OFF;
            closePlayer();
            return false;
        }
        phase = Phase.SCREEN;
        // 能走到这里说明资源重载已完成，字体肯定加载好了（万一 Mixin 没触发，这里兜底）
        if (!fontsReloaded) {
            IntroVideo.LOGGER.warn("[IntroVideo] 字体加载回调没有触发，进入开场界面时兜底标记字体可用");
            fontsReloaded = true;
        }
        // 保持循环：没按键就一直播（提示“按任意键继续”在加载完成时就已经显示）
        return true;
    }

    /** 开场动画结束（按键/播完/失败），进入主菜单。 */
    public void onIntroFinished() {
        phase = Phase.DONE;
        closePlayer();
    }
}
