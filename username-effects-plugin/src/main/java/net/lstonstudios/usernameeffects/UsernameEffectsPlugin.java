package net.lstonstudios.usernameeffects;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class UsernameEffectsPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, NameEffect> effects = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadEffects();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("nameeffect").setExecutor(this);
    }

    private void loadEffects() {
        effects.clear();
        if (!getConfig().isConfigurationSection("players")) {
            return;
        }
        for (String key : getConfig().getConfigurationSection("players").getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String path = "players." + key + ".";
                effects.put(uuid, new NameEffect(
                        color(getConfig().getString(path + "prefix", "")),
                        color(getConfig().getString(path + "suffix", ""))));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> applyEffect(player, effects.get(player.getUniqueId())));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        NameEffect effect = effects.get(event.getPlayer().getUniqueId());
        if (effect == null) {
            return;
        }
        String prefix = effect.prefix.isEmpty() ? "" : effect.prefix + " ";
        String suffix = effect.suffix.isEmpty() ? "" : " " + effect.suffix;
        event.setFormat(escapePercent(prefix) + "%1$s" + escapePercent(suffix) + ChatColor.RESET + ": %2$s");
    }

    private String escapePercent(String value) {
        return value.replace("%", "%%");
    }

    private boolean applyEffect(Player player, NameEffect effect) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = "ue" + player.getUniqueId().toString().replace("-", "").substring(0, 14);
        Team team = scoreboard.getTeam(teamName);
        if (effect == null) {
            if (team != null) {
                team.removePlayer(player);
                team.unregister();
            }
            return true;
        }

        String prefix = effect.prefix.isEmpty() ? "" : effect.prefix + " ";
        String suffix = effect.suffix.isEmpty() ? "" : " " + effect.suffix;
        if (prefix.length() > 16 || suffix.length() > 16) {
            return false;
        }
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }
        Team currentTeam = scoreboard.getEntryTeam(player.getName());
        if (currentTeam != null && !currentTeam.getName().equals(teamName)) {
            return false;
        }
        team.setPrefix(prefix);
        team.setSuffix(suffix);
        team.addPlayer(player);
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("usernameeffects.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage username effects.");
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadEffects();
            for (Player player : Bukkit.getOnlinePlayers()) {
                applyEffect(player, effects.get(player.getUniqueId()));
            }
            sender.sendMessage(ChatColor.GREEN + "Username effects reloaded.");
            return true;
        }
        if (args.length < 2) {
            sendUsage(sender, label);
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "That player must be online.");
            return true;
        }
        if (args[0].equalsIgnoreCase("clear")) {
            effects.remove(target.getUniqueId());
            getConfig().set("players." + target.getUniqueId(), null);
            saveConfig();
            applyEffect(target, null);
            sender.sendMessage(ChatColor.GREEN + "Cleared the username effect for " + target.getName() + ".");
            return true;
        }
        if (!args[0].equalsIgnoreCase("set") || args.length < 3) {
            sendUsage(sender, label);
            return true;
        }

        String style = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        int separator = style.indexOf('|');
        String prefix = color((separator < 0 ? style : style.substring(0, separator)).trim());
        String suffix = color(separator < 0 ? "" : style.substring(separator + 1).trim());
        NameEffect effect = new NameEffect(prefix, suffix);
        if ((prefix.isEmpty() ? 0 : prefix.length() + 1) > 16 || (suffix.isEmpty() ? 0 : suffix.length() + 1) > 16) {
            sender.sendMessage(ChatColor.RED + "Prefix and suffix must each fit the 16-character 1.12 scoreboard limit.");
            return true;
        }
        if (!applyEffect(target, effect)) {
            sender.sendMessage(ChatColor.RED + "Could not apply the effect; the player may already be in another scoreboard team.");
            return true;
        }

        effects.put(target.getUniqueId(), effect);
        String path = "players." + target.getUniqueId() + ".";
        getConfig().set(path + "name", target.getName());
        getConfig().set(path + "prefix", prefix);
        getConfig().set(path + "suffix", suffix);
        saveConfig();
        sender.sendMessage(ChatColor.GREEN + "Applied the username effect to " + target.getName() + ".");
        return true;
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " set <player> <prefix>|<suffix>");
        sender.sendMessage(ChatColor.YELLOW + "       /" + label + " clear <player>  or  /" + label + " reload");
    }

    private static final class NameEffect {
        private final String prefix;
        private final String suffix;

        private NameEffect(String prefix, String suffix) {
            this.prefix = prefix;
            this.suffix = suffix;
        }
    }
}