package dev.yetanothertown.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.IllegalFormatException;
import java.util.Locale;
import net.minecraft.locale.Language;

/** 复用游戏语言与资源包翻译；游戏尚未加载资源时回退英文。 */
public final class TownText {
    private static final JsonObject ENGLISH = loadEnglish();

    private TownText() { }

    private static JsonObject loadEnglish() {
        try (var stream = TownText.class.getResourceAsStream("/assets/yetanothertown/lang/en_us.json")) {
            if (stream == null) throw new IllegalStateException("Missing Yet Another Town English translations");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load Yet Another Town translations", exception);
        }
    }

    public static String text(String key, Object... arguments) {
        String fallback = ENGLISH.has(key) ? ENGLISH.get(key).getAsString() : key;
        String template = Language.getInstance().getOrDefault(key, fallback);
        try { return arguments.length == 0 ? template : String.format(Locale.ROOT, template, arguments); }
        catch (IllegalFormatException exception) {
            try { return arguments.length == 0 ? fallback : String.format(Locale.ROOT, fallback, arguments); }
            catch (IllegalFormatException invalidArguments) { return fallback; }
        }
    }

    /** 服务仅能选择模组自己的文案；成员填写的内容始终作为参数。 */
    static String serviceError(JsonObject error) {
        String key = string(error, "messageKey", "");
        if (!key.startsWith("yat.") || !ENGLISH.has(key)) return text("yat.error.service_request");
        var values = error.has("messageArgs") && error.get("messageArgs").isJsonArray() ? error.getAsJsonArray("messageArgs") : new com.google.gson.JsonArray();
        Object[] arguments = new Object[values.size()];
        for (int index = 0; index < arguments.length; index++) {
            JsonElement value = values.get(index);
            if (value.isJsonObject()) {
                String nested = string(value.getAsJsonObject(), "translate", "");
                arguments[index] = nested.startsWith("yat.") && ENGLISH.has(nested) ? text(nested) : "?";
            } else arguments[index] = value.isJsonPrimitive() ? value.getAsString() : "?";
        }
        return text(key, arguments);
    }

    static String recordName(JsonObject record, String fallback) {
        String key = string(record, "nameKey", "");
        return key.startsWith("yat.") && ENGLISH.has(key) ? text(key) : string(record, "name", fallback);
    }

    static String editedName(JsonObject record, String value) {
        return record.has("nameKey") && value.equals(recordName(record, "")) ? string(record, "name", value) : value;
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : fallback;
    }
}
