package com.syuto.introvideo.mixin;

import com.syuto.introvideo.client.IntroVideoClient;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 字体（含中文字形）真正加载完成时通知客户端。
 *
 * <p>加载画面前期语言资源可能已经有翻译，但字体里的中文字形还没就绪；如果用“宽度猜”的方式，
 * 某些替换字体/字体模组会误判，导致又出现“口口口”。这里直接挂到字体加载完成的回调上，最可靠。</p>
 */
@Mixin(FontManager.class)
public abstract class FontManagerMixin {

    @Inject(method = "apply", at = @At("RETURN"), require = 0)
    private void introvideo$onFontsApplied(CallbackInfo ci) {
        IntroVideoClient.get().onFontsReloaded();
    }
}
