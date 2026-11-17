package com.syuto.introvideo.client;

import com.syuto.introvideo.IntroVideo;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 找到要播放的视频文件。查找顺序：
 *
 * <ol>
 *   <li>配置项 {@code video}（绝对路径 / 相对 .minecraft / 相对 config 目录）；</li>
 *   <li>{@code config/intro_video/} 目录里的 {@code intro.mp4} 或第一个 mp4 文件；</li>
 *   <li>{@code .minecraft/intro.mp4}、{@code config/intro_video.mp4}；</li>
 *   <li>模组 jar 内置资源 {@code assets/introvideo/intro.mp4}（会被释放到 {@code config/intro_video/intro.mp4}）。</li>
 * </ol>
 */
public final class VideoLocator {

    private static final String[] EXTENSIONS = {"mp4", "m4v", "mov"};
    private static final String BUNDLED_RESOURCE = "assets/" + IntroVideo.MOD_ID + "/intro.mp4";

    private VideoLocator() {
    }

    /**
     * @return 可播放的视频文件；找不到返回 {@code null}
     */
    public static File locate(IntroConfig config, File gameDir) {
        File configDir = new File(gameDir, "config");
        File videoDir = new File(configDir, "intro_video");

        File explicit = resolveExplicit(config, gameDir, configDir);
        if (explicit != null) {
            return explicit;
        }
        if (config.video != null && !config.video.trim().isEmpty()) {
            IntroVideo.LOGGER.warn("[IntroVideo] 配置里的视频不存在: {}", config.video);
        }

        // 用户放到 config/intro_video/ 的视频
        File fromDir = findInDirectory(videoDir);
        if (fromDir != null) {
            return fromDir;
        }

        for (String name : new String[] {"intro.mp4", "intro.m4v", "intro.mov", "intro_video.mp4"}) {
            File candidate = new File(gameDir, name);
            if (candidate.isFile()) {
                return candidate;
            }
            candidate = new File(configDir, name);
            if (candidate.isFile()) {
                return candidate;
            }
        }

        // 内置于模组 jar 的视频
        return extractBundled(videoDir);
    }

    private static File resolveExplicit(IntroConfig config, File gameDir, File configDir) {
        if (config.video == null) {
            return null;
        }
        String raw = config.video.trim();
        if (raw.isEmpty()) {
            return null;
        }
        List<File> candidates = new ArrayList<>();
        File direct = new File(raw);
        if (direct.isAbsolute()) {
            candidates.add(direct);
        }
        candidates.add(new File(gameDir, raw));
        candidates.add(new File(configDir, raw));
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    private static File findInDirectory(File videoDir) {
        if (!videoDir.isDirectory()) {
            return null;
        }
        for (String ext : EXTENSIONS) {
            File exact = new File(videoDir, "intro." + ext);
            if (exact.isFile()) {
                return exact;
            }
        }
        File[] files = videoDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return Arrays.stream(EXTENSIONS).anyMatch(lower::endsWith);
        });
        if (files != null && files.length > 0) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            return files[0];
        }
        return null;
    }

    private static File extractBundled(File videoDir) {
        // 两种方式都试一下：jar 内置资源在不同加载器/开发环境下的可见方式略有差异
        InputStream stream = VideoLocator.class.getResourceAsStream("/" + BUNDLED_RESOURCE);
        if (stream == null) {
            stream = VideoLocator.class.getClassLoader().getResourceAsStream(BUNDLED_RESOURCE);
        }
        if (stream == null) {
            return null;
        }
        File target = new File(videoDir, "intro.mp4");
        try (InputStream in = stream) {
            if (target.isFile() && target.length() > 0) {
                return target;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                IntroVideo.LOGGER.warn("[IntroVideo] 无法创建目录 {}", parent);
                return null;
            }
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            IntroVideo.LOGGER.info("[IntroVideo] 已从模组 jar 释放内置视频到 {}", target);
            return target;
        } catch (IOException e) {
            IntroVideo.LOGGER.warn("[IntroVideo] 释放内置视频失败", e);
            return null;
        }
    }
}
