package com.syuto.introvideo.client;

import com.syuto.introvideo.IntroVideo;
import java.io.BufferedInputStream;
import java.io.InputStream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * 开场视频的声音。
 *
 * <p>视频本身没有音频轨（JCodec 只解码视频），音频来自原视频，按“视频循环段”长度裁剪
 * （{@code startAt} 到结尾），循环播放，和视频循环周期一致。</p>
 *
 * <p>为了三端（Forge / NeoForge / Fabric）都一定有声音，播放支持两种后端：</p>
 * <ol>
 *   <li><b>Minecraft 声音系统</b>：{@code assets/introvideo/sounds.json} + OGG，
 *       走游戏音量/音频设备。前提是资源包能扫到我们的 sounds.json（正式 jar 一般都可以）。</li>
 *   <li><b>Java Sound 兜底</b>：如果找不到上面的声音事件（例如某些开发环境/加载器资源差异），
 *       就直接用 {@link javax.sound.sampled.Clip} 播放内置 WAV，完全不依赖资源包。</li>
 * </ol>
 */
public final class IntroVideoAudio {

    private static final ResourceLocation SOUND_ID = new ResourceLocation(IntroVideo.MOD_ID, "intro");
    private static final String WAV_RESOURCE = "/assets/" + com.syuto.introvideo.IntroVideo.MOD_ID + "/sounds/intro.wav";

    private SoundInstance instance;
    private Clip clip;
    private Thread javaSoundThread;
    private boolean stopped = true;
    private String backend = "none";

    /** 开始播放（循环）。 */
    public String start(float volume) {
        return start(volume, "auto");
    }

    /**
     * 开始播放（循环）。
     *
     * @param prefer {@code auto}（默认，优先 MC 声音系统）/ {@code minecraft}（只用 MC）/ {@code javasound}（强制 Java Sound）
     */
    public String start(float volume, String prefer) {
        stop();
        stopped = false;
        String mode = prefer == null ? "auto" : prefer.trim().toLowerCase(java.util.Locale.ROOT);
        if ("javasound".equals(mode)) {
            startJavaSound(volume, 0.0, "javasound");
            return backend;
        }
        Minecraft minecraft = Minecraft.getInstance();
        boolean mcSoundAvailable = false;
        try {
            mcSoundAvailable = minecraft != null && minecraft.getSoundManager().getSoundEvent(SOUND_ID) != null;
        } catch (Throwable ignored) {
            mcSoundAvailable = false;
        }
        if (mcSoundAvailable && minecraft != null) {
            try {
                instance = new SimpleSoundInstance(SOUND_ID, SoundSource.MASTER,
                        Math.max(0.0F, Math.min(1.0F, volume)), 1.0F, RandomSource.create(),
                        true, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true);
                minecraft.getSoundManager().play(instance);
                backend = "minecraft";
                IntroVideo.LOGGER.info("[IntroVideo] 开场音频使用 Minecraft 声音系统（循环，音量 {}）", volume);
                return backend;
            } catch (Throwable t) {
                IntroVideo.LOGGER.warn("[IntroVideo] Minecraft 声音系统播放失败，改用 Java Sound", t);
                instance = null;
            }
        }
        if ("minecraft".equals(mode)) {
            IntroVideo.LOGGER.warn("[IntroVideo] Minecraft 声音系统不可用（找不到声音事件），已回退到 Java Sound");
        }
        startJavaSound(volume, 0.0, "javasound");
        return backend;
    }

    /**
     * 循环重播阶段：从音频的第 {@code skipSeconds} 秒开始循环播放（跳过片头那段的音乐）。
     * 直接用 Java Sound 从内置 WAV 里跳过对应帧，不需要额外音频文件。
     */
    public void startLoopPart(float volume, double skipSeconds) {
        startLoopPart(volume, skipSeconds, 0.0);
    }

    /**
     * 循环段：从音频 skipSeconds 开始、到 endSeconds 结束（0 = 到结尾），循环播放。
     * 用来和视频的「跳过片头 + 跳过结尾黑场」保持一致。
     */
    public void startLoopPart(float volume, double skipSeconds, double endSeconds) {
        stop();
        stopped = false;
        startJavaSound(volume, Math.max(0.0, skipSeconds), Math.max(0.0, endSeconds), "javasound-loop");
    }

    /** Java Sound 兜底：直接播放内置 WAV（不依赖资源包），可从指定秒数开始。 */
    private void startJavaSound(float volume, double skipSeconds, String backendName) {
        startJavaSound(volume, skipSeconds, 0.0, backendName);
    }

