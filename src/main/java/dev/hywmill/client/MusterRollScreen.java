package dev.hywmill.client;

import dev.hywmill.net.RecruitPayloads;
import dev.hywmill.recruit.RecruitOffers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * The Muster Roll screen. Three tabs, each split into lists and subgroups:
 * <ul>
 *   <li>Soldiers: single hires (unit, gear tier, price, a quantity 1-32 and Hire), listed under Levies, Foot, Missile, Horse,
 *       Mercenaries and Engines;</li>
 *   <li>Squads (post-M5): the village culture's squads, hired whole, filtered by Foot, Missile, Horse (Raiders for peoples without
 *       horses) and Special, each list in subgroups (Spearmen, Archers, Heavy horse, ...) from low to high quality; one that cannot
 *       be hired shows why;</li>
 *   <li>Mercenaries (1.6): the companies to be found where a Muster Roll stands outside any village, by the land and the nearest
 *       village's people, filtered and grouped the same way.</li>
 * </ul>
 * The spawn radius (2-36) is the placer's. Your colours (post-M5): two dye colours the soldiers you hire here wear, cycled by
 * clicking (right-click goes back); "Village" leaves them in their look's or the village's colours. Everything shown comes from
 * the server; it decides and re-validates.
 */
public final class MusterRollScreen extends Screen {
    private static final int PARCHMENT = 0xE0F2E3C2;
    private static final int INK = 0xFF3B2A14;
    private static final int FADED = 0xFF7A6548;
    private static final int RED = 0xFF8A1E1E;
    private static final int HEADER = 0xFFE8D8A8;
    private static final int MAX_QTY = 32;
    private static final int MIN_RADIUS = 2, MAX_RADIUS = 36;
    private static final int LIST_TOP = 78, ROW = 22;
    private static final List<String> SOLDIER_GROUPS = List.of("Levies", "Foot", "Missile", "Horse", "Mercenaries", "Engines");
    private static final List<String> SQUAD_CATEGORIES = List.of("Foot", "Missile", "Horse", "Raiders", "Special");
    private static final List<String> SUBGROUPS = List.of("Levies", "Spearmen", "Sword and shield", "Heavy foot", "Archers", "Crossbowmen",
            "Light horse", "Horse archers", "Heavy horse", "Combined arms", "Others");
    private static final List<String> QUALITIES = List.of("LOW", "MEDIUM", "HIGH");

    private enum Tab { SOLDIERS, SQUADS, COMPANIES }

    /** A row of the list: a subgroup heading, or the index of an offer, squad or company. */
    private record Row(String header, int index) {}

    private RecruitPayloads.View view;
    private List<RecruitPayloads.SquadView> squads;
    private List<RecruitPayloads.SquadView> companies;
    private String wilds;
    private Tab tab = Tab.SOLDIERS;
    private final java.util.EnumMap<Tab, String> filter = new java.util.EnumMap<>(Tab.class);
    private final java.util.EnumMap<Tab, Integer> selected = new java.util.EnumMap<>(Tab.class);
    private int qty = 1;
    private int scroll;
    private final List<int[]> headings = new java.util.ArrayList<>();   // {y} of each heading drawn this frame
    private final List<String> headingText = new java.util.ArrayList<>();
    private String lastResult = "";
    private boolean lastOk = true;

    public MusterRollScreen(RecruitPayloads.View view) {
        super(Component.literal("Muster Roll"));
        this.view = view;
        RecruitPayloads.Squads pending = RecruitClient.pendingFor(view.pos());
        this.squads = pending == null ? List.of() : pending.squads();
        this.companies = pending == null ? List.of() : pending.companies();
        this.wilds = pending == null ? "" : pending.wilds();
        pickTab();
    }

    public BlockPos pos() {
        return view.pos();
    }

    public void update(RecruitPayloads.View v) {
        this.view = v;
        rebuildWidgets();
    }

