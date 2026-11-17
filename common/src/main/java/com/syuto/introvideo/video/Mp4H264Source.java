package com.syuto.introvideo.video;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jcodec.codecs.h264.H264Decoder;
import org.jcodec.common.Codec;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.SeekableDemuxerTrack;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Size;
import org.jcodec.containers.mp4.MP4Packet;
import org.jcodec.containers.mp4.boxes.NodeBox;
import org.jcodec.containers.mp4.boxes.SyncSamplesBox;
import org.jcodec.containers.mp4.boxes.TimeToSampleBox;
import org.jcodec.containers.mp4.boxes.TrakBox;
import org.jcodec.containers.mp4.demuxer.AbstractMP4DemuxerTrack;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;

/**
 * 纯 Java 的 MP4 / H.264 视频源（基于 JCodec 0.2.5，不使用 AWT）。
 *
 * <p>支持的格式：MP4 容器 + H.264 (avc1) 视频轨，8bit 4:2:0。音频轨会被忽略。
 * 解码是同步的，调用 {@link #decode(FrameSink)} 会一直解码到文件结尾。</p>
 *
 * <p>线程安全性：本类不是线程安全的，一个实例只能在同一个线程里使用。
 * 换向（循环播放）通过 {@link #seekToStart()} 完成，不会重新创建解码器（JCodec 的
 * H264Decoder 内部线程池没有关闭接口，反复创建会泄漏线程）。</p>
 */
public final class Mp4H264Source implements AutoCloseable {

    /** 解码回调；{@code picture} 只在回调期间有效（它指向解码器复用的缓冲区）。 */
    public interface FrameSink {
        /**
         * @param picture 解码后的画面（YUV420，数据偏移了 -128）
         * @param ptsNanos 显示时间戳（纳秒，基于轨道时基换算）
         * @param frameNo 解码顺序的帧号（不是显示顺序）
         * @return false 表示要求中止解码
         */
        boolean onDecodedFrame(Picture picture, long ptsNanos, long frameNo) throws IOException, InterruptedException;
    }

    private final FileChannelWrapper channel;
    private final MP4Demuxer demuxer;
    private final SeekableDemuxerTrack track;
    private final H264Decoder decoder = new H264Decoder();

    private final String fourcc;
    private final double durationSeconds;
    private final int frameCount;
    private final double fps;
    private final int codedWidth;
    private final int codedHeight;
    private final long trackTimescale;
    private final int[] syncSamples;

    private byte[][] pictureBuffer;
    private int bufferWidth;
    private int bufferHeight;

    private int width = -1;
    private int height = -1;
    private boolean loggedReject;

    public Mp4H264Source(File file) throws IOException {
        this.channel = NIOUtils.readableChannel(file);
        try {
            this.demuxer = MP4Demuxer.createMP4Demuxer(channel);
            DemuxerTrack videoTrack = demuxer.getVideoTrack();
            if (videoTrack == null) {
                throw new IOException("MP4 中没有视频轨（只有音频？）: " + file);
            }
            if (!(videoTrack instanceof SeekableDemuxerTrack)) {
                throw new IOException("无法按帧定位的视频轨: " + file);
            }
            this.track = (SeekableDemuxerTrack) videoTrack;

            TrakBox trak = demuxer.getMovie().getVideoTrack();
            this.fourcc = trak.getSampleEntries().length > 0
                    ? trak.getSampleEntries()[0].getHeader().getFourcc()
                    : "?";
            this.trackTimescale = Math.max(1, trak.getTimescale());

            DemuxerTrackMeta meta = track.getMeta();
            if (meta == null || meta.getCodec() != Codec.H264) {
                String codec = meta == null || meta.getCodec() == null ? "unknown" : meta.getCodec().toString();
                throw new IOException("只支持 H.264 编码的视频，当前: fourcc=" + fourcc + " codec=" + codec
                        + "（请用 ffmpeg 转成 H.264，例如: ffmpeg -i in.mp4 -c:v libx264 -profile:v main -bf 0 -pix_fmt yuv420p out.mp4）");
            }
            this.durationSeconds = meta.getTotalDuration();
            this.frameCount = meta.getTotalFrames();

            Size coded = trak.getCodedSize();
            this.codedWidth = coded.getWidth();
            this.codedHeight = coded.getHeight();
            ensureBuffer(codedWidth + 16, codedHeight + 16);
            this.fps = computeFps(trak);
            SyncSamplesBox stss = NodeBox.findFirstPath(trak, SyncSamplesBox.class,
                    new String[] {"mdia", "minf", "stbl", "stss"});
            this.syncSamples = stss == null ? null : stss.getSyncSamples();
        } catch (IOException | RuntimeException e) {
            NIOUtils.closeQuietly(channel);
            throw e;
        }
    }

