import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

/** 静态检查每套适配层引用的真实游戏、Fabric 和 Xaero 成员，不初始化游戏。 */
public final class VerifyLinks {
    private static final Map<String, JsonObject> metadataCache = new HashMap<>();
    private final List<ZipFile> archives = new ArrayList<>();
    private final Map<String, ClassModel> classes = new HashMap<>();
    private final Set<String> missing = new HashSet<>();
    private Path classDirectory;
    private int references;
    private boolean legacyMapControls;

    public static void main(String[] arguments) throws Exception {
        boolean metadataOnly = arguments.length > 0 && arguments[0].equals("--metadata-only");
        if (metadataOnly) arguments = Arrays.copyOfRange(arguments, 1, arguments.length);
        if (arguments.length < 3) throw new IllegalArgumentException("Expected: mod.jar|classes-directory package-prefix classpath-lines.txt...");
        for (int index = 2; index < arguments.length; index++) {
            VerifyLinks verifier = new VerifyLinks();
            try {
                Path input = Path.of(arguments[0]);
                if (Files.isDirectory(input)) verifier.classDirectory = input;
                else verifier.archives.add(new ZipFile(arguments[0]));
                for (String jar : Files.readAllLines(Path.of(arguments[index]))) if (!jar.isBlank()) verifier.archives.add(new ZipFile(jar));
                if (metadataOnly) {
                    List<String> failures = new ArrayList<>();
                    verifier.verifyMetadata(arguments[1].replace('.', '/'), failures);
                    System.out.println((failures.isEmpty() ? "COMPATIBLE" : "INCOMPATIBLE") + "\t" + arguments[index] + "\t" + String.join("; ", failures));
                } else verifier.verify(arguments[1].replace('.', '/'));
            } catch (IllegalStateException exception) {
                throw new IllegalStateException("Compatibility failed: " + Path.of(arguments[index]).getFileName() + "\n" + exception.getMessage(), exception);
            } finally {
                for (ZipFile archive : verifier.archives) archive.close();
            }
        }
    }

    private void verify(String prefix) throws IOException {
        List<String> failures = new ArrayList<>();
        verifyMetadata(prefix, failures);
        List<String> names;
        if (classDirectory != null) {
            try (var entries = Files.walk(classDirectory)) {
                names = entries.filter(Files::isRegularFile).map(file -> classDirectory.relativize(file).toString().replace('\\', '/')).toList();
            }
        } else names = archives.getFirst().stream().map(entry -> entry.getName()).toList();
        int checked = 0;
        for (String name : names) {
            if (!name.endsWith(".class") || !(name.startsWith(prefix + "/") || name.startsWith("dev/yetanothertown/mixin/") || name.equals("dev/yetanothertown/Bootstrap.class") || name.equals("dev/yetanothertown/ModMenuIntegration.class"))) continue;
            ClassModel model = load(name.substring(0, name.length() - 6));
            checked++;
            if (model.majorVersion() > 69) failures.add(name + ": requires Java newer than 25");
            for (var entry : model.constantPool()) {
                if ((name.startsWith(prefix + "/XaeroBridge") || name.startsWith(prefix + "/ProjectHighlighter"))
                        && entry instanceof Utf8Entry text && Set.of("cameraX", "cameraZ", "scale", "drawArea").contains(text.stringValue())
                        && !(name.equals(prefix + "/XaeroBridge$CameraFields.class") && Set.of("cameraX", "cameraZ").contains(text.stringValue())))
                    failures.add(name + ": independent map projection must not replace native chunk highlighting: " + text.stringValue());
                if (entry instanceof ClassEntry type && scoped(type.asInternalName())) {
                    references++;
                    if (load(type.asInternalName()) == null) failures.add(name + ": missing class " + type.asInternalName());
                } else if (entry instanceof MemberRefEntry member && scoped(member.owner().asInternalName())) {
                    references++;
                    String owner = member.owner().asInternalName();
                    String memberName = member.name().stringValue();
                    String descriptor = member.type().stringValue();
                    if (!resolves(owner, memberName, descriptor, member instanceof FieldRefEntry, new HashSet<>()))
                        failures.add(name + ": missing member " + owner + "." + memberName + descriptor);
                }
            }
        }
        if (checked == 0) failures.add("No adapter classes found for " + prefix);
        verifyNativeMap(prefix, failures);
        boolean legacyScreen = resolves("net/minecraft/client/Minecraft", "setScreen", "(Lnet/minecraft/client/gui/screens/Screen;)V", false, new HashSet<>())
                && fieldType("net/minecraft/client/Minecraft", "screen") != null;
        String gui = fieldType("net/minecraft/client/Minecraft", "gui");
        boolean currentScreen = gui != null && resolves(gui, "setScreen", "(Lnet/minecraft/client/gui/screens/Screen;)V", false, new HashSet<>())
                && resolves(gui, "screen", "()Lnet/minecraft/client/gui/screens/Screen;", false, new HashSet<>());
        if (!legacyScreen && !currentScreen) failures.add("Minecraft reflective screen access contract changed");
        String options = fieldType("net/minecraft/client/Minecraft", "options");
        String hud = gui == null ? null : fieldType(gui, "hud");
        boolean legacyHidden = options != null && resolves(options, "hideGui", "Z", true, new HashSet<>());
        boolean currentHidden = hud != null && resolves(hud, "isHidden", "()Z", false, new HashSet<>());
        if (!legacyHidden && !currentHidden) failures.add("Minecraft reflective HUD visibility contract changed");
        if (!resolves(prefix + "/VersionClient", "createConfigScreen", "(Lnet/minecraft/client/gui/screens/Screen;)Lnet/minecraft/client/gui/screens/Screen;", false, new HashSet<>()))
            failures.add("ModMenu config factory adapter target missing");
        if (!failures.isEmpty()) throw new IllegalStateException(String.join("\n", failures));
        System.out.println(prefix + ": " + checked + " classes, " + references + " references verified against real dependency bytecode; game not initialized");
    }

