package dev.yetanothertown.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static dev.yetanothertown.client.TownForm.Field.*;
import static dev.yetanothertown.client.TownGateway.object;

/** 小镇三入口与所有协作、审核、管理操作。 */
public final class TownScreen extends Screen {
    static final int TEXT = TownStyle.TEXT;
    static final int MUTED = TownStyle.MUTED;
    static final int ACCENT = TownStyle.ACCENT;
    private static final String[] PLATFORM = {"工程", "留言"};
    private static final String[] MANAGEMENT = {"成员审核", "权限组", "世界与周目", "小镇设置"};
    private static final String[] COLLECTIONS = {"projects", "messages"};
    private final TownGateway gateway = TownGateway.get();
    private final Screen parent;
    private final long openedAt = System.nanoTime();
    private final List<Line> lines = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private final List<Panel> panels = new ArrayList<>();
    private final List<Button> homeButtons = new ArrayList<>();
    private int avatarX, avatarY, logoX, logoY;
    private boolean showLogo;
    private int tab;
    private int section;
    private int page;
    private JsonObject previousSnapshot;
    private JsonArray previousTowns;
    private int contentLeft;
    private int contentWidth;
    private int contentTop;
    private int contentBottom;
    private int bodyTop;
    private int bodyBottom;
    private TownStyle.Window window;
    private int homeScroll;
    private int maximumHomeScroll;
    private boolean homeContent;
    private record Panel(int x, int y, int width, int height, boolean accented) { }
    private record Line(String text, int x, int y, int color) { }
    private record Card(int x, int y, int width, String title, String subtitle, String state, boolean repair) { }

    public TownScreen() { this(null); }
    public TownScreen(Screen parent) { super(Component.literal("YetAnotherTown · 小镇协作")); this.parent = parent; }

