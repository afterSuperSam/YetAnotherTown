package dev.yetanothertown.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** 直接驱动原版按键分发与生产映射，不创建游戏实例或窗口。 */
public final class TownInputTest {
    private static int checks;
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] arguments) {
        MouseButtonEvent selection = new MouseButtonEvent(120, 100, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOD_SHIFT));
        require(selection.hasShiftDown(), "Native Shift modifier must reach the map event");
        require(XaeroBridge.isSelectionButton(selection), "Physical Shift + left mouse must begin the map selection");
        require(XaeroBridge.isSelectionButton(new MouseButtonEvent(120, 100, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0))), "Releasing Shift before left mouse must still finish the selection");
        require(!XaeroBridge.isSelectionButton(new MouseButtonEvent(120, 100, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, InputConstants.MOD_SHIFT))), "Right mouse must remain available to Xaero");
        require(!XaeroBridge.isSelectionButton(new MouseButtonEvent(120, 100, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_MIDDLE, InputConstants.MOD_SHIFT))), "Middle mouse must remain available to Xaero");
        KeyMapping[] mappings = VersionClient.createKeyMappings();
        KeyMapping.click(InputConstants.getKey("key.keyboard.k"));
        require(mappings[0].consumeClick(), "Physical K did not reach the town menu mapping");
        KeyMapping.click(InputConstants.getKey("key.keyboard.h"));
        require(mappings[1].consumeClick(), "Physical H did not reach the nearby menu mapping");
        require(mappings[0].getCategory().id().toString().equals("yetanothertown:general"), "Dedicated vanilla controls category missing");
        require(mappings[1].getCategory().equals(mappings[0].getCategory()), "Both controls must share the YetAnotherTown category");
        require(KeyMapping.get("key.yetanothertown.open") == mappings[0], "Town action missing from the vanilla mapping registry");
        require(KeyMapping.get("key.yetanothertown.nearby") == mappings[1], "Nearby action missing from the vanilla mapping registry");

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

        mappings[0].setKey(InputConstants.getKey("key.keyboard.page.up"));
        mappings[1].setKey(InputConstants.getKey("key.keyboard.pause"));
        KeyMapping.resetMapping();
        boolean migrated = VersionClient.repairLegacyKeyMappings(mappings);
        if (InputConstants.KEY_K != 75) {
            require(migrated, "Legacy SDL defaults must migrate");
            KeyMapping.click(InputConstants.getKey("key.keyboard.k"));
            require(mappings[0].consumeClick(), "Migrated K must reach the town menu");
            KeyMapping.click(InputConstants.getKey("key.keyboard.h"));
            require(mappings[1].consumeClick(), "Migrated H must reach nearby information");
        } else require(!migrated, "GLFW custom PageUp/Pause bindings must remain unchanged");

        mappings[0].setKey(InputConstants.getKey("key.keyboard.j"));
        mappings[1].setKey(InputConstants.getKey("key.keyboard.n"));
        KeyMapping.resetMapping();
        require(!VersionClient.repairLegacyKeyMappings(mappings), "Custom native bindings must never be reset by migration");
        require(mappings[0].saveString().equals("key.keyboard.j") && mappings[1].saveString().equals("key.keyboard.n"), "Custom native bindings must be retained");
        mappings[0].setKey(InputConstants.UNKNOWN);
        KeyMapping.resetMapping();
        require(mappings[0].isUnbound(), "Vanilla unbound state must be supported");
        require(!VersionClient.repairLegacyKeyMappings(mappings), "Unbound action must not be reset");
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
                TownForm.parseNumber("提示距离（方块）", invalid);
                throw new AssertionError("Invalid numeric input must be rejected: " + invalid);
            } catch (NumberFormatException exception) {
                require(exception.getMessage().equals("提示距离（方块）必须填写有效整数"), "Numeric errors must identify the field");
            }
        }
        var project = TownGateway.object("id", "private-record-id", "name", "中央车站", "purpose", "Rail Hub", "status", "maintenance");
        require(TownScreen.matchesSearch(project, "DogSam", " 车站 DOGSAM rail "), "Search must combine Chinese text and case-insensitive owner keywords");
        require(TownScreen.matchesSearch(project, "DogSam", "待维护"), "Search must recognize visible state labels");
        require(!TownScreen.matchesSearch(project, "DogSam", "车站 仓库"), "Every search keyword must match");
        require(!TownScreen.matchesSearch(project, "DogSam", "private-record-id"), "Search must not match internal identifiers");
        require(TownScreen.matchesSearch(TownGateway.object("text", "材料在二楼"), "车站", "二楼 车站"), "Messages must be searchable by content and linked project");
        project.addProperty("status", "completed");
        require(TownScreen.matchesSearch(project, "DogSam", " ") && TownScreen.state("completed").isEmpty(), "Clearing search must retain completed projects without a status label");
        System.out.println("TownInput: " + checks + " checks passed through real vanilla dispatch; no game launched");
    }
}