    private double computeFps(TrakBox trak) {
        try {
            int ts = trak.getTimescale();
            TimeToSampleBox stts = NodeBox.findFirstPath(trak, TimeToSampleBox.class,
                    new String[] {"mdia", "minf", "stbl", "stts"});
            if (stts != null && ts > 0) {
                TimeToSampleBox.TimeToSampleEntry[] entries = stts.getEntries();
                if (entries.length > 0 && entries[0].getSampleDuration() > 0) {
                    return ts / (double) entries[0].getSampleDuration();
                }
            }
        } catch (RuntimeException ignored) {
            // 兜底用总时长/总帧数
        }
        if (durationSeconds > 0 && frameCount > 0) {
            return frameCount / durationSeconds;
        }
        return 0.0;
    }

    private void ensureBuffer(int needWidth, int needHeight) {
        if (pictureBuffer != null && needWidth <= bufferWidth && needHeight <= bufferHeight) {
            return;
        }
        int w = Math.max(16, ((needWidth + 15) / 16) * 16);
        int h = Math.max(16, ((needHeight + 15) / 16) * 16);
        // YUV444 给三个平面各 w*h 字节，任何 YUV420 布局都放得下（JCodec 官方 FrameGrab 也是这么干的）。
        this.pictureBuffer = Picture.create(w, h, ColorSpace.YUV444).getData();
        this.bufferWidth = w;
        this.bufferHeight = h;
    }

    /**
     * 从当前位置解码到文件结尾。帧按解码顺序回调（B 帧需要调用方按 pts 重排）。
     *
     * @return 实际回调的帧数
     */
    public long decode(FrameSink sink) throws IOException {
        long count = 0;
        Packet packet;
        while ((packet = track.nextFrame()) != null) {
            Picture picture;
            try {
                picture = decoder.decodeFrame(packet.getData(), pictureBuffer);
            } catch (RuntimeException e) {
                // JCodec 对不支持的码流（10bit / 4:2:2 等）会直接抛异常；跳过该帧继续，
                // 但只打一次日志，避免刷屏。
                if (!loggedReject) {
                    loggedReject = true;
                    System.err.println("[IntroVideo] JCodec 拒绝了一帧（可能是不支持的 H.264 profile）: " + e);
                }
                continue;
            }
            if (picture == null) {
                continue;
            }
            ensureBuffer(picture.getWidth() + 16, picture.getHeight() + 16);
            if (width <= 0 || width != picture.getCroppedWidth()) {
                width = picture.getCroppedWidth();
                height = picture.getCroppedHeight();
            }

            long mediaPts = packet instanceof MP4Packet
                    ? ((MP4Packet) packet).getMediaPts()
                    : packet.pts;
            long ptsNanos = mediaPts * 1_000_000_000L / Math.max(1, packet.timescale);

            count++;
            boolean goOn;
            try {
                goOn = sink.onDecodedFrame(picture, ptsNanos, packet.frameNo);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return count;
            }
            if (!goOn) {
                return count;
            }
        }
        return count;
    }

