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
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

/** 管理服务连接、临时会话和已选小镇的资料缓存。 */
public final class TownGateway {
    public static final TownGateway INSTANCE = new TownGateway();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final System.Logger LOGGER = System.getLogger("Yet Another Town");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicLong selection = new AtomicLong();
    private final AtomicLong authentication = new AtomicLong();
    private final AtomicLong snapshots = new AtomicLong();
    private final AtomicLong townLists = new AtomicLong();
    private volatile String token = "";
    private volatile JsonObject player = new JsonObject();
    private volatile ClientConfig configuration = new ClientConfig();
    private Path configurationPath;
    private boolean configurationWritable = true;
    public volatile String configurationNotice = "";
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

    public record Context(long authentication, long selection, String endpoint) { }

    public synchronized Context context() {
        return new Context(authentication.get(), selection.get(), configuration.endpoint);
    }

    public synchronized boolean isCurrent(Context context) {
        return context.authentication() == authentication.get() && context.selection() == selection.get()
                && context.endpoint().equals(configuration.endpoint);
    }

    public synchronized void initialize(Path directory) {
        configurationPath = directory.resolve("yetanothertown.json");
        configuration = new ClientConfig();
        configurationWritable = true;
        configurationNotice = "";
        try {
            if (!Files.notExists(configurationPath)) {
                JsonObject parsed = JSON.fromJson(Files.readString(configurationPath), JsonObject.class);
                if (parsed == null) throw new IllegalArgumentException("客户端配置不能为空");
                Set<String> allowed = Set.of("endpoint", "entrySubtitleSeconds", "entryCooldownSeconds", "projectOverlayEnabled", "pollIntervalSeconds", "animationEnabled", "mapBindings");
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
                            case "animationEnabled", "projectOverlayEnabled" -> primitive.isBoolean();
                            default -> primitive.isNumber() && primitive.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
                        };
                    }
                    if (!valid) throw new IllegalArgumentException("客户端配置类型错误：" + key);
                    if (value.getAsJsonPrimitive().isNumber()) {
                        try { value.getAsBigDecimal().intValueExact(); }
                        catch (ArithmeticException exception) { throw new IllegalArgumentException("客户端配置数值超出范围：" + key); }
                    }
                }
                ClientConfig loaded = JSON.fromJson(parsed, ClientConfig.class);
                loaded.validate();
                configuration = loaded;
            } else {
                save();
            }
        } catch (com.google.gson.JsonParseException | IllegalArgumentException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Invalid client configuration; resetting after backup", exception);
            Path backup = configurationPath.resolveSibling("yetanothertown.invalid-" + java.util.UUID.randomUUID() + ".json");
            try {
                Files.copy(configurationPath, backup);
                save();
                configurationNotice = "旧版或损坏配置已备份为 " + backup.getFileName() + "，当前使用全新默认设置，请重新设置服务地址和地图绑定。";
                status = configurationNotice;
            } catch (IOException failure) { configurationUnavailable(failure); }
        } catch (IOException exception) {
            configurationUnavailable(exception);
        }
    }

    /** 读写失败时保留原文件，禁止后续设置保存覆盖未经读取的配置。 */
    private void configurationUnavailable(IOException exception) {
        configurationWritable = false;
        configurationNotice = "无法读取或保存客户端配置，原文件已保留。请检查 config/yetanothertown.json 的文件权限后重启游戏。";
        status = configurationNotice;
        LOGGER.log(System.Logger.Level.ERROR, "Client configuration unavailable; writes disabled", exception);
    }

    public synchronized void configure(String endpoint) {
        configure(endpoint, configuration.entrySubtitleSeconds, configuration.entryCooldownSeconds, configuration.pollIntervalSeconds, configuration.animationEnabled, configuration.projectOverlayEnabled);
    }

    public synchronized void configure(String endpoint, int entrySubtitleSeconds, int entryCooldownSeconds, int pollIntervalSeconds, boolean animationEnabled, boolean projectOverlayEnabled) {
        ClientConfig replacement = JSON.fromJson(JSON.toJson(configuration), ClientConfig.class);
        replacement.endpoint = endpoint.strip().replaceAll("/+$", "");
        replacement.entrySubtitleSeconds = entrySubtitleSeconds;
        replacement.entryCooldownSeconds = entryCooldownSeconds;
        replacement.projectOverlayEnabled = projectOverlayEnabled;
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
        configurationNotice = "";
        if (!previous.endpoint.equals(replacement.endpoint)) {
            clearSession("服务地址已更新，将使用当前游戏身份重新连接");
            revoke(previous.endpoint, previousToken);
        } else status = "客户端配置已保存";
    }

    public synchronized void setProjectOverlayEnabled(boolean enabled) {
        if (configuration.projectOverlayEnabled == enabled) return;
        configure(configuration.endpoint, configuration.entrySubtitleSeconds, configuration.entryCooldownSeconds, configuration.pollIntervalSeconds, configuration.animationEnabled, enabled);
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

    /** 唯一匹配的当前周目首次关联玩家底图，已有或历史绑定始终保留。 */
    public synchronized boolean autoBindMap(String serverAddress, String dimension, String mapKey) {
        if (!loggedIn() || worldId.isEmpty() || mapKey.isEmpty() || !getBoundMapKey(dimension).isEmpty() || !snapshot.has("worlds")) return false;
        String candidate = "";
        for (JsonElement element : snapshot.getAsJsonArray("worlds")) {
            JsonObject world = element.getAsJsonObject();
            String address = world.has("serverAddress") ? world.get("serverAddress").getAsString() : "";
            if (world.get("archived").getAsBoolean() || address.isBlank() || !address.equalsIgnoreCase(serverAddress)) continue;
            if (!candidate.isEmpty()) return false;
            candidate = world.get("id").getAsString();
        }
        if (!worldId.equals(candidate)) return false;
        String scope = configuration.endpoint + "#" + townId + "/";
        for (var binding : configuration.mapBindings.entrySet())
            if (binding.getKey().startsWith(scope) && binding.getKey().endsWith("/" + dimension) && binding.getValue().equals(mapKey)) return false;
        bindCurrentMap(dimension, mapKey);
        return true;
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
        if (!configurationWritable) throw new IOException("Client configuration is protected after an I/O failure");
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

    public synchronized CompletableFuture<JsonObject> login(String playerId, String name) {
        long attempt = authentication.incrementAndGet();
        String endpoint = configuration.endpoint;
        status = "正在使用当前游戏身份连接…";
        return request("auth.login", object("playerId", playerId, "name", name)).thenCompose(result -> {
            synchronized (this) {
                if (authentication.get() != attempt) revoke(endpoint, result.get("token").getAsString(), false);
                requireAttempt(attempt);
                token = result.get("token").getAsString();
                player = result.getAsJsonObject("player");
                status = "已连接当前游戏身份";
            }
            return refreshTowns().thenApply(ignored -> { requireAttempt(attempt); return result; });
        }).exceptionallyCompose(exception -> failed(exception, "连接游戏身份失败，请检查小镇服务", attempt));
    }

    public synchronized void logout() {
        String previousToken = token;
        String previousEndpoint = configuration.endpoint;
        clearSession("已退出平台登录");
        revoke(previousEndpoint, previousToken);
    }

    private synchronized void clearSession(String message) {
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
        revoke(endpoint, previousToken, true);
    }

    private void revoke(String endpoint, String previousToken, boolean reportFailure) {
        if (previousToken.isEmpty()) return;
        long revision = authentication.get();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/api"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + previousToken)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(object("action", "auth.logout", "payload", object())))).build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
            if (error != null || response.statusCode() != 200 && response.statusCode() != 401) {
                LOGGER.log(System.Logger.Level.WARNING, "Remote session revocation failed; local session was cleared");
                if (reportFailure && authentication.get() == revision) status = "本地已退出；服务端注销未完成，会话将在有效期结束后失效";
            }
        });
    }

    public synchronized CompletableFuture<JsonObject> request(String action, JsonObject payload) {
        String endpoint = configuration.endpoint;
        String bearer = token;
        long revision = authentication.get();
        long selectedRevision = selection.get();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + "/api"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(object("action", action, "payload", payload))));
        if (!bearer.isEmpty()) builder.header("Authorization", "Bearer " + bearer);
        return http.sendAsync(builder.build(), limitedResponseBody()).thenApply(response -> {
            JsonObject envelope;
            try { envelope = JSON.fromJson(response.body(), JsonObject.class); }
            catch (RuntimeException exception) { throw new CompletionException(new ServiceException("服务返回格式错误")); }
            if (envelope == null || !envelope.has("ok") || !envelope.get("ok").isJsonPrimitive()
                    || !envelope.getAsJsonPrimitive("ok").isBoolean()) throw new CompletionException(new ServiceException("服务协议不匹配"));
            if (!envelope.get("ok").getAsBoolean()) {
                if (!envelope.has("error") || !envelope.get("error").isJsonObject())
                    throw new CompletionException(new ServiceException("服务协议不匹配"));
                JsonObject error = envelope.getAsJsonObject("error");
                if (!error.has("message") || !error.get("message").isJsonPrimitive() || !error.getAsJsonPrimitive("message").isString())
                    throw new CompletionException(new ServiceException("服务协议不匹配"));
                String message = error.get("message").getAsString();
                synchronized (this) {
                    if (response.statusCode() == 401 && !action.startsWith("auth.") && authentication.get() == revision && token.equals(bearer)) clearSession(message);
                }
                throw new CompletionException(new ServiceException(message));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new CompletionException(new ServiceException("服务请求失败（HTTP " + response.statusCode() + "）"));
            JsonElement data = envelope.get("data");
            if (data != null && data.isJsonObject()) return data.getAsJsonObject();
            if (data != null && data.isJsonArray()) return object("items", data);
            throw new CompletionException(new ServiceException("服务协议不匹配"));
        }).exceptionallyCompose(exception -> failed(exception, "连接服务失败，请检查地址和服务状态", revision, selectedRevision));
    }

    /** 在网络读取阶段限制响应体，避免异常服务无限占用内存。 */
    private static HttpResponse.BodyHandler<String> limitedResponseBody() {
        return information -> new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<String> delegate = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
            private Flow.Subscription subscription;
            private long received;
            @Override public CompletionStage<String> getBody() { return delegate.getBody(); }
            @Override public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
            @Override public void onNext(List<ByteBuffer> buffers) {
                for (ByteBuffer buffer : buffers) received += buffer.remaining();
                if (received > 8_000_000) {
                    subscription.cancel();
                    delegate.onError(new ServiceException("服务返回的数据过大"));
                } else delegate.onNext(buffers);
            }
            @Override public void onError(Throwable error) { delegate.onError(error); }
            @Override public void onComplete() { delegate.onComplete(); }
        };
    }

    private <T> CompletableFuture<T> failed(Throwable exception, String fallback, long revision) {
        return failed(exception, fallback, revision, null);
    }

    private synchronized <T> CompletableFuture<T> failed(Throwable exception, String fallback, long revision, Long selectedRevision) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause instanceof CompletionException) cause = cause.getCause();
        if (authentication.get() == revision && (selectedRevision == null || selection.get() == selectedRevision)) status = cause instanceof ServiceException ? cause.getMessage() : fallback;
        LOGGER.log(System.Logger.Level.WARNING, "Town service request failed: " + cause.getClass().getSimpleName());
        return CompletableFuture.failedFuture(cause);
    }

    public synchronized CompletableFuture<JsonObject> refreshTowns() {
        long revision = authentication.get();
        long requestRevision = townLists.incrementAndGet();
        return request("town.list", object()).thenApply(result -> {
            synchronized (this) {
                if (authentication.get() == revision && townLists.get() == requestRevision && result.has("items")) towns = result.getAsJsonArray("items");
            }
            return result;
        });
    }

    public synchronized CompletableFuture<JsonObject> refresh() {
        String selectedTown = townId;
        String selectedWorld = worldId;
        long revision = selection.get();
        long requestRevision = snapshots.incrementAndGet();
        if (selectedTown.isEmpty()) return CompletableFuture.completedFuture(snapshot);
        return request("town.snapshot", object("townId", selectedTown, "worldId", selectedWorld)).thenCompose(result -> {
            synchronized (this) {
                if (snapshots.get() != requestRevision || selection.get() != revision || !townId.equals(selectedTown) || !worldId.equals(selectedWorld)) return CompletableFuture.completedFuture(result);
                if (selectedWorld.isEmpty() && result.has("worlds")) {
                    for (JsonElement element : result.getAsJsonArray("worlds")) {
                        JsonObject world = element.getAsJsonObject();
                        if (!world.get("archived").getAsBoolean()) { worldId = world.get("id").getAsString(); break; }
                    }
                    if (worldId.isEmpty() && !result.getAsJsonArray("worlds").isEmpty()) worldId = result.getAsJsonArray("worlds").get(0).getAsJsonObject().get("id").getAsString();
                    if (!worldId.isEmpty()) {
                        selection.incrementAndGet();
                        return refresh();
                    }
                }
                snapshot = result;
                return CompletableFuture.completedFuture(result);
            }
        });
    }

    public synchronized void selectTown(String id) {
        selection.incrementAndGet();
        townId = id;
        worldId = "";
        snapshot = new JsonObject();
        refresh();
    }

    public synchronized void selectWorld(String id) {
        if (worldId.equals(id)) return;
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
        public int entrySubtitleSeconds = 3;
        public int entryCooldownSeconds = 8;
        public int pollIntervalSeconds = 10;
        public boolean animationEnabled = true;
        public boolean projectOverlayEnabled = true;
        public Map<String, String> mapBindings = new TreeMap<>();

        public void validate() {
            URI address;
            try { address = URI.create(endpoint); }
            catch (RuntimeException exception) { throw new IllegalArgumentException("服务地址格式错误"); }
            if (address.getPort() != -1 && (address.getPort() < 1 || address.getPort() > 65535))
                throw new IllegalArgumentException("服务端口需在 1～65535 之间");
            boolean local = address.getHost() != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(address.getHost());
            if (address.getHost() == null || address.getUserInfo() != null || address.getQuery() != null || address.getFragment() != null
                    || (address.getPath() != null && !address.getPath().isEmpty())
                    || !("https".equals(address.getScheme()) || "http".equals(address.getScheme()) && local))
                throw new IllegalArgumentException("服务地址需为 HTTPS；本地服务可使用 HTTP，地址不带路径");
            if (entrySubtitleSeconds < 0 || entrySubtitleSeconds > 10) throw new IllegalArgumentException("进入项目字幕时长需在 0～10 秒之间，0 为关闭");
            if (entryCooldownSeconds < 1 || entryCooldownSeconds > 120) throw new IllegalArgumentException("进入项目提示间隔需在 1～120 秒之间");
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