    public void squads(RecruitPayloads.Squads s) {
        boolean first = squads.isEmpty() && companies.isEmpty();
        this.squads = s.squads();
        this.companies = s.companies();
        this.wilds = s.wilds();
        if (first) {
            pickTab();
        }
        rebuildWidgets();
    }

    /** Outside any village only the mercenary companies are on offer: open on them. */
    private void pickTab() {
        if (view.offers().isEmpty() && squads.isEmpty() && !companies.isEmpty()) {
            tab = Tab.COMPANIES;
        }
    }

    public void result(RecruitPayloads.Result r) {
        lastResult = r.message();
        lastOk = r.ok();
    }

    private int rows() {
        return Math.max(1, (height - LIST_TOP - 70) / ROW);
    }

    private List<RecruitPayloads.SquadView> squadList() {
        return tab == Tab.COMPANIES ? companies : squads;
    }

    private int sel() {
        return selected.getOrDefault(tab, -1);
    }

    private String filterOf() {
        return filter.getOrDefault(tab, "All");
    }

    /** The filters of the current tab: All, then each list that has something in it. */
    private List<String> filters() {
        List<String> out = new java.util.ArrayList<>(List.of("All"));
        if (tab == Tab.SOLDIERS) {
            for (String g : SOLDIER_GROUPS) {
                if (view.offers().stream().anyMatch(o -> o.group().equals(g))) {
                    out.add(g);
                }
            }
        } else {
            for (String c : SQUAD_CATEGORIES) {
                if (squadList().stream().anyMatch(q -> q.category().equals(c))) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** The rows of the current tab under its filter: headings and entries, grouped and ordered. */
    private List<Row> listRows() {
        List<Row> out = new java.util.ArrayList<>();
        String f = filterOf();
        if (tab == Tab.SOLDIERS) {
            for (String g : SOLDIER_GROUPS) {
                if (!f.equals("All") && !f.equals(g)) {
                    continue;
                }
                List<Integer> idx = new java.util.ArrayList<>();
                for (int i = 0; i < view.offers().size(); i++) {
                    if (view.offers().get(i).group().equals(g) || (g.equals("Foot") && view.offers().get(i).group().isEmpty())) {
                        idx.add(i);
                    }
                }
                if (!idx.isEmpty()) {
                    out.add(new Row(g, -1));
                    idx.forEach(i -> out.add(new Row("", i)));
                }
            }
            return out;
        }
        List<RecruitPayloads.SquadView> list = squadList();
        for (String c : SQUAD_CATEGORIES) {
            if (!f.equals("All") && !f.equals(c)) {
                continue;
            }
            for (String g : SUBGROUPS) {
                List<Integer> idx = new java.util.ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    RecruitPayloads.SquadView q = list.get(i);
                    String qg = SUBGROUPS.contains(q.group()) ? q.group() : "Others";
                    if (q.category().equals(c) && qg.equals(g)) {
                        idx.add(i);
                    }
                }
                if (idx.isEmpty()) {
                    continue;
                }
                idx.sort(java.util.Comparator.comparingInt((Integer i) -> QUALITIES.indexOf(list.get(i).quality()))
                        .thenComparingInt(i -> list.get(i).price()));
                out.add(new Row((f.equals("All") ? c + ": " : "") + g, -1));
                idx.forEach(i -> out.add(new Row("", i)));
            }
        }
        return out;
    }

    @Override
    protected void init() {
        int left = 20;
        String[] names = {"Soldiers (" + view.offers().size() + ")", "Squads (" + squads.size() + ")", "Mercenaries (" + companies.size() + ")"};
        for (Tab t : Tab.values()) {
            addRenderableWidget(Button.builder(Component.literal((tab == t ? "> " : "") + names[t.ordinal()]), b -> tab(t))
                    .bounds(left + t.ordinal() * 102, 32, 100, 18).build());
        }
        List<String> fs = filters();
        int fw = Math.max(36, Math.min(60, 270 / Math.max(1, fs.size()) - 2));
        for (int i = 0; i < fs.size(); i++) {
            String f = fs.get(i);
            addRenderableWidget(Button.builder(Component.literal((filterOf().equals(f) ? "> " : "") + f), b -> {
                filter.put(tab, f);
                scroll = 0;
                rebuildWidgets();
            }).bounds(left + i * (fw + 2), 54, fw, 18).build());
        }
        List<Row> rows = listRows();
        int visible = rows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        headings.clear();
        headingText.clear();
        if (sel() < 0) {
            rows.stream().filter(r -> r.index() >= 0).findFirst().ifPresent(r -> selected.put(tab, r.index()));
        }
        for (int i = scroll; i < Math.min(rows.size(), scroll + visible); i++) {
            Row r = rows.get(i);
            int y = LIST_TOP + (i - scroll) * ROW;
            if (r.index() < 0) {
                headings.add(new int[]{y});
                headingText.add(r.header());
                continue;
            }
            int idx = r.index();
            String label;
            if (tab == Tab.SOLDIERS) {
                RecruitPayloads.OfferView o = view.offers().get(idx);
                label = (idx == sel() ? "> " : "") + o.label() + " - " + RecruitOffers.money(o.price());
            } else {
                RecruitPayloads.SquadView q = squadList().get(idx);
                label = (idx == sel() ? "> " : "") + (q.refusal().isEmpty() ? "" : "x ") + q.name() + " (" + quality(q.quality()) + ") - "
                        + RecruitOffers.money(q.price());
            }
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                selected.put(tab, idx);
                rebuildWidgets();
            }).bounds(left + 8, y, 262, 20).build());
        }
        if (rows.size() > visible) {
            addRenderableWidget(Button.builder(Component.literal("▲"), b -> { scroll--; rebuildWidgets(); }).bounds(left + 274, LIST_TOP, 16, 18).build());
            addRenderableWidget(Button.builder(Component.literal("▼"), b -> { scroll++; rebuildWidgets(); })
                    .bounds(left + 274, LIST_TOP + (visible - 1) * ROW, 16, 18).build());
        }
        int x = width - 230, y = height - 110;
        if (tab != Tab.SOLDIERS) {
            int i = sel();
            if (i >= 0 && i < squadList().size() && squadList().get(i).refusal().isEmpty()) {
                addRenderableWidget(Button.builder(Component.literal(tab == Tab.COMPANIES ? "Hire this company" : "Hire this squad"),
                        b -> hireSquad()).bounds(x, y + 24, 200, 20).build());
            }
        } else if (!view.offers().isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("-8"), b -> setQty(qty - 8)).bounds(x, y, 30, 20).build());
            addRenderableWidget(Button.builder(Component.literal("-1"), b -> setQty(qty - 1)).bounds(x + 32, y, 30, 20).build());
            addRenderableWidget(Button.builder(Component.literal("+1"), b -> setQty(qty + 1)).bounds(x + 138, y, 30, 20).build());
            addRenderableWidget(Button.builder(Component.literal("+8"), b -> setQty(qty + 8)).bounds(x + 170, y, 30, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Hire"), b -> hire()).bounds(x, y + 24, 200, 20).build());
        }
        if (view.owner()) {
            int ry = height - 58;
            addRenderableWidget(Button.builder(Component.literal("-"), b -> radius(view.radius() - 1)).bounds(x, ry, 30, 20).build());
            addRenderableWidget(Button.builder(Component.literal("+"), b -> radius(view.radius() + 1)).bounds(x + 170, ry, 30, 20).build());
        }
        addRenderableWidget(new ColourButton(left + 76, height - 50, 0));
        addRenderableWidget(new ColourButton(left + 166, height - 50, 1));
        if (view.colour1() >= 0) {
            addRenderableWidget(Button.builder(Component.literal("x"), b -> PacketDistributor.sendToServer(new RecruitPayloads.SetColours(view.pos(), -1, -1)))
                    .bounds(left + 256, height - 50, 16, 20).tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.literal("Clear: hired soldiers wear their look's or the village's colours"))).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 - 40, height - 26, 80, 20).build());
    }

    private static String quality(String q) {
        return switch (q) {
            case "LOW" -> "low";
            case "MEDIUM" -> "medium";
            default -> "high";
        };
    }

    /** One of the two colour pickers: click for the next dye colour, right-click for the previous; "Village" is none chosen. */
    private final class ColourButton extends Button {
        private final int which;

        ColourButton(int x, int y, int which) {
            super(x, y, 86, 20, Component.literal(label(which == 0 ? view.colour1() : view.colour2())), b -> {}, DEFAULT_NARRATION);
            this.which = which;
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!active || !visible || !isMouseOver(mx, my) || (button != 0 && button != 1)) {
                return false;
            }
            playDownSound(net.minecraft.client.Minecraft.getInstance().getSoundManager());
            int c1 = view.colour1(), c2 = view.colour2();
            if (c1 < 0 || c2 < 0) {
                c1 = 14; // red and white to start from
                c2 = 0;
            }
            int step = button == 0 ? 1 : 15;
            if (which == 0) {
                c1 = (c1 + step) % 16;
            } else {
                c2 = (c2 + step) % 16;
            }
            PacketDistributor.sendToServer(new RecruitPayloads.SetColours(view.pos(), c1, c2));
            return true;
        }
    }

    private static String label(int dye) {
        if (dye < 0) {
            return "Village";
        }
        String n = net.minecraft.world.item.DyeColor.byId(dye).getName().replace('_', ' ');
        return "   " + Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private void tab(Tab t) {
        if (tab != t) {
            tab = t;
            scroll = 0;
            rebuildWidgets();
        }
    }

    private void setQty(int q) {
        qty = Math.max(1, Math.min(MAX_QTY, q));
    }

    private void hire() {
        int i = sel();
        if (i >= 0 && i < view.offers().size()) {
            PacketDistributor.sendToServer(new RecruitPayloads.Hire(view.pos(), view.offers().get(i).key(), qty));
        }
    }

    private void hireSquad() {
        int i = sel();
        if (i >= 0 && i < squadList().size()) {
            PacketDistributor.sendToServer(new RecruitPayloads.Hire(view.pos(), (tab == Tab.COMPANIES ? "company:" : "squad:") + squadList().get(i).id(), 1));
        }
    }

    private void radius(int r) {
        PacketDistributor.sendToServer(new RecruitPayloads.SetRadius(view.pos(), Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, r))));
    }

    /** The vanilla (1.21) blurred background, then the parchment panel on top of it. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.renderBackground(g, mouseX, mouseY, partial);
        g.fill(width - 240, 34, width - 16, height - 32, PARCHMENT);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial); // background and widgets first, text on top
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        int y = 20;
        for (var line : font.split(Component.literal(view.header()), width - 40)) {
            g.drawString(font, line, 20, y, 0xFFFFFFFF, false);
            y += 10;
        }
        for (int i = 0; i < headings.size(); i++) {
            int hy = headings.get(i)[0];
            g.fill(20, hy + 2, 290, hy + 18, 0x80000000);
            g.drawString(font, headingText.get(i), 26, hy + 6, HEADER, false);
        }
        int x = width - 230, py = 40;
        g.drawString(font, "Your money: " + RecruitOffers.money(view.money()), x, py, INK, false);
        py += 14;
        if (tab != Tab.SOLDIERS) {
            renderSquad(g, x, py);
        } else if (view.offers().isEmpty()) {
            g.drawString(font, "Nothing on offer to you here.", x, py, FADED, false);
        } else if (sel() >= 0 && sel() < view.offers().size()) {
            RecruitPayloads.OfferView o = view.offers().get(sel());
            g.drawString(font, o.label(), x, py, INK, false);
            g.drawString(font, "Gear: " + o.gear().toLowerCase().replace('_', ' '), x, py + 12, FADED, false);
            g.drawString(font, "Each: " + RecruitOffers.money(o.price()), x, py + 24, FADED, false);
            g.drawString(font, "Total: " + RecruitOffers.money((long) o.price() * qty), x, py + 36, (long) o.price() * qty > view.money()
                    ? RED : INK, false);
            g.drawCenteredString(font, "x " + qty, x + 100, height - 104, INK);
        }
        g.drawString(font, "Muster radius: " + view.radius() + " blocks" + (view.owner() ? "" : " (set by the placer)"), x,
                view.owner() ? height - 72 : height - 58, INK, false);
        if (view.owner()) {
            g.drawCenteredString(font, view.radius() + "", x + 100, height - 52, INK);
        }
        // your colours: label, a swatch on each picker, and a reset
        g.drawString(font, "Your colours:", 20, height - 44, 0xFFFFFFFF, false);
        if (view.colour1() >= 0) {
            for (int i = 0; i < 2; i++) {
                int dye = i == 0 ? view.colour1() : view.colour2();
                int bx = 20 + 76 + i * 90 + 4, by = height - 50 + 5;
                g.fill(bx - 1, by - 1, bx + 11, by + 11, 0xFF000000);
                g.fill(bx, by, bx + 10, by + 10, 0xFF000000 | (net.minecraft.world.item.DyeColor.byId(dye).getTextureDiffuseColor() & 0xFFFFFF));
            }
        }
        if (!lastResult.isEmpty()) {
            int ry = height - 46 - 10 * Math.max(0, font.split(Component.literal(lastResult), 210).size() - 1);
            for (var line : font.split(Component.literal(lastResult), 210)) {
                g.drawString(font, line, x, ry - 30, lastOk ? 0xFF2E5E1E : RED, false);
                ry += 10;
            }
        }
    }

    private void renderSquad(GuiGraphics g, int x, int py) {
        List<RecruitPayloads.SquadView> list = squadList();
        if (list.isEmpty()) {
            String none = tab == Tab.COMPANIES ? (wilds.isEmpty() ? "Mercenary companies are hired in the wilds: set a Muster Roll down"
                    + " outside any village." : "No company is to be found here.") : "No squads are raised here.";
            for (var line : font.split(Component.literal(none), 210)) {
                g.drawString(font, line, x, py, FADED, false);
                py += 10;
            }
            return;
        }
        if (sel() < 0 || sel() >= list.size()) {
            return;
        }
        RecruitPayloads.SquadView s = list.get(sel());
        g.drawString(font, s.name(), x, py, INK, false);
        py += 12;
        g.drawString(font, s.category() + " (" + s.group().toLowerCase() + "), " + quality(s.quality()) + " quality, " + s.size() + " soldiers",
                x, py, FADED, false);
        py += 12;
        if (!s.origin().isEmpty()) {
            for (var line : font.split(Component.literal(s.origin()), 210)) {
                g.drawString(font, line, x, py, FADED, false);
                py += 10;
            }
        }
        for (var line : font.split(Component.literal(s.roster()), 210)) {
            g.drawString(font, line, x, py, INK, false);
            py += 10;
        }
        g.drawString(font, "Best gear: " + s.gear().toLowerCase().replace('_', ' '), x, py, FADED, false);
        py += 12;
        for (var line : font.split(Component.literal(s.description()), 210)) {
            if (py > height - 128) {
                break;
            }
            g.drawString(font, line, x, py, FADED, false);
            py += 10;
        }
        py += 2;
        g.drawString(font, "Price: " + RecruitOffers.money(s.price()), x, py, s.price() > view.money() ? RED : INK, false);
        if (!s.refusal().isEmpty()) {
            g.drawString(font, "Cannot hire: " + s.refusal(), x, height - 104, RED, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
