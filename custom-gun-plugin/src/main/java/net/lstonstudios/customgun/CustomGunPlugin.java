package net.lstonstudios.customgun;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public final class CustomGunPlugin extends JavaPlugin implements Listener {

    private enum Gun {
        PISTOL("pistol", Material.GOLD_HOE, ChatColor.GRAY, "Pistol", "Reliable and accurate.", 6.0, 1, 0.0, 50, 60, 0),
        SMG("smg", Material.GOLD_HOE, ChatColor.YELLOW, "SMG", "Rapid fire, low damage.", 3.0, 1, 0.05, 40, 3, 0),
        SHOTGUN("shotgun", Material.GOLD_HOE, ChatColor.GOLD, "Shotgun", "Six pellets, deadly up close.", 4.0, 6, 0.12, 20, 100, 0),
        SNIPER("sniper", Material.GOLD_HOE, ChatColor.AQUA, "Sniper", "One long-range, high-damage shot.", 18.0, 1, 0.0, 120, 160, 0),
        ROCKET("rocket", Material.GOLD_HOE, ChatColor.RED, "Rocket Launcher", "Explodes where it lands, without breaking blocks.", 0.0, 1, 0.0, 70, 200, 3),
        REVOLVER("revolver", Material.GOLD_HOE, ChatColor.DARK_RED, "Revolver", "Heavy rounds, steady aim.", 9.0, 1, 0.0, 60, 80, 0),
        RIFLE("rifle", Material.GOLD_HOE, ChatColor.GREEN, "Assault Rifle", "Fast, accurate bursts.", 4.0, 1, 0.02, 60, 5, 0),
        MINIGUN("minigun", Material.GOLD_HOE, ChatColor.DARK_GRAY, "Minigun", "A storm of weak, wild bullets.", 2.0, 1, 0.1, 35, 2, 0),
        FREEZE("freeze", Material.GOLD_HOE, ChatColor.AQUA, "Freeze Ray", "Chills targets with heavy slowness.", 3.0, 1, 0.0, 40, 100, 0),
        THUNDER("thunder", Material.GOLD_HOE, ChatColor.LIGHT_PURPLE, "Thunder Gun", "Calls lightning down on your target.", 8.0, 1, 0.0, 50, 160, 0),
        RAILGUN("railgun", Material.GOLD_HOE, ChatColor.DARK_PURPLE, "Railgun", "Pierces through every target in line.", 14.0, 1, 0.0, 100, 240, 0),
        DERRINGER("derringer", ChatColor.WHITE, "Derringer", "Tiny, quick, short range.", 4.0, 1, 0.0, 25, 30, 0, Particle.VILLAGER_HAPPY, Sound.ENTITY_ARROW_SHOOT, 2.0f, null),
        MACHINE_PISTOL("machinepistol", ChatColor.YELLOW, "Machine Pistol", "Fast and loose.", 2.5, 1, 0.08, 30, 4, 0, Particle.NOTE, Sound.ENTITY_SKELETON_SHOOT, 2.0f, null),
        HUNTING_RIFLE("huntingrifle", ChatColor.DARK_GREEN, "Hunting Rifle", "Accurate and powerful.", 10.0, 1, 0.0, 80, 120, 0, Particle.TOWN_AURA, Sound.ENTITY_ARROW_SHOOT, 0.7f, null),
        POISON("poison", ChatColor.GREEN, "Poison Dart Gun", "Poisons whoever it hits.", 2.0, 1, 0.0, 45, 100, 0, Particle.SLIME, Sound.ENTITY_SPIDER_AMBIENT, 1.8f, "poison"),
        BLINDER("blinder", ChatColor.DARK_GRAY, "Blinder", "Blinds and slows whoever it hits.", 2.0, 1, 0.0, 40, 120, 0, Particle.SMOKE_LARGE, Sound.ENTITY_BAT_TAKEOFF, 0.6f, "blind"),
        GRAVITY("gravity", ChatColor.BLUE, "Gravity Gun", "Yanks the target toward you.", 3.0, 1, 0.0, 40, 120, 0, Particle.ENCHANTMENT_TABLE, Sound.ENTITY_ENDERMEN_TELEPORT, 1.5f, "pull"),
        KNOCKBACK("knockback", ChatColor.GOLD, "Knockback Cannon", "Blasts the target away.", 3.0, 1, 0.0, 35, 140, 0, Particle.EXPLOSION_NORMAL, Sound.ENTITY_IRONGOLEM_ATTACK, 0.8f, "knock"),
        LASER("laser", ChatColor.RED, "Laser", "Fast, straight and precise.", 5.0, 1, 0.0, 70, 6, 0, Particle.REDSTONE, Sound.BLOCK_NOTE_PLING, 2.0f, null),
        PLASMA("plasma", ChatColor.LIGHT_PURPLE, "Plasma Rifle", "A slow, hard-hitting plasma bolt.", 8.0, 1, 0.0, 60, 40, 0, Particle.SPELL_WITCH, Sound.ENTITY_BLAZE_SHOOT, 0.5f, null),
        GRENADE("grenade", ChatColor.DARK_GREEN, "Grenade Launcher", "A small blast where it lands.", 0.0, 1, 0.0, 50, 140, 2, Particle.SMOKE_NORMAL, Sound.ENTITY_SNOWBALL_THROW, 0.5f, null),
        WITHER("wither", ChatColor.BLACK, "Wither Gun", "Withers whoever it hits.", 6.0, 1, 0.0, 50, 160, 0, Particle.SPELL_MOB, Sound.ENTITY_WITHER_SHOOT, 1.2f, "wither"),
        VOID("void", ChatColor.DARK_PURPLE, "Void Gun", "Withers and slows its target.", 8.0, 1, 0.0, 55, 200, 0, Particle.SUSPENDED_DEPTH, Sound.ENTITY_ENDERMEN_SCREAM, 0.6f, "void"),
        DRAGON("dragon", ChatColor.DARK_RED, "Dragon Cannon", "A huge blast in a dragon's breath.", 0.0, 1, 0.0, 70, 260, 4, Particle.DRAGON_BREATH, Sound.ENTITY_ENDERDRAGON_GROWL, 0.8f, null),
        ANTIMATERIAL("antimaterial", ChatColor.AQUA, "Anti-Material Rifle", "Enormous damage from very far.", 28.0, 1, 0.0, 140, 300, 0, Particle.CRIT_MAGIC, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, null),
        NUKE("nuke", ChatColor.DARK_RED, "Mini Nuke Launcher", "A massive blast. Stand back.", 0.0, 1, 0.0, 90, 500, 6, Particle.CLOUD, Sound.ENTITY_WITHER_SPAWN, 1.0f, null),
        OMEGA("omega", ChatColor.LIGHT_PURPLE, "Omega Cannon", "Pierces every target for huge damage.", 20.0, 1, 0.0, 120, 400, 0, Particle.TOTEM, Sound.ENTITY_ENDERDRAGON_DEATH, 1.5f, null);

        final String key;
        final Material material;
        final ChatColor color;
        final String name;
        final String description;
        final double damage;
        final int pellets;
        final double spread;
        final int range;
        final int cooldownTicks;
        final float explosion;
        final Particle trail;
        final Sound sound;
        final float pitch;
        final String effect;

        Gun(String key, ChatColor color, String name, String description, double damage, int pellets,
                double spread, int range, int cooldownTicks, int explosion, Particle trail, Sound sound,
                float pitch, String effect) {
            this(key.toLowerCase(), Material.GOLD_HOE, color, name, description, damage, pellets, spread, range,
                    cooldownTicks, explosion, trail, sound, pitch, effect);
        }

        Gun(String key, Material material, ChatColor color, String name, String description, double damage,
                int pellets, double spread, int range, int cooldownTicks, int explosion) {
            this(key, material, color, name, description, damage, pellets, spread, range, cooldownTicks,
                    explosion, null, null, 1.0f, null);
        }

        Gun(String key, Material material, ChatColor color, String name, String description, double damage,
                int pellets, double spread, int range, int cooldownTicks, int explosion, Particle trail,
                Sound sound, float pitch, String effect) {
            this.key = key;
            this.trail = trail;
            this.sound = sound;
            this.pitch = pitch;
            this.effect = effect;
            this.material = material;
            this.color = color;
            this.name = name;
            this.description = description;
            this.damage = damage;
            this.pellets = pellets;
            this.spread = spread;
            this.range = range;
            this.cooldownTicks = cooldownTicks;
            this.explosion = explosion;
        }

        static Gun fromKey(String key) {
            for (Gun gun : values()) {
                if (gun.key.equalsIgnoreCase(key)) {
                    return gun;
                }
            }
            return null;
        }
    }

    private static final String MARKER_PREFIX = ChatColor.BLACK + "CustomGun:";
    private final Map<String, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("cgun").setExecutor(this);
    }

    private ItemStack createGun(Gun gun) {
        ItemStack stack = new ItemStack(gun.material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(gun.color + "" + ChatColor.BOLD + "\u2726 " + gun.name + " \u2726");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + gun.description);
        lore.add("");
        if (gun.explosion > 0) {
            lore.add(ChatColor.RED + "Damage: " + ChatColor.WHITE + "explosion (size " + (int) gun.explosion + ")");
        } else {
            String total = gun.pellets > 1 ? " x" + gun.pellets + " pellets" : "";
            lore.add(ChatColor.RED + "Damage: " + ChatColor.WHITE + trim(gun.damage / 2) + " hearts" + total);
        }
        lore.add(ChatColor.AQUA + "Range: " + ChatColor.WHITE + gun.range + " blocks");
        lore.add(ChatColor.GREEN + "Reload: " + ChatColor.WHITE + trim(gun.cooldownTicks / 20.0) + "s");
        lore.add("");
        lore.add(ChatColor.YELLOW + "Right-click to shoot.");
        lore.add(MARKER_PREFIX + gun.key);
        meta.setLore(lore);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.DURABILITY, 3, true);
        meta.addItemFlags(ItemFlag.values());
        meta.setUnbreakable(true);
        stack.setItemMeta(meta);
        return stack;
    }

    private static String keyList() {
        StringBuilder out = new StringBuilder();
        for (Gun gun : Gun.values()) {
            out.append(out.length() > 0 ? ", " : "").append(gun.key);
        }
        return out.toString();
    }

    private static String trim(double value) {
        return value == Math.floor(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    private Gun gunOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta() || !stack.getItemMeta().hasLore()) {
            return null;
        }
        List<String> lore = stack.getItemMeta().getLore();
        String last = lore.get(lore.size() - 1);
        return last.startsWith(MARKER_PREFIX) ? Gun.fromKey(last.substring(MARKER_PREFIX.length())) : null;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if ((event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Gun gun = gunOf(event.getItem());
        if (gun == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        String id = player.getUniqueId() + ":" + gun.key;
        long now = System.currentTimeMillis();
        Long ready = cooldowns.get(id);
        if (ready != null && now < ready) {
            if (gun != Gun.SMG) {
                player.sendMessage(ChatColor.RED + gun.name + " is on cooldown for " + ((ready - now + 999) / 1000) + "s.");
            }
            return;
        }
        cooldowns.put(id, now + gun.cooldownTicks * 50L);
        fire(player, gun);
    }

    private void fire(Player player, Gun gun) {
        World world = player.getWorld();
        playFireSound(player, gun);
        for (int i = 0; i < gun.pellets; i++) {
            Vector direction = player.getEyeLocation().getDirection().clone();
            if (gun.spread > 0) {
                direction.add(new Vector((Math.random() - 0.5) * 2 * gun.spread, (Math.random() - 0.5) * 2 * gun.spread,
                        (Math.random() - 0.5) * 2 * gun.spread));
            }
            shoot(player, gun, direction.normalize());
        }
    }

    private void playFireSound(Player player, Gun gun) {
        Location at = player.getLocation();
        World world = player.getWorld();
        if (gun.sound != null) {
            world.playSound(at, gun.sound, 0.9f, gun.pitch);
            return;
        }
        switch (gun) {
            case PISTOL:
                world.playSound(at, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 2.0f);
                break;
            case SMG:
                world.playSound(at, Sound.BLOCK_DISPENSER_LAUNCH, 0.7f, 2.0f);
                break;
            case SHOTGUN:
                world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.6f);
                world.playSound(at, Sound.ENTITY_IRONGOLEM_ATTACK, 1.0f, 0.5f);
                break;
            case SNIPER:
                world.playSound(at, Sound.ENTITY_LIGHTNING_THUNDER, 0.5f, 2.0f);
                world.playSound(at, Sound.ENTITY_ARROW_SHOOT, 1.0f, 0.5f);
                break;
            case REVOLVER:
                world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 2.0f);
                world.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.4f, 2.0f);
                break;
            case RIFLE:
                world.playSound(at, Sound.ENTITY_SKELETON_SHOOT, 1.0f, 1.5f);
                break;
            case MINIGUN:
                world.playSound(at, Sound.BLOCK_PISTON_CONTRACT, 0.6f, 2.0f);
                break;
            case FREEZE:
                world.playSound(at, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.5f);
                world.playSound(at, Sound.ENTITY_SNOWBALL_THROW, 1.0f, 0.8f);
                break;
            case THUNDER:
                world.playSound(at, Sound.ENTITY_LIGHTNING_IMPACT, 0.5f, 2.0f);
                break;
            case RAILGUN:
                world.playSound(at, Sound.ENTITY_ENDERDRAGON_SHOOT, 0.8f, 1.5f);
                world.playSound(at, Sound.ENTITY_WITHER_SHOOT, 0.7f, 1.8f);
                break;
            default:
                world.playSound(at, Sound.ENTITY_FIREWORK_LAUNCH, 1.0f, 0.6f);
                world.playSound(at, Sound.ENTITY_GHAST_SHOOT, 0.8f, 0.6f);
                break;
        }
    }

    // Steps along the shot until it hits a solid block or a living entity.
    private void shoot(Player shooter, Gun gun, Vector direction) {
        World world = shooter.getWorld();
        Location point = shooter.getEyeLocation();
        List<Entity> nearby = shooter.getNearbyEntities(gun.range, gun.range, gun.range);
        java.util.Set<Entity> pierced = new java.util.HashSet<>();
        for (double travelled = 0.5; travelled <= gun.range; travelled += 0.5) {
            point.add(direction.clone().multiply(0.5));
            if (point.getBlock().getType().isSolid()) {
                impact(shooter, gun, point.clone().subtract(direction.clone().multiply(0.5)), null);
                return;
            }
            trail(world, gun, point, travelled);
            for (Entity entity : nearby) {
                if (!(entity instanceof LivingEntity) || entity.isDead() || entity.equals(shooter)) {
                    continue;
                }
                Location base = entity.getLocation();
                double height = entity instanceof Player ? 1.9 : Math.max(0.6, entity.getHeight());
                if (Math.abs(base.getX() - point.getX()) < 0.55 && Math.abs(base.getZ() - point.getZ()) < 0.55
                        && point.getY() >= base.getY() - 0.1 && point.getY() <= base.getY() + height) {
                    if (gun == Gun.RAILGUN || gun == Gun.OMEGA) {
                        if (pierced.add(entity)) {
                            impact(shooter, gun, point, (LivingEntity) entity);
                        }
                        continue;
                    }
                    impact(shooter, gun, point, (LivingEntity) entity);
                    return;
                }
            }
        }
        if (gun.explosion > 0) {
            impact(shooter, gun, point, null);
        }
    }

    // Every gun has its own trail. Only the sniper uses fire.
    private void trail(World world, Gun gun, Location point, double travelled) {
        if (gun.trail != null) {
            world.spawnParticle(gun.trail, point, 1, 0.02, 0.02, 0.02, 0.01);
            return;
        }
        switch (gun) {
            case PISTOL:
                world.spawnParticle(Particle.CRIT, point, 1, 0, 0, 0, 0);
                break;
            case SMG:
                if (travelled % 2 == 0) {
                    world.spawnParticle(Particle.SMOKE_NORMAL, point, 1, 0, 0, 0, 0);
                }
                break;
            case SHOTGUN:
                world.spawnParticle(Particle.CLOUD, point, 1, 0.02, 0.02, 0.02, 0.01);
                break;
            case SNIPER:
                world.spawnParticle(Particle.FLAME, point, 2, 0, 0, 0, 0);
                world.spawnParticle(Particle.CRIT_MAGIC, point, 1, 0, 0, 0, 0);
                break;
            case ROCKET:
                world.spawnParticle(Particle.SMOKE_LARGE, point, 1, 0.05, 0.05, 0.05, 0.01);
                break;
            case REVOLVER:
                world.spawnParticle(Particle.SPELL_INSTANT, point, 1, 0, 0, 0, 0);
                break;
            case RIFLE:
                world.spawnParticle(Particle.FIREWORKS_SPARK, point, 1, 0, 0, 0, 0);
                break;
            case MINIGUN:
                if (travelled % 2 == 0) {
                    world.spawnParticle(Particle.SPELL, point, 1, 0, 0, 0, 0);
                }
                break;
            case FREEZE:
                world.spawnParticle(Particle.SNOW_SHOVEL, point, 2, 0.03, 0.03, 0.03, 0.01);
                break;
            case THUNDER:
                world.spawnParticle(Particle.END_ROD, point, 1, 0.02, 0.02, 0.02, 0.01);
                break;
            default:
                world.spawnParticle(Particle.PORTAL, point, 3, 0.05, 0.05, 0.05, 0.2);
                break;
        }
    }

    private void impact(Player shooter, Gun gun, Location where, LivingEntity target) {
        World world = where.getWorld();
        if (gun.explosion > 0) {
            world.spawnParticle(Particle.EXPLOSION_LARGE, where, gun.explosion >= 4 ? 4 : 1, 0.5, 0.5, 0.5, 0);
            world.createExplosion(where.getX(), where.getY(), where.getZ(), gun.explosion, false, false);
            return;
        }
        switch (gun) {
            case FREEZE:
                world.spawnParticle(Particle.SNOWBALL, where, 12, 0.2, 0.2, 0.2, 0.05);
                break;
            case THUNDER:
                world.spawnParticle(Particle.END_ROD, where, 12, 0.2, 0.2, 0.2, 0.1);
                world.strikeLightningEffect(where);
                break;
            case RAILGUN:
                world.spawnParticle(Particle.PORTAL, where, 20, 0.2, 0.2, 0.2, 0.5);
                break;
            case SHOTGUN:
                world.spawnParticle(Particle.CLOUD, where, 3, 0.05, 0.05, 0.05, 0.02);
                break;
            default:
                world.spawnParticle(Particle.FIREWORKS_SPARK, where, 6, 0.1, 0.1, 0.1, 0.05);
                world.spawnParticle(Particle.SMOKE_NORMAL, where, 4, 0.05, 0.05, 0.05, 0.01);
                break;
        }
        if (target != null) {
            target.setNoDamageTicks(0);
            target.damage(gun.damage, shooter);
            if (gun.effect != null) {
                applyEffect(shooter, gun, target);
            }
            if (gun == Gun.FREEZE) {
                target.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.SLOW, 100, 3));
            }
        }
    }

    private void applyEffect(Player shooter, Gun gun, LivingEntity target) {
        org.bukkit.potion.PotionEffectType slow = org.bukkit.potion.PotionEffectType.SLOW;
        switch (gun.effect) {
            case "poison":
                target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.POISON, 100, 1));
                break;
            case "wither":
                target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WITHER, 100, 1));
                break;
            case "void":
                target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WITHER, 100, 1));
                target.addPotionEffect(new org.bukkit.potion.PotionEffect(slow, 100, 2));
                break;
            case "blind":
                target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS, 100, 0));
                target.addPotionEffect(new org.bukkit.potion.PotionEffect(slow, 100, 1));
                break;
            case "knock":
                target.setVelocity(shooter.getEyeLocation().getDirection().multiply(2.5).setY(0.7));
                break;
            case "pull":
                target.setVelocity(shooter.getLocation().toVector().subtract(target.getLocation().toVector())
                        .normalize().multiply(1.6).setY(0.4));
                break;
            default:
                break;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            return false;
        }
        List<Player> targets = new ArrayList<>();
        String selector = args[0].toLowerCase(Locale.ROOT);
        if (selector.equals("@a")) {
            targets.addAll(Bukkit.getOnlinePlayers());
        } else if (selector.equals("@s") || selector.equals("@p")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Specify a player name.");
                return true;
            }
            targets.add((Player) sender);
        } else {
            Player player = Bukkit.getPlayerExact(args[0]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "That player must be online.");
                return true;
            }
            targets.add(player);
        }
        List<Gun> guns = new ArrayList<>();
        if (args[1].equalsIgnoreCase("all")) {
            for (Gun gun : Gun.values()) {
                guns.add(gun);
            }
        } else {
            Gun gun = Gun.fromKey(args[1]);
            if (gun == null) {
                sender.sendMessage(ChatColor.RED + "Guns: " + keyList() + ", all");
                return true;
            }
            guns.add(gun);
        }
        int amount = 1;
        if (args.length > 2) {
            try {
                amount = Math.max(1, Math.min(10, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        for (Player target : targets) {
            for (Gun gun : guns) {
                for (int i = 0; i < amount; i++) {
                    for (ItemStack leftover : target.getInventory().addItem(createGun(gun)).values()) {
                        target.getWorld().dropItemNaturally(target.getLocation(), leftover);
                    }
                }
            }
        }
        sender.sendMessage(ChatColor.GREEN + "Gave " + (guns.size() > 1 ? "all guns" : guns.get(0).name) + " x" + amount
                + " to " + targets.size() + " player(s).");
        return true;
    }
}
