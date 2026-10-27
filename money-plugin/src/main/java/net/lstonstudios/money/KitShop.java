package net.lstonstudios.money;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.block.ShulkerBox;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionType;

/** /shop <1-5>: shulker box kits. The pricier the kit, the better the contents. */
final class KitShop implements Listener {
    static final int PAGES = 5;
    private static final int[] KIT_SLOTS = {10, 12, 14, 16};
    private static final int PREV = 18;
    private static final int INFO = 22;
    private static final int NEXT = 26;
    private static final int BACK = 31;
    private static final String[] PAGE_NAMES = {"Starter", "Iron & Specialty", "Utility", "Diamond", "Legendary"};
    // Stained glass pane colors per page.
    private static final short[] PAGE_PANES = {5, 8, 11, 3, 2};

    private final MoneyPlugin plugin;
    private final List<List<Kit>> pages = new ArrayList<>();

    private static final class Kit {
        final String name;
        final ChatColor color;
        final Material box;
        final long price;
        final String blurb;
        final List<ItemStack> items = new ArrayList<>();

        Kit(String name, ChatColor color, Material box, long price, String blurb) {
            this.name = name;
            this.color = color;
            this.box = box;
            this.price = price;
            this.blurb = blurb;
        }

        Kit add(ItemStack... stacks) {
            for (ItemStack s : stacks) {
                items.add(s);
            }
            return this;
        }
    }

    private static final class KitHolder implements InventoryHolder {
        final int page;
        final Kit preview;

        KitHolder(int page, Kit preview) {
            this.page = page;
            this.preview = preview;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    KitShop(MoneyPlugin plugin) {
        this.plugin = plugin;
        for (int i = 0; i < PAGES; i++) {
            pages.add(new ArrayList<Kit>());
        }
        buildKits();
        validate();
    }

    /** Builds every kit once so bad item data shows up in the log at startup. */
    private void validate() {
        for (List<Kit> kits : pages) {
            for (Kit kit : kits) {
                try {
                    ItemStack box = display(kit);
                    ShulkerBox state = (ShulkerBox) ((BlockStateMeta) box.getItemMeta()).getBlockState();
                    int stored = 0;
                    for (ItemStack it : state.getInventory().getContents()) {
                        if (it != null) {
                            stored++;
                        }
                    }
                    if (stored != kit.items.size()) {
                        plugin.getLogger().warning("Kit " + kit.name + " does not fit its shulker box.");
                    }
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Kit " + kit.name + " is broken: " + ex);
                }
            }
        }
        plugin.getLogger().info("Kit shop ready: " + pages.size() + " pages.");
    }

    // ---------------------------------------------------------- item helpers

    private static ItemStack i(Material m, int amount) {
        return new ItemStack(m, amount);
    }

    private static ItemStack i(Material m) {
        return new ItemStack(m, 1);
    }

    private static ItemStack data(Material m, int amount, int data) {
        return new ItemStack(m, amount, (short) data);
    }

    /** Item with enchantments given as alternating enchantment, level. */
    private static ItemStack e(Material m, Object... enchants) {
        ItemStack s = new ItemStack(m);
        for (int k = 0; k + 1 < enchants.length; k += 2) {
            s.addUnsafeEnchantment((Enchantment) enchants[k], (Integer) enchants[k + 1]);
        }
        return s;
    }

    // Must match the items built by the CustomWeapons plugin (same material and hidden lore marker).
    private static ItemStack weapon(Material m, String name, ChatColor color, String marker, String[] lore,
            Object... enchants) {
        ItemStack s = e(m, enchants);
        ItemMeta meta = s.getItemMeta();
        meta.setDisplayName(color + "" + ChatColor.BOLD + name);
        List<String> l = new ArrayList<>();
        for (String line : lore) {
            l.add(ChatColor.GRAY + line);
        }
        l.add(ChatColor.BLACK + marker);
        meta.setLore(l);
        meta.setUnbreakable(true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
        s.setItemMeta(meta);
        return s;
    }

    private static ItemStack mace(Object... enchants) {
        return weapon(Material.GOLD_SPADE, "Mace", ChatColor.DARK_GRAY, "CustomWeapon:mace",
                new String[] {"Fall onto your target for smash damage.", "The farther you fall, the harder it hits",
                        "and you take no fall damage."}, enchants);
    }

    private static ItemStack spear(Object... enchants) {
        return weapon(Material.GOLD_PICKAXE, "Spear", ChatColor.AQUA, "CustomWeapon:spear",
                new String[] {"Left-click: jab up to 4.5 blocks.", "Hold right-click, release to dash (3s cooldown)",
                        "and skewer all. Efficiency = longer dash."}, enchants);
    }

    private static ItemStack wind(int amount) {
        ItemStack s = new ItemStack(Material.PRISMARINE_CRYSTALS, amount);
        ItemMeta meta = s.getItemMeta();
        meta.setDisplayName(ChatColor.WHITE + "" + ChatColor.BOLD + "Wind Burst");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Right-click: launch yourself skyward,",
                ChatColor.GRAY + "then smash down with the Mace.",
                ChatColor.GRAY + "Consumed on use. No fall damage for 4s.", ChatColor.BLACK + "CustomWeapon:wind"));
        s.setItemMeta(meta);
        return s;
    }

