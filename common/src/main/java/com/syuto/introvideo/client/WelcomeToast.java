package com.syuto.introvideo.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 原版风格的“成就达成”提示：使用原版 toast 背景贴图 + 物品图标 + 两行文字，
 * 由原版 ToastComponent 负责滑入/滑出/排队，外观与原版成就弹窗一致。
 */
public class WelcomeToast implements Toast {

    private static final ResourceLocation BACKGROUND = new ResourceLocation("textures/gui/toasts.png");

    private final Component title;
    private final Component text;
    private final ItemStack icon;
    private final long durationMs;

    public WelcomeToast(Component title, Component text, ItemStack icon, long durationMs) {
        this.title = title;
        this.text = text;
        this.icon = icon;
        this.durationMs = Math.max(1000L, durationMs);
    }

    @Override
    public Toast.Visibility render(GuiGraphics graphics, ToastComponent toastComponent, long timeSinceLastVisible) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.blit(BACKGROUND, 0, 0, 0, 0, this.width(), this.height());
        graphics.renderFakeItem(this.icon, 8, 8);
        graphics.drawString(toastComponent.getMinecraft().font, this.title, 30, 7, 0xFFFFD24A, false);
        graphics.drawString(toastComponent.getMinecraft().font, this.text, 30, 18, 0xFFFFFFFF, false);
        return timeSinceLastVisible < this.durationMs ? Toast.Visibility.SHOW : Toast.Visibility.HIDE;
    }
}
