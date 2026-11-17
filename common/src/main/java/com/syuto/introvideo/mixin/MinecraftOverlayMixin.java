package com.syuto.introvideo.mixin;

import com.syuto.introvideo.client.IntroVideoClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 把游戏原本的加载界面（LoadingOverlay / ForgeLoadingOverlay）包一层，
 * 在它渲染完的同一帧里立刻画上我们的黑底/视频，从而彻底盖住它的画面。
 */
@Mixin(Minecraft.class)
public abstract class MinecraftOverlayMixin {

    @ModifyVariable(method = "setOverlay", at = @At("HEAD"), argsOnly = true, require = 1)
    private Overlay introvideo$wrapLoadingOverlay(Overlay overlay) {
        return IntroVideoClient.get().wrapOverlay(overlay);
    }
}
