package dev.yetanothertown.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import xaero.map.gui.MapTileSelection;

/** 直接驱动原版按键分发与生产映射，不创建游戏实例或窗口。 */
public final class TownInputTest {
    private static int checks;
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] arguments) throws Exception {
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
        java.util.function.ToIntFunction<String> measure = text -> text.codePointCount(0, text.length());
        require(ProjectHighlighter.textLines("第一行\r\n第二行\n第三行\r第四行", 10, measure).equals(java.util.List.of("第一行", "第二行", "第三行", "第四行")), "Hover must render each newline as a separate line, never as a control glyph");
        require(ProjectHighlighter.textLines("a😀b", 2, measure).equals(java.util.List.of("a😀", "b")), "Wrapped hover text must preserve Unicode surrogate pairs");
        require(ProjectHighlighter.textLines("abcdef", 2, measure).equals(java.util.List.of("ab", "cd", "ef")), "Long descriptions must wrap to the available width");
        require(ProjectHighlighter.textLines("a\n\nb", 10, measure).equals(java.util.List.of("a", "", "b")), "Paragraph spacing must survive wrapping");
        int[] lowerTooltip = ProjectHighlighter.tooltipPosition(640, 360, 280, 160, 638, 358);
        require(lowerTooltip[0] >= 16 && lowerTooltip[0] + 280 <= 624 && lowerTooltip[1] >= 16 && lowerTooltip[1] + 160 <= 344, "Tooltip near bottom-right must retain space for its background inside the screen");
        int[] upperTooltip = ProjectHighlighter.tooltipPosition(640, 360, 280, 160, 0, 0);
        require(upperTooltip[0] == 16 && upperTooltip[1] == 16, "Tooltip near top-left must remain inside the screen");
        java.util.function.ToIntFunction<String> pixels = text -> text.codePointCount(0, text.length()) * 8;
        var shortTooltip = ProjectHighlighter.tooltipLayout("袭击塔", "DogSam", "一个袭击塔", "completed", 640, 360, 9, pixels);
        require(shortTooltip.width() < 248 && shortTooltip.owner().equals(java.util.List.of("DogSam"))
                && shortTooltip.introduction().equals(java.util.List.of("一个袭击塔")), "Short hover cards must fit their content without redundant field labels");
        require(shortTooltip.ownerTop() >= shortTooltip.titleHeight() && shortTooltip.introductionTop() > shortTooltip.ownerTop(), "Hover title, player ID and introduction must occupy separate rows");
        var longTooltip = ProjectHighlighter.tooltipLayout("长工程名称".repeat(20), "PlayerName1234567", "长简介😀".repeat(200), "planned", 160, 140, 9, pixels);
        require(longTooltip.width() <= 128 && longTooltip.height() <= 108, "Wrapped hover card must fit the minimum supported viewport");
        require(longTooltip.introduction().getLast().endsWith("…"), "Truncated introductions must indicate hidden content");
        require(longTooltip.title().size() == 1 && longTooltip.owner().size() == 1 && longTooltip.avatarSize() == 22, "Hover header must remain two rows beside a compact avatar even with long names");
        require(longTooltip.statusLeft() + pixels.applyAsInt("计划中") <= longTooltip.width(), "Inline status must remain inside a narrow card after title truncation");
        require(longTooltip.introductionTop() > longTooltip.avatarSize()
                && pixels.applyAsInt(longTooltip.introduction().getFirst()) > longTooltip.width() - longTooltip.textLeft(), "Description must use the entire card width below the avatar");
        var emptyTooltip = ProjectHighlighter.tooltipLayout("Test", "DogSam", "", "completed", 640, 360, 9, pixels);
        require(emptyTooltip.introduction().isEmpty() && emptyTooltip.height() == emptyTooltip.avatarSize(), "Empty description and completed status must not leave empty footer rows");
        for (int viewport : new int[]{320, 480, 640, 960}) {
            var window = TownStyle.bounds(viewport, 360, 560, 346);
            require(window.left() >= 0 && window.right() <= viewport && Math.abs(window.left() - (viewport - window.right())) <= 1, "Main panel must remain centered with navigation inside the window");
        }
        require(TownStyle.compactStatus("小镇设置没有修改").equals("未作修改"), "No-change feedback must use a concise meaningful label");
        for (String status : new String[]{"已连接当前游戏身份", "正在使用当前游戏身份连接…", "长提示😀".repeat(8), "", "没有小镇管理权限"}) {
            String compact = TownStyle.compactStatus(status);
            require(compact.codePointCount(0, compact.length()) <= 8 && !compact.endsWith("\uD83D…"), "Header feedback must fit eight complete Unicode characters");
        }
        require(ProjectHighlighter.projectIcon("machine").equals("minecraft:redstone") && ProjectHighlighter.projectIcon("building").equals("minecraft:quartz")
                && ProjectHighlighter.projectIcon("other").equals("minecraft:crafting_table"), "Project categories must use their designated vanilla items");
        var activeWorld = TownGateway.object("id", "0123456789abcdef01234567", "archived", false);
        require(XaeroBridge.associationBlockReason(activeWorld, true, "minecraft:overworld", "minecraft:overworld", "town", "town").isEmpty(), "A loaded current map must allow one-step association without an existing binding");
        activeWorld.addProperty("archived", true);
        require(!XaeroBridge.associationBlockReason(activeWorld, true, "minecraft:overworld", "minecraft:overworld", "history", "history").isEmpty(), "Historical maps must never become the current player world through association");
        activeWorld.addProperty("archived", false);
        require(!XaeroBridge.associationBlockReason(activeWorld, false, "minecraft:overworld", "minecraft:overworld", "town", "town").isEmpty(), "A stale native main world must not associate a map");
        require(!XaeroBridge.associationBlockReason(activeWorld, true, "minecraft:overworld", "minecraft:the_nether", "town", "town").isEmpty(), "Browsing another dimension must not associate it as the player's current map");
        require(!XaeroBridge.associationBlockReason(activeWorld, true, "minecraft:overworld", "minecraft:overworld", "town", "history").isEmpty(), "An unsettled native map selection must not associate a different displayed map");
        require(!XaeroBridge.associationBlockReason(new com.google.gson.JsonObject(), true, "minecraft:overworld", "minecraft:overworld", "town", "town").isEmpty(), "Missing selected-world metadata must not bypass historical protection");
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
        settingsTests();
        navigationTests();
        nativeAssociationTests();
        System.out.println("TownInput: " + checks + " checks passed through real vanilla dispatch and native selection; no game launched");
    }

    private static void settingsTests() {
        var scope = new TownGateway.Context(3, 4, "http://town");
        require(TownScreen.sameSettingsScope("12345678", "http://town", 3, "12345678", scope)
                && TownScreen.sameSettingsScope("12345678", "http://town", 3, "12345678", new TownGateway.Context(3, 5, "http://town")), "Changing only the selected world must preserve same-town settings drafts");
        require(!TownScreen.sameSettingsScope("12345678", "http://old", 3, "12345678", scope)
                && !TownScreen.sameSettingsScope("12345678", "http://town", 2, "12345678", scope)
                && !TownScreen.sameSettingsScope("87654321", "http://town", 3, "12345678", scope), "Settings drafts must not cross service, identity or town boundaries");
        require(TownScreen.synchronizedSettingValue("old", "old", "remote").equals("remote"), "Clean settings fields must follow remote updates");
        require(TownScreen.synchronizedSettingValue("edited", "old", "remote").equals("edited"), "Remote refresh must preserve locally edited settings fields");
        var values = java.util.Map.of("name", "old", "introduction", "edited", "password", "", "unexpected", "ignore");
        var baseline = java.util.Map.of("name", "old", "introduction", "previous", "password", "");
        var snapshot = TownGateway.object("town", TownGateway.object("id", "12345678", "name", "remote"));
        require(!TownScreen.validSettingsTown(new JsonObject(), "12345678") && TownScreen.changedTownSettings(values, baseline, new JsonObject(), "12345678").isEmpty(), "Unloaded town settings must not produce an update payload");
        require(TownScreen.changedTownSettings(values, baseline, snapshot, "87654321").isEmpty(), "A snapshot for a different town must not produce settings changes");
        require(TownScreen.changedTownSettings(values, baseline, snapshot, "12345678").equals(TownGateway.object("introduction", "edited")), "Saving a changed introduction must not overwrite a clean name updated remotely or send blank password/unknown fields");
        require(TownScreen.changedTownSettings(java.util.Map.of("password", "secret123"), baseline, snapshot, "12345678").equals(TownGateway.object("password", "secret123")), "An explicitly entered password must be submitted");
    }

    /** 导航使用真实状态更新与清理路径，抵达区域边界就停止，不要求走到中心点。 */
    private static void navigationTests() throws Exception {
        TownGateway gateway = TownGateway.get();
        JsonObject target = TownGateway.object("id", "station", "name", "车站", "worldId", gateway.worldId,
                "dimension", "minecraft:overworld", "chunks", TownGateway.object("minX", -2, "maxX", 2, "minZ", -3, "maxZ", 3));
        try {
            seedNavigation(target);
            var action = new TownForm.Action("导航到这里", () -> {}, () -> TownScreen.isNavigationTarget(target, VersionClient.navigation()) ? "停止导航" : "导航到这里");
            require(action.label().equals("停止导航"), "A live navigation action must show cancellation while its target is active");
            VersionClient.updateNavigation(true, "minecraft:overworld", -33, 0);
            require(VersionClient.navigation() != null, "Navigation must remain active outside the target region");
            VersionClient.updateNavigation(true, "minecraft:overworld", -32, 0);
            require(VersionClient.navigation() == null, "Arriving at the target region edge must remove the navigation HUD");
            require(action.label().equals("导航到这里"), "The same detail action must update its label after automatic arrival");
            seedNavigation(target);
            VersionClient.updateNavigation(true, "minecraft:overworld", 47, 63);
            require(VersionClient.navigation() == null, "Maximum included chunk must complete navigation without reaching its center");
            seedNavigation(target);
            VersionClient.updateNavigation(true, "minecraft:overworld", 48, 63);
            require(VersionClient.navigation() != null, "Adjacent positive chunk must not complete navigation");
            VersionClient.updateNavigation(true, "minecraft:the_nether", 0, 0);
            require(VersionClient.navigation() == null, "Changing dimension must clear stale navigation");
            seedNavigation(target);
            VersionClient.updateNavigation(false, "minecraft:overworld", -100, 0);
            require(VersionClient.navigation() == null, "Losing the current map or connection must clear navigation");
            seedNavigation(target);
            nativeField(null, VersionClient.class, "navigationContext", new TownGateway.Context(-1, -1, "old-service"));
            VersionClient.updateNavigation(true, "minecraft:overworld", -100, 0);
            require(VersionClient.navigation() == null, "Navigation must not survive an identity or service context change");
            var point = TownGateway.object("id", "ticket", "title", "维护位置", "worldId", gateway.worldId, "dimension", "minecraft:overworld", "x", -17, "z", 3);
            seedNavigation(point);
            VersionClient.updateNavigation(true, "minecraft:overworld", -18, 3);
            require(VersionClient.navigation() != null, "Point navigation must remain until its destination block is reached");
            VersionClient.updateNavigation(true, "minecraft:overworld", -17, 3);
            require(VersionClient.navigation() == null, "Reaching a maintenance coordinate must complete navigation");
            require(gateway.status.equals("已到达：维护位置"), "Arrival messages must use the ticket title for maintenance destinations");
            var constructor = xaero.hud.minimap.waypoint.set.WaypointSet.class.getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            var waypoints = constructor.newInstance("test");
            var own = (xaero.common.minimap.waypoints.Waypoint) allocateNative(xaero.common.minimap.waypoints.Waypoint.class);
            var other = (xaero.common.minimap.waypoints.Waypoint) allocateNative(xaero.common.minimap.waypoints.Waypoint.class);
            waypoints.add(own); waypoints.add(other);
            seedNavigation(target);
            nativeField(null, XaeroBridge.class, "navigationSet", waypoints);
            nativeField(null, XaeroBridge.class, "navigationWaypoint", own);
            VersionClient.stopNavigation(); VersionClient.stopNavigation();
            require(VersionClient.navigation() == null && waypoints.size() == 1 && waypoints.get(0) == other, "Stopping navigation must remove only our native waypoint and remain safe when repeated");
            waypoints.add(own); seedNavigation(target);
            nativeField(null, XaeroBridge.class, "navigationSet", waypoints);
            nativeField(null, XaeroBridge.class, "navigationWaypoint", own);
            VersionClient.updateNavigation(true, "minecraft:overworld", 0, 0);
            require(VersionClient.navigation() == null && waypoints.size() == 1, "Arrival must clear both HUD state and the actual native waypoint");
        } finally { VersionClient.clearNavigation(); }
    }

    private static void seedNavigation(JsonObject target) throws Exception {
        nativeField(null, VersionClient.class, "navigation", target);
        nativeField(null, VersionClient.class, "navigationTown", TownGateway.get().townId);
        nativeField(null, VersionClient.class, "navigationWorld", TownGateway.get().worldId);
        nativeField(null, VersionClient.class, "navigationContext", TownGateway.get().context());
    }

    /** 无窗口初始化注册表；只替代配置落盘和注入字段，确认及玩家地图解析全部执行原生代码。 */
    private static void nativeAssociationTests() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Class<?> levelType = nativeFixtureType("Level", net.minecraft.client.multiplayer.ClientLevel.class, null);
        Class<?> worldType = nativeFixtureType("World", xaero.map.world.MapWorld.class, "saveConfig");
        Class<?> dimensionType = nativeFixtureType("Dimension", xaero.map.world.MapDimension.class, "saveConfigUnsynced");
        NativeFixture named = nativeFixture(1, false, levelType, worldType, dimensionType);
        TownGateway gateway = TownGateway.get();
        String previousTown = gateway.townId, previousWorld = gateway.worldId;
        JsonObject previousSnapshot = gateway.snapshot;
        Object previousToken = nativeField(gateway, TownGateway.class, "token");
        var previousBindings = new java.util.TreeMap<>(gateway.config().mapBindings);
        try {
            gateway.townId = "12345678"; gateway.worldId = "0123456789abcdef01234567";
            nativeField(gateway, TownGateway.class, "token", "native-map-name-test");
            JsonObject world = TownGateway.object("id", gateway.worldId, "serverAddress", "singleplayer", "archived", false, "mapName", "小镇周目");
            JsonArray worlds = new JsonArray(); worlds.add(world);
            gateway.snapshot = TownGateway.object("town", TownGateway.object("currentWorldId", gateway.worldId), "worlds", worlds);
            var mapNames = new java.util.Hashtable<String, String>(); mapNames.put("cm$town", "小镇周目");
            nativeField(named.dimension(), xaero.map.world.MapDimension.class, "multiworldNames", mapNames);
            require(XaeroBridge.resolvedMapKey(named.dimension()).equals("test-server|cm$town"), "The actual native shared map name must resolve without a personal binding");
            mapNames.put("cm$town", " 小镇周目 ");
            require(XaeroBridge.resolvedMapKey(named.dimension()).equals("test-server|cm$town"), "Native map names must use the same whitespace normalization as the service");
            String binding = gateway.config().endpoint + "#" + gateway.townId + "/" + gateway.worldId + "/minecraft:overworld";
            gateway.config().mapBindings.put(binding, "test-server|cm$town");
            mapNames.put("cm$town", "其他周目");
            require(XaeroBridge.resolvedMapKey(named.dimension()).isEmpty(), "A stale personal cache must not override a renamed native map");
            world.add("mapNames", TownGateway.object("minecraft:overworld", "其他周目"));
            require(XaeroBridge.resolvedMapKey(named.dimension()).equals("test-server|cm$town"), "An explicit dimension name must override the shared default name");
            world.addProperty("archived", true);
            require(XaeroBridge.resolvedMapKey(named.dimension()).equals("test-server|cm$town"), "Archived maps must retain their personal historical association");
            gateway.config().mapBindings.remove(binding);
            require(XaeroBridge.resolvedMapKey(named.dimension()).isEmpty(), "Shared names must never infer an unbound historical map");
        } finally {
            gateway.townId = previousTown; gateway.worldId = previousWorld; gateway.snapshot = previousSnapshot;
            nativeField(gateway, TownGateway.class, "token", previousToken);
            gateway.config().mapBindings.clear(); gateway.config().mapBindings.putAll(previousBindings);
        }
        for (int mode = 0; mode < 3; mode++) for (boolean serverId : new boolean[]{false, true}) {
            NativeFixture fixture = nativeFixture(mode, serverId, levelType, worldType, dimensionType);
            require(XaeroBridge.confirmAssociation(fixture.processor(), fixture.dimension(), "test-server|cm$town"), "One-step native association must work in every mode: " + mode + "/" + serverId);
            require(fixture.world().getPlayerMapKey().getMw().equals("cm$town"), "The full native player lookup must resolve the selected map after confirmation");
            require(nativeField(fixture.world(), xaero.map.world.MapWorld.class, "futureMultiworldType").equals(mode == 0 && !serverId ? 1 : mode), "Only conflicting Single mode may change; existing Manual, Spawn and server-controlled modes must remain unchanged");
        }
        NativeFixture matching = nativeFixture(0, false, levelType, worldType, dimensionType);
        Object previousSuspended = nativeField(null, XaeroBridge.class, "suspendedMap");
        try {
            nativeField(null, XaeroBridge.class, "suspendedMap", allocateNative(Class.forName(XaeroBridge.class.getName() + "$BoundMap")));
            require(XaeroBridge.currentMapSetupBlockReason().contains("关闭历史地图"), "A suspended historical map must not be published as the player's current map");
        } finally { nativeField(null, XaeroBridge.class, "suspendedMap", previousSuspended); }
        String automatic = matching.world().getPlayerMapKey().getMw();
        nativeField(matching.dimension(), xaero.map.world.MapDimension.class, "currentMultiworld", automatic);
        nativeField(matching.dimension(), xaero.map.world.MapDimension.class, "futureCustomSelectedMultiworld", automatic);
        nativeField(matching.processor(), xaero.map.MapProcessor.class, "waitingForWorldUpdate", true);
        require(XaeroBridge.confirmAssociation(matching.processor(), matching.dimension(), "test-server|" + automatic)
                && matching.world().getFutureMultiworldType(matching.dimension()) == 0, "An already matching Single map must not change mode or be rejected by a pending worker update");
        for (int mode = 1; mode < 3; mode++) {
            NativeFixture waiting = nativeFixture(mode, false, levelType, worldType, dimensionType);
            nativeField(waiting.processor(), xaero.map.MapProcessor.class, "waitingForWorldUpdate", true);
            require(XaeroBridge.confirmAssociation(waiting.processor(), waiting.dimension(), "test-server|cm$town")
                    && waiting.world().getPlayerMapKey().getMw().equals("cm$town"), "A pending worker update must not block valid Manual or World Spawn confirmation: " + mode);
        }
        NativeFixture serverWaiting = nativeFixture(0, true, levelType, worldType, dimensionType);
        nativeField(serverWaiting.processor(), xaero.map.MapProcessor.class, "waitingForWorldUpdate", true);
        require(XaeroBridge.confirmAssociation(serverWaiting.processor(), serverWaiting.dimension(), "test-server|cm$town"), "Server-controlled effective mode must stay usable while the worker updates");
        NativeFixture firstAssociation = nativeFixture(0, false, levelType, worldType, dimensionType);
        nativeField(firstAssociation.dimension(), xaero.map.world.MapDimension.class, "confirmedMultiworld", null);
        require(XaeroBridge.confirmAssociation(firstAssociation.processor(), firstAssociation.dimension(), "test-server|cm$town")
                && firstAssociation.world().getPlayerMapKey().getMw().equals("cm$town"), "The first Single-mode association must work without any previously confirmed map");
        for (int invalid = 0; invalid < 6; invalid++) {
            NativeFixture fixture = nativeFixture(0, false, levelType, worldType, dimensionType);
            xaero.map.world.MapDimension target = fixture.dimension();
            switch (invalid) {
                case 0 -> nativeField(fixture.processor(), xaero.map.MapProcessor.class, "mapWorldUsable", false);
                case 1 -> fixture.processor().mainWorld = null;
                case 2 -> fixture.world().setCustomDimensionId(net.minecraft.world.level.Level.NETHER);
                case 3 -> { target = (xaero.map.world.MapDimension) allocateNative(dimensionType); nativeField(target, xaero.map.world.MapDimension.class, "mapWorld", fixture.world()); }
                case 4 -> nativeField(fixture.processor(), xaero.map.MapProcessor.class, "waitingForWorldUpdate", true);
                case 5 -> nativeField(fixture.dimension(), xaero.map.world.MapDimension.class, "futureCustomSelectedMultiworld", "other-map");
            }
            require(!XaeroBridge.confirmAssociation(fixture.processor(), target, "test-server|cm$town")
                    && nativeField(fixture.world(), xaero.map.world.MapWorld.class, "futureMultiworldType").equals(0)
                    && nativeField(fixture.dimension(), xaero.map.world.MapDimension.class, "confirmedMultiworld").equals("cm$old"), "Invalid native preconditions must not change mode or confirmation: " + invalid);
        }
        nativeField(null, net.minecraft.client.Minecraft.class, "instance", null);
    }

    private record NativeFixture(xaero.map.MapProcessor processor, xaero.map.world.MapWorld world, xaero.map.world.MapDimension dimension) { }

    private static NativeFixture nativeFixture(int mode, boolean serverId, Class<?> levelType, Class<?> worldType, Class<?> dimensionType) throws Exception {
        var client = (net.minecraft.client.Minecraft) allocateNative(net.minecraft.client.Minecraft.class);
        var level = (net.minecraft.client.multiplayer.ClientLevel) allocateNative(levelType);
        nativeField(level, net.minecraft.world.level.Level.class, "dimension", net.minecraft.world.level.Level.OVERWORLD);
        var data = new xaero.map.mcworld.WorldMapClientWorldData(level);
        data.latestSpawn = new net.minecraft.core.BlockPos(0, 64, 0); data.serverLevelId = serverId ? 77 : null;
        ((xaero.map.mcworld.IWorldMapClientWorld) level).setXaero_worldmapData(data);
        client.level = level; nativeField(null, net.minecraft.client.Minecraft.class, "instance", client);
        var processor = (xaero.map.MapProcessor) allocateNative(xaero.map.MapProcessor.class);
        var world = (xaero.map.world.MapWorld) allocateNative(worldType);
        var dimension = (xaero.map.world.MapDimension) allocateNative(dimensionType);
        processor.mainWorld = level;
        nativeField(processor, xaero.map.MapProcessor.class, "mapWorld", world); nativeField(processor, xaero.map.MapProcessor.class, "mapWorldUsable", true);
        nativeField(world, xaero.map.world.MapWorld.class, "mapProcessor", processor); nativeField(world, xaero.map.world.MapWorld.class, "mainId", "test-server");
        nativeField(world, xaero.map.world.MapWorld.class, "isMultiplayer", true); nativeField(world, xaero.map.world.MapWorld.class, "futureMultiworldType", mode);
        nativeField(world, xaero.map.world.MapWorld.class, "currentDimensionId", net.minecraft.world.level.Level.OVERWORLD);
        var dimensions = new java.util.Hashtable<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, xaero.map.world.MapDimension>();
        dimensions.put(net.minecraft.world.level.Level.OVERWORLD, dimension); nativeField(world, xaero.map.world.MapWorld.class, "dimensions", dimensions);
        nativeField(dimension, xaero.map.world.MapDimension.class, "mapWorld", world); nativeField(dimension, xaero.map.world.MapDimension.class, "dimId", net.minecraft.world.level.Level.OVERWORLD);
        nativeField(dimension, xaero.map.world.MapDimension.class, "autoMultiworldBindings", new java.util.Hashtable<String, String>());
        nativeField(dimension, xaero.map.world.MapDimension.class, "multiworldIds", new java.util.ArrayList<>(java.util.List.of("cm$town")));
        nativeField(dimension, xaero.map.world.MapDimension.class, "multiworldNames", new java.util.Hashtable<String, String>());
        nativeField(dimension, xaero.map.world.MapDimension.class, "currentMultiworld", "cm$town"); nativeField(dimension, xaero.map.world.MapDimension.class, "futureCustomSelectedMultiworld", "cm$town");
        nativeField(dimension, xaero.map.world.MapDimension.class, "confirmedMultiworld", "cm$old"); dimension.futureMultiworldWritable = true;
        processor.updateVisitedDimension(level);
        return new NativeFixture(processor, world, dimension);
    }

    /** 无构造器空壳跨三个 Minecraft 版本复用，避免测试依赖 ClientLevel 构造参数。 */
    private static Class<?> nativeFixtureType(String suffix, Class<?> parent, String saveMethod) throws Exception {
        var type = java.lang.constant.ClassDesc.of(TownInputTest.class.getPackageName() + ".NativeFixture" + suffix);
        var data = java.lang.constant.ClassDesc.of("xaero.map.mcworld.WorldMapClientWorldData");
        byte[] bytes = java.lang.classfile.ClassFile.of().build(type, builder -> {
            builder.withSuperclass(java.lang.constant.ClassDesc.of(parent.getName()));
            if (saveMethod != null) builder.withMethodBody(saveMethod, java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void), java.lang.classfile.ClassFile.ACC_PUBLIC, code -> code.return_());
            else {
                builder.withInterfaceSymbols(java.lang.constant.ClassDesc.of("xaero.map.mcworld.IWorldMapClientWorld"));
                builder.withField("data", data, java.lang.classfile.ClassFile.ACC_PRIVATE);
                builder.withMethodBody("getXaero_worldmapData", java.lang.constant.MethodTypeDesc.of(data), java.lang.classfile.ClassFile.ACC_PUBLIC, code -> code.aload(0).getfield(type, "data", data).areturn());
                builder.withMethodBody("setXaero_worldmapData", java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void, data), java.lang.classfile.ClassFile.ACC_PUBLIC, code -> code.aload(0).aload(1).putfield(type, "data", data).return_());
            }
        });
        return java.lang.invoke.MethodHandles.lookup().defineClass(bytes);
    }

    private static Object allocateNative(Class<?> type) throws Exception {
        Class<?> allocator = Class.forName("sun.misc.Unsafe");
        var field = allocator.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return allocator.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
    }
    private static Object nativeField(Object target, Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void nativeField(Object target, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
