package com.syuto.introvideo.neoforge;

import com.syuto.introvideo.IntroVideo;
import com.syuto.introvideo.client.IntroVideoClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * NeoForge 1.20.1 入口。
 *
 * <p>NeoForge 1.20.1（47.1.x）是 Forge 47.x 的 fork，包名仍然是
 * {@code net.minecraftforge.*}，注册的 mod id 也是 {@code forge}，所以入口写法和
 * Forge 版一致，common 代码完全共用。真正改包名（net.neoforged.*）从 1.20.2 才开始。</p>
 */
@Mod(IntroVideo.MOD_ID)
public final class IntroVideoNeoForge {
    public IntroVideoNeoForge() {
        IntroVideo.boot("NeoForge 1.20.1 (compat build) " + ModList.get().getModContainerById("forge")
                .map(c -> c.getModInfo().getVersion().toString()).orElse("47.1.x"));
        if (FMLEnvironment.dist == Dist.CLIENT) {
            IntroVideo.disableForgeEarlyWindow(FMLPaths.CONFIGDIR.get());
            IntroVideoClient.get().preInit(FMLPaths.GAMEDIR.get());
        }
    }
}
