package net.lstonstudios.eaglernotifications;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.lax1dude.eaglercraft.backend.server.api.notifications.INotificationBuilder;
import net.lax1dude.eaglercraft.backend.server.api.notifications.INotificationManager;
import net.lax1dude.eaglercraft.backend.server.api.notifications.INotificationService;
import net.lax1dude.eaglercraft.backend.server.api.velocity.EaglerXServerAPI;

public final class EaglerNotifyCommand implements SimpleCommand {
    @Override
    public void execute(Invocation invocation) {
        if (!invocation.source().hasPermission("eaglernotifications.send")) {
            invocation.source().sendMessage(Component.text("You do not have permission to send Eagler notifications."));
            return;
        }

        String input = String.join(" ", invocation.arguments()).trim();
        if (input.isEmpty()) {
            invocation.source().sendMessage(Component.text("Usage: /eaglernotify <message> or <title> :: <message>"));
            return;
        }

        int separator = input.indexOf("::");
        String title = separator >= 0 ? input.substring(0, separator).trim() : "Server Notice";
        String body = separator >= 0 ? input.substring(separator + 2).trim() : input;
        if (title.isEmpty() || body.isEmpty()) {
            invocation.source().sendMessage(Component.text("Usage: /eaglernotify <message> or <title> :: <message>"));
            return;
        }

        INotificationService<Player> service = EaglerXServerAPI.instance().getNotificationService();
        INotificationBuilder<Component> badge = service.createNotificationBuilder(Component.class)
                .setTitleComponent(title)
                .setBodyComponent(body)
                .setSourceComponent("The Lston EaglerCraft Server")
                .setOriginalTimestampSec(System.currentTimeMillis() / 1000L);
        INotificationManager<Player> manager = service.getNotificationManagerAll();
        int recipients = manager.getPlayerList().size();
        manager.showNotificationBadge(badge);
        invocation.source().sendMessage(Component.text("Sent an Eagler notification to " + recipients + " player(s)."));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("eaglernotifications.send");
    }
}