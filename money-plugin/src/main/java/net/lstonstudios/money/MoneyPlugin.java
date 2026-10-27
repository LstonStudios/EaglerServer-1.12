package net.lstonstudios.money;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class MoneyPlugin extends JavaPlugin implements Listener {

    private static final int PLACED_LIMIT = 200000;
    private static final int SHOP_COUNT = 5;

    private final Map<UUID, Long> balances = new LinkedHashMap<>();
    private final Map<String, Integer> shopBlocks = new HashMap<>();
    private AdminLogin adminLogin;
    private Moderation moderation;
    private KitShop kitShop;
    private final Set<UUID> guideReceived = new HashSet<>();
    private final Set<String> placedBlocks = new HashSet<>();
    private final Shop[] shops = new Shop[SHOP_COUNT];
    private File dataFile;
    private boolean dirty;
    private String symbol = "$";
    private long playerReward;
    private long mobReward;
    private long blockReward;
    private long achievementReward;
    private final Map<Material, Long> blockRewards = new LinkedHashMap<>();

    private static final long DISPLAY_ONLY = -1;
    private static final String[] PANE_COLORS = {"white", "orange", "magenta", "lightblue", "yellow", "lime",
            "pink", "gray", "silver", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    // One slot of a shop menu. A price below zero means the item is only displayed.
    private final class Entry {
        Material material;
        short data;
        int amount = 1;
        long price = DISPLAY_ONLY;
        String name;
        String description;
        String command;
        boolean glow;
        boolean askPrice;

        Entry copy() {
            Entry e = new Entry();
            e.material = material;
            e.data = data;
            e.amount = amount;
            e.price = price;
            e.name = name;
            e.description = description;
            e.command = command;
            e.glow = glow;
            return e;
        }

        boolean isBuyable() {
            return price >= 0;
        }

        String label() {
            return name != null ? ChatColor.translateAlternateColorCodes('&', name)
                    : amount + " " + niceName(material);
        }

        ItemStack icon(boolean edit) {
            ItemStack stack = new ItemStack(material, amount, data);
            ItemMeta meta = stack.getItemMeta();
            if (name != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            }
            List<String> lore = new ArrayList<>();
            if (description != null) {
                lore.add(ChatColor.GRAY + description);
            }
            if (isBuyable()) {
                lore.add(ChatColor.GOLD + "Price: " + money(price));
                if (!edit) {
                    lore.add(ChatColor.YELLOW + "Click to buy");
                }
            } else if (edit) {
                lore.add(ChatColor.GRAY + "Display only (not for sale)");
            }
            if (edit) {
                lore.add(ChatColor.DARK_GRAY + "Left-click: pick up / place");
                lore.add(ChatColor.DARK_GRAY + "Right-click: change price");
            }
            meta.setLore(lore);
            if (glow) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.DURABILITY, 1, true);
            }
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            stack.setItemMeta(meta);
            return stack;
        }
    }

    private final class Shop {
        String title = "Shop";
        final Map<Integer, Entry> slots = new TreeMap<>();
    }

    private static final class ShopHolder implements InventoryHolder {
        final int shop;
        final boolean edit;

        ShopHolder(int shop, boolean edit) {
            this.shop = shop;
            this.edit = edit;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private final class EditSession {
        final int shop;
        final Map<Integer, Entry> entries = new TreeMap<>();
        Entry cursor;

        EditSession(int shop) {
            this.shop = shop;
        }
    }

    private final class PriceTask {
        final int shop;
        final Deque<Integer> slots = new ArrayDeque<>();

        PriceTask(int shop, List<Integer> slots) {
            this.shop = shop;
            this.slots.addAll(slots);
        }
    }

    private final Map<UUID, EditSession> editSessions = new HashMap<>();
    private final Map<UUID, PriceTask> pricing = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadSettings();
        loadData();
        adminLogin = new AdminLogin(this);
        getServer().getPluginManager().registerEvents(adminLogin, this);
        moderation = new Moderation(this, adminLogin);
        getServer().getPluginManager().registerEvents(moderation, this);
        getServer().getPluginManager().registerEvents(this, this);
        kitShop = new KitShop(this);
        getServer().getPluginManager().registerEvents(kitShop, this);
        for (String name : new String[] {"mgive", "minfo", "mhelp", "sinfo", "cshop", "shop", "login", "admin", "deadmin", "op", "deop", "mute", "unmute", "timeout", "unban", "permhelp", "mreload", "mclear"}) {
            getCommand(name).setExecutor(this);
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                saveData();
            }
        }.runTaskTimer(this, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ShopHolder) {
                player.closeInventory();
            }
        }
        saveData();
    }

    private void loadSettings() {
        reloadConfig();
        symbol = getConfig().getString("currency-symbol", "$");
        migrateOldShopConfig();
        playerReward = Math.max(0, getConfig().getLong("rewards.player", 20));
        mobReward = Math.max(0, getConfig().getLong("rewards.mob", 15));
        blockReward = Math.max(0, getConfig().getLong("rewards.block", 5));
        blockRewards.clear();
        ConfigurationSection overrides = getConfig().getConfigurationSection("rewards.blocks");
        if (overrides != null) {
            for (String key : overrides.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                if (material == null) {
                    getLogger().warning("Unknown block in rewards.blocks: " + key);
                    continue;
                }
                blockRewards.put(material, Math.max(0, overrides.getLong(key)));
            }
        }
        achievementReward = Math.max(0, getConfig().getLong("rewards.achievement", 50));
        buildShopLayout();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemMaps(Map<String, Object> category) {
        Object items = category.get("items");
        return items instanceof List ? (List<Map<String, Object>>) items : new ArrayList<Map<String, Object>>();
    }

    private void migrateOldShopConfig() {
        if (getConfig().isList("shop.categories") && !getConfig().isConfigurationSection("shops")) {
            getConfig().set("shops.1.title", getConfig().getString("shop.title", "Shop"));
            getConfig().set("shops.1.categories", getConfig().getList("shop.categories"));
            getConfig().set("shop", null);
            saveConfig();
        }
    }

    private List<Map<String, Object>> loadCategories(int shop) {
        List<Map<String, Object>> categories = new ArrayList<>();
        for (Map<?, ?> raw : getConfig().getMapList("shops." + shop + ".categories")) {
            Map<String, Object> category = new LinkedHashMap<>();
            category.put("name", raw.get("name"));
            List<Map<String, Object>> items = new ArrayList<>();
            Object rawItems = raw.get("items");
            if (rawItems instanceof List) {
                for (Object item : (List<?>) rawItems) {
                    if (item instanceof Map) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : ((Map<?, ?>) item).entrySet()) {
                            copy.put(String.valueOf(e.getKey()), e.getValue());
                        }
                        items.add(copy);
                    }
                }
            }
            category.put("items", items);
            categories.add(category);
        }
        return categories;
    }

    // Converts the older "categories" shop format into free-form slots.
    private void migrateCategories(int n) {
        String base = "shops." + n;
        if (getConfig().isConfigurationSection(base + ".slots") || !getConfig().isList(base + ".categories")) {
            return;
        }
        Map<Integer, Entry> out = new TreeMap<>();
        short[] paneColors = {14, 11, 5, 1, 10, 3};
        int row = 0;
        int colorIndex = 0;
        for (Map<String, Object> category : loadCategories(n)) {
            List<Map<String, Object>> items = itemMaps(category);
            if (items.isEmpty() || row + 1 + (items.size() + 8) / 9 > 6) {
                continue;
            }
            short color = paneColors[colorIndex++ % paneColors.length];
            for (int col = 0; col < 9; col++) {
                Entry pane = new Entry();
                pane.material = Material.STAINED_GLASS_PANE;
                pane.data = color;
                pane.name = col == 4 ? String.valueOf(category.get("name")) : " ";
                out.put(row * 9 + col, pane);
            }
            row++;
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> raw = items.get(i);
                Material material = Material.matchMaterial(String.valueOf(raw.get("material")));
                if (material == null || !(raw.get("price") instanceof Number)) {
                    getLogger().warning("Skipping invalid shop item: " + raw);
                    continue;
                }
                Entry e = new Entry();
                e.material = material;
                e.amount = raw.get("amount") instanceof Number
                        ? Math.max(1, Math.min(64, ((Number) raw.get("amount")).intValue())) : 1;
                e.price = Math.max(0, ((Number) raw.get("price")).longValue());
                e.name = raw.get("name") == null ? null : String.valueOf(raw.get("name"));
                e.description = raw.get("description") == null ? null : String.valueOf(raw.get("description"));
                e.command = raw.get("command") == null ? null : String.valueOf(raw.get("command"));
                e.glow = Boolean.TRUE.equals(raw.get("glow"));
                out.put(row * 9 + i, e);
            }
            row += (items.size() + 8) / 9;
        }
        writeEntries(n, out);
        getConfig().set(base + ".categories", null);
        saveConfig();
    }

    private void writeEntries(int n, Map<Integer, Entry> entries) {
        String base = "shops." + n + ".slots";
        getConfig().set(base, null);
        for (Map.Entry<Integer, Entry> slot : entries.entrySet()) {
            String path = base + "." + slot.getKey() + ".";
            Entry e = slot.getValue();
            getConfig().set(path + "material", e.material.name());
            if (e.data != 0) {
                getConfig().set(path + "data", (int) e.data);
            }
            getConfig().set(path + "amount", e.amount);
            getConfig().set(path + "price", e.price);
            if (e.name != null) {
                getConfig().set(path + "name", e.name);
            }
            if (e.description != null) {
                getConfig().set(path + "description", e.description);
            }
            if (e.command != null) {
                getConfig().set(path + "command", e.command);
            }
            if (e.glow) {
                getConfig().set(path + "glow", true);
            }
        }
    }

    private Map<Integer, Entry> readEntries(int n) {
        Map<Integer, Entry> out = new TreeMap<>();
        ConfigurationSection slots = getConfig().getConfigurationSection("shops." + n + ".slots");
        if (slots == null) {
            return out;
        }
        for (String key : slots.getKeys(false)) {
            ConfigurationSection section = slots.getConfigurationSection(key);
            int slot;
            try {
                slot = Integer.parseInt(key);
            } catch (NumberFormatException ex) {
                continue;
            }
            Material material = section == null ? null : Material.matchMaterial(section.getString("material", ""));
            if (slot < 0 || slot > 53 || material == null) {
                getLogger().warning("Skipping invalid shop " + n + " slot: " + key);
                continue;
            }
            Entry e = new Entry();
            e.material = material;
            e.data = (short) section.getInt("data", 0);
            e.amount = Math.max(1, Math.min(64, section.getInt("amount", 1)));
            e.price = section.getLong("price", DISPLAY_ONLY);
            e.name = section.getString("name");
            e.description = section.getString("description");
            e.command = section.getString("command");
            e.glow = section.getBoolean("glow", false);
            out.put(slot, e);
        }
        return out;
    }

    private void buildShopLayout() {
        for (int n = 1; n <= SHOP_COUNT; n++) {
            migrateCategories(n);
            Shop shop = new Shop();
            shop.title = getConfig().getString("shops." + n + ".title", n == 1 ? "Shop" : "Shop " + n);
            shop.slots.putAll(readEntries(n));
            shops[n - 1] = shop;
        }
    }

    private void loadData() {
        balances.clear();
        shopBlocks.clear();
        guideReceived.clear();
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = data.getConfigurationSection("balances");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    balances.put(UUID.fromString(key), section.getLong(key));
                } catch (IllegalArgumentException ignored) {
                    getLogger().warning("Ignoring invalid balance entry: " + key);
                }
            }
        }
        for (String id : data.getStringList("guide-received")) {
            try {
                guideReceived.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Ignoring invalid guide entry: " + id);
            }
        }
        for (String entry : data.getStringList("shop-blocks")) {
            int split = entry.lastIndexOf('|');
            if (split < 0) {
                shopBlocks.put(entry, 1);
                continue;
            }
            try {
                shopBlocks.put(entry.substring(0, split), Integer.parseInt(entry.substring(split + 1)));
            } catch (NumberFormatException ignored) {
                getLogger().warning("Ignoring invalid shop block entry: " + entry);
            }
        }
    }

    private synchronized void saveData() {
        if (!dirty) {
            return;
        }
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
            data.set("balances." + entry.getKey(), entry.getValue());
        }
        List<String> blockEntries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : shopBlocks.entrySet()) {
            blockEntries.add(entry.getKey() + "|" + entry.getValue());
        }
        data.set("shop-blocks", blockEntries);
        List<String> guideEntries = new ArrayList<>();
        for (UUID id : guideReceived) {
            guideEntries.add(id.toString());
        }
        data.set("guide-received", guideEntries);
        try {
            getDataFolder().mkdirs();
            data.save(dataFile);
            dirty = false;
        } catch (IOException e) {
            getLogger().severe("Could not save money data: " + e.getMessage());
        }
    }

    long getBalance(UUID id) {
        Long value = balances.get(id);
        return value == null ? 0L : value;
    }

    private void addBalance(UUID id, long amount) {
        long current = getBalance(id);
        long updated = current + amount;
        if (updated < current) {
            updated = Long.MAX_VALUE;
        }
        balances.put(id, updated);
        dirty = true;
    }

    /** Takes money if the player can afford it. */
    boolean charge(UUID id, long amount) {
        long balance = getBalance(id);
        if (balance < amount) {
            return false;
        }
        balances.put(id, balance - amount);
        dirty = true;
        return true;
    }

    String money(long amount) {
        return symbol + amount;
    }

    private static String key(Block block) {
        return block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private void reward(Player player, long amount, String reason) {
        if (amount <= 0) {
            return;
        }
        addBalance(player.getUniqueId(), amount);
        player.sendMessage(ChatColor.GREEN + "+" + money(amount) + ChatColor.GRAY + " (" + reason + ")"
                + ChatColor.DARK_GRAY + " Balance: " + money(getBalance(player.getUniqueId())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (placedBlocks.size() >= PLACED_LIMIT) {
            placedBlocks.clear();
        }
        placedBlocks.add(key(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (shopBlocks.remove(key(block)) != null) {
            dirty = true;
        }
        // Blocks placed by players pay nothing, so place-and-break farming doesn't work.
        if (placedBlocks.remove(key(block)) || player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        Long override = blockRewards.get(block.getType());
        reward(player, override != null ? override : blockReward, "mined " + niceName(block.getType()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || killer.equals(dead) || dead instanceof ArmorStand) {
            return;
        }
        if (dead instanceof Player) {
            reward(killer, playerReward, "killed " + dead.getName());
        } else {
            reward(killer, mobReward, "killed a " + niceName(dead.getType().name()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(org.bukkit.event.player.PlayerAdvancementDoneEvent event) {
        String path = event.getAdvancement().getKey().getKey();
        if (path.startsWith("recipes/") || path.endsWith("/root")) {
            return;
        }
        reward(event.getPlayer(), achievementReward, "achievement");
    }

    private static String niceName(Material material) {
        return niceName(material.name());
    }

    private static String niceName(String name) {
        return name.toLowerCase().replace('_', ' ');
    }

    private static final String GUIDE_MARKER = ChatColor.BLACK + "LstonGuide";

    private boolean isGuide(ItemStack stack) {
        if (stack == null || (stack.getType() != Material.PAPER && stack.getType() != Material.WRITTEN_BOOK)
                || !stack.hasItemMeta()
                || !stack.getItemMeta().hasLore()) {
            return false;
        }
        List<String> lore = stack.getItemMeta().getLore();
        return lore.get(lore.size() - 1).equals(GUIDE_MARKER);
    }

    private List<String> guidePages() {
        List<String> pages = new ArrayList<>();
        pages.add(ChatColor.GOLD + "" + ChatColor.BOLD + "\n\n   Lston Server\n        Guide\n\n"
                + ChatColor.DARK_GRAY + "   Made by\n   " + ChatColor.DARK_PURPLE + "LstonStudios\n\n"
                + ChatColor.DARK_GRAY + "Turn the page to read about the server, the rules and the money system.");
        List<String> body = new ArrayList<>();
        addPages(body, guideLines());
        // White, yellow and gray are unreadable on book pages, so swap in darker colors.
        for (String page : body) {
            pages.add(page.replace(ChatColor.WHITE.toString(), ChatColor.BLACK.toString())
                    .replace(ChatColor.YELLOW.toString(), ChatColor.DARK_BLUE.toString())
                    .replace(ChatColor.GRAY.toString(), ChatColor.DARK_GRAY.toString())
                    .replace(ChatColor.GOLD.toString(), ChatColor.DARK_RED.toString()));
        }
        int total = pages.size();
        for (int i = 1; i < total; i++) {
            pages.set(i, pages.get(i) + ChatColor.DARK_GRAY + "\n(" + (i + 1) + "/" + total + ")");
        }
        return pages;
    }

    private ItemStack createGuide() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        org.bukkit.inventory.meta.BookMeta meta = (org.bukkit.inventory.meta.BookMeta) book.getItemMeta();
        meta.setTitle(ChatColor.GOLD + "Lston Server Guide");
        meta.setAuthor("LstonStudios");
        meta.setPages(guidePages());
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "A survival server with custom TNTs,");
        lore.add(ChatColor.GRAY + "5-use abilities, special swords and");
        lore.add(ChatColor.GRAY + "a money system. Made by LstonStudios.");
        lore.add(ChatColor.GRAY + "Includes the community rules.");
        lore.add("");
        lore.add(ChatColor.GREEN + "Right-click to read.");
        lore.add(GUIDE_MARKER);
        meta.setLore(lore);
        book.setItemMeta(meta);
        return book;
    }

    private List<String> guideLines() {
        List<String> lines = new ArrayList<>();
        lines.add(ChatColor.GOLD + "" + ChatColor.BOLD + "Welcome to the Lston Server!");
        lines.add(ChatColor.YELLOW + "About the server:");
        lines.add(ChatColor.WHITE + " A survival server with custom TNTs, right-click abilities, special"
                + " swords and a money system. It works with Eaglercraft and normal Minecraft 1.12 clients.");
        lines.add(ChatColor.WHITE + " Accept the resource pack when asked: custom TNTs show up as rainbow"
                + " command block minecarts in your inventory.");
        lines.add(ChatColor.WHITE + " You can style your own name, see the Name styling page.");
        lines.add(ChatColor.WHITE + " The Lston Server is made and run by LstonStudios, built for players"
                + " who love big explosions, wild abilities and friendly competition.");
        lines.add(ChatColor.YELLOW + "Community:");
        lines.add(ChatColor.WHITE + " Everyone is welcome here, new or experienced. Help new players, share"
                + " what you find, team up, and have fun. Ask an admin if you have questions or ideas.");
        lines.add(ChatColor.YELLOW + "Rules:");
        lines.add(ChatColor.WHITE + " 1. Be respectful. No harassment, hate or spam in chat.");
        lines.add(ChatColor.WHITE + " 2. No cheating: no hacked clients, x-ray, duping or exploiting bugs"
                + " (report bugs to an admin instead).");
        lines.add(ChatColor.WHITE + " 3. No griefing or stealing from players' builds. Claim your land.");
        lines.add(ChatColor.WHITE + " 4. Custom TNT is for fun, not for ruining other players' bases.");
        lines.add(ChatColor.WHITE + " 5. Don't beg for items or money, and don't scam other players.");
        lines.add(ChatColor.WHITE + " 6. Listen to the admins. Breaking the rules can get you muted or banned.");
        lines.add(ChatColor.YELLOW + "Earning money:");
        lines.add(ChatColor.WHITE + " Mine blocks (" + symbol + "1 common up to " + symbol
                + "10 for the rarest, like diamonds and emeralds), kill mobs (" + money(mobReward)
                + "), kill players (" + money(playerReward) + "), earn achievements ("
                + money(achievementReward) + ").");
        lines.add(ChatColor.GRAY + " Blocks you place yourself and creative mode pay nothing.");
        lines.add(ChatColor.YELLOW + "Money commands:");
        lines.add(ChatColor.WHITE + " /minfo [player] - see a balance");
        lines.add(ChatColor.WHITE + " /mgive <player> <amount> - send some of your money to a player");
        lines.add(ChatColor.WHITE + " /cshop <1-5> - open a shop (or right-click a shop block)");
        lines.add(ChatColor.WHITE + " /shop <1-5> - buy shulker box kits (gear, weapons, food); pricier = better");
        lines.add(ChatColor.WHITE + " /mhelp - list the money commands");
        lines.add(ChatColor.YELLOW + "Name styling (/nameeffect):");
        lines.add(ChatColor.WHITE + " /nameeffect set <before>|<after> adds text before and after your name in"
                + " chat and above your head. The | splits the two parts; either can be empty.");
        lines.add(ChatColor.WHITE + " Example: /nameeffect set &c&l[VIP]|&e *");
        lines.add(ChatColor.WHITE + " /nameeffect clear removes it. Each part can be 15 characters at most,"
                + " counting the & codes.");
        lines.add(ChatColor.YELLOW + "Name styling: colors");
        lines.add(ChatColor.WHITE + " Put & and a code before text:");
        lines.add(ChatColor.WHITE + " &0 black, &1 dark blue, &2 dark green, &3 dark aqua, &4 dark red,"
                + " &5 purple, &6 gold, &7 gray, &8 dark gray, &9 blue");
        lines.add(ChatColor.WHITE + " &a green, &b aqua, &c red, &d pink, &e yellow, &f white");
        lines.add(ChatColor.YELLOW + "Name styling: formats");
        lines.add(ChatColor.WHITE + " &l bold, &n underline, &o italic, &m strikethrough, &k scrambled,"
                + " &r reset all styling");
        lines.add(ChatColor.WHITE + " Mix them: &c&l&nHi is red, bold and underlined. Put the color first,"
                + " because a new color cancels earlier formats.");
        lines.add(ChatColor.YELLOW + "Shop 1 - Abilities (right-click to use, 5 uses each, then buy another):");
        lines.add(ChatColor.WHITE + " Gale Force " + symbol + "400 - launch 10 blocks up, no fall damage");
        lines.add(ChatColor.WHITE + " Quantum Shift " + symbol + "500 - teleport 6 blocks forward");
        lines.add(ChatColor.WHITE + " Tailwind " + symbol + "600 - Speed II for you and nearby teammates");
        lines.add(ChatColor.WHITE + " Overclock " + symbol + "700 - Speed III and Haste II for 10s");
        lines.add(ChatColor.WHITE + " Sonic Boom " + symbol + "800 - knock back everything within 4 blocks");
        lines.add(ChatColor.WHITE + " Chitinous Shield " + symbol + "900 - Resistance III, Slowness I for 5s");
        lines.add(ChatColor.WHITE + " Aegis Burst " + symbol + "1000 - clear bad effects, gain absorption");
        lines.add(ChatColor.WHITE + " Shadow Step " + symbol + "1100 - invisible for 4s with gear hidden");
        lines.add(ChatColor.WHITE + " Pyroclastic Smash " + symbol + "1200 - fire wave, 3 hearts and burning");
        lines.add(ChatColor.WHITE + " Reaper's Harvest " + symbol + "1300 - Strength II for 6s, but Hunger III");
        lines.add(ChatColor.WHITE + " Void Tether " + symbol + "1500 - pull a hit player closer and slow them");
        lines.add(ChatColor.YELLOW + "Shop 1 - Custom TNT (place it and light it like normal TNT):");
        lines.add(ChatColor.WHITE + " Poop " + symbol + "600, Firework " + symbol + "800, Slime " + symbol
                + "1000, Anti-TNT " + symbol + "1200 (undoes other custom TNT damage), Fire " + symbol + "1500");
        lines.add(ChatColor.WHITE + " rdmTNT " + symbol + "2000 (random explosion), X-Ray " + symbol
                + "2500 (reveals ores), Mining " + symbol + "3000 (digs a tunnel), Mob " + symbol + "3500");
        lines.add(ChatColor.WHITE + " Sun " + symbol + "4000, Boss " + symbol + "4500, Black Hole "
                + symbol + "5000 (pulls everything in), Nuke " + symbol + "6000 (huge blast)");
        lines.add(ChatColor.YELLOW + "Shop 2 - Custom Guns (golden hoes, right-click to shoot, no ammo):");
        lines.add(ChatColor.WHITE + " 27 guns from " + symbol + "500 (Pistol) to " + symbol + "7000 (Omega Cannon), sorted weakest to strongest."
                + " Each has its own damage, range, reload, sound and particles. Check the tooltip.");
        lines.add(ChatColor.WHITE + " Rapid fire: Derringer, SMG, Machine Pistol, Assault Rifle, Minigun, Laser.");
        lines.add(ChatColor.WHITE + " Effects: Poison Dart, Blinder, Freeze Ray (slows), Gravity Gun (pulls), Knockback Cannon,"
                + " Wither Gun, Void Gun, Thunder Gun (lightning).");
        lines.add(ChatColor.WHITE + " Big hitters: Hunting Rifle, Plasma Rifle, Sniper, Anti-Material Rifle.");
        lines.add(ChatColor.WHITE + " Explosives (no block damage): Grenade Launcher, Rocket Launcher, Dragon Cannon, Mini Nuke Launcher.");
        lines.add(ChatColor.WHITE + " Piercing: Railgun and Omega Cannon hit everything in line.");
        lines.add(ChatColor.WHITE + " Shop 2 also sells the custom swords.");
        lines.add(ChatColor.GRAY + "Other shops may hold more items, so have a look around.");
        return lines;
    }

    // Rows a line takes in a book page (about 19 characters wide), word-wrapped.
    private static int bookRows(String line) {
        String plain = ChatColor.stripColor(line);
        int rows = 1;
        int used = 0;
        for (String word : plain.split(" ", -1)) {
            int len = word.length();
            if (used > 0 && used + 1 + len > 18) {
                rows++;
                used = len;
            } else {
                used += (used > 0 ? 1 : 0) + len;
            }
            while (used > 18) {
                rows++;
                used -= 18;
            }
        }
        return rows;
    }

    // Every yellow heading starts a new page; long sections continue onto more pages.
    private static void addPages(List<String> pages, List<String> lines) {
        StringBuilder page = new StringBuilder();
        int rows = 0;
        for (String line : lines) {
            int lineRows = bookRows(line);
            boolean heading = line.startsWith(ChatColor.YELLOW.toString());
            if (page.length() > 0 && (heading || rows + lineRows > 13)) {
                pages.add(page.toString());
                page.setLength(0);
                rows = 0;
            }
            page.append(line).append('\n');
            rows += lineRows;
        }
        if (page.length() > 0) {
            pages.add(page.toString());
        }
    }

    private void showGuide(Player player) {
        List<String> lines = guideLines();
        if (!openBook(player, lines)) {
            for (String line : lines) {
                player.sendMessage(line);
            }
        }
    }

    // Opens a written book window (pages scroll with the arrows). 1.12 has no API for this,
    // so a temporary book is held and the client is told to open it.
    private boolean openBook(Player player, List<String> lines) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        org.bukkit.inventory.meta.BookMeta meta = (org.bukkit.inventory.meta.BookMeta) book.getItemMeta();
        meta.setTitle("Lston Server Guide");
        meta.setAuthor("LstonStudios");
        meta.setPages(guidePages());
        book.setItemMeta(meta);

        int slot = player.getInventory().getHeldItemSlot();
        ItemStack previous = player.getInventory().getItem(slot);
        player.getInventory().setItem(slot, book);
        player.updateInventory();
        try {
            String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object connection = handle.getClass().getField("playerConnection").get(handle);
            Class<?> byteBuf = Class.forName("io.netty.buffer.ByteBuf");
            Object buffer = Class.forName("io.netty.buffer.Unpooled").getMethod("buffer").invoke(null);
            byteBuf.getMethod("writeByte", int.class).invoke(buffer, 0);
            Class<?> serializer = Class.forName("net.minecraft.server." + version + ".PacketDataSerializer");
            Object data = serializer.getConstructor(byteBuf).newInstance(buffer);
            Class<?> payload = Class.forName("net.minecraft.server." + version + ".PacketPlayOutCustomPayload");
            Object packet = payload.getConstructor(String.class, serializer).newInstance("MC|BOpen", data);
            Class<?> packetClass = Class.forName("net.minecraft.server." + version + ".Packet");
            connection.getClass().getMethod("sendPacket", packetClass).invoke(connection, packet);
            return true;
        } catch (Exception | LinkageError e) {
            getLogger().warning("Could not open the guide book, using chat instead: " + e);
            return false;
        } finally {
            player.getInventory().setItem(slot, previous);
            player.updateInventory();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        // Everyone gets the guide book once, including players who joined before it existed.
        if (!guideReceived.add(player.getUniqueId())) {
            return;
        }
        dirty = true;
        final boolean returning = player.hasPlayedBefore();
        Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    return;
                }
                String welcome = returning ? "Welcome back to the Lston Server!" : "Welcome to the Lston Server!";
                player.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + welcome,
                        ChatColor.YELLOW + "Check the book to learn about the server!", 10, 100, 20);
                player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + welcome);
                for (ItemStack leftover : player.getInventory().addItem(createGuide()).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
        }, 20L);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if ((event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
                && event.getHand() == EquipmentSlot.HAND && isGuide(event.getItem())) {
            if (event.getItem().getType() == Material.WRITTEN_BOOK) {
                return;
            }
            event.setCancelled(true);
            showGuide(event.getPlayer());
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Integer shop = shopBlocks.get(key(event.getClickedBlock()));
        if (shop != null) {
            event.setCancelled(true);
            openShop(event.getPlayer(), shop);
        }
    }

    private String fitTitle(String raw, String prefix) {
        String title = prefix + ChatColor.translateAlternateColorCodes('&', raw);
        if (title.length() > 32) {
            title = title.substring(0, 32);
            if (title.endsWith("\u00a7")) {
                title = title.substring(0, 31);
            }
        }
        return title;
    }

    // Admins edit the shop layout; everyone else just buys.
    private void openShop(Player player, int n) {
        if (player.hasPermission("money.admin")) {
            openEditor(player, n);
        } else {
            openMemberShop(player, n);
        }
    }

    private void openMemberShop(Player player, int n) {
        Shop shop = shops[n - 1];
        int rows = 6;
        Inventory inventory = Bukkit.createInventory(new ShopHolder(n, false), rows * 9, fitTitle(shop.title, ""));
        for (Map.Entry<Integer, Entry> slot : shop.slots.entrySet()) {
            inventory.setItem(slot.getKey(), slot.getValue().icon(false));
        }
        player.openInventory(inventory);
    }

    private void openEditor(Player player, int n) {
        for (Map.Entry<UUID, EditSession> other : editSessions.entrySet()) {
            if (other.getValue().shop == n && !other.getKey().equals(player.getUniqueId())) {
                Player editor = Bukkit.getPlayer(other.getKey());
                if (editor != null && editor.isOnline()) {
                    player.sendMessage(ChatColor.RED + "Shop " + n + " is already being edited by "
                            + editor.getName() + ". Try again once they close it, or use /cshop " + n + " view.");
                    return;
                }
            }
        }
        Shop shop = shops[n - 1];
        Inventory inventory = Bukkit.createInventory(new ShopHolder(n, true), 54, fitTitle(shop.title, ChatColor.RED + "[Edit] "));
        player.openInventory(inventory);
        EditSession session = new EditSession(n);
        for (Map.Entry<Integer, Entry> slot : shop.slots.entrySet()) {
            session.entries.put(slot.getKey(), slot.getValue().copy());
        }
        editSessions.put(player.getUniqueId(), session);
        refreshEditor(inventory, session);
        player.sendMessage(ChatColor.GOLD + "Editing shop " + n + ". Changes save when you close the menu.");
        player.sendMessage(ChatColor.GRAY + "Left-click an item to pick it up, click a slot to place it (swaps if taken).");
        player.sendMessage(ChatColor.GRAY + "Click an item in your own inventory to copy it in. Click your inventory or outside"
                + " while holding one to delete it. Right-click an item to change its price.");
        player.sendMessage(ChatColor.GRAY + "Make section headers with /cshop " + n + " section <color> <name>."
                + " Members see a buy-only view; use /cshop " + n + " view to preview it.");
    }

    private void refreshEditor(Inventory top, EditSession session) {
        for (int slot = 0; slot < top.getSize(); slot++) {
            Entry e = session.entries.get(slot);
            top.setItem(slot, e == null ? null : e.icon(true));
        }
    }

    private Entry fromStack(ItemStack stack, int amount) {
        Entry e = new Entry();
        e.material = stack.getType();
        e.data = e.material.getMaxDurability() == 0 ? stack.getDurability() : 0;
        e.amount = Math.max(1, Math.min(64, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            e.name = meta.getDisplayName().replace('\u00a7', '&');
        }
        boolean pane = e.material == Material.STAINED_GLASS_PANE || e.material == Material.THIN_GLASS;
        e.price = DISPLAY_ONLY;
        e.askPrice = !pane;
        return e;
    }

    @EventHandler
    public void onShopClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof ShopHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        ShopHolder holder = (ShopHolder) top.getHolder();
        if (holder.edit) {
            editClick(event, player, top, holder);
            return;
        }
        if (event.getClickedInventory() != top) {
            return;
        }
        Entry item = shops[holder.shop - 1].slots.get(event.getSlot());
        if (item == null || !item.isBuyable()) {
            return;
        }
        long balance = getBalance(player.getUniqueId());
        if (balance < item.price) {
            player.sendMessage(ChatColor.RED + "You need " + money(item.price - balance) + " more.");
            return;
        }
        if (item.command != null) {
            String command = item.command.replace("%player%", player.getName());
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                player.sendMessage(ChatColor.RED + "That item is unavailable right now.");
                return;
            }
        } else {
            Map<Integer, ItemStack> leftover = player.getInventory()
                    .addItem(new ItemStack(item.material, item.amount, item.data));
            if (!leftover.isEmpty()) {
                for (ItemStack stack : leftover.values()) {
                    player.getInventory().removeItem(new ItemStack(stack.getType(), item.amount - stack.getAmount(),
                            item.data));
                }
                player.sendMessage(ChatColor.RED + "Your inventory is full.");
                return;
            }
        }
        balances.put(player.getUniqueId(), balance - item.price);
        dirty = true;
        player.sendMessage(ChatColor.GREEN + "Bought " + item.label() + ChatColor.GREEN + " for "
                + money(item.price) + ". Balance: " + money(balance - item.price));
    }

    private void editClick(InventoryClickEvent event, Player player, Inventory top, ShopHolder holder) {
        EditSession session = editSessions.get(player.getUniqueId());
        if (session == null || session.shop != holder.shop) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw == -999) {
            session.cursor = null;
        } else if (raw < top.getSize()) {
            Entry here = session.entries.get(raw);
            if (click == ClickType.RIGHT && session.cursor == null) {
                if (here != null) {
                    here.askPrice = true;
                    player.sendMessage(ChatColor.YELLOW + "You'll be asked for the new price when you close the menu.");
                }
            } else if (session.cursor == null) {
                if (here != null) {
                    session.cursor = here;
                    session.entries.remove(raw);
                }
            } else {
                session.entries.put(raw, session.cursor);
                session.cursor = here;
            }
        } else if (session.cursor != null) {
            session.cursor = null;
        } else {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && clicked.getType() != Material.AIR) {
                session.cursor = fromStack(clicked, click == ClickType.RIGHT ? 1 : clicked.getAmount());
            }
        }
        refreshEditor(top, session);
        syncCursor(player, session);
    }

    private void syncCursor(final Player player, final EditSession session) {
        player.setItemOnCursor(session.cursor == null ? null : session.cursor.icon(true));
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                if (editSessions.get(player.getUniqueId()) == session) {
                    player.setItemOnCursor(session.cursor == null ? null : session.cursor.icon(true));
                    player.updateInventory();
                }
            }
        });
    }

    @EventHandler
    public void onShopDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShopHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onShopClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopHolder)
                || !((ShopHolder) event.getInventory().getHolder()).edit
                || !(event.getPlayer() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        EditSession session = editSessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        player.setItemOnCursor(null);
        List<Integer> toPrice = new ArrayList<>();
        for (Map.Entry<Integer, Entry> slot : session.entries.entrySet()) {
            if (slot.getValue().askPrice) {
                toPrice.add(slot.getKey());
            }
        }
        writeEntries(session.shop, session.entries);
        saveConfig();
        loadSettings();
        player.sendMessage(ChatColor.GREEN + "Shop " + session.shop + " saved.");
        if (!toPrice.isEmpty()) {
            PriceTask task = new PriceTask(session.shop, toPrice);
            pricing.put(player.getUniqueId(), task);
            promptPrice(player, task);
        }
    }

    private void promptPrice(Player player, PriceTask task) {
        Entry e = shops[task.shop - 1].slots.get(task.slots.peek());
        player.sendMessage(ChatColor.YELLOW + "Type the price for " + (e == null ? "this item" : e.label())
                + ChatColor.YELLOW + " in chat (a number, 'skip' for display only, or 'cancel'). "
                + task.slots.size() + " left.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPriceChat(AsyncPlayerChatEvent event) {
        final Player player = event.getPlayer();
        if (!pricing.containsKey(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        final String message = event.getMessage().trim();
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                handlePriceReply(player, message);
            }
        });
    }

    private void handlePriceReply(Player player, String message) {
        PriceTask task = pricing.get(player.getUniqueId());
        if (task == null) {
            return;
        }
        String lower = message.toLowerCase();
        if (lower.equals("cancel")) {
            pricing.remove(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "Pricing stopped. Unpriced items stay display only.");
            return;
        }
        long price = DISPLAY_ONLY;
        if (!lower.equals("skip") && !lower.equals("display")) {
            try {
                price = Long.parseLong(message);
                if (price < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Type a whole number, 'skip' or 'cancel'.");
                return;
            }
        }
        Integer slot = task.slots.poll();
        getConfig().set("shops." + task.shop + ".slots." + slot + ".price", price);
        saveConfig();
        loadSettings();
        if (task.slots.isEmpty()) {
            pricing.remove(player.getUniqueId());
            player.sendMessage(ChatColor.GREEN + "All prices set.");
        } else {
            promptPrice(player, task);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pricing.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "mgive":
                return give(sender, args);
            case "minfo":
                return info(sender, args);
            case "login":
                return adminLogin.login(sender, args);
            case "admin":
                return adminLogin.admin(sender, command, args, true, false);
            case "op":
                return adminLogin.admin(sender, command, args, true, true);
            case "deadmin":
                return adminLogin.admin(sender, command, args, false, false);
            case "deop":
                return adminLogin.admin(sender, command, args, false, true);
            case "permhelp":
                return adminLogin.permHelp(sender);
            case "unban":
                if (args.length != 1) {
                    return false;
                }
                boolean ip = args[0].matches("[0-9.:]+");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), (ip ? "pardon-ip " : "pardon ") + args[0]);
                sender.sendMessage(ChatColor.GREEN + "Unbanned " + args[0] + " (if they were banned).");
                return true;
            case "mute":
            case "unmute":
            case "timeout":
                return moderation.command(sender, command.getName().toLowerCase(), args);
            case "mclear":
                return clear(sender, args);
            case "sinfo":
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Only players can use this.");
                    return true;
                }
                Player reader = (Player) sender;
                for (ItemStack leftover : reader.getInventory().addItem(createGuide()).values()) {
                    reader.getWorld().dropItemNaturally(reader.getLocation(), leftover);
                }
                reader.sendMessage(ChatColor.GREEN + "Here is a new server guide book. Right-click it to read.");
                return true;
            case "mhelp":
                help(sender);
                return true;
            case "cshop":
                return manageShopBlock(sender, args);
            case "shop":
                return kitShop.command(sender, args);
            case "mreload":
                loadSettings();
                sender.sendMessage(ChatColor.GREEN + "Money configuration reloaded.");
                return true;
            default:
                return false;
        }
    }

    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (name.equalsIgnoreCase(offline.getName())) {
                return offline;
            }
        }
        return null;
    }

    private boolean give(CommandSender sender, String[] args) {
        if (args.length != 2) {
            return false;
        }
        OfflinePlayer target = findPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Unknown player: " + args[0]);
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            amount = 0;
        }
        if (amount <= 0) {
            sender.sendMessage(ChatColor.RED + "Amount must be a positive whole number.");
            return true;
        }
        boolean unlimited = !(sender instanceof Player) || sender.hasPermission("money.admin");
        if (!unlimited) {
            Player giver = (Player) sender;
            if (giver.getUniqueId().equals(target.getUniqueId())) {
                sender.sendMessage(ChatColor.RED + "You can't send money to yourself.");
                return true;
            }
            long balance = getBalance(giver.getUniqueId());
            if (balance < amount) {
                sender.sendMessage(ChatColor.RED + "You only have " + money(balance) + ".");
                return true;
            }
            balances.put(giver.getUniqueId(), balance - amount);
        }
        addBalance(target.getUniqueId(), amount);
        sender.sendMessage(ChatColor.GREEN + "Gave " + money(amount) + " to " + target.getName() + ".");
        if (target.isOnline()) {
            target.getPlayer().sendMessage(ChatColor.GREEN + "You received " + money(amount) + ". Balance: "
                    + money(getBalance(target.getUniqueId())));
        }
        return true;
    }

    private boolean clear(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return false;
        }
        OfflinePlayer target = findPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Unknown player: " + args[0]);
            return true;
        }
        balances.put(target.getUniqueId(), 0L);
        dirty = true;
        sender.sendMessage(ChatColor.GREEN + "Cleared " + target.getName() + "'s balance.");
        if (target.isOnline()) {
            target.getPlayer().sendMessage(ChatColor.RED + "Your balance was cleared.");
        }
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        if (args.length > 1) {
            return false;
        }
        OfflinePlayer target;
        if (args.length == 1) {
            target = findPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Unknown player: " + args[0]);
                return true;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage("Usage: /minfo <player>");
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + target.getName() + "'s balance: " + ChatColor.YELLOW
                + money(getBalance(target.getUniqueId())));
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Money Help ===");
        sender.sendMessage(ChatColor.YELLOW + "Earn money: " + ChatColor.WHITE + "mine blocks ($1 common to $10 rare"
                + "), kill mobs (" + money(mobReward) + "), kill players (" + money(playerReward) + "), earn achievements (" + money(achievementReward) + ").");
        sender.sendMessage(ChatColor.GRAY + "Blocks you place yourself and creative mode pay nothing.");
        sender.sendMessage(ChatColor.YELLOW + "/minfo [player]" + ChatColor.WHITE + " - see a balance");
        sender.sendMessage(ChatColor.YELLOW + "/cshop <1-5>" + ChatColor.WHITE
                + " - buy items (or right-click a shop block)");
        sender.sendMessage(ChatColor.YELLOW + "/shop <1-5>" + ChatColor.WHITE
                + " - buy shulker box kits; the pricier the kit, the better the gear");
        sender.sendMessage(ChatColor.YELLOW + "/mgive <player> <amount>" + ChatColor.WHITE
                + (sender.hasPermission("money.admin") ? " - give money (unlimited for admins)"
                        : " - send some of your money to a player"));
        sender.sendMessage(ChatColor.YELLOW + "/mhelp" + ChatColor.WHITE + " - show this help");
        sender.sendMessage(ChatColor.GRAY + "Every /m<name> command also works as /money<name>, e.g. /moneyinfo.");
        if (sender.hasPermission("money.admin")) {

            sender.sendMessage(ChatColor.YELLOW + "/cshop <1-5>" + ChatColor.WHITE
                    + " - (admin) opens the shop in edit mode: move, copy and delete items, set prices");
            sender.sendMessage(ChatColor.YELLOW + "/cshop <1-5> view" + ChatColor.WHITE
                    + " - preview the member (buy-only) view");
            sender.sendMessage(ChatColor.YELLOW + "/cshop <1-5> section <color> <name>" + ChatColor.WHITE
                    + " - get a colored header pane to place in the shop");
            sender.sendMessage(ChatColor.YELLOW + "/cshop <1-5> rename <name>" + ChatColor.WHITE
                    + " - rename a shop menu (& color codes work)");
            sender.sendMessage(ChatColor.YELLOW + "/cshop <1-5> setblock|delblock" + ChatColor.WHITE
                    + " - make or unmake the block you look at a shop block");
            sender.sendMessage(ChatColor.YELLOW + "/customtnt <player> <type|all> [amount]" + ChatColor.WHITE
                    + " - give custom TNT (/rldcustomtnt reloads)");
            sender.sendMessage(ChatColor.YELLOW + "/customability <player> <ability|all> [amount]" + ChatColor.WHITE
                    + " - give abilities");
            sender.sendMessage(ChatColor.YELLOW + "/customsword <player> <type|all> [amount]" + ChatColor.WHITE
                    + " - give custom swords");
            sender.sendMessage(ChatColor.YELLOW + "/spawn <entity> [x y z amount radius]" + ChatColor.WHITE
                    + " - spawn many entities");
            sender.sendMessage(ChatColor.YELLOW + "/nameeffect set|clear <player> ..." + ChatColor.WHITE
                    + " - style anyone's name; /nameeffect reload");
            sender.sendMessage(ChatColor.YELLOW + "/ban /unban /kick /mute /unmute /timeout /gamemode /kill /give /xp"
                    + ChatColor.WHITE + " - moderation");
            sender.sendMessage(ChatColor.YELLOW + "/mclear <player>" + ChatColor.WHITE + " - reset a balance");
            sender.sendMessage(ChatColor.YELLOW + "/mreload" + ChatColor.WHITE + " - reload config");
        }
    }

    private int parseShop(String text) {
        try {
            int n = Integer.parseInt(text);
            return n >= 1 && n <= SHOP_COUNT ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean manageShopBlock(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use the shop.");
            return true;
        }
        if (args.length == 0) {
            openShop((Player) sender, 1);
            return true;
        }
        int shop = parseShop(args[0]);
        if (shop < 0) {
            sender.sendMessage(ChatColor.RED + "Shop must be a number from 1 to " + SHOP_COUNT + ".");
            return true;
        }
        if (args.length == 1) {
            openShop((Player) sender, shop);
            return true;
        }
        if (args[1].equalsIgnoreCase("view") && args.length == 2) {
            openMemberShop((Player) sender, shop);
            return true;
        }
        if (!sender.hasPermission("money.admin")) {
            sender.sendMessage(ChatColor.RED + "Only admins can change shops. Use /cshop " + shop + " to open it.");
            return true;
        }
        String sub = args[1].toLowerCase();
        if (sub.equals("section")) {
            return giveSection(sender, args);
        }
        if (sub.equals("rename")) {
            if (args.length < 3) {
                return false;
            }
            StringBuilder name = new StringBuilder(args[2]);
            for (int i = 3; i < args.length; i++) {
                name.append(' ').append(args[i]);
            }
            if (ChatColor.translateAlternateColorCodes('&', name.toString()).length() > 32) {
                sender.sendMessage(ChatColor.RED + "The name can be at most 32 characters, including color codes.");
                return true;
            }
            getConfig().set("shops." + shop + ".title", name.toString());
            saveConfig();
            loadSettings();
            sender.sendMessage(ChatColor.GREEN + "Shop " + shop + " is now named "
                    + ChatColor.translateAlternateColorCodes('&', name.toString()) + ChatColor.GREEN + ".");
            return true;
        }
        if (args.length != 2) {
            return false;
        }
        Block target = ((Player) sender).getTargetBlock((Set<Material>) null, 6);
        if (target == null || target.getType() == Material.AIR) {
            sender.sendMessage(ChatColor.RED + "Look at a block within 6 blocks.");
            return true;
        }
        if (sub.equals("setblock")) {
            shopBlocks.put(key(target), shop);
            sender.sendMessage(ChatColor.GREEN + "Block set. Right-click it to open shop " + shop + ".");
        } else if (sub.equals("delblock")) {
            if (shopBlocks.remove(key(target)) == null) {
                sender.sendMessage(ChatColor.RED + "That is not a shop block.");
                return true;
            }
            sender.sendMessage(ChatColor.GREEN + "Shop block removed.");
        } else {
            return false;
        }
        dirty = true;
        return true;
    }

    private boolean giveSection(CommandSender sender, String[] args) {
        if (args.length < 4) {
            return false;
        }
        int color = Arrays.asList(PANE_COLORS).indexOf(args[2].toLowerCase());
        if (color < 0) {
            sender.sendMessage(ChatColor.RED + "Colors: " + String.join(", ", PANE_COLORS));
            return true;
        }
        StringBuilder name = new StringBuilder(args[3]);
        for (int i = 4; i < args.length; i++) {
            name.append(' ').append(args[i]);
        }
        Player player = (Player) sender;
        ItemStack pane = new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) color);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name.toString()));
        pane.setItemMeta(meta);
        for (ItemStack leftover : player.getInventory().addItem(pane).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        sender.sendMessage(ChatColor.GREEN + "Got a section pane. Open the shop in edit mode, click it in your"
                + " inventory to copy it, and place it where you want the header.");
        return true;
    }
}
