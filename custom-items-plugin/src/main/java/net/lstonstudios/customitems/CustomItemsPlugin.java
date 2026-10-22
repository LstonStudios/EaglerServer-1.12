package net.lstonstudios.customitems;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.material.MaterialData;
import org.bukkit.material.Button;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class CustomItemsPlugin extends JavaPlugin implements Listener {

    private static final String PASS_MARKER = "CustomItems:one-time-redstone-pass";
    private static final String PASS_BUTTON_MARKER = "CustomItems:one-time-redstone-button";

    private enum SwordType {
        LIGHTNING("lightning", Material.GOLD_SWORD, ChatColor.YELLOW, ChatColor.WHITE, "Lightning", "Strikes lightning on whoever you hit."),
        EXPLOSION("explosion", Material.IRON_SWORD, ChatColor.RED, ChatColor.GOLD, "Explosion", "Explodes where you hit without breaking blocks."),
        PEARL("pearl", Material.DIAMOND_SWORD, ChatColor.DARK_PURPLE, ChatColor.AQUA, "Pearl", "Teleports you to whoever you hit."),
        FALL("fall", Material.STONE_SWORD, ChatColor.DARK_GREEN, ChatColor.GREEN, "Fall", "Launches whoever you hit 8 blocks into the air."),
        BAD("bad", Material.WOOD_SWORD, ChatColor.DARK_GRAY, ChatColor.DARK_RED, "Bad", "Gives whoever you hit every bad effect for 3 seconds."),
        TP("tp", Material.DIAMOND_AXE, ChatColor.LIGHT_PURPLE, ChatColor.WHITE, "TP", "Launches whoever you hit 8 blocks straight up. They take 5 damage and a slam when they land.");

        final String key;
        final Material material;
        final ChatColor first;
        final ChatColor second;
        final String name;
        final String description;
        final String marker;

        SwordType(String key, Material material, ChatColor first, ChatColor second, String name, String description) {
            this.key = key;
            this.material = material;
            this.first = first;
            this.second = second;
            this.name = name;
            this.description = description;
            this.marker = "CustomItems:" + key;
        }

        String displayName() {
            return first + "" + ChatColor.BOLD + "\u2726 " + name + " " + second + ChatColor.BOLD + "Sword " + first + ChatColor.BOLD + "\u2726";
        }

        static SwordType fromKey(String key) {
            for (SwordType type : values()) {
                if (type.key.equalsIgnoreCase(key)) {
                    return type;
                }
            }
            return null;
        }
    }

    private final Set<UUID> slamming = new HashSet<>();
    private final Set<String> specialButtons = new HashSet<>();
    private final Map<Location, BukkitTask> activeButtonPulses = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("customsword").setExecutor(this);
        getCommand("pass").setExecutor(this);
        getCommand("passbutton").setExecutor(this);
        specialButtons.addAll(getConfig().getStringList("special-buttons"));
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
            refreshItems(online);
        }
    }

    @Override
    public void onDisable() {
        for (Map.Entry<Location, BukkitTask> entry : activeButtonPulses.entrySet()) {
            entry.getValue().cancel();
            Block block = entry.getKey().getBlock();
            if (isButton(block.getType())) {
                setButtonPowered(block, false);
            }
        }
        activeButtonPulses.clear();
    }

    private void refreshItems(org.bukkit.entity.Player player) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack old = inv.getItem(i);
            if (old == null) {
                continue;
            }
            SwordType type = getSwordType(old);
            if (type != null) {
                ItemStack fresh = createSword(type);
                fresh.setAmount(old.getAmount());
                inv.setItem(i, fresh);
            }
        }
    }

    @EventHandler
    public void onJoinRefresh(org.bukkit.event.player.PlayerJoinEvent event) {
        refreshItems(event.getPlayer());
    }

    private ItemStack createSword(SwordType type) {
        ItemStack item = new ItemStack(type.material, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(type.displayName());
        List<String> lore = new ArrayList<String>();
        lore.add(type.first + "" + ChatColor.BOLD + "Ability: " + type.second + type.name);
        StringBuilder cur = new StringBuilder();
        for (String word : type.description.split(" ")) {
            if (cur.length() > 0 && cur.length() + 1 + word.length() > 14) {
                lore.add(ChatColor.GRAY + cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append(' ');
            }
            cur.append(word);
        }
        if (cur.length() > 0) {
            lore.add(ChatColor.GRAY + cur.toString());
        }
        int last = lore.size() - 1;
        lore.set(last, lore.get(last) + hide(type.marker));
        meta.setLore(lore);
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        item.setItemMeta(meta);
        return item;
    }

    private static String hide(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            sb.append('\u00a7').append(c);
        }
        return sb.toString();
    }

    private static boolean containsHidden(List<String> lore, String marker) {
        String hidden = hide(marker);
        for (String line : lore) {
            if (line.endsWith(hidden)) {
                return true;
            }
        }
        return false;
    }

    private SwordType getSwordType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        List<String> lore = item.getItemMeta().getLore();
        if (lore == null) {
            return null;
        }
        for (SwordType type : SwordType.values()) {
            if (lore.contains(ChatColor.BLACK + type.marker) || containsHidden(lore, type.marker)) {
                return type;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        Player attacker = (Player) event.getDamager();
        Entity victim = event.getEntity();
        SwordType type = getSwordType(attacker.getInventory().getItemInMainHand());
        if (type == null || victim.equals(attacker)) {
            return;
        }
        Location location = victim.getLocation();
        switch (type) {
            case LIGHTNING:
                location.getWorld().strikeLightning(location);
                location.getWorld().spawnParticle(Particle.CRIT_MAGIC, location.clone().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.1);
                break;
            case EXPLOSION:
                location.getWorld().createExplosion(location.getX(), location.getY(), location.getZ(), 3.0F, false, false);
                break;
            case PEARL:
                Location from = attacker.getLocation();
                from.getWorld().playSound(from, Sound.ENTITY_ENDERMEN_TELEPORT, 1.0F, 1.0F);
                Location destination = location.clone();
                destination.setYaw(from.getYaw());
                destination.setPitch(from.getPitch());
                attacker.teleport(destination);
                destination.getWorld().playSound(destination, Sound.ENTITY_ENDERMEN_TELEPORT, 1.0F, 1.0F);
                destination.getWorld().spawnParticle(Particle.PORTAL, destination.clone().add(0, 1, 0), 60, 0.5, 1.0, 0.5, 0.5);
                break;
            case FALL:
                liftVictim(victim);
                break;
            case TP:
                tpStrike(victim, location);
                break;
            case BAD:
                curse((LivingEntity) victim);
                break;
        }
    }

    private void curse(LivingEntity victim) {
        PotionEffectType[] debuffs = {
            PotionEffectType.SLOW, PotionEffectType.SLOW_DIGGING, PotionEffectType.CONFUSION,
            PotionEffectType.BLINDNESS, PotionEffectType.HUNGER, PotionEffectType.WEAKNESS,
            PotionEffectType.LEVITATION,
            PotionEffectType.UNLUCK, PotionEffectType.HARM
        };
        for (PotionEffectType debuff : debuffs) {
            victim.addPotionEffect(new PotionEffect(debuff, 60, 0), true);
        }
        Location location = victim.getLocation();
        location.getWorld().playSound(location, Sound.ENTITY_WITHER_SPAWN, 0.5F, 1.5F);
        location.getWorld().spawnParticle(Particle.SMOKE_LARGE, location.clone().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.02);
    }

    private void tpStrike(Entity victim, Location origin) {
        if (liftFrom(victim, origin)) {
            trackLanding((LivingEntity) victim);
        }
    }

    private void trackLanding(final LivingEntity victim) {
        slamming.add(victim.getUniqueId());
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ticks++;
                if (victim.isDead() || !victim.isValid() || ticks > 400) {
                    slamming.remove(victim.getUniqueId());
                    cancel();
                    return;
                }
                if (ticks > 5 && victim.isOnGround()) {
                    slamming.remove(victim.getUniqueId());
                    victim.setFallDistance(0.0F);
                    victim.damage(5.0);
                    slamEffects(victim.getLocation());
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void slamEffects(final Location feet) {
        final World world = feet.getWorld();
        Block ground = feet.clone().add(0, -0.5, 0).getBlock();
        Location center = feet.clone().add(0, 0.1, 0);
        ring(world, center, 0.8);
        getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                ring(world, feet.clone().add(0, 0.1, 0), 1.6);
            }
        }, 3L);
        if (ground.getType() != Material.AIR) {
            world.spawnParticle(Particle.BLOCK_CRACK, center, 60, 0.4, 0.1, 0.4, 0.15, new MaterialData(ground.getType(), ground.getData()));
        }
        world.spawnParticle(Particle.EXPLOSION_LARGE, center, 1, 0, 0, 0, 0);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6F, 1.6F);
        if (ground.getType() != Material.AIR) {
            world.playSound(center, Sound.BLOCK_STONE_BREAK, 1.0F, 0.8F);
        }
    }

    private void ring(World world, Location center, double radius) {
        for (int i = 0; i < 24; i++) {
            double angle = 2 * Math.PI * i / 24;
            Location point = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            world.spawnParticle(Particle.REDSTONE, point, 0, 0.001, 0.35, 1.0, 1.0);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && slamming.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private void liftVictim(Entity victim) {
        liftFrom(victim, victim.getLocation());
    }

    private boolean liftFrom(Entity victim, Location start) {
        int height = 0;
        while (height < 8 && start.getY() + height + 1 < 255
                && start.clone().add(0, height + 1, 0).getBlock().getType().isTransparent()
                && start.clone().add(0, height + 2, 0).getBlock().getType().isTransparent()) {
            height++;
        }
        if (height == 0) {
            return false;
        }
        Location destination = start.clone().add(0, height, 0);
        destination.setYaw(victim.getLocation().getYaw());
        destination.setPitch(victim.getLocation().getPitch());
        victim.teleport(destination);
        victim.setFallDistance(0.0F);
        destination.getWorld().playSound(destination, Sound.ENTITY_FIREWORK_LAUNCH, 1.0F, 1.0F);
        destination.getWorld().spawnParticle(Particle.CLOUD, start.clone().add(0, 1, 0), 40, 0.4, 0.5, 0.4, 0.1);
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("customitems.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage custom items.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("pass")) {
            return givePasses(sender, label, args);
        }
        if (command.getName().equalsIgnoreCase("passbutton")) {
            return givePassButtons(sender, label, args);
        }
        if (args.length < 2 || args.length > 3) {
            sendUsage(sender, label);
            return true;
        }
        boolean all = args[1].equalsIgnoreCase("all");
        SwordType type = all ? null : SwordType.fromKey(args[1]);
        if (!all && type == null) {
            sender.sendMessage(ChatColor.RED + "Unknown item. Choose lightning, explosion, pearl, fall, bad, tp, or all.");
            return true;
        }
        List<Player> targets;
        try {
            targets = resolveTargets(sender, args[0]);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(ChatColor.RED + exception.getMessage());
            return true;
        }
        if (targets.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No online players matched " + args[0] + ".");
            return true;
        }
        int amount = 1;
        if (args.length == 3) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(ChatColor.RED + "Amount must be a number from 1 to 64.");
                return true;
            }
        }
        if (amount < 1 || amount > 64) {
            sender.sendMessage(ChatColor.RED + "Amount must be from 1 to 64.");
            return true;
        }
        SwordType[] given = all ? SwordType.values() : new SwordType[] {type};
        for (Player target : targets) {
            for (SwordType each : given) {
                ItemStack sword = createSword(each);
                for (int i = 0; i < amount; i++) {
                    Map<Integer, ItemStack> leftovers = target.getInventory().addItem(sword.clone());
                    for (ItemStack leftover : leftovers.values()) {
                        target.getWorld().dropItemNaturally(target.getLocation(), leftover);
                    }
                }
            }
            String what = all ? "every custom sword" : type.displayName();
            target.sendMessage(ChatColor.GREEN + "You received " + amount + " " + what + ChatColor.GREEN
                    + (all || amount == 1 ? "." : "s."));
        }
        String what = all ? "every custom sword" : type.displayName();
        sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " " + what + ChatColor.GREEN + (all || amount == 1 ? "" : "s")
                + " to " + targets.size() + (targets.size() == 1 ? " player." : " players."));
        return true;
    }

    private boolean givePasses(CommandSender sender, String label, String[] args) {
        if (args.length < 1 || args.length > 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <player|@a|@s|@p> [amount]");
            return true;
        }

        List<Player> targets;
        try {
            targets = resolveTargets(sender, args[0]);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(ChatColor.RED + exception.getMessage());
            return true;
        }
        if (targets.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No online players matched " + args[0] + ".");
            return true;
        }

        int amount = 1;
        if (args.length == 2) {
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(ChatColor.RED + "Amount must be a number from 1 to 64.");
                return true;
            }
        }
        if (amount < 1 || amount > 64) {
            sender.sendMessage(ChatColor.RED + "Amount must be from 1 to 64.");
            return true;
        }

        for (Player target : targets) {
            ItemStack passes = createPass(amount);
            Map<Integer, ItemStack> leftovers = target.getInventory().addItem(passes);
            for (ItemStack leftover : leftovers.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), leftover);
            }
            target.sendMessage(ChatColor.GREEN + "You received " + amount + " redstone pass"
                    + (amount == 1 ? "." : "es."));
        }
        sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " redstone pass"
                + (amount == 1 ? "" : "es") + " to " + targets.size()
                + (targets.size() == 1 ? " player." : " players."));
        return true;
    }

    private boolean givePassButtons(CommandSender sender, String label, String[] args) {
        if (args.length < 1 || args.length > 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <player|@a|@s|@p> [amount]");
            return true;
        }

        List<Player> targets;
        try {
            targets = resolveTargets(sender, args[0]);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(ChatColor.RED + exception.getMessage());
            return true;
        }
        if (targets.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No online players matched " + args[0] + ".");
            return true;
        }

        int amount = 1;
        if (args.length == 2) {
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(ChatColor.RED + "Amount must be a number from 1 to 64.");
                return true;
            }
        }
        if (amount < 1 || amount > 64) {
            sender.sendMessage(ChatColor.RED + "Amount must be from 1 to 64.");
            return true;
        }

        for (Player target : targets) {
            Map<Integer, ItemStack> leftovers = target.getInventory().addItem(createPassButton(amount));
            for (ItemStack leftover : leftovers.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), leftover);
            }
            target.sendMessage(ChatColor.GREEN + "You received " + amount + " special pass button"
                    + (amount == 1 ? "." : "s."));
        }
        sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " special pass button"
                + (amount == 1 ? "" : "s") + " to " + targets.size()
                + (targets.size() == 1 ? " player." : " players."));
        return true;
    }

    private ItemStack createPass(int amount) {
        ItemStack item = new ItemStack(Material.PAPER, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "One-Time Redstone Pass");
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "Right-click a stone or wooden button.");
        lore.add(ChatColor.GRAY + "Powers it for 3 seconds, then consumes this pass."
                + hide(PASS_MARKER));
        meta.setLore(lore);
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createPassButton(int amount) {
        ItemStack item = new ItemStack(Material.STONE_BUTTON, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Pass Button");
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "Special button: requires a redstone pass.");
        lore.add(ChatColor.GRAY + "Place it, then right-click it with a pass."
                + hide(PASS_BUTTON_MARKER));
        meta.setLore(lore);
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPassButtonPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!isButton(block.getType()) || !isPassButton(event.getItemInHand())) {
            return;
        }
        specialButtons.add(buttonKey(block.getLocation()));
        saveSpecialButtons();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPassButtonBreak(BlockBreakEvent event) {
        if (specialButtons.remove(buttonKey(event.getBlock().getLocation()))) {
            saveSpecialButtons();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPassUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null || !isButton(event.getClickedBlock().getType())) {
            return;
        }
        ItemStack item = event.getItem();
        if (!specialButtons.contains(buttonKey(event.getClickedBlock().getLocation()))) {
            if (isPass(item)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Passes only work on special pass buttons.");
            }
            return;
        }

        if (!isPass(item)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You need a one-time redstone pass to use this button.");
            return;
        }

        event.setCancelled(true);
        consumeMainHandPass(event.getPlayer());
        pulseButton(event.getClickedBlock());
        event.getPlayer().sendMessage(ChatColor.GREEN + "The button is powered for 3 seconds.");
    }

    private boolean isPassButton(ItemStack item) {
        if (item == null || item.getType() != Material.STONE_BUTTON || !item.hasItemMeta()) {
            return false;
        }
        List<String> lore = item.getItemMeta().getLore();
        return lore != null && containsHidden(lore, PASS_BUTTON_MARKER);
    }

    private boolean isPass(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) {
            return false;
        }
        List<String> lore = item.getItemMeta().getLore();
        return lore != null && containsHidden(lore, PASS_MARKER);
    }

    private void consumeMainHandPass(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
            player.getInventory().setItemInMainHand(item);
        }
    }

    private boolean isButton(Material material) {
        return material == Material.STONE_BUTTON || material == Material.WOOD_BUTTON;
    }

    private String buttonKey(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":"
                + location.getBlockY() + ":" + location.getBlockZ();
    }

    private void saveSpecialButtons() {
        getConfig().set("special-buttons", new ArrayList<String>(specialButtons));
        saveConfig();
    }

    private void pulseButton(final Block block) {
        final Location location = block.getLocation();
        BukkitTask previous = activeButtonPulses.remove(location);
        if (previous != null) {
            previous.cancel();
        }
        setButtonPowered(block, true);

        BukkitTask task = getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                activeButtonPulses.remove(location);
                Block current = location.getBlock();
                if (isButton(current.getType())) {
                    setButtonPowered(current, false);
                }
            }
        }, 60L);
        activeButtonPulses.put(location, task);
    }

    private void setButtonPowered(Block block, boolean powered) {
        MaterialData data = block.getState().getData();
        if (data instanceof Button) {
            Button button = (Button) data;
            button.setPowered(powered);
            block.setData(button.getData(), true);
            notifyRedstone(block, block.getRelative(button.getAttachedFace()));
        }
    }

    // Changing block data does not notify neighbours, so send the same updates vanilla buttons do.
    private void notifyRedstone(Block button, Block attached) {
        try {
            String version = getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> craftWorld = Class.forName("org.bukkit.craftbukkit." + version + ".CraftWorld");
            Class<?> magic = Class.forName("org.bukkit.craftbukkit." + version + ".util.CraftMagicNumbers");
            Class<?> posClass = Class.forName("net.minecraft.server." + version + ".BlockPosition");
            Class<?> nmsBlock = Class.forName("net.minecraft.server." + version + ".Block");
            Object world = craftWorld.getMethod("getHandle").invoke(button.getWorld());
            Object block = magic.getMethod("getBlock", Material.class).invoke(null, button.getType());
            java.lang.reflect.Method apply = world.getClass().getMethod("applyPhysics", posClass, nmsBlock, boolean.class);
            java.lang.reflect.Constructor<?> pos = posClass.getConstructor(int.class, int.class, int.class);
            for (Block target : new Block[] {button, attached}) {
                apply.invoke(world, pos.newInstance(target.getX(), target.getY(), target.getZ()), block, false);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            getLogger().warning("Could not send redstone update for pass button: " + exception);
        }
    }

    private List<Player> resolveTargets(CommandSender sender, String targetName) {
        if (!targetName.startsWith("@")) {
            Player target = getServer().getPlayerExact(targetName);
            if (target == null) {
                throw new IllegalArgumentException("That player must be online.");
            }
            return Collections.singletonList(target);
        }
        if (targetName.equalsIgnoreCase("@a")) {
            return new ArrayList<>(getServer().getOnlinePlayers());
        }
        if (targetName.equalsIgnoreCase("@s")) {
            if (sender instanceof Player) {
                return Collections.singletonList((Player) sender);
            }
            throw new IllegalArgumentException("@s can only be used by a player.");
        }
        if (targetName.equalsIgnoreCase("@p")) {
            if (!(sender instanceof Player)) {
                throw new IllegalArgumentException("@p can only be used by a player.");
            }
            Player nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            Location origin = ((Player) sender).getLocation();
            for (Player player : getServer().getOnlinePlayers()) {
                if (!player.getWorld().equals(origin.getWorld())) {
                    continue;
                }
                double distance = player.getLocation().distanceSquared(origin);
                if (distance < nearestDistance) {
                    nearest = player;
                    nearestDistance = distance;
                }
            }
            return nearest == null ? Collections.<Player>emptyList() : Collections.singletonList(nearest);
        }
        throw new IllegalArgumentException("Supported player selectors are @a, @s, and @p.");
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <player|@a|@s|@p> <lightning|explosion|pearl|fall|bad|tp|all> [amount]");
    }
}
