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
    private volatile boolean followCurrentWorld = true;
    private volatile JsonObject player = new JsonObject();
    private volatile ClientConfig configuration = new ClientConfig();
    private Path configurationPath;
    private boolean configurationWritable = true;
    public volatile String configurationNotice = "";
    public volatile JsonObject snapshot = new JsonObject();
    public volatile JsonArray towns = new JsonArray();
    public volatile String status = TownText.text("yat.status.waiting_for_world");
    public volatile String townId = "";
    public volatile String worldId = "";

    private TownGateway() {}

    public static TownGateway get() { return INSTANCE; }
    public ClientConfig config() { return configuration; }
    public JsonObject player() { return player.deepCopy(); }
    public boolean loggedIn() { return !token.isEmpty(); }

    public synchronized String currentWorldId() {
        return snapshot.has("town") && snapshot.get("town").isJsonObject() ? stringValue(snapshot.getAsJsonObject("town"), "currentWorldId") : "";
    }

    /** 当前周目按镇长公布的服务器、维度与地图名称匹配，不依赖本地缓存。 */
    public synchronized boolean matchesSharedMap(String serverAddress, String dimension, String mapName) {
        if (!loggedIn() || worldId.isEmpty() || !worldId.equals(currentWorldId()) || serverAddress == null || serverAddress.isBlank()
                || dimension == null || dimension.length() > 128 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")
                || mapName == null || mapName.isBlank() || !snapshot.has("worlds") || !snapshot.get("worlds").isJsonArray()) return false;
        for (JsonElement element : snapshot.getAsJsonArray("worlds")) {
            if (!element.isJsonObject()) continue;
            JsonObject world = element.getAsJsonObject();
            if (!worldId.equals(stringValue(world, "id")) || !world.has("archived") || !world.get("archived").isJsonPrimitive()
                    || !world.getAsJsonPrimitive("archived").isBoolean() || world.get("archived").getAsBoolean()) continue;
            String address = stringValue(world, "serverAddress").strip();
            if (address.isEmpty() || !address.equalsIgnoreCase(serverAddress.strip())) return false;
            String expected = world.has("mapNames") && world.get("mapNames").isJsonObject() ? stringValue(world.getAsJsonObject("mapNames"), dimension) : "";
            if (expected.isBlank()) expected = stringValue(world, "mapName");
            return !expected.isBlank() && expected.equals(mapName);
        }
        return false;
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    private static JsonObject sharedMapIdentity(JsonObject source, String selectedWorld) {
        JsonObject identity = new JsonObject();
        if (source.has("town") && source.get("town").isJsonObject()) identity.add("currentWorldId", source.getAsJsonObject("town").get("currentWorldId"));
        if (source.has("worlds") && source.get("worlds").isJsonArray()) {
            for (JsonElement element : source.getAsJsonArray("worlds")) {
                if (!element.isJsonObject() || !selectedWorld.equals(stringValue(element.getAsJsonObject(), "id"))) continue;
                JsonObject world = element.getAsJsonObject();
                for (String field : new String[]{"serverAddress", "mapName", "mapNames"}) identity.add(field, world.get(field));
                break;
            }
        }
        return identity;
    }

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
                if (parsed == null) throw new IllegalArgumentException(TownText.text("yat.error.empty_client_config"));
                Set<String> allowed = Set.of("endpoint", "entrySubtitleSeconds", "entryCooldownSeconds", "projectOverlayEnabled", "pollIntervalSeconds", "animationEnabled", "mapBindings");
                for (String key : parsed.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException(TownText.text("yat.error.unknown_client_setting") + key);
                for (String key : parsed.keySet()) {
                    JsonElement value = parsed.get(key);
                    if (key.equals("mapBindings")) {
                        if (!value.isJsonObject()) throw new IllegalArgumentException(TownText.text("yat.error.map_bindings_object"));
                        for (var binding : value.getAsJsonObject().entrySet())
                            if (!binding.getValue().isJsonPrimitive() || !binding.getValue().getAsJsonPrimitive().isString())
                                throw new IllegalArgumentException(TownText.text("yat.error.map_binding_text"));
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
                    if (!valid) throw new IllegalArgumentException(TownText.text("yat.error.client_setting_type") + key);
                    if (value.getAsJsonPrimitive().isNumber()) {
                        try { value.getAsBigDecimal().intValueExact(); }
                        catch (ArithmeticException exception) { throw new IllegalArgumentException(TownText.text("yat.error.client_setting_range") + key); }
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
                configurationNotice = TownText.text("yat.status.config_reset", backup.getFileName());
                status = configurationNotice;
            } catch (IOException failure) { configurationUnavailable(failure); }
        } catch (IOException exception) {
            configurationUnavailable(exception);
        }
    }

    /** 读写失败时保留原文件，禁止后续设置保存覆盖未经读取的配置。 */
    private void configurationUnavailable(IOException exception) {
        configurationWritable = false;
        configurationNotice = TownText.text("yat.error.client_config_access");
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
            throw new IllegalStateException(TownText.text("yat.error.save_client_config"), exception);
        }
        configurationNotice = "";
        if (!previous.endpoint.equals(replacement.endpoint)) {
            clearSession(TownText.text("yat.status.service_address_updated"));
            revoke(previous.endpoint, previousToken);
        } else status = TownText.text("yat.status.client_config_saved");
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

    /** 共享地图匹配成功后缓存玩家底图，缓存失败不影响当前地图显示。 */
    public synchronized boolean autoBindMap(String serverAddress, String dimension, String mapKey, String mapName) {
        if (!matchesSharedMap(serverAddress, dimension, mapName) || mapKey == null || mapKey.isBlank() || getBoundMapKey(dimension).equals(mapKey)) return false;
        String target = bindingKey(dimension);
        String scope = configuration.endpoint + "#" + townId + "/";
        for (var binding : configuration.mapBindings.entrySet())
            if (binding.getKey().startsWith(scope) && !binding.getKey().equals(target) && binding.getKey().endsWith("/" + dimension) && binding.getValue().equals(mapKey)) return false;
        try { bindCurrentMap(dimension, mapKey); status = TownText.text("yat.status.map_matched"); return true; }
        catch (IllegalArgumentException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Shared map cache rejected: invalid local binding", exception);
            return false;
        } catch (IllegalStateException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Shared map cache unavailable: local configuration could not be saved", exception);
            return false;
        }
    }

    private String bindingKey(String dimension) {
        return configuration.endpoint + "#" + townId + "/" + worldId + "/" + dimension;
    }

    private void updateBinding(String dimension, String key) {
        if (!townId.matches("\\d{8}") || !worldId.matches("[a-f0-9]{24}") || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]{1,128}"))
            throw new IllegalArgumentException(TownText.text("yat.error.map_binding_context"));
        ClientConfig replacement = JSON.fromJson(JSON.toJson(configuration), ClientConfig.class);
        String target = bindingKey(dimension);
        String scope = configuration.endpoint + "#" + townId + "/";
        if (key != null) {
            for (var binding : replacement.mapBindings.entrySet()) {
                String[] identity = binding.getKey().substring(binding.getKey().lastIndexOf('#') + 1).split("/", 3);
                if (binding.getKey().startsWith(scope) && !binding.getKey().equals(target) && identity.length == 3
                        && identity[2].equals(dimension) && binding.getValue().equals(key))
                    throw new IllegalArgumentException(TownText.text("yat.error.map_bound_other_season"));
            }
            replacement.mapBindings.put(target, key);
        } else replacement.mapBindings.remove(target);
        replacement.validate();
        ClientConfig previous = configuration;
        configuration = replacement;
        try { save(); }
        catch (IOException exception) { configuration = previous; throw new IllegalStateException(TownText.text("yat.error.save_map_binding"), exception); }
        status = key == null ? TownText.text("yat.status.map_binding_removed") : TownText.text("yat.status.map_binding_saved");
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
        status = TownText.text("yat.status.connecting_identity");
        return request("auth.login", object("playerId", playerId, "name", name)).thenCompose(result -> {
            synchronized (this) {
                if (authentication.get() != attempt) revoke(endpoint, result.get("token").getAsString(), false);
                requireAttempt(attempt);
                token = result.get("token").getAsString();
                player = result.getAsJsonObject("player");
                status = TownText.text("yat.status.identity_connected");
            }
            return refreshTowns().thenApply(ignored -> { requireAttempt(attempt); return result; });
        }).exceptionallyCompose(exception -> failed(exception, TownText.text("yat.error.connect_identity"), attempt));
    }

    public synchronized void logout() {
        String previousToken = token;
        String previousEndpoint = configuration.endpoint;
        clearSession(TownText.text("yat.status.signed_out"));
        revoke(previousEndpoint, previousToken);
    }

    private synchronized void clearSession(String message) {
        authentication.incrementAndGet();
        token = "";
        player = new JsonObject();
        towns = new JsonArray();
        townId = "";
        worldId = "";
        followCurrentWorld = true;
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
                if (reportFailure && authentication.get() == revision) status = TownText.text("yat.status.local_signout_only");
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
            catch (RuntimeException exception) { throw new CompletionException(new ServiceException(TownText.text("yat.error.invalid_service_response"))); }
            if (envelope == null || !envelope.has("ok") || !envelope.get("ok").isJsonPrimitive()
                    || !envelope.getAsJsonPrimitive("ok").isBoolean()) throw new CompletionException(new ServiceException(TownText.text("yat.error.protocol_mismatch")));
            if (!envelope.get("ok").getAsBoolean()) {
                if (!envelope.has("error") || !envelope.get("error").isJsonObject())
                    throw new CompletionException(new ServiceException(TownText.text("yat.error.protocol_mismatch")));
                JsonObject error = envelope.getAsJsonObject("error");
                if (!error.has("message") || !error.get("message").isJsonPrimitive() || !error.getAsJsonPrimitive("message").isString())
                    throw new CompletionException(new ServiceException(TownText.text("yat.error.protocol_mismatch")));
                String message = TownText.serviceError(error);
                synchronized (this) {
                    if (response.statusCode() == 401 && !action.startsWith("auth.") && authentication.get() == revision && token.equals(bearer)) clearSession(message);
                }
                throw new CompletionException(new ServiceException(message));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new CompletionException(new ServiceException(TownText.text("yat.error.http_status", response.statusCode())));
            JsonElement data = envelope.get("data");
            if (data != null && data.isJsonObject()) return data.getAsJsonObject();
            if (data != null && data.isJsonArray()) return object("items", data);
            throw new CompletionException(new ServiceException(TownText.text("yat.error.protocol_mismatch")));
        }).exceptionallyCompose(exception -> failed(exception, TownText.text("yat.error.connect_service"), revision, selectedRevision));
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
                    delegate.onError(new ServiceException(TownText.text("yat.error.response_too_large")));
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
                String nextWorld = selectedWorld;
                if (followCurrentWorld || selectedWorld.isEmpty()) {
                    String currentWorld = result.has("town") && result.get("town").isJsonObject() ? stringValue(result.getAsJsonObject("town"), "currentWorldId") : "";
                    if (!currentWorld.isEmpty()) nextWorld = currentWorld;
                    else if (result.has("worlds") && result.get("worlds").isJsonArray()) {
                        for (JsonElement element : result.getAsJsonArray("worlds")) {
                            JsonObject world = element.getAsJsonObject();
                            if (!world.get("archived").getAsBoolean()) { nextWorld = world.get("id").getAsString(); break; }
                        }
                        if (nextWorld.isEmpty() && !result.getAsJsonArray("worlds").isEmpty()) nextWorld = result.getAsJsonArray("worlds").get(0).getAsJsonObject().get("id").getAsString();
                    }
                }
                if (!nextWorld.equals(selectedWorld)) {
                    worldId = nextWorld;
                    snapshot = new JsonObject();
                    selection.incrementAndGet();
                    return refresh();
                }
                if (!snapshot.isEmpty() && !sharedMapIdentity(snapshot, selectedWorld).equals(sharedMapIdentity(result, selectedWorld))) selection.incrementAndGet();
                snapshot = result;
                return CompletableFuture.completedFuture(result);
            }
        });
    }

    public synchronized void selectTown(String id) {
        selection.incrementAndGet();
        townId = id;
        worldId = "";
        followCurrentWorld = true;
        snapshot = new JsonObject();
        refresh();
    }

    public synchronized void selectWorld(String id) {
        followCurrentWorld = !id.isEmpty() && id.equals(currentWorldId());
        if (worldId.equals(id)) return;
        selection.incrementAndGet();
        worldId = id;
        snapshot = new JsonObject();
        refresh();
    }

    public synchronized CompletableFuture<JsonObject> followCurrentWorld() {
        followCurrentWorld = true;
        selection.incrementAndGet();
        worldId = "";
        snapshot = new JsonObject();
        return refresh();
    }

    public static JsonObject object(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException(TownText.text("yat.error.json_field_pairs"));
        JsonObject result = new JsonObject();
        for (int index = 0; index < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            result.add(pairs[index].toString(), value instanceof JsonElement element ? element : JSON.toJsonTree(value));
        }
        return result;
    }

    private void requireAttempt(long attempt) {
        if (authentication.get() != attempt) throw new CompletionException(new ServiceException(TownText.text("yat.status.login_cancelled")));
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
            catch (RuntimeException exception) { throw new IllegalArgumentException(TownText.text("yat.error.invalid_service_address")); }
            if (address.getPort() != -1 && (address.getPort() < 1 || address.getPort() > 65535))
                throw new IllegalArgumentException(TownText.text("yat.error.invalid_service_port"));
            boolean local = address.getHost() != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(address.getHost());
            if (address.getHost() == null || address.getUserInfo() != null || address.getQuery() != null || address.getFragment() != null
                    || (address.getPath() != null && !address.getPath().isEmpty())
                    || !("https".equals(address.getScheme()) || "http".equals(address.getScheme()) && local))
                throw new IllegalArgumentException(TownText.text("yat.error.service_address_scheme"));
            if (entrySubtitleSeconds < 0 || entrySubtitleSeconds > 10) throw new IllegalArgumentException(TownText.text("yat.error.entry_subtitle_duration"));
            if (entryCooldownSeconds < 1 || entryCooldownSeconds > 120) throw new IllegalArgumentException(TownText.text("yat.error.entry_hint_interval"));
            if (pollIntervalSeconds < 2 || pollIntervalSeconds > 300) throw new IllegalArgumentException(TownText.text("yat.error.refresh_interval"));
            if (mapBindings == null || mapBindings.size() > 10000) throw new IllegalArgumentException(TownText.text("yat.error.map_binding_limit"));
            for (var binding : mapBindings.entrySet()) {
                if (!binding.getKey().matches("https?://[^#]{1,2048}#\\d{8}/[a-f0-9]{24}/[a-z0-9_.-]+:[a-z0-9_/.-]{1,128}")
                        || binding.getValue() == null || binding.getValue().length() > 512 || binding.getValue().indexOf('|') <= 0)
                    throw new IllegalArgumentException(TownText.text("yat.error.invalid_map_binding"));
            }
        }
    }
}