    /** 按 Fabric 的版本谓词核对依赖，并采用重复内置模组中版本最高的实际字节码。 */
    private void verifyMetadata(String prefix, List<String> failures) throws IOException {
        record Metadata(JsonObject value, Version version, ZipFile archive) {}
        Map<String, Metadata> selected = new HashMap<>();
        Map<ZipFile, String> identifiers = new HashMap<>();
        try {
            if (classDirectory != null) {
                JsonObject value = JsonParser.parseString(resource("fabric.mod.json")).getAsJsonObject();
                selected.put(value.get("id").getAsString(), new Metadata(value, Version.parse(value.get("version").getAsString()), null));
            }
            for (ZipFile archive : archives) {
                var entry = archive.getEntry("fabric.mod.json");
                if (entry == null) continue;
                JsonObject value = metadataCache.get(archive.getName());
                if (value == null) {
                    try (var input = archive.getInputStream(entry)) {
                        value = JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    }
                    metadataCache.put(archive.getName(), value);
                }
                String identifier = value.get("id").getAsString();
                identifiers.put(archive, identifier);
                Metadata candidate = new Metadata(value, Version.parse(value.get("version").getAsString()), archive);
                Metadata previous = selected.get(identifier);
                if (previous == null || candidate.version().compareTo(previous.version()) > 0) selected.put(identifier, candidate);
            }
            for (var iterator = archives.iterator(); iterator.hasNext();) {
                ZipFile archive = iterator.next();
                String identifier = identifiers.get(archive);
                if (identifier != null && selected.get(identifier).archive() != archive) {
                    archive.close();
                    iterator.remove();
                }
            }
            String minecraft = Map.of("dev/yetanothertown/v263", "26.3", "dev/yetanothertown/v262", "26.2", "dev/yetanothertown/v2612", "26.1.2").get(prefix);
            if (minecraft == null) throw new IllegalArgumentException("Unknown Minecraft adapter: " + prefix);
            Map<String, Version> provided = new HashMap<>();
            provided.put("minecraft", Version.parse(minecraft));
            provided.put("java", Version.parse("25"));
            for (var entry : selected.entrySet()) {
                provided.put(entry.getKey(), entry.getValue().version());
                if (entry.getValue().value().has("provides")) for (JsonElement alias : entry.getValue().value().getAsJsonArray("provides"))
                    provided.put(alias.getAsString(), entry.getValue().version());
            }
            Version worldMap = provided.get("xaeroworldmap");
            legacyMapControls = worldMap != null && VersionPredicate.parse("<1.42.0").test(worldMap);
            for (var entry : selected.entrySet()) for (String type : List.of("depends", "breaks")) {
                JsonObject requirements = entry.getValue().value().getAsJsonObject(type);
                if (requirements == null) continue;
                for (var requirement : requirements.entrySet()) {
                    Version installed = provided.get(requirement.getKey());
                    boolean matches = installed != null && matchesVersion(requirement.getValue(), installed);
                    if (type.equals("depends") ? !matches : matches)
                        failures.add("Fabric metadata " + type + ": " + entry.getKey() + " " + entry.getValue().version().getFriendlyString()
                                + " requires " + requirement.getKey() + " " + requirement.getValue() + "; selected " + (installed == null ? "missing" : installed.getFriendlyString()));
                }
            }
        } catch (VersionParsingException | IllegalArgumentException exception) {
            throw new IOException("Invalid Fabric dependency metadata", exception);
        }
    }

