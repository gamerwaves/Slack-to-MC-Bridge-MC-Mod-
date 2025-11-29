package com.gamer_waves.slackbridge.mixin;

import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.gamer_waves.slackbridge.SlackBridge;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerPlayNetworkHandler.class)
public class ChatMessageMixin {

    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "handleDecoratedMessage", at = @At("HEAD"))
    private void onChatMessage(SignedMessage message, CallbackInfo ci) {
        String playerName = player.getName().getString();
        String uuid = player.getUuidAsString();
        String messageText = message.getContent().getString();

        messageText = SlackBridge.processMcMentions(messageText);

        SlackBridge.sendSlackMessageFromPlayer(
            playerName,
            uuid,
            messageText
        );
    }
}
