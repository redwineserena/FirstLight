package com.syuto.introvideo.client;

import com.syuto.introvideo.IntroVideo;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * 视频播完之后的“按任意键继续”界面。
 *
 * <p>它不是在加载阶段就创建的，而是等原版要显示主菜单时由
 * {@link IntroVideoClient#shouldReplaceFirstScreen(Screen)} 拦截后替换过来的，
 * 所以此时加载已经完成、输入系统也处于正常状态。</p>
 */
public final class IntroVideoScreen extends Screen {

    private static final int KEY_ESCAPE = 256;

    private final IntroVideoClient client;
    private final Screen nextScreen;

    private long promptAppearedAt = -1L;
    /** 提示文字第一次真正画出来的时间（用于弹出/呼吸动画）。 */
    private long promptShownAt = -1L;
    private long finishStart = -1L;
    private boolean finishRequested;
    private boolean completePending;
    private boolean closed;

    public IntroVideoScreen(IntroVideoClient client, Screen nextScreen) {
        super(Component.translatable("introvideo.title"));
        this.client = client;
        this.nextScreen = nextScreen;
    }

    @Override
    protected void init() {
        // 没有按钮；一切都是全屏视频
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        client.tickAudio();
        client.tickWindowTitle();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        // 黑底 + 当前帧
        client.renderInScreen(graphics, width, height);

        IntroConfig config = client.config();

        if (finishRequested) {
            renderFadeOut(graphics, width, height, config);
            return;
        }

        if (client.player() == null || client.player().isFailed()) {
            // 视频出问题了，直接进主菜单
            completePending = true;
            return;
        }

        if (client.isPromptReady()) {
            long now = Util.getMillis();
            if (promptAppearedAt < 0L) {
                promptAppearedAt = now;
                IntroVideo.LOGGER.info("[IntroVideo] 加载完成，显示「按任意键继续」（视频继续循环播放，按键即进入主界面）");
            }
            long shownFor = now - promptAppearedAt;
            long delay = config == null ? 0L : config.promptDelayMillis();
            if (shownFor >= delay) {
                if (config == null || config.showPrompt) {
                    if (promptShownAt < 0L) {
                        promptShownAt = now;
                    }
                    client.notifyPromptVisible();
                    drawPrompt(graphics, width, height);
                }
                long auto = config == null ? 0L : config.autoContinueMillis();
                boolean autoContinue = auto > 0L && shownFor >= delay + auto;
                if (config != null && !config.requireKeypress && auto <= 0L) {
                    // 配置成“不用按键”：加载完成就直接进入主菜单
                    autoContinue = true;
                }
                if (autoContinue) {
                    beginFinish();
                }
            }
        }

        // 调试覆盖层：播放遍数 / 视频时间 / 亮度 / 黑屏时长（默认关闭，方便观察循环黑屏）
        if (config != null && config.showLoopDebug) {
            java.util.List<String> lines = client.debugOverlayLines();
            int y = 6;
            for (String line : lines) {
                int w = font.width(line);
                graphics.fill(4, y - 2, 8 + w, y + font.lineHeight + 1, 0x99000000);
                graphics.drawString(font, line, 6, y, 0xFFFFFF00, true);
                y += font.lineHeight + 2;
            }
        }

        // 调试截图放在最后，确保能看到提示文字/淡出效果
        client.debugDumpFrame();
    }

    private void drawPrompt(GuiGraphics graphics, int width, int height) {
        Component prompt = client.promptComponent();
        // 字形没就绪（含中文但字体还没加载好）时先不画，避免口口口
        if (!IntroVideoClient.canRenderText(prompt.getString())) {
            return;
        }
        IntroConfig cfg = client.config();
        float baseScale = cfg == null ? 0.8F : (float) Math.max(0.4, Math.min(2.0, cfg.promptScale));
        int bottomOffset = cfg == null ? 16 : Math.max(0, cfg.promptYOffset);
        String animation = cfg == null ? "pop" : cfg.promptAnimation;

        // 动画：pop = 弹出(带回弹) + 呼吸；pulse = 只呼吸；none = 静止
        float animScale = 1.0F;
        float alpha = 1.0F;
        long now = Util.getMillis();
        if (promptShownAt > 0L && !"none".equalsIgnoreCase(animation)) {
            long elapsed = Math.max(0L, now - promptShownAt);
            if ("pulse".equalsIgnoreCase(animation)) {
                float pulse = 0.5F + 0.5F * (float) Math.sin(elapsed / 430.0);
                animScale = 1.0F + 0.035F * pulse;
                alpha = 0.82F + 0.18F * pulse;
            } else {
                float t = Math.min(1.0F, elapsed / 320.0F);
                float c1 = 1.70158F;
                float c3 = c1 + 1.0F;
                float ease = 1.0F + c3 * (float) Math.pow(t - 1.0F, 3) + c1 * (float) Math.pow(t - 1.0F, 2);
                animScale = 0.55F + 0.45F * ease;
                alpha = Math.min(1.0F, elapsed / 220.0F);
                if (t >= 1.0F) {
                    float pulse = 0.5F + 0.5F * (float) Math.sin((elapsed - 320L) / 430.0);
                    animScale *= 1.0F + 0.035F * pulse;
                    alpha = 0.82F + 0.18F * pulse;
                }
            }
        }
        float scale = baseScale * animScale;
        int textWidth = font.width(prompt);
        // 缩放绘制：坐标除以 scale，最终屏幕上文字更小、位置更靠下
        int x = (int) Math.round((width / 2.0) / scale - textWidth / 2.0);
        int y = (int) Math.round((height - bottomOffset) / (double) scale) - font.lineHeight;
        int alphaByte = (int) (Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F);
        int color = (alphaByte << 24) | 0xFFFFFF;
        var pose = graphics.pose();
        pose.pushPose();
        pose.scale(scale, scale, 1.0F);
        // 只画文字（带阴影），不加任何背景条
        graphics.drawString(font, prompt, x, y, color, true);
        pose.popPose();
    }

    private void renderFadeOut(GuiGraphics graphics, int width, int height, IntroConfig config) {
        long total = config == null ? 0L : config.fadeOutMillis();
        if (finishStart < 0L) {
            finishStart = Util.getMillis();
        }
        long elapsed = Util.getMillis() - finishStart;
        if (total <= 0L || elapsed >= total) {
            completePending = true;
            return;
        }
        float progress = Math.min(1.0F, elapsed / (float) total);
        int alpha = (int) (progress * 255.0F) << 24;
        graphics.fill(0, 0, width, height, alpha);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (finishRequested || completePending) {
            return true;
        }
        IntroConfig config = client.config();
        if (config == null) {
            beginFinish();
            return true;
        }
        if (client.isPromptReady()) {
            // “按任意键继续”
            beginFinish();
            return true;
        }
        switch (config.skipMode()) {
            case ANY_KEY:
                beginFinish();
                return true;
            case ESC_ONLY:
                if (keyCode == KEY_ESCAPE) {
                    beginFinish();
                    return true;
                }
                return false;
            case DISABLED:
            default:
                return false;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (finishRequested || completePending) {
            return true;
        }
        IntroConfig config = client.config();
        if (config == null || client.isPromptReady() || config.skipMode() == IntroConfig.SkipMode.ANY_KEY) {
            beginFinish();
            return true;
        }
        return false;
    }

    @Override
    public void tick() {
        if (completePending) {
            complete();
            return;
        }
        if (client.player() == null || client.player().isFailed()) {
            complete();
        }
    }

    private void beginFinish() {
        if (finishRequested) {
            return;
        }
        finishRequested = true;
        finishStart = Util.getMillis();
        IntroVideo.LOGGER.info("[IntroVideo] 开始淡出，准备进入主界面");
        IntroConfig config = client.config();
        if (config == null || config.fadeOutMillis() <= 0L) {
            completePending = true;
        }
    }

    private void complete() {
        if (closed) {
            return;
        }
        closed = true;
        IntroVideo.LOGGER.info("[IntroVideo] 开场动画结束，进入主界面");
        try {
            client.onIntroFinished();
        } catch (Throwable t) {
            IntroVideo.LOGGER.warn("[IntroVideo] 结束开场动画时出错", t);
        }
        Screen target = nextScreen != null ? nextScreen : new TitleScreen();
        if (minecraft != null) {
            minecraft.setScreen(target);
        }
    }

    @Override
    public void removed() {
        // 如果被别的界面顶掉了，也要保证解码线程被关掉
        if (!closed) {
            closed = true;
            try {
                client.onIntroFinished();
            } catch (Throwable t) {
                IntroVideo.LOGGER.warn("[IntroVideo] 关闭开场动画时出错", t);
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        // 背景由视频自己画
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
