package net.lstonstudios.eaglermotdvelocity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.lax1dude.eaglercraft.backend.server.api.IEaglerXServerAPI;
import net.lax1dude.eaglercraft.backend.server.api.IEaglerListenerInfo;
import net.lax1dude.eaglercraft.backend.server.api.query.IQueryConnection;
import net.lax1dude.eaglercraft.backend.server.api.query.IQueryHandler;
import net.lax1dude.eaglercraft.backend.server.api.query.IQueryServer;
import net.lax1dude.eaglercraft.backend.server.api.query.IMOTDConnection;
import net.lax1dude.eaglercraft.backend.server.api.velocity.event.EaglercraftMOTDEvent;
import net.lax1dude.eaglercraft.v1_8.plugin.gateway_velocity.api.query.MOTDConnection;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EaglerMOTDVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<String, List<Message>> messages = new ConcurrentHashMap<>();
    private final Map<MOTDHandle, Animation> animations = new ConcurrentHashMap<>();
    private final Map<String, QueryDefinition> queries = new ConcurrentHashMap<>();
    private final Set<String> registeredQueries = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean legacyRequestLogged = new AtomicBoolean();
    private final AtomicBoolean legacyUpdateLogged = new AtomicBoolean();
    private volatile IQueryServer queryServer;
    private volatile ScheduledTask tickTask;
    private volatile int closeSocketAfter = 1200;

    @Inject
    public EaglerMOTDVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            reloadConfiguration();
        } catch (Exception exception) {
            logger.error("Could not load EaglerMOTD configuration", exception);
        }
        try {
            registerQueries(IEaglerXServerAPI.instance().getQueryServer());
        } catch (RuntimeException exception) {
            logger.warn("EaglerXServer query API is not ready yet; queries will register on the first MOTD request", exception);
        }
        proxy.getCommandManager().register("motd-reload", new ReloadCommand());
        tickTask = proxy.getScheduler().buildTask(this, this::tick)
                .repeat(50, TimeUnit.MILLISECONDS)
                .schedule();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (tickTask != null) {
            tickTask.cancel();
        }
        animations.clear();
        unregisterQueries();
    }

    @Subscribe
    public void onEaglercraftMOTD(EaglercraftMOTDEvent event) {
        IMOTDConnection connection = event.getMOTDConnection();
        registerQueries(event.getServerAPI().getQueryServer());
        startAnimation(new XServerMOTDHandle(connection), connection.getAccept());
    }

    @Subscribe
    public void onEaglerXVelocityMOTD(
            net.lax1dude.eaglercraft.v1_8.plugin.gateway_velocity.api.event.EaglercraftMOTDEvent event) {
        MOTDConnection connection = event.getConnection();
        if (legacyRequestLogged.compareAndSet(false, true)) {
            logger.info("Received EaglerXVelocity MOTD request on {} (accept={})",
                    connection.getListener().getAddress(), connection.getAccept());
        }
        startAnimation(new LegacyMOTDHandle(connection), connection.getAccept());
    }

    private void startAnimation(MOTDHandle connection, String accept) {
        if (!"motd".equalsIgnoreCase(accept) && !"motd.noicon".equalsIgnoreCase(accept)) {
            return;
        }

        String listenerKey = connection.listenerKey();
        List<Message> pool = findPool(listenerKey);
        if (pool == null || pool.isEmpty()) {
            return;
        }

        Message message = pickMessage(pool, null);
        Animation animation = new Animation(connection, listenerKey, pool, message);
        if (animation.isAnimated()) {
            connection.keepAlive(Math.max(50L, closeSocketAfter * 50L));
            animations.put(connection, animation);
        }
        applyFrame(animation, animation.currentFrame(), true);
    }

    private synchronized void reloadConfiguration() throws IOException {
        Files.createDirectories(dataDirectory);
        copyDefault("messages.json", "/default_messages.json");
        copyDefault("frames.json", "/default_frames.json");
        copyDefault("queries.json", "/default_queries.json");

        Map<String, List<Message>> loadedMessages = new HashMap<>();
        Map<String, JsonObject> loadedFrames = new HashMap<>();
        Map<String, QueryDefinition> loadedQueries = new HashMap<>();

        JsonObject messagesRoot = readJson(dataDirectory.resolve("messages.json")).getAsJsonObject();
        closeSocketAfter = Math.max(1, messagesRoot.has("close_socket_after")
                ? messagesRoot.get("close_socket_after").getAsInt() : 1200);
        JsonObject messageGroups = messagesRoot.getAsJsonObject("messages");
        if (messageGroups != null) {
            for (Map.Entry<String, JsonElement> group : messageGroups.entrySet()) {
                if (!group.getValue().isJsonArray()) {
                    continue;
                }
                List<Message> pool = new ArrayList<>();
                JsonArray entries = group.getValue().getAsJsonArray();
                for (JsonElement entryElement : entries) {
                    try {
                        JsonObject entry = entryElement.getAsJsonObject();
                        List<JsonObject> frameList = new ArrayList<>();
                        for (JsonElement frameRef : entry.getAsJsonArray("frames")) {
                            JsonObject frame = resolveFrame(frameRef.getAsString(), loadedFrames);
                            if (frame != null) {
                                frameList.add(frame);
                            }
                        }
                        if (!frameList.isEmpty()) {
                            pool.add(new Message(entry, frameList));
                        }
                    } catch (RuntimeException exception) {
                        logger.warn("Skipping an invalid EaglerMOTD message in group {}", group.getKey(), exception);
                    }
                }
                if (!pool.isEmpty()) {
                    loadedMessages.put(group.getKey(), pool);
                }
            }
        }

        JsonObject queriesRoot = readJson(dataDirectory.resolve("queries.json")).getAsJsonObject();
        JsonObject queryEntries = queriesRoot.getAsJsonObject("queries");
        if (queryEntries != null) {
            for (Map.Entry<String, JsonElement> entry : queryEntries.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    loadedQueries.put(entry.getKey().toLowerCase(), new QueryDefinition(entry.getValue().getAsJsonObject()));
                }
            }
        }

        messages.clear();
        messages.putAll(loadedMessages);
        queries.clear();
        queries.putAll(loadedQueries);
        unregisterQueries();
        if (queryServer != null) {
            registerQueries(queryServer);
        }
        logger.info("Loaded {} EaglerMOTD message groups and {} custom queries", messages.size(), queries.size());
    }

    private void copyDefault(String fileName, String resourceName) throws IOException {
        Path file = dataDirectory.resolve(fileName);
        if (Files.exists(file)) {
            return;
        }
        try (InputStream input = getClass().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing built-in resource " + resourceName);
            }
            Files.copy(input, file);
        }
    }

    private JsonElement readJson(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader);
        }
    }

    private JsonObject resolveFrame(String reference, Map<String, JsonObject> frameFiles) {
        int separator = reference.indexOf('.');
        if (separator <= 0 || separator == reference.length() - 1) {
            logger.warn("Invalid EaglerMOTD frame reference: {}", reference);
            return null;
        }
        String fileName = reference.substring(0, separator);
        String frameName = reference.substring(separator + 1);
        JsonObject file = frameFiles.get(fileName);
        if (file == null) {
            try {
                file = readJson(resolveDataPath(fileName + ".json")).getAsJsonObject();
                frameFiles.put(fileName, file);
            } catch (Exception exception) {
                logger.warn("Could not load EaglerMOTD frame file {}.json", fileName, exception);
                return null;
            }
        }
        JsonElement frame = file.get(frameName);
        return frame != null && frame.isJsonObject() ? frame.getAsJsonObject() : null;
    }

    private Path resolveDataPath(String fileName) throws IOException {
        Path path = dataDirectory.resolve(fileName).normalize();
        if (!path.startsWith(dataDirectory.normalize())) {
            throw new IOException("Path escapes EaglerMOTD data directory");
        }
        return path;
    }

    private String listenerKey(IEaglerListenerInfo listener) {
        return listener.getName() + "|" + addressKey(listener.getAddress());
    }

    private String addressKey(java.net.SocketAddress address) {
        if (address instanceof InetSocketAddress socketAddress) {
            String host = socketAddress.getAddress() == null
                    ? socketAddress.getHostString() : socketAddress.getAddress().getHostAddress();
            if (host.contains(":")) {
                host = "[" + host + "]";
            }
            return host + ":" + socketAddress.getPort();
        }
        return address.toString().replaceFirst("^/", "");
    }

    private List<Message> findPool(String listenerKey) {
        List<Message> specific = messages.get(listenerKey);
        if (specific != null) {
            return specific;
        }
        String[] keyParts = listenerKey.split("\\|", 2);
        if (keyParts.length == 2) {
            specific = messages.get(keyParts[0]);
            if (specific == null) {
                specific = messages.get(keyParts[1]);
            }
            if (specific != null) {
                return specific;
            }
        }
        return messages.get("all");
    }

    private synchronized void registerQueries(IQueryServer server) {
        if (server == null || server == queryServer && registeredQueries.size() == queries.size()) {
            return;
        }
        unregisterQueries();
        queryServer = server;
        for (Map.Entry<String, QueryDefinition> entry : queries.entrySet()) {
            String accept = entry.getKey();
            QueryDefinition definition = entry.getValue();
            IQueryHandler handler = connection -> handleQuery(connection, definition);
            try {
                server.registerQueryType(this, accept, handler);
                registeredQueries.add(accept);
            } catch (RuntimeException exception) {
                logger.warn("Could not register EaglerMOTD query {}", accept, exception);
            }
        }
    }

    private synchronized void unregisterQueries() {
        IQueryServer server = queryServer;
        if (server != null) {
            for (String accept : registeredQueries) {
                server.unregisterQueryType(this, accept);
            }
        }
        registeredQueries.clear();
    }

    private void handleQuery(IQueryConnection connection, QueryDefinition definition) {
        try {
            JsonObject config = definition.config;
            String responseType = config.has("type") ? config.get("type").getAsString() : "text";
            JsonElement json = config.get("json");
            if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
                json = readJson(resolveDataPath(json.getAsString()));
            }
            String text = config.has("string") ? config.get("string").getAsString() : null;
            if (config.has("txt")) {
                text = Files.readString(resolveDataPath(config.get("txt").getAsString()));
            }
            byte[] binary = config.has("file")
                    ? Files.readAllBytes(resolveDataPath(config.get("file").getAsString())) : null;
            if (json != null && json.isJsonObject()) {
                connection.sendResponse(responseType, json.getAsJsonObject());
            } else if (text != null) {
                connection.sendResponse(responseType, text);
            } else if (binary != null) {
                JsonObject metadata = new JsonObject();
                metadata.addProperty("binary", true);
                metadata.addProperty("file", config.get("file").getAsString());
                metadata.addProperty("size", binary.length);
                connection.sendResponse(responseType, metadata);
            } else {
                connection.sendResponse(responseType, "<No Content>");
            }
            if (binary != null) {
                connection.send(binary);
            }
            connection.disconnect();
        } catch (Exception exception) {
            logger.warn("Could not serve an EaglerMOTD custom query", exception);
            connection.sendResponse("error", "Could not load custom query response");
            connection.disconnect();
        }
    }

    private void tick() {
        for (Map.Entry<MOTDHandle, Animation> entry : animations.entrySet()) {
            Animation animation = entry.getValue();
            try {
                if (!entry.getKey().isConnected() || !animation.tick()) {
                    animations.remove(entry.getKey(), animation);
                }
            } catch (RuntimeException exception) {
                logger.warn("Error updating an animated EaglerMOTD", exception);
                entry.getKey().disconnect();
                animations.remove(entry.getKey(), animation);
            }
        }
    }

    private void applyFrame(Animation animation, JsonObject frame, boolean send) {
        MOTDHandle connection = animation.connection;
        boolean changed = false;
        JsonElement online = frame.get("online");
        if (online != null) {
            connection.setPlayerTotal(online.isJsonPrimitive() && online.getAsJsonPrimitive().isNumber()
                    ? online.getAsInt() : proxy.getPlayerCount());
            changed = true;
        }
        JsonElement max = frame.get("max");
        if (max != null) {
            connection.setPlayerMax(max.isJsonPrimitive() && max.getAsJsonPrimitive().isNumber()
                    ? max.getAsInt() : connection.getDefaultPlayerMax());
            changed = true;
        }
        JsonElement players = frame.get("players");
        if (players != null) {
            List<String> names = new ArrayList<>();
            if (players.isJsonArray()) {
                for (JsonElement player : players.getAsJsonArray()) {
                    names.add(translateColors(player.getAsString()));
                }
            } else {
                int count = 0;
                int total = proxy.getAllPlayers().size();
                for (Player player : proxy.getAllPlayers()) {
                    if (count == 9) {
                        names.add("§7§o(" + (total - count) + " more)");
                        break;
                    }
                    names.add(player.getUsername());
                    count++;
                }
            }
            connection.setPlayerList(names);
            changed = true;
        }
        String line0 = stringValue(frame, "text0", stringValue(frame, "text", null));
        String line1 = stringValue(frame, "text1", null);
        if (line0 != null) {
            int newline = line0.indexOf('\n');
            List<String> motd = new ArrayList<>(connection.getServerMOTD());
            while (motd.size() < 2) {
                motd.add("");
            }
            if (newline >= 0) {
                motd.set(0, translateColors(line0.substring(0, newline)));
                motd.set(1, translateColors(line0.substring(newline + 1)));
            } else {
                motd.set(0, translateColors(line0));
            }
            if (line1 != null) {
                motd.set(1, translateColors(line1));
            }
                connection.setServerMOTD(motd);
            changed = true;
        } else if (line1 != null) {
            List<String> motd = new ArrayList<>(connection.getServerMOTD());
            while (motd.size() < 2) {
                motd.add("");
            }
            motd.set(1, translateColors(line1));
            connection.setServerMOTD(motd);
            changed = true;
        }
        if (frame.has("icon") || frame.has("icon_spriteX") || frame.has("icon_spriteY")
                || frame.has("icon_pixelX") || frame.has("icon_pixelY") || frame.has("icon_flipX")
                || frame.has("icon_flipY") || frame.has("icon_rotate") || frame.has("icon_color")
                || frame.has("icon_tint")) {
            try {
                connection.setServerIcon(animation.icon.render(frame));
                changed = true;
            } catch (IOException exception) {
                logger.warn("Could not render EaglerMOTD icon", exception);
            }
        }
        if (send && changed) {
            connection.sendToUser();
        }
    }

    private String stringValue(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private String translateColors(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '&' && index + 1 < text.length()
                    && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(text.charAt(index + 1)) >= 0) {
                result.append('\u00a7').append(Character.toLowerCase(text.charAt(++index)));
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static int[] rgbaToArgb(byte[] rgba) {
        if (rgba == null) {
            return null;
        }
        int[] pixels = new int[rgba.length / 4];
        for (int index = 0; index < pixels.length; index++) {
            int offset = index * 4;
            int red = rgba[offset] & 0xff;
            int green = rgba[offset + 1] & 0xff;
            int blue = rgba[offset + 2] & 0xff;
            int alpha = rgba[offset + 3] & 0xff;
            pixels[index] = (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
        return pixels;
    }

    private Message pickMessage(List<Message> pool, Message avoid) {
        if (pool.size() == 1 && pool.get(0) != avoid) {
            return pool.get(0);
        }
        double total = pool.stream().filter(message -> message != avoid).mapToDouble(message -> message.weight).sum();
        if (total <= 0.0) {
            return pool.stream().filter(message -> message != avoid).findFirst().orElse(pool.get(0));
        }
        double selection = ThreadLocalRandom.current().nextDouble(total);
        for (Message message : pool) {
            if (message == avoid) {
                continue;
            }
            selection -= message.weight;
            if (selection < 0.0) {
                return message;
            }
        }
        return pool.get(pool.size() - 1);
    }

    private interface MOTDHandle {
        String listenerKey();

        boolean isConnected();

        void disconnect();

        void keepAlive(long maxAgeMillis);

        List<String> getServerMOTD();

        void setServerMOTD(List<String> motd);

        void setPlayerTotal(int total);

        int getDefaultPlayerMax();

        void setPlayerMax(int max);

        void setPlayerList(List<String> players);

        void setServerIcon(byte[] rgba);

        void sendToUser();
    }

    private final class XServerMOTDHandle implements MOTDHandle {
        private final IMOTDConnection connection;

        private XServerMOTDHandle(IMOTDConnection connection) {
            this.connection = connection;
        }

        @Override
        public String listenerKey() {
            return EaglerMOTDVelocity.this.listenerKey(connection.getListenerInfo());
        }

        @Override
        public boolean isConnected() {
            return connection.isConnected();
        }

        @Override
        public void disconnect() {
            connection.disconnect();
        }

        @Override
        public void keepAlive(long maxAgeMillis) {
            connection.setMaxAge(maxAgeMillis);
        }

        @Override
        public List<String> getServerMOTD() {
            return connection.getServerMOTD();
        }

        @Override
        public void setServerMOTD(List<String> motd) {
            connection.setServerMOTD(motd);
        }

        @Override
        public void setPlayerTotal(int total) {
            connection.setPlayerTotal(total);
        }

        @Override
        public int getDefaultPlayerMax() {
            return connection.getDefaultPlayerMax();
        }

        @Override
        public void setPlayerMax(int max) {
            connection.setPlayerMax(max);
        }

        @Override
        public void setPlayerList(List<String> players) {
            connection.setPlayerList(players);
        }

        @Override
        public void setServerIcon(byte[] rgba) {
            connection.setServerIcon(rgba);
        }

        @Override
        public void sendToUser() {
            connection.sendToUser();
        }
    }

    private final class LegacyMOTDHandle implements MOTDHandle {
        private final MOTDConnection connection;

        private LegacyMOTDHandle(MOTDConnection connection) {
            this.connection = connection;
        }

        @Override
        public String listenerKey() {
            return addressKey(connection.getListener().getAddress());
        }

        @Override
        public boolean isConnected() {
            return !connection.isClosed();
        }

        @Override
        public void disconnect() {
            connection.close();
        }

        @Override
        public void keepAlive(long maxAgeMillis) {
            connection.setKeepAlive(true);
        }

        @Override
        public List<String> getServerMOTD() {
            List<String> motd = new ArrayList<>(2);
            motd.add(connection.getLine1());
            motd.add(connection.getLine2());
            return motd;
        }

        @Override
        public void setServerMOTD(List<String> motd) {
            connection.setLine1(motd.get(0));
            connection.setLine2(motd.get(1));
        }

        @Override
        public void setPlayerTotal(int total) {
            connection.setOnlinePlayers(total);
        }

        @Override
        public int getDefaultPlayerMax() {
            return connection.getListener().getMaxPlayer();
        }

        @Override
        public void setPlayerMax(int max) {
            connection.setMaxPlayers(max);
        }

        @Override
        public void setPlayerList(List<String> players) {
            connection.setPlayerList(players);
        }

        @Override
        public void setServerIcon(byte[] rgba) {
            connection.setBitmap(rgbaToArgb(rgba));
        }

        @Override
        public void sendToUser() {
            connection.sendToUser();
        }
    }

    private final class ReloadCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof ConsoleCommandSource)
                    && !invocation.source().hasPermission("eaglermotd.reload")) {
                invocation.source().sendMessage(Component.text("You do not have permission to reload EaglerMOTD."));
                return;
            }
            try {
                reloadConfiguration();
                invocation.source().sendMessage(Component.text("EaglerMOTD configuration reloaded."));
            } catch (Exception exception) {
                logger.error("Could not reload EaglerMOTD configuration", exception);
                invocation.source().sendMessage(Component.text("EaglerMOTD configuration reload failed."));
            }
        }
    }

    private static final class Message {
        private final String name;
        private final int interval;
        private final int timeout;
        private final boolean random;
        private final boolean shuffle;
        private final double weight;
        private final String next;
        private final List<JsonObject> frames;

        private Message(JsonObject config, List<JsonObject> frames) {
            this.name = config.has("name") ? config.get("name").getAsString() : null;
            this.interval = Math.max(0, config.has("interval") ? config.get("interval").getAsInt() : 0);
            this.timeout = Math.max(1, config.has("timeout") ? config.get("timeout").getAsInt() : 500);
            this.random = config.has("random") && config.get("random").getAsBoolean();
            this.shuffle = config.has("shuffle") && config.get("shuffle").getAsBoolean();
            this.weight = Math.max(0.0, config.has("weight") ? config.get("weight").getAsDouble() : 1.0);
            this.next = config.has("next") && !config.get("next").isJsonNull()
                    ? config.get("next").getAsString() : null;
            this.frames = frames;
        }
    }

    private final class Animation {
        private final MOTDHandle connection;
        private final String listenerKey;
        private final List<Message> pool;
        private final IconState icon = new IconState();
        private Message message;
        private int frameIndex;
        private int messageTicks;
        private int intervalTicks;
        private int ageTicks;
        private Random random;

        private Animation(MOTDHandle connection, String listenerKey, List<Message> pool, Message message) {
            this.connection = connection;
            this.listenerKey = listenerKey;
            this.pool = pool;
            this.message = message;
            this.random = message.random || message.shuffle ? new Random() : null;
            this.frameIndex = message.random ? random.nextInt(message.frames.size()) : 0;
        }

        private JsonObject currentFrame() {
            return message.frames.get(frameIndex);
        }

        private boolean isAnimated() {
            return message.next != null || message.interval > 0 && message.frames.size() > 1;
        }

        private boolean tick() {
            ageTicks++;
            if (ageTicks >= closeSocketAfter) {
                connection.disconnect();
                return false;
            }
            messageTicks++;
            intervalTicks++;
            if (messageTicks >= message.timeout) {
                if (!advanceMessage()) {
                    connection.disconnect();
                    return false;
                }
                applyFrame(this, currentFrame(), true);
                return true;
            }
            if (message.interval > 0 && intervalTicks >= message.interval && message.frames.size() > 1) {
                intervalTicks = 0;
                if (message.shuffle) {
                    int nextFrame;
                    do {
                        nextFrame = random.nextInt(message.frames.size());
                    } while (nextFrame == frameIndex);
                    frameIndex = nextFrame;
                } else {
                    frameIndex = (frameIndex + 1) % message.frames.size();
                }
                if (connection instanceof LegacyMOTDHandle && legacyUpdateLogged.compareAndSet(false, true)) {
                    logger.info("Sent first animated EaglerXVelocity MOTD frame update on {}", listenerKey);
                }
                applyFrame(this, currentFrame(), true);
            }
            return true;
        }

        private boolean advanceMessage() {
            if (message.next == null) {
                return false;
            }
            if ("any".equalsIgnoreCase(message.next) || "random".equalsIgnoreCase(message.next)) {
                message = pickMessage(pool, message);
            } else {
                Message match = pool.stream().filter(candidate -> candidate.name != null
                        && candidate.name.equalsIgnoreCase(message.next)).findFirst().orElse(null);
                if (match == null) {
                    for (List<Message> candidatePool : messages.values()) {
                        match = candidatePool.stream().filter(candidate -> candidate.name != null
                                && candidate.name.equalsIgnoreCase(message.next)).findFirst().orElse(null);
                        if (match != null) {
                            break;
                        }
                    }
                }
                if (match == null) {
                    logger.warn("EaglerMOTD message '{}' points to missing message '{}'", message.name, message.next);
                    return false;
                }
                message = match;
            }
            random = message.random || message.shuffle ? new Random() : null;
            frameIndex = message.random ? random.nextInt(message.frames.size()) : 0;
            messageTicks = 0;
            intervalTicks = 0;
            return true;
        }
    }

    private final class IconState {
        private BufferedImage image;
        private boolean defaultIcon = true;
        private int spriteX;
        private int spriteY;
        private boolean flipX;
        private boolean flipY;
        private int rotate;
        private float[] color = {0, 0, 0, 0};
        private float[] tint = {1, 1, 1, 1};

        private byte[] render(JsonObject frame) throws IOException {
            if (frame.has("icon")) {
                String name = frame.get("icon").getAsString();
                defaultIcon = name.equalsIgnoreCase("default") || name.equalsIgnoreCase("none")
                        || name.equalsIgnoreCase("null") || name.equalsIgnoreCase("color");
                image = defaultIcon ? null : ImageIO.read(resolveDataPath(name).toFile());
                spriteX = spriteY = rotate = 0;
                flipX = flipY = false;
                color = new float[]{0, 0, 0, 0};
                tint = new float[]{1, 1, 1, 1};
            }
            if (frame.has("icon_spriteX")) {
                spriteX = frame.get("icon_spriteX").getAsInt() * 64;
            }
            if (frame.has("icon_spriteY")) {
                spriteY = frame.get("icon_spriteY").getAsInt() * 64;
            }
            if (frame.has("icon_pixelX")) {
                spriteX = frame.get("icon_pixelX").getAsInt();
            }
            if (frame.has("icon_pixelY")) {
                spriteY = frame.get("icon_pixelY").getAsInt();
            }
            if (frame.has("icon_flipX")) {
                flipX = frame.get("icon_flipX").getAsBoolean();
            }
            if (frame.has("icon_flipY")) {
                flipY = frame.get("icon_flipY").getAsBoolean();
            }
            if (frame.has("icon_rotate")) {
                rotate = Math.floorMod(frame.get("icon_rotate").getAsInt(), 4);
            }
            if (frame.has("icon_color")) {
                color = readColor(frame.getAsJsonArray("icon_color"), color);
            }
            if (frame.has("icon_tint")) {
                tint = readColor(frame.getAsJsonArray("icon_tint"), tint);
            }
            if (defaultIcon) {
                return null;
            }
            if (image == null) {
                throw new IOException("Icon image could not be decoded");
            }
            int[] pixels = new int[64 * 64];
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    int sourceX = spriteX + (flipX ? 63 - x : x);
                    int sourceY = spriteY + (flipY ? 63 - y : y);
                    int pixel = sourceX >= 0 && sourceY >= 0 && sourceX < image.getWidth() && sourceY < image.getHeight()
                            ? image.getRGB(sourceX, sourceY) : 0;
                    pixels[y * 64 + x] = transformPixel(pixel);
                }
            }
            if (rotate != 0) {
                int[] rotated = new int[pixels.length];
                for (int y = 0; y < 64; y++) {
                    for (int x = 0; x < 64; x++) {
                        int targetX = rotate == 1 ? 63 - y : rotate == 2 ? 63 - x : y;
                        int targetY = rotate == 1 ? x : rotate == 2 ? 63 - y : 63 - x;
                        rotated[targetY * 64 + targetX] = pixels[y * 64 + x];
                    }
                }
                pixels = rotated;
            }
            byte[] rgba = new byte[pixels.length * 4];
            for (int index = 0; index < pixels.length; index++) {
                int pixel = pixels[index];
                rgba[index * 4] = (byte) (pixel >> 16);
                rgba[index * 4 + 1] = (byte) (pixel >> 8);
                rgba[index * 4 + 2] = (byte) pixel;
                rgba[index * 4 + 3] = (byte) (pixel >>> 24);
            }
            return rgba;
        }

        private float[] readColor(JsonArray array, float[] defaults) {
            float[] color = defaults.clone();
            for (int index = 0; index < Math.min(array.size(), 4); index++) {
                color[index] = Math.max(0, Math.min(1, array.get(index).getAsFloat()));
            }
            return color;
        }

        private int transformPixel(int pixel) {
            int alpha = (pixel >>> 24) & 0xff;
            int red = (pixel >>> 16) & 0xff;
            int green = (pixel >>> 8) & 0xff;
            int blue = pixel & 0xff;
            red = Math.round(red * tint[0]);
            green = Math.round(green * tint[1]);
            blue = Math.round(blue * tint[2]);
            alpha = Math.round(alpha * tint[3]);
            float overlay = color[3];
            red = Math.round(red * (1 - overlay) + color[0] * 255 * overlay);
            green = Math.round(green * (1 - overlay) + color[1] * 255 * overlay);
            blue = Math.round(blue * (1 - overlay) + color[2] * 255 * overlay);
            return (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
    }

    private static final class QueryDefinition {
        private final JsonObject config;

        private QueryDefinition(JsonObject config) {
            this.config = config;
        }
    }
}