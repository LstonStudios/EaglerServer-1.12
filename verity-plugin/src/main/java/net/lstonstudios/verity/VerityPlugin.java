package net.lstonstudios.verity;

import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

public final class VerityPlugin extends JavaPlugin implements TabExecutor {
    private static final String EGG_MARKER = "§0VerityEgg";

    private final Random random = new Random();
    private VerityManager manager;
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new VerityManager(this);
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        getServer().getPluginManager().registerEvents(new VerityListener(this, manager), this);
        getCommand("verity").setExecutor(this);
        getCommand("verity").setTabCompleter(this);
        getCommand("verityphase").setExecutor(this);
        getCommand("verityphase").setTabCompleter(this);
        // Clear anything a crash left behind before the first tick spawns fresh bodies.
        for (org.bukkit.World w : getServer().getWorlds()) {
            for (org.bukkit.entity.Entity e : w.getEntities()) {
                manager.cleanupStray(e);
            }
        }
        manager.start();
    }

    @Override
    public void onDisable() {
        saveData();
        manager.shutdown();
    }

    // ------------------------------------------------------------- storage

    private void loadData() {
        manager.states.clear();
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        if (y.getConfigurationSection("players") == null) {
            return;
        }
        for (String key : y.getConfigurationSection("players").getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                manager.states.put(id, VerityState.load(id, y.getConfigurationSection("players." + key)));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Skipping bad Verity entry " + key);
            }
        }
    }

    void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (VerityState s : manager.states.values()) {
            s.save(y.createSection("players." + s.owner));
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save Verity data: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------- egg

    // Own item (not a spawn egg) so the resource pack can give it a unique texture.
    static final Material EGG_MATERIAL = Material.NETHER_STAR;

    static ItemStack createEgg(int amount) {
        ItemStack egg = new ItemStack(EGG_MATERIAL, amount);
        ItemMeta meta = egg.getItemMeta();
        meta.setDisplayName("§e§lVerity Box");
        meta.setLore(Arrays.asList("§7Right-click to open the Box", "§7and meet your personal helper friend.",
                "§8Are you sure about this?", EGG_MARKER));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        egg.setItemMeta(meta);
        return egg;
    }

    static boolean isEgg(ItemStack item) {
        if (item == null || (item.getType() != EGG_MATERIAL && item.getType() != Material.MONSTER_EGG) || !item.hasItemMeta()) {
            return false;
        }
        List<String> lore = item.getItemMeta().getLore();
        return lore != null && lore.contains(EGG_MARKER);
    }

    // ------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("verityphase")) {
            return phaseCommand(sender, args);
        }
        if (command.getName().equalsIgnoreCase("verityhelp")) {
            return help(sender);
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "":
                return giveSelf(sender);
            case "phase":
                return phasePlayer(sender, args);
            case "kill":
                return killOwn(sender);
            case "killall":
                return killAll(sender);
            default:
                if (!sender.hasPermission("verity.admin")) {
                    sender.sendMessage("§eUsage: /verity  |  /verity kill");
                    return true;
                }
                return giveEggs(sender, args);
        }
    }

    private boolean help(CommandSender sender) {
        sender.sendMessage("§e§lVerity commands");
        sender.sendMessage("§e/verity §7- get a Verity Box");
        sender.sendMessage("§e/verity kill §7- kill your Verity");
        sender.sendMessage("§e/verityhelp §7- show this list");
        if (sender.hasPermission("verity.admin")) {
            sender.sendMessage("§6Admin:");
            sender.sendMessage("§e/verity <targets> [amount] §7- give Verity Boxes (@a, @s, @p, @r or names)");
            sender.sendMessage("§e/verity phase <player> <1-4> §7- set one player's Verity phase");
            sender.sendMessage("§e/verityphase <1-4> §7- set every Verity's phase");
            sender.sendMessage("§e/verity killall §7- kill every Verity");
        }
        return true;
    }

    private boolean phasePlayer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("verity.admin")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return true;
        }
        if (args.length != 3) {
            sender.sendMessage("§eUsage: /verity phase <player> <1-4>");
            return true;
        }
        int value;
        try {
            value = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            value = 0;
        }
        if (value < 1 || value > 4) {
            sender.sendMessage("§cPhase must be 1-4.");
            return true;
        }
        List<Player> targets = resolve(sender, args[1]);
        if (targets == null) {
            return true;
        }
        int done = 0;
        for (Player p : targets) {
            VerityState s = manager.states.get(p.getUniqueId());
            if (s != null) {
                manager.setPhase(s, value);
                done++;
            }
        }
        saveData();
        sender.sendMessage("§aSet " + done + " Verity" + (done == 1 ? "" : "s") + " to phase " + value
                + (done < targets.size() ? " (" + (targets.size() - done) + " player(s) had no Verity)." : "."));
        return true;
    }

    private boolean giveSelf(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cUse /verity <targets> [amount] from the console.");
            return true;
        }
        Player p = (Player) sender;
        for (ItemStack rest : p.getInventory().addItem(createEgg(1)).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        p.sendMessage("§eYou received a Verity Box.");
        return true;
    }

    private boolean giveEggs(CommandSender sender, String[] args) {
        int max = Math.max(1, getConfig().getInt("max-eggs", 64));
        int amount = 1;
        if (args.length > 2) {
            sender.sendMessage("§eUsage: /verity <targets> [amount]");
            return true;
        }
        if (args.length == 2) {
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cAmount must be a whole number.");
                return true;
            }
            if (amount < 1 || amount > max) {
                sender.sendMessage("§cAmount must be between 1 and " + max + ".");
                return true;
            }
        }
        List<Player> targets = resolve(sender, args[0]);
        if (targets == null) {
            return true;
        }
        for (Player p : targets) {
            for (ItemStack rest : p.getInventory().addItem(createEgg(amount)).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
            p.sendMessage("§eYou received " + amount + " Verity Box" + (amount == 1 ? "" : "es") + ".");
        }
        sender.sendMessage("§aGave " + amount + " Verity Box" + (amount == 1 ? "" : "es") + " to "
                + targets.size() + " player" + (targets.size() == 1 ? "" : "s") + ".");
        return true;
    }

    private boolean killOwn(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players have a Verity.");
            return true;
        }
        Player p = (Player) sender;
        if (manager.states.get(p.getUniqueId()) == null) {
            sender.sendMessage("§7You don't have a Verity.");
            return true;
        }
        manager.remove(p.getUniqueId());
        sender.sendMessage("§aYour Verity is gone.");
        return true;
    }

    private boolean killAll(CommandSender sender) {
        if (!sender.hasPermission("verity.admin")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return true;
        }
        List<UUID> ids = new ArrayList<>(manager.states.keySet());
        for (UUID id : ids) {
            manager.remove(id);
        }
        for (org.bukkit.World w : getServer().getWorlds()) {
            for (org.bukkit.entity.Entity e : w.getEntities()) {
                manager.cleanupStray(e);
            }
        }
        sender.sendMessage("§aRemoved " + ids.size() + " Verity" + (ids.size() == 1 ? "" : "s") + ".");
        return true;
    }

    private boolean phaseCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("verity.admin")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return true;
        }
        if (args.length != 1) {
            return false;
        }
        int value;
        try {
            value = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            value = 0;
        }
        if (value < 1 || value > 4) {
            sender.sendMessage("§cPhase must be 1-4.");
            return true;
        }
        int done = 0;
        for (VerityState s : new ArrayList<>(manager.states.values())) {
            manager.setPhase(s, value);
            done++;
        }
        saveData();
        sender.sendMessage("§aSet " + done + " Verity" + (done == 1 ? "" : "s") + " to phase " + value + ".");
        return true;
    }

    /** Resolves @a, @s, @p, @r or a (partial) player name. Returns null after reporting an error. */
    private List<Player> resolve(CommandSender sender, String sel) {
        List<Player> out = new ArrayList<>();
        String lower = sel.toLowerCase(Locale.ROOT);
        if (lower.startsWith("@")) {
            switch (lower) {
                case "@a":
                    out.addAll(Bukkit.getOnlinePlayers());
                    break;
                case "@s":
                    if (!(sender instanceof Player)) {
                        sender.sendMessage("§c@s needs a player sender.");
                        return null;
                    }
                    out.add((Player) sender);
                    break;
                case "@p": {
                    Location from = sender instanceof Player ? ((Player) sender).getLocation()
                            : sender instanceof BlockCommandSender ? ((BlockCommandSender) sender).getBlock().getLocation()
                            : null;
                    if (from == null) {
                        sender.sendMessage("§c@p needs a player or command block sender.");
                        return null;
                    }
                    Player best = null;
                    double bd = Double.MAX_VALUE;
                    for (Player p : from.getWorld().getPlayers()) {
                        double d = p.getLocation().distanceSquared(from);
                        if (d < bd) {
                            bd = d;
                            best = p;
                        }
                    }
                    if (best != null) {
                        out.add(best);
                    }
                    break;
                }
                case "@r": {
                    List<Player> all = new ArrayList<>(Bukkit.getOnlinePlayers());
                    if (!all.isEmpty()) {
                        out.add(all.get(random.nextInt(all.size())));
                    }
                    break;
                }
                default:
                    sender.sendMessage("§cUnsupported selector: " + sel + " (use @a, @s, @p, @r or a name).");
                    return null;
            }
        } else {
            Player p = Bukkit.getPlayerExact(sel);
            if (p == null) {
                List<Player> partial = Bukkit.matchPlayer(sel);
                p = partial.size() == 1 ? partial.get(0) : null;
            }
            if (p != null) {
                out.add(p);
            }
        }
        if (out.isEmpty()) {
            sender.sendMessage("§cNo matching players found for " + sel + ".");
            return null;
        }
        return out;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("verityphase")) {
            if (args.length == 1 && sender.hasPermission("verity.admin")) {
                addMatches(out, args[0], Arrays.asList("1", "2", "3", "4"));
            }
        } else if (command.getName().equalsIgnoreCase("verity")) {
            boolean admin = sender.hasPermission("verity.admin");
            if (args.length == 1) {
                addMatches(out, args[0], Arrays.asList("kill"));
                if (admin) {
                    addMatches(out, args[0], Arrays.asList("phase", "killall", "@a", "@s", "@p", "@r"));
                    addPlayers(out, args[0]);
                }
            } else if (admin && args[0].equalsIgnoreCase("phase")) {
                if (args.length == 2) {
                    addMatches(out, args[1], Arrays.asList("@a", "@s", "@p", "@r"));
                    addPlayers(out, args[1]);
                } else if (args.length == 3) {
                    addMatches(out, args[2], Arrays.asList("1", "2", "3", "4"));
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    private static void addPlayers(List<String> out, String prefix) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(p.getName());
            }
        }
    }

    private static void addMatches(List<String> out, String prefix, List<String> options) {
        for (String o : options) {
            if (o.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(o);
            }
        }
    }
}
