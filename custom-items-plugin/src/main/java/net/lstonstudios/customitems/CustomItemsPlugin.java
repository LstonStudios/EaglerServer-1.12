package net.lstonstudios.customitems;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class CustomItemsPlugin extends JavaPlugin implements Listener {

    private enum SwordType {
        LIGHTNING("lightning", Material.GOLD_SWORD, ChatColor.YELLOW, ChatColor.WHITE, "Lightning", "Strikes lightning on whoever you hit."),
        EXPLOSION("explosion", Material.IRON_SWORD, ChatColor.RED, ChatColor.GOLD, "Explosion", "Explodes where you hit."),
        PEARL("pearl", Material.DIAMOND_SWORD, ChatColor.DARK_PURPLE, ChatColor.AQUA, "Pearl", "Teleports you to whoever you hit.");

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
            this.marker = ChatColor.BLACK + "CustomItems:" + key;
        }

        String displayName() {
            return first + "" + ChatColor.BOLD + name + " " + second + ChatColor.BOLD + "Sword";
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

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("customitem").setExecutor(this);
    }

    private ItemStack createSword(SwordType type) {
        ItemStack item = new ItemStack(type.material, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(type.displayName());
        meta.setLore(Arrays.asList(ChatColor.GRAY + type.description, type.marker));
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        item.setItemMeta(meta);
        return item;
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
            if (item.getType() == type.material && lore.contains(type.marker)) {
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
                break;
            case EXPLOSION:
                location.getWorld().createExplosion(location.getX(), location.getY(), location.getZ(), 3.0F, false, true);
                break;
            case PEARL:
                Location from = attacker.getLocation();
                from.getWorld().playSound(from, Sound.ENTITY_ENDERMEN_TELEPORT, 1.0F, 1.0F);
                Location destination = location.clone();
                destination.setYaw(from.getYaw());
                destination.setPitch(from.getPitch());
                attacker.teleport(destination);
                destination.getWorld().playSound(destination, Sound.ENTITY_ENDERMEN_TELEPORT, 1.0F, 1.0F);
                break;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("customitems.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage custom items.");
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            sendUsage(sender, label);
            return true;
        }
        SwordType type = SwordType.fromKey(args[1]);
        if (type == null) {
            sender.sendMessage(ChatColor.RED + "Unknown item. Choose lightning, explosion, or pearl.");
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
        for (Player target : targets) {
            ItemStack sword = createSword(type);
            for (int i = 0; i < amount; i++) {
                Map<Integer, ItemStack> leftovers = target.getInventory().addItem(sword.clone());
                for (ItemStack leftover : leftovers.values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), leftover);
                }
            }
            target.sendMessage(ChatColor.GREEN + "You received " + amount + " " + type.displayName() + ChatColor.GREEN + (amount == 1 ? "." : "s."));
        }
        sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " " + type.displayName() + ChatColor.GREEN + (amount == 1 ? "" : "s")
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
        sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <player|@a|@s|@p> <lightning|explosion|pearl> [amount]");
    }
}