    private static ItemStack book(Object... enchants) {
        ItemStack s = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) s.getItemMeta();
        for (int k = 0; k + 1 < enchants.length; k += 2) {
            meta.addStoredEnchant((Enchantment) enchants[k], (Integer) enchants[k + 1], true);
        }
        s.setItemMeta(meta);
        return s;
    }

    private static ItemStack potion(Material kind, PotionType type, boolean extended, boolean upgraded, int amount) {
        ItemStack s = new ItemStack(kind, amount);
        PotionMeta meta = (PotionMeta) s.getItemMeta();
        meta.setBasePotionData(new PotionData(type, extended, upgraded));
        s.setItemMeta(meta);
        return s;
    }

    private static ItemStack rocket(int amount, int power) {
        ItemStack s = new ItemStack(Material.FIREWORK, amount);
        FireworkMeta meta = (FireworkMeta) s.getItemMeta();
        meta.setPower(power);
        s.setItemMeta(meta);
        return s;
    }

    // --------------------------------------------------------------- catalog

    private void buildKits() {
        Enchantment prot = Enchantment.PROTECTION_ENVIRONMENTAL;
        Enchantment unb = Enchantment.DURABILITY;
        Enchantment mend = Enchantment.MENDING;
        Enchantment sharp = Enchantment.DAMAGE_ALL;
        Enchantment eff = Enchantment.DIG_SPEED;
        Enchantment fort = Enchantment.LOOT_BONUS_BLOCKS;
        Enchantment power = Enchantment.ARROW_DAMAGE;

        // ---- Page 1: Starter (cheap) ----
        pages.get(0).add(new Kit("Food Pack", ChatColor.GREEN, Material.LIME_SHULKER_BOX, 150,
                "Never go hungry on day one.")
                .add(i(Material.BREAD, 16), i(Material.COOKED_BEEF, 16), i(Material.BAKED_POTATO, 16),
                        i(Material.COOKED_CHICKEN, 8), i(Material.APPLE, 8), i(Material.MELON, 16)));
        pages.get(0).add(new Kit("Builder Starter", ChatColor.YELLOW, Material.YELLOW_SHULKER_BOX, 250,
                "Everything for your first house.")
                .add(i(Material.WOOD, 64), i(Material.WOOD, 64), i(Material.COBBLESTONE, 64),
                        i(Material.GLASS, 32), i(Material.TORCH, 32), i(Material.WOOD_DOOR, 4),
                        i(Material.LADDER, 16), i(Material.CHEST, 4), i(Material.WOOD_AXE), i(Material.WOOD_SPADE)));
        pages.get(0).add(new Kit("Leather Scout", ChatColor.GOLD, Material.BROWN_SHULKER_BOX, 350,
                "Light armor and a bow.")
                .add(i(Material.LEATHER_HELMET), i(Material.LEATHER_CHESTPLATE), i(Material.LEATHER_LEGGINGS),
                        i(Material.LEATHER_BOOTS), i(Material.WOOD_SWORD), i(Material.BOW), i(Material.ARROW, 32),
                        i(Material.COOKED_BEEF, 8), i(Material.TORCH, 16)));
        pages.get(0).add(new Kit("Stone Warrior", ChatColor.GRAY, Material.SILVER_SHULKER_BOX, 600,
                "Stone tools, chainmail and a shield.")
                .add(i(Material.STONE_SWORD), i(Material.STONE_PICKAXE), i(Material.STONE_AXE),
                        i(Material.STONE_SPADE), i(Material.CHAINMAIL_HELMET), i(Material.CHAINMAIL_CHESTPLATE),
                        i(Material.CHAINMAIL_LEGGINGS), i(Material.CHAINMAIL_BOOTS), i(Material.SHIELD),
                        i(Material.COOKED_BEEF, 16), i(Material.TORCH, 32), i(Material.COAL, 16)));

        // ---- Page 2: Iron & specialty ----
        pages.get(1).add(new Kit("Iron Warrior", ChatColor.WHITE, Material.WHITE_SHULKER_BOX, 1200,
                "Full iron armor with Protection I and a sharp blade.")
                .add(e(Material.IRON_HELMET, prot, 1), e(Material.IRON_CHESTPLATE, prot, 1),
                        e(Material.IRON_LEGGINGS, prot, 1), e(Material.IRON_BOOTS, prot, 1),
                        e(Material.IRON_SWORD, sharp, 1), i(Material.SHIELD), i(Material.BOW),
                        i(Material.ARROW, 48), i(Material.COOKED_BEEF, 24), i(Material.GOLDEN_APPLE, 2)));
        pages.get(1).add(new Kit("Iron Miner", ChatColor.AQUA, Material.CYAN_SHULKER_BOX, 1400,
                "Efficient iron tools and supplies for a deep dive.")
                .add(e(Material.IRON_PICKAXE, eff, 2, unb, 1), e(Material.IRON_AXE, eff, 2),
                        e(Material.IRON_SPADE, eff, 2), i(Material.IRON_SWORD), i(Material.TORCH, 64),
                        i(Material.COOKED_BEEF, 32), i(Material.WATER_BUCKET), i(Material.COBBLESTONE, 64),
                        i(Material.RAILS, 32), i(Material.MINECART), i(Material.COAL, 32)));
        pages.get(1).add(new Kit("Archer Pack", ChatColor.DARK_GREEN, Material.GREEN_SHULKER_BOX, 1500,
                "A Power II bow and a mountain of arrows.")
                .add(e(Material.BOW, power, 2, unb, 1), i(Material.ARROW, 64), i(Material.ARROW, 64),
                        i(Material.SPECTRAL_ARROW, 32), e(Material.IRON_HELMET), i(Material.IRON_CHESTPLATE),
                        i(Material.IRON_LEGGINGS), i(Material.IRON_BOOTS), i(Material.COOKED_BEEF, 16)));
        pages.get(1).add(new Kit("Farmer's Bounty", ChatColor.DARK_AQUA, Material.BLUE_SHULKER_BOX, 1000,
                "Seeds, tools and animals' favorite snacks.")
                .add(i(Material.IRON_HOE), i(Material.WATER_BUCKET), i(Material.SEEDS, 32),
                        i(Material.CARROT_ITEM, 32), i(Material.POTATO_ITEM, 32), i(Material.BEETROOT_SEEDS, 16),
                        i(Material.PUMPKIN_SEEDS, 16), i(Material.MELON_SEEDS, 16), data(Material.INK_SACK, 64, 15),
                        i(Material.SADDLE), i(Material.LEASH, 4), i(Material.WHEAT, 32), i(Material.SHEARS)));

        // ---- Page 3: Utility ----
        pages.get(2).add(new Kit("Redstone Engineer", ChatColor.RED, Material.RED_SHULKER_BOX, 2000,
                "Pistons, hoppers and redstone for any contraption.")
                .add(i(Material.REDSTONE, 64), i(Material.REDSTONE_BLOCK, 8), i(Material.PISTON_BASE, 8),
                        i(Material.PISTON_STICKY_BASE, 8), i(Material.HOPPER, 8), i(Material.DISPENSER, 4),
                        i(Material.DROPPER, 4), i(Material.OBSERVER, 8), i(Material.DIODE, 8),
                        i(Material.REDSTONE_COMPARATOR, 4), i(Material.LEVER, 8), i(Material.STONE_BUTTON, 8),
                        i(Material.SLIME_BLOCK, 8), i(Material.DAYLIGHT_DETECTOR, 2), i(Material.TNT, 16)));
        pages.get(2).add(new Kit("Potion Pack", ChatColor.LIGHT_PURPLE, Material.MAGENTA_SHULKER_BOX, 2400,
                "Splash and drinkable potions for any fight.")
                .add(potion(Material.POTION, PotionType.STRENGTH, false, false, 1),
                        potion(Material.POTION, PotionType.SPEED, false, false, 1),
                        potion(Material.POTION, PotionType.FIRE_RESISTANCE, true, false, 1),
                        potion(Material.POTION, PotionType.NIGHT_VISION, true, false, 1),
                        potion(Material.POTION, PotionType.WATER_BREATHING, true, false, 1),
                        potion(Material.POTION, PotionType.REGEN, false, false, 1),
                        potion(Material.SPLASH_POTION, PotionType.INSTANT_HEAL, false, true, 4),
                        potion(Material.SPLASH_POTION, PotionType.POISON, false, false, 2),
                        potion(Material.SPLASH_POTION, PotionType.SLOWNESS, false, false, 2),
                        potion(Material.SPLASH_POTION, PotionType.WEAKNESS, false, false, 2),
                        i(Material.GOLDEN_CARROT, 16), i(Material.GOLDEN_APPLE, 3)));
        pages.get(2).add(new Kit("Explorer's Chest", ChatColor.DARK_PURPLE, Material.PURPLE_SHULKER_BOX, 3000,
                "Boats, ender pearls and a trusty steed.")
                .add(i(Material.BOAT, 2), i(Material.SADDLE), i(Material.DIAMOND_BARDING),
                        i(Material.ENDER_PEARL, 8), i(Material.COMPASS), i(Material.EMPTY_MAP, 4),
                        i(Material.NAME_TAG, 2), i(Material.WATER_BUCKET), i(Material.COOKED_BEEF, 32),
                        i(Material.GOLDEN_CARROT, 32), i(Material.TORCH, 64), i(Material.BED, 2),
                        i(Material.FISHING_ROD), i(Material.SHEARS), i(Material.FLINT_AND_STEEL)));
        pages.get(2).add(new Kit("Enchanter's Study", ChatColor.BLUE, Material.BLUE_SHULKER_BOX, 3800,
                "Everything to enchant your own gear.")
                .add(i(Material.ENCHANTMENT_TABLE), i(Material.BOOKSHELF, 15), i(Material.ANVIL),
                        data(Material.INK_SACK, 64, 4), i(Material.EXP_BOTTLE, 64), i(Material.BOOK, 16),
                        i(Material.OBSIDIAN, 8), i(Material.DIAMOND, 4),
                        book(unb, 3), book(eff, 3), book(sharp, 3), book(prot, 3)));

        // ---- Page 4: Diamond ----
        pages.get(3).add(new Kit("Diamond Warrior", ChatColor.AQUA, Material.LIGHT_BLUE_SHULKER_BOX, 6000,
                "Protection III diamond armor and a Sharpness III sword.")
                .add(e(Material.DIAMOND_HELMET, prot, 3, unb, 2), e(Material.DIAMOND_CHESTPLATE, prot, 3, unb, 2),
                        e(Material.DIAMOND_LEGGINGS, prot, 3, unb, 2), e(Material.DIAMOND_BOOTS, prot, 3, unb, 2),
                        e(Material.DIAMOND_SWORD, sharp, 3, unb, 2, Enchantment.FIRE_ASPECT, 1),
                        i(Material.SHIELD), e(Material.BOW, power, 3), i(Material.ARROW, 64),
                        i(Material.GOLDEN_APPLE, 6), i(Material.COOKED_BEEF, 32), i(Material.ENDER_PEARL, 8)));
        pages.get(3).add(new Kit("Diamond Miner", ChatColor.GREEN, Material.GREEN_SHULKER_BOX, 5500,
                "Efficiency IV tools with Fortune III on the pick.")
                .add(e(Material.DIAMOND_PICKAXE, eff, 4, fort, 3, unb, 3), e(Material.DIAMOND_AXE, eff, 4, unb, 3),
                        e(Material.DIAMOND_SPADE, eff, 4, unb, 3),
                        e(Material.DIAMOND_PICKAXE, eff, 4, Enchantment.SILK_TOUCH, 1, unb, 3),
                        e(Material.DIAMOND_HELMET, Enchantment.WATER_WORKER, 1, unb, 2),
                        potion(Material.POTION, PotionType.NIGHT_VISION, true, false, 2),
                        i(Material.TORCH, 64), i(Material.COOKED_BEEF, 32), i(Material.WATER_BUCKET),
                        i(Material.LAVA_BUCKET)));
        pages.get(3).add(new Kit("Diamond Sniper", ChatColor.DARK_GREEN, Material.GRAY_SHULKER_BOX, 5500,
                "A Power IV bow with Punch and Unbreaking.")
                .add(e(Material.BOW, power, 4, Enchantment.ARROW_KNOCKBACK, 1, unb, 3),
                        e(Material.BOW, power, 3, Enchantment.ARROW_FIRE, 1),
                        i(Material.ARROW, 64), i(Material.ARROW, 64), i(Material.ARROW, 64),
                        i(Material.SPECTRAL_ARROW, 64),
                        e(Material.DIAMOND_HELMET, prot, 2), e(Material.DIAMOND_CHESTPLATE, prot, 2),
                        e(Material.DIAMOND_LEGGINGS, prot, 2), e(Material.DIAMOND_BOOTS, prot, 2, Enchantment.PROTECTION_FALL, 3),
                        i(Material.GOLDEN_APPLE, 4)));
        pages.get(3).add(new Kit("Diamond Feast", ChatColor.GOLD, Material.ORANGE_SHULKER_BOX, 4000,
                "Premium food that heals and fills.")
                .add(i(Material.COOKED_BEEF, 64), i(Material.GOLDEN_CARROT, 64), i(Material.GOLDEN_APPLE, 10),
                        i(Material.PUMPKIN_PIE, 32), i(Material.CAKE, 2), i(Material.COOKED_CHICKEN, 32),
                        i(Material.GRILLED_PORK, 32), i(Material.COOKED_FISH, 32),
                        potion(Material.POTION, PotionType.REGEN, true, false, 2)));

        // ---- Page 5: Legendary ----
        pages.get(4).add(new Kit("Legendary Warrior", ChatColor.RED, Material.RED_SHULKER_BOX, 18000,
                "Maxed mending armor, a god-tier blade, mace (with 64 Wind Bursts) and spear.")
                .add(e(Material.DIAMOND_HELMET, prot, 4, unb, 3, mend, 1, Enchantment.WATER_WORKER, 1, Enchantment.OXYGEN, 3),
                        e(Material.DIAMOND_CHESTPLATE, prot, 4, unb, 3, mend, 1, Enchantment.THORNS, 2),
                        e(Material.DIAMOND_LEGGINGS, prot, 4, unb, 3, mend, 1),
                        e(Material.DIAMOND_BOOTS, prot, 4, unb, 3, mend, 1, Enchantment.PROTECTION_FALL, 4,
                                Enchantment.DEPTH_STRIDER, 3),
                        e(Material.DIAMOND_SWORD, sharp, 5, unb, 3, mend, 1, Enchantment.FIRE_ASPECT, 2,
                                Enchantment.LOOT_BONUS_MOBS, 3, Enchantment.SWEEPING_EDGE, 3),
                        e(Material.SHIELD, unb, 3, mend, 1),
                        e(Material.BOW, power, 5, unb, 3, mend, 1, Enchantment.ARROW_KNOCKBACK, 2),
                        mace(sharp, 5, unb, 3, mend, 1, Enchantment.FIRE_ASPECT, 2), wind(64),
                        spear(sharp, 5, unb, 3, Enchantment.FIRE_ASPECT, 2),
                        i(Material.ARROW, 64), data(Material.GOLDEN_APPLE, 8, 1), i(Material.TOTEM, 2),
                        i(Material.ENDER_PEARL, 16), i(Material.COOKED_BEEF, 64)));
        pages.get(4).add(new Kit("Legendary Miner", ChatColor.GREEN, Material.LIME_SHULKER_BOX, 15000,
                "Efficiency V tools with Fortune III and Mending.")
                .add(e(Material.DIAMOND_PICKAXE, eff, 5, fort, 3, unb, 3, mend, 1),
                        e(Material.DIAMOND_AXE, eff, 5, unb, 3, mend, 1, fort, 3),
                        e(Material.DIAMOND_SPADE, eff, 5, unb, 3, mend, 1),
                        e(Material.DIAMOND_PICKAXE, eff, 5, Enchantment.SILK_TOUCH, 1, unb, 3, mend, 1),
                        e(Material.DIAMOND_HELMET, prot, 3, unb, 3, mend, 1, Enchantment.WATER_WORKER, 1),
                        potion(Material.POTION, PotionType.NIGHT_VISION, true, false, 3),
                        i(Material.TORCH, 64), i(Material.COOKED_BEEF, 64), data(Material.GOLDEN_APPLE, 4, 1),
                        i(Material.WATER_BUCKET), i(Material.LAVA_BUCKET), i(Material.TNT, 32),
                        i(Material.DIAMOND_BLOCK, 4)));
        pages.get(4).add(new Kit("Legendary Hunter", ChatColor.DARK_GREEN, Material.BLACK_SHULKER_BOX, 15000,
                "Looting III, Power V and enchanted golden apples.")
                .add(e(Material.DIAMOND_SWORD, sharp, 5, Enchantment.LOOT_BONUS_MOBS, 3, unb, 3, mend, 1,
                        Enchantment.KNOCKBACK, 2),
                        e(Material.BOW, power, 5, Enchantment.ARROW_INFINITE, 1, Enchantment.ARROW_FIRE, 1,
                                Enchantment.ARROW_KNOCKBACK, 2),
                        i(Material.ARROW, 1), i(Material.SPECTRAL_ARROW, 64),
                        e(Material.DIAMOND_CHESTPLATE, prot, 4, unb, 3, mend, 1),
                        e(Material.DIAMOND_LEGGINGS, prot, 4, unb, 3, mend, 1),
                        e(Material.DIAMOND_BOOTS, prot, 4, unb, 3, mend, 1, Enchantment.PROTECTION_FALL, 4),
                        e(Material.DIAMOND_HELMET, prot, 4, unb, 3, mend, 1),
                        data(Material.GOLDEN_APPLE, 6, 1), i(Material.TOTEM, 1),
                        potion(Material.SPLASH_POTION, PotionType.INSTANT_HEAL, false, true, 6),
                        potion(Material.POTION, PotionType.STRENGTH, false, true, 2)));
        pages.get(4).add(new Kit("Ultimate Survival", ChatColor.LIGHT_PURPLE, Material.PURPLE_SHULKER_BOX, 30000,
                "The best of everything: elytra, totems, god gear, mace and spear.")
                .add(e(Material.ELYTRA, unb, 3, mend, 1), rocket(64, 3),
                        e(Material.DIAMOND_HELMET, prot, 4, unb, 3, mend, 1, Enchantment.WATER_WORKER, 1, Enchantment.OXYGEN, 3),
                        e(Material.DIAMOND_CHESTPLATE, prot, 4, unb, 3, mend, 1),
                        e(Material.DIAMOND_LEGGINGS, prot, 4, unb, 3, mend, 1),
                        e(Material.DIAMOND_BOOTS, prot, 4, unb, 3, mend, 1, Enchantment.PROTECTION_FALL, 4),
                        e(Material.DIAMOND_SWORD, sharp, 5, unb, 3, mend, 1, Enchantment.FIRE_ASPECT, 2,
                                Enchantment.LOOT_BONUS_MOBS, 3),
                        e(Material.DIAMOND_PICKAXE, eff, 5, fort, 3, unb, 3, mend, 1),
                        mace(sharp, 5, unb, 3, mend, 1, Enchantment.FIRE_ASPECT, 2), wind(64),
                        spear(sharp, 5, unb, 3, Enchantment.FIRE_ASPECT, 2),
                        e(Material.BOW, power, 5, unb, 3, mend, 1, Enchantment.ARROW_INFINITE, 1),
                        i(Material.ARROW, 1), data(Material.GOLDEN_APPLE, 16, 1), i(Material.TOTEM, 4),
                        i(Material.ENDER_PEARL, 16), i(Material.COOKED_BEEF, 64), i(Material.EXP_BOTTLE, 64),
                        i(Material.DIAMOND_BLOCK, 8), i(Material.EMERALD_BLOCK, 4), i(Material.GOLD_BLOCK, 4)));
    }

