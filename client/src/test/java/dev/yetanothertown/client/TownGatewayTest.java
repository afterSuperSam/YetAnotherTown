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
    public static void main(String[] arguments) throws Exception {
        TownGateway gateway = TownGateway.INSTANCE;
        for (String invalid : new String[]{"{\"keyBindingsVersion\":1,\"endpoint\":\"https://old.example.test\"}", "{", "null", "[]", "{\"pollIntervalSeconds\":0}"}) {
            var brokenDirectory = Files.createTempDirectory("yat-invalid-config-");
            var original = brokenDirectory.resolve("yetanothertown.json");
            Files.writeString(original, invalid);
            gateway.initialize(brokenDirectory);
            require(gateway.config().endpoint.equals("http://127.0.0.1:37841"), "invalid configuration starts with fresh defaults without crashing");
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
        var directory = Files.createTempDirectory("yat-gateway-test-");
        gateway.initialize(directory);
        require(Files.exists(directory.resolve("yetanothertown.json")), "default configuration saved");
        for (String invalid : new String[]{"http://example.com", "ftp://localhost", "https://example.com/api", "http://", "https://name:secret@example.com", "https://example.com:0", "https://example.com:65536"}) {
            boolean rejected = false;
            try { gateway.configure(invalid); } catch (IllegalArgumentException exception) { rejected = true; }
            require(rejected, "unsafe or malformed endpoint rejected: " + invalid);
        }
        AtomicReference<String> authorization = new AtomicReference<>("");
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowRelease = new CountDownLatch(1);
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch refreshRelease = new CountDownLatch(1);
        var refreshCount = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch revoked = new CountDownLatch(1);
        CountDownLatch loginStarted = new CountDownLatch(1);
        CountDownLatch loginRelease = new CountDownLatch(1);
        CountDownLatch oversizedRelease = new CountDownLatch(1);
        AtomicReference<JsonObject> claimedIdentity = new AtomicReference<>();
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
                    body = "{\"ok\":true,\"data\":{\"token\":\"test-token\",\"player\":{\"id\":\"" + payload.get("playerId").getAsString() + "\",\"name\":\"" + payload.get("name").getAsString() + "\"}}}";
                }
                case "auth.logout" -> { revoked.countDown(); body = "{\"ok\":true,\"data\":{}}"; }
                case "town.list" -> body = "{\"ok\":true,\"data\":[{\"id\":\"00000001\",\"name\":\"测试镇\"}]}";
                case "town.snapshot" -> {
                    String town = payload.get("townId").getAsString();
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
                    body = "{\"ok\":true,\"data\":{\"town\":{\"id\":\"" + town + "\"},\"worlds\":" + worlds + "}}";
                }
                case "denied" -> { status = 403; body = "{\"ok\":false,\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"没有操作权限\"}}"; }
                case "expired" -> { status = 401; body = "{\"ok\":false,\"error\":{\"code\":\"unauthenticated\",\"message\":\"登录已过期\"}}"; }
                case "malformed" -> body = "not JSON";
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
            require(claimedIdentity.get().size() == 2, "identity exchange sends no launcher access token or external verification data");
            require(gateway.loggedIn() && gateway.player().get("name").getAsString().equals("Sam"), "current game identity retained");
            require(gateway.towns.size() == 1, "array envelope populates town list");
            require("Bearer test-token".equals(authorization.get()), "subsequent requests use session token");
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
            var currentWorld = TownGateway.object("id", gateway.worldId, "archived", false, "serverAddress", "town.example");
            worlds.add(currentWorld);
            gateway.snapshot = TownGateway.object("worlds", worlds);
            require(!gateway.autoBindMap("different.example", "minecraft:overworld", "personal-server|third"), "automatic binding must match the current server");
            require(!gateway.autoBindMap("town.example", "minecraft:overworld", "personal-server|first"), "automatic binding cannot reuse historical terrain");
            require(gateway.autoBindMap("TOWN.EXAMPLE", "minecraft:overworld", "personal-server|third"), "a unique active world binds automatically without a management detour");
            require(!gateway.autoBindMap("town.example", "minecraft:overworld", "personal-server|replacement") && gateway.getBoundMapKey("minecraft:overworld").equals("personal-server|third"), "automatic binding never overwrites an existing map");
            worlds.add(TownGateway.object("id", "d".repeat(24), "archived", false, "serverAddress", "town.example"));
            require(!gateway.autoBindMap("town.example", "minecraft:the_nether", "personal-server|third"), "ambiguous active worlds require explicit binding");
            worlds.remove(1);
            currentWorld.addProperty("archived", true);
            require(!gateway.autoBindMap("town.example", "minecraft:the_nether", "personal-server|third"), "archived worlds cannot bind automatically");
            currentWorld.addProperty("archived", false);
            gateway.worldId = "d".repeat(24);
            require(!gateway.autoBindMap("town.example", "minecraft:the_nether", "personal-server|third"), "the automatically matched world must be the selected world");
            gateway.townId = savedTown; gateway.worldId = savedWorld; gateway.snapshot = savedSnapshot;
            gateway.request("denied", TownGateway.object()).handle((value, error) -> null).join();
            require(gateway.status.equals("没有操作权限"), "service errors remain Chinese");
            gateway.request("malformed", TownGateway.object()).handle((value, error) -> null).join();
            require(gateway.status.equals("服务返回格式错误"), "invalid protocol explicitly classified");
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
            gateway.townId = "same";
            CompletableFuture<JsonObject> oldRefresh = gateway.refresh();
            require(refreshStarted.await(5, TimeUnit.SECONDS), "same selection refresh is in flight");
            gateway.refresh().get(5, TimeUnit.SECONDS);
            refreshRelease.countDown();
            oldRefresh.get(5, TimeUnit.SECONDS);
            require(gateway.snapshot.getAsJsonObject("town").get("id").getAsString().equals("same"), "older refresh cannot overwrite a newer snapshot of the same selection");
            TownGateway.Context previousSession = gateway.context();
            gateway.request("expired", TownGateway.object()).handle((value, error) -> null).join();
            require(!gateway.isCurrent(previousSession), "session expiry invalidates pending screen operations");
            require(!gateway.loggedIn() && gateway.towns.isEmpty() && gateway.snapshot.isEmpty() && gateway.player().isEmpty(), "expired session clears private cached data");
            require(gateway.status.equals("登录已过期"), "expired session preserves visible error");
            gateway.login(playerId, "Sam").get(5, TimeUnit.SECONDS);
            CompletableFuture<JsonObject> login = gateway.login(playerId, "Delayed");
            require(loginStarted.await(5, TimeUnit.SECONDS), "identity connection is in flight");
            gateway.logout();
            require(revoked.await(5, TimeUnit.SECONDS), "logout revokes the external service session");
            loginRelease.countDown();
            login.handle((value, error) -> null).get(5, TimeUnit.SECONDS);
            require(!gateway.loggedIn(), "logout cancels a pending login");
            require(!Files.readString(directory.resolve("yetanothertown.json")).contains("test-token"), "session token never persisted");
        } finally {
            slowRelease.countDown();
            refreshRelease.countDown();
            loginRelease.countDown();
            server.stop(0);
            oversizedRelease.countDown();
            executor.close();
        }
        System.out.println("TownGateway: " + checks + " checks passed (no game launched)");
    }
}
