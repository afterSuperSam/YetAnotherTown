package dev.yetanothertown.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 不加载游戏，验证配置、游戏 UUID 会话和异步缓存隔离。 */
public final class TownGatewayTest {
    private static int checks;
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static void useLanguage(String language) throws Exception {
        java.util.Map<String, String> translations = new java.util.HashMap<>();
        try (var stream = TownText.class.getResourceAsStream("/assets/yetanothertown/lang/" + language + ".json")) {
            net.minecraft.locale.Language.loadFromJson(stream, translations::put);
        }
        var previous = net.minecraft.locale.Language.getInstance();
        net.minecraft.locale.Language.inject(new net.minecraft.locale.Language() {
            public String getOrDefault(String key, String fallback) { return translations.getOrDefault(key, fallback); }
            public boolean has(String key) { return translations.containsKey(key); }
            public boolean isDefaultRightToLeft() { return false; }
            public net.minecraft.util.FormattedCharSequence getVisualOrder(net.minecraft.network.chat.FormattedText text) { return previous.getVisualOrder(text); }
        });
    }

    public static void main(String[] arguments) throws Exception {
        useLanguage("zh_cn");
        TownGateway gateway = TownGateway.INSTANCE;
        if (arguments.length == 1 && arguments[0].equals("loading")) {
            loadingLifecycle(gateway);
            System.out.println("TownGateway loading: " + checks + " checks passed (no game launched)");
            return;
        }
        if (arguments.length == 1 && arguments[0].equals("onboarding")) {
            var fresh = Files.createTempDirectory("yat-onboarding-");
            gateway.initialize(fresh);
            require(gateway.config().endpoint.equals(TownGateway.ClientConfig.PUBLIC_ENDPOINT), "fresh install defaults to public service");
            gateway.initialize(fresh);
            require(gateway.config().endpoint.equals(TownGateway.ClientConfig.PUBLIC_ENDPOINT), "public default survives reload without confirmation");
            require(!Files.readString(fresh.resolve("yetanothertown.json")).contains("serviceSelected"), "new configuration has no service selection gate");
            gateway.config().completedHints = null;
            require(gateway.config().completedHints.isEmpty() && !gateway.hasCompletedHint("navigation"), "HotSwap legacy instance restores hints");
            gateway.configure(TownGateway.ClientConfig.PUBLIC_ENDPOINT);
            gateway.completeHint("navigation");
            String completed = Files.readString(fresh.resolve("yetanothertown.json"));
            gateway.completeHint("navigation");
            require(completed.equals(Files.readString(fresh.resolve("yetanothertown.json"))), "duplicate completion leaves configuration unchanged");
            gateway.initialize(fresh);
            require(gateway.hasCompletedHint("navigation"), "completed hint survives reload");
            var local = Files.createTempDirectory("yat-local-selection-");
            gateway.initialize(local);
            gateway.configure(TownGateway.ClientConfig.LOCAL_ENDPOINT);
            gateway.initialize(local);
            require(gateway.config().endpoint.equals(TownGateway.ClientConfig.LOCAL_ENDPOINT), "saved local service survives reload");
            var legacy = Files.createTempDirectory("yat-legacy-selection-");
            Files.writeString(legacy.resolve("yetanothertown.json"), "{\"endpoint\":\"https://legacy.example.test\"}");
            gateway.initialize(legacy);
            require(gateway.config().endpoint.equals("https://legacy.example.test"), "legacy custom address is retained");
            Files.writeString(legacy.resolve("yetanothertown.json"), "{\"endpoint\":\"https://legacy.example.test\",\"serviceSelected\":false}");
            gateway.initialize(legacy);
            require(gateway.configurationNotice.isEmpty() && gateway.config().endpoint.equals("https://legacy.example.test"), "obsolete unconfirmed selection field cannot reset a custom endpoint");
            Files.writeString(legacy.resolve("yetanothertown.json"), "{\"endpoint\":\"http://127.0.0.1:37841\"}");
            gateway.initialize(legacy);
            require(gateway.config().endpoint.equals(TownGateway.ClientConfig.LOCAL_ENDPOINT), "legacy local endpoint is retained");
            boolean unknownHint = false;
            try { gateway.completeHint("unknown"); } catch (IllegalArgumentException exception) { unknownHint = true; }
            require(unknownHint && gateway.config().completedHints.isEmpty(), "unknown hint cannot mutate configuration");
            for (String invalid : new String[]{"{\"serviceSelected\":\"yes\"}", "{\"completedHints\":true}", "{\"completedHints\":[1]}", "{\"completedHints\":[\"unknown\"]}"}) {
                var broken = Files.createTempDirectory("yat-invalid-onboarding-");
                Files.writeString(broken.resolve("yetanothertown.json"), invalid);
                gateway.initialize(broken);
                require(!gateway.configurationNotice.isEmpty() && gateway.config().endpoint.equals(TownGateway.ClientConfig.PUBLIC_ENDPOINT), "invalid onboarding configuration is rejected");
            }
            System.out.println("TownGateway onboarding: " + checks + " checks passed (no game launched)");
            return;
        }
        boolean pollingOnly = arguments.length == 1 && arguments[0].equals("polling");
        boolean loginRecoveryOnly = arguments.length == 1 && arguments[0].equals("login-recovery");
        var directory = Files.createTempDirectory("yat-gateway-test-");
        if (!pollingOnly && !loginRecoveryOnly) {
        for (String invalid : new String[]{"{\"keyBindingsVersion\":1,\"endpoint\":\"https://old.example.test\"}", "{", "null", "[]", "{\"pollIntervalSeconds\":0}", "{\"pollIntervalSeconds\":4294967306}"}) {
            var brokenDirectory = Files.createTempDirectory("yat-invalid-config-");
            var original = brokenDirectory.resolve("yetanothertown.json");
            Files.writeString(original, invalid);
            gateway.initialize(brokenDirectory);
            require(gateway.config().endpoint.equals(TownGateway.ClientConfig.PUBLIC_ENDPOINT), "invalid configuration starts with fresh defaults without crashing");
            require(gateway.configurationNotice.contains("已备份"), "recovery notice remains available in client settings");
            try (var files = Files.list(brokenDirectory)) {
                var backups = files.filter(file -> file.getFileName().toString().startsWith("yetanothertown.invalid-")).toList();
                require(backups.size() == 1 && Files.readString(backups.getFirst()).equals(invalid), "invalid configuration preserved exactly before reset");
            }
            require(!Files.readString(original).contains("keyBindingsVersion"), "obsolete fields are not migrated");
            gateway.configure("https://new.example.test");
            require(gateway.configurationNotice.isEmpty(), "saving settings acknowledges configuration recovery");
            gateway.initialize(brokenDirectory);
            require(gateway.config().endpoint.equals("https://new.example.test"), "configuration remains editable after recovery");
        }
        var unreadableDirectory = Files.createTempDirectory("yat-unreadable-config-");
        var occupiedPath = Files.createDirectory(unreadableDirectory.resolve("yetanothertown.json"));
        Files.writeString(occupiedPath.resolve("sentinel"), "preserve");
        gateway.initialize(unreadableDirectory);
        require(gateway.configurationNotice.contains("文件权限"), "configuration I/O failure is visible without crashing startup");
        boolean protectedFile = false;
        try { gateway.configure("https://new.example.test"); } catch (IllegalStateException exception) { protectedFile = true; }
        require(protectedFile, "saving cannot overwrite unreadable configuration");
        require(Files.readString(occupiedPath.resolve("sentinel")).equals("preserve"), "unreadable original remains untouched");
        gateway.initialize(directory);
        require(Files.exists(directory.resolve("yetanothertown.json")), "default configuration saved");
        require(gateway.config().endpoint.equals(TownGateway.ClientConfig.PUBLIC_ENDPOINT), "new configuration uses public service by default");
        JsonObject favorite = TownGateway.object("id", "f".repeat(24), "type", "machine", "status", "maintenance");
        gateway.townId = "12345678";
        require(!gateway.isFavorite(favorite), "favorites start empty");
        gateway.toggleFavorite(favorite);
        gateway.initialize(directory);
        require(gateway.isFavorite(favorite), "favorites survive configuration reload");
        require(gateway.matchesProjectFilter(favorite, "favorites", "machine"), "favorite and type filters combine");
        require(!gateway.matchesProjectFilter(favorite, "favorites", "building"), "type filter excludes unrelated projects");
        require(gateway.matchesProjectFilter(favorite, "maintenance", "all"), "maintenance quick filter uses project status");
        require(!gateway.matchesProjectFilter(favorite, "mine", "all"), "signed-out player cannot match ownership");
        gateway.townId = "87654321";
        require(!gateway.isFavorite(favorite), "same project ID in another town is not favorited");
        gateway.townId = "12345678";
        String favoriteEndpoint = gateway.config().endpoint;
        gateway.configure("https://other.example.test");
        gateway.townId = "12345678";
        require(!gateway.isFavorite(favorite), "favorites are isolated between service endpoints");
        gateway.configure(favoriteEndpoint);
        gateway.townId = "12345678";
        require(gateway.isFavorite(favorite), "changing endpoints preserves stored favorites");
        gateway.toggleFavorite(favorite);
        require(!gateway.isFavorite(favorite), "favorite can be removed");
        gateway.townId = "";
        for (String invalid : new String[]{"http://example.com", "ftp://localhost", "https://example.com/api", "http://", "https://name:secret@example.com", "https://example.com:0", "https://example.com:65536"}) {
            boolean rejected = false;
            try { gateway.configure(invalid); } catch (IllegalArgumentException exception) { rejected = true; }
            require(rejected, "unsafe or malformed endpoint rejected: " + invalid);
        }
        } else gateway.initialize(directory);
        AtomicReference<String> authorization = new AtomicReference<>("");
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowRelease = new CountDownLatch(1);
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch refreshRelease = new CountDownLatch(1);
        var refreshCount = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch revoked = new CountDownLatch(1);
        CountDownLatch staleLoginRevoked = new CountDownLatch(1);
        CountDownLatch loginStarted = new CountDownLatch(1);
        CountDownLatch loginRelease = new CountDownLatch(1);
        CountDownLatch townListStarted = new CountDownLatch(1);
        CountDownLatch townListRelease = new CountDownLatch(1);
        var delayTownList = new java.util.concurrent.atomic.AtomicBoolean();
        var failTownList = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch oversizedRelease = new CountDownLatch(1);
        AtomicReference<String> sharedCurrent = new AtomicReference<>("e".repeat(24));
        AtomicReference<JsonObject> sharedMetadata = new AtomicReference<>(TownGateway.object("serverAddress", "town.example", "mapName", "world", "mapNames", TownGateway.object("minecraft:the_nether", "nether"), "name", "原周目名称"));
        var sharedSnapshots = new java.util.concurrent.atomic.AtomicInteger();
        var delaySharedSnapshot = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch sharedStarted = new CountDownLatch(1);
        CountDownLatch sharedRelease = new CountDownLatch(1);
        AtomicReference<JsonObject> claimedIdentity = new AtomicReference<>();
        AtomicReference<com.google.gson.JsonArray> customTownList = new AtomicReference<>();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api", exchange -> {
            JsonObject input = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            String action = input.get("action").getAsString();
            JsonObject payload = input.getAsJsonObject("payload");
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            int status = 200;
            String body;
            switch (action) {
                case "auth.login" -> {
                    claimedIdentity.set(payload);
                    if (payload.get("name").getAsString().equals("Delayed")) {
                        loginStarted.countDown();
                        try { loginRelease.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                    }
                    String issuedToken = payload.get("name").getAsString().equals("Delayed") ? "delayed-token" : "test-token";
                    body = "{\"ok\":true,\"data\":{\"token\":\"" + issuedToken + "\",\"player\":{\"id\":\"" + payload.get("playerId").getAsString() + "\",\"name\":\"" + payload.get("name").getAsString() + "\"}}}";
                }
                case "auth.logout" -> {
                    revoked.countDown();
                    if ("Bearer delayed-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) staleLoginRevoked.countDown();
                    body = "{\"ok\":true,\"data\":{}}";
                }
                case "town.list" -> {
                    if (failTownList.getAndSet(false)) {
                        exchange.sendResponseHeaders(503, -1);
                        exchange.close();
                        return;
                    }
                    boolean delayed = delayTownList.getAndSet(false);
                    if (delayed) {
                        townListStarted.countDown();
                        try { townListRelease.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                    }
                    body = customTownList.get() == null
                            ? "{\"ok\":true,\"data\":[{\"id\":\"" + (delayed ? "outdated" : "00000001") + "\",\"name\":\"测试镇\",\"server\":\"" + payload.get("serverAddress").getAsString() + "\"}]}"
                            : "{\"ok\":true,\"data\":" + customTownList.get() + "}";
                }
                case "town.snapshot" -> {
                    String town = payload.get("townId").getAsString();
                    String sharedWorld = sharedCurrent.get();
                    if (town.equals("shared") && delaySharedSnapshot.getAndSet(false)) {
                        sharedStarted.countDown();
                        try { sharedRelease.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                    }
                    if (town.equals("slow")) {
                        slowStarted.countDown();
                        try { slowRelease.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                    }
                    if (town.equals("same") && refreshCount.incrementAndGet() == 1) {
                        refreshStarted.countDown();
                        try { refreshRelease.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                        town = "outdated";
                    }
                    String worlds = town.equals("automatic") ? "[{\"id\":\"" + "c".repeat(24) + "\",\"archived\":false}]" : "[]";
                    if (town.equals("shared")) {
                        sharedSnapshots.incrementAndGet();
                        worlds = "[{\"id\":\"" + "e".repeat(24) + "\",\"archived\":false},{\"id\":\"" + "f".repeat(24) + "\",\"archived\":false},{\"id\":\"" + "b".repeat(24) + "\",\"archived\":true}]";
                        var sharedWorlds = JsonParser.parseString(worlds).getAsJsonArray();
                        for (var world : sharedWorlds) {
                            for (var metadata : sharedMetadata.get().entrySet()) world.getAsJsonObject().add(metadata.getKey(), metadata.getValue());
                        }
                        worlds = sharedWorlds.toString();
                    }
                    String pointer = town.equals("shared") ? ",\"currentWorldId\":\"" + sharedWorld + "\"" : "";
                    body = "{\"ok\":true,\"data\":{\"town\":{\"id\":\"" + town + "\"" + pointer + "},\"worlds\":" + worlds + ",\"projects\":[{\"worldId\":\"" + payload.get("worldId").getAsString() + "\"}]}}";
                }
                case "denied" -> { status = 403; body = "{\"ok\":false,\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"没有操作权限\"}}"; }
                case "expired" -> { status = 401; body = "{\"ok\":false,\"error\":{\"code\":\"unauthenticated\",\"message\":\"登录已过期\"}}"; }
                case "malformed" -> body = "not JSON";
                case "invalid-ok" -> body = "{\"ok\":\"true\",\"data\":{}}";
                case "invalid-error" -> { status = 403; body = "{\"ok\":false,\"error\":[]}"; }
                case "invalid-message" -> { status = 403; body = "{\"ok\":false,\"error\":{\"message\":null}}"; }
                case "invalid-data" -> body = "{\"ok\":true,\"data\":42}";
                case "http-failure" -> { status = 503; body = "{\"ok\":true,\"data\":{}}"; }
                case "oversized" -> {
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        exchange.getResponseBody().write("x".repeat(8_000_001).getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();
                        oversizedRelease.await(10, TimeUnit.SECONDS);
                    } catch (java.io.IOException exception) {
                        // 客户端达到上限后主动终止读取。
                    } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                    finally { exchange.close(); }
                    return;
                }
                default -> body = "{\"ok\":true,\"data\":{}}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            gateway.configure("http://127.0.0.1:" + server.getAddress().getPort());
            if (loginRecoveryOnly) {
                gateway.setGameServerAddress("recovery.example");
                String playerId = "a".repeat(32);
                var failedContext = gateway.context();
                failTownList.set(true);
                require(gateway.login(playerId, "Sam").handle((value, error) -> error != null).get(5, TimeUnit.SECONDS), "first town list HTTP 503 fails the complete login chain");
                require(!gateway.loggedIn() && gateway.player().isEmpty() && gateway.towns.isEmpty() && gateway.townId.isEmpty() && gateway.snapshot.isEmpty(), "failed identity initialization clears private state and the loggedIn retry guard");
                require(!gateway.isCurrent(failedContext), "failed initialization invalidates the former session context");
                require(revoked.await(5, TimeUnit.SECONDS), "failed initialization revokes its issued HTTP session");
                gateway.login(playerId, "Sam").get(5, TimeUnit.SECONDS);
                require(gateway.loggedIn() && gateway.townId.equals("00000001"), "next identity attempt recovers town selection after transient HTTP failure");
                delayTownList.set(true);
                var staleLogin = gateway.login(playerId, "Old context");
                require(townListStarted.await(5, TimeUnit.SECONDS), "old session has an actual town list request in flight");
                gateway.setGameServerAddress("new-context.example");
                gateway.login(playerId, "New context").get(5, TimeUnit.SECONDS);
                var currentContext = gateway.context();
                String currentStatus = gateway.status;
                townListRelease.countDown();
                require(staleLogin.handle((value, error) -> error != null).get(5, TimeUnit.SECONDS), "old login rejects completion after game server context changes");
                require(gateway.loggedIn() && gateway.isCurrent(currentContext) && gateway.status.equals(currentStatus), "old login failure does not clear or overwrite the new session");
                require(gateway.player().get("name").getAsString().equals("New context") && gateway.townId.equals("00000001") && gateway.towns.get(0).getAsJsonObject().get("server").getAsString().equals("new-context.example"), "delayed old town list cannot contaminate the new player or town cache");
                System.out.println("TownGateway login-recovery: " + checks + " checks passed (no game launched)");
                return;
            }
            if (pollingOnly) {
                pollingReferences(gateway, sharedCurrent.get(), sharedMetadata, customTownList);
                System.out.println("TownGateway polling: " + checks + " checks passed (no game launched)");
                return;
            }
            for (String action : new String[]{"town.create", "town.update"}) {
                for (String name : new String[]{"ABCDEFGHIJKLM", "一二三四五六七", "ABCD一二三四E", "😀".repeat(7)}) {
                    var result = gateway.request(action, TownGateway.object("name", name));
                    require(result.isCompletedExceptionally(), "overlong town names rejected before sending: " + name);
                }
                for (String name : new String[]{"ABCDEFGHIJKL", "一二三四五六", "ABCD一二三四", "😀".repeat(6)})
                    gateway.request(action, TownGateway.object("name", " " + name + " ")).get(5, TimeUnit.SECONDS);
            }
            String endpoint = gateway.config().endpoint;
            gateway.townId = "00000001";
            gateway.worldId = "a".repeat(24);
            gateway.bindCurrentMap("minecraft:overworld", "personal-server|first");
            gateway.snapshot.addProperty("loaded", true);
            var selectedContext = gateway.context();
            var selectedSnapshot = gateway.snapshot;
            gateway.selectWorld(gateway.worldId);
            require(gateway.isCurrent(selectedContext) && gateway.snapshot == selectedSnapshot, "selecting the current world preserves map context and loaded permissions");
            gateway.bindCurrentMap("minecraft:the_nether", "personal-server|first");
            require(gateway.getBoundMapKey("minecraft:overworld").equals("personal-server|first"), "personal map binding saved");
            require(gateway.getBoundMapKey("minecraft:the_nether").equals("personal-server|first"), "dimensions may independently use the same local instance name");
            gateway.worldId = "b".repeat(24);
            require(gateway.getBoundMapKey("minecraft:overworld").isEmpty(), "new season never inherits historical terrain");
            boolean collisionRejected = false;
            try { gateway.bindCurrentMap("minecraft:overworld", "personal-server|first"); }
            catch (IllegalArgumentException exception) { collisionRejected = true; }
            require(collisionRejected, "one terrain instance cannot overwrite two seasons");
            gateway.bindCurrentMap("minecraft:overworld", "personal-server|second");
            gateway.unbindMap("minecraft:overworld");
            require(gateway.getBoundMapKey("minecraft:overworld").isEmpty(), "incorrect local bindings can be removed");
            gateway.worldId = "a".repeat(24);
            require(gateway.getBoundMapKey("minecraft:overworld").equals("personal-server|first"), "historical binding retained");
            gateway.initialize(directory);
            require(gateway.getBoundMapKey("minecraft:overworld").equals("personal-server|first"), "bindings survive a configuration reload");
            gateway.configure("https://example.test");
            gateway.townId = "00000001";
            gateway.worldId = "a".repeat(24);
            require(gateway.getBoundMapKey("minecraft:overworld").isEmpty(), "different services cannot reuse personal bindings");
            gateway.configure(endpoint);
            String playerId = "a".repeat(32);
            gateway.login(playerId, "Sam").get(5, TimeUnit.SECONDS);
            require(claimedIdentity.get().get("playerId").getAsString().equals(playerId) && claimedIdentity.get().get("name").getAsString().equals("Sam"), "current game UUID and name sent directly");
            require(claimedIdentity.get().size() == 3 && claimedIdentity.get().has("serverAddress"), "identity exchange includes game server but no launcher access token");
            require(gateway.loggedIn() && gateway.player().get("name").getAsString().equals("Sam"), "current game identity retained");
            require(gateway.towns.size() == 1, "array envelope populates town list");
            require("Bearer test-token".equals(authorization.get()), "subsequent requests use session token");
            delayTownList.set(true);
            var olderTowns = gateway.refreshTowns();
            require(townListStarted.await(5, TimeUnit.SECONDS), "older town list response is in flight");
            gateway.refreshTowns().get(5, TimeUnit.SECONDS);
            townListRelease.countDown();
            olderTowns.get(5, TimeUnit.SECONDS);
            require(gateway.towns.get(0).getAsJsonObject().get("id").getAsString().equals("00000001"), "older town list cannot overwrite the latest memberships");
            TownGateway.Context connectedContext = gateway.context();
            gateway.configure(endpoint, 4, 8, 5, false, false);
            require(gateway.isCurrent(connectedContext), "visual settings preserve operation context");
            require(gateway.loggedIn(), "visual and polling settings do not log out the player");
            require(gateway.config().entrySubtitleSeconds == 4 && gateway.config().entryCooldownSeconds == 8 && gateway.config().pollIntervalSeconds == 5 && !gateway.config().animationEnabled && !gateway.config().projectOverlayEnabled, "configuration screen settings are applied");
            require(Files.readString(directory.resolve("yetanothertown.json")).contains("personal-server|first"), "saving settings retains personal map bindings");
            boolean rejectedSettings = false;
            try { gateway.configure(endpoint, 11, 8, 5, false, true); } catch (IllegalArgumentException exception) { rejectedSettings = true; }
            require(rejectedSettings && gateway.config().entrySubtitleSeconds == 4 && !gateway.config().projectOverlayEnabled, "invalid settings cannot partially overwrite configuration");
            gateway.setProjectOverlayEnabled(true);
            require(gateway.config().projectOverlayEnabled && gateway.isCurrent(connectedContext), "map overlay toggle preserves active session and context");
            gateway.configure(endpoint, 0, 8, 5, false, true);
            require(gateway.config().entrySubtitleSeconds == 0, "entry subtitles can be disabled independently of project details");
            var savedSnapshot = gateway.snapshot;
            String savedTown = gateway.townId, savedWorld = gateway.worldId;
            gateway.townId = "00000001";
            gateway.worldId = "c".repeat(24);
            var worlds = new com.google.gson.JsonArray();
            var currentWorld = TownGateway.object("id", gateway.worldId, "archived", false, "serverAddress", "town.example", "mapName", "world");
            worlds.add(currentWorld);
            var currentTown = TownGateway.object("currentWorldId", gateway.worldId);
            gateway.snapshot = TownGateway.object("town", currentTown, "worlds", worlds);
            require(gateway.currentWorldId().equals(gateway.worldId), "current season pointer is exposed from the shared snapshot");
            currentWorld.addProperty("serverAddress", "");
            require(!gateway.matchesSharedMap("town.example", "minecraft:overworld", "world"), "worlds without a server address cannot match a shared map");
            currentWorld.addProperty("serverAddress", " town.example ");
            require(gateway.matchesSharedMap(" TOWN.EXAMPLE ", "minecraft:overworld", "world"), "shared server identity strips whitespace and ignores address case");
            require(!gateway.matchesSharedMap("different.example", "minecraft:overworld", "world"), "shared maps must match the current server");
            require(!gateway.matchesSharedMap("town.example", "minecraft:overworld", "World"), "Xaero map names match exactly including case");
            require(!gateway.matchesSharedMap("town.example", "minecraft:overworld", ""), "blank Xaero map names cannot match");
            require(!gateway.matchesSharedMap("town.example", "overworld", "world"), "shared matching rejects invalid dimensions");
            require(!gateway.matchesSharedMap("town.example", "example:" + "a".repeat(121), "world"), "shared matching rejects overlong dimensions");
            require(!gateway.autoBindMap("town.example", "minecraft:overworld", "personal-server|first", "world"), "archive cache cannot reuse historical terrain");
            require(gateway.matchesSharedMap("town.example", "minecraft:overworld", "world"), "historical cache collision does not block normal shared map matching");
            require(gateway.autoBindMap("TOWN.EXAMPLE", "minecraft:overworld", "personal-server|third", "world"), "a shared current map caches the local instance for later archive viewing");
            require(!gateway.autoBindMap("town.example", "minecraft:overworld", "personal-server|third", "world"), "unchanged local archive cache is a no-op");
            require(gateway.autoBindMap("town.example", "minecraft:overworld", "personal-server|replacement", "world") && gateway.getBoundMapKey("minecraft:overworld").equals("personal-server|replacement"), "a legitimate new local instance refreshes the current season archive cache");
            require(!gateway.autoBindMap("town.example", "minecraft:overworld", " ", "world"), "blank local cache keys are ignored");
            require(!gateway.autoBindMap("town.example", "minecraft:overworld", "invalid", "world") && gateway.matchesSharedMap("town.example", "minecraft:overworld", "world"), "invalid archive cache data does not block shared map matching");
            currentWorld.add("mapNames", TownGateway.object("minecraft:the_nether", "nether"));
            require(gateway.matchesSharedMap("town.example", "minecraft:the_nether", "nether"), "dimension-specific names override the shared default");
            require(!gateway.matchesSharedMap("town.example", "minecraft:the_nether", "world"), "dimension overrides do not also match the default name");
            require(gateway.matchesSharedMap("town.example", "minecraft:the_end", "world"), "unlisted dimensions fall back to the shared default name");
            currentWorld.addProperty("mapName", "");
            require(!gateway.matchesSharedMap("town.example", "minecraft:the_end", "world") && gateway.matchesSharedMap("town.example", "minecraft:the_nether", "nether"), "a missing default name disables only dimensions without an override");
            currentWorld.addProperty("mapName", "world");
            worlds.add(TownGateway.object("id", "d".repeat(24), "archived", false, "serverAddress", "town.example"));
            require(gateway.matchesSharedMap("town.example", "minecraft:overworld", "world"), "multiple seasons on the same server are unambiguous with the current pointer");
            worlds.remove(1);
            currentWorld.addProperty("archived", true);
            require(!gateway.matchesSharedMap("town.example", "minecraft:overworld", "world"), "archived worlds cannot match shared current maps");
            currentWorld.addProperty("archived", false);
            gateway.worldId = "d".repeat(24);
            require(!gateway.matchesSharedMap("town.example", "minecraft:overworld", "world"), "shared current pointer must equal the selected world");
            currentTown.addProperty("currentWorldId", gateway.worldId);
            require(!gateway.matchesSharedMap("town.example", "minecraft:overworld", "world"), "a current pointer without a known world cannot match");
            gateway.worldId = "c".repeat(24);
            currentTown.remove("currentWorldId");
            require(!gateway.matchesSharedMap("town.example", "minecraft:overworld", "world"), "legacy snapshots without a current pointer cannot automatically match maps");
            gateway.townId = savedTown; gateway.worldId = savedWorld; gateway.snapshot = savedSnapshot;
            gateway.request("denied", TownGateway.object()).handle((value, error) -> null).join();
            require(gateway.status.equals(TownText.text("yat.error.service_request")), "legacy service errors use a localized fallback");
            gateway.request("malformed", TownGateway.object()).handle((value, error) -> null).join();
            require(gateway.status.equals("服务返回格式错误"), "invalid protocol explicitly classified");
            for (String action : new String[]{"invalid-ok", "invalid-error", "invalid-message", "invalid-data"}) {
                gateway.request(action, TownGateway.object()).handle((value, error) -> null).join();
                require(gateway.status.equals("服务协议不匹配"), "invalid envelope types are classified: " + action);
            }
            gateway.request("http-failure", TownGateway.object()).handle((value, error) -> null).join();
            require(gateway.status.equals("服务请求失败（HTTP 503）"), "unsuccessful HTTP status cannot report a successful operation");
            gateway.request("oversized", TownGateway.object()).handle((value, error) -> null).get(5, TimeUnit.SECONDS);
            require(gateway.status.equals("服务返回的数据过大"), "oversized response rejected during network reading");
            oversizedRelease.countDown();
            gateway.request("noop", TownGateway.object()).get(5, TimeUnit.SECONDS);
            gateway.selectTown("slow");
            require(slowStarted.await(5, TimeUnit.SECONDS), "old response is in flight");
            TownGateway.Context previousSelection = gateway.context();
            gateway.selectTown("fast");
            require(!gateway.isCurrent(previousSelection), "town switching invalidates pending screen operations");
            gateway.refresh().get(5, TimeUnit.SECONDS);
            slowRelease.countDown();
            gateway.request("noop", TownGateway.object()).get(5, TimeUnit.SECONDS);
            require(gateway.snapshot.getAsJsonObject("town").get("id").getAsString().equals("fast"), "old town response cannot overwrite selection");
            gateway.townId = "automatic";
            gateway.worldId = "";
            TownGateway.Context beforeAutomaticWorld = gateway.context();
            gateway.refresh().get(5, TimeUnit.SECONDS);
            require(!gateway.isCurrent(beforeAutomaticWorld) && gateway.worldId.equals("c".repeat(24)), "automatic world selection invalidates the previous operation context");
            require(gateway.currentWorldId().isEmpty() && !gateway.matchesSharedMap("town.example", "minecraft:overworld", "world"), "legacy world selection remains available for viewing without shared identity matching");
            gateway.selectTown("shared");
            gateway.refresh().get(5, TimeUnit.SECONDS);
            require(gateway.worldId.equals("e".repeat(24)), "town selection follows the mayor's current season pointer");
            gateway.snapshot = new JsonObject();
            var beforeInitialSharedSnapshot = gateway.context();
            gateway.refresh().get(5, TimeUnit.SECONDS);
            require(gateway.isCurrent(beforeInitialSharedSnapshot), "the initial snapshot for an already-selected world preserves its operation context");
            for (String field : new String[]{"serverAddress", "mapName", "mapNames"}) {
                var beforeIdentityChange = gateway.context();
                int requestsBeforeIdentityChange = sharedSnapshots.get();
                JsonObject revisedMetadata = sharedMetadata.get().deepCopy();
                if (field.equals("mapNames")) revisedMetadata.add(field, TownGateway.object("minecraft:the_nether", "new-nether"));
                else revisedMetadata.addProperty(field, "changed-" + field);
                sharedMetadata.set(revisedMetadata);
                gateway.refresh().get(5, TimeUnit.SECONDS);
                require(!gateway.isCurrent(beforeIdentityChange), "same-season shared map changes invalidate pending forms: " + field);
                require(sharedSnapshots.get() == requestsBeforeIdentityChange + 1 && gateway.worldId.equals("e".repeat(24)), "same-season shared identity changes require no second snapshot request: " + field);
            }
            var beforeMetadataChange = gateway.context();
            JsonObject renamedMetadata = sharedMetadata.get().deepCopy();
            renamedMetadata.addProperty("name", "新的周目名称");
            renamedMetadata.addProperty("description", "更新简介");
            renamedMetadata.addProperty("worldKey", "legacy-metadata");
            sharedMetadata.set(renamedMetadata);
            gateway.refresh().get(5, TimeUnit.SECONDS);
            require(gateway.isCurrent(beforeMetadataChange), "ordinary names, descriptions and legacy metadata do not invalidate pending forms");
            var beforeMayorSwitch = gateway.context();
            sharedCurrent.set("f".repeat(24));
            gateway.refresh().get(5, TimeUnit.SECONDS);
            require(gateway.worldId.equals(sharedCurrent.get()) && !gateway.isCurrent(beforeMayorSwitch), "polling follows a changed current season and invalidates old operation context");
            require(gateway.snapshot.getAsJsonArray("projects").get(0).getAsJsonObject().get("worldId").getAsString().equals(gateway.worldId), "a mayor's season switch refetches data for the newly selected world");
            gateway.selectWorld("b".repeat(24));
            gateway.refresh().get(5, TimeUnit.SECONDS);
            sharedCurrent.set("e".repeat(24));
            gateway.refresh().get(5, TimeUnit.SECONDS);
            require(gateway.worldId.equals("b".repeat(24)) && gateway.currentWorldId().equals(sharedCurrent.get()), "explicit historical browsing is preserved when the shared current season changes");
            var beforeFollow = gateway.context();
            gateway.followCurrentWorld().get(5, TimeUnit.SECONDS);
            require(gateway.worldId.equals(sharedCurrent.get()) && !gateway.isCurrent(beforeFollow), "returning to the current season discards historical context and follows the current pointer");
            gateway.selectWorld("b".repeat(24));
            gateway.refresh().get(5, TimeUnit.SECONDS);
            gateway.selectWorld(sharedCurrent.get());
            gateway.refresh().get(5, TimeUnit.SECONDS);
            var currentSelectionContext = gateway.context();
            var currentSelectionSnapshot = gateway.snapshot;
            gateway.selectWorld(gateway.worldId);
            require(gateway.isCurrent(currentSelectionContext) && gateway.snapshot == currentSelectionSnapshot, "selecting the already-current season preserves loaded context");
            sharedCurrent.set("f".repeat(24));
            gateway.refresh().get(5, TimeUnit.SECONDS);
            require(gateway.worldId.equals(sharedCurrent.get()), "explicitly choosing the shared current season resumes following future mayor changes");
            sharedCurrent.set("e".repeat(24));
            delaySharedSnapshot.set(true);
            var staleSharedRefresh = gateway.refresh();
            require(sharedStarted.await(5, TimeUnit.SECONDS), "an older current-season snapshot is in flight");
            sharedCurrent.set("f".repeat(24));
            gateway.followCurrentWorld().get(5, TimeUnit.SECONDS);
            sharedRelease.countDown();
            staleSharedRefresh.get(5, TimeUnit.SECONDS);
            require(gateway.worldId.equals(sharedCurrent.get()) && gateway.currentWorldId().equals(sharedCurrent.get()), "an old response cannot overwrite the latest followed season");
            gateway.townId = "same";
            CompletableFuture<JsonObject> oldRefresh = gateway.refresh();
            require(refreshStarted.await(5, TimeUnit.SECONDS), "same selection refresh is in flight");
            gateway.refresh().get(5, TimeUnit.SECONDS);
            refreshRelease.countDown();
            oldRefresh.get(5, TimeUnit.SECONDS);
            require(gateway.snapshot.getAsJsonObject("town").get("id").getAsString().equals("same"), "older refresh cannot overwrite a newer snapshot of the same selection");
            gateway.selectWorld("b".repeat(24));
            gateway.refresh().get(5, TimeUnit.SECONDS);
            TownGateway.Context previousSession = gateway.context();
            gateway.request("expired", TownGateway.object()).handle((value, error) -> null).join();
            require(!gateway.isCurrent(previousSession), "session expiry invalidates pending screen operations");
            require(!gateway.loggedIn() && gateway.towns.isEmpty() && gateway.snapshot.isEmpty() && gateway.player().isEmpty(), "expired session clears private cached data");
            require(gateway.status.equals(TownText.text("yat.error.service_request")), "expired session preserves visible error");
            gateway.login(playerId, "Sam").get(5, TimeUnit.SECONDS);
            gateway.townId = "shared";
            gateway.worldId = "b".repeat(24);
            gateway.refresh().get(5, TimeUnit.SECONDS);
            require(gateway.worldId.equals(sharedCurrent.get()), "a fresh session follows the current season by default");
            CompletableFuture<JsonObject> login = gateway.login(playerId, "Delayed");
            require(loginStarted.await(5, TimeUnit.SECONDS), "identity connection is in flight");
            gateway.logout();
            require(revoked.await(5, TimeUnit.SECONDS), "logout revokes the external service session");
            loginRelease.countDown();
            login.handle((value, error) -> null).get(5, TimeUnit.SECONDS);
            require(!gateway.loggedIn(), "logout cancels a pending login");
            require(staleLoginRevoked.await(5, TimeUnit.SECONDS), "a login token issued after cancellation is revoked remotely");
            require(!Files.readString(directory.resolve("yetanothertown.json")).contains("test-token"), "session token never persisted");
            failTownList.set(true);
            require(gateway.login(playerId, "Sam").handle((value, error) -> error != null).get(5, TimeUnit.SECONDS), "town list failure fails identity initialization");
            require(!gateway.loggedIn() && gateway.townId.isEmpty() && gateway.towns.isEmpty() && gateway.player().isEmpty(), "incomplete login clears session so automatic identity retry remains possible");
            gateway.login(playerId, "Sam").get(5, TimeUnit.SECONDS);
            require(gateway.loggedIn() && gateway.townId.equals("00000001"), "identity retry recovers selected town after transient town list failure");
            customTownList.set(JsonParser.parseString("[{\"id\":\"11111111\",\"server\":\"a.example\"},{\"id\":\"22222222\",\"server\":\"a.example:25565\"},{\"id\":\"33333333\",\"server\":\"b.example\"}]").getAsJsonArray());
            gateway.setGameServerAddress(" A.EXAMPLE:25565 ");
            gateway.login(playerId, "Sam").get(5, TimeUnit.SECONDS);
            require(claimedIdentity.get().get("serverAddress").getAsString().equals("a.example"), "login scopes the session to normalized game server");
            require(gateway.towns.size() == 2 && gateway.townId.equals("11111111"), "automatic selection uses only matching server towns");
            gateway.selectTown("22222222");
            gateway.refresh().get(5, TimeUnit.SECONDS);
            var serverContext = gateway.context();
            require(!gateway.setGameServerAddress("a.example:25565") && gateway.isCurrent(serverContext), "same-server normalization keeps active session and selected world");
            gateway.setGameServerAddress("b.example");
            require(!gateway.isCurrent(serverContext) && !gateway.loggedIn() && gateway.towns.isEmpty() && gateway.snapshot.isEmpty() && gateway.townId.isEmpty(), "switching servers immediately clears previous town data and invalidates requests");
            gateway.login(playerId, "Sam").get(5, TimeUnit.SECONDS);
            require(gateway.towns.size() == 1 && gateway.townId.equals("33333333"), "another server chooses its own town");
            gateway.initialize(directory);
            gateway.setGameServerAddress("a.example");
            gateway.login(playerId, "Sam").get(5, TimeUnit.SECONDS);
            require(gateway.townId.equals("22222222"), "last selected town survives configuration reload per game server");
            gateway.setGameServerAddress("empty.example");
            gateway.login(playerId, "Sam").get(5, TimeUnit.SECONDS);
            require(gateway.towns.isEmpty() && gateway.townId.isEmpty() && gateway.snapshot.isEmpty(), "new server does not fall back to another server town");
            require(TownGateway.normalizeServerAddress("[::1]:25565").equals("[::1]") && TownGateway.normalizeServerAddress("a.example:25566").equals("a.example:25566"), "default port normalization preserves nondefault ports and bracketed IPv6");
            pollingReferences(gateway, sharedCurrent.get(), sharedMetadata, customTownList);
        } finally {
            slowRelease.countDown();
            refreshRelease.countDown();
            loginRelease.countDown();
            townListRelease.countDown();
            sharedRelease.countDown();
            server.stop(0);
            oversizedRelease.countDown();
            executor.close();
        }
        loadingLifecycle(gateway);
        System.out.println("TownGateway: " + checks + " checks passed (no game launched)");
    }

    private static void loadingLifecycle(TownGateway gateway) throws Exception {
        var pending = new java.util.concurrent.LinkedBlockingQueue<com.sun.net.httpserver.HttpExchange>();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api", pending::add);
        server.start();
        try {
            gateway.initialize(Files.createTempDirectory("yat-loading-"));
            gateway.configure("http://127.0.0.1:" + server.getAddress().getPort());
            gateway.setGameServerAddress("loading.example");
            var login = gateway.login("a".repeat(32), "Sam");
            require(gateway.isLoading(), "login immediately hides the incomplete identity page");
            reply(pending, "auth.login", TownGateway.object("token", "loading-token", "player", TownGateway.object("name", "Sam")), 200);
            var townList = receive(pending, "town.list");
            require(gateway.loggedIn() && gateway.isLoading(), "issued token does not reveal the page while the first town list is pending");
            reply(townList, JsonParser.parseString("[{\"id\":\"00000001\",\"server\":\"loading.example\"}]"), 200);
            var firstSnapshot = receive(pending, "town.snapshot");
            login.get(5, TimeUnit.SECONDS);
            require(gateway.isLoading(), "completed login retains loading while its selected town snapshot is pending");
            var content = TownGateway.object("town", TownGateway.object("id", "00000001", "currentWorldId", "e".repeat(24)), "worlds", new com.google.gson.JsonArray(), "projects", new com.google.gson.JsonArray());
            reply(firstSnapshot, content, 200);
            var selectedSnapshot = receive(pending, "town.snapshot");
            require(gateway.worldId.equals("e".repeat(24)) && gateway.isLoading(), "automatic world selection keeps loading continuously through the second snapshot");
            reply(selectedSnapshot, content, 200);
            await(() -> !gateway.isLoading());
            require(gateway.snapshot.equals(content), "only the complete initial snapshot ends loading");
            JsonObject reference = gateway.snapshot;
            var poll = gateway.refresh();
            require(!gateway.isLoading(), "background polling with a complete snapshot does not hide content");
            reply(pending, "town.snapshot", content, 200);
            poll.get(5, TimeUnit.SECONDS);
            require(gateway.snapshot == reference, "unchanged background polling keeps the published snapshot reference");

            gateway.selectWorld("f".repeat(24));
            var superseded = receive(pending, "town.snapshot");
            var oldRefresh = gateway.refresh();
            var oldResponse = receive(pending, "town.snapshot");
            gateway.selectWorld("b".repeat(24));
            var currentResponse = receive(pending, "town.snapshot");
            reply(oldResponse, content, 200);
            oldRefresh.get(5, TimeUnit.SECONDS);
            reply(superseded, content, 200);
            require(gateway.isLoading(), "superseded snapshot completion cannot end the newer selection loading");
            reply(currentResponse, TownGateway.object(), 503);
            await(() -> !gateway.isLoading());
            require(gateway.loggedIn(), "failed initial snapshot stops loading and preserves the session for polling retry");

            gateway.selectTown("00000002");
            var cancelledSnapshot = receive(pending, "town.snapshot");
            require(gateway.isLoading(), "new town selection begins loading immediately");
            gateway.logout();
            require(!gateway.isLoading(), "logout clears loading before pending requests finish");
            reply(cancelledSnapshot, content, 200);
            reply(pending, "auth.logout", TownGateway.object(), 200);
            var failedLogin = gateway.login("a".repeat(32), "Retry");
            reply(pending, "auth.login", TownGateway.object("token", "retry-token", "player", TownGateway.object()), 200);
            reply(pending, "town.list", TownGateway.object(), 503);
            require(failedLogin.handle((value, error) -> error != null).get(5, TimeUnit.SECONDS) && !gateway.isLoading() && !gateway.loggedIn(), "failed town initialization stops loading and leaves identity retry possible");
            reply(pending, "auth.logout", TownGateway.object(), 200);

            var oldLogin = gateway.login("a".repeat(32), "Old");
            var oldIdentity = receive(pending, "auth.login");
            gateway.setGameServerAddress("new-loading.example");
            require(!gateway.isLoading(), "server change immediately clears the cancelled loading state");
            var newLogin = gateway.login("a".repeat(32), "New");
            var newIdentity = receive(pending, "auth.login");
            reply(oldIdentity, TownGateway.object("token", "old-token", "player", TownGateway.object()), 200);
            require(oldLogin.handle((value, error) -> error != null).get(5, TimeUnit.SECONDS) && gateway.isLoading(), "old identity completion cannot clear a new login spinner");
            reply(pending, "auth.logout", TownGateway.object(), 200);
            reply(newIdentity, TownGateway.object("token", "new-token", "player", TownGateway.object()), 200);
            reply(pending, "town.list", new com.google.gson.JsonArray(), 200);
            newLogin.get(5, TimeUnit.SECONDS);
            require(!gateway.isLoading() && gateway.loggedIn() && gateway.townId.isEmpty(), "successful login with no towns ends loading at the usable empty state");
        } finally {
            server.stop(0);
            executor.close();
        }
    }

    private static com.sun.net.httpserver.HttpExchange receive(java.util.concurrent.BlockingQueue<com.sun.net.httpserver.HttpExchange> pending, String action) throws Exception {
        var exchange = pending.poll(5, TimeUnit.SECONDS);
        require(exchange != null, "HTTP request reaches the controlled " + action + " boundary");
        JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        require(request.get("action").getAsString().equals(action), "controlled HTTP request is " + action);
        return exchange;
    }

    private static void reply(java.util.concurrent.BlockingQueue<com.sun.net.httpserver.HttpExchange> pending, String action, com.google.gson.JsonElement data, int status) throws Exception {
        reply(receive(pending, action), data, status);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, com.google.gson.JsonElement data, int status) throws Exception {
        byte[] body = TownGateway.object("ok", true, "data", data).toString().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        require(condition.getAsBoolean(), "asynchronous loading settles within the controlled response deadline");
    }

    private static void pollingReferences(TownGateway gateway, String worldId, AtomicReference<JsonObject> metadata,
                                          AtomicReference<com.google.gson.JsonArray> townList) throws Exception {
        gateway.setGameServerAddress("polling.example");
        gateway.townId = "shared";
        gateway.worldId = worldId;
        gateway.refresh().get(5, TimeUnit.SECONDS);
        var snapshot = gateway.snapshot;
        var context = gateway.context();
        gateway.refresh().get(5, TimeUnit.SECONDS);
        require(gateway.snapshot == snapshot && gateway.isCurrent(context), "unchanged snapshot polling preserves the published reference and operation context");
        JsonObject changedMetadata = metadata.get().deepCopy();
        changedMetadata.addProperty("name", "轮询更新周目");
        metadata.set(changedMetadata);
        gateway.refresh().get(5, TimeUnit.SECONDS);
        require(gateway.snapshot != snapshot && gateway.snapshot.getAsJsonArray("worlds").get(0).getAsJsonObject().get("name").getAsString().equals("轮询更新周目"), "changed snapshot polling publishes fresh content");
        snapshot = gateway.snapshot;
        gateway.refresh().get(5, TimeUnit.SECONDS);
        require(gateway.snapshot == snapshot, "polling after a snapshot change retains the new reference while content stays equal");
        townList.set(new com.google.gson.JsonArray());
        gateway.refreshTowns().get(5, TimeUnit.SECONDS);
        var towns = gateway.towns;
        gateway.refreshTowns().get(5, TimeUnit.SECONDS);
        require(gateway.towns == towns, "unchanged town list polling preserves the published reference");
        townList.get().add(TownGateway.object("id", "00000002", "name", "轮询更新小镇", "server", "polling.example"));
        gateway.refreshTowns().get(5, TimeUnit.SECONDS);
        require(gateway.towns != towns && gateway.towns.size() == 1, "changed town list polling publishes fresh content");
        towns = gateway.towns;
        gateway.refreshTowns().get(5, TimeUnit.SECONDS);
        require(gateway.towns == towns, "polling after a town list change retains the new reference while content stays equal");
    }
}
