package dev.yetanothertown.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
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
    private static final String[] PLATFORM = {"工程地图", "工程任务", "材料认捐", "空间留言", "公共设施", "维修工单"};
    private static final String[] MANAGEMENT = {"成员审核", "权限组", "世界与周目", "小镇设置"};
    private static final String[] COLLECTIONS = {"projects", "tasks", "materials", "messages", "facilities", "tickets"};
    private final TownGateway gateway = TownGateway.get();
    private final Screen parent;
    private final long openedAt = System.nanoTime();
    private final List<Line> lines = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private final List<Panel> panels = new ArrayList<>();
    private final List<Metric> metrics = new ArrayList<>();
    private int avatarX, avatarY, logoX, logoY;
    private boolean showLogo;
    private int tab;
    private int section;
    private int page;
    private String projectId = "";
    private JsonObject previousSnapshot;
    private JsonArray previousTowns;
    private int contentLeft;
    private int contentWidth;
    private int contentTop;
    private int homeScroll;
    private int maximumHomeScroll;
    private boolean homeContent;
    private record Panel(int x, int y, int width, int height, boolean accented) { }
    private record Metric(int x, int y, int width, String label, String value, int icon) { }
    private record Line(String text, int x, int y, int color) { }
    private record Card(int x, int y, int width, String title, String subtitle, String state) { }

    public TownScreen() { this(null); }
    public TownScreen(Screen parent) { super(Component.literal("YetAnotherTown · 小镇协作")); this.parent = parent; }

    @Override protected void init() { rebuild(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { VersionClient.show(parent); }
    @Override public void tick() {
        if (previousSnapshot != gateway.snapshot || previousTowns != gateway.towns) rebuild();
    }

    private void rebuild() {
        clearFocus();
        clearWidgets(); lines.clear(); cards.clear(); panels.clear(); metrics.clear();
        previousSnapshot = gateway.snapshot; previousTowns = gateway.towns;
        int margin = Math.max(10, (width - 720) / 2);
        contentLeft = margin;
        contentWidth = width - margin * 2;
        int navigationWidth = Math.min(104, (contentWidth - 8) / 3);
        String[] tabs = {"小镇主页", "协作平台", "小镇管理"};
        for (int index = 0; index < tabs.length; index++) {
            final int selected = index;
            Button navigation = addRenderableWidget(TownStyle.button(tabs[index], margin + index * (navigationWidth + 4), 43, navigationWidth,
                    () -> { tab = selected; section = 0; page = 0; rebuild(); }, tab == index));
            navigation.active = index == 0 || gateway.loggedIn() && !gateway.townId.isEmpty();
        }
        button("×", margin + contentWidth - 20, 10, 20, this::onClose);
        contentTop = 78;
        if (tab == 0) {
            homeContent = true; home(); homeContent = false;
            if (homeScroll > maximumHomeScroll) { homeScroll = maximumHomeScroll; rebuild(); return; }
        }
        else {
            String[] sections = tab == 1 ? PLATFORM : MANAGEMENT;
            int sectionWidth = Math.min(98, contentWidth / sections.length);
            if (contentWidth < 440) {
                button("‹", margin, 76, 22, () -> { section = (section + sections.length - 1) % sections.length; page = 0; rebuild(); });
                label(sections[section], margin + 32, 82, ACCENT);
                button("›", margin + 125, 76, 22, () -> { section = (section + 1) % sections.length; page = 0; rebuild(); });
            } else for (int index = 0; index < sections.length; index++) {
                final int selected = index;
                addRenderableWidget(TownStyle.button(sections[index], margin + index * sectionWidth, 76, sectionWidth - 4,
                        () -> { section = selected; page = 0; rebuild(); }, section == index));
            }
            contentTop = 108;
            if (tab == 1) platform(); else management();
        }
        button("刷新资料", margin + contentWidth - 64, height - 27, 64, () -> { gateway.refreshTowns(); gateway.refresh(); });
    }

    private void home() {
        int x = contentLeft, y = contentTop - homeScroll;
        boolean wide = contentWidth >= 500;
        int sideWidth = wide ? 150 : contentWidth;
        int mainWidth = wide ? contentWidth - sideWidth - 12 : contentWidth;
        int profileX = wide ? x + mainWidth + 12 : x;
        int profileY = y;
        int profileHeight = wide ? 120 : 58;
        panels.add(new Panel(profileX, profileY, sideWidth, profileHeight, false));
        avatarX = profileX + 12; avatarY = profileY + 12;
        int nameX = wide ? profileX + 12 : profileX + 50;
        int nameY = wide ? profileY + 51 : profileY + 14;
        label(font.plainSubstrByWidth(VersionClient.playerName(), wide ? sideWidth - 24 : Math.max(40, sideWidth - 155)), nameX, nameY, TEXT);
        label(gateway.loggedIn() ? "游戏身份已连接" : "等待服务连接", nameX, nameY + 14, gateway.loggedIn() ? TownStyle.SUCCESS : MUTED);
        button("客户端设置", wide ? profileX + 12 : profileX + sideWidth - 92, wide ? profileY + 89 : profileY + 18, wide ? sideWidth - 24 : 80, this::connectionForm);
        int heroY = wide ? y : y + 70;
        int heroHeight = 128;
        panels.add(new Panel(x, heroY, mainWidth, heroHeight, true));
        showLogo = !gateway.townId.isEmpty();
        logoX = x + 16; logoY = heroY + 18;
        String townName = stringValue(objectValue(gateway.snapshot, "town"), "name", gateway.townId.isEmpty() ? "每座小镇，都从这里开始" : "正在载入小镇…");
        int titleX = x + (showLogo ? 59 : 16);
        label("TOWN / 小镇总览", titleX, heroY + 16, ACCENT);
        label(font.plainSubstrByWidth(townName, mainWidth - (titleX - x) - 16), titleX, heroY + 33, TEXT);
        if (!gateway.loggedIn()) {
            paragraph("自动使用当前游戏 UUID 连接小镇服务。进入世界后即可创建小镇、登记工程并与伙伴协作。", x + 16, heroY + 60, mainWidth - 32, MUTED);
            button("重新连接", x + 16, heroY + 96, Math.min(132, mainWidth - 32), () -> VersionClient.connectIdentity(this::rebuild));
        } else if (gateway.townId.isEmpty()) {
            paragraph("注册自己的小镇，或输入八位小镇 ID，加入伙伴的建设计划。", x + 16, heroY + 60, mainWidth - 32, MUTED);
            int actionWidth = Math.min(120, (mainWidth - 40) / 2);
            button("创建小镇", x + 16, heroY + 96, actionWidth, this::createTown);
            button("通过 ID 加入", x + 24 + actionWidth, heroY + 96, actionWidth, this::joinTown);
        } else {
            JsonObject town = objectValue(gateway.snapshot, "town");
            label("ID " + gateway.townId + "  ·  " + array("members").size() + " 位成员", titleX, heroY + 48, MUTED);
            List<String> introduction = wrap(stringValue(town, "introduction", "一起规划，一起建设，让每一份投入都有回响。"), mainWidth - 32);
            for (int index = 0; index < Math.min(2, introduction.size()); index++) label(introduction.get(index), x + 16, heroY + 70 + index * 12, MUTED);
            int actionWidth = Math.min(120, (mainWidth - 40) / 2);
            button("进入协作平台", x + 16, heroY + 99, actionWidth, () -> { tab = 1; page = 0; rebuild(); });
            button("切换小镇", x + 24 + actionWidth, heroY + 99, actionWidth, this::chooseTown);
        }
        int metricY = heroY + heroHeight + 10;
        if (!gateway.townId.isEmpty()) {
            int metricWidth = (mainWidth - 12) / 3;
            String[] names = {"登记工程", "工程任务", "待处理维修"};
            int openTickets = 0;
            for (JsonElement element : array("tickets")) if (!stringValue(element.getAsJsonObject(), "status").equals("resolved")) openTickets++;
            String[] counts = {Integer.toString(array("projects").size()), Integer.toString(array("tasks").size()), Integer.toString(openTickets)};
            for (int index = 0; index < 3; index++) metrics.add(new Metric(x + index * (metricWidth + 6), metricY, metricWidth, names[index], counts[index], index == 0 ? 1 : index == 1 ? 4 : 8));
        }
        int informationY = metricY + (gateway.townId.isEmpty() ? 0 : 56);
        int informationX = wide ? profileX : x;
        int informationTop = wide ? profileY + profileHeight + 10 : informationY;
        int informationWidth = wide ? sideWidth : mainWidth;
        panels.add(new Panel(informationX, informationTop, informationWidth, 110, false));
        label("当前世界", informationX + 12, informationTop + 13, ACCENT);
        label(font.plainSubstrByWidth(worldName(gateway.worldId), informationWidth - 24), informationX + 12, informationTop + 31, TEXT);
        String server = stringValue(objectValue(gateway.snapshot, "town"), "server", VersionClient.serverAddress());
        label(font.plainSubstrByWidth(server, informationWidth - 24), informationX + 12, informationTop + 47, MUTED);
        label(roleName(stringValue(objectValue(gateway.snapshot, "currentMember"), "roleId", "尚未加入小镇")), informationX + 12, informationTop + 63, MUTED);
        Button world = button("周目与历史地图", informationX + 12, informationTop + 80, informationWidth - 24, this::chooseWorld);
        world.active = !gateway.townId.isEmpty();
        int linksY = wide ? informationY : informationTop + 122;
        label("小镇事务", x + 1, linksY + 4, ACCENT);
        int linkWidth = Math.min(116, (mainWidth - 12) / 3);
        Button create = button("注册小镇", x, linksY + 22, linkWidth, this::createTown);
        Button join = button("加入小镇", x + linkWidth + 6, linksY + 22, linkWidth, this::joinTown);
        Button manage = button("成员与权限", x + (linkWidth + 6) * 2, linksY + 22, linkWidth, () -> { tab = 2; section = 0; rebuild(); });
        create.active = gateway.loggedIn(); join.active = gateway.loggedIn(); manage.active = !gateway.townId.isEmpty();
        int bottom = Math.max(linksY + 50, informationTop + 120);
        maximumHomeScroll = Math.max(0, bottom + homeScroll - (height - 40));
    }

    private void platform() {
        String collection = COLLECTIONS[section];
        int y = contentTop;
        if (section <= 2) {
            button("工程：" + (projectId.isEmpty() ? "全部" : projectName(projectId)), contentLeft, y, Math.max(110, contentWidth - 146), this::chooseProject);
        } else if (section == 5 && has("ticket.create") && !archived()) {
            button("堵塞", contentLeft, y, 50, () -> quickTicket("机器堵塞"));
            button("照明", contentLeft + 54, y, 50, () -> quickTicket("照明缺失"));
            button("断线", contentLeft + 108, y, 50, () -> quickTicket("线路断裂"));
        } else label("世界：" + worldName(gateway.worldId), contentLeft + 4, y + 6, MUTED);
        if (section == 0) button("地图", contentLeft + contentWidth - 132, y, 60, () -> VersionClient.show(new TownMapScreen(this)));
        String permission = new String[]{"project.create", "task.manage", "material.manage", "message.create", "facility.manage", "ticket.create"}[section];
        Button create = button("＋ 新建", contentLeft + contentWidth - 62, y, 62, this::createRecord);
        create.active = has(permission) && !archived();
        List<JsonObject> records = new ArrayList<>();
        for (JsonElement element : array(collection)) {
            JsonObject record = element.getAsJsonObject();
            if (!projectId.isEmpty() && section <= 2 && !stringValue(record, section == 0 ? "id" : "projectId").equals(projectId)) continue;
            records.add(record);
        }
        list(records, y + 29, record -> showRecord(collection, record));
    }

    private void management() {
        int y = contentTop;
        if (section == 0) {
            label("新人只读；审核时为成员分配权限组。", contentLeft + 3, y + 5, MUTED);
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
            int actionWidth = Math.min(220, (contentWidth - 8) / 2);
            Button edit = button("小镇资料", contentLeft, y + 29, actionWidth, this::townSettings);
            edit.active = has("town.manage");
            Button password = button("加入密码", contentLeft + actionWidth + 8, y + 29, actionWidth, this::passwordForm);
            password.active = has("town.manage");
            button("切换小镇", contentLeft, y + 57, actionWidth, this::chooseTown);
            button("重新连接游戏身份", contentLeft + actionWidth + 8, y + 57, actionWidth, () -> { gateway.logout(); tab = 0; VersionClient.connectIdentity(this::rebuild); rebuild(); });
        }
    }

    private void list(List<JsonObject> records, int top, Consumer<JsonObject> details) {
        int available = Math.max(1, (height - 64 - top) / 52);
        int pages = Math.max(1, (records.size() + available - 1) / available);
        page = Math.max(0, Math.min(page, pages - 1));
        if (records.isEmpty()) {
            int emptyHeight = Math.min(70, height - 55 - top);
            panels.add(new Panel(contentLeft, top, contentWidth, emptyHeight, false));
            label("还没有记录，新的计划正等待开始。", contentLeft + 18, top + 12, TEXT);
            if (emptyHeight >= 45) label("使用上方的新建按钮，登记小镇事务。", contentLeft + 18, top + 30, MUTED);
        }
        for (int index = page * available; index < Math.min(records.size(), (page + 1) * available); index++) {
            JsonObject record = records.get(index);
            int y = top + (index - page * available) * 52;
            String title = stringValue(record, "name", stringValue(record, "title", stringValue(record, "text", "记录")));
            String subtitle = subtitle(record);
            String status = state(stringValue(record, "status", bool(record, "archived") ? "archived" : ""));
            cards.add(new Card(contentLeft, y, contentWidth, title, subtitle, status));
            button("查看", contentLeft + contentWidth - 62, y + 13, 57, () -> details.accept(record));
        }
        int footer = height - 53;
        if (pages > 1) {
            button("‹ 上页", contentLeft, footer, 65, () -> { page--; rebuild(); });
            button("下页 ›", contentLeft + 135, footer, 65, () -> { page++; rebuild(); });
            label((page + 1) + " / " + pages, contentLeft + 79, footer + 6, MUTED);
        }
        if (archived()) label(contentWidth < 380 && pages > 1 ? "只读" : "历史周目 · 只读浏览", contentLeft + contentWidth - (contentWidth < 380 && pages > 1 ? 30 : 120), footer + 6, ACCENT);
    }

    private void createRecord() {
        if (section == 0) VersionClient.show(new TownMapScreen(this, chunks -> projectForm(new JsonObject(), chunks)));
        if (section == 1) taskForm();
        if (section == 2) materialForm();
        if (section == 3) messageForm();
        if (section == 4) facilityForm(new JsonObject());
        if (section == 5) ticketForm();
    }

    private void showRecord(String collection, JsonObject record) {
        List<String> description = describe(collection, record);
        List<TownForm.Action> actions = new ArrayList<>();
        String id = stringValue(record, "id");
        boolean writable = !archived();
        if (collection.equals("projects")) {
            if (canManageProject(record, "project.edit") && writable && !stringValue(record, "status").equals("completed")) actions.add(action("修改工程资料 / 项目组", () -> projectForm(record, objectValue(record, "chunks"))));
            if (canManageProject(record, "project.edit") && canConfirm(record) && writable && stringValue(record, "status").equals("active")) actions.add(action("提交工程验收", () -> perform("project.submit", object("id", id))));
            if (has("project.approve") && writable) {
                if (stringValue(record, "status").equals("pending")) {
                    actions.add(action("批准施工预约", () -> review(id, "approve")));
                    actions.add(action("驳回预约并填写理由", () -> review(id, "reject")));
                }
                if (stringValue(record, "status").equals("review")) {
                    actions.add(action("通过验收", () -> review(id, "accept")));
                    actions.add(action("退回验收并填写说明", () -> review(id, "reject")));
                }
            }
            actions.add(action("查看该工程的任务与材料", () -> { projectId = id; section = 1; page = 0; VersionClient.show(this); }));
            actions.add(action("在地图中查看工程", () -> { projectId = id; VersionClient.show(new TownMapScreen(this)); }));
        } else if (collection.equals("tasks") && writable) {
            String status = stringValue(record, "status");
            JsonObject project = project(stringValue(record, "projectId"));
            if (stringValue(project, "status").equals("active")) {
                if (status.equals("open") && has("task.claim")) actions.add(action("领取这项任务", () -> perform("task.claim", object("id", id))));
                if (status.equals("claimed") && has("task.claim") && stringValue(record, "assigneeId").equals(playerId())) actions.add(action("提交任务完成", () -> perform("task.submit", object("id", id))));
                if (status.equals("review") && canManageProject(project, "task.manage") && canConfirm(project)) actions.add(action("确认任务已完成", () -> perform("task.confirm", object("id", id))));
                if (List.of("claimed", "review").contains(status) && (has("task.claim") && stringValue(record, "assigneeId").equals(playerId()) || canManageProject(project, "task.manage") && canConfirm(project))) actions.add(action("释放任务，重新开放领取", () -> perform("task.release", object("id", id))));
            }
        } else if (collection.equals("materials")) {
            JsonObject project = project(stringValue(record, "projectId"));
            if (has("material.pledge") && writable && stringValue(project, "status").equals("active")) actions.add(action("认捐材料", () -> form("认捐 " + stringValue(record, "name"), List.of(number("quantity", "认捐数量", 1)), data -> { data.addProperty("materialId", id); perform("material.pledge", data); })));
            for (JsonElement element : array("pledges")) {
                JsonObject pledge = element.getAsJsonObject();
                if (!stringValue(pledge, "materialId").equals(id)) continue;
                String pledgeId = stringValue(pledge, "id");
                description.add(memberName(stringValue(pledge, "playerId", stringValue(pledge, "createdBy"))) + " · " + stringValue(pledge, "quantity") + " · " + state(stringValue(pledge, "status")));
                if (writable && stringValue(project, "status").equals("active")) {
                    if (stringValue(pledge, "status").equals("pledged") && has("material.pledge") && stringValue(pledge, "playerId").equals(playerId())) actions.add(action("我已交付 " + stringValue(pledge, "quantity") + " 份", () -> perform("pledge.submit", object("id", pledgeId))));
                    if (stringValue(pledge, "status").equals("submitted") && canManageProject(project, "material.manage") && canConfirm(project)) actions.add(action("确认 " + memberName(stringValue(pledge, "playerId")) + " 的交付", () -> perform("pledge.confirm", object("id", pledgeId))));
                    if (!stringValue(pledge, "status").equals("confirmed") && (has("material.pledge") && stringValue(pledge, "playerId").equals(playerId()) || canManageProject(project, "material.manage") && canConfirm(project))) actions.add(action("取消 " + memberName(stringValue(pledge, "playerId")) + " 的未确认认捐", () -> perform("pledge.cancel", object("id", pledgeId))));
                }
            }
        } else if (collection.equals("messages") && has("message.create") && writable) {
            if (stringValue(record, "createdBy").equals(playerId()) || has("project.approve")) actions.add(action("删除这条留言", () -> confirm("删除留言", "删除后无法恢复。", () -> perform("message.delete", object("id", id)))));
        } else if (collection.equals("facilities") && has("facility.manage") && writable) {
            actions.add(action("编辑设施说明", () -> facilityForm(record)));
        } else if (collection.equals("tickets") && has("ticket.manage") && writable) {
            actions.add(action("标为处理中", () -> perform("ticket.update", object("id", id, "status", "working"))));
            actions.add(action("填写处理结果并解决", () -> form("解决维修工单", List.of(text("resolution", "处理结果", stringValue(record, "resolution"))), data -> { data.addProperty("id", id); data.addProperty("status", "resolved"); perform("ticket.update", data); })));
        }
        if (record.has("dimension") && !archived()) actions.add(action("设为导航目标", () -> VersionClient.navigate(record)));
        VersionClient.show(new TownForm(this, stringValue(record, "name", stringValue(record, "title", "记录详情")), List.of(), description, actions, null));
    }

    private List<String> describe(String collection, JsonObject record) {
        List<String> result = new ArrayList<>();
        if (record.has("status")) result.add("状态：" + state(stringValue(record, "status")));
        String[][] labels = {{"purpose", "建筑用途"}, {"type", "类型"}, {"description", "说明"}, {"text", "留言"}, {"notes", "备注"}, {"expectedAt", "预计完工"}, {"instructions", "操作方式"}, {"rules", "使用规则"}, {"resolution", "处理结果"}, {"reviewReason", "审批说明"}};
        for (String[] label : labels) if (!stringValue(record, label[0]).isBlank()) result.add(label[1] + "：" + stringValue(record, label[0]));
        if (record.has("leaderId")) result.add("负责人：" + memberName(stringValue(record, "leaderId")));
        if (record.has("maintainerId")) result.add("维护人：" + memberName(stringValue(record, "maintainerId")));
        if (record.has("assigneeId")) result.add("领取人：" + memberName(stringValue(record, "assigneeId")));
        if (record.has("projectId")) result.add("所属工程：" + projectName(stringValue(record, "projectId")));
        if (record.has("chunks")) { JsonObject chunks = objectValue(record, "chunks"); result.add("区块：(" + stringValue(chunks, "minX") + ", " + stringValue(chunks, "minZ") + ") → (" + stringValue(chunks, "maxX") + ", " + stringValue(chunks, "maxZ") + ")"); }
        if (record.has("memberIds")) { List<String> names = new ArrayList<>(); for (JsonElement id : record.getAsJsonArray("memberIds")) names.add(memberName(id.getAsString())); result.add("项目组：" + String.join("、", names)); }
        if (record.has("required")) {
            int confirmed = 0, promised = 0;
            for (JsonElement element : array("pledges")) { JsonObject pledge = element.getAsJsonObject(); if (stringValue(pledge, "materialId").equals(stringValue(record, "id"))) { promised += integer(pledge, "quantity"); if (stringValue(pledge, "status").equals("confirmed")) confirmed += integer(pledge, "quantity"); } }
            result.add("需求 " + integer(record, "required") + " · 已确认 " + confirmed + " · 尚缺 " + Math.max(0, integer(record, "required") - confirmed) + " · 已认捐 " + promised);
        }
        if (record.has("visibility")) result.add("可见范围：" + (stringValue(record, "visibility").equals("project") ? "项目组与小镇管理" : "全镇"));
        if (record.has("dimension")) result.add("位置：" + stringValue(record, "dimension") + "  " + stringValue(record, "x") + ", " + stringValue(record, "y") + ", " + stringValue(record, "z"));
        if (record.has("createdBy")) result.add("登记人：" + stringValue(record, "createdByName", memberName(stringValue(record, "createdBy"))));
        if (!arrayValue(record, "overlapIds").isEmpty()) result.add("区域重叠提醒：与 " + arrayValue(record, "overlapIds").size() + " 个工程区块相交，请审批人核查。");
        if (record.has("createdAt")) result.add("登记时间：" + stringValue(record, "createdAt"));
        result.add("记录 ID：" + stringValue(record, "id"));
        return result;
    }

    private void projectForm(JsonObject record, JsonObject chunks) {
        boolean editing = record.has("id");
        List<TownForm.Field> fields = new ArrayList<>(List.of(text("name", "工程名称", stringValue(record, "name")), choice("type", "工程类型", stringValue(record, "type", "building"), new String[]{"building", "machine", "other"}, new String[]{"建筑", "机器", "其他"}), text("purpose", "建筑用途", stringValue(record, "purpose")), memberChoice("leaderId", "负责人", stringValue(record, "leaderId", playerId())), text("expectedAt", "预计完工时间（如 2026-11-01）", stringValue(record, "expectedAt")), text("notes", "备注", stringValue(record, "notes")), text("dimension", "维度", stringValue(record, "dimension", stringValue(chunks, "dimension", VersionClient.dimension()))), number("minX", "起始区块 X", integer(chunks, "minX")), number("minZ", "起始区块 Z", integer(chunks, "minZ")), number("maxX", "终止区块 X", integer(chunks, "maxX")), number("maxZ", "终止区块 Z", integer(chunks, "maxZ"))));
        if (editing && !canConfirm(record)) fields.removeIf(field -> field.key().equals("leaderId"));
        for (JsonElement element : array("members")) { JsonObject member = element.getAsJsonObject(); fields.add(toggle("projectMember_" + stringValue(member, "playerId"), "项目组 · " + stringValue(member, "name"), contains(arrayValue(record, "memberIds"), stringValue(member, "playerId")))); }
        form(editing ? "修改工程预约" : "登记建设预约", fields, data -> {
            JsonObject region = object("minX", integer(data, "minX"), "maxX", integer(data, "maxX"), "minZ", integer(data, "minZ"), "maxZ", integer(data, "maxZ"));
            for (String key : List.of("minX", "minZ", "maxX", "maxZ")) data.remove(key);
            data.add("chunks", region);
            JsonArray members = new JsonArray();
            for (TownForm.Field field : fields) if (field.key().startsWith("projectMember_")) { if (bool(data, field.key())) members.add(field.key().substring("projectMember_".length())); data.remove(field.key()); }
            data.add("memberIds", members);
            if (editing) data.addProperty("id", stringValue(record, "id")); else data.addProperty("worldId", gateway.worldId);
            perform(editing ? "project.update" : "project.create", data);
        });
    }

    private void taskForm() { requireProject("task.manage", () -> form("添加工程任务", List.of(projectChoice("task.manage"), text("title", "任务名称", ""), text("description", "任务要求", "")), data -> perform("task.create", data))); }
    private void materialForm() { requireProject("material.manage", () -> form("登记材料需求", List.of(projectChoice("material.manage"), text("name", "材料名称", ""), number("required", "总需求数量", 1)), data -> perform("material.create", data))); }
    private void messageForm() {
        List<TownForm.Field> fields = positionFields();
        fields.add(0, text("text", "留言内容", ""));
        fields.add(1, optionalProjectChoice());
        fields.add(2, choice("visibility", "可见范围", "town", new String[]{"town", "project"}, new String[]{"全镇", "仅项目组"}));
        fields.add(number("radius", "附近提示范围（方块，1–" + maximumRadius() + "）", Math.min(maximumRadius(), gateway.config().nearbyRadius)));
        form("留下空间留言", fields, data -> { if (stringValue(data, "projectId").isEmpty()) data.remove("projectId"); data.addProperty("worldId", gateway.worldId); perform("message.create", data); });
    }
    private void facilityForm(JsonObject record) {
        List<TownForm.Field> fields = positionFields(record);
        fields.add(0, text("name", "设施名称", stringValue(record, "name")));
        fields.add(1, text("instructions", "操作方式", stringValue(record, "instructions")));
        fields.add(2, text("rules", "使用规则", stringValue(record, "rules")));
        fields.add(3, memberChoice("maintainerId", "维护人", stringValue(record, "maintainerId", playerId())));
        fields.add(number("radius", "附近提示范围（方块，1–" + maximumRadius() + "）", record.has("radius") ? integer(record, "radius") : Math.min(maximumRadius(), gateway.config().nearbyRadius)));
        form(record.has("id") ? "编辑公共设施说明卡" : "登记公共设施", fields, data -> { data.addProperty("worldId", gateway.worldId); if (record.has("id")) data.addProperty("id", stringValue(record, "id")); perform(record.has("id") ? "facility.update" : "facility.create", data); });
    }
    private void ticketForm() {
        if (Minecraft.getInstance().player == null || !XaeroBridge.isCurrentWorld()) { gateway.status = "请先选择并绑定玩家当前世界与周目，再报告维修"; return; }
        List<TownForm.Field> fields = positionFields();
        fields.add(0, choice("category", "快捷故障类型", "机器堵塞", new String[]{"机器堵塞", "照明缺失", "线路断裂", "其他故障"}, new String[]{"机器堵塞", "照明缺失", "线路断裂", "其他故障"}));
        fields.add(1, text("title", "补充简述（可留空使用故障类型）", ""));
        fields.add(1, text("description", "故障详情", ""));
        fields.add(2, optionalProjectChoice());
        form("报告维修 · 已带上当前位置", fields, data -> { if (stringValue(data, "title").isBlank()) data.addProperty("title", stringValue(data, "category")); data.remove("category"); data.addProperty("worldId", gateway.worldId); if (stringValue(data, "projectId").isEmpty()) data.remove("projectId"); perform("ticket.create", data); });
    }
    private void quickTicket(String title) {
        if (Minecraft.getInstance().player == null || !XaeroBridge.isCurrentWorld()) { gateway.status = "请先选择并绑定玩家当前世界与周目，再报告维修"; return; }
        JsonObject payload = VersionClient.position();
        payload.addProperty("worldId", gateway.worldId); payload.addProperty("title", title);
        if (!projectId.isEmpty()) payload.addProperty("projectId", projectId);
        confirm("快速报修 · " + title, "周目：" + worldName(gateway.worldId) + "\n位置：" + stringValue(payload, "dimension") + " " + stringValue(payload, "x") + ", " + stringValue(payload, "y") + ", " + stringValue(payload, "z"), () -> perform("ticket.create", payload));
    }
    private List<TownForm.Field> positionFields() { return positionFields(VersionClient.position()); }
    private List<TownForm.Field> positionFields(JsonObject location) {
        return new ArrayList<>(List.of(text("dimension", "维度", stringValue(location, "dimension", VersionClient.dimension())), number("x", "X 坐标", integer(location, "x")), number("y", "Y 坐标", integer(location, "y")), number("z", "Z 坐标", integer(location, "z"))));
    }

    private void connectionForm() {
        VersionClient.show(VersionClient.createConfigScreen(this));
    }
    private void createTown() {
        form("注册小镇", List.of(text("name", "小镇名称", ""), text("introduction", "小镇介绍", ""), text("logo", "Logo：物品 ID 或 texture:命名空间:贴图路径.png", "minecraft:oak_sapling"), text("server", "所在服务器", VersionClient.serverAddress()), text("password", "加入密码（留空关闭，开启时至少 6 字）", "")), data -> TownForm.track(gateway.request("town.create", data).thenAccept(result -> Minecraft.getInstance().execute(() -> { gateway.selectTown(stringValue(result, "townId")); gateway.refreshTowns(); VersionClient.show(this); }))));
    }
    private void joinTown() {
        form("通过小镇 ID 加入", List.of(text("townId", "八位数字小镇 ID", ""), text("password", "小镇密码（未开启则留空）", "")), data -> TownForm.track(gateway.request("town.join", data).thenAccept(result -> Minecraft.getInstance().execute(() -> { gateway.selectTown(stringValue(result, "townId")); gateway.refreshTowns(); VersionClient.show(this); }))));
    }
    private void chooseTown() {
        List<TownForm.Action> actions = new ArrayList<>();
        for (JsonElement element : gateway.towns) { JsonObject town = element.getAsJsonObject(); actions.add(action(stringValue(town, "name") + " · " + stringValue(town, "id"), () -> { gateway.selectTown(stringValue(town, "id")); projectId = ""; homeScroll = 0; tab = 0; VersionClient.show(this); })); }
        VersionClient.show(new TownForm(this, "选择当前小镇", List.of(), List.of("加入多个小镇后，可随时切换。"), actions, null));
    }
    private void chooseWorld() {
        List<TownForm.Action> actions = new ArrayList<>();
        for (JsonElement element : array("worlds")) { JsonObject world = element.getAsJsonObject(); actions.add(action(stringValue(world, "name") + (bool(world, "archived") ? " · 历史" : " · 进行中"), () -> { gateway.selectWorld(stringValue(world, "id")); projectId = ""; VersionClient.show(this); })); }
        VersionClient.show(new TownForm(this, "选择世界与周目", List.of(), List.of("归档周目保存工程与业务标记，并按 Xaero 世界标识关联个人本地历史地图。"), actions, null));
    }
    private void chooseProject() {
        List<TownForm.Action> actions = new ArrayList<>();
        actions.add(action("全部工程", () -> { projectId = ""; page = 0; VersionClient.show(this); }));
        for (JsonElement element : array("projects")) { JsonObject project = element.getAsJsonObject(); actions.add(action(stringValue(project, "name") + " · " + state(stringValue(project, "status")), () -> { projectId = stringValue(project, "id"); page = 0; VersionClient.show(this); })); }
        VersionClient.show(new TownForm(this, "筛选工程", List.of(), List.of(), actions, null));
    }
    private void requireProject(String permission, Runnable continuation) {
        if (manageableProjects(permission).isEmpty()) { gateway.status = "暂无可维护的工程；请先登记工程或由负责人加入项目组"; return; }
        continuation.run();
    }
    private void review(String id, String decision) {
        form(decision.equals("reject") ? "驳回预约" : decision.equals("accept") ? "工程验收" : "批准预约", List.of(text("reason", "审批说明", "")), data -> { data.addProperty("id", id); data.addProperty("decision", decision); perform("project.review", data); });
    }

    private void memberDetail(JsonObject member) {
        String id = stringValue(member, "playerId");
        List<TownForm.Action> actions = new ArrayList<>();
        actions.add(action("复制玩家 UUID", () -> { VersionClient.copy(id); gateway.status = "已复制 UUID"; }));
        if (has("members.manage") && !stringValue(member, "roleId").equals("mayor")) {
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
        if (has("roles.manage") && !List.of("newcomer", "mayor").contains(stringValue(role, "id"))) {
            actions.add(action("逐项编辑权限", () -> roleForm(role)));
            if (!List.of("member", "manager").contains(stringValue(role, "id"))) actions.add(action("删除权限组", () -> confirm("删除权限组", "只有没有成员使用的权限组可删除。", () -> perform("role.delete", object("id", stringValue(role, "id"))))));
        }
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
        actions.add(action("切换并查看" + (bool(world, "archived") ? "历史" : "当前") + "地图资料", () -> { gateway.selectWorld(stringValue(world, "id")); projectId = ""; VersionClient.show(this); }));
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

    private TownForm.Field projectChoice(String permission) {
        JsonArray available = manageableProjects(permission);
        String selected = projectId;
        boolean found = false;
        for (JsonElement element : available) if (stringValue(element.getAsJsonObject(), "id").equals(selected)) found = true;
        if (!found && !available.isEmpty()) selected = stringValue(available.get(0).getAsJsonObject(), "id");
        return collectionChoice("projectId", "所属工程", available, "id", "name", selected, false);
    }
    private JsonArray manageableProjects(String permission) { JsonArray result = new JsonArray(); for (JsonElement element : array("projects")) { JsonObject project = element.getAsJsonObject(); if (canManageProject(project, permission) && !stringValue(project, "status").equals("completed")) result.add(project); } return result; }
    private int maximumRadius() { int maximum = integer(objectValue(gateway.snapshot, "limits"), "maximumProximityRadius"); return maximum > 0 ? maximum : 256; }
    private TownForm.Field optionalProjectChoice() { return collectionChoice("projectId", "关联工程", array("projects"), "id", "name", projectId, true); }
    private TownForm.Field roleChoice(String selected) {
        JsonArray roles = new JsonArray();
        for (JsonElement element : array("roles")) {
            JsonObject role = element.getAsJsonObject();
            if (stringValue(role, "id").equals("mayor")) continue;
            boolean allowed = true;
            for (JsonElement permission : arrayValue(role, "permissions")) if (!has(permission.getAsString())) allowed = false;
            if (allowed || isMayor()) roles.add(element);
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
        if (!data.has("townId")) data.addProperty("townId", gateway.townId);
        TownForm.track(gateway.request(action, data).thenAccept(ignored -> Minecraft.getInstance().execute(() -> { gateway.status = "操作已保存"; VersionClient.show(this); rebuild(); gateway.refresh(); })));
    }

    private Button button(String label, int x, int y, int width, Runnable run) { Button button = addRenderableWidget(TownStyle.button(label, x, y, width, run, label.startsWith("进入") || label.startsWith("＋"))); if (homeContent && (y < 72 || y + 20 > height - 35)) button.visible = false; return button; }
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
    private boolean canManageProject(JsonObject project, String permission) { return has(permission) && (has("project.approve") || stringValue(project, "leaderId").equals(playerId()) || contains(arrayValue(project, "memberIds"), playerId())); }
    private boolean canConfirm(JsonObject project) { return has("project.approve") || stringValue(project, "leaderId").equals(playerId()); }
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
        if (record.has("roleId")) return roleName(stringValue(record, "roleId")) + " · " + stringValue(record, "playerId");
        if (record.has("permissions")) return arrayValue(record, "permissions").size() + " 项权限";
        if (record.has("worldKey")) return stringValue(record, "serverAddress") + " · " + stringValue(record, "worldKey");
        if (record.has("required")) {
            int confirmed = 0;
            for (JsonElement element : array("pledges")) { JsonObject pledge = element.getAsJsonObject(); if (stringValue(pledge, "materialId").equals(stringValue(record, "id")) && stringValue(pledge, "status").equals("confirmed")) confirmed += integer(pledge, "quantity"); }
            return "尚缺 " + Math.max(0, integer(record, "required") - confirmed) + " / 需求 " + stringValue(record, "required") + " · " + projectName(stringValue(record, "projectId"));
        }
        if (record.has("projectId")) return projectName(stringValue(record, "projectId")) + " · " + memberName(stringValue(record, "createdBy"));
        if (record.has("purpose")) return stringValue(record, "purpose") + " · " + memberName(stringValue(record, "leaderId"));
        if (record.has("instructions")) return stringValue(record, "instructions");
        return stringValue(record, "dimension") + (record.has("x") ? " · " + stringValue(record, "x") + ", " + stringValue(record, "y") + ", " + stringValue(record, "z") : "");
    }
    static String state(String value) { return switch (value) { case "pending" -> "待审批"; case "active" -> "施工中"; case "rejected" -> "已驳回"; case "review" -> "待确认"; case "completed" -> "已完成"; case "open" -> "待领取 / 待处理"; case "claimed" -> "已领取"; case "pledged" -> "已认捐"; case "submitted" -> "待确认交付"; case "confirmed" -> "已确认交付"; case "working" -> "处理中"; case "resolved" -> "已解决"; case "archived" -> "历史归档"; default -> value; }; }

    static void background(GuiGraphicsExtractor graphics, int width, int height) { TownStyle.background(graphics, width, height); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { if (tab == 0) homeScroll = Math.max(0, Math.min(maximumHomeScroll, homeScroll - (int) vertical * 32)); else page += vertical < 0 ? 1 : -1; rebuild(); return true; }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        background(graphics, width, height);
        int headerX = contentLeft;
        double progress = gateway.config().animationEnabled ? Math.min(1, (System.nanoTime() - openedAt) / 260_000_000d) : 1;
        double eased = 1 - Math.pow(1 - progress, 3);
        TownStyle.panel(graphics, headerX, 10, 22, 22, true);
        TownStyle.icon(graphics, 0, headerX + 7, 17, ACCENT);
        graphics.text(font, "Yet Another Town", headerX + 31 + (int) ((1 - eased) * 6), 12, TEXT, true);
        graphics.text(font, "一起规划  /  一起建设", headerX + 31, 25, MUTED, false);
        graphics.fill(headerX, 69, headerX + contentWidth, 70, TownStyle.LINE);
        graphics.fill(headerX, height - 35, headerX + contentWidth, height - 34, TownStyle.LINE);
        graphics.enableScissor(contentLeft - 2, 72, contentLeft + contentWidth + 3, height - 35);
        for (Panel panel : panels) TownStyle.panel(graphics, panel.x(), panel.y(), panel.width(), panel.height(), panel.accented());
        for (Metric metric : metrics) {
            TownStyle.panel(graphics, metric.x(), metric.y(), metric.width(), 46, false);
            TownStyle.icon(graphics, metric.icon(), metric.x() + 10, metric.y() + 10, ACCENT);
            graphics.text(font, metric.value(), metric.x() + 25, metric.y() + 9, TEXT, true);
            graphics.text(font, metric.label(), metric.x() + 10, metric.y() + 28, MUTED, false);
        }
        for (Card card : cards) {
            TownStyle.panel(graphics, card.x(), card.y(), card.width(), 46, false);
            TownStyle.panel(graphics, card.x() + 9, card.y() + 10, 25, 25, false);
            TownStyle.icon(graphics, tab == 1 ? section + 1 : 3, card.x() + 17, card.y() + 18, ACCENT);
            int textWidth = card.width() - 128;
            boolean badge = !card.state().isEmpty() && card.width() >= 400;
            graphics.text(font, font.plainSubstrByWidth(card.title(), textWidth - (badge ? 80 : 0)), card.x() + 44, card.y() + 9, TEXT, true);
            graphics.text(font, font.plainSubstrByWidth((!badge && !card.state().isEmpty() ? card.state() + " · " : "") + card.subtitle(), textWidth), card.x() + 44, card.y() + 26, MUTED, false);
            if (badge) {
                int badgeWidth = Math.min(79, font.width(card.state()) + 12);
                int badgeX = card.x() + card.width() - 76 - badgeWidth;
                graphics.fill(badgeX, card.y() + 8, badgeX + badgeWidth, card.y() + 22, TownStyle.RAISED);
                graphics.text(font, card.state(), badgeX + 6, card.y() + 11, ACCENT, false);
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
            int trackHeight = Math.max(12, height - 125);
            int scrollbarX = contentLeft + contentWidth + 5;
            graphics.fill(scrollbarX, 78, scrollbarX + 2, height - 42, TownStyle.LINE);
            int thumbY = 78 + (int) ((trackHeight - 16) * (double) homeScroll / maximumHomeScroll);
            graphics.fill(scrollbarX, thumbY, scrollbarX + 2, thumbY + 16, ACCENT);
        }
        graphics.fill(contentLeft, height - 20, contentLeft + 3, height - 17, gateway.loggedIn() ? TownStyle.SUCCESS : MUTED);
        graphics.text(font, font.plainSubstrByWidth(gateway.status, contentWidth - 84), contentLeft + 9, height - 22, MUTED, false);
    }
}
