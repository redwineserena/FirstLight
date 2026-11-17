package com.syuto.introvideo.video;

import com.syuto.introvideo.IntroVideo;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import org.jcodec.common.model.Picture;

/**
 * 视频播放器：一个后台解码线程 + 一个“已解码待显示”的帧队列。
 *
 * <p>线程模型：</p>
 * <ul>
 *   <li>解码线程：顺序解码 → 转换 RGBA → 按 pts 重排（处理 B 帧）→ 塞进阻塞队列（满则等待）；</li>
 *   <li>渲染线程：每帧调用 {@link #advance(long)}，按系统时钟取当前应显示的帧。</li>
 * </ul>
 *
 * <p>本类不依赖任何 Minecraft 类，方便单独测试。</p>
 */
public final class VideoPlayer implements AutoCloseable {

    /** 已解码待显示的帧数（够平滑即可，太大浪费显存外的堆内存）。 */
    private static final int QUEUE_CAPACITY = 4;
    /** B 帧重排窗口：常见 GOP 的重排深度是 2~3。 */
    private static final int REORDER_WINDOW = 3;

    public static final class Frame {
        public final long ptsNanos;
        public final long frameNo;
        public final ByteBuffer rgba;

        Frame(long ptsNanos, long frameNo, ByteBuffer rgba) {
            this.ptsNanos = ptsNanos;
            this.frameNo = frameNo;
            this.rgba = rgba;
        }
    }

    private final File file;
    private final boolean debug;
    private final double fpsOverride;
    private final LinkedBlockingDeque<Frame> ready = new LinkedBlockingDeque<>(QUEUE_CAPACITY);
    private final ConcurrentLinkedQueue<ByteBuffer> freeBuffers = new ConcurrentLinkedQueue<>();
    private final Thread decoderThread;

    private volatile boolean running = true;
    private volatile boolean looping;
    private volatile boolean ended;
    private volatile Throwable failure;

    private volatile int width = -1;
    private volatile int height = -1;
    private volatile long durationNanos;
    private volatile double fps;
    private volatile int frameCount;
    private volatile String fourcc = "?";

    // 仅渲染线程访问

    private long shownFirstPassNanos = -1L;
    private long droppedFrames;
    /** 每一遍的墙钟起点与该遍起点 pts（保证每遍 1x 实时速度，不受跨遍时间轴累积误差影响）。 */
    private volatile long anchorWallNs;
    private long lastShownPts;

    private long lastDropLogMs;
    private Frame current;
    private Frame next;

    private final Yuv420Rgba.Matrix colorMatrix;
    /** 第一遍从视频第几纳秒开始播放。 */
    private final long startAtNanos;
    /** 第二遍及以后循环从第几纳秒开始（用来跳过片头）。 */
    private final long loopStartNanos;
    /** 播放结束点（纳秒）；0 表示到视频结尾。 */
    private final long endAtNanos;
    /** 当前播放到第几遍（0 = 第一遍，1 = 第二次及之后）。 */
    private volatile int passIndex;
    /** 当前这一遍实际从视频第几纳秒开始（循环时是关键帧的时间）。 */
    private volatile long currentPassStartNanos;

    public VideoPlayer(File file, boolean looping, boolean debug, double fpsOverride,
                       Yuv420Rgba.Matrix colorMatrix, long startAtNanos) {
        this(file, looping, debug, fpsOverride, colorMatrix, startAtNanos, startAtNanos, 0L);
    }

    public VideoPlayer(File file, boolean looping, boolean debug, double fpsOverride,
                       Yuv420Rgba.Matrix colorMatrix, long startAtNanos, long loopStartNanos) {
        this(file, looping, debug, fpsOverride, colorMatrix, startAtNanos, loopStartNanos, 0L);
    }

    public VideoPlayer(File file, boolean looping, boolean debug, double fpsOverride,
                       Yuv420Rgba.Matrix colorMatrix, long startAtNanos, long loopStartNanos, long endAtNanos) {
        this.file = file;
        this.looping = looping;
        this.debug = debug;
        this.fpsOverride = fpsOverride;
        this.colorMatrix = colorMatrix == null ? Yuv420Rgba.Matrix.BT709 : colorMatrix;
        this.startAtNanos = Math.max(0L, startAtNanos);
        this.loopStartNanos = Math.max(0L, loopStartNanos);
        this.endAtNanos = Math.max(0L, endAtNanos);
        this.decoderThread = new Thread(this::decodeLoop, "IntroVideo-Decoder");
        this.decoderThread.setDaemon(true);
    }

    /** 当前是第几遍（0 = 第一遍，>=1 表示已经在循环重播）。 */
    public int passIndex() {
        return passIndex;
    }

    /** 当前这一遍实际从视频第几纳秒开始（纳秒）。 */
    public long currentPassStartNanos() {
        return currentPassStartNanos;
    }

