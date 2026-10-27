package net.lstonstudios.verity;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
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
    private static final List<String> SUBCOMMANDS = Arrays.asList("summon", "remove", "phase", "karma", "info", "reload");
    private static final List<String> SELECTORS = Arrays.asList("@a", "@s", "@p", "@r");

    private VerityManager manager;
    private File dataFile;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        manager = new VerityManager(this);
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        getServer().getPluginManager().registerEvents(new VerityListener(this, manager), this);
        getCommand("verity").setExecutor(this);
        getCommand("verity").setTabCompleter(this);
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
        if (args.length == 0) {
            return false;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload":
                reloadConfig();
                sender.sendMessage("§aVerity config reloaded.");
                return true;
            case "summon":
            case "remove":
            case "info":
                return subCommand(sender, sub, args);
            case "phase":
            case "karma":
                return valueCommand(sender, sub, args);
            default:
                return giveEggs(sender, args);
        }
    }

    private boolean giveEggs(CommandSender sender, String[] args) {
        int max = Math.max(1, getConfig().getInt("max-eggs", 64));
        int amount = 1;
        if (args.length > 2) {
            return false;
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
            ItemStack egg = createEgg(amount);
            for (ItemStack rest : p.getInventory().addItem(egg).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
            p.sendMessage("§eYou received " + amount + " Verity Spawn Egg" + (amount == 1 ? "" : "s") + ".");
        }
        sender.sendMessage("§aGave " + amount + " Verity Spawn Egg" + (amount == 1 ? "" : "s") + " to "
                + targets.size() + " player" + (targets.size() == 1 ? "" : "s") + ".");
        return true;
    }

    private boolean subCommand(CommandSender sender, String sub, String[] args) {
        if (args.length > 2 || (args.length < 2 && !sub.equals("info"))) {
            return false;
        }
        List<Player> targets = args.length == 2 ? resolve(sender, args[1]) : resolve(sender, "@s");
        if (targets == null) {
            return true;
        }
        int done = 0;
        for (Player p : targets) {
            VerityState s = manager.states.get(p.getUniqueId());
            if (sub.equals("summon")) {
                if (s == null) {
                    manager.openBox(p, p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(2)));
                    done++;
                }
            } else if (sub.equals("remove")) {
                if (s != null) {
                    manager.remove(p.getUniqueId());
                    done++;
                }
            } else {
                if (s == null) {
                    sender.sendMessage("§7" + p.getName() + ": no Verity.");
                } else {
                    sender.sendMessage("§e" + p.getName() + ": phase " + s.phase + ", karma " + s.karma + ", age "
                            + String.format(Locale.ROOT, "%.2f", s.ageTicks / manager.dayTicks()) + " days.");
                }
                done++;
            }
        }
        if (!sub.equals("info")) {
            sender.sendMessage("§aVerity " + sub + ": affected " + done + " of " + targets.size() + " player(s).");
        }
        return true;
    }

    private boolean valueCommand(CommandSender sender, String sub, String[] args) {
        if (args.length != 3) {
            return false;
        }
        int value;
        try {
            value = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cValue must be a whole number.");
            return true;
        }
        boolean phase = sub.equals("phase");
        if (phase ? (value < 1 || value > 4) : (value < 0 || value > 100)) {
            sender.sendMessage(phase ? "§cPhase must be 1-4." : "§cKarma must be 0-100.");
            return true;
        }
        List<Player> targets = resolve(sender, args[1]);
        if (targets == null) {
            return true;
        }
        int done = 0;
        for (Player p : targets) {
            VerityState s = manager.states.get(p.getUniqueId());
            if (s == null) {
                continue;
            }
            if (phase) {
                manager.setPhase(s, value);
            } else {
                s.karma = value;
            }
            done++;
        }
        saveData();
        sender.sendMessage("§aSet " + sub + " to " + value + " for " + done + " player(s) with a Verity.");
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
        if (args.length == 1) {
            addMatches(out, args[0], SUBCOMMANDS);
            addMatches(out, args[0], SELECTORS);
            addPlayers(out, args[0]);
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (SUBCOMMANDS.contains(sub)) {
                if (!sub.equals("reload")) {
                    addMatches(out, args[1], SELECTORS);
                    addPlayers(out, args[1]);
                }
            } else {
                addMatches(out, args[1], Arrays.asList("1", "8", "16", "64"));
            }
        } else if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("phase")) {
                addMatches(out, args[2], Arrays.asList("1", "2", "3", "4"));
            } else if (sub.equals("karma")) {
                addMatches(out, args[2], Arrays.asList("0", "50", "100"));
            }
        }
        Collections.sort(out);
        return out;
    }

    private static void addMatches(List<String> out, String prefix, List<String> options) {
        for (String o : options) {
            if (o.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(o);
            }
        }
    }

    private static void addPlayers(List<String> out, String prefix) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(p.getName());
            }
        }
    }
}
