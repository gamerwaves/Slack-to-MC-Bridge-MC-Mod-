package com.gamer_waves.slackbridge.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import com.gamer_waves.slackbridge.SlackBridge;

public class UnlinkCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("unlink")
            .executes(UnlinkCommand::execute));
    }

    private static int execute(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        if (!source.isExecutedByPlayer()) {
            source.sendError(Text.literal("This command can only be used by players."));
            return 0;
        }

        ServerPlayerEntity player = source.getPlayer();
        String uuid = player.getUuidAsString();

        if (!SlackBridge.isPlayerLinked(uuid)) {
            player.sendMessage(Text.literal("§cYour account is not linked to Slack."));
            return 0;
        }

        SlackBridge.unlinkAccount(uuid);
        
        player.networkHandler.disconnect(Text.literal(
            "§c§lYour account has been unlinked from Slack.\n\n" +
            "§eYou must relink to continue playing."
        ));

        return 1;
    }
}
