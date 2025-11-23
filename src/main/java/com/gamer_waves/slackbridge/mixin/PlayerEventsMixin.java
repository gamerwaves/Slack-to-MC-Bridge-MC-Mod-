package com.gamer_waves.slackbridge.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.gamer_waves.slackbridge.SlackBridge;

@Mixin(ServerPlayerEntity.class)
public class PlayerEventsMixin {

    @Inject(method = "onDeath", at = @At("HEAD"))
    private void onPlayerDeath(DamageSource damageSource, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        String name = player.getName().getString();
        String uuid = player.getUuidAsString();
        Text deathMessage = damageSource.getDeathMessage(player);
        String message = deathMessage.getString();
        SlackBridge.sendSlackMessageFromPlayer(name, uuid, message);
    }
}
