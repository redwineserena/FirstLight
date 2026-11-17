package com.syuto.introvideo.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.syuto.introvideo.IntroVideo;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;

/**
 * 一块“动态视频纹理”：分配一次 GL 纹理，之后每帧把解码好的 RGBA 数据上传上去。
 *
 * <p>注意：Minecraft 的 {@code GuiGraphics.blit(ResourceLocation, ...)} 会通过
 * {@code TextureManager} 拿到本纹理的 GL id，所以需要先用
 * {@code TextureManager.register(ResourceLocation, AbstractTexture)} 注册。</p>
 */
public final class VideoTexture extends AbstractTexture {

    private final int width;
    private final int height;
    private final ResourceLocation location;
    private boolean allocated;
    private boolean closed;

    public VideoTexture(ResourceLocation location, int width, int height) {
        this.location = location;
        this.width = width;
        this.height = height;
    }

    @Override
    public void load(ResourceManager resourceManager) {
        // 动态纹理，没有静态资源可加载
    }

    public ResourceLocation location() {
        return location;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** 上传一帧（RGBA 字节，宽高必须与创建时一致）。调用线程必须是渲染线程。 */
    public void upload(ByteBuffer rgba) {
        if (closed) {
            return;
        }
        int id = getId();
        if (!allocated) {
            allocate(id);
        }
        GlStateManager._bindTexture(id);
        // 关键：Minecraft 上传纹理图集时会改 GL_UNPACK_ROW_LENGTH / SKIP_ROWS / ALIGNMENT，
        // 不重置就会按错误的“每行像素数”读我们的缓冲区 → 画面会斜切/花屏。
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
    }

    private void allocate(int id) {
        GlStateManager._bindTexture(id);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        allocated = true;
        IntroVideo.LOGGER.info("[IntroVideo] 已创建视频纹理 {}x{} (gl id {})", width, height, id);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (id != -1) {
            TextureUtil.releaseTextureId(id);
            id = -1;
        }
    }
}