    private static boolean matchesVersion(JsonElement requirement, Version installed) throws VersionParsingException {
        if (requirement.isJsonArray()) {
            for (JsonElement alternative : requirement.getAsJsonArray()) if (matchesVersion(alternative, installed)) return true;
            return false;
        }
        return VersionPredicate.parse(requirement.getAsString()).test(installed);
    }

    /** 检查原生注入目标、区块单位和实际注册路径，防止退回仅自建窗口可用的覆盖层。 */
    private void verifyNativeMap(String prefix, List<String> failures) throws IOException {
        ClassModel map = load("xaero/map/gui/GuiMap");
        for (var field : Map.of("mapTileSelection", "Lxaero/map/gui/MapTileSelection;", "rightClickDim", "Lnet/minecraft/resources/ResourceKey;",
                "mouseBlockPosX", "I", "mouseBlockPosZ", "I", "mouseBlockDim", "Lnet/minecraft/resources/ResourceKey;",
                "rightClickMenu", "Lxaero/map/gui/dropdown/rightclick/GuiRightClickMenu;", "overWaypointsMenu", "Z", "overPlayersMenu", "Z").entrySet()) {
            if (map == null || map.fields().stream().noneMatch(value -> value.fieldName().equalsString(field.getKey()) && value.fieldType().equalsString(field.getValue())))
                failures.add("Xaero reflection contract changed: GuiMap." + field.getKey() + field.getValue());
        }
        // 原生相机仅用于一次定位，不参与投影或项目图层绘制。
        for (var field : Map.of("cameraX", "D", "cameraZ", "D", "shouldResetCameraPos", "Z",
                "cameraDestination", "[I", "cameraDestinationAnimX", "Lxaero/map/animation/SlowingAnimation;",
                "cameraDestinationAnimZ", "Lxaero/map/animation/SlowingAnimation;", "prevPlayerDimDiv", "D").entrySet()) {
            if (map == null || map.fields().stream().noneMatch(value -> value.fieldName().equalsString(field.getKey()) && value.fieldType().equalsString(field.getValue())))
                failures.add("Xaero camera contract changed: GuiMap." + field.getKey());
        }
        // 1.42.0 前原生地图没有隐藏界面和相机附着模式；两种契约分别核对。
        for (String field : List.of("hiddenUI", "attachedCamera")) {
            var control = map == null ? null : map.fields().stream().filter(value -> value.fieldName().equalsString(field)).findFirst().orElse(null);
            if (legacyMapControls ? control != null : control == null || !control.fieldType().equalsString("Z")
                    || !control.flags().has(java.lang.reflect.AccessFlag.STATIC))
                failures.add("Xaero map controls contract changed: GuiMap." + field + " (legacy=" + legacyMapControls + ")");
        }
        for (String[] method : List.of(
                new String[]{"xaero/map/highlight/HighlighterRegistry", "end", "()V"},
                new String[]{"xaero/map/gui/GuiMap", "getRightClickOptions", "()Ljava/util/ArrayList;"},
                new String[]{"xaero/map/MapProcessor", "confirmMultiworld", "(Lxaero/map/world/MapDimension;)Z"},
                new String[]{prefix + "/XaeroBridge", "registerHighlighter", "(Ljava/lang/Object;)V"},
                new String[]{prefix + "/XaeroBridge", "renderMapTooltip", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"},
                new String[]{prefix + "/XaeroBridge", "playerMapKey", "(Ljava/lang/String;)Ljava/lang/String;"},
                new String[]{prefix + "/XaeroBridge", "synchronizeSharedMap", "()V"},
                new String[]{prefix + "/XaeroBridge", "currentMapSetupBlockReason", "()Ljava/lang/String;"},
                new String[]{prefix + "/XaeroBridge", "currentMapName", "()Ljava/lang/String;"},
                new String[]{prefix + "/XaeroBridge", "addMapOptions", "(Ljava/lang/Object;Ljava/lang/Object;)V"}))
            if (!resolves(method[0], method[1], method[2], false, new HashSet<>())) failures.add("Native map hook target missing: " + String.join(" ", method));

        ClassModel highlighter = load(prefix + "/ProjectHighlighter");
        if (highlighter == null || highlighter.superclass().isEmpty() || !highlighter.superclass().get().asInternalName().equals("xaero/map/highlight/ChunkHighlighter"))
            failures.add("Project overlay must use the native ChunkHighlighter texture pipeline");
        if (!calls(load(prefix + "/XaeroBridge"), "registerHighlighter", "xaero/map/highlight/HighlighterRegistry", "register"))
            failures.add("Project highlighter is not registered with the native registry");
        ClassModel bridge = load(prefix + "/XaeroBridge");
        if (calls(bridge, "synchronizeSharedMap", prefix + "/TownGateway", "selectWorld")
                || !calls(bridge, "synchronizeSharedMap", prefix + "/XaeroBridge", "currentMapSetupBlockReason")
                || !calls(bridge, "synchronizeSharedMap", prefix + "/TownGateway", "matchesSharedMap")
                || !calls(bridge, "synchronizeSharedMap", prefix + "/TownGateway", "autoBindMap")
                || !calls(bridge, "resolvedMapKey", prefix + "/TownGateway", "matchesSharedMap")
                || !calls(bridge, "mapName", "xaero/map/world/MapDimension", "getMultiworldName"))
            failures.add("Shared map synchronization must validate native readiness and authoritative map names without changing the selected world");
        if (!calls(bridge, "synchronizeSharedMap", prefix + "/XaeroBridge", "confirmAssociation")
                || !calls(bridge, "confirmAssociation", "xaero/map/MapProcessor", "setMultiworld")
                || !calls(bridge, "confirmAssociation", "xaero/map/MapProcessor", "confirmMultiworld")
                || !calls(bridge, "confirmAssociation", "xaero/map/MapProcessor", "toggleMultiworldType")
                || !calls(bridge, "confirmAssociation", "xaero/map/world/MapWorld", "getFutureMultiworldType")
                || !calls(bridge, "confirmAssociation", "xaero/map/world/MapWorld", "getPlayerMapKey"))
            failures.add("Map association must resolve Single mode through Xaero, reselect and confirm, then verify the actual player map identity");
        if (!calls(bridge, "playerMapKey", "xaero/map/world/MapConnectionNode", "getDimId")
                || !usesField(bridge, "playerMapKey", "xaero/map/MapProcessor", "mainWorld")
                || !calls(bridge, "playerMapKey", "xaero/map/world/MapWorld", "getPlayerMapKey")
                || calls(bridge, "playerMapKey", "xaero/map/MapProcessor", "getWorld")
                || calls(bridge, "playerMapKey", "xaero/map/MapProcessor", "getNewWorld")
                || calls(bridge, "playerMapKey", "xaero/map/MapProcessor", "isWaitingForWorldUpdate")
                || calls(bridge, "playerMapKey", "xaero/map/world/MapWorld", "getCurrentMultiworld"))
            failures.add("Player map identity must validate mainWorld and the actual dimension, independent of renderer updates or the viewed map");
        ClassModel nativeWorld = load("xaero/map/world/MapWorld"), nativeDimension = load("xaero/map/world/MapDimension");
        if (!usesField(nativeWorld, "getPlayerMapKey", "xaero/map/MapProcessor", "mainWorld")
                || !calls(nativeWorld, "getPlayerMapKey", "xaero/map/world/MapDimension", "getPlayerMapKey")
                || !usesField(nativeDimension, "getPlayerMapKey", "xaero/map/world/MapDimension", "confirmedMultiworld")
                || !calls(nativeDimension, "getPlayerMapKey", "xaero/map/world/MapDimension", "getFutureAutoMultiworld")
                || calls(nativeDimension, "getPlayerMapKey", "xaero/map/world/MapDimension", "getCurrentMultiworld"))
            failures.add("Xaero player map identity contract changed; inspect automatic and confirmed multiworld selection before adapting");
        ClassModel nativeProcessor = load("xaero/map/MapProcessor");
        if (!usesField(nativeDimension, "confirmMultiworldUnsynced", "xaero/map/world/MapDimension", "futureMultiworldWritable")
                || !usesField(nativeDimension, "setMultiworldUnsynced", "xaero/map/world/MapDimension", "futureMultiworldWritable")
                || !usesField(nativeProcessor, "isWritingPaused", "xaero/map/MapProcessor", "pauseWriting")
                || !usesField(nativeProcessor, "isProcessingPaused", "xaero/map/MapProcessor", "pauseProcessing")
                || usesField(nativeProcessor, "isProcessingPaused", "xaero/map/MapProcessor", "pauseWriting"))
            failures.add("Native confirmation or writer-pause lifecycle changed; inspect reselect-confirm behavior before adapting");
        if (!usesField(nativeProcessor, "confirmMultiworld", "xaero/map/MapProcessor", "mainWorld")
                || !calls(nativeProcessor, "confirmMultiworld", "xaero/map/world/MapWorld", "getPotentialDimId")
                || !calls(nativeProcessor, "confirmMultiworld", "xaero/map/world/MapWorld", "getCurrentDimension")
                || !calls(nativeProcessor, "confirmMultiworld", "xaero/map/world/MapDimension", "confirmMultiworldUnsynced"))
            failures.add("Xaero native player map confirmation no longer checks the live dimension before confirming the selected map");
        if (!calls(bridge, "renderMapTooltip", prefix + "/ProjectHighlighter", "renderTooltip")
                || !calls(highlighter, "renderTooltip", prefix + "/VersionClient", "drawAvatar")
                || !calls(highlighter, "renderTooltip", prefix + "/ProjectHighlighter", "tooltipLayout")
                || !calls(highlighter, "renderTooltip", prefix + "/VersionClient", "drawLogo")
                || !calls(highlighter, "renderTooltip", "net/minecraft/client/gui/screens/inventory/tooltip/TooltipRenderUtil", "extractTooltipBackground"))
            failures.add("Native project hover must render wrapped text and owner avatar in its tooltip card");
        ClassModel client = load(prefix + "/VersionClient");
        if (!calls(client, "updateNavigation", prefix + "/VersionClient", "clearNavigation")
                || !calls(client, "stopNavigation", prefix + "/VersionClient", "clearNavigation")
                || !calls(client, "clearNavigation", prefix + "/XaeroBridge", "clearNavigation")
                || !calls(bridge, "clearNavigation", "xaero/hud/minimap/waypoint/set/WaypointSet", "remove"))
            failures.add("Arrival and explicit cancellation must remove our native waypoint and navigation HUD together");
        verifyMixin("HighlighterRegistryMixin", "xaero/map/highlight/HighlighterRegistry", "end()V", "HEAD", "registerHighlighter", failures);
        verifyMixin("MapMenuMixin", "xaero/map/gui/GuiMap", "getRightClickOptions()Ljava/util/ArrayList;", "RETURN", "addMapOptions", failures);
        verifyMixin("MapMenuMixin", "xaero/map/gui/GuiMap", "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", "TAIL", "renderMapTooltip", failures);
        String metadata = resource("fabric.mod.json");
        String mixins = resource("yetanothertown.mixins.json");
        if (!metadata.contains("\"yetanothertown.mixins.json\"") || !mixins.contains("\"HighlighterRegistryMixin\"") || !mixins.contains("\"MapMenuMixin\"")
                || !mixins.contains("\"dev.yetanothertown.mixin\"") || !mixins.matches("(?s).*\"required\"\\s*:\\s*true.*"))
            failures.add("Required native map mixins are not declared in the packaged Fabric metadata");

        boolean chunkSelection = false;
        if (map != null) for (var method : map.methods()) if (method.methodName().equalsString("mouseClicked") && method.code().isPresent()) {
            List<Instruction> instructions = method.code().get().elementList().stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
            for (int index = 7; index < instructions.size(); index++) {
                if (!(instructions.get(index) instanceof InvokeInstruction invoke) || !invoke.owner().asInternalName().equals("xaero/map/gui/MapTileSelection") || !invoke.name().equalsString("<init>") || !invoke.type().equalsString("(II)V")) continue;
                chunkSelection = instructions.get(index - 7) instanceof FieldInstruction x && x.name().equalsString("rightClickX")
                        && instructions.get(index - 6).opcode() == Opcode.ICONST_4 && instructions.get(index - 5).opcode() == Opcode.ISHR
                        && instructions.get(index - 3) instanceof FieldInstruction z && z.name().equalsString("rightClickZ")
                        && instructions.get(index - 2).opcode() == Opcode.ICONST_4 && instructions.get(index - 1).opcode() == Opcode.ISHR;
            }
        }
        if (!chunkSelection) failures.add("Native right-drag selection no longer converts block coordinates with signed >> 4; inspect units before adapting");
    }

    private static boolean usesField(ClassModel model, String methodName, String owner, String fieldName) {
        if (model == null) return false;
        return model.methods().stream().filter(method -> method.methodName().equalsString(methodName)).flatMap(method -> method.code().stream())
                .flatMap(code -> code.elementList().stream()).anyMatch(element -> element instanceof FieldInstruction field
                        && field.owner().asInternalName().equals(owner) && field.name().equalsString(fieldName));
    }

    private void verifyMixin(String name, String target, String method, String location, String hook, List<String> failures) throws IOException {
        ClassModel model = load("dev/yetanothertown/mixin/" + name);
        boolean targetsNative = model != null && annotationStrings(model, "Lorg/spongepowered/asm/mixin/Mixin;").contains(target.replace('/', '.'));
        boolean injects = false;
        if (model != null) for (var member : model.methods()) {
            Set<String> values = annotationStrings(member, "Lorg/spongepowered/asm/mixin/injection/Inject;");
            if (values.contains(method) && values.contains(location) && calls(model, member.methodName().stringValue(), "dev/yetanothertown/Bootstrap", "mapHook")) injects = true;
        }
        boolean dispatches = false;
        if (model != null) for (var entry : model.constantPool()) if (entry instanceof Utf8Entry text && text.equalsString(hook)) dispatches = true;
        if (!targetsNative || !injects || !dispatches) failures.add(name + " must inject " + target + "." + method + " at " + location + " and dispatch " + hook);
    }

    private static Set<String> annotationStrings(AttributedElement element, String annotationType) {
        Set<String> result = new HashSet<>();
        for (var attribute : element.attributes()) {
            List<Annotation> annotations = attribute instanceof RuntimeVisibleAnnotationsAttribute visible ? visible.annotations()
                    : attribute instanceof RuntimeInvisibleAnnotationsAttribute invisible ? invisible.annotations() : List.of();
            for (Annotation annotation : annotations) if (annotation.className().equalsString(annotationType))
                for (var value : annotation.elements()) collectAnnotationStrings(value.value(), result);
        }
        return result;
    }

    private static void collectAnnotationStrings(AnnotationValue value, Set<String> result) {
        if (value instanceof AnnotationValue.OfString text) result.add(text.stringValue());
        else if (value instanceof AnnotationValue.OfArray array) for (AnnotationValue child : array.values()) collectAnnotationStrings(child, result);
        else if (value instanceof AnnotationValue.OfAnnotation annotation) for (var child : annotation.annotation().elements()) collectAnnotationStrings(child.value(), result);
    }

    private static boolean calls(ClassModel model, String method, String owner, String target) {
        if (model != null) for (var member : model.methods()) if (member.methodName().equalsString(method) && member.code().isPresent())
            for (var element : member.code().get()) if (element instanceof InvokeInstruction invoke && invoke.owner().asInternalName().equals(owner) && invoke.name().equalsString(target)) return true;
        return false;
    }

    private String resource(String name) throws IOException {
        if (classDirectory != null) {
            Path file = classDirectory.resolve(name);
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        }
        var entry = archives.getFirst().getEntry(name);
        if (entry == null) return "";
        try (var input = archives.getFirst().getInputStream(entry)) { return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
    }

    private String fieldType(String owner, String name) throws IOException {
        ClassModel model = load(owner);
        if (model == null) return null;
        for (var field : model.fields()) if (field.fieldName().equalsString(name)) {
            String type = field.fieldType().stringValue();
            return type.startsWith("L") ? type.substring(1, type.length() - 1) : type;
        }
        return model.superclass().isPresent() ? fieldType(model.superclass().get().asInternalName(), name) : null;
    }

    private boolean scoped(String name) {
        return name.startsWith("net/minecraft/") || name.startsWith("net/fabricmc/")
                || name.startsWith("xaero/") || name.startsWith("com/mojang/") || name.startsWith("com/terraformersmc/modmenu/") || name.startsWith("dev/yetanothertown/");
    }

    private ClassModel load(String name) throws IOException {
        if (classes.containsKey(name)) return classes.get(name);
        if (missing.contains(name)) return null;
        if (classDirectory != null) {
            Path file = classDirectory.resolve(name + ".class");
            if (Files.isRegularFile(file)) {
                ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
                classes.put(name, model);
                return model;
            }
        }
        for (ZipFile archive : archives) {
            var entry = archive.getEntry(name + ".class");
            if (entry == null) continue;
            try (var input = archive.getInputStream(entry)) {
                ClassModel model = ClassFile.of().parse(input.readAllBytes());
                classes.put(name, model);
                return model;
            }
        }
        if (name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/") || name.startsWith("sun/") || name.startsWith("com/sun/"))
        try (var input = ClassLoader.getSystemResourceAsStream(name + ".class")) {
            if (input != null) {
                ClassModel model = ClassFile.of().parse(input.readAllBytes());
                classes.put(name, model);
                return model;
            }
        }
        missing.add(name);
        return null;
    }

    private boolean resolves(String owner, String name, String descriptor, boolean field, Set<String> visited) throws IOException {
        if (!visited.add(owner)) return false;
        ClassModel model = load(owner);
        if (model == null) return false;
        if (field) {
            if (model.fields().stream().anyMatch(value -> value.fieldName().equalsString(name) && value.fieldType().equalsString(descriptor))) return true;
        } else {
            if (model.methods().stream().anyMatch(value -> value.methodName().equalsString(name) && value.methodType().equalsString(descriptor))) return true;
            if (name.equals("<init>")) return false;
        }
        if (model.superclass().isPresent() && resolves(model.superclass().get().asInternalName(), name, descriptor, field, visited)) return true;
        for (ClassEntry implemented : model.interfaces()) if (resolves(implemented.asInternalName(), name, descriptor, field, visited)) return true;
        return false;
    }
}