    /**
     * 找到 <= requestedNanos 的最近关键帧，返回它的显示时间戳（纳秒）。
     * 用于循环重播：从关键帧直接开始解码，不用“解码再丢弃”几千帧，避免循环时长时间黑屏。
     * 没有 stss 信息时退回 requestedNanos。
     */
    public long nearestSyncStartNanos(long requestedNanos) {
        if (syncSamples == null || syncSamples.length == 0 || fps <= 0.5) {
            return requestedNanos;
        }
        long requestedFrame = Math.round(requestedNanos / 1_000_000_000.0 * fps);
        int best = -1;
        for (int sample : syncSamples) {
            int frameIndex = sample - 1; // stss 是 1-based
            if (frameIndex <= requestedFrame && frameIndex > best) {
                best = frameIndex;
            }
        }
        if (best < 0) {
            return 0L;
        }
        return Math.round(best / fps * 1_000_000_000.0);
    }

    /**
     * 找到 >= requestedNanos 的最近关键帧，返回它的显示时间戳（纳秒）。
     * 循环重播用它做起点：直接从关键帧解码，不需要“解码再丢弃”任何帧，第一帧立刻可用。
     * 没有 stss / 没有更后面的关键帧时返回 requestedNanos。
     */
    public long nextSyncStartNanos(long requestedNanos) {
        if (syncSamples == null || syncSamples.length == 0 || fps <= 0.5) {
            return requestedNanos;
        }
        long requestedFrame = Math.round(requestedNanos / 1_000_000_000.0 * fps);
        int best = -1;
        for (int sample : syncSamples) {
            int frameIndex = sample - 1;
            if (frameIndex >= requestedFrame && (best < 0 || frameIndex < best)) {
                best = frameIndex;
            }
        }
        if (best < 0) {
            return requestedNanos;
        }
        return Math.round(best / fps * 1_000_000_000.0);
    }

    /** 回到第一帧（用于循环播放）。 */
    public void seekToStart() throws IOException {
        if (track instanceof AbstractMP4DemuxerTrack) {
            ((AbstractMP4DemuxerTrack) track).seek(0.0d);
        } else {
            track.gotoFrame(0);
        }
    }

    /**
     * 跳到指定帧附近（会定位到该帧之前最近的关键帧，保证解码器有参考帧）。
     * 用于循环重播时跳过片头：直接从关键帧解码，省掉从头解码几千帧的时间。
     */
    public void seekToFrame(int frameNo) throws IOException {
        int target = Math.max(0, frameNo);
        if (track instanceof org.jcodec.containers.mp4.demuxer.MP4DemuxerTrack mp4Track) {
            mp4Track.gotoSyncFrame(target);
        } else if (track instanceof AbstractMP4DemuxerTrack abstractTrack) {
            double seconds = fps > 0.5 ? target / fps : 0.0;
            abstractTrack.seek(seconds);
        } else {
            track.gotoFrame(target);
        }
    }

    /** 重新读取关键帧索引之后能否继续解码；供调试用。 */
    public List<String> describeTrack() {
        List<String> info = new ArrayList<>();
        info.add("fourcc=" + fourcc);
        info.add("size=" + width + "x" + height + " (coded " + codedWidth + "x" + codedHeight + ")");
        info.add("fps=" + fps);
        info.add("frames=" + frameCount);
        info.add("duration=" + durationSeconds + "s");
        return info;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int codedWidth() {
        return codedWidth;
    }

    public int codedHeight() {
        return codedHeight;
    }

    public double fps() {
        return fps;
    }

    public int frameCount() {
        return frameCount;
    }

    public double durationSeconds() {
        return durationSeconds;
    }

    public long durationNanos() {
        return (long) (durationSeconds * 1_000_000_000.0d);
    }

    public String fourcc() {
        return fourcc;
    }

    public long trackTimescale() {
        return trackTimescale;
    }

    @Override
    public void close() {
        try {
            demuxer.close();
        } catch (Exception ignored) {
            // ignore
        }
        NIOUtils.closeQuietly(channel);
        pictureBuffer = null;
    }
}
