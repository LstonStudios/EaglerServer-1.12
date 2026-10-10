package net.lstonstudios.customtnt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.material.MaterialData;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public final class CustomTNTPlugin extends JavaPlugin implements Listener {

    private static final String TNT_METADATA_KEY = "net.lstonstudios.customtnt.type";
    private static final String ITEM_MARKER_PREFIX = ChatColor.BLACK + "CustomTNT:";
    private final Map<TNTType, ExplosionSettings> explosionSettings = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        loadExplosionSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("customtnt").setExecutor(this);
    }

    private void loadExplosionSettings() {
        Map<TNTType, ExplosionSettings> loadedSettings = new HashMap<>();
        for (TNTType type : TNTType.values()) {
            String path = "explosions." + type.key + ".";
            String powerPath = path + "power";
            Object configuredPower = getConfig().get(powerPath);
            if (!(configuredPower instanceof Number)) {
                throw new IllegalArgumentException("Invalid or missing explosion power: " + powerPath);
            }
            double power = ((Number) configuredPower).doubleValue();
            if (Double.isNaN(power) || Double.isInfinite(power) || power < 0.0 || power > 16.0) {
                throw new IllegalArgumentException("Explosion power must be between 0.0 and 16.0: " + powerPath);
            }
            String firePath = path + "fire";
            if (!getConfig().isBoolean(firePath)) {
                throw new IllegalArgumentException("Invalid or missing fire setting: " + firePath);
            }
            String sizePath = path + "size";
            Object configuredSize = getConfig().get(sizePath);
            if (!(configuredSize instanceof Number)) {
                throw new IllegalArgumentException("Invalid or missing effect size: " + sizePath);
            }
            int size = ((Number) configuredSize).intValue();
            if (size < 1 || size > 64) {
                throw new IllegalArgumentException("Effect size must be between 1 and 64: " + sizePath);
            }
            loadedSettings.put(type, new ExplosionSettings((float) power, getConfig().getBoolean(firePath), size));
        }
        explosionSettings.clear();
        explosionSettings.putAll(loadedSettings);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTNTPlace(BlockPlaceEvent event) {
        TNTType type = getTNTType(event.getItemInHand());
        if (type == null || type.icon != Material.TNT) {
            return;
        }

        event.setCancelled(true);
        Block placedBlock = event.getBlockPlaced();
        placedBlock.setType(Material.AIR);
        if (primeCustomTNT(placedBlock.getLocation().add(0.5, 0.0, 0.5), type)
                && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            consumeItem(event.getPlayer(), event.getHand());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCustomTNTInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() == null) {
            return;
        }

        TNTType type = getTNTType(event.getItem());
        if (type == null || type.icon == Material.TNT) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.OFF_HAND
                && getTNTType(event.getPlayer().getInventory().getItemInMainHand()) != null) {
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null || event.getBlockFace() == null) {
            return;
        }
        Location spawnLocation;
        spawnLocation = event.getClickedBlock().getRelative(event.getBlockFace())
                .getLocation().add(0.5, 0.0, 0.5);
        if (primeCustomTNT(spawnLocation, type) && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            consumeItem(event.getPlayer(), event.getHand());
        }
    }

    private boolean primeCustomTNT(Location location, TNTType type) {
        Entity spawned = location.getWorld().spawnEntity(location, EntityType.PRIMED_TNT);
        if (!(spawned instanceof TNTPrimed)) {
            getLogger().severe("Could not spawn custom TNT entity for " + type.key + ".");
            return false;
        }

        TNTPrimed tnt = (TNTPrimed) spawned;
        tnt.setMetadata(TNT_METADATA_KEY, new FixedMetadataValue(this, type.key));
        startFuseEffects(tnt, type);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTNTExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof TNTPrimed) || !entity.hasMetadata(TNT_METADATA_KEY)) {
            return;
        }

        String typeKey = entity.getMetadata(TNT_METADATA_KEY).get(0).asString();
        TNTType type = TNTType.fromKey(typeKey);
        entity.removeMetadata(TNT_METADATA_KEY, this);
        if (type == null) {
            getLogger().warning("Ignoring custom TNT with an unknown type: " + typeKey);
            return;
        }

        event.setCancelled(true);
        ExplosionSettings settings = explosionSettings.get(type);
        Location center = event.getLocation();
        spawnBlockBurst(center, type.effectBlock, 100, 2.5, 1.5, 2.5);
        if (type == TNTType.SLIME) {
            center.getWorld().playSound(center, Sound.BLOCK_SLIME_PLACE, 2.0F, 0.7F);
            animateShockwave(center, settings.size, Particle.SLIME, Particle.VILLAGER_HAPPY);
            spawnBurst(center, Particle.SLIME, 80, 2.2, 1.2, 2.2);
            return;
        }

        World world = center.getWorld();

        if (type == TNTType.MINING) {
            digMiningTunnel(center, Math.max(4, Math.min(settings.size, 10)));
            world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5F, 0.6F);
            spawnBurst(center, Particle.CRIT, 45, 1.5, 1.0, 1.5);
            spawnBurst(center, Particle.FIREWORKS_SPARK, 70, 2.0, 1.0, 2.0);
            animateShockwave(center, settings.size, Particle.CRIT, Particle.FIREWORKS_SPARK);
            return;
        }

        world.createExplosion(center.getX(), center.getY(), center.getZ(), settings.power, settings.fire, true);

        if (type == TNTType.NUKE) {
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 4.0F, 0.5F);
            animateShockwave(center, settings.size, Particle.EXPLOSION_HUGE, Particle.FIREWORKS_SPARK);
            spawnBurst(center, Particle.FIREWORKS_SPARK, 120, 3.0, 1.5, 3.0);
        } else if (type == TNTType.FIRE) {
            world.playSound(center, Sound.ITEM_FIRECHARGE_USE, 3.0F, 0.7F);
            spawnBurst(center, Particle.FLAME, 80, 2.0, 1.0, 2.0);
            spawnBurst(center, Particle.LAVA, 20, 1.5, 0.8, 1.5);
            spawnBurst(center, Particle.SMOKE_LARGE, 40, 1.8, 1.2, 1.8);
            animateShockwave(center, settings.size, Particle.FLAME, Particle.SMOKE_LARGE);
        } else if (type == TNTType.BLACK_HOLE) {
            pullEntitiesIn(center, settings.size / 2.0);
            world.playSound(center, Sound.BLOCK_PORTAL_TRAVEL, 3.0F, 0.4F);
            animateShockwave(center, settings.size, Particle.PORTAL, Particle.SMOKE_LARGE);
            spawnBurst(center, Particle.PORTAL, 120, 3.0, 1.5, 3.0);
            spawnBurst(center, Particle.SUSPENDED_DEPTH, 80, 2.0, 1.0, 2.0);
        } else if (type == TNTType.X_RAY) {
            mineXRayArea(center, 10);
            world.playSound(center, Sound.BLOCK_STONE_BREAK, 1.8F, 0.7F);
            spawnBurst(center, Particle.CRIT, 70, 2.6, 1.2, 2.6);
            animateShockwave(center, settings.size, Particle.CRIT, Particle.SPELL_INSTANT);
        }
    }

    private void startFuseEffects(final TNTPrimed tnt, final TNTType type) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (tnt.isDead() || !tnt.isValid()) {
                    cancel();
                    return;
                }
                Particle particle = type == TNTType.FIRE ? Particle.FLAME
                        : type == TNTType.SLIME ? Particle.SLIME
                        : type == TNTType.BLACK_HOLE ? Particle.PORTAL
                        : type == TNTType.MINING ? Particle.CRIT : Particle.SMOKE_LARGE;
                tnt.getWorld().spawnParticle(particle, tnt.getLocation().add(0.0, 0.35, 0.0),
                        3, 0.12, 0.12, 0.12, 0.01);
                tnt.getWorld().spawnParticle(Particle.BLOCK_CRACK, tnt.getLocation().add(0.0, 0.25, 0.0),
                        6, 0.18, 0.18, 0.18, new MaterialData(type.effectBlock));
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    private void spawnBlockBurst(Location center, Material material, int count,
            double offsetX, double offsetY, double offsetZ) {
        center.getWorld().spawnParticle(Particle.BLOCK_CRACK, center, count,
                offsetX, offsetY, offsetZ, new MaterialData(material));
    }

    private void digMiningTunnel(final Location center, final int depth) {
        final World world = center.getWorld();
        final List<Block> targets = new ArrayList<>();
        final int cx = center.getBlockX();
        final int cy = center.getBlockY();
        final int cz = center.getBlockZ();

        int[][] directions = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        for (int[] direction : directions) {
            int dirX = direction[0];
            int dirZ = direction[1];
            for (int step = 0; step <= depth; step++) {
                for (int offsetY = -1; offsetY <= 1; offsetY++) {
                    for (int side = -1; side <= 1; side++) {
                        int x = cx + (dirX == 0 ? side : dirX * step);
                        int y = cy + offsetY;
                        int z = cz + (dirZ == 0 ? side : dirZ * step);
                        Block block = world.getBlockAt(x, y, z);
                        Material material = block.getType();
                        if (material == Material.AIR || material == Material.BEDROCK
                                || material == Material.BARRIER || material == Material.WATER
                                || material == Material.LAVA || block.getState() instanceof InventoryHolder) {
                            continue;
                        }
                        targets.add(block);
                    }
                }
            }
        }

        if (targets.isEmpty()) {
            return;
        }

        final int[] index = {0};
        new BukkitRunnable() {
            @Override
            public void run() {
                int processed = 0;
                while (processed < 18 && index[0] < targets.size()) {
                    Block block = targets.get(index[0]++);
                    Material material = block.getType();
                    if (material == Material.AIR || material == Material.BEDROCK || material == Material.BARRIER) {
                        continue;
                    }
                    block.setType(Material.AIR);
                    world.spawnParticle(Particle.BLOCK_CRACK, block.getLocation().add(0.5, 0.5, 0.5), 8,
                            0.2, 0.2, 0.2, 0.02, new MaterialData(material));
                    processed++;
                }
                if (index[0] >= targets.size()) {
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private void mineXRayArea(final Location center, final int radius) {
        final World world = center.getWorld();
        final List<Block> targets = new ArrayList<>();
        final int cx = center.getBlockX();
        final int cy = center.getBlockY();
        final int cz = center.getBlockZ();
        final int half = radius / 2;

        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                for (int y = cy - 2; y <= cy + 2; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material material = block.getType();
                    if (material == Material.AIR || material == Material.BEDROCK
                            || material == Material.BARRIER || material == Material.WATER
                            || material == Material.LAVA || block.getState() instanceof InventoryHolder
                            || isOre(material)) {
                        continue;
                    }
                    targets.add(block);
                }
            }
        }

        if (targets.isEmpty()) {
            return;
        }

        final int[] index = {0};
        new BukkitRunnable() {
            @Override
            public void run() {
                int processed = 0;
                while (processed < 30 && index[0] < targets.size()) {
                    Block block = targets.get(index[0]++);
                    Material material = block.getType();
                    if (material == Material.AIR || material == Material.BEDROCK || material == Material.BARRIER
                            || isOre(material)) {
                        continue;
                    }
                    block.setType(Material.AIR);
                    world.spawnParticle(Particle.BLOCK_CRACK, block.getLocation().add(0.5, 0.5, 0.5), 8,
                            0.2, 0.2, 0.2, 0.02, new MaterialData(material));
                    processed++;
                }
                if (index[0] >= targets.size()) {
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private boolean isOre(Material material) {
        switch (material) {
            case COAL_ORE:
            case DIAMOND_ORE:
            case EMERALD_ORE:
            case GOLD_ORE:
            case IRON_ORE:
            case LAPIS_ORE:
            case REDSTONE_ORE:
            case GLOWING_REDSTONE_ORE:
            case QUARTZ_ORE:
                return true;
            default:
                return false;
        }
    }

    private void pullEntitiesIn(Location center, double radius) {
        double radiusSquared = radius * radius;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity.getLocation().distanceSquared(center) > radiusSquared) {
                continue;
            }
            if (entity instanceof LivingEntity) {
                LivingEntity living = (LivingEntity) entity;
                living.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0));
                living.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 100, 1));
                living.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0));
            }
            Vector pull = center.toVector().subtract(entity.getLocation().toVector());
            if (pull.lengthSquared() > 0.01) {
                entity.setVelocity(pull.normalize().multiply(1.2).setY(0.25));
            }
        }
    }

    private void animateShockwave(final Location center, int size, final Particle primary, final Particle secondary) {
        final double radius = size / 2.0;
        final int steps = Math.max(16, size * 4);
        new BukkitRunnable() {
            private int frame;

            @Override
            public void run() {
                frame++;
                double frameRadius = radius * frame / 10.0;
                World world = center.getWorld();
                for (int i = 0; i < steps; i++) {
                    double angle = Math.PI * 2.0 * i / steps;
                    double x = Math.cos(angle) * frameRadius;
                    double z = Math.sin(angle) * frameRadius;
                    world.spawnParticle(primary, center.clone().add(x, 0.5, z), 1, 0.0, 0.0, 0.0, 0.0);
                    if (i % 4 == 0) {
                        world.spawnParticle(secondary, center.clone().add(x, 0.5, z), 1,
                                0.0, 0.0, 0.0, 0.0);
                    }
                }
                if (frame >= 10) {
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private void spawnBurst(Location center, Particle particle, int count, double x, double y, double z) {
        center.getWorld().spawnParticle(particle, center, count, x, y, z, 0.05);
    }

    private void consumeItem(Player player, EquipmentSlot hand) {
        ItemStack item = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (item.getAmount() <= 1) {
            if (hand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(null);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private TNTType getTNTType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore()) {
            return null;
        }
        for (String line : meta.getLore()) {
            if (line.startsWith(ITEM_MARKER_PREFIX)) {
                return TNTType.fromKey(line.substring(ITEM_MARKER_PREFIX.length()));
            }
        }
        return null;
    }

    private ItemStack createTNTItem(TNTType type, int amount) {
        ItemStack item = new ItemStack(type.icon, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(type.displayName);
        meta.setLore(Collections.singletonList(ITEM_MARKER_PREFIX + type.key));
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("customtnt.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage custom TNT.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            try {
                loadExplosionSettings();
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Custom TNT configuration was not reloaded: " + exception.getMessage());
                sender.sendMessage(ChatColor.RED + "Custom TNT configuration is invalid; previous settings remain active.");
                return true;
            }
            sender.sendMessage(ChatColor.GREEN + "Custom TNT configuration reloaded.");
            return true;
        }
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sendUsage(sender, label);
            return true;
        }

        TNTType type = TNTType.fromKey(args[2]);
        if (type == null) {
            sender.sendMessage(ChatColor.RED + "Unknown TNT type. Choose nuke, mining, fire, slime, x-ray, or black-hole.");
            return true;
        }
        List<Player> targets;
        try {
            targets = resolveTargets(sender, args[1]);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(ChatColor.RED + exception.getMessage());
            return true;
        }
        if (targets.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No online players matched " + args[1] + ".");
            return true;
        }
        int amount = 1;
        if (args.length > 4) {
            sendUsage(sender, label);
            return true;
        }
        if (args.length == 4) {
            try {
                amount = Integer.parseInt(args[3]);
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
            Map<Integer, ItemStack> leftovers = target.getInventory().addItem(createTNTItem(type, amount));
            for (ItemStack leftover : leftovers.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), leftover);
            }
            target.sendMessage(ChatColor.GREEN + "You received " + amount + " " + type.displayName + ChatColor.GREEN + ".");
        }
        sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " " + type.displayName + ChatColor.GREEN
                + " to " + targets.size() + (targets.size() == 1 ? " player." : " players."));
        return true;
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
        sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label
                + " give <player|@a|@s|@p> <nuke|mining|fire|slime|x-ray|black-hole> [amount]");
        sender.sendMessage(ChatColor.YELLOW + "       /" + label + " reload");
    }

    private enum TNTType {
        NUKE("nuke", ChatColor.DARK_RED + "Nuke TNT", Material.TNT, Material.REDSTONE_BLOCK),
        MINING("mining", ChatColor.GRAY + "Mining TNT", Material.GOLD_INGOT, Material.GOLD_BLOCK),
        FIRE("fire", ChatColor.RED + "Fire TNT", Material.FIREBALL, Material.NETHERRACK),
        SLIME("slime", ChatColor.GREEN + "Slime TNT", Material.SLIME_BALL, Material.SLIME_BLOCK),
        X_RAY("x-ray", ChatColor.BLUE + "X-Ray TNT", Material.DIAMOND_ORE, Material.DIAMOND_BLOCK),
        BLACK_HOLE("black-hole", ChatColor.DARK_PURPLE + "Black Hole TNT",
                Material.ENDER_PEARL, Material.OBSIDIAN);

        private final String key;
        private final String displayName;
        private final Material icon;
        private final Material effectBlock;

        TNTType(String key, String displayName, Material icon, Material effectBlock) {
            this.key = key;
            this.displayName = displayName;
            this.icon = icon;
            this.effectBlock = effectBlock;
        }

        private static TNTType fromKey(String key) {
            for (TNTType type : values()) {
                if (type.key.equalsIgnoreCase(key)
                        || type.key.replace("-", "").equalsIgnoreCase(key.replace("-", ""))) {
                    return type;
                }
            }
            return null;
        }
    }

    private static final class ExplosionSettings {
        private final float power;
        private final boolean fire;
        private final int size;

        private ExplosionSettings(float power, boolean fire, int size) {
            this.power = power;
            this.fire = fire;
            this.size = size;
        }
    }
}
