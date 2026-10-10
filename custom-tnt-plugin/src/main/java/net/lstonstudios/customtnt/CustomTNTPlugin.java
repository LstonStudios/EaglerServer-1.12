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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
            if (Double.isNaN(power) || Double.isInfinite(power) || power < 0.0 || power > 8.0) {
                throw new IllegalArgumentException("Explosion power must be between 0.0 and 8.0: " + powerPath);
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
            if (size < 1 || size > 32) {
                throw new IllegalArgumentException("Effect size must be between 1 and 32: " + sizePath);
            }
            loadedSettings.put(type, new ExplosionSettings((float) power, getConfig().getBoolean(firePath), size));
        }
        explosionSettings.clear();
        explosionSettings.putAll(loadedSettings);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTNTPlace(BlockPlaceEvent event) {
        TNTType type = getTNTType(event.getItemInHand());
        if (type == null) {
            return;
        }

        event.setCancelled(true);
        Block placedBlock = event.getBlockPlaced();
        placedBlock.setType(Material.AIR);
        Entity spawned = event.getBlock().getWorld().spawnEntity(
                placedBlock.getLocation().add(0.5, 0.0, 0.5), EntityType.PRIMED_TNT);
        if (!(spawned instanceof TNTPrimed)) {
            getLogger().severe("Could not spawn custom TNT entity for " + type.key + ".");
            return;
        }

        TNTPrimed tnt = (TNTPrimed) spawned;
        tnt.setMetadata(TNT_METADATA_KEY, new FixedMetadataValue(this, type.key));
        startFuseEffects(tnt, type);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            consumeItem(event.getPlayer(), event.getHand());
        }
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
        if (type == TNTType.SLIME) {
            convertSurfaceToSlime(center, settings.size);
            center.getWorld().playSound(center, Sound.BLOCK_SLIME_PLACE, 2.0F, 0.7F);
            return;
        }

        World world = center.getWorld();
        world.createExplosion(center.getX(), center.getY(), center.getZ(), settings.power, settings.fire, true);

        if (type == TNTType.NUKE) {
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 4.0F, 0.5F);
            animateShockwave(center, settings.size, Particle.EXPLOSION_HUGE, Particle.FIREWORKS_SPARK);
        } else if (type == TNTType.FIRE) {
            world.playSound(center, Sound.ITEM_FIRECHARGE_USE, 3.0F, 0.7F);
            spawnBurst(center, Particle.FLAME, 80, 2.0, 1.0, 2.0);
            spawnBurst(center, Particle.LAVA, 20, 1.5, 0.8, 1.5);
            spawnBurst(center, Particle.SMOKE_LARGE, 40, 1.8, 1.2, 1.8);
        } else if (type == TNTType.BLACK_HOLE) {
            pullEntitiesIn(center, settings.size / 2.0);
            world.playSound(center, Sound.BLOCK_PORTAL_TRAVEL, 3.0F, 0.4F);
            animateShockwave(center, settings.size, Particle.PORTAL, Particle.SMOKE_LARGE);
        } else if (type == TNTType.MINING) {
            spawnBurst(center, Particle.CRIT, 45, 1.5, 1.0, 1.5);
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
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    private void convertSurfaceToSlime(Location center, int size) {
        World world = center.getWorld();
        int startX = center.getBlockX() - size / 2;
        int startZ = center.getBlockZ() - size / 2;
        for (int x = startX; x < startX + size; x++) {
            for (int z = startZ; z < startZ + size; z++) {
                int y = world.getHighestBlockYAt(x, z);
                Block block = world.getBlockAt(x, y, z);
                Material material = block.getType();
                if (!material.isSolid() || material == Material.BEDROCK || material == Material.BARRIER
                        || material == Material.SLIME_BLOCK || block.getState() instanceof InventoryHolder) {
                    continue;
                }
                BlockState newState = block.getState();
                newState.setType(Material.SLIME_BLOCK);
                BlockFormEvent formEvent = new BlockFormEvent(block, newState);
                Bukkit.getPluginManager().callEvent(formEvent);
                if (!formEvent.isCancelled()) {
                    formEvent.getNewState().update(true, false);
                }
            }
        }
        animateShockwave(center, size, Particle.SLIME, Particle.VILLAGER_HAPPY);
    }

    private void pullEntitiesIn(Location center, double radius) {
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
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
            sender.sendMessage(ChatColor.RED + "Unknown TNT type. Choose nuke, mining, fire, slime, or black-hole.");
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
                + " give <player|@a|@s|@p> <nuke|mining|fire|slime|black-hole> [amount]");
        sender.sendMessage(ChatColor.YELLOW + "       /" + label + " reload");
    }

    private enum TNTType {
        NUKE("nuke", ChatColor.DARK_RED + "Nuke TNT", Material.TNT),
        MINING("mining", ChatColor.GOLD + "Mining TNT", Material.GOLD_INGOT),
        FIRE("fire", ChatColor.RED + "Fire TNT", Material.FIREBALL),
        SLIME("slime", ChatColor.GREEN + "Slime TNT", Material.SLIME_BALL),
        BLACK_HOLE("black-hole", ChatColor.DARK_PURPLE + "Black Hole TNT", Material.ENDER_PEARL);

        private final String key;
        private final String displayName;
        private final Material icon;

        TNTType(String key, String displayName, Material icon) {
            this.key = key;
            this.displayName = displayName;
            this.icon = icon;
        }

        private static TNTType fromKey(String key) {
            for (TNTType type : values()) {
                if (type.key.equalsIgnoreCase(key)) {
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
