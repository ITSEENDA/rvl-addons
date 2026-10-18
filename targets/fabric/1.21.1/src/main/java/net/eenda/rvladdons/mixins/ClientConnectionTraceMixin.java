package net.eenda.rvladdons.mixins;

import io.netty.channel.ChannelHandlerContext;
import net.eenda.rvladdons.network.RvlAddonsTraceBridge;
import net.eenda.rvladdons.core.RvlAddonsTrace;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionTraceMixin {
    @Inject(
        method = "sendInternal(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;Z)V",
        at = @At("HEAD")
    )
    private void rvladdons$traceOutbound(Packet<?> packet, PacketCallbacks callbacks, boolean flush, CallbackInfo info) {
        RvlAddonsTraceBridge.inspectOutgoingPacket(packet);
        RvlAddonsTrace.logPacket("out", packet);
    }

    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;)V",
        at = @At("HEAD")
    )
    private void rvladdons$traceInbound(ChannelHandlerContext context, Object packet, CallbackInfo info) {
        RvlAddonsTraceBridge.inspectIncomingPacket(packet);
        RvlAddonsTrace.logPacket("in", packet);
    }
}
