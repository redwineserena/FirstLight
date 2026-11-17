package com.syuto.introvideo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组公共常量与加载器无关的引导入口。
 *
 * <p>真正的逻辑全在客户端类与 Mixin 里；Forge / NeoForge / Fabric 的入口类只是调用
 * {@link #boot(String)} 打一行日志。</p>
 */
public final class IntroVideo {
    public static final String MOD_ID = "firstlight";
    public static final Logger LOGGER = LoggerFactory.getLogger("IntroVideo");

    private static boolean booted;
    private static boolean earlyWindowPatched;

    private IntroVideo() {
    }

    /** Forge / NeoForge / Fabric 入口调用；重复调用安全。 */
    public static void boot(String loader) {
        if (booted) {
            return;
        }
        booted = true;
        LOGGER.info("[IntroVideo] booting on {} (Minecraft 1.20.1)", loader);
    }

    /**
     * 关闭 Forge/NeoForge 的“早期启动窗口”（那个独立的小窗口里显示 Forge logo + 进度条的动画）。
     *
     * <p>官方没有“关闭加载界面”的开关，只有 {@code config/fml.toml} 里的
     * {@code earlyWindowControl}。它在 FML 启动的最早期读取，模组加载时已经太晚，
     * 所以这里只改配置文件，**下一次启动**开始生效（之后 Forge 不再创建早期 splash 窗口，
     * 主窗口打开后由本模组的视频覆盖原版加载界面）。</p>
     *
     * @param configDir 游戏 config 目录（Forge/NeoForge 用 {@code FMLPaths.CONFIGDIR}）
     */
    public static void disableForgeEarlyWindow(Path configDir) {
        if (earlyWindowPatched || configDir == null) {
            return;
        }
        earlyWindowPatched = true;
        Path fmlToml = configDir.resolve("fml.toml");
        try {
            List<String> lines = Files.isRegularFile(fmlToml)
                    ? new ArrayList<>(Files.readAllLines(fmlToml, StandardCharsets.UTF_8))
                    : new ArrayList<>();
            boolean found = false;
            boolean alreadyOff = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("earlyWindowControl") && trimmed.contains("=")) {
                    alreadyOff = trimmed.replace(" ", "").equals("earlyWindowControl=false");
                    if (!alreadyOff) {
                        lines.set(i, "earlyWindowControl = false");
                    }
                    found = true;
                }
            }
            if (!found) {
                lines.add("earlyWindowControl = false");
            }
            if (!alreadyOff || !found) {
                Files.createDirectories(configDir);
                Files.write(fmlToml, lines, StandardCharsets.UTF_8);
            }
            LOGGER.info("[IntroVideo] fml.toml earlyWindowControl=false：{}",
                    alreadyOff ? "本次启动已生效" : "已写入，下次启动生效（之后不再显示 Forge 敲铁砧动画）");
        } catch (Exception e) {
            LOGGER.warn("[IntroVideo] 修改 fml.toml 失败（不影响视频功能）", e);
        }
    }
}
