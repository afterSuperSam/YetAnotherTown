import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
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
        if (arguments.length != 3) throw new IllegalArgumentException("Expected: mod.jar package-prefix classpath-lines.txt");
        VerifyLinks verifier = new VerifyLinks();
        try {
            verifier.archives.add(new ZipFile(arguments[0]));
            for (String jar : Files.readAllLines(Path.of(arguments[2]))) if (!jar.isBlank()) verifier.archives.add(new ZipFile(jar));
            verifier.verify(arguments[1].replace('.', '/'));
        } finally {
            for (ZipFile archive : verifier.archives) archive.close();
        }
    }

    private void verify(String prefix) throws IOException {
        List<String> failures = new ArrayList<>();
        var entries = archives.getFirst().entries();
        int checked = 0;
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.endsWith(".class") || !(name.startsWith(prefix + "/") || name.equals("dev/yetanothertown/Bootstrap.class") || name.equals("dev/yetanothertown/ModMenuIntegration.class"))) continue;
            ClassModel model = load(name.substring(0, name.length() - 6));
            checked++;
            if (model.majorVersion() > 69) failures.add(name + ": requires Java newer than 25");
            for (var entry : model.constantPool()) {
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
        for (String field : List.of("cameraX", "cameraZ", "scale")) {
            ClassModel map = load("xaero/map/gui/GuiMap");
            if (map == null || map.fields().stream().noneMatch(value -> value.fieldName().equalsString(field) && value.fieldType().equalsString("D")))
                failures.add("Xaero reflection contract changed: GuiMap." + field + " must be a declared double field");
        }
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