    private void startJavaSound(float volume, double skipSeconds, double endSeconds, String backendName) {
        backend = backendName;
        final float vol = Math.max(0.0F, Math.min(1.0F, volume));
        final double skip = Math.max(0.0, skipSeconds);
        final double endAt = Math.max(0.0, endSeconds);
        javaSoundThread = new Thread(() -> {
            try (InputStream raw = IntroVideoAudio.class.getResourceAsStream(WAV_RESOURCE)) {
                if (raw == null) {
                    IntroVideo.LOGGER.warn("[IntroVideo] 找不到内置 WAV 音频 {}", WAV_RESOURCE);
                    return;
                }
                try (AudioInputStream stream = AudioSystem.getAudioInputStream(new BufferedInputStream(raw))) {
                    if (skip > 0.0) {
                        // 注意：AudioInputStream.skip 的单位是“字节”，不是帧。
                        // 之前少乘 frameSize，导致循环段只跳了一半 (2.5s)，音画不同步。
                        long frameSize = Math.max(1, stream.getFormat().getFrameSize());
                        long bytesToSkip = Math.round(skip * stream.getFormat().getFrameRate() * frameSize);
                        long skipped = 0L;
                        while (skipped < bytesToSkip) {
                            long n = stream.skip(bytesToSkip - skipped);
                            if (n <= 0L) {
                                break;
                            }
                            skipped += n;
                        }
                        IntroVideo.LOGGER.info("[IntroVideo] 循环段音频跳过 {} 字节（约 {} 秒）", skipped, skip);
                    }
                    Clip newClip = AudioSystem.getClip();
                    if (endAt > skip) {
                        long frameSize = Math.max(1, stream.getFormat().getFrameSize());
                        long bytes = Math.round((endAt - skip) * stream.getFormat().getFrameRate() * frameSize);
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) Math.min(bytes, 8_000_000L));
                        byte[] buf = new byte[8192];
                        long remaining = bytes;
                        int n;
                        while (remaining > 0 && (n = stream.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
                            out.write(buf, 0, n);
                            remaining -= n;
                        }
                        byte[] data = out.toByteArray();
                        long frames = data.length / frameSize;
                        AudioInputStream trimmed = new AudioInputStream(
                                new java.io.ByteArrayInputStream(data), stream.getFormat(), frames);
                        newClip.open(trimmed);
                        IntroVideo.LOGGER.info("[IntroVideo] 循环段音频裁剪为 {}s ~ {}s（{} 帧）", skip, endAt, frames);
                    } else {
                        newClip.open(stream);
                    }
                    if (newClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                        FloatControl gain = (FloatControl) newClip.getControl(FloatControl.Type.MASTER_GAIN);
                        float db = vol <= 0.0001F ? gain.getMinimum() : (float) (20.0 * Math.log10(vol));
                        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
                    }
                    synchronized (this) {
                        if (stopped) {
                            newClip.close();
                            return;
                        }
                        clip = newClip;
                    }
                    newClip.loop(Clip.LOOP_CONTINUOUSLY);
                    IntroVideo.LOGGER.info("[IntroVideo] 开场音频使用 Java Sound 播放（{}，循环，音量 {}）",
                            skip > 0.0 ? "循环段从 " + skip + "s 开始" : "完整", vol);
                }
            } catch (Throwable t) {
                IntroVideo.LOGGER.warn("[IntroVideo] Java Sound 播放开场音频失败", t);
            }
        }, "IntroVideo-Audio");
        javaSoundThread.setDaemon(true);
        javaSoundThread.start();
    }

    /** 停止并释放。 */
    public void stop() {
        stopped = true;
        backend = "none";
        SoundInstance current = instance;
        instance = null;
        if (current != null) {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft != null) {
                    minecraft.getSoundManager().stop(current);
                }
            } catch (Throwable ignored) {
                // 声音系统关闭阶段可能抛错
            }
        }
        synchronized (this) {
            Clip currentClip = clip;
            clip = null;
            if (currentClip != null) {
                try {
                    currentClip.stop();
                } catch (Throwable ignored) {
                }
                try {
                    currentClip.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public boolean isPlaying() {
        SoundInstance current = instance;
        if (current != null) {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft != null && minecraft.getSoundManager().isActive(current)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // fall through to java sound
            }
        }
        synchronized (this) {
            if (clip != null) {
                try {
                    return clip.isRunning();
                } catch (Throwable ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    public String backend() {
        return backend;
    }
}
