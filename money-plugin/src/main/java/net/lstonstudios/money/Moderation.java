package net.lstonstudios.money;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

// /mute, /unmute and /timeout, plus protection so admins can't punish other staff.
final class Moderation implements Listener {
    private static final String[] CHAT_COMMANDS = {"me", "say", "msg", "tell", "w", "r", "reply", "t"};
    private final MoneyPlugin plugin;
    private final AdminLogin login;
    private final File file;
    private final YamlConfiguration data;

    Moderation(MoneyPlugin plugin, AdminLogin login) {
        this.plugin = plugin;
        this.login = login;
        this.file = new File(plugin.getDataFolder(), "moderation.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    private void save() {
        try {
            plugin.getDataFolder().mkdirs();
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save moderation.yml: " + e.getMessage());
        }
    }

    private boolean isMuted(UUID id) {
        return data.getBoolean("mutes." + id, false);
    }

    private long timeoutLeft(UUID id) {
        long end = data.getLong("timeouts." + id, 0);
        if (end == 0) {
            return 0;
        }
        long left = end - System.currentTimeMillis();
        if (left <= 0) {
            data.set("timeouts." + id, null);
            return 0;
        }
        return left;
    }

    boolean command(CommandSender sender, String name, String[] args) {
        if (args.length < 1 || (name.equals("timeout") && args.length < 2)) {
            return false;
        }
        Player online = Bukkit.getPlayerExact(args[0]);
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(args[0]);
        if (online == null && !target.hasPlayedBefore()) {
            sender.sendMessage(ChatColor.RED + "That player has never joined the server.");
            return true;
        }
        String who = target.getName() != null ? target.getName() : args[0];
        boolean undo = name.equals("unmute") || (name.equals("timeout") && args[1].equalsIgnoreCase("off"));
        if (!undo && login.isStaff(target) && !(sender instanceof org.bukkit.command.ConsoleCommandSender)
                && !sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "You can't do that to " + who + ".");
            return true;
        }
        UUID id = target.getUniqueId();
        if (name.equals("mute")) {
            data.set("mutes." + id, true);
            save();
            sender.sendMessage(ChatColor.GREEN + who + " is muted.");
            if (online != null) {
                online.sendMessage(ChatColor.RED + "You have been muted." + reason(args, 1));
            }
        } else if (name.equals("unmute")) {
            data.set("mutes." + id, null);
            save();
            sender.sendMessage(ChatColor.GREEN + who + " is unmuted.");
            if (online != null) {
                online.sendMessage(ChatColor.GREEN + "You have been unmuted.");
            }
        } else if (undo) {
            data.set("timeouts." + id, null);
            save();
            sender.sendMessage(ChatColor.GREEN + who + "'s timeout is over.");
            if (online != null) {
                online.sendMessage(ChatColor.GREEN + "Your timeout has ended.");
            }
        } else {
            long minutes;
            try {
                minutes = Long.parseLong(args[1]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (minutes < 1 || minutes > 10080) {
                sender.sendMessage(ChatColor.RED + "Minutes must be between 1 and 10080 (one week).");
                return true;
            }
            data.set("timeouts." + id, System.currentTimeMillis() + minutes * 60000L);
            save();
            sender.sendMessage(ChatColor.GREEN + who + " is timed out for " + minutes + " minute(s).");
            if (online != null) {
                online.sendMessage(ChatColor.RED + "You have been timed out for " + minutes + " minute(s)."
                        + reason(args, 2));
            }
        }
        return true;
    }

    private static String reason(String[] args, int from) {
        if (args.length <= from) {
            return "";
        }
        return " Reason: " + String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }

    private boolean blocked(Player player, boolean notify) {
        long left = timeoutLeft(player.getUniqueId());
        if (left > 0 && notify) {
            player.sendMessage(ChatColor.RED + "You are timed out for " + (left / 60000 + 1) + " more minute(s).");
        }
        return left > 0;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (isMuted(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You are muted.");
        } else if (blocked(player, true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String[] parts = event.getMessage().substring(1).split(" ");
        String name = parts[0].toLowerCase();
        if (name.startsWith("minecraft:") || name.startsWith("bukkit:")) {
            name = name.substring(name.indexOf(':') + 1);
        }
        if (blocked(player, true)) {
            event.setCancelled(true);
            return;
        }
        if (isMuted(player.getUniqueId())) {
            for (String chat : CHAT_COMMANDS) {
                if (chat.equals(name)) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "You are muted.");
                    return;
                }
            }
        }
        // Admins can't ban or kick ops or other admins.
        if (!player.isOp() && parts.length > 1 && (name.equals("ban") || name.equals("kick")
                || name.equals("ban-ip") || name.equals("tempban"))) {
            Player online = Bukkit.getPlayerExact(parts[1]);
            OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(parts[1]);
            if (login.isStaff(target)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You can't do that to " + parts[1] + ".");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onMove(PlayerMoveEvent event) {
        if (timeoutLeft(event.getPlayer().getUniqueId()) > 0 && (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY() || event.getFrom().getZ() != event.getTo().getZ())) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent event) {
        if (blocked(event.getPlayer(), false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent event) {
        if (blocked(event.getPlayer(), false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (blocked(event.getPlayer(), false)) {
            event.setCancelled(true);
        }
    }
}
