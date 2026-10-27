package net.lstonstudios.customweapons;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * The Mace: falling onto a target adds smash damage (4 per block for the first 3, 2 for the next 5,
 * 1 beyond), cancels your fall damage and knocks nearby mobs back.
 * The Spear: long-reach jab on left click, and a damaging lunge on right click.
 */
public final class CustomWeaponsPlugin extends JavaPlugin implements Listener, TabExecutor {
    // These markers are duplicated in the Money plugin's kit shop; keep them in sync.
    static final String MACE_MARKER = ChatColor.BLACK + "CustomWeapon:mace";
    static final String SPEAR_MARKER = ChatColor.BLACK + "CustomWeapon:spear";
    static final String WIND_MARKER = ChatColor.BLACK + "CustomWeapon:wind";
    static final Material WIND_MATERIAL = Material.PRISMARINE_CRYSTALS;
    static final Material MACE_MATERIAL = Material.GOLD_SPADE;
    static final Material SPEAR_MATERIAL = Material.GOLD_PICKAXE;
    // While right-click is held the spear is swapped for this item, whose texture is the spear tilted.
    static final Material TILTED_MATERIAL = Material.GOLD_AXE;

    private static final double MACE_BASE = 6.0;
    private static final double SPEAR_BASE = 5.0;
    private static final double SPEAR_REACH = 4.5;
    private static final long JAB_COOLDOWN_MS = 500;
    private static final long LUNGE_COOLDOWN_MS = 3000;

