package com.syuto.introvideo.client;

import com.syuto.introvideo.IntroVideo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Overlay;

/**
 * 包住游戏原本的加载界面（{@code LoadingOverlay} / Forge 的 {@code ForgeLoadingOverlay}）。
 *
 * <p>为什么需要它：{@code GameRenderer.render} 末尾的 TAIL 注入在某些加载器/版本下会画在
 * 加载界面之前（加载界面随后又把自己的红色画面刷上去），所以只靠 TAIL 可能出现
 * “先看到原版红底白字，再出现我们的视频”。</p>
 *
 * <p>这里在 {@code Minecraft.setOverlay(...)} 时把原加载界面包一层：先让原加载界面照常渲染
 * （它内部的 onFinish/completeModLoading 等逻辑不受影响），**紧接着在同一个渲染阶段里**
 * 画我们的黑底/视频，这样一定能盖住它的画面。</p>
 */
public final class IntroVideoOverlay extends Overlay {

    private final Overlay delegate;

    public IntroVideoOverlay(Overlay delegate) {
        this.delegate = delegate;
    }

    public Overlay delegate() {
        return delegate;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (delegate != null) {
            delegate.render(graphics, mouseX, mouseY, partialTick);
        }
        IntroVideoClient.get().renderOnTopOfLoadingOverlay();
    }

    @Override
    public boolean isPauseScreen() {
        return delegate != null && delegate.isPauseScreen();
    }
}
