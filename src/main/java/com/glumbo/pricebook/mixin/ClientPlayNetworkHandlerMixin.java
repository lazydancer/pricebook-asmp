package com.glumbo.pricebook.mixin;

import com.glumbo.pricebook.GlumboPricebookClient;
import com.glumbo.pricebook.scanner.ShopScanner;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Shadow private ClientWorld world;

    @Inject(method = "onChunkData", at = @At("TAIL"))
    private void glumbo$scanChunk(ChunkDataS2CPacket packet, CallbackInfo ci) {
        ShopScanner scanner = GlumboPricebookClient.scanner();
        if (scanner == null || world == null || !GlumboPricebookClient.isEnabled()) {
            return;
        }

        int chunkX = packet.getChunkX();
        int chunkZ = packet.getChunkZ();
        WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }
        scanner.scanChunk(world, chunk);
    }

    @Inject(method = "onGameJoin", at = @At("TAIL"))
    private void glumbo$resetScanner(GameJoinS2CPacket packet, CallbackInfo ci) {
        GlumboPricebookClient.onMultiplayerJoin();
    }

    @Inject(method = "onUnloadChunk", at = @At("TAIL"))
    private void glumbo$forgetChunk(UnloadChunkS2CPacket packet, CallbackInfo ci) {
        ShopScanner scanner = GlumboPricebookClient.scanner();
        if (scanner == null) {
            return;
        }
        scanner.forgetChunk(packet.pos());
    }
}
