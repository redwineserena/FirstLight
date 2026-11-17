package com.syuto.introvideo.video;

import java.nio.ByteBuffer;
import org.jcodec.common.model.Picture;

/**
 * 把 JCodec 解码出来的 YUV420 {@link Picture} 转换成 OpenGL 直接可用的 RGBA 字节。
 *
 * <p>JCodec 的平面数据是“偏移 -128”的，所以统一按 {@code actual = stored + 128} 还原，
 * 再用整数系数做 YUV → RGB。系数支持两种常见标准：</p>
 * <ul>
 *   <li>{@link Matrix#BT709}（默认）—— HD 视频（720p/1080p）基本都是 BT.709；
 *       用 720p 测试片对比 ffmpeg 参考帧，709 的 PSNR 约 45dB，601 只有 25~36dB（颜色明显偏）；</li>
 *   <li>{@link Matrix#BT601}—— 老 SD 视频常用。</li>
 * </ul>
 *
 * <p>输出为 top-down 的 RGBA 字节（每像素 4 字节），正好对应 MC 的纹理坐标（v=0 在顶部）。</p>
 */
public final class Yuv420Rgba {

    public enum Matrix {
        BT709,
        BT601
    }

    private Yuv420Rgba() {
    }

    /** 使用 BT.709（HD 默认）。 */
    public static void convert(Picture pic, ByteBuffer dst) {
        convert(pic, dst, Matrix.BT709);
    }

    /** 转换到 {@code dst}（容量必须 >= croppedWidth * croppedHeight * 4），转换后 flip()。 */
    public static void convert(Picture pic, ByteBuffer dst, Matrix matrix) {
        final int cw = pic.getCroppedWidth();
        final int ch = pic.getCroppedHeight();
        final int sx = pic.getStartX();
        final int sy = pic.getStartY();

        final byte[] yPlane = pic.getPlaneData(0);
        final byte[] uPlane = pic.getPlaneData(1);
        final byte[] vPlane = pic.getPlaneData(2);
        final int yStride = pic.getPlaneWidth(0);
        final int cStride = pic.getPlaneWidth(1);

        // BT.709 limited: R = 298(Y-16) + 459(V-128), G = 298(Y-16) - 55(U-128) - 136(V-128), B = 298(Y-16) + 541(U-128)
        // BT.601 limited: R = 298(Y-16) + 409(V-128), G = 298(Y-16) - 100(U-128) - 208(V-128), B = 298(Y-16) + 516(U-128)
        final boolean bt709 = matrix == Matrix.BT709;
        final int rv = bt709 ? 459 : 409;
        final int gu = bt709 ? 55 : 100;
        final int gv = bt709 ? 136 : 208;
        final int bu = bt709 ? 541 : 516;

        dst.clear();
        for (int row = 0; row < ch; row++) {
            final int yOff = (sy + row) * yStride + sx;
            final int cOff = ((sy + row) >> 1) * cStride + (sx >> 1);
            for (int col = 0; col < cw; col++) {
                // c = 1.164 * (Y - 16)；JCodec 数据是 -128 偏移的，所以是 (stored + 112)
                final int c = yPlane[yOff + col] + 112;
                final int u = uPlane[cOff + (col >> 1)];
                final int v = vPlane[cOff + (col >> 1)];
                int r = (298 * c + rv * v + 128) >> 8;
                int g = (298 * c - gu * u - gv * v + 128) >> 8;
                int b = (298 * c + bu * u + 128) >> 8;
                dst.put((byte) (r < 0 ? 0 : (r > 255 ? 255 : r)));
                dst.put((byte) (g < 0 ? 0 : (g > 255 ? 255 : g)));
                dst.put((byte) (b < 0 ? 0 : (b > 255 ? 255 : b)));
                dst.put((byte) 0xFF);
            }
        }
        dst.flip();
    }
}