    private final Map<UUID, Long> jabCooldown = new HashMap<>();
    private final Map<UUID, Long> lungeCooldown = new HashMap<>();
    private final Set<UUID> windAirborne = new HashSet<>();
    private final Map<UUID, Long> fallImmunity = new HashMap<>();
    private final Set<UUID> internalDamage = new HashSet<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("weapon").setExecutor(this);
        getCommand("weapon").setTabCompleter(this);
    }

    // ----------------------------------------------------------------- items

    static ItemStack createMace() {
        ItemStack item = new ItemStack(MACE_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Mace");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Fall onto your target for smash damage.",
                ChatColor.GRAY + "The farther you fall, the harder it hits",
                ChatColor.GRAY + "and you take no fall damage.", MACE_MARKER));
        meta.setUnbreakable(true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
        item.setItemMeta(meta);
        return item;
    }

    static ItemStack createSpear() {
        ItemStack item = new ItemStack(SPEAR_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Spear");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Left-click: jab up to 4.5 blocks.",
                ChatColor.GRAY + "Hold right-click, release to dash (3s cooldown)",
                ChatColor.GRAY + "and skewer all. Efficiency = longer dash.", SPEAR_MARKER));
        meta.setUnbreakable(true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
        item.setItemMeta(meta);
        return item;
    }

    static ItemStack createWind() {
        ItemStack item = new ItemStack(WIND_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.WHITE + "" + ChatColor.BOLD + "Wind Burst");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Right-click: launch yourself skyward,",
                ChatColor.GRAY + "then smash down with the Mace.",
                ChatColor.GRAY + "Consumed on use. No fall damage for 4s.", WIND_MARKER));
        item.setItemMeta(meta);
        return item;
    }

    static boolean isWind(ItemStack item) {
        return has(item, WIND_MATERIAL, WIND_MARKER);
    }

    private static boolean has(ItemStack item, Material material, String marker) {
        if (item == null || item.getType() != material || !item.hasItemMeta()) {
            return false;
        }
        List<String> lore = item.getItemMeta().getLore();
        return lore != null && lore.contains(marker);
    }

    static boolean isMace(ItemStack item) {
        return has(item, MACE_MATERIAL, MACE_MARKER);
    }

    static boolean isSpear(ItemStack item) {
        return has(item, SPEAR_MATERIAL, SPEAR_MARKER) || isTilted(item);
    }

    static boolean isTilted(ItemStack item) {
        return has(item, TILTED_MATERIAL, SPEAR_MARKER);
    }

    /** Turns any tilted spear in the stack back into the normal spear. */
    private static void untilt(ItemStack item) {
        if (isTilted(item)) {
            item.setType(SPEAR_MATERIAL);
        }
    }

    private static void untiltAll(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            untilt(item);
        }
        untilt(player.getInventory().getItemInOffHand());
        untilt(player.getItemOnCursor());
    }

    private static double sharpness(ItemStack item) {
        int level = item.getEnchantmentLevel(Enchantment.DAMAGE_ALL);
        return level > 0 ? 0.5 * level + 0.5 : 0;
    }

    private static void burn(ItemStack item, Entity target) {
        int fire = item.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
        if (fire > 0) {
            target.setFireTicks(80 * fire);
        }
    }

    // ---------------------------------------------------------------- combat

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getDamager();
        if (internalDamage.contains(player.getUniqueId())) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (isMace(hand)) {
            maceHit(event, player, hand);
        } else if (isSpear(hand)) {
            double damage = SPEAR_BASE + sharpness(hand);
            if (player.isSprinting()) {
                damage += 3;
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1f, 1f);
            }
            event.setDamage(damage);
            burn(hand, event.getEntity());
        }
    }

    private void maceHit(EntityDamageByEntityEvent event, Player player, ItemStack hand) {
        double damage = MACE_BASE + sharpness(hand);
        float fall = player.getFallDistance();
        boolean smash = fall > 1.5f && !player.isOnGround() && !player.isGliding();
        if (smash) {
            double blocks = fall;
            damage += 4 * Math.min(blocks, 3) + 2 * Math.min(Math.max(blocks - 3, 0), 5) + Math.max(blocks - 8, 0);
            player.setFallDistance(0);
        }
        event.setDamage(damage);
        burn(hand, event.getEntity());
        if (!smash) {
            return;
        }
        Entity target = event.getEntity();
        Location at = target.getLocation();
        at.getWorld().playSound(at, fall > 5 ? Sound.ENTITY_GENERIC_EXPLODE : Sound.ENTITY_IRONGOLEM_ATTACK,
                1.2f, fall > 5 ? 1.4f : 0.7f);
        at.getWorld().spawnParticle(Particle.EXPLOSION_LARGE, at.clone().add(0, 0.5, 0), 1);
        at.getWorld().spawnParticle(Particle.CRIT, at.clone().add(0, 1, 0), 30, 0.6, 0.4, 0.6, 0.3);
        for (Entity near : target.getNearbyEntities(3.5, 2, 3.5)) {
            if (near instanceof LivingEntity && near != player) {
                Vector push = near.getLocation().toVector().subtract(at.toVector()).setY(0);
                if (push.lengthSquared() < 1e-4) {
                    push = new Vector(0, 0, 1);
                }
                near.setVelocity(push.normalize().multiply(0.8).setY(0.35));
            }
        }
        player.sendMessage(ChatColor.GRAY + "Smash! " + String.format(Locale.ROOT, "%.1f", fall) + " blocks.");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        Action action = event.getAction();
        if (isWind(hand)) {
            if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true);
                windBurst(player, hand);
            }
            return;
        }
        if (!isSpear(hand)) {
            return;
        }
        if (action == Action.LEFT_CLICK_AIR) {
            jab(player, hand);
        } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            holdCharge(player);
        }
    }

    private boolean ready(Map<UUID, Long> map, Player player, long cooldownMs) {
        return ready(map, player, cooldownMs, null);
    }

    /** Like above, but tells the player how long is left when {@code name} is given. */
    private boolean ready(Map<UUID, Long> map, Player player, long cooldownMs, String name) {
        long now = System.currentTimeMillis();
        Long last = map.get(player.getUniqueId());
        if (last != null && now - last < cooldownMs) {
            if (name != null) {
                player.sendMessage(ChatColor.RED + name + " is on cooldown for "
                        + ((cooldownMs - (now - last) + 999) / 1000) + "s.");
            }
            return false;
        }
        map.put(player.getUniqueId(), now);
        return true;
    }

    /** The nearest living entity in front of the player within reach, or null. */
    private LivingEntity aimed(Player player, double reach) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : player.getNearbyEntities(reach, reach, reach)) {
            if (!(e instanceof LivingEntity) || e.isDead()) {
                continue;
            }
            Vector to = ((LivingEntity) e).getEyeLocation().subtract(0, 0.4, 0).toVector().subtract(eye.toVector());
            double along = to.dot(dir);
            if (along < 0 || along > reach) {
                continue;
            }
            double off = to.clone().subtract(dir.clone().multiply(along)).length();
            if (off <= 0.9 && along < bestDist) {
                bestDist = along;
                best = (LivingEntity) e;
            }
        }
        return best;
    }

    private void jab(Player player, ItemStack spear) {
        if (!ready(jabCooldown, player, JAB_COOLDOWN_MS)) {
            return;
        }
        LivingEntity target = aimed(player, SPEAR_REACH);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.4f);
        if (target == null) {
            return;
        }
        double damage = SPEAR_BASE + sharpness(spear) + (player.isSprinting() ? 3 : 0);
        hurt(player, target, damage);
        burn(spear, target);
        Vector push = target.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
        if (push.lengthSquared() > 1e-4) {
            target.setVelocity(push.normalize().multiply(0.4).setY(0.15));
        }
        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.2);
    }

    private void hurt(Player attacker, LivingEntity target, double damage) {
        internalDamage.add(attacker.getUniqueId());
        try {
            target.damage(damage, attacker);
        } finally {
            internalDamage.remove(attacker.getUniqueId());
        }
    }

    private void lunge(final Player player, final ItemStack spear) {
        if (!ready(lungeCooldown, player, LUNGE_COOLDOWN_MS, "Dash")) {
            return;
        }
        // Efficiency lengthens the dash: a stronger push plus extra thrust ticks.
        final int level = Math.min(5, spear.getEnchantmentLevel(Enchantment.DIG_SPEED));
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Vector flat = dir.clone().setY(0);
        if (flat.lengthSquared() < 1e-4) {
            flat = dir.clone();
        }
        final Vector heading = flat.normalize();
        player.setVelocity(heading.clone().multiply(1.5 + 0.25 * level).setY(Math.max(0.2, dir.getY() * 0.8)));
        fallImmunity.put(player.getUniqueId(), System.currentTimeMillis() + 2500L + level * 400L);
        Location start = player.getLocation();
        start.getWorld().playSound(start, Sound.ENTITY_ENDERDRAGON_FLAP, 1f, 1.6f);
        start.getWorld().playSound(start, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.6f);
        start.getWorld().spawnParticle(Particle.EXPLOSION_NORMAL, start.clone().add(0, 0.3, 0), 12, 0.3, 0.1, 0.3, 0.05);
        start.getWorld().spawnParticle(Particle.SWEEP_ATTACK, start.clone().add(heading.clone().multiply(0.8)).add(0, 1, 0), 1);
        final int thrust = 4 + level * 4;
        final int total = 10 + level * 4;
        final Set<UUID> hit = new HashSet<>();
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || ++ticks > total) {
                    cancel();
                    return;
                }
                if (ticks > 4 && ticks <= thrust) {
                    Vector v = player.getVelocity();
                    player.setVelocity(heading.clone().multiply(1.1 + 0.1 * level).setY(Math.max(v.getY(), -0.1)));
                }
                Location at = player.getLocation();
                // Streak trail plus a spinning ring around the player.
                at.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 1, 0), 3, 0.15, 0.3, 0.15, 0.01);
                at.getWorld().spawnParticle(Particle.CRIT, at.clone().add(0, 1, 0), 4, 0.3, 0.4, 0.3, 0.1);
                for (int k = 0; k < 2; k++) {
                    double angle = (ticks * 0.9) + k * Math.PI;
                    at.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                            at.clone().add(Math.cos(angle) * 0.9, 1.0 + 0.04 * ticks, Math.sin(angle) * 0.9), 0);
                    at.getWorld().spawnParticle(Particle.FIREWORKS_SPARK,
                            at.clone().add(Math.cos(angle) * 0.9, 1.0, Math.sin(angle) * 0.9), 1, 0, 0, 0, 0);
                }
                if (ticks % 3 == 0) {
                    at.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.8f);
                }
                double speed = player.getVelocity().length();
                for (Entity e : player.getNearbyEntities(1.8, 1.5, 1.8)) {
                    if (!(e instanceof LivingEntity) || e.isDead() || !hit.add(e.getUniqueId())) {
                        continue;
                    }
                    double damage = SPEAR_BASE + sharpness(spear) + Math.min(8, speed * 6);
                    hurt(player, (LivingEntity) e, damage);
                    burn(spear, e);
                    Vector push = player.getVelocity().clone().setY(0);
                    if (push.lengthSquared() > 1e-4) {
                        e.setVelocity(push.normalize().multiply(0.9).setY(0.3));
                    }
                    e.getWorld().playSound(e.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
                    e.getWorld().spawnParticle(Particle.CRIT, e.getLocation().add(0, 1, 0), 15, 0.3, 0.3, 0.3, 0.3);
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    // The client repeats right-click about every 4 ticks while the button is held, so a gap with
    // no click means the button was released.
    private static final long RELEASE_GAP_MS = 300;
    private final Map<UUID, Long> lastHold = new HashMap<>();
    private final Map<UUID, Long> cooldownNotice = new HashMap<>();

    private void holdCharge(final Player player) {
        final UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (lastHold.containsKey(id)) {
            lastHold.put(id, now);
            return;
        }
        Long last = lungeCooldown.get(id);
        if (last != null && now - last < LUNGE_COOLDOWN_MS) {
            Long noticed = cooldownNotice.get(id);
            if (noticed == null || now - noticed > 1000) {
                cooldownNotice.put(id, now);
                player.sendMessage(ChatColor.RED + "Dash is on cooldown for "
                        + ((LUNGE_COOLDOWN_MS - (now - last) + 999) / 1000) + "s.");
            }
            return;
        }
        lastHold.put(id, now);
        final int slot = player.getInventory().getHeldItemSlot();
        ItemStack inHand = player.getInventory().getItem(slot);
        inHand.setType(TILTED_MATERIAL);
        player.getInventory().setItem(slot, inHand);
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                ItemStack held = player.getInventory().getItemInMainHand();
                Long seen = lastHold.get(id);
                boolean released = seen == null || System.currentTimeMillis() - seen > RELEASE_GAP_MS;
                if (!player.isOnline() || !isSpear(held)) {
                    finish();
                    return;
                }
                if (released) {
                    finish();
                    lunge(player, player.getInventory().getItemInMainHand());
                    return;
                }
                ticks++;
            }

            private void finish() {
                cancel();
                lastHold.remove(id);
                untiltAll(player);
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void windBurst(final Player player, ItemStack item) {
        final UUID id = player.getUniqueId();
        if (windAirborne.contains(id)) {
            return;
        }
        windAirborne.add(id);
        // No cooldown: the burst is available again as soon as the player touches the ground.
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || (++ticks > 5 && player.isOnGround()) || ticks > 600) {
                    windAirborne.remove(id);
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
        player.setVelocity(new Vector(0, 1.36, 0));
        fallImmunity.put(player.getUniqueId(), System.currentTimeMillis() + 4000L);
        Location at = player.getLocation();
        at.getWorld().playSound(at, Sound.ENTITY_ENDERDRAGON_FLAP, 1f, 1.2f);
        at.getWorld().spawnParticle(Particle.CLOUD, at, 40, 0.4, 0.1, 0.4, 0.1);
        item.setAmount(item.getAmount() - 1);
        player.getInventory().setItemInMainHand(item.getAmount() > 0 ? item : null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        Long until = fallImmunity.get(player.getUniqueId());
        if (until != null && until > System.currentTimeMillis()
                || isMace(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onHeldChange(PlayerItemHeldEvent event) {
        untiltAll(event.getPlayer());
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        untilt(event.getMainHandItem());
        untilt(event.getOffHandItem());
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack stack = event.getItemDrop().getItemStack();
        if (isTilted(stack)) {
            stack.setType(SPEAR_MATERIAL);
            event.getItemDrop().setItemStack(stack);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        for (ItemStack item : event.getDrops()) {
            untilt(item);
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player) {
            untiltAll((Player) event.getPlayer());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        untiltAll(event.getPlayer());
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            untiltAll(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        jabCooldown.remove(event.getPlayer().getUniqueId());
        lungeCooldown.remove(event.getPlayer().getUniqueId());
        windAirborne.remove(event.getPlayer().getUniqueId());
        untiltAll(event.getPlayer());
        lastHold.remove(event.getPlayer().getUniqueId());
        cooldownNotice.remove(event.getPlayer().getUniqueId());
        lastHold.remove(event.getPlayer().getUniqueId());
        fallImmunity.remove(event.getPlayer().getUniqueId());
    }

    // --------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2 || args.length > 3) {
            return false;
        }
        String type = args[1].toLowerCase(Locale.ROOT);
        if (!type.equals("mace") && !type.equals("spear") && !type.equals("wind") && !type.equals("all")) {
            return false;
        }
        int amount = 1;
        if (args.length == 3) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Amount must be a whole number.");
                return true;
            }
            if (amount < 1 || amount > 64) {
                sender.sendMessage(ChatColor.RED + "Amount must be between 1 and 64.");
                return true;
            }
        }
        List<Player> targets = resolve(sender, args[0]);
        if (targets == null) {
            return true;
        }
        for (Player p : targets) {
            for (int i = 0; i < amount; i++) {
                if (type.equals("mace") || type.equals("all")) {
                    give(p, createMace());
                }
                if (type.equals("spear") || type.equals("all")) {
                    give(p, createSpear());
                }
                if (type.equals("wind") || type.equals("all")) {
                    give(p, createWind());
                }
            }
        }
        sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " " + type + " to " + targets.size() + " player(s).");
        return true;
    }

    private static void give(Player p, ItemStack item) {
        for (ItemStack rest : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    private List<Player> resolve(CommandSender sender, String sel) {
        List<Player> out = new ArrayList<>();
        String lower = sel.toLowerCase(Locale.ROOT);
        if (lower.equals("@a")) {
            out.addAll(Bukkit.getOnlinePlayers());
        } else if (lower.equals("@s")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "@s needs a player sender.");
                return null;
            }
            out.add((Player) sender);
        } else if (lower.equals("@p")) {
            Location from = sender instanceof Player ? ((Player) sender).getLocation()
                    : sender instanceof BlockCommandSender ? ((BlockCommandSender) sender).getBlock().getLocation()
                    : null;
            if (from == null) {
                sender.sendMessage(ChatColor.RED + "@p needs a player or command block sender.");
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
        } else {
            Player p = Bukkit.getPlayerExact(sel);
            if (p != null) {
                out.add(p);
            }
        }
        if (out.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No matching players found for " + sel + ".");
            return null;
        }
        return out;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : Arrays.asList("@a", "@s", "@p")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 2) {
            for (String s : Arrays.asList("mace", "spear", "wind", "all")) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
