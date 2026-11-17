package com.syuto.introvideo.mixin;

import com.syuto.introvideo.client.IntroVideoClient;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.sounds.Music;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 开场视频期间屏蔽**原版音乐**（只屏蔽 MUSIC 这一类声音；按钮音效、环境音等不受影响）。
 *
 * <p>如果不屏蔽，新启动的客户端会在还没进主菜单的时候就听到原版标题/菜单音乐，
 * 和我们的开场视频声音混在一起。</p>
 */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 1)
    private void introvideo$muteWhileIntro(CallbackInfo ci) {
        if (IntroVideoClient.get().suppressVanillaMusic()) {
            ci.cancel();
        }
    }

    @Inject(method = "startPlaying", at = @At("HEAD"), cancellable = true, require = 1)
    private void introvideo$muteStartWhileIntro(Music music, CallbackInfo ci) {
        if (IntroVideoClient.get().suppressVanillaMusic()) {
            ci.cancel();
        }
    }
}
