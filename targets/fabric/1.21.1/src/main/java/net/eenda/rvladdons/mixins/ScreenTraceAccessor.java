package net.eenda.rvladdons.mixins;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Screen.class)
public interface ScreenTraceAccessor {
    @Accessor("title")
    Text rvladdons$getTitle();
}
