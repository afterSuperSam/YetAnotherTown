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

    private static void useLanguage(String language) throws Exception {
        java.util.Map<String, String> translations = new java.util.HashMap<>();
        try (var stream = TownText.class.getResourceAsStream("/assets/yetanothertown/lang/" + language + ".json")) {
            net.minecraft.locale.Language.loadFromJson(stream, translations::put);
        }
        var previous = net.minecraft.locale.Language.getInstance();
        net.minecraft.locale.Language.inject(new net.minecraft.locale.Language() {
            public String getOrDefault(String key, String fallback) { return translations.getOrDefault(key, fallback); }
            public boolean has(String key) { return translations.containsKey(key); }
            public boolean isDefaultRightToLeft() { return false; }
            public net.minecraft.util.FormattedCharSequence getVisualOrder(net.minecraft.network.chat.FormattedText text) { return previous.getVisualOrder(text); }
        });
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && arguments[0].equals("map-compat")) {
            nativeMapCompatibility();
            System.out.println("TownInput map-compat: " + checks + " checks passed; real Xaero fields, no game launched");
            return;
        }
        if (arguments.length > 0 && arguments[0].equals("form-focus")) {
            projectDetailFocus();
            System.out.println("TownInput form-focus: " + checks + " checks passed; real widgets, no game launched");
            return;
        }
        if (arguments.length > 0 && arguments[0].equals("form-sync")) {
            formFieldSynchronization();
            System.out.println("TownInput form-sync: " + checks + " checks passed; real EditBox, no game launched");
            return;
        }
        formFieldSynchronization();
        projectDetailFocus();
        loadingLifecycle();
        useLanguage("en_us");
        require(TownScreen.state("planned").equals("Planned"), "English status uses the game language");
        require(TownText.text("yat.label.project_count", 3).equals("3 projects"), "Counts support translation parameters");
        require(TownText.recordName(TownGateway.object("name", "玩家内容%中文"), "").equals("玩家内容%中文"), "User content is never translated or formatted");
        require(TownText.serviceError(TownGateway.object("messageKey", "yat.error.text_required", "messageArgs", new com.google.gson.Gson().fromJson("[{\"translate\":\"yat.field.project_name\"}]", JsonArray.class))).contains("Project"), "Service field errors use localized parameters");
        require(ProjectHighlighter.textLines("hello world again", 11, String::length).equals(java.util.List.of("hello world", "again")), "English wrapping preserves words");
        useLanguage("zh_cn");
        require(TownScreen.state("planned").equals("计划中"), "Language changes do not retain cached English labels");
        JsonObject projectChunks = TownGateway.object("minX", 0, "maxX", 0, "minZ", 0, "maxZ", 0);
        for (String status : new String[]{"planned", "active", "completed", "maintenance"}) {
            JsonObject record = TownGateway.object("id", "project", "name", "原名", "status", status);
            JsonObject update = TownScreen.projectFormData(record, projectChunks, "minecraft:overworld", "world",
                    TownGateway.object("name", "新名称", "purpose", "修改简介", "status", status));
            require(!update.has("status"), "Unchanged " + status + " must not trigger 请使用工程状态操作 on beta service");
            require(update.get("name").getAsString().equals("新名称") && update.get("purpose").getAsString().equals("修改简介"), "Ordinary project edits retain name and summary");
            require(update.get("id").getAsString().equals("project") && !update.has("chunks") && !update.has("worldId"), "Ordinary edits preserve project identity without rewriting region");
        }
        JsonObject changedStatus = TownScreen.projectFormData(TownGateway.object("id", "project", "status", "active"), projectChunks, "minecraft:overworld", "world",
                TownGateway.object("name", "工程", "purpose", "同时修改简介", "status", "completed"));
        require(changedStatus.get("status").getAsString().equals("completed") && changedStatus.get("purpose").getAsString().equals("同时修改简介"), "Real status changes remain in the atomic project update");
        for (String status : new String[]{"planned", "active", "completed"}) {
            JsonObject created = TownScreen.projectFormData(TownGateway.object("status", status), projectChunks, "minecraft:overworld", "world",
                    TownGateway.object("name", "新工程", "status", status));
            require(created.get("status").getAsString().equals(status) && created.has("chunks") && created.get("worldId").getAsString().equals("world"), "New project retains initial " + status + " and region");
        }
        MapTileSelection selection = new MapTileSelection(2, 4);
        selection.setEnd(-3, -5);
        var chunks = XaeroBridge.selectionChunks(selection, "minecraft:overworld");
        require(chunks.get("minX").getAsInt() == -3 && chunks.get("maxX").getAsInt() == 2 && chunks.get("minZ").getAsInt() == -5 && chunks.get("maxZ").getAsInt() == 4, "Native reverse drag must preserve inclusive chunk coordinates without block scaling");
        require(chunks.get("dimension").getAsString().equals("minecraft:overworld"), "Native selection must retain its displayed dimension");
        nativeMapCompatibility();
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
        for (String status : new String[]{"planned", "active", "completed", "maintenance"}) {
            var entryLine = TownHud.entryLine("进入项目：超长名称😀".repeat(12), status, 120, pixels);
            require(pixels.applyAsInt(entryLine.getString()) <= 120 && entryLine.getString().endsWith(TownHud.entryStatus(status)) && entryLine.getString().contains("…"),
                    "Long entry names must preserve the full project status within the HUD width");
            var expectedColor = switch (status) {
                case "planned" -> net.minecraft.ChatFormatting.GOLD;
                case "active" -> net.minecraft.ChatFormatting.YELLOW;
                case "completed" -> net.minecraft.ChatFormatting.GREEN;
                default -> net.minecraft.ChatFormatting.RED;
            };
            require(entryLine.getSiblings().getLast().getStyle().getColor().equals(net.minecraft.network.chat.TextColor.fromLegacyFormat(expectedColor)),
                    "Entry status must retain its own color after name truncation");
        }
        require(TownHud.entryLine("短名", "completed", 120, pixels).getString().equals("短名 · 已完工"), "Short entry names must remain complete");
        require(TownHud.entryLine("长名", "maintenance", 24, pixels).getString().equals("待维护"), "Very narrow HUDs must prioritize status without a dangling separator");
        var cardFailures = new java.util.ArrayList<String>();
        String clippedCardTitle = TownStyle.fitText("一个长工程名称😀", 40, pixels);
        if (!clippedCardTitle.endsWith("…")) cardFailures.add("card title silently clipped: " + clippedCardTitle);
        String cardSummary = TownScreen.projectSummary("待维护", "Project Description.", "DogSam", 162, pixels);
        if (!cardSummary.contains("DogSam") || !cardSummary.contains("…")) cardFailures.add("description consumed owner space: " + cardSummary);
        var tooltipField = net.minecraft.client.gui.components.AbstractWidget.class.getDeclaredField("tooltip");
        tooltipField.setAccessible(true);
        var selectionTooltip = (net.minecraft.client.gui.components.WidgetTooltipHolder) tooltipField.get(TownScreen.projectSelectionButton("Town Project 2", 0, 0, 162, 32, () -> {}));
        if (selectionTooltip.get() != null)
            cardFailures.add("transparent selection widget submits a second title-only tooltip");
        require(cardFailures.isEmpty(), String.join("; ", cardFailures));
        require(TownStyle.fitText("a😀b", 24, pixels).equals("a😀b"), "Text that fits must remain unchanged");
        require(TownStyle.fitText("a😀bc", 24, pixels).equals("a😀…"), "Clipping must preserve the entire Unicode character before the ellipsis");
        require(TownStyle.fitText("long", 4, pixels).isEmpty(), "A width smaller than an ellipsis must not draw outside its allocation");
        var sidebarName = TownStyle.nameLines("楹雾小镇工程", 24, pixels);
        require(sidebarName.equals(java.util.List.of("楹雾小", "镇工程")), "Sidebar names must use the available two rows without losing characters");
        var longSidebarName = TownStyle.nameLines("楹雾小镇工程资料室", 24, pixels);
        require(longSidebarName.size() == 2 && longSidebarName.getLast().endsWith("…"), "Names longer than two rows must visibly indicate omitted content");
        for (int cardWidth : new int[]{96, 128, 162, 240}) {
            String summary = TownScreen.projectSummary("待维护", "Project Description.", "DogSam", cardWidth, pixels);
            require(pixels.applyAsInt(summary) <= cardWidth && summary.endsWith("DogSam"), "Project summary must reserve the full owner name before spending space on its description");
        }
        require(TownScreen.projectSummary("", "", "DogSam", 80, pixels).equals("DogSam"), "Empty project descriptions must not add separators");
        require(TownScreen.projectSummary("待维护", "说明", "VeryLongPlayerName", 40, pixels).endsWith("…"), "Even an owner that cannot fit must show explicit truncation");
        require(TownScreen.projectTooltip("Town Project 2", "待维护", "DogSam", "First line\nSecond line").getString()
                .equals("Town Project 2\n待维护\nDogSam\nFirst line\nSecond line"), "The single authoritative tooltip must preserve the complete owner and description paragraphs");
        require(((net.minecraft.client.gui.components.WidgetTooltipHolder) tooltipField.get(TownStyle.button("确认", 0, 0, 48, () -> {}))).get() == null,
                "A fully visible action must not install a redundant label tooltip");
        var shortTooltip = ProjectHighlighter.tooltipLayout("袭击塔", "DogSam", "一个袭击塔", "completed", 640, 360, 9, pixels);
        require(shortTooltip.width() < 248 && shortTooltip.owner().equals(java.util.List.of("DogSam"))
                && shortTooltip.introduction().equals(java.util.List.of("一个袭击塔")), "Short hover cards must fit their content without redundant field labels");
        require(shortTooltip.ownerTop() >= shortTooltip.titleHeight() && shortTooltip.introductionTop() > shortTooltip.ownerTop(), "Hover title, player ID and introduction must occupy separate rows");
        var longTooltip = ProjectHighlighter.tooltipLayout("长工程名称".repeat(20), "PlayerName1234567", "长简介😀".repeat(200), "planned", 160, 140, 9, pixels);
        require(longTooltip.width() <= 128 && longTooltip.height() <= 108, "Wrapped hover card must fit the minimum supported viewport");
        require(longTooltip.introduction().getLast().endsWith("…"), "Truncated introductions must indicate hidden content");
        require(longTooltip.introduction().size() <= 3 && longTooltip.folded(), "Long descriptions must stay compact and offer full details");
        require(!shortTooltip.folded(), "Short descriptions must not show a redundant expansion hint");
        var paragraphs = ProjectHighlighter.tooltipLayout("Test", "DogSam", "one\ntwo\nthree\nfour", "completed", 640, 360, 9, pixels);
        require(paragraphs.introduction().equals(java.util.List.of("one", "two", "three…")), "Collapsed descriptions must preserve paragraph breaks");
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
        require(TownScreen.singleLine("第一行\r\n第二行\t第三行").equals("第一行 第二行 第三行"), "Project list must never render newline or tab control characters");
        require(TownScreen.joinedTime("2026-10-20T23:04:59.999Z", java.time.ZoneId.of("Asia/Shanghai")).equals("2026-10-21 07:04"), "Joined time must use the local date and minute precision across midnight");
        require(TownScreen.joinedTime("invalid", java.time.ZoneOffset.UTC).equals("—") && TownScreen.joinedTime("", java.time.ZoneOffset.UTC).equals("—"), "Missing joined dates must not render raw data or break member rows");
        var machine = TownGateway.object("type", "machine", "status", "maintenance", "createdAt", "2026-01-01T00:00:00Z");
        var building = TownGateway.object("type", "building", "status", "planned", "createdAt", "2026-02-01T00:00:00Z");
        require(TownScreen.projectColor(machine) == TownStyle.DANGER && TownScreen.projectColor(building) == TownStyle.ACCENT, "Maintenance and planned titles must use distinct status colors");
        var originalOrder = java.util.List.of(building, machine);
        require(TownScreen.recentProjects(originalOrder).equals(java.util.List.of(machine, building)) && originalOrder.getFirst() == building, "Home preview must prioritize maintenance without mutating the project list");
        var facility = TownGateway.object("id", "facility", "pinned", true, "status", "completed", "createdAt", "2025-01-01T00:00:00Z", "dimension", "minecraft:the_nether");
        var secondFacility = TownGateway.object("id", "second", "pinned", true, "status", "maintenance", "createdAt", "2024-01-01T00:00:00Z");
        require(TownScreen.recentProjects(java.util.List.of(building, machine, facility, secondFacility)).equals(java.util.List.of(secondFacility, facility, machine, building)), "Home must keep cross-dimension pinned facilities before maintenance and recency fill-ins");
        facility.addProperty("pinned", false);
        require(TownScreen.recentProjects(java.util.List.of(facility, machine, building)).equals(java.util.List.of(machine, building, facility)), "Unpinning restores ordinary maintenance and recency order");
        var invitedTown = TownGateway.object("name", "Example Town", "id", "12345678", "server", "play.example.test", "password", "secret-password", "token", "secret-token", "ownerId", "secret-uuid");
        String invitation = TownScreen.invitationText(invitedTown, "https://town.example.test");
        require(invitation.contains("Example Town") && invitation.contains("12345678") && invitation.contains("play.example.test") && invitation.contains("https://town.example.test"), "Invitation carries the town and both service addresses");
        require(!invitation.contains("secret-password") && !invitation.contains("secret-token") && !invitation.contains("secret-uuid"), "Invitation must only copy explicitly selected public fields");
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
        projectKeyTests(mappings[1]);
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
        require(TownHud.entryStatus("planned").equals("计划中"), "Planned entry must retain its own state");
        require(TownHud.entryStatus("active").equals("未完工"), "Active entry must show unfinished status");
        require(TownHud.entryStatus("maintenance").equals("待维护"), "Maintenance entry must show repair status");
        require(TownHud.entryStatus("completed").equals("已完工"), "Completed entry must show completed status without changing list badges");
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
        var configuration = new TownGateway.ClientConfig();
        require(configuration.projectKeyHoldMillis == 600, "Project key holds default to 600 milliseconds");
        for (int duration : new int[]{200, 3000}) {
            configuration.projectKeyHoldMillis = duration;
            configuration.validate();
        }
        for (int duration : new int[]{0, 199, 3001}) {
            configuration.projectKeyHoldMillis = duration;
            boolean rejected = false;
            try { configuration.validate(); } catch (IllegalArgumentException exception) { rejected = true; }
            require(rejected, "Project key hold configuration rejects out-of-range duration: " + duration);
        }
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
        var enabledBaseline = java.util.Map.of("passwordEnabled", "true", "listed", "false");
        var disabledBaseline = java.util.Map.of("passwordEnabled", "false", "listed", "false");
        require(TownScreen.changedTownSettings(java.util.Map.of("password", "", "passwordEnabled", "true", "listed", "false"), enabledBaseline, snapshot, "12345678").isEmpty(), "Keeping password protection enabled with a blank password preserves the current password");
        require(TownScreen.changedTownSettings(java.util.Map.of("password", "draft123", "passwordEnabled", "false", "listed", "true"), enabledBaseline, snapshot, "12345678").equals(TownGateway.object("password", "", "listed", true)), "Saving disabled password protection and listing submits both changes together");
        require(TownScreen.changedTownSettings(java.util.Map.of("password", "secret123", "passwordEnabled", "true"), disabledBaseline, snapshot, "12345678").equals(TownGateway.object("password", "secret123")), "Enabling password protection submits the entered password");
        require(TownScreen.changedTownSettings(java.util.Map.of("password", "", "passwordEnabled", "true"), disabledBaseline, snapshot, "12345678").has("password"), "Enabling protection without a password must reach local password validation");
        require(TownScreen.changedTownSettings(java.util.Map.of("password", "draft123", "passwordEnabled", "false", "listed", "false"), disabledBaseline, snapshot, "12345678").isEmpty(), "Disabled password drafts and reverted listing toggles must not change the town");
    }

    private static void projectKeyTests(KeyMapping mapping) {
        JsonObject target = TownGateway.object("id", "station");
        var press = new VersionClient.ProjectKeyPress();
        require(press.update(0, 600, true, true, true, target) == VersionClient.ProjectKeyPress.NONE, "Pressing the project key must wait for release");
        require(press.update(100, 600, true, false, false, target) == VersionClient.ProjectKeyPress.DETAILS, "A short press opens details on release");
        require(press.update(200, 600, true, false, true, target) == VersionClient.ProjectKeyPress.DETAILS, "A complete tap between ticks must still open details");
        press.update(300, 600, true, true, true, target);
        require(press.update(899, 600, true, true, true, target) == VersionClient.ProjectKeyPress.NONE, "Keyboard repeat clicks must not turn a hold into a tap");
        require(press.update(900, 600, true, true, true, target) == VersionClient.ProjectKeyPress.STOP, "Crossing the configured hold threshold stops navigation");
        require(press.update(1500, 600, true, true, true, null) == VersionClient.ProjectKeyPress.NONE
                && press.update(1600, 600, true, false, true, null) == VersionClient.ProjectKeyPress.NONE, "A hold stops only once and release cannot open details");
        press.update(1700, 600, true, true, true, target);
        press.update(1800, 600, false, true, true, target);
        require(press.update(2500, 600, true, true, true, target) == VersionClient.ProjectKeyPress.NONE
                && press.update(2600, 600, true, false, false, target) == VersionClient.ProjectKeyPress.NONE, "Closing a menu or restoring focus while held must not trigger an action");
        press.update(2700, 600, true, true, true, target);
        require(press.update(3400, 600, true, true, true, target.deepCopy()) == VersionClient.ProjectKeyPress.NONE
                && press.update(3500, 600, true, false, false, target) == VersionClient.ProjectKeyPress.NONE, "Replacing the navigation target cancels an in-flight key press");
        press.update(3600, 600, true, true, true, target);
        press.reset();
        require(press.update(4300, 600, true, true, true, null) == VersionClient.ProjectKeyPress.NONE
                && press.update(4400, 600, true, false, false, null) == VersionClient.ProjectKeyPress.NONE, "Navigation cancellation or context reset discards the pending press");
        press.update(4500, 600, true, true, true, null);
        require(press.update(5200, 600, true, true, true, null) == VersionClient.ProjectKeyPress.NONE
                && press.update(5300, 600, true, false, false, null) == VersionClient.ProjectKeyPress.DETAILS, "Without navigation, holding opens details only once on release");
        for (String binding : new String[]{"key.keyboard.g", "key.mouse.4"}) {
            var key = InputConstants.getKey(binding);
            mapping.setKey(key);
            KeyMapping.resetMapping();
            KeyMapping.set(InputConstants.getKey("key.keyboard.x"), true);
            require(!mapping.isDown(), "Rebinding the project action releases physical X: " + binding);
            KeyMapping.set(key, true);
            KeyMapping.click(key);
            require(press.update(5400, 600, true, mapping.isDown(), mapping.consumeClick(), target) == VersionClient.ProjectKeyPress.NONE, "The rebound keyboard or mouse key starts the same project action: " + binding);
            KeyMapping.set(key, false);
            require(press.update(5500, 600, true, mapping.isDown(), mapping.consumeClick(), target) == VersionClient.ProjectKeyPress.DETAILS, "The rebound key release opens project details: " + binding);
        }
        mapping.setKey(InputConstants.getKey("key.keyboard.x"));
        KeyMapping.resetMapping();
    }

    /** 导航使用真实状态更新与清理路径，抵达区域边界就停止，不要求走到中心点。 */
    private static void navigationTests() throws Exception {
        TownGateway gateway = TownGateway.get();
        JsonObject target = TownGateway.object("id", "station", "name", "车站", "worldId", gateway.worldId,
                "dimension", "minecraft:overworld", "chunks", TownGateway.object("minX", -2, "maxX", 2, "minZ", -3, "maxZ", 3));
        try {
            seedNavigation(target);
            JsonObject fresh = target.deepCopy();
            fresh.addProperty("name", "更新后的车站");
            JsonArray currentProjects = new JsonArray(); currentProjects.add(fresh);
            var snapshot = TownGateway.object("projects", currentProjects);
            require(TownHud.projectForDetails(snapshot, target, null) == fresh, "Navigation details use the latest snapshot even when standing outside all projects");
            require(TownHud.projectForDetails(new JsonObject(), target, fresh) == null, "A deleted navigation target must not reopen stale details");
            require(TownHud.projectForDetails(snapshot, null, fresh) == fresh
                    && TownHud.projectForDetails(snapshot, TownGateway.object("id", "ticket"), fresh) == fresh, "Nearby details remain available without project navigation and for legacy repair points");
            require(TownScreen.isNavigationTarget(target, target.deepCopy())
                    && !TownScreen.isNavigationTarget(TownGateway.object("id", "other", "worldId", gateway.worldId), target)
                    && !TownScreen.isNavigationTarget(TownGateway.object("id", "station", "worldId", "history"), target), "Menu cancellation matches only the same project and world");
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
            VersionClient.updateNavigation(true, "minecraft:overworld", -100, 0);
            require(VersionClient.navigation() != null && waypoints.size() == 2, "A retained native destination must keep navigation active");
            own.setDisabled(true);
            VersionClient.updateNavigation(true, "minecraft:overworld", -100, 0);
            require(VersionClient.navigation() != null, "Hiding a destination icon must not count as deleting its waypoint");
            waypoints.remove(other);
            VersionClient.updateNavigation(true, "minecraft:overworld", -100, 0);
            require(VersionClient.navigation() != null && waypoints.size() == 1 && waypoints.get(0) == own, "Deleting someone else's waypoint must not cancel our navigation");
            waypoints.add(other);
            waypoints.remove(own);
            VersionClient.updateNavigation(true, "minecraft:overworld", -100, 0);
            require(VersionClient.navigation() == null && waypoints.size() == 1 && waypoints.get(0) == other,
                    "Deleting our native waypoint must clear navigation without removing another waypoint or recreating ours");
            waypoints.add(own); seedNavigation(target);
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

    /** 用真实原生字段验证隐藏界面与项目相机定位的新旧兼容。 */
    private static void nativeMapCompatibility() throws Exception {
        java.lang.reflect.Field hidden = null, attached = null;
        try { hidden = xaero.map.gui.GuiMap.class.getDeclaredField("hiddenUI"); hidden.setAccessible(true); }
        catch (NoSuchFieldException exception) { }
        try { attached = xaero.map.gui.GuiMap.class.getDeclaredField("attachedCamera"); attached.setAccessible(true); }
        catch (NoSuchFieldException exception) { }
        boolean originalHidden = hidden != null && hidden.getBoolean(null);
        boolean originalAttached = attached != null && attached.getBoolean(null);
        try {
            if (hidden != null) {
                hidden.setBoolean(null, true);
                require(XaeroBridge.mapUiHidden(), "Modern map overlays follow the native hidden UI state");
                hidden.setBoolean(null, false);
            }
            require(!XaeroBridge.mapUiHidden(), "Visible native controls retain overlays, including maps before hidden UI was introduced");
            var map = allocateNative(xaero.map.gui.GuiMap.class);
            nativeField(map, xaero.map.gui.GuiMap.class, "shouldResetCameraPos", true);
            nativeField(map, xaero.map.gui.GuiMap.class, "prevPlayerDimDiv", 8d);
            nativeField(map, xaero.map.gui.GuiMap.class, "cameraDestination", new int[]{100, 100});
            if (attached != null) attached.setBoolean(null, true);
            var center = XaeroBridge.class.getDeclaredMethod("centerMap", xaero.map.gui.GuiMap.class, JsonObject.class);
            center.setAccessible(true);
            center.invoke(null, map, TownGateway.object("minX", -2, "maxX", 0, "minZ", 1, "maxZ", 2));
            require((double) nativeField(map, xaero.map.gui.GuiMap.class, "cameraX") == -8d
                    && (double) nativeField(map, xaero.map.gui.GuiMap.class, "cameraZ") == 32d, "Old and modern native cameras center on the inclusive project region");
            require(!(boolean) nativeField(map, xaero.map.gui.GuiMap.class, "shouldResetCameraPos")
                    && (double) nativeField(map, xaero.map.gui.GuiMap.class, "prevPlayerDimDiv") == 0d, "Map focusing prevents native player reset and stale dimension conversion");
            require(nativeField(map, xaero.map.gui.GuiMap.class, "cameraDestination") == null
                    && nativeField(map, xaero.map.gui.GuiMap.class, "cameraDestinationAnimX") == null
                    && nativeField(map, xaero.map.gui.GuiMap.class, "cameraDestinationAnimZ") == null, "Map focusing clears native destination animations");
            require(attached == null || !attached.getBoolean(null), "Focusing detaches modern follow mode while preserving the older free camera mechanism");
        } finally {
            if (hidden != null) hidden.setBoolean(null, originalHidden);
            if (attached != null) attached.setBoolean(null, originalAttached);
        }
    }

    /** 页面打开不默认聚焦，主动导航与输入期间刷新保留焦点。 */
    private static void projectDetailFocus() throws Exception {
        var previousClient = nativeField(null, net.minecraft.client.Minecraft.class, "instance");
        try {
            var font = (net.minecraft.client.gui.Font) allocateNative(net.minecraft.client.gui.Font.class);
            nativeField(font, net.minecraft.client.gui.Font.class, "splitter", new net.minecraft.client.StringSplitter((character, style) -> 1));
            var client = allocateNative(net.minecraft.client.Minecraft.class);
            nativeField(client, net.minecraft.client.Minecraft.class, "font", font);
            nativeField(client, net.minecraft.client.Minecraft.class, "lastInputType", net.minecraft.client.InputType.KEYBOARD_TAB);
            nativeField(null, net.minecraft.client.Minecraft.class, "instance", client);
            var detail = new TownForm(null, "Project", java.util.List.of(), java.util.List.of(),
                    java.util.List.of(new TownForm.Action("Navigate", () -> {})), null)
                    .withProjectTitle(TownGateway.object("name", "Project", "status", "completed"), "Owner");
            nativeField(detail, net.minecraft.client.gui.screens.Screen.class, "width", 640);
            nativeField(detail, net.minecraft.client.gui.screens.Screen.class, "height", 360);
            detail.init();
            require(detail.getFocused() == null, "Opening project details must not focus its first action");
            var initialFocus = net.minecraft.client.gui.screens.Screen.class.getDeclaredMethod("setInitialFocus");
            initialFocus.setAccessible(true); initialFocus.invoke(detail);
            require(detail.getFocused() == null, "Screen initial keyboard focus must not reselect the first project action");
            detail.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_TAB, 0, 0));
            var first = detail.getFocused();
            require(first != null && first.isFocused(), "Tab must focus the first detail action");
            detail.clearFocus();
            detail.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_TAB, 0, 1));
            require(detail.getFocused() != null && detail.getFocused() != first, "Shift Tab must focus the last detail action");
            detail.init(); initialFocus.invoke(detail);
            require(detail.getFocused() == null, "Returning to a page must not restore an automatic focus");
            var form = new TownForm(null, "Settings", java.util.List.of(
                    TownForm.Field.number("entrySubtitleSeconds", "Duration", 3),
                    TownForm.Field.number("entryCooldownSeconds", "Cooldown", 8),
                    TownForm.Field.number("pollIntervalSeconds", "Refresh", 10)), ignored -> {});
            nativeField(form, net.minecraft.client.gui.screens.Screen.class, "width", 640);
            nativeField(form, net.minecraft.client.gui.screens.Screen.class, "height", 360);
            form.init(); initialFocus.invoke(form);
            require(form.getFocused() == null, "Editable forms also open without default focus");
            require(((java.util.Map<?, ?>) nativeField(form, TownForm.class, "fieldWidgets")).size() == 3,
                    "All numeric settings are built directly without an advanced toggle");
            form.init(); initialFocus.invoke(form);
            require(form.getFocused() == null, "Reopening an editable form clears its old focus");
        } finally {
            nativeField(null, net.minecraft.client.Minecraft.class, "instance", previousClient);
        }
    }

    /** 真实输入控件复用时，目录选镇和公共地址更新须同时进入可见内容。 */
    @SuppressWarnings("unchecked")
    private static void formFieldSynchronization() throws Exception {
        var previousClient = nativeField(null, net.minecraft.client.Minecraft.class, "instance");
        try {
            var font = (net.minecraft.client.gui.Font) allocateNative(net.minecraft.client.gui.Font.class);
            nativeField(font, net.minecraft.client.gui.Font.class, "splitter", new net.minecraft.client.StringSplitter((character, style) -> 1));
            var client = allocateNative(net.minecraft.client.Minecraft.class);
            nativeField(client, net.minecraft.client.Minecraft.class, "font", font);
            nativeField(null, net.minecraft.client.Minecraft.class, "instance", client);
            var fields = java.util.List.of(TownForm.Field.text("townId", "Town", "A"),
                    TownForm.Field.text("password", "Password", "old"), TownForm.Field.text("endpoint", "Endpoint", "local"));
            var form = new TownForm(null, "Join", fields, ignored -> {});
            var addField = TownForm.class.getDeclaredMethod("addField", TownForm.Field.class, int.class, int.class, int.class);
            addField.setAccessible(true);
            var values = (java.util.Map<String, String>) nativeField(form, TownForm.class, "values");
            var widgets = (java.util.Map<String, net.minecraft.client.gui.components.AbstractWidget>) nativeField(form, TownForm.class, "fieldWidgets");
            var serviceNotice = TownForm.class.getDeclaredMethod("currentServiceNoticeKey");
            serviceNotice.setAccessible(true);
            form.withServiceNotice();
            values.put("endpoint", TownGateway.ClientConfig.DEFAULT_ENDPOINT);
            require(serviceNotice.invoke(form).equals("yat.hint.public_service"), "Default endpoint shows public service information");
            values.put("endpoint", TownGateway.ClientConfig.LOCAL_ENDPOINT);
            require(serviceNotice.invoke(form).equals("yat.hint.local_service"), "Local endpoint keeps local service information");
            values.put("endpoint", "https://private.example.test");
            require(serviceNotice.invoke(form).equals(""), "Custom endpoint does not show public or local information");
            for (var field : fields) addField.invoke(form, field, 0, 0, 200);
            values.put("townId", "B"); values.put("password", "");
            values.put("endpoint", TownGateway.ClientConfig.PUBLIC_ENDPOINT);
            for (var field : fields) {
                var input = (net.minecraft.client.gui.components.EditBox) widgets.get(field.key());
                addField.invoke(form, field, 0, 0, 200);
                require(widgets.get(field.key()) == input, "Programmatic update preserves the existing input: " + field.key());
                require(input.getValue().equals(values.get(field.key())), "Visible input matches submitted value: " + field.key());
                input.setCursorPosition(0);
                addField.invoke(form, field, 0, 0, 200);
                require(input.getCursorPosition() == 0, "Unchanged value preserves the cursor: " + field.key());
                input.setValue("typed");
                require(values.get(field.key()).equals("typed"), "Reused input still updates submitted value: " + field.key());
            }
            var protectedTown = TownGateway.object("id", "12345678", "name", "Protected", "passwordEnabled", true);
            var openTown = TownGateway.object("id", "87654321", "name", "Open", "passwordEnabled", false);
            form.withTownDirectory();
            nativeField(form, TownForm.class, "towns", java.util.List.of(protectedTown, openTown));
            form.selectDirectoryTown(protectedTown);
            require(form.directoryTowns().isEmpty() && nativeField(form, TownForm.class, "selectedTown") == protectedTown,
                    "Password entry retains the chosen summary and folds other directory cards");
            require(values.get("townId").equals("12345678") && values.get("password").isEmpty()
                    && (boolean) nativeField(form, TownForm.class, "passwordRequired"), "Selecting a protected town starts with its ID and a fresh password");
            values.put("password", "previous secret");
            form.clearDirectorySelection();
            require(form.directoryTowns().equals(java.util.List.of(protectedTown, openTown)) && values.get("townId").isEmpty()
                    && values.get("password").isEmpty() && nativeField(form, TownForm.class, "selectedTown") == null,
                    "Returning to the directory restores its cards and removes the prior town credentials");
            form.selectDirectoryTown(openTown);
            require(!(boolean) nativeField(form, TownForm.class, "passwordRequired") && values.get("townId").equals("87654321"),
                    "An open town still takes the direct join path without a password step");
            form.selectDirectoryTown(null);
            require(form.directoryTowns().isEmpty() && nativeField(form, TownForm.class, "selectedTown") == null
                    && values.get("townId").isEmpty(), "Manual ID entry folds the directory and starts without a selected town");
            values.put("password", "kept on failure");
            Object passwordWidget = widgets.get("password");
            nativeField(form, TownForm.class, "loading", null);
            form.withLoading(true);
            require(form.mouseClicked(null, false) && form.mouseReleased(null) && form.mouseDragged(null, 1, 1)
                    && form.mouseScrolled(0, 0, 0, 1), "A newly busy form blocks mouse input before the overlay delay");
            require(form.keyPressed(null) && form.keyReleased(null) && form.charTyped(null) && form.preeditUpdated(null),
                    "Busy forms block keys, text and input method edits");
            form.onClose();
            form.withLoading(false);
            require(values.get("password").equals("kept on failure") && widgets.get("password") == passwordWidget,
                    "Ending a request keeps the same input widget and its entered value");
            var join = new TownForm(null, "Join", java.util.List.of(fields.get(0).compact(), fields.get(1).compact()), ignored -> {})
                    .withTownDirectory().withLoading(true);
            nativeField(join, net.minecraft.client.gui.screens.Screen.class, "width", 640);
            nativeField(join, net.minecraft.client.gui.screens.Screen.class, "height", 360);
            var rebuild = TownForm.class.getDeclaredMethod("rebuild"); rebuild.setAccessible(true);
            rebuild.invoke(join);
            var waitingBounds = (TownStyle.Window) nativeField(join, TownForm.class, "window");
            join.withLoading(false).showTowns(java.util.List.of(protectedTown, openTown));
            require(waitingBounds.equals(nativeField(join, TownForm.class, "window")),
                    "The join directory keeps its window bounds when the first request completes");
            join.showTowns(java.util.List.of());
            require(waitingBounds.equals(nativeField(join, TownForm.class, "window")),
                    "An empty directory result keeps the same window as the loading shell");
            join.showTowns(java.util.List.of(protectedTown, openTown));
            join.selectDirectoryTown(protectedTown); rebuild.invoke(join);
            require(((TownStyle.Window) nativeField(join, TownForm.class, "window")).height() < waitingBounds.height(),
                    "A selected town may compact around its summary and password field");
            join.selectDirectoryTown(null); rebuild.invoke(join);
            require(((TownStyle.Window) nativeField(join, TownForm.class, "window")).height() < waitingBounds.height(),
                    "Manual ID entry may compact after folding the directory");
            var screen = new TownScreen();
            nativeField(screen, TownScreen.class, "mutationPending", true);
            var screenLoading = TownScreen.class.getDeclaredMethod("loading"); screenLoading.setAccessible(true);
            long before = System.nanoTime();
            var screenState = (TownStyle.LoadingState) screenLoading.invoke(screen);
            long after = System.nanoTime();
            require(screenState.blocking() && screenState.startedAt() >= before && screenState.startedAt() <= after,
                    "TownScreen starts the shared loading clock with monotonic nanoseconds");
            nativeField(screenState, TownStyle.LoadingState.class, "startedAt", System.nanoTime() - 200_000_000L);
            require(((TownStyle.LoadingState) screenLoading.invoke(screen)).visible(),
                    "TownScreen shows an ongoing request after the shared delay without mixing milliseconds and nanoseconds");
        } finally {
            nativeField(null, net.minecraft.client.Minecraft.class, "instance", previousClient);
        }
    }

    private static void loadingLifecycle() {
        var loading = new TownStyle.LoadingState();
        loading.update(false, 0);
        require(!loading.blocking() && !loading.visible(), "Idle screens have no loading layer or input block");
        loading.update(true, 10_000_000L);
        require(loading.blocking() && !loading.visible(), "Requests block immediately while the short display delay is pending");
        loading.update(true, 159_999_999L);
        require(!loading.visible() && loading.startedAt() == 10_000_000L, "Repeated busy frames keep the original request start and avoid a short flash");
        loading.update(true, 160_000_000L);
        require(loading.visible(), "A request lasting 150 milliseconds displays its loading layer");
        loading.update(false, 161_000_000L);
        require(!loading.blocking() && !loading.visible(), "Success and failure both release loading immediately");
        loading.update(true, 200_000_000L);
        require(loading.startedAt() == 200_000_000L && !loading.visible(), "The next request receives a fresh delay instead of inheriting an old overlay");
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
