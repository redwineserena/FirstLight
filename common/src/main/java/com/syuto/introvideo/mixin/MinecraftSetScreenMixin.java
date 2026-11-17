package com.syuto.introvideo.mixin;

import com.syuto.introvideo.IntroVideo;
import com.syuto.introvideo.client.IntroVideoClient;
import com.syuto.introvideo.client.IntroVideoScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 原版在加载完成后会 {@code setScreen(new TitleScreen(true))}；这里把参数换成
 * {@link IntroVideoScreen}，等视频播完 / 玩家按键后再把原来的主菜单放回去。
 *
 * <p>用 {@code @ModifyVariable(argsOnly = true)} 而不是取消整个方法，
 * 这样原版的 setScreen 逻辑（初始化界面、触发 Forge 的 ScreenEvent.Opening 等）都保持不变。</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftSetScreenMixin {

    @ModifyVariable(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V",
            at = @At("HEAD"), argsOnly = true, require = 1)
    private Screen introvideo$replaceFirstScreen(Screen screen) {
        if (screen == null) {
            return null;
        }
        try {
            IntroVideoClient client = IntroVideoClient.get();
            if (client.shouldReplaceFirstScreen(screen)) {
                IntroVideo.LOGGER.info("[IntroVideo] 拦截到主菜单，改为播放开场动画");
                return new IntroVideoScreen(client, screen);
            }
        } catch (Throwable t) {
            IntroVideo.LOGGER.warn("[IntroVideo] 拦截主菜单失败，按原版处理", t);
        }
        return screen;
    }
}
