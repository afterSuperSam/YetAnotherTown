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

/** 不加载游戏，验证连接边界、正版会话握手和异步缓存隔离。 */
public final class TownGatewayTest {
    private static int checks;
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] arguments) throws Exception {
        TownGateway gateway = TownGateway.INSTANCE;
        var directory = Files.createTempDirectory("yat-gateway-test-");
        gateway.initialize(directory);
        require(Files.exists(directory.resolve("yetanothertown.json")), "default configuration saved");
        for (String invalid : new String[]{"http://example.com", "ftp://localhost", "https://example.com/api", "http://", "https://name:secret@example.com"}) {
            boolean rejected = false;
            try { gateway.configure(invalid); } catch (IllegalArgumentException exception) { rejected = true; }
            require(rejected, "unsafe or malformed endpoint rejected: " + invalid);
        }
        AtomicReference<String> authorization = new AtomicReference<>("");
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowRelease = new CountDownLatch(1);
        CountDownLatch revoked = new CountDownLatch(1);
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
                case "auth.challenge" -> body = "{\"ok\":true,\"data\":{\"challengeId\":\"challenge\",\"serverHash\":\"server-hash\"}}";
                case "auth.verify" -> body = "{\"ok\":true,\"data\":{\"token\":\"test-token\",\"player\":{\"id\":\"player-id\",\"name\":\"Sam\"}}}";
                case "auth.logout" -> { revoked.countDown(); body = "{\"ok\":true,\"data\":{}}"; }
                case "town.list" -> body = "{\"ok\":true,\"data\":[{\"id\":\"00000001\",\"name\":\"测试镇\"}]}";
                case "town.snapshot" -> {
                    String town = payload.get("townId").getAsString();
                    if (town.equals("slow")) {
                        slowStarted.countDown();
                        try { slowRelease.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                    }
                    body = "{\"ok\":true,\"data\":{\"town\":{\"id\":\"" + town + "\"},\"worlds\":[]}}";
                }
                case "denied" -> { status = 403; body = "{\"ok\":false,\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"没有操作权限\"}}"; }
                case "expired" -> { status = 401; body = "{\"ok\":false,\"error\":{\"code\":\"unauthenticated\",\"message\":\"登录已过期\"}}"; }
                case "malformed" -> body = "not JSON";
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
            AtomicReference<String> joined = new AtomicReference<>();
            gateway.login("Sam", joined::set).get(5, TimeUnit.SECONDS);
            require("server-hash".equals(joined.get()), "official client join receives challenge hash");
            require(gateway.loggedIn() && gateway.player().get("name").getAsString().equals("Sam"), "verified identity retained");
            require(gateway.towns.size() == 1, "array envelope populates town list");
            require("Bearer test-token".equals(authorization.get()), "subsequent requests use session token");
            gateway.request("denied", TownGateway.object()).handle((value, error) -> null).join();
            require(gateway.status.equals("没有操作权限"), "service errors remain Chinese");
            gateway.request("malformed", TownGateway.object()).handle((value, error) -> null).join();
            require(gateway.status.equals("服务返回格式错误"), "invalid protocol explicitly classified");
            gateway.selectTown("slow");
            require(slowStarted.await(5, TimeUnit.SECONDS), "old response is in flight");
            gateway.selectTown("fast");
            gateway.refresh().get(5, TimeUnit.SECONDS);
            slowRelease.countDown();
            gateway.request("noop", TownGateway.object()).get(5, TimeUnit.SECONDS);
            require(gateway.snapshot.getAsJsonObject("town").get("id").getAsString().equals("fast"), "old town response cannot overwrite selection");
            gateway.request("expired", TownGateway.object()).handle((value, error) -> null).join();
            require(!gateway.loggedIn() && gateway.towns.isEmpty() && gateway.snapshot.isEmpty() && gateway.player().isEmpty(), "expired session clears private cached data");
            require(gateway.status.equals("登录已过期"), "expired session preserves visible error");
            gateway.login("Sam", joined::set).get(5, TimeUnit.SECONDS);
            CountDownLatch joining = new CountDownLatch(1);
            CountDownLatch joinRelease = new CountDownLatch(1);
            CompletableFuture<JsonObject> login = gateway.login("Sam", hash -> {
                joining.countDown();
                try { joinRelease.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            });
            require(joining.await(5, TimeUnit.SECONDS), "join is in flight");
            gateway.logout();
            require(revoked.await(5, TimeUnit.SECONDS), "logout revokes the external service session");
            joinRelease.countDown();
            login.handle((value, error) -> null).get(5, TimeUnit.SECONDS);
            require(!gateway.loggedIn(), "logout cancels a pending login");
            require(!Files.readString(directory.resolve("yetanothertown.json")).contains("test-token"), "session token never persisted");
        } finally {
            slowRelease.countDown();
            server.stop(0);
            executor.close();
        }
        System.out.println("TownGateway: " + checks + " checks passed (no game launched)");
    }
}
