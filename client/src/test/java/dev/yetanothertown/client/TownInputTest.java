package dev.yetanothertown.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import com.google.gson.JsonArray;
import xaero.map.gui.MapTileSelection;

/** 直接驱动原版按键分发与生产映射，不创建游戏实例或窗口。 */
public final class TownInputTest {
    private static int checks;
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] arguments) {
        MapTileSelection selection = new MapTileSelection(2, 4);
        selection.setEnd(-3, -5);
        var chunks = XaeroBridge.selectionChunks(selection, "minecraft:overworld");
        require(chunks.get("minX").getAsInt() == -3 && chunks.get("maxX").getAsInt() == 2 && chunks.get("minZ").getAsInt() == -5 && chunks.get("maxZ").getAsInt() == 4, "Native reverse drag must preserve inclusive chunk coordinates without block scaling");
        require(chunks.get("dimension").getAsString().equals("minecraft:overworld"), "Native selection must retain its displayed dimension");
        require(ProjectHighlighter.nativeColor(0x123456, 0x78) == 0x56341278, "Native texture color channels must match Xaero BBGGRRAA");
        require(ProjectHighlighter.intersects(-32, -1, 0, 3, -1, 2, 3, 6), "Shared edge chunk must count as occupied");
        require(!ProjectHighlighter.intersects(-32, -1, 0, 3, 0, 2, 0, 3), "Adjacent negative and positive chunks must not overlap");
        require(ProjectHighlighter.summary("第一行\n 第二行", 100).equals("第一行 第二行"), "Hover instructions must collapse line breaks without losing text");
        require(ProjectHighlighter.summary("a😀b", 2).equals("a😀…"), "Hover truncation must preserve complete Unicode characters");
        KeyMapping[] mappings = VersionClient.createKeyMappings();
        KeyMapping.click(InputConstants.getKey("key.keyboard.k"));
        require(mappings[0].consumeClick(), "Physical K did not reach the town menu mapping");
        KeyMapping.click(InputConstants.getKey("key.keyboard.x"));
        require(mappings[1].consumeClick(), "Physical X did not reach the current project mapping");
        require(mappings[0].getCategory().id().toString().equals("yetanothertown:general"), "Dedicated vanilla controls category missing");
        require(mappings[1].getCategory().equals(mappings[0].getCategory()), "Both controls must share the Yet Another Town category");
        require(KeyMapping.get("key.yetanothertown.open") == mappings[0], "Town action missing from the vanilla mapping registry");
        require(KeyMapping.get("key.yetanothertown.project") == mappings[1], "Nearby action missing from the vanilla mapping registry");

        mappings[0].setKey(InputConstants.getKey("key.keyboard.j"));
        KeyMapping.resetMapping();
        KeyMapping.click(InputConstants.getKey("key.keyboard.k"));
        require(!mappings[0].consumeClick(), "Native remapping must release the old key");
        KeyMapping.click(InputConstants.getKey("key.keyboard.j"));
        require(mappings[0].consumeClick(), "Native remapping must dispatch the new key");
        String savedBinding = mappings[0].saveString();
        mappings[0].setKey(InputConstants.getKey(savedBinding));
        KeyMapping.resetMapping();
        KeyMapping.click(InputConstants.getKey("key.keyboard.j"));
        require(mappings[0].consumeClick(), "Vanilla symbolic binding must survive save/load");

        mappings[0].setKey(InputConstants.UNKNOWN);
        KeyMapping.resetMapping();
        require(mappings[0].isUnbound(), "Vanilla unbound state must be supported");
        String[] choices = {"first", "second", "third"};
        require(TownForm.choiceIndex(choices, "first", -1) == 2, "Reverse selection must wrap to the last option");
        require(TownForm.choiceIndex(choices, "third", 1) == 0, "Forward selection must wrap to the first option");
        require(TownForm.choiceIndex(choices, "second", -1) == 0, "Reverse selection must move to the preceding option");
        require(TownForm.choiceIndex(new String[]{"only"}, "only", -1) == 0, "Single option must remain selected");
        require(TownForm.choiceIndex(new String[0], "", 1) == -1, "Empty selection must have no option");
        require(TownForm.parseNumber("坐标", " -2147483648 ") == Integer.MIN_VALUE, "Negative coordinates and surrounding whitespace must be accepted");
        require(TownForm.parseNumber("数量", "2147483647") == Integer.MAX_VALUE, "Maximum integer must be accepted");
        for (String invalid : new String[]{"", "1.5", "2147483648"}) {
            try {
                TownForm.parseNumber("提示时间（秒）", invalid);
                throw new AssertionError("Invalid numeric input must be rejected: " + invalid);
            } catch (NumberFormatException exception) {
                require(exception.getMessage().equals("提示时间（秒）必须填写有效整数"), "Numeric errors must identify the field");
            }
        }
        var project = TownGateway.object("id", "private-record-id", "name", "中央车站", "purpose", "Rail Hub", "status", "maintenance");
        require(TownScreen.matchesSearch(project, "DogSam", " 车站 DOGSAM rail "), "Search must combine Chinese text and case-insensitive owner keywords");
        require(TownScreen.matchesSearch(project, "DogSam", "待维护"), "Search must recognize visible state labels");
        require(!TownScreen.matchesSearch(project, "DogSam", "车站 仓库"), "Every search keyword must match");
        require(!TownScreen.matchesSearch(project, "DogSam", "private-record-id"), "Search must not match internal identifiers");
        require(TownScreen.matchesSearch(TownGateway.object("purpose", "一楼拉杆启动\n请勿进入村民区域"), "车站", "拉杆 村民"), "Project instructions must be searchable across multiple lines");
        project.addProperty("status", "completed");
        require(TownScreen.matchesSearch(project, "DogSam", " ") && TownScreen.state("completed").isEmpty(), "Clearing search must retain completed projects without a status label");
        JsonArray projects = new JsonArray();
        project.addProperty("worldId", "current"); project.addProperty("dimension", "minecraft:overworld"); project.add("chunks", chunks);
        projects.add(project);
        var snapshot = TownGateway.object("projects", projects);
        require(TownHud.projectAt(snapshot, "current", "minecraft:overworld", -3, -5) == project, "Entry detection must include the negative minimum boundary");
        require(TownHud.projectAt(snapshot, "current", "minecraft:overworld", 2, 4) == project, "Entry detection must include the maximum boundary");
        require(TownHud.projectAt(snapshot, "current", "minecraft:overworld", 3, 4) == null, "Adjacent outside chunk must not trigger entry");
        require(TownHud.projectAt(snapshot, "history", "minecraft:overworld", 0, 0) == null && TownHud.projectAt(snapshot, "current", "minecraft:the_nether", 0, 0) == null, "Entry detection must isolate worlds and dimensions");
        var tracker = new TownHud.EntryTracker();
        require(tracker.enter("station", 0, 8), "First entry must announce immediately");
        require(!tracker.enter("station", 20, 8), "Staying inside must not repeat the subtitle");
        require(!tracker.enter("", 21, 8) && tracker.enter("station", 22, 8), "A later re-entry may announce after the cooldown");
        require(!tracker.enter("", 23, 8) && !tracker.enter("station", 24, 8), "Boundary jitter must not repeat the subtitle");
        require(tracker.enter("warehouse", 25, 8), "Entering a different project must not be suppressed by the previous project's cooldown");
        require(!tracker.enter("station", 26, 8), "Crossing a shared project boundary must still respect each project's cooldown");
        tracker.reset();
        require(tracker.enter("station", 25, 8), "Changing world context must reset entry detection");
        System.out.println("TownInput: " + checks + " checks passed through real vanilla dispatch and native selection; no game launched");
    }
}