    @Override protected void init() { rebuild(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { VersionClient.show(parent); }
    @Override public void tick() {
        if (previousSnapshot != gateway.snapshot || previousTowns != gateway.towns) rebuild();
    }

    private void rebuild() {
        Button focused = getFocused() instanceof Button button ? button : null;
        clearFocus();
        clearWidgets(); lines.clear(); cards.clear(); panels.clear(); homeButtons.clear();
        previousSnapshot = gateway.snapshot; previousTowns = gateway.towns;
        window = TownStyle.bounds(width, height, 560, 346);
        contentLeft = window.left() + 12;
        contentWidth = window.width() - 24;
        bodyTop = window.top() + 48;
        bodyBottom = window.bottom() - 34;
        contentBottom = bodyBottom - 6;
        int navigationWidth = Math.min(86, (contentWidth - 8) / 3);
        String[] tabs = {"主页", "平台", "管理"};
        for (int index = 0; index < tabs.length; index++) {
            final int selected = index;
            Button navigation = addRenderableWidget(TownStyle.tab(tabs[index], contentLeft + index * (navigationWidth + 4), window.top() + 25, navigationWidth,
                    () -> { tab = selected; section = 0; page = 0; rebuild(); }, tab == index));
            navigation.active = index == 0 || gateway.loggedIn() && !gateway.townId.isEmpty();
        }
        button("×", window.right() - 28, window.top() + 5, 20, this::onClose);
        contentTop = bodyTop + 6;
        if (tab == 0) {
            homeContent = true; home(); homeContent = false;
            if (homeScroll > maximumHomeScroll) { homeScroll = maximumHomeScroll; rebuild(); return; }
        }
        else {
            String[] sections = tab == 1 ? PLATFORM : MANAGEMENT;
            int sectionWidth = (contentWidth - (sections.length - 1) * 4) / sections.length;
            if (contentWidth < 420 && tab != 1) {
                button("‹", contentLeft, contentTop, 22, () -> { section = (section + sections.length - 1) % sections.length; page = 0; rebuild(); });
                String sectionTitle = sections[section] + "  " + (section + 1) + "/" + sections.length;
                label(sectionTitle, contentLeft + (contentWidth - font.width(sectionTitle)) / 2, contentTop + 6, ACCENT);
                button("›", contentLeft + contentWidth - 22, contentTop, 22, () -> { section = (section + 1) % sections.length; page = 0; rebuild(); });
            } else for (int index = 0; index < sections.length; index++) {
                final int selected = index;
                int offset = index * (sectionWidth + 4);
                addRenderableWidget(TownStyle.tab(sections[index], contentLeft + offset, contentTop, index == sections.length - 1 ? contentWidth - offset : sectionWidth,
                        () -> { section = selected; page = 0; rebuild(); }, section == index));
            }
            contentTop += 26;
            if (tab == 1) platform(); else management();
        }
        button("刷新资料", window.right() - 76, window.bottom() - 26, 64, () -> { gateway.refreshTowns(); gateway.refresh(); });
        restoreFocus(focused);
    }

    private void restoreFocus(Button previous) {
        if (previous == null) return;
        for (var child : children()) if (child instanceof Button button && button.active && button.visible && button.getX() == previous.getX() && button.getY() == previous.getY() && button.getMessage().getString().equals(previous.getMessage().getString())) { setInitialFocus(button); break; }
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        boolean handled = super.mouseClicked(event, doubleClick);
        // 原版会在回调结束后重新聚焦被点击按钮；重建页面后改用新控件。
        if (getFocused() != null && !children().contains(getFocused())) {
            Button previous = getFocused() instanceof Button button ? button : null;
            clearFocus();
            restoreFocus(previous);
        }
        return handled;
    }

    private void home() {
        int x = contentLeft, y = contentTop - homeScroll;
        boolean wide = contentWidth >= 460;
        int sideWidth = wide ? 156 : contentWidth;
        int mainWidth = wide ? contentWidth - sideWidth - 10 : contentWidth;
        int profileX = wide ? x + mainWidth + 10 : x;
        int profileY = y;
        int profileHeight = wide ? 103 : 66;
        panels.add(new Panel(profileX, profileY, sideWidth, profileHeight, false));
        avatarX = profileX + 10; avatarY = profileY + 12;
        int nameX = profileX + 47;
        int nameWidth = wide ? sideWidth - 57 : sideWidth - 147;
        label(font.plainSubstrByWidth(VersionClient.playerName(), nameWidth), nameX, profileY + 13, TEXT);
        label(font.plainSubstrByWidth(gateway.loggedIn() ? "身份已连接" : "等待连接", nameWidth), nameX, profileY + 28, gateway.loggedIn() ? TownStyle.SUCCESS : MUTED);
        JsonObject town = objectValue(gateway.snapshot, "town");
        String townName = stringValue(town, "name", gateway.townId.isEmpty() ? "尚未加入小镇" : "正在载入小镇…");
        label(font.plainSubstrByWidth("所在镇 · " + townName, sideWidth - 20), profileX + 10, profileY + 49, MUTED);
        button("客户端设置", wide ? profileX + 10 : profileX + sideWidth - 90, wide ? profileY + 73 : profileY + 18, wide ? sideWidth - 20 : 80, this::connectionForm);
        int heroY = wide ? y : y + profileHeight + 10;
        int heroHeight = 153;
        panels.add(new Panel(x, heroY, mainWidth, heroHeight, true));
        showLogo = !gateway.townId.isEmpty();
        logoX = x + 14; logoY = heroY + 14;
        int titleX = x + (showLogo ? 55 : 12);
        label("小镇档案", titleX, heroY + 13, ACCENT);
        label(font.plainSubstrByWidth(gateway.townId.isEmpty() ? "从这里，开始建设" : townName, mainWidth - (titleX - x) - 12), titleX, heroY + 29, TEXT);
        if (!gateway.loggedIn()) {
            paragraph("使用当前游戏 UUID 自动连接。进入世界后，即可与伙伴规划小镇、登记工程。", x + 12, heroY + 56, mainWidth - 24, MUTED);
            button("重新连接", x + 12, heroY + 123, Math.min(132, mainWidth - 24), () -> VersionClient.connectIdentity(this::rebuild));
        } else if (gateway.townId.isEmpty()) {
            paragraph("注册自己的小镇，或输入八位小镇 ID，加入伙伴的建设计划。", x + 12, heroY + 56, mainWidth - 24, MUTED);
            int actionWidth = (mainWidth - 30) / 2;
            button("创建小镇", x + 12, heroY + 123, actionWidth, this::createTown);
            button("通过 ID 加入", x + 18 + actionWidth, heroY + 123, actionWidth, this::joinTown);
        } else {
            label(font.plainSubstrByWidth("ID " + gateway.townId + "  ·  " + array("members").size() + " 位成员", mainWidth - 24), x + 12, heroY + 55, MUTED);
            List<String> introduction = wrap(stringValue(town, "introduction", "一起规划，一起建设。"), mainWidth - 24);
            for (int index = 0; index < Math.min(2, introduction.size()); index++) label(introduction.get(index), x + 12, heroY + 73 + index * 12, MUTED);
            int actionWidth = (mainWidth - 30) / 2;
            button("进入协作平台", x + 12, heroY + 123, actionWidth, () -> { tab = 1; section = 0; page = 0; rebuild(); });
            button("切换小镇", x + 18 + actionWidth, heroY + 123, actionWidth, this::chooseTown);
        }
        label(font.plainSubstrByWidth("服务器 · " + stringValue(town, "server", VersionClient.serverAddress()), mainWidth - 24), x + 12, heroY + 104, MUTED);
        int informationX = wide ? profileX : x;
        int informationTop = wide ? profileY + profileHeight + 10 : heroY + heroHeight + 10;
        int informationWidth = wide ? sideWidth : mainWidth;
        panels.add(new Panel(informationX, informationTop, informationWidth, 96, false));
        label("世界与周目", informationX + 10, informationTop + 12, ACCENT);
        label(font.plainSubstrByWidth(worldName(gateway.worldId), informationWidth - 20), informationX + 10, informationTop + 29, TEXT);
        label(font.plainSubstrByWidth(roleName(stringValue(objectValue(gateway.snapshot, "currentMember"), "roleId", "尚未加入小镇")), informationWidth - 20), informationX + 10, informationTop + 44, MUTED);
        Button world = button("周目与历史地图", informationX + 10, informationTop + 65, informationWidth - 20, this::chooseWorld);
        world.active = !gateway.townId.isEmpty();
        int linksY = wide ? heroY + heroHeight + 12 : informationTop + 108;
        label("小镇事务", x + 2, linksY, ACCENT);
        int openTickets = 0;
        for (JsonElement element : array("tickets")) if (!stringValue(element.getAsJsonObject(), "status").equals("resolved")) openTickets++;
        String overview = gateway.townId.isEmpty() ? "让每一份建设，都有伙伴同行。" : array("projects").size() + " 工程 · " + array("messages").size() + " 留言 · " + openTickets + " 待修";
        label(font.plainSubstrByWidth(overview, mainWidth - 4), x + 2, linksY + 16, MUTED);
        int linkWidth = (mainWidth - 8) / 3;
        Button create = button("注册小镇", x, linksY + 34, linkWidth, this::createTown);
        Button join = button("加入小镇", x + linkWidth + 4, linksY + 34, linkWidth, this::joinTown);
        Button manage = button("成员与权限", x + (linkWidth + 4) * 2, linksY + 34, linkWidth, () -> { tab = 2; section = 0; rebuild(); });
        create.active = gateway.loggedIn(); join.active = gateway.loggedIn(); manage.active = !gateway.townId.isEmpty();
        int bottom = Math.max(linksY + 60, informationTop + 102);
        maximumHomeScroll = Math.max(0, bottom + homeScroll - contentBottom);
    }

    private void platform() {
        int actionWidth = (contentWidth - 8) / 2;
        int y = contentTop;
        if (section == 0) button("打开工程地图", contentLeft, y, actionWidth, () -> VersionClient.show(new TownMapScreen(this)));
        else label(font.plainSubstrByWidth("世界：" + worldName(gateway.worldId), actionWidth - 4), contentLeft + 2, y + 6, MUTED);
        Button create = button(section == 0 ? "登记工程" : "留下留言", contentLeft + contentWidth - actionWidth, y, actionWidth, this::createRecord);
        create.active = !gateway.worldId.isEmpty() && has(section == 0 ? "project.create" : "message.create") && !archived();
        String collection = COLLECTIONS[section];
        list(objects(array(collection)), y + 26, record -> showRecord(collection, record));
    }

    private void management() {
        int y = contentTop;
        if (section == 0) {
            label(has("members.manage") ? "新人只读；审核时为成员分配权限组。" : "成员名单 · 当前权限仅供查阅", contentLeft + 3, y + 5, MUTED);
            list(objects(array("members")), y + 24, this::memberDetail);
        } else if (section == 1) {
            Button create = button("＋ 创建权限组", contentLeft, y, 125, () -> roleForm(new JsonObject()));
            create.active = has("roles.manage");
            list(objects(array("roles")), y + 28, this::roleDetail);
        } else if (section == 2) {
            Button create = button("＋ 登记新世界 / 周目", contentLeft, y, 170, this::worldForm);
            create.active = has("worlds.manage");
            list(objects(array("worlds")), y + 28, this::worldDetail);
        } else {
            JsonObject town = objectValue(gateway.snapshot, "town");
            paragraph("小镇 ID " + gateway.townId + " · " + (bool(town, "passwordEnabled") ? "加入密码已开启" : "加入密码已关闭"), contentLeft, y + 5, contentWidth, MUTED);
            int actionWidth = (contentWidth - 8) / 2;
            Button edit = button("小镇资料", contentLeft, y + 29, actionWidth, this::townSettings);
            edit.active = has("town.manage");
            Button password = button("加入密码", contentLeft + actionWidth + 8, y + 29, actionWidth, this::passwordForm);
            password.active = has("town.manage");
            button("切换小镇", contentLeft, y + 57, actionWidth, this::chooseTown);
            button("重新连接身份", contentLeft + actionWidth + 8, y + 57, actionWidth, () -> { gateway.logout(); tab = 0; VersionClient.connectIdentity(this::rebuild); rebuild(); });
        }
    }

    private void list(List<JsonObject> records, int top, Consumer<JsonObject> details) {
        int available = Math.max(0, (contentBottom - top - 24 + 4) / 48);
        if (available == 0) {
            if (contentBottom - top >= 14) label("请减小 GUI 缩放以查看列表", contentLeft + 4, top + 3, MUTED);
            return;
        }
        int pages = Math.max(1, (records.size() + available - 1) / available);
        page = Math.max(0, Math.min(page, pages - 1));
        if (records.isEmpty()) {
            int emptyHeight = Math.min(70, contentBottom - top - 24);
            panels.add(new Panel(contentLeft, top, contentWidth, emptyHeight, false));
            label("还没有记录，新的计划正等待开始。", contentLeft + 12, top + 12, TEXT);
            if (emptyHeight >= 45) label("使用上方的新建按钮，登记小镇事务。", contentLeft + 12, top + 30, MUTED);
        }
        for (int index = page * available; index < Math.min(records.size(), (page + 1) * available); index++) {
            JsonObject record = records.get(index);
            int y = top + (index - page * available) * 48;
            String title = stringValue(record, "name", stringValue(record, "title", stringValue(record, "text", "记录")));
            String subtitle = subtitle(record);
            String status = state(stringValue(record, "status", bool(record, "archived") ? "archived" : ""));
            boolean repair = tab == 1 && section == 0 && List.of("completed", "maintenance").contains(stringValue(record, "status"));
            cards.add(new Card(contentLeft, y, contentWidth, title, subtitle, status, repair));
            if (repair) {
                Button report = button("报修", contentLeft + contentWidth - 55, y + 12, 48, () -> ticketForm(record));
                report.active = has("ticket.create") && !archived();
            }
            button("详情", contentLeft + contentWidth - (repair ? 109 : 55), y + 12, 48, () -> details.accept(record));
        }
        int footer = contentBottom - 20;
        if (pages > 1) {
            Button previous = button("‹", contentLeft, footer, 24, () -> { page--; rebuild(); });
            Button next = button("›", contentLeft + 100, footer, 24, () -> { page++; rebuild(); });
            previous.active = page > 0;
            next.active = page < pages - 1;
            label((page + 1) + " / " + pages, contentLeft + 36, footer + 6, MUTED);
        }
        if (archived()) label("历史周目 · 只读", contentLeft + contentWidth - 96, footer + 6, ACCENT);
    }

    private void createRecord() {
        if (section == 0) VersionClient.show(new TownMapScreen(this, chunks -> projectForm(new JsonObject(), chunks)));
        else messageForm();
    }

    private void showRecord(String collection, JsonObject record) {
        List<String> description = describe(record);
        List<TownForm.Action> actions = new ArrayList<>();
        String id = stringValue(record, "id");
        boolean writable = !archived();
        if (collection.equals("projects")) {
            if (canManageProject(record) && writable) {
                actions.add(action("编辑工程", () -> projectForm(record, objectValue(record, "chunks"))));
                if (stringValue(record, "status").equals("planned")) actions.add(action("开始施工", () -> perform("project.status", object("id", id, "status", "active"))));
                if (stringValue(record, "status").equals("active")) actions.add(action("标记完工", () -> confirm("工程完工", "确认工程已建成，后续可随时报修。", () -> perform("project.status", object("id", id, "status", "completed")))));
            }
            if (writable && has("ticket.create") && List.of("completed", "maintenance").contains(stringValue(record, "status"))) actions.add(action("提交维护工单", () -> ticketForm(record)));
            for (JsonElement element : array("tickets")) {
                JsonObject ticket = element.getAsJsonObject();
                if (stringValue(ticket, "projectId").equals(id)) actions.add(action(stringValue(ticket, "title") + " · " + state(stringValue(ticket, "status")), () -> showRecord("tickets", ticket)));
            }
            actions.add(action("查看地图", () -> VersionClient.show(new TownMapScreen(this))));
        } else if (collection.equals("messages") && has("message.create") && writable) {
            if (stringValue(record, "createdBy").equals(playerId()) || has("project.approve")) actions.add(action("删除留言", () -> confirm("删除留言", "删除后无法恢复。", () -> perform("message.delete", object("id", id)))));
        } else if (collection.equals("tickets") && writable && (has("ticket.manage") || canManageProject(project(stringValue(record, "projectId"))))) {
            if (stringValue(record, "status").equals("open")) actions.add(action("开始维护", () -> perform("ticket.update", object("id", id, "status", "working"))));
            if (!stringValue(record, "status").equals("resolved")) actions.add(action("完成维护", () -> form("维护结果", List.of(text("resolution", "处理说明", "")), data -> { data.addProperty("id", id); data.addProperty("status", "resolved"); perform("ticket.update", data); })));
        }
        if (record.has("dimension") && !archived()) actions.add(action("导航到这里", () -> VersionClient.navigate(record)));
        VersionClient.show(new TownForm(this, stringValue(record, "name", stringValue(record, "title", "留言详情")), List.of(), description, actions, null));
    }

    private List<String> describe(JsonObject record) {
        List<String> result = new ArrayList<>();
        if (record.has("status") && !stringValue(record, "status").equals("completed")) result.add("状态：" + state(stringValue(record, "status")));
        String[][] labels = {{"purpose", "用途"}, {"description", "说明"}, {"text", "留言"}, {"notes", "备注"}, {"resolution", "处理结果"}};
        for (String[] label : labels) if (!stringValue(record, label[0]).isBlank()) result.add(label[1] + "：" + stringValue(record, label[0]));
        if (record.has("leaderId")) result.add("负责人：" + memberName(stringValue(record, "leaderId")));
        if (record.has("projectId") && !stringValue(record, "projectId").isBlank()) result.add("工程：" + projectName(stringValue(record, "projectId")));
        if (record.has("chunks")) { JsonObject chunks = objectValue(record, "chunks"); result.add("区块：(" + stringValue(chunks, "minX") + ", " + stringValue(chunks, "minZ") + ") → (" + stringValue(chunks, "maxX") + ", " + stringValue(chunks, "maxZ") + ")"); }
        if (record.has("x")) result.add("位置：" + stringValue(record, "dimension") + "  " + stringValue(record, "x") + ", " + stringValue(record, "y") + ", " + stringValue(record, "z"));
        if (record.has("createdBy")) result.add("登记人：" + stringValue(record, "createdByName", memberName(stringValue(record, "createdBy"))));
        if (!arrayValue(record, "overlapIds").isEmpty()) result.add("与 " + arrayValue(record, "overlapIds").size() + " 个工程区域重叠");
        return result;
    }

    private void projectForm(JsonObject record, JsonObject chunks) {
        boolean editing = record.has("id");
        List<TownForm.Field> fields = List.of(text("name", "工程名称（预留可留空）", stringValue(record, "name")).compact(), choice("type", "类型", stringValue(record, "type", "building"), new String[]{"building", "machine", "other"}, new String[]{"建筑", "机器", "其他"}).compact(), memberChoice("leaderId", "负责人", stringValue(record, "leaderId", playerId())).compact(), text("purpose", "用途与备注", stringValue(record, "purpose") + (stringValue(record, "notes").isBlank() ? "" : " / " + stringValue(record, "notes"))).compact());
        String dimension = stringValue(record, "dimension", stringValue(chunks, "dimension", VersionClient.dimension()));
        List<String> details = List.of("选区：" + integer(chunks, "minX") + ", " + integer(chunks, "minZ") + " → " + integer(chunks, "maxX") + ", " + integer(chunks, "maxZ") + " · " + dimension);
        VersionClient.show(new TownForm(this, editing ? "编辑工程" : "预留工程区域", fields, details, List.of(), data -> {
            data.add("chunks", chunks.deepCopy()); data.addProperty("dimension", dimension); data.addProperty("notes", "");
            if (editing) data.addProperty("id", stringValue(record, "id")); else data.addProperty("worldId", gateway.worldId);
            perform(editing ? "project.update" : "project.create", data);
        }));
    }

    private void messageForm() {
        if (Minecraft.getInstance().player == null || !XaeroBridge.isCurrentWorld()) {
            gateway.status = "请进入对应周目并绑定个人地图后，在当前位置留言";
            return;
        }
        JsonObject location = VersionClient.position();
        List<TownForm.Field> fields = List.of(text("text", "留言内容", ""), optionalProjectChoice().compact(), number("radius", "提示距离（方块）", Math.min(maximumRadius(), gateway.config().nearbyRadius)).compact());
        VersionClient.show(new TownForm(this, "留下留言", fields, List.of("自动记录当前位置：" + stringValue(location, "dimension") + " " + integer(location, "x") + ", " + integer(location, "y") + ", " + integer(location, "z")), List.of(), data -> {
            if (stringValue(data, "projectId").isEmpty()) data.remove("projectId");
            for (String key : List.of("dimension", "x", "y", "z")) data.add(key, location.get(key));
            data.addProperty("worldId", gateway.worldId); perform("message.create", data);
        }));
    }

    private void ticketForm(JsonObject project) {
        JsonObject location = VersionClient.position();
        boolean atProjectWorld = Minecraft.getInstance().player != null && XaeroBridge.isCurrentWorld() && stringValue(location, "dimension").equals(stringValue(project, "dimension"));
        if (!atProjectWorld) {
            JsonObject chunks = objectValue(project, "chunks");
            location = object("dimension", stringValue(project, "dimension"), "x", (integer(chunks, "minX") + integer(chunks, "maxX") + 1) * 8, "y", 64, "z", (integer(chunks, "minZ") + integer(chunks, "maxZ") + 1) * 8);
        }
        JsonObject position = location;
        List<TownForm.Field> fields = List.of(choice("title", "故障类型", "机器堵塞", new String[]{"机器堵塞", "照明缺失", "线路断裂", "其他故障"}, new String[]{"机器堵塞", "照明缺失", "线路断裂", "其他故障"}).compact(), text("description", "补充说明", "").compact());
        VersionClient.show(new TownForm(this, "维护报修 · " + stringValue(project, "name"), fields, List.of((atProjectWorld ? "当前位置：" : "工程中心：") + stringValue(position, "dimension") + " " + integer(position, "x") + ", " + integer(position, "y") + ", " + integer(position, "z")), List.of(), data -> {
            for (String key : List.of("dimension", "x", "y", "z")) data.add(key, position.get(key));
            data.addProperty("projectId", stringValue(project, "id")); data.addProperty("worldId", stringValue(project, "worldId")); perform("ticket.create", data);
        }));
    }

    private void connectionForm() {
        VersionClient.show(VersionClient.createConfigScreen(this));
    }
    private void createTown() {
        form("注册小镇", List.of(text("name", "小镇名称", ""), text("introduction", "小镇介绍", ""), text("logo", "Logo：物品 ID 或 texture:命名空间:贴图路径.png", "minecraft:oak_sapling"), text("server", "所在服务器", VersionClient.serverAddress()), text("password", "加入密码（留空关闭，开启时至少 6 字）", "")), data -> perform("town.create", data));
    }
    private void joinTown() {
        form("通过小镇 ID 加入", List.of(text("townId", "八位数字小镇 ID", ""), text("password", "小镇密码（未开启则留空）", "")), data -> perform("town.join", data));
    }
    private void chooseTown() {
        List<TownForm.Action> actions = new ArrayList<>();
        for (JsonElement element : gateway.towns) { JsonObject town = element.getAsJsonObject(); actions.add(action(stringValue(town, "name") + " · " + stringValue(town, "id"), () -> { gateway.selectTown(stringValue(town, "id")); homeScroll = 0; tab = 0; VersionClient.show(this); })); }
        VersionClient.show(new TownForm(this, "选择当前小镇", List.of(), List.of("加入多个小镇后，可随时切换。"), actions, null));
    }
    private void chooseWorld() {
        List<TownForm.Action> actions = new ArrayList<>();
        for (JsonElement element : array("worlds")) { JsonObject world = element.getAsJsonObject(); actions.add(action(stringValue(world, "name") + (bool(world, "archived") ? " · 历史" : " · 进行中"), () -> { gateway.selectWorld(stringValue(world, "id")); VersionClient.show(this); })); }
        VersionClient.show(new TownForm(this, "选择世界与周目", List.of(), List.of("归档周目保存工程与业务标记，并按 Xaero 世界标识关联个人本地历史地图。"), actions, null));
    }
    private void memberDetail(JsonObject member) {
        String id = stringValue(member, "playerId");
        List<TownForm.Action> actions = new ArrayList<>();
        actions.add(action("复制玩家 UUID", () -> { VersionClient.copy(id); gateway.status = "已复制 UUID"; }));
        if (has("members.manage") && !stringValue(member, "roleId").equals("mayor") && canGrant(role(stringValue(member, "roleId")))) {
            actions.add(action("审核 / 分配权限组", () -> form("修改 " + stringValue(member, "name") + " 的权限组", List.of(roleChoice(stringValue(member, "roleId"))), data -> { data.addProperty("playerId", id); perform("member.role", data); })));
            actions.add(action("移出小镇", () -> confirm("移出成员", "将 " + stringValue(member, "name") + " 移出小镇。", () -> perform("member.remove", object("playerId", id)))));
        }
        if (isMayor() && !id.equals(playerId())) actions.add(action("移交镇长职位", () -> confirm("移交镇长", "将唯一镇长职位交给 " + stringValue(member, "name") + "，你的权限将改变。", () -> perform("town.transfer", object("playerId", id)))));
        VersionClient.show(new TownForm(this, stringValue(member, "name"), List.of(), List.of("权限组：" + roleName(stringValue(member, "roleId")), "UUID：" + id, "加入时间：" + stringValue(member, "joinedAt")), actions, null));
    }
    private void roleDetail(JsonObject role) {
        List<String> description = new ArrayList<>();
        description.add("权限组 ID：" + stringValue(role, "id"));
        for (JsonElement permission : arrayValue(role, "permissions")) description.add("✓ " + permissionName(permission.getAsString()));
        if (arrayValue(role, "permissions").isEmpty()) description.add("只读；没有修改权限。");
        List<TownForm.Action> actions = new ArrayList<>();
        if (canEditRole(role)) {
            actions.add(action("逐项编辑权限", () -> roleForm(role)));
        }
        boolean assigned = objects(array("members")).stream().anyMatch(member -> stringValue(member, "roleId").equals(stringValue(role, "id")));
        if (has("roles.manage") && canGrant(role) && !assigned && !List.of("newcomer", "mayor", "member", "manager").contains(stringValue(role, "id"))) actions.add(action("删除权限组", () -> confirm("删除权限组", "只有没有成员使用的权限组可删除。", () -> perform("role.delete", object("id", stringValue(role, "id"))))));
        if (has("roles.manage") && !canEditRole(role)) description.add("此权限组不可编辑：需具备该组全部权限；修改其他成员使用的组还需成员管理权限。镇长与新人组固定。");
        VersionClient.show(new TownForm(this, stringValue(role, "name"), List.of(), description, actions, null));
    }
    private void roleForm(JsonObject role) {
        List<TownForm.Field> fields = new ArrayList<>(); fields.add(text("name", "权限组名称", stringValue(role, "name")));
        for (JsonElement element : array("permissionCatalog")) { JsonObject permission = element.getAsJsonObject(); String id = stringValue(permission, "id"); if (isMayor() || has(id)) fields.add(toggle(id, stringValue(permission, "name"), contains(arrayValue(role, "permissions"), id))); }
        form("逐项设置权限", fields, data -> { JsonArray permissions = new JsonArray(); for (TownForm.Field field : fields) if (!field.key().equals("name") && bool(data, field.key())) permissions.add(field.key()); JsonObject payload = object("name", stringValue(data, "name"), "permissions", permissions); if (role.has("id")) payload.addProperty("id", stringValue(role, "id")); perform("role.save", payload); });
    }
    private void worldForm() { worldForm(new JsonObject()); }
    private void worldForm(JsonObject world) {
        form(world.has("id") ? "编辑世界 / 周目信息" : "登记新世界 / 新周目", List.of(text("name", "世界 / 周目名称", stringValue(world, "name")), text("serverAddress", "游戏服务器地址", stringValue(world, "serverAddress", VersionClient.serverAddress())), text("worldKey", "世界标识（小镇自定义，如周目编号）", stringValue(world, "worldKey"))), data -> { if (world.has("id")) data.addProperty("id", stringValue(world, "id")); perform(world.has("id") ? "world.update" : "world.create", data); });
    }
    private void worldDetail(JsonObject world) {
        List<TownForm.Action> actions = new ArrayList<>();
        actions.add(action("切换并查看" + (bool(world, "archived") ? "历史" : "当前") + "地图资料", () -> { gateway.selectWorld(stringValue(world, "id")); VersionClient.show(this); }));
        if (has("worlds.manage") && !bool(world, "archived")) actions.add(action("编辑世界名称、服务器与地图标识", () -> worldForm(world)));
        if (!XaeroBridge.currentMapKey().isBlank()) actions.add(action("为此周目绑定我的当前 Xaero 地图", () -> bindWorldMap(world)));
        actions.add(action("解除此周目的个人地图绑定", () -> form("解除个人地图绑定", List.of(text("dimension", "需要解除的维度", XaeroBridge.currentMapDimension())), data -> { gateway.selectWorld(stringValue(world, "id")); gateway.unbindMap(stringValue(data, "dimension")); gateway.status = "个人地图绑定已解除"; VersionClient.show(this); })));
        if (has("worlds.manage") && !bool(world, "archived")) actions.add(action("归档此世界 / 周目", () -> confirm("归档周目", "归档后业务资料只读，个人 Xaero 地图保留。", () -> perform("world.archive", object("worldId", stringValue(world, "id"))))));
        List<String> details = new ArrayList<>(List.of("服务器：" + stringValue(world, "serverAddress"), "世界标识：" + stringValue(world, "worldKey"), "状态：" + (bool(world, "archived") ? "已归档" : "进行中"), "个人地图绑定仅保存在本机，每位成员分别关联自己探索的 Xaero 地形。"));
        if (stringValue(world, "id").equals(gateway.worldId)) {
            String dimension = XaeroBridge.currentMapDimension();
            String bound = gateway.getBoundMapKey(dimension);
            details.add("我的 " + dimension + " 地图：" + (bound.isBlank() ? "尚未绑定" : bound));
        } else details.add("选择此周目后，可查看本机的地图绑定。");
        VersionClient.show(new TownForm(this, stringValue(world, "name"), List.of(), details, actions, null));
    }
    private void bindWorldMap(JsonObject world) {
        String dimension = XaeroBridge.currentMapDimension(), mapKey = XaeroBridge.currentMapKey();
        String explanation = "周目：" + stringValue(world, "name") + "\n地图维度：" + dimension + "\n个人 Xaero 实例：" + mapKey + "\n请确认当前 Xaero 实例属于这个周目。";
        if (bool(world, "archived")) explanation += "\n这是历史周目，请先在 Xaero 中选择对应历史实例；不要关联当前周目。";
        confirm("绑定个人地图", explanation, () -> { gateway.selectWorld(stringValue(world, "id")); gateway.bindCurrentMap(dimension, mapKey); gateway.status = "个人地图绑定已保存"; VersionClient.show(this); });
    }
    private void townSettings() {
        JsonObject town = objectValue(gateway.snapshot, "town");
        form("小镇资料", List.of(text("name", "名称", stringValue(town, "name")), text("introduction", "介绍", stringValue(town, "introduction")), text("logo", "Logo：物品 ID 或 texture:命名空间:贴图路径.png", stringValue(town, "logo", "minecraft:oak_sapling")), text("server", "所在服务器", stringValue(town, "server"))), data -> perform("town.update", data));
    }
    private void passwordForm() { form("设置小镇加入密码", List.of(text("password", "加入密码（留空即关闭）", "")), data -> perform("town.update", data)); }

    private int maximumRadius() { int maximum = integer(objectValue(gateway.snapshot, "limits"), "maximumProximityRadius"); return maximum > 0 ? maximum : 256; }
    private TownForm.Field optionalProjectChoice() { return collectionChoice("projectId", "关联工程", array("projects"), "id", "name", "", true); }
    private TownForm.Field roleChoice(String selected) {
        JsonArray roles = new JsonArray();
        for (JsonElement element : array("roles")) {
            JsonObject role = element.getAsJsonObject();
            if (stringValue(role, "id").equals("mayor")) continue;
            if (canGrant(role)) roles.add(element);
        }
        return collectionChoice("roleId", "权限组", roles, "id", "name", selected, false);
    }
    private TownForm.Field memberChoice(String key, String label, String selected) { return collectionChoice(key, label, array("members"), "playerId", "name", selected, false); }
    private TownForm.Field collectionChoice(String key, String label, JsonArray collection, String idKey, String nameKey, String selected, boolean optional) {
        List<String> values = new ArrayList<>(), names = new ArrayList<>();
        if (optional) { values.add(""); names.add("不关联工程"); }
        for (JsonElement element : collection) { JsonObject item = element.getAsJsonObject(); values.add(stringValue(item, idKey)); names.add(stringValue(item, nameKey)); }
        if (values.isEmpty()) { values.add(""); names.add("暂无可选项"); }
        return choice(key, label, values.contains(selected) ? selected : values.get(0), values.toArray(String[]::new), names.toArray(String[]::new));
    }
    private void form(String title, List<TownForm.Field> fields, Consumer<JsonObject> submit) { VersionClient.show(new TownForm(this, title, fields, submit)); }
    private void confirm(String title, String explanation, Runnable submit) { VersionClient.show(new TownForm(this, title, List.of(), List.of(explanation), List.of(), ignored -> submit.run())); }
    private TownForm.Action action(String label, Runnable run) { return new TownForm.Action(label, run); }
    private void perform(String action, JsonObject data) {
        boolean selectTown = action.equals("town.create") || action.equals("town.join");
        if (!selectTown && !data.has("townId")) data.addProperty("townId", gateway.townId);
        TownGateway.Context context = gateway.context();
        Screen origin = VersionClient.currentScreen();
        TownForm.track(gateway.request(action, data).thenAccept(result -> Minecraft.getInstance().execute(() -> {
            if (!gateway.isCurrent(context)) return;
            gateway.status = "操作已保存";
            if (selectTown) {
                gateway.refreshTowns();
                if (VersionClient.currentScreen() == origin) { gateway.selectTown(stringValue(result, "townId")); tab = 0; section = 0; page = 0; }
            } else gateway.refresh();
            if (VersionClient.currentScreen() == origin) VersionClient.show(this);
        })));
    }

    private Button button(String label, int x, int y, int width, Runnable run) {
        Button button = TownStyle.button(label, x, y, width, run);
        if (homeContent) { homeButtons.add(button); button.visible = y >= bodyTop && y + 20 <= bodyBottom; }
        return addRenderableWidget(button);
    }
    private void label(String text, int x, int y, int color) { lines.add(new Line(text, x, y, color)); }
    private int paragraph(String text, int x, int y, int width, int color) { for (String line : wrap(text, width)) { label(line, x, y, color); y += 13; } return y; }
    private List<String> wrap(String text, int width) { return wrapText(text, width, font); }
    static List<String> wrapText(String text, int width, net.minecraft.client.gui.Font font) {
        List<String> result = new ArrayList<>();
        for (String paragraph : text.split("\\n", -1)) {
            String remaining = paragraph;
            while (!remaining.isEmpty()) { String part = font.plainSubstrByWidth(remaining, Math.max(12, width)); if (part.isEmpty()) part = remaining.substring(0, 1); result.add(part); remaining = remaining.substring(part.length()); }
            if (paragraph.isEmpty()) result.add("");
        }
        return result;
    }
    static JsonObject objectValue(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject(); }
    static JsonArray arrayValue(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray(); }
    static String stringValue(JsonObject object, String key) { return stringValue(object, key, ""); }
    static String stringValue(JsonObject object, String key, String fallback) { return object.has(key) && !object.get(key).isJsonNull() && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback; }
    static int integer(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : 0; }
    static boolean bool(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean(); }
    private JsonArray array(String key) { return arrayValue(gateway.snapshot, key); }
    private List<JsonObject> objects(JsonArray array) { List<JsonObject> result = new ArrayList<>(); for (JsonElement element : array) result.add(element.getAsJsonObject()); return result; }
    static boolean contains(JsonArray array, String value) { for (JsonElement item : array) if (item.isJsonPrimitive() && item.getAsString().equals(value)) return true; return false; }
    private boolean has(String permission) { return contains(array("permissions"), permission); }
    private JsonObject project(String id) { for (JsonElement element : array("projects")) if (stringValue(element.getAsJsonObject(), "id").equals(id)) return element.getAsJsonObject(); return new JsonObject(); }
    private boolean canManageProject(JsonObject project) { return has("project.approve") || has("project.edit") && stringValue(project, "leaderId").equals(playerId()); }
    private JsonObject role(String id) { for (JsonElement element : array("roles")) if (stringValue(element.getAsJsonObject(), "id").equals(id)) return element.getAsJsonObject(); return new JsonObject(); }
    private boolean canGrant(JsonObject role) {
        if (!role.has("id")) return false;
        for (JsonElement permission : arrayValue(role, "permissions")) if (!has(permission.getAsString())) return false;
        return true;
    }
    private boolean canEditRole(JsonObject role) {
        if (!has("roles.manage") || !canGrant(role) || List.of("newcomer", "mayor").contains(stringValue(role, "id"))) return false;
        return has("members.manage") || objects(array("members")).stream().noneMatch(member -> stringValue(member, "roleId").equals(stringValue(role, "id")) && !stringValue(member, "playerId").equals(playerId()));
    }
    private boolean isMayor() { return stringValue(objectValue(gateway.snapshot, "town"), "ownerId").equals(playerId()); }
    private String playerId() { return stringValue(gateway.player(), "id", stringValue(gateway.player(), "playerId")); }
    private boolean archived() { for (JsonElement element : array("worlds")) { JsonObject world = element.getAsJsonObject(); if (stringValue(world, "id").equals(gateway.worldId)) return bool(world, "archived"); } return false; }
    private String lookup(String collection, String key, String id, String fallback) { for (JsonElement element : array(collection)) { JsonObject item = element.getAsJsonObject(); if (stringValue(item, key).equals(id)) return stringValue(item, "name", fallback); } return fallback; }
    private String projectName(String id) { return lookup("projects", "id", id, "未指定"); }
    private String worldName(String id) { return lookup("worlds", "id", id, "尚未登记世界"); }
    private String roleName(String id) { return lookup("roles", "id", id, id); }
    private String memberName(String id) { return lookup("members", "playerId", id, id.isEmpty() ? "未指定" : id); }
    private String permissionName(String id) { for (JsonElement element : array("permissionCatalog")) { JsonObject item = element.getAsJsonObject(); if (stringValue(item, "id").equals(id)) return stringValue(item, "name"); } return id; }
    private String subtitle(JsonObject record) {
        if (record.has("roleId")) return roleName(stringValue(record, "roleId"));
        if (record.has("permissions")) return arrayValue(record, "permissions").size() + " 项权限";
        if (record.has("worldKey")) return stringValue(record, "serverAddress") + " · " + stringValue(record, "worldKey");
        if (record.has("projectId")) return projectName(stringValue(record, "projectId")) + " · " + memberName(stringValue(record, "createdBy"));
        if (record.has("purpose")) return (stringValue(record, "purpose").isBlank() ? "" : stringValue(record, "purpose") + " · ") + memberName(stringValue(record, "leaderId"));
        return stringValue(record, "dimension") + (record.has("x") ? " · " + stringValue(record, "x") + ", " + stringValue(record, "y") + ", " + stringValue(record, "z") : "");
    }
    static String state(String value) { return switch (value) { case "planned" -> "计划中"; case "active" -> "施工中"; case "completed" -> ""; case "maintenance" -> "待维护"; case "open" -> "待处理"; case "working" -> "处理中"; case "resolved" -> "已解决"; case "archived" -> "历史归档"; default -> value; }; }

    static void background(GuiGraphicsExtractor graphics, int width, int height) { TownStyle.background(graphics, width, height); }
    private void moveScroll(int direction, boolean pageStep) {
        if (tab == 0) homeScroll = Math.max(0, Math.min(maximumHomeScroll, homeScroll + direction * (pageStep ? Math.max(32, bodyBottom - bodyTop - 20) : 32)));
        else page += direction;
        rebuild();
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x < contentLeft || x > contentLeft + contentWidth || y < bodyTop || y > bodyBottom || vertical == 0) return super.mouseScrolled(x, y, horizontal, vertical);
        moveScroll(vertical < 0 ? 1 : -1, false);
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (tab == 0 && event.isCycleFocus()) {
            int direction = event.hasShiftDown() ? -1 : 1;
            int index = children().indexOf(getFocused());
            if (index < 0) index = direction > 0 ? -1 : 0;
            for (int step = 0; step < children().size(); step++) {
                index = Math.floorMod(index + direction, children().size());
                if (!(children().get(index) instanceof Button target) || !target.active) continue;
                if (homeButtons.contains(target) && !target.visible) {
                    int offset = target.getY() < bodyTop ? target.getY() - bodyTop : target.getY() + target.getHeight() - bodyBottom;
                    homeScroll = Math.max(0, Math.min(maximumHomeScroll, homeScroll + offset));
                    rebuild();
                }
                setInitialFocus(children().get(index));
                return true;
            }
        }
        if (event.key() == InputConstants.KEY_PAGEUP || event.key() == InputConstants.KEY_PAGEDOWN) {
            moveScroll(event.key() == InputConstants.KEY_PAGEUP ? -1 : 1, true);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        background(graphics, width, height);
        TownStyle.window(graphics, window, bodyTop, bodyBottom);
        double progress = gateway.config().animationEnabled ? Math.min(1, (System.nanoTime() - openedAt) / 260_000_000d) : 1;
        double eased = 1 - Math.pow(1 - progress, 3);
        TownStyle.icon(graphics, 0, contentLeft + 1, window.top() + 10, TownStyle.FRAME_TEXT);
        graphics.text(font, font.plainSubstrByWidth(TownStyle.title(), contentWidth - 50), contentLeft + 16 + (int) ((1 - eased) * 4), window.top() + 10, TownStyle.FRAME_TEXT, false);
        graphics.enableScissor(contentLeft, bodyTop, contentLeft + contentWidth, bodyBottom);
        for (Panel panel : panels) TownStyle.panel(graphics, panel.x(), panel.y(), panel.width(), panel.height(), panel.accented());
        for (Card card : cards) {
            TownStyle.panel(graphics, card.x(), card.y(), card.width(), 44, false);
            boolean projectRow = tab == 1 && section == 0;
            int textOffset = projectRow ? 12 : 38;
            if (!projectRow) {
                TownStyle.panel(graphics, card.x() + 7, card.y() + 10, 23, 23, false);
                TownStyle.icon(graphics, tab == 1 ? section + 1 : 3, card.x() + 15, card.y() + 18, ACCENT);
            }
            int buttonSpace = card.repair() ? 120 : 66;
            int textWidth = card.width() - textOffset - buttonSpace;
            int titleY = card.y() + 9;
            int badgeWidth = card.state().isEmpty() ? 0 : Math.min(79, font.width(card.state()) + 12);
            graphics.text(font, font.plainSubstrByWidth(card.title(), Math.max(0, textWidth - (badgeWidth > 0 ? badgeWidth + 8 : 0))), card.x() + textOffset, titleY, TEXT, true);
            graphics.text(font, font.plainSubstrByWidth(card.subtitle(), Math.max(0, textWidth)), card.x() + textOffset, card.y() + 26, MUTED, false);
            if (badgeWidth > 0) {
                int badgeX = card.x() + card.width() - buttonSpace - badgeWidth;
                graphics.fill(badgeX, titleY - 3, badgeX + badgeWidth, titleY + 11, TownStyle.RAISED);
                graphics.text(font, font.plainSubstrByWidth(card.state(), badgeWidth - 12), badgeX + 6, titleY, ACCENT, false);
            }
        }
        for (Line line : lines) graphics.text(font, font.plainSubstrByWidth(line.text(), contentLeft + contentWidth - line.x() - 8), line.x(), line.y(), line.color(), false);
        if (tab == 0) {
            VersionClient.drawAvatar(graphics, avatarX, avatarY, 28);
            if (showLogo) {
                TownStyle.panel(graphics, logoX - 4, logoY - 4, 36, 36, false);
                VersionClient.drawLogo(graphics, stringValue(objectValue(gateway.snapshot, "town"), "logo"), logoX, logoY);
            }
        }
        graphics.disableScissor();
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (tab == 0 && maximumHomeScroll > 0) {
            int trackHeight = bodyBottom - bodyTop;
            int scrollbarX = window.right() - 10;
            int thumbHeight = Math.max(14, trackHeight * trackHeight / (trackHeight + maximumHomeScroll));
            graphics.fill(scrollbarX, bodyTop, scrollbarX + 2, bodyBottom, TownStyle.LINE);
            int thumbY = bodyTop + (trackHeight - thumbHeight) * homeScroll / maximumHomeScroll;
            graphics.fill(scrollbarX, thumbY, scrollbarX + 2, thumbY + thumbHeight, ACCENT);
        }
        graphics.fill(contentLeft, window.bottom() - 19, contentLeft + 3, window.bottom() - 16, gateway.loggedIn() ? 0xFF42643C : TownStyle.FRAME_MUTED);
        graphics.text(font, font.plainSubstrByWidth(gateway.status, contentWidth - 84), contentLeft + 9, window.bottom() - 20, TownStyle.FRAME_MUTED, false);
        if (font.width(gateway.status) > contentWidth - 84 && mouseX >= contentLeft + 9 && mouseX < contentLeft + contentWidth - 76 && mouseY >= window.bottom() - 23 && mouseY < window.bottom() - 9) graphics.setTooltipForNextFrame(Component.literal(gateway.status), mouseX, mouseY);
    }
}
