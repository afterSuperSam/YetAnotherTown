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
        for (String invalid : new String[]{"{\"keyBindingsVersion\":1,\"endpoint\":\"https://old.example.test\"}", "{", "null", "[]", "{\"pollIntervalSeconds\":0}", "{\"pollIntervalSeconds\":4294967306}"}) {
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
        CountDownLatch staleLoginRevoked = new CountDownLatch(1);
        CountDownLatch loginStarted = new CountDownLatch(1);
        CountDownLatch loginRelease = new CountDownLatch(1);
        CountDownLatch townListStarted = new CountDownLatch(1);
        CountDownLatch townListRelease = new CountDownLatch(1);
        var delayTownList = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch oversizedRelease = new CountDownLatch(1);
        AtomicReference<String> sharedCurrent = new AtomicReference<>("e".repeat(24));
        AtomicReference<JsonObject> sharedMetadata = new AtomicReference<>(TownGateway.object("serverAddress", "town.example", "mapName", "world", "mapNames", TownGateway.object("minecraft:the_nether", "nether"), "name", "原周目名称"));
        var sharedSnapshots = new java.util.concurrent.atomic.AtomicInteger();
        var delaySharedSnapshot = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch sharedStarted = new CountDownLatch(1);
        CountDownLatch sharedRelease = new CountDownLatch(1);
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
                    String issuedToken = payload.get("name").getAsString().equals("Delayed") ? "delayed-token" : "test-token";
                    body = "{\"ok\":true,\"data\":{\"token\":\"" + issuedToken + "\",\"player\":{\"id\":\"" + payload.get("playerId").getAsString() + "\",\"name\":\"" + payload.get("name").getAsString() + "\"}}}";
                }
                case "auth.logout" -> {
                    revoked.countDown();
                    if ("Bearer delayed-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) staleLoginRevoked.countDown();
                    body = "{\"ok\":true,\"data\":{}}";
                }
                case "town.list" -> {
                    boolean delayed = delayTownList.getAndSet(false);
                    if (delayed) {
                        townListStarted.countDown();
                        try { townListRelease.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                    }
                    body = "{\"ok\":true,\"data\":[{\"id\":\"" + (delayed ? "outdated" : "00000001") + "\",\"name\":\"测试镇\"}]}";
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
        System.out.println("TownGateway: " + checks + " checks passed (no game launched)");
    }
}
