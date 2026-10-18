package dev.yetanothertown.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

/** 直接驱动原版按键分发与生产映射，不创建游戏实例或窗口。 */
public final class TownInputTest {
    private static int checks;
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] arguments) {
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
        System.out.println("TownInput: " + checks + " checks passed through real vanilla dispatch; no game launched");
    }
}
