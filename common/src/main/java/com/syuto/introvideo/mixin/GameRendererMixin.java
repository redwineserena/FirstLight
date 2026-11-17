package com.syuto.introvideo.mixin;

import com.syuto.introvideo.client.IntroVideoClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在每帧渲染的最后（原版 / Forge / NeoForge 的加载界面都画完之后）再画一层全屏视频，
 * 从而“盖掉”加载动画。
 *
 * <p>为什么不直接取消加载界面的渲染？因为 Forge/NeoForge 的加载界面
 * （{@code ForgeLoadingOverlay}）在自己的 {@code render} 里负责
 * {@code onFinish} 回调、{@code ClientModLoader.completeModLoading()}、
 * {@code displayWindow.close()} 以及切换主菜单；取消它会让游戏永远卡在加载阶段。
 * 因此这里选择“照常让它跑，只是画在它上面”。</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render(FJZ)V", at = @At("TAIL"), require = 1)
    private void introvideo$drawOverLoadingScreen(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        IntroVideoClient.get().onEndOfFrame(partialTick);
    }
}
