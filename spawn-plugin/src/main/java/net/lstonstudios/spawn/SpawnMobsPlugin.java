package net.lstonstudios.spawn;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class SpawnMobsPlugin extends JavaPlugin implements TabExecutor {
    private static final int MAX_AMOUNT = 100;
    private static final double MAX_RADIUS = 50;
    private static final double DEFAULT_RADIUS = 3;

    private final Random random = new Random();

    @Override
    public void onEnable() {
        getCommand("spawn").setExecutor(this);
        getCommand("spawn").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if ((args.length != 1 && args.length < 4) || args.length > 6) {
            return false;
        }

        EntityType type = parseType(args[0]);
        if (type == null) {
            sender.sendMessage("§cUnknown or unspawnable entity: " + args[0]);
            return true;
        }

        Location origin = senderLocation(sender);
        if (origin == null) {
            sender.sendMessage("§cThis command must be run by a player or command block.");
            return true;
        }

        Location center;
        if (args.length == 1) {
            center = origin;
        } else {
            Double x = parseCoordinate(args[1], origin.getX());
            Double y = parseCoordinate(args[2], origin.getY());
            Double z = parseCoordinate(args[3], origin.getZ());
            if (x == null || y == null || z == null) {
                sender.sendMessage("§cInvalid coordinates.");
                return true;
            }
            center = new Location(origin.getWorld(), x, y, z);
        }

        int amount = 1;
        if (args.length >= 5) {
            try {
                amount = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cAmount must be a whole number.");
                return true;
            }
            if (amount < 1 || amount > MAX_AMOUNT) {
                sender.sendMessage("§cAmount must be between 1 and " + MAX_AMOUNT + ".");
                return true;
            }
        }

        double radius = amount > 1 ? DEFAULT_RADIUS : 0;
        if (args.length == 6) {
            try {
                radius = Double.parseDouble(args[5]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cRadius must be a number.");
                return true;
            }
            if (!(radius >= 0 && radius <= MAX_RADIUS)) {
                sender.sendMessage("§cRadius must be between 0 and " + (int) MAX_RADIUS + ".");
                return true;
            }
        }

        World world = center.getWorld();
        int spawned = 0;
        for (int i = 0; i < amount; i++) {
            Location loc = center.clone();
            if (radius > 0) {
                double angle = random.nextDouble() * Math.PI * 2;
                double dist = Math.sqrt(random.nextDouble()) * radius;
                loc.add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
            }
            Entity entity = world.spawnEntity(loc, type);
            if (entity != null) {
                spawned++;
            }
        }

        sender.sendMessage("§aSpawned " + spawned + " " + type.name().toLowerCase(Locale.ROOT)
                + " at " + format(center.getX()) + " " + format(center.getY()) + " "
                + format(center.getZ()) + (radius > 0 ? " (radius " + format(radius) + ")" : "") + ".");
        return true;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static Location senderLocation(CommandSender sender) {
        if (sender instanceof Player) {
            return ((Player) sender).getLocation();
        }
        if (sender instanceof BlockCommandSender) {
            return ((BlockCommandSender) sender).getBlock().getLocation().add(0.5, 0, 0.5);
        }
        return null;
    }

    private static Double parseCoordinate(String text, double base) {
        try {
            if (text.startsWith("~")) {
                return base + (text.length() == 1 ? 0 : Double.parseDouble(text.substring(1)));
            }
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static EntityType parseType(String name) {
        String key = name.toUpperCase(Locale.ROOT);
        if (key.startsWith("MINECRAFT:")) {
            key = key.substring("MINECRAFT:".length());
        }
        for (EntityType type : EntityType.values()) {
            if (type.isSpawnable() && type.name().equals(key)) {
                return type;
            }
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (EntityType type : EntityType.values()) {
                String n = type.name().toLowerCase(Locale.ROOT);
                if (type.isSpawnable() && n.startsWith(prefix)) {
                    out.add(n);
                }
            }
        } else if (args.length >= 2 && args.length <= 4) {
            out.add("~");
        }
        return out;
    }
}
