package com.syuto.introvideo.fabric;

import com.syuto.introvideo.IntroVideo;
import com.syuto.introvideo.client.IntroVideoClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric 入口。真正的逻辑全在 common 的 Mixin / 客户端类里，这里只负责打一行版本日志，
 * 并提前开始视频解码（这样 Minecraft 第一次渲染时就有画面可画，不会露出原版加载动画）。
 */
public final class IntroVideoFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        IntroVideo.boot("Fabric");
        IntroVideoClient.get().preInit(FabricLoader.getInstance().getGameDir());
    }
}
