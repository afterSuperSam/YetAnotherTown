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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** 静态检查每套适配层引用的真实游戏、Fabric 和 Xaero 成员，不初始化游戏。 */
public final class VerifyLinks {
    private final List<ZipFile> archives = new ArrayList<>();
    private final Map<String, ClassModel> classes = new HashMap<>();
    private final Set<String> missing = new HashSet<>();
    private int references;

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 3) throw new IllegalArgumentException("Expected: mod.jar package-prefix classpath-lines.txt...");
        for (int index = 2; index < arguments.length; index++) {
            VerifyLinks verifier = new VerifyLinks();
            try {
                verifier.archives.add(new ZipFile(arguments[0]));
                for (String jar : Files.readAllLines(Path.of(arguments[index]))) if (!jar.isBlank()) verifier.archives.add(new ZipFile(jar));
                verifier.verify(arguments[1].replace('.', '/'));
            } catch (IllegalStateException exception) {
                throw new IllegalStateException("Compatibility failed: " + Path.of(arguments[index]).getFileName() + "\n" + exception.getMessage(), exception);
            } finally {
                for (ZipFile archive : verifier.archives) archive.close();
            }
        }
    }

    private void verify(String prefix) throws IOException {
        List<String> failures = new ArrayList<>();
        var entries = archives.getFirst().entries();
        int checked = 0;
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.endsWith(".class") || !(name.startsWith(prefix + "/") || name.startsWith("dev/yetanothertown/mixin/") || name.equals("dev/yetanothertown/Bootstrap.class") || name.equals("dev/yetanothertown/ModMenuIntegration.class"))) continue;
            ClassModel model = load(name.substring(0, name.length() - 6));
            checked++;
            if (model.majorVersion() > 69) failures.add(name + ": requires Java newer than 25");
            for (var entry : model.constantPool()) {
                if ((name.startsWith(prefix + "/XaeroBridge") || name.startsWith(prefix + "/ProjectHighlighter"))
                        && entry instanceof Utf8Entry text && Set.of("cameraX", "cameraZ", "scale", "drawArea").contains(text.stringValue()))
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

    /** 检查原生注入目标、区块单位和实际注册路径，防止退回仅自建窗口可用的覆盖层。 */
    private void verifyNativeMap(String prefix, List<String> failures) throws IOException {
        ClassModel map = load("xaero/map/gui/GuiMap");
        for (var field : Map.of("mapTileSelection", "Lxaero/map/gui/MapTileSelection;", "rightClickDim", "Lnet/minecraft/resources/ResourceKey;").entrySet()) {
            if (map == null || map.fields().stream().noneMatch(value -> value.fieldName().equalsString(field.getKey()) && value.fieldType().equalsString(field.getValue())))
                failures.add("Xaero reflection contract changed: GuiMap." + field.getKey() + field.getValue());
        }
        for (String[] method : List.of(
                new String[]{"xaero/map/highlight/HighlighterRegistry", "end", "()V"},
                new String[]{"xaero/map/gui/GuiMap", "getRightClickOptions", "()Ljava/util/ArrayList;"},
                new String[]{"xaero/map/MapProcessor", "confirmMultiworld", "(Lxaero/map/world/MapDimension;)Z"},
                new String[]{prefix + "/XaeroBridge", "registerHighlighter", "(Ljava/lang/Object;)V"},
                new String[]{prefix + "/XaeroBridge", "playerMapKey", "(Ljava/lang/String;)Ljava/lang/String;"},
                new String[]{prefix + "/XaeroBridge", "associateCurrentMap", "(Lnet/minecraft/client/gui/screens/Screen;)Z"},
                new String[]{prefix + "/XaeroBridge", "addMapOptions", "(Ljava/lang/Object;Ljava/lang/Object;)V"}))
            if (!resolves(method[0], method[1], method[2], false, new HashSet<>())) failures.add("Native map hook target missing: " + String.join(" ", method));

        ClassModel highlighter = load(prefix + "/ProjectHighlighter");
        if (highlighter == null || highlighter.superclass().isEmpty() || !highlighter.superclass().get().asInternalName().equals("xaero/map/highlight/ChunkHighlighter"))
            failures.add("Project overlay must use the native ChunkHighlighter texture pipeline");
        if (!calls(load(prefix + "/XaeroBridge"), "registerHighlighter", "xaero/map/highlight/HighlighterRegistry", "register"))
            failures.add("Project highlighter is not registered with the native registry");
        ClassModel bridge = load(prefix + "/XaeroBridge");
        if (calls(bridge, "associateCurrentMap", prefix + "/TownGateway", "selectWorld")
                || calls(bridge, "saveAssociation", prefix + "/TownGateway", "selectWorld")
                || !calls(bridge, "associateCurrentMap", prefix + "/XaeroBridge", "saveAssociation")
                || !calls(bridge, "saveAssociation", prefix + "/TownGateway", "bindCurrentMap")
                || !calls(bridge, "saveAssociation", prefix + "/TownGateway", "unbindMap"))
            failures.add("One-step map association must preserve the selected-world snapshot and restore failed bindings");
        boolean confirmsPlayer = bridge != null && bridge.methods().stream().anyMatch(method -> {
            String name = method.methodName().stringValue();
            return name.startsWith("lambda$associateCurrentMap$")
                    && calls(bridge, name, "xaero/map/MapProcessor", "setMultiworld")
                    && calls(bridge, name, "xaero/map/MapProcessor", "confirmMultiworld")
                    && calls(bridge, name, prefix + "/XaeroBridge", "playerMapKey");
        });
        if (!confirmsPlayer) failures.add("Map association must reselect and confirm through Xaero, then verify the actual player map identity");
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
        if (!calls(map, "extractRenderState", "xaero/map/highlight/DimensionHighlighterHandler", "getBlockHighlightBluntTooltip")
                || !calls(map, "extractRenderState", "xaero/lib/client/gui/widget/Tooltip", "drawBox"))
            failures.add("Native map hover must render the full project tooltip through Xaero's tooltip box");
        verifyMixin("HighlighterRegistryMixin", "xaero/map/highlight/HighlighterRegistry", "end()V", "HEAD", "registerHighlighter", failures);
        verifyMixin("MapMenuMixin", "xaero/map/gui/GuiMap", "getRightClickOptions()Ljava/util/ArrayList;", "RETURN", "addMapOptions", failures);
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
        for (ZipFile archive : archives) {
            var entry = archive.getEntry(name + ".class");
            if (entry == null) continue;
            try (var input = archive.getInputStream(entry)) {
                ClassModel model = ClassFile.of().parse(input.readAllBytes());
                classes.put(name, model);
                return model;
            }
        }
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