    // ------------------------------------------------------------- building

    private ItemStack buildBox(Kit kit) {
        ItemStack box = new ItemStack(kit.box);
        BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
        ShulkerBox state = (ShulkerBox) meta.getBlockState();
        for (ItemStack item : kit.items) {
            state.getInventory().addItem(item.clone());
        }
        meta.setBlockState(state);
        meta.setDisplayName(kit.color + "" + ChatColor.BOLD + kit.name + " Kit");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + kit.blurb);
        meta.setLore(lore);
        box.setItemMeta(meta);
        return box;
    }

    private static String describe(ItemStack stack) {
        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.GRAY).append("- ").append(ChatColor.WHITE).append(stack.getAmount()).append("x ");
        if (stack.getType() == Material.ENCHANTED_BOOK) {
            sb.append("Enchanted Book");
        } else if (stack.getType() == Material.INK_SACK) {
            sb.append(stack.getDurability() == 15 ? "bone meal" : stack.getDurability() == 4 ? "lapis lazuli"
                    : "dye");
        } else if (stack.getType() == Material.GOLDEN_APPLE && stack.getDurability() == 1) {
            sb.append("enchanted golden apple");
        } else {
            sb.append(stack.getType().name().toLowerCase().replace('_', ' '));
        }
        if (stack.getType() == Material.POTION || stack.getType() == Material.SPLASH_POTION) {
            PotionMeta pm = (PotionMeta) stack.getItemMeta();
            PotionData pd = pm.getBasePotionData();
            sb.append(ChatColor.AQUA).append(" (").append(pd.getType().name().toLowerCase().replace('_', ' '))
                    .append(pd.isUpgraded() ? " II" : "").append(pd.isExtended() ? " long" : "").append(")");
        }
        Map<Enchantment, Integer> enchants = stack.getType() == Material.ENCHANTED_BOOK
                ? ((EnchantmentStorageMeta) stack.getItemMeta()).getStoredEnchants() : stack.getEnchantments();
        if (!enchants.isEmpty()) {
            sb.append(ChatColor.LIGHT_PURPLE).append(" (").append(enchants.size()).append(" enchant")
                    .append(enchants.size() == 1 ? "" : "s").append(")");
        }
        return sb.toString();
    }

    private ItemStack display(Kit kit) {
        ItemStack stack = buildBox(kit);
        ItemMeta meta = stack.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + kit.blurb);
        lore.add("");
        lore.add(ChatColor.YELLOW + "Price: " + ChatColor.GOLD + plugin.money(kit.price));
        lore.add("");
        lore.add(ChatColor.GRAY + "Contains " + kit.items.size() + " stacks:");
        int shown = 0;
        for (ItemStack item : kit.items) {
            if (shown++ >= 8) {
                lore.add(ChatColor.GRAY + "...and " + (kit.items.size() - 8) + " more (right-click to preview)");
                break;
            }
            lore.add(describe(item));
        }
        lore.add("");
        lore.add(ChatColor.GREEN + "Left-click to buy");
        lore.add(ChatColor.AQUA + "Right-click to preview");
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack named(Material m, int data, String name, String... lore) {
        ItemStack s = new ItemStack(m, 1, (short) data);
        ItemMeta meta = s.getItemMeta();
        meta.setDisplayName(name);
        List<String> l = new ArrayList<>();
        for (String line : lore) {
            l.add(line);
        }
        meta.setLore(l);
        s.setItemMeta(meta);
        return s;
    }

    // ------------------------------------------------------------------- UI

    boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can open the shop.");
            return true;
        }
        int page = 1;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                page = -1;
            }
            if (page < 1 || page > PAGES) {
                sender.sendMessage(ChatColor.RED + "Usage: /shop <1-" + PAGES + ">");
                return true;
            }
        }
        open((Player) sender, page);
        return true;
    }

    void open(Player player, int page) {
        Inventory inv = Bukkit.createInventory(new KitHolder(page, null), 27,
                ChatColor.DARK_GRAY + "Kit Shop: " + PAGE_NAMES[page - 1] + " (" + page + "/" + PAGES + ")");
        ItemStack filler = named(Material.STAINED_GLASS_PANE, PAGE_PANES[page - 1], " ");
        for (int slot = 0; slot < 27; slot++) {
            inv.setItem(slot, filler);
        }
        List<Kit> kits = pages.get(page - 1);
        for (int k = 0; k < kits.size() && k < KIT_SLOTS.length; k++) {
            inv.setItem(KIT_SLOTS[k], display(kits.get(k)));
        }
        if (page > 1) {
            inv.setItem(PREV, named(Material.ARROW, 0, ChatColor.YELLOW + "< Page " + (page - 1),
                    ChatColor.GRAY + PAGE_NAMES[page - 2]));
        }
        if (page < PAGES) {
            inv.setItem(NEXT, named(Material.ARROW, 0, ChatColor.YELLOW + "Page " + (page + 1) + " >",
                    ChatColor.GRAY + PAGE_NAMES[page]));
        }
        inv.setItem(INFO, named(Material.GOLD_INGOT, 0, ChatColor.GOLD + "Your balance: "
                + plugin.money(plugin.getBalance(player.getUniqueId())),
                ChatColor.GRAY + "Pricier kits hold better gear."));
        player.openInventory(inv);
    }

    private void openPreview(Player player, int page, Kit kit) {
        Inventory inv = Bukkit.createInventory(new KitHolder(page, kit), 36,
                ChatColor.DARK_GRAY + kit.name + " Kit (preview)");
        for (ItemStack item : kit.items) {
            inv.addItem(item.clone());
        }
        for (int slot = 27; slot < 36; slot++) {
            inv.setItem(slot, named(Material.STAINED_GLASS_PANE, 15, " "));
        }
        inv.setItem(BACK, named(Material.BARRIER, 0, ChatColor.RED + "Back to shop"));
        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof KitHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player) || event.getClickedInventory() != top) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        KitHolder holder = (KitHolder) top.getHolder();
        int slot = event.getSlot();
        if (holder.preview != null) {
            if (slot == BACK) {
                open(player, holder.page);
            }
            return;
        }
        if (slot == PREV && holder.page > 1) {
            open(player, holder.page - 1);
            return;
        }
        if (slot == NEXT && holder.page < PAGES) {
            open(player, holder.page + 1);
            return;
        }
        List<Kit> kits = pages.get(holder.page - 1);
        for (int k = 0; k < kits.size() && k < KIT_SLOTS.length; k++) {
            if (KIT_SLOTS[k] != slot) {
                continue;
            }
            Kit kit = kits.get(k);
            if (event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT) {
                openPreview(player, holder.page, kit);
            } else {
                buy(player, holder.page, kit);
            }
            return;
        }
    }

    private void buy(Player player, int page, Kit kit) {
        long balance = plugin.getBalance(player.getUniqueId());
        if (balance < kit.price) {
            player.sendMessage(ChatColor.RED + "You need " + plugin.money(kit.price - balance) + " more.");
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(ChatColor.RED + "Your inventory is full.");
            return;
        }
        if (!plugin.charge(player.getUniqueId(), kit.price)) {
            return;
        }
        player.getInventory().addItem(buildBox(kit));
        player.sendMessage(ChatColor.GREEN + "Bought the " + kit.color + kit.name + " Kit" + ChatColor.GREEN
                + " for " + plugin.money(kit.price) + ". Balance: "
                + plugin.money(plugin.getBalance(player.getUniqueId())) + ". Place the shulker box to open it.");
        open(player, page);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof KitHolder) {
            event.setCancelled(true);
        }
    }
}