    public void start() {
        decoderThread.start();
    }

    public void setLooping(boolean looping) {
        this.looping = looping;
    }

    // ------------------------------------------------------------------ 解码线程

    private void decodeLoop() {
        if (debug) {
            System.err.println("[IntroVideo] 开始解码: " + file);
        }
        try (Mp4H264Source source = new Mp4H264Source(file)) {
            this.durationNanos = source.durationNanos();
            this.fps = source.fps();
            this.frameCount = source.frameCount();
            this.fourcc = source.fourcc();
            if (fpsOverride > 0.5) {
                this.fps = fpsOverride;
                if (frameCount > 0) {
                    this.durationNanos = (long) (frameCount * 1_000_000_000.0 / fpsOverride);
                }
            }
            if (durationNanos <= 0 && frameCount > 0 && fps > 0.5) {
                this.durationNanos = (long) (frameCount * 1_000_000_000.0 / fps);
            }
            if (debug) {
                System.err.println("[IntroVideo] 视频信息: " + source.describeTrack()
                        + " 实际使用 durationMs=" + durationNanos / 1_000_000L + " fps=" + fps);
            }

            // 第一遍从 startAt 开始（完整播放），第二遍及以后从 loopStart 开始（跳过片头）
            final long firstStart = Math.min(startAtNanos, Math.max(0L, durationNanos - 1_000_000L));
            final long loopStart = Math.min(loopStartNanos, Math.max(0L, durationNanos - 1_000_000L));
            final long resolvedEnd = (endAtNanos > 0L && endAtNanos < durationNanos) ? endAtNanos : durationNanos;

            // 循环遍的起点：>= loopStart 的最近关键帧（从关键帧直接解码，第一帧立刻可用）
            long loopSyncStart = loopStart;
            {
                long next = source.nextSyncStartNanos(loopStart);
                if (next > 0L && next < durationNanos) {
                    loopSyncStart = next;
                }
            }

            long passOffset = 0L;
            int pass = 0;
            while (running) {
                final long displayStart = pass == 0 ? firstStart : loopSyncStart;
                final long seekFrom = pass == 0 ? firstStart : loopSyncStart;
                final long effectiveStart = displayStart;
                currentPassStartNanos = displayStart;
                final long passEndNanos = pass == 0 ? durationNanos : resolvedEnd;
                final long offset = passOffset;
                passIndex = pass;
                final int passNo = pass;
                if (seekFrom > 0L && fps > 0.5) {
                    try {
                        source.seekToFrame((int) Math.round(seekFrom / 1_000_000_000.0 * fps));
                    } catch (Throwable t) {
                        IntroVideo.LOGGER.warn("[IntroVideo] 循环定位失败，回退到文件开头: {}", t.toString());
                        source.seekToStart();
                    }
                } else if (pass > 0) {
                    source.seekToStart();
                }
                if (passNo > 0) {
                    IntroVideo.LOGGER.info("[IntroVideo] 视频进入第 {} 遍，从 {}ms 开始",
                            passNo + 1, effectiveStart / 1_000_000L);
                }
                final boolean[] passFirstFrame = {false};
                final List<Frame> reorder = new ArrayList<>(REORDER_WINDOW + 1);
                source.decode((Picture picture, long ptsNanos, long frameNo) -> {
                    if (!running) {
                        return false;
                    }
                    if (ptsNanos < effectiveStart) {
                        return true;
                    }
                    if (ptsNanos >= passEndNanos) {
                        return false;
                    }
                    if (width <= 0) {
                        width = picture.getCroppedWidth();
                        height = picture.getCroppedHeight();
                    }
                    int need = picture.getCroppedWidth() * picture.getCroppedHeight() * 4;
                    ByteBuffer buffer = acquireBuffer(need);
                    Yuv420Rgba.convert(picture, buffer, colorMatrix);
                    reorder.add(new Frame(ptsNanos, frameNo, buffer));
                    if (passNo > 0 && !passFirstFrame[0]) {
                        passFirstFrame[0] = true;
                        IntroVideo.LOGGER.info("[IntroVideo] 第 {} 遍首帧就绪，pts={}ms",
                                passNo + 1, ptsNanos / 1_000_000L);
                    }
                    // 保持一个小窗口，按显示时间顺序输出（B 帧是乱序到达的）
                    while (reorder.size() > REORDER_WINDOW) {
                        emitOldest(reorder);
                    }
                    return running;
                });
                // 把这一遍剩下的帧按 pts 顺序输出
                reorder.sort(Comparator.comparingLong(frame -> frame.ptsNanos));
                for (Frame frame : reorder) {
                    putFrame(frame);
                }
                reorder.clear();

                if (!running || !looping) {
                    break;
                }
                // 循环：下一遍开始时会先 seek 到 loopStart 之前的关键帧（见 while 开头），
                // 这里只负责把“显示时间轴”接上，避免循环时重复从文件头解码造成长时间黑屏。
                // 时间轴接续的关键：下一遍从 loopSyncStart 开始，所以这一遍只前进 (结束点 - 下一遍起点)，\n                // 否则每一遍都会多算出 loopStart 秒的“空黑”时间。\n                passOffset += Math.max(1L, passEndNanos - loopSyncStart);
                pass++;
            }
        } catch (Throwable t) {
            if (running) {
                failure = t;
                System.err.println("[IntroVideo] 视频解码失败: " + t);
                if (debug) {
                    t.printStackTrace();
                }
            }
        } finally {
            ended = true;
        }
    }

