package dev.yetanothertown.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

/** 管理服务连接、临时会话和已选小镇的资料缓存。 */
public final class TownGateway {
    public static final TownGateway INSTANCE = new TownGateway();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final System.Logger LOGGER = System.getLogger("YetAnotherTown");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicLong selection = new AtomicLong();
    private final AtomicLong authentication = new AtomicLong();
    private volatile String token = "";
    private volatile JsonObject player = new JsonObject();
    private volatile ClientConfig configuration = new ClientConfig();
    private Path configurationPath;
    public volatile JsonObject snapshot = new JsonObject();
    public volatile JsonArray towns = new JsonArray();
    public volatile String status = "进入世界后自动读取游戏身份并连接小镇服务";
    public volatile String townId = "";
    public volatile String worldId = "";

    private TownGateway() {}

    public static TownGateway get() { return INSTANCE; }
    public ClientConfig config() { return configuration; }
    public JsonObject player() { return player.deepCopy(); }
    public boolean loggedIn() { return !token.isEmpty(); }

    public synchronized void initialize(Path directory) {
        configurationPath = directory.resolve("yetanothertown.json");
        try {
            if (Files.exists(configurationPath)) {
                JsonObject parsed = JSON.fromJson(Files.readString(configurationPath), JsonObject.class);
                if (parsed == null) throw new IllegalArgumentException("客户端配置不能为空");
                Set<String> allowed = Set.of("endpoint", "openKey", "nearbyKey", "keyBindingsVersion", "nearbyRadius", "pollIntervalSeconds", "animationEnabled", "mapBindings");
                for (String key : parsed.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("未知客户端配置项：" + key);
                for (String key : parsed.keySet()) {
                    JsonElement value = parsed.get(key);
                    if (key.equals("mapBindings")) {
                        if (!value.isJsonObject()) throw new IllegalArgumentException("个人地图绑定必须是对象");
                        for (var binding : value.getAsJsonObject().entrySet())
                            if (!binding.getValue().isJsonPrimitive() || !binding.getValue().getAsJsonPrimitive().isString())
                                throw new IllegalArgumentException("个人地图绑定必须是文本");
                        continue;
                    }
                    boolean valid = value.isJsonPrimitive();
                    if (valid) {
                        var primitive = value.getAsJsonPrimitive();
                        valid = switch (key) {
                            case "endpoint" -> primitive.isString();
                            case "animationEnabled" -> primitive.isBoolean();
                            default -> primitive.isNumber() && primitive.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
                        };
                    }
                    if (!valid) throw new IllegalArgumentException("客户端配置类型错误：" + key);
                }
                parsed.remove("openKey");
                parsed.remove("nearbyKey");
                ClientConfig loaded = JSON.fromJson(parsed, ClientConfig.class);
                loaded.validate();
                configuration = loaded;
            } else {
                save();
            }
        } catch (IOException | RuntimeException exception) {
            status = "客户端配置无效：" + exception.getMessage();
            LOGGER.log(System.Logger.Level.ERROR, "Failed to load client configuration", exception);
            throw new IllegalStateException(status, exception);
        }
    }

    public synchronized void configure(String endpoint) {
        configure(endpoint, configuration.nearbyRadius, configuration.pollIntervalSeconds, configuration.animationEnabled);
    }

    public synchronized void configure(String endpoint, int nearbyRadius, int pollIntervalSeconds, boolean animationEnabled) {
        ClientConfig replacement = JSON.fromJson(JSON.toJson(configuration), ClientConfig.class);
        replacement.endpoint = endpoint.strip().replaceAll("/+$", "");
        replacement.nearbyRadius = nearbyRadius;
        replacement.pollIntervalSeconds = pollIntervalSeconds;
        replacement.animationEnabled = animationEnabled;
        replacement.validate();
        ClientConfig previous = configuration;
        String previousToken = token;
        configuration = replacement;
        try { save(); }
        catch (IOException exception) {
            configuration = previous;
            throw new IllegalStateException("保存客户端配置失败", exception);
        }
        if (!previous.endpoint.equals(replacement.endpoint)) {
            clearSession("服务地址已更新，将使用当前游戏身份重新连接");
            revoke(previous.endpoint, previousToken);
        } else status = "客户端配置已保存";
    }

    public boolean needsKeyMigration() { return configuration.keyBindingsVersion == 0; }

    public synchronized void finishKeyMigration() {
        ClientConfig previous = configuration;
        ClientConfig replacement = JSON.fromJson(JSON.toJson(previous), ClientConfig.class);
        replacement.keyBindingsVersion = 1;
        configuration = replacement;
        try { save(); }
        catch (IOException exception) { configuration = previous; throw new IllegalStateException("保存按键迁移状态失败", exception); }
    }

    public String getBoundMapKey(String dimension) {
        return configuration.mapBindings.getOrDefault(bindingKey(dimension), "");
    }

    public synchronized void bindCurrentMap(String dimension, String key) {
        updateBinding(dimension, key);
    }

    public synchronized void unbindMap(String dimension) {
        updateBinding(dimension, null);
    }

    private String bindingKey(String dimension) {
        return configuration.endpoint + "#" + townId + "/" + worldId + "/" + dimension;
    }

    private void updateBinding(String dimension, String key) {
        if (!townId.matches("\\d{8}") || !worldId.matches("[a-f0-9]{24}") || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]{1,128}"))
            throw new IllegalArgumentException("请先选择小镇、周目和有效维度");
        ClientConfig replacement = JSON.fromJson(JSON.toJson(configuration), ClientConfig.class);
        String target = bindingKey(dimension);
        String scope = configuration.endpoint + "#" + townId + "/";
        if (key != null) {
            for (var binding : replacement.mapBindings.entrySet()) {
                String[] identity = binding.getKey().substring(binding.getKey().lastIndexOf('#') + 1).split("/", 3);
                if (binding.getKey().startsWith(scope) && !binding.getKey().equals(target) && identity.length == 3
                        && identity[2].equals(dimension) && binding.getValue().equals(key))
                    throw new IllegalArgumentException("此底图已绑定同镇其他周目；请先在 Xaero 选择独立地图实例，或解除误绑定");
            }
            replacement.mapBindings.put(target, key);
        } else replacement.mapBindings.remove(target);
        replacement.validate();
        ClientConfig previous = configuration;
        configuration = replacement;
        try { save(); }
        catch (IOException exception) { configuration = previous; throw new IllegalStateException("保存个人地图绑定失败", exception); }
        status = key == null ? "已解除个人地图绑定" : "已保存此周目和维度的个人地图绑定";
    }

    private void save() throws IOException {
        configuration.validate();
        if (configurationPath == null) return;
        Files.createDirectories(configurationPath.getParent());
        Path temporary = configurationPath.resolveSibling(configurationPath.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(configuration) + "\n", StandardCharsets.UTF_8);
        try { Files.move(temporary, configurationPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, configurationPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public CompletableFuture<JsonObject> login(String playerId, String name) {
        long attempt = authentication.incrementAndGet();
        status = "正在使用当前游戏身份连接…";
        return request("auth.login", object("playerId", playerId, "name", name)).thenCompose(result -> {
            requireAttempt(attempt);
            token = result.get("token").getAsString();
            player = result.getAsJsonObject("player");
            status = "已连接当前游戏身份";
            return refreshTowns().thenApply(ignored -> result);
        }).exceptionallyCompose(exception -> failed(exception, "连接游戏身份失败，请检查小镇服务"));
    }

    public void logout() {
        String previousToken = token;
        String previousEndpoint = configuration.endpoint;
        clearSession("已退出平台登录");
        revoke(previousEndpoint, previousToken);
    }

    private void clearSession(String message) {
        authentication.incrementAndGet();
        token = "";
        player = new JsonObject();
        towns = new JsonArray();
        townId = "";
        worldId = "";
        snapshot = new JsonObject();
        selection.incrementAndGet();
        status = message;
    }

    private void revoke(String endpoint, String previousToken) {
        if (previousToken.isEmpty()) return;
        long revision = authentication.get();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/api"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + previousToken)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(object("action", "auth.logout", "payload", object())))).build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
            if (error != null || response.statusCode() != 200 && response.statusCode() != 401) {
                LOGGER.log(System.Logger.Level.WARNING, "Remote session revocation failed; local session was cleared");
                if (authentication.get() == revision) status = "本地已退出；服务端注销未完成，会话将在有效期结束后失效";
            }
        });
    }

    public CompletableFuture<JsonObject> request(String action, JsonObject payload) {
        String endpoint = configuration.endpoint;
        String bearer = token;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + "/api"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(object("action", action, "payload", payload))));
        if (!bearer.isEmpty()) builder.header("Authorization", "Bearer " + bearer);
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response -> {
            if (response.body().length() > 8_000_000) throw new CompletionException(new ServiceException("服务返回的数据过大"));
            JsonObject envelope;
            try { envelope = JSON.fromJson(response.body(), JsonObject.class); }
            catch (RuntimeException exception) { throw new CompletionException(new ServiceException("服务返回格式错误")); }
            if (envelope == null || !envelope.has("ok")) throw new CompletionException(new ServiceException("服务协议不匹配"));
            if (!envelope.get("ok").getAsBoolean()) {
                JsonObject error = envelope.getAsJsonObject("error");
                String message = error != null && error.has("message") ? error.get("message").getAsString() : "服务请求失败";
                if (response.statusCode() == 401 && !action.startsWith("auth.") && token.equals(bearer)) clearSession(message);
                throw new CompletionException(new ServiceException(message));
            }
            JsonElement data = envelope.get("data");
            if (data != null && data.isJsonObject()) return data.getAsJsonObject();
            return object("items", data == null ? new JsonArray() : data);
        }).exceptionallyCompose(exception -> failed(exception, "连接服务失败，请检查地址和服务状态"));
    }

    private <T> CompletableFuture<T> failed(Throwable exception, String fallback) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause instanceof CompletionException) cause = cause.getCause();
        status = cause instanceof ServiceException ? cause.getMessage() : fallback;
        LOGGER.log(System.Logger.Level.WARNING, "Town service request failed: " + cause.getClass().getSimpleName());
        return CompletableFuture.failedFuture(cause);
    }

    public CompletableFuture<JsonObject> refreshTowns() {
        long revision = selection.get();
        return request("town.list", object()).thenApply(result -> {
            if (selection.get() == revision && result.has("items")) towns = result.getAsJsonArray("items");
            return result;
        });
    }

    public CompletableFuture<JsonObject> refresh() {
        String selectedTown = townId;
        String selectedWorld = worldId;
        long revision = selection.get();
        if (selectedTown.isEmpty()) return CompletableFuture.completedFuture(snapshot);
        return request("town.snapshot", object("townId", selectedTown, "worldId", selectedWorld)).thenCompose(result -> {
            if (selection.get() != revision || !townId.equals(selectedTown) || !worldId.equals(selectedWorld)) return CompletableFuture.completedFuture(result);
            if (selectedWorld.isEmpty() && result.has("worlds")) {
                for (JsonElement element : result.getAsJsonArray("worlds")) {
                    JsonObject world = element.getAsJsonObject();
                    if (!world.get("archived").getAsBoolean()) { worldId = world.get("id").getAsString(); break; }
                }
                if (worldId.isEmpty() && !result.getAsJsonArray("worlds").isEmpty()) worldId = result.getAsJsonArray("worlds").get(0).getAsJsonObject().get("id").getAsString();
                if (!worldId.isEmpty()) return refresh();
            }
            snapshot = result;
            return CompletableFuture.completedFuture(result);
        });
    }

    public void selectTown(String id) {
        selection.incrementAndGet();
        townId = id;
        worldId = "";
        snapshot = new JsonObject();
        refresh();
    }

    public void selectWorld(String id) {
        selection.incrementAndGet();
        worldId = id;
        snapshot = new JsonObject();
        refresh();
    }

    public static JsonObject object(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("JSON字段必须成对提供");
        JsonObject result = new JsonObject();
        for (int index = 0; index < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            result.add(pairs[index].toString(), value instanceof JsonElement element ? element : JSON.toJsonTree(value));
        }
        return result;
    }

    private void requireAttempt(long attempt) {
        if (authentication.get() != attempt) throw new CompletionException(new ServiceException("登录操作已取消"));
    }

    private static final class ServiceException extends IOException {
        private static final long serialVersionUID = 1L;
        ServiceException(String message) { super(message); }
    }

    public static final class ClientConfig {
        public String endpoint = "http://127.0.0.1:37841";
        public int keyBindingsVersion = 0;
        public int nearbyRadius = 48;
        public int pollIntervalSeconds = 10;
        public boolean animationEnabled = true;
        public Map<String, String> mapBindings = new TreeMap<>();

        public void validate() {
            URI address;
            try { address = URI.create(endpoint); }
            catch (RuntimeException exception) { throw new IllegalArgumentException("服务地址格式错误"); }
            boolean local = address.getHost() != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(address.getHost());
            if (address.getHost() == null || address.getUserInfo() != null || address.getQuery() != null || address.getFragment() != null
                    || (address.getPath() != null && !address.getPath().isEmpty())
                    || !("https".equals(address.getScheme()) || "http".equals(address.getScheme()) && local))
                throw new IllegalArgumentException("服务地址需为 HTTPS；本地服务可使用 HTTP，地址不带路径");
            if (keyBindingsVersion < 0 || keyBindingsVersion > 1) throw new IllegalArgumentException("按键迁移版本无效");
            if (nearbyRadius < 1 || nearbyRadius > 512) throw new IllegalArgumentException("附近提示距离需在 1～512 格之间");
            if (pollIntervalSeconds < 2 || pollIntervalSeconds > 300) throw new IllegalArgumentException("刷新间隔需在 2～300 秒之间");
            if (mapBindings == null || mapBindings.size() > 10000) throw new IllegalArgumentException("个人地图绑定最多保存 10000 项");
            for (var binding : mapBindings.entrySet()) {
                if (!binding.getKey().matches("https?://[^#]{1,2048}#\\d{8}/[a-f0-9]{24}/[a-z0-9_.-]+:[a-z0-9_/.-]{1,128}")
                        || binding.getValue() == null || binding.getValue().length() > 512 || binding.getValue().indexOf('|') <= 0)
                    throw new IllegalArgumentException("个人地图绑定的周目、维度或地图标识无效");
            }
        }
    }
}
