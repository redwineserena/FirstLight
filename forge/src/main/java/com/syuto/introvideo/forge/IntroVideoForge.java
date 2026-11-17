package com.syuto.introvideo.forge;

import com.syuto.introvideo.IntroVideo;
import com.syuto.introvideo.client.IntroVideoClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Forge 1.20.1 entry point.
 *
 * <p>The mod itself is completely client-only: the actual work is done by the mixins in
 * {@code com.syuto.introvideo.mixin} and the common {@code com.syuto.introvideo.client} package.
 * This class only exists so that Forge recognises the jar as a mod, logs a friendly line at
 * startup, disables the Forge early splash window for future launches, and starts the video
 * player early (before Minecraft's first frame) so the vanilla loading screen is covered
 * immediately.</p>
 */
@Mod(IntroVideo.MOD_ID)
public final class IntroVideoForge {
    public IntroVideoForge() {
        IntroVideo.boot("Forge " + ModList.get().getModContainerById("forge")
                .map(c -> c.getModInfo().getVersion().toString()).orElse("?"));
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 关闭 Forge 自己的早期启动动画（那个 Forge logo + 进度条的小窗口），下次启动生效
            IntroVideo.disableForgeEarlyWindow(FMLPaths.CONFIGDIR.get());
            // 提前开始解码，等 Minecraft 第一次渲染时通常已经有画面（或黑底），不会露出原版加载动画
            IntroVideoClient.get().preInit(FMLPaths.GAMEDIR.get());
        }
    }
}