    private void emitOldest(List<Frame> reorder) throws InterruptedException {
        int best = 0;
        for (int i = 1; i < reorder.size(); i++) {
            if (reorder.get(i).ptsNanos < reorder.get(best).ptsNanos) {
                best = i;
            }
        }
        putFrame(reorder.remove(best));
    }

    private ByteBuffer acquireBuffer(int needed) {
        ByteBuffer buffer;
        while ((buffer = freeBuffers.poll()) != null) {
            if (buffer.capacity() >= needed) {
                buffer.clear();
                return buffer;
            }
        }
        return ByteBuffer.allocateDirect(needed);
    }

    private void putFrame(Frame frame) throws InterruptedException {
        ready.put(frame);
    }

    /**
     * 预览最早解码好的一帧但不推进播放（用于加载阶段显示“视频首帧”作为静止底图）。
     * 返回 null 表示还没有解码好的帧。
     */
    public Frame previewFrame() {
        return ready.peek();
    }

    // ------------------------------------------------------------------ 渲染线程

    /**
     * 根据系统时钟推进播放；如果切换到了新的一帧则返回它（调用方负责上传到纹理），
     * 否则返回 {@code null}。
     */
    public Frame advance(long nowNs) {
        Frame changed = null;
        while (true) {
            if (next == null) {
                next = ready.poll();
            }
            if (next == null) {
                break;
            }
            long pts = next.ptsNanos;
            if (anchorWallNs == 0L) {
                anchorWallNs = nowNs - pts;
                lastShownPts = pts;
            } else if (pts + 1_000_000_000L < lastShownPts) {
                // 检测到循环回跳（时间戳变回片头）→ 重建墙钟锚点，立刻从新位置 1x 播放
                anchorWallNs = nowNs - pts;
                lastShownPts = pts;
            }
            long elapsed = nowNs - anchorWallNs;
            if (pts <= elapsed) {
                Frame picked = next;
                next = null;
                releaseBuffer(current);
                current = picked;
                lastShownPts = Math.max(lastShownPts, picked.ptsNanos);
                if (durationNanos > 0 && picked.ptsNanos < durationNanos) {
                    shownFirstPassNanos = Math.max(shownFirstPassNanos, picked.ptsNanos);
                }
                changed = picked;
            } else {
                break;
            }
        }
        return changed;
    }

    private void releaseBuffer(Frame frame) {
        if (frame != null) {
            freeBuffers.add(frame.rgba);
        }
    }

    public boolean hasFrame() {
        return current != null;
    }

    public Frame currentFrame() {
        return current;
    }

    /** 第一遍（不含循环）是否已经播放完。 */
    public boolean isFirstPassDone() {
        if (durationNanos <= 0 || shownFirstPassNanos < 0) {
            return false;
        }
        double frameInterval = fps > 0.5 ? 1_000_000_000.0 / fps : 100_000_000.0;
        return shownFirstPassNanos >= durationNanos - frameInterval - 1_000_000L;
    }

    public double progress() {
        if (anchorWallNs == 0L || durationNanos <= 0) {
            return 0.0;
        }
        double value = (System.nanoTime() - anchorWallNs) / (double) durationNanos;
        if (looping) {
            value -= Math.floor(value);
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    public boolean isReady() {
        return failure == null && width > 0 && height > 0 && durationNanos > 0;
    }

    public boolean isFailed() {
        return failure != null;
    }

    public Throwable failure() {
        return failure;
    }

    public boolean isEnded() {
        return ended;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public long durationNanos() {
        return durationNanos;
    }

    public double fps() {
        return fps;
    }

    public int frameCount() {
        return frameCount;
    }

    public String fourcc() {
        return fourcc;
    }

    public File file() {
        return file;
    }

    @Override
    public void close() {
        running = false;
        decoderThread.interrupt();
        Frame frame;
        while ((frame = ready.poll()) != null) {
            releaseBuffer(frame);
        }
        releaseBuffer(next);
        releaseBuffer(current);
        next = null;
        current = null;
        freeBuffers.clear();
        try {
            decoderThread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
