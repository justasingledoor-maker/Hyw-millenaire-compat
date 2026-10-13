package dev.hywmill.client;

import dev.hywmill.net.PoliticsPayloads;
import dev.hywmill.net.PoliticsSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * The Politics / Diplomacy screen (M5-UI). It renders the server's {@link PoliticsSnapshot} and sends
 * intents: selecting a village and choosing an offered action. Every availability, cost and outcome shown
 * comes from the server; the screen holds no political logic.
 */
public final class PoliticsScreen extends Screen {
    /** 1.6: the village list: rows from here down, each a name button with the distance and bearing beside it. */
    private static final int LIST_TOP = 66, ROW = 20, NAME_X = 24;
    /**
     * 1.8: the layout follows the window: the village list's right edge, its name buttons' width, the centre panel's edges
     * and the action column's left edge and width (narrower on small screens or a large GUI scale).
     */
    private int listRight = 180, nameW = 98, x0 = 190, x1, ax, actionW = 170;
    private static final int PARCHMENT = 0xE0F2E3C2;
    private static final int INK = 0xFF3B2A14;
    private static final int FADED = 0xFF7A6548;
    private PoliticsSnapshot snap;
    private int scroll;
    /**
     * The centre panel's tabs: the overview of the village you deal with; its realm (treaties, wars, the known realms); the
     * war (scout reports: columns on the road, convoys); 1.8 your service in a realm (orders, calls to arms, officials); the
     * history (vassal ties, battle reports). The text tabs scroll by line, the War tab by report.
     */
    enum Tab {
        OVERVIEW("Overview", "The village you deal with: your standing, word, chronicle"),
        REALM("Realm", "The selected village's realm, treaties and wars; the known realms"),
        WAR("War", "Your scouts' reports: columns on the road, convoys"),
        SERVICE("Service", "Your service in a realm: orders, calls to arms, its officials"),
        HISTORY("History", "Vassal ties and battle reports");

        final String label, tip;

        Tab(String label, String tip) {
            this.label = label;
            this.tip = tip;
        }
    }

    private Tab tab = Tab.OVERVIEW;
    private int textScroll, warScroll, actionScroll;
    private static final int WAR_ROW = 34, ACTION_ROW = 22;
    /** 1.9: the War tab's reports start under the assault bar; the bar's choices (rally side, minutes' index, help) are kept. */
    private int warTop = 34;
    private static int assaultSide, assaultMinutes = 1;
    private static boolean assaultHelp = true;
    private String lastResult = "";
    private boolean lastOk = true;
    /** 1.6: the search box's text, the sort (nearest first, else by name), the list's widgets and the rows they show. */
    private String query = "";
    private boolean byName;
    private net.minecraft.client.gui.components.EditBox search;
    private final List<net.minecraft.client.gui.components.AbstractWidget> rowWidgets = new java.util.ArrayList<>();
    private List<PoliticsSnapshot.VillageRow> shown = List.of();

    public PoliticsScreen(PoliticsSnapshot snap) {
        super(Component.translatable("screen.hywmill.politics"));
        this.snap = snap;
    }

    public void update(PoliticsSnapshot s) {
        this.snap = s;
        boolean typing = search != null && search.isFocused();
        rebuildWidgets();
        if (typing) {
            setFocused(search);
        }
    }

    private int rows() {
        return Math.max(4, (height - LIST_TOP - 40) / ROW);
    }

    /** The villages the search matches (name, people, standing or place), nearest first or by name; the home village first. */
    private List<PoliticsSnapshot.VillageRow> filtered() {
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        java.util.Comparator<PoliticsSnapshot.VillageRow> order = byName
                ? java.util.Comparator.comparing(v -> v.name().toLowerCase(java.util.Locale.ROOT))
                : java.util.Comparator.comparingInt(v -> v.distance() < 0 ? Integer.MAX_VALUE : v.distance());
        return snap.villages().stream()
                .filter(v -> q.isEmpty() || (v.name() + " " + v.culture() + " " + v.standing() + " " + v.tag()).toLowerCase(java.util.Locale.ROOT)
                        .contains(q))
                .sorted(java.util.Comparator.comparing((PoliticsSnapshot.VillageRow v) -> !v.id().equals(snap.home())).thenComparing(order))
                .toList();
    }

    private static String distance(PoliticsSnapshot.VillageRow v) {
        if (v.distance() < 0) {
            return "";
        }
        String d = v.distance() >= 1000 ? String.format("%.1fkm", v.distance() / 1000.0) : v.distance() + "m";
        return d + (v.bearing().isEmpty() ? "" : " " + v.bearing());
    }

    /** (Re)builds only the list's rows, so the search box keeps its focus while typing. */
    private void layoutRows() {
        rowWidgets.forEach(this::removeWidget);
        rowWidgets.clear();
        List<PoliticsSnapshot.VillageRow> all = filtered();
        int rows = rows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, all.size() - rows)));
        shown = all.subList(scroll, Math.min(all.size(), scroll + rows));
        for (int i = 0; i < shown.size(); i++) {
            PoliticsSnapshot.VillageRow v = shown.get(i);
            boolean home = v.id().equals(snap.home());
            String rel = v.relation() == PoliticsSnapshot.VillageRow.NO_RELATION ? "" : "\nRelation of " + snap.homeName() + " to it: " + v.relation();
            String from = snap.home() == null ? "you" : snap.homeName();
            String label = (v.id().equals(snap.selected()) ? "> " : "") + v.name();
            if (font.width(label) > nameW - 8) {
                label = font.plainSubstrByWidth(label, nameW - 14) + "..";
            }
            Button b = Button.builder(Component.literal(label), btn -> select(v.id()))
                    .bounds(NAME_X, LIST_TOP + i * ROW, nameW, 18)
                    .tooltip(Tooltip.create(Component.literal(v.name() + (v.culture().isEmpty() ? "" : " (" + v.culture() + ")")
                            + (home ? "\nYou are here" : v.distance() < 0 ? "" : "\n" + distance(v) + " from " + from)
                            + "\nYour standing: " + v.standing().toLowerCase()
                            + (v.tag().isEmpty() ? "" : "\n" + Character.toUpperCase(v.tag().charAt(0)) + v.tag().substring(1))
                            + (v.truce() ? "\nUnder truce with " + snap.homeName() : "") + rel))).build();
            rowWidgets.add(addRenderableWidget(b));
        }
    }

    public void result(PoliticsPayloads.ActionResult r) {
        lastResult = r.message();
        lastOk = r.ok();
    }

    @Override
    protected void init() {
        listRight = Math.max(130, Math.min(180, (int) (width * 0.28)));
        nameW = listRight - NAME_X - 58;
        actionW = Math.max(118, Math.min(170, (int) (width * 0.27)));
        ax = width - actionW - 12;
        x0 = listRight + 10;
        x1 = ax - 10;
        // 1.6: a search box over the village list, the sort and the list's scroll beside it
        search = new net.minecraft.client.gui.components.EditBox(font, 12, 34, listRight - 78, 16, Component.literal("Search villages"));
        search.setHint(Component.literal("Search...").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        search.setMaxLength(40);
        search.setValue(query);
        search.setResponder(t -> {
            if (!t.equals(query)) {
                query = t;
                scroll = 0;
                layoutRows();
            }
        });
        search.setTooltip(Tooltip.create(Component.literal("Search by name, people (culture), standing or place (ally, vassal...)")));
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal(byName ? "A-Z" : "Near"), b -> {
            byName = !byName;
            scroll = 0;
            b.setMessage(Component.literal(byName ? "A-Z" : "Near"));
            layoutRows();
        }).bounds(listRight - 64, 33, 30, 18).tooltip(Tooltip.create(Component.literal("Sort: nearest first, or by name"))).build());
        addRenderableWidget(Button.builder(Component.literal("\u25B2"), b -> { scroll = Math.max(0, scroll - rows() + 1); layoutRows(); })
                .bounds(listRight - 32, 33, 16, 18).build());
        addRenderableWidget(Button.builder(Component.literal("\u25BC"), b -> { scroll += rows() - 1; layoutRows(); })
                .bounds(listRight - 16, 33, 16, 18).build());
        layoutRows();
        // the actions on the right: the service's on the Service tab, the rest elsewhere; a scroll when they do not fit
        List<PoliticsSnapshot.ActionRow> acts = actions();
        int ay = 34, fitActions = Math.max(1, (height - 34 - ay) / ACTION_ROW);
        actionScroll = Math.max(0, Math.min(actionScroll, Math.max(0, acts.size() - fitActions)));
        for (int i = 0; i < fitActions && actionScroll + i < acts.size(); i++) {
            PoliticsSnapshot.ActionRow a = acts.get(actionScroll + i);
            String label = a.label();
            if (font.width(label) > actionW - 8) {
                label = font.plainSubstrByWidth(label, actionW - 14) + "..";
            }
            Button b = Button.builder(Component.literal(label), btn -> submit(a.action()))
                    .bounds(ax, ay + i * ACTION_ROW, actionW, 20)
                    .tooltip(Tooltip.create(Component.literal(a.label() + (a.requirement().isEmpty() ? "" : "\n" + a.requirement())
                            + (a.outcome().isEmpty() ? "" : "\nOutcome: " + a.outcome())))).build();
            b.active = a.available() && snap.home() != null;
            addRenderableWidget(b);
        }
        if (acts.size() > fitActions) {
            addRenderableWidget(Button.builder(Component.literal("\u25B2"), b -> { actionScroll = Math.max(0, actionScroll - fitActions + 1); rebuildWidgets(); })
                    .bounds(ax, height - 28, 16, 16).build());
            addRenderableWidget(Button.builder(Component.literal("\u25BC"), b -> { actionScroll += fitActions - 1; rebuildWidgets(); })
                    .bounds(ax + 18, height - 28, 16, 16).build());
        }
        // the tabs over the centre panel: the open one in gold
        long openCount = snap.intel().stream().filter(PoliticsSnapshot.IntelRow::open).count();
        int tx = x0;
        for (Tab t : Tab.values()) {
            String label = t == Tab.WAR && openCount > 0 ? t.label + " (" + openCount + ")" : t == Tab.SERVICE && !snap.service().isEmpty()
                    && snap.service().stream().anyMatch(l -> l.startsWith("Orders")) ? t.label + " !" : t.label;
            Component c = t == tab ? Component.literal(label).withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.UNDERLINE)
                    : Component.literal(label);
            int tw = font.width(label) + 12;
            addRenderableWidget(Button.builder(c, b -> tab(t)).bounds(tx, 12, tw, 16)
                    .tooltip(Tooltip.create(Component.literal(t.tip + "\nKey " + (t.ordinal() + 1)))).build());
            tx += tw + 2;
        }
        boolean war = tab == Tab.WAR;
        if (war) {
            warTop = assaultBar(x0, x1, 32) + 4;
            List<PoliticsSnapshot.IntelRow> rows = snap.intel();
            int fit = Math.max(1, (height - 44 - warTop) / WAR_ROW);
            warScroll = Math.max(0, Math.min(warScroll, Math.max(0, rows.size() - fit)));
            int bx = x1 - 72;
            for (int k = 0; k < fit && warScroll + k < rows.size(); k++) {
                PoliticsSnapshot.IntelRow row = rows.get(warScroll + k);
                int ry = warTop + k * WAR_ROW;
                if (row.canTake()) {
                    addRenderableWidget(Button.builder(Component.literal("Intercept"), b -> submitWar("INTERCEPT", row.id()))
                            .bounds(bx, ry, 72, 14).tooltip(Tooltip.create(Component.literal("Take the job: ride out and cut it down."
                                    + " Your village's council will leave it to you."))).build());
                }
                if (row.canBribe()) {
                    addRenderableWidget(Button.builder(Component.literal("Bribe " + row.price() + "d"), b -> submitWar("BRIBE", row.id()))
                            .bounds(bx, ry + 16, 72, 14).tooltip(Tooltip.create(Component.literal("Pay " + row.price()
                                    + " deniers: the company rides for your side instead."))).build());
                }
            }
            if (rows.size() > fit) {
                addRenderableWidget(Button.builder(Component.literal("\u25B2"), b -> { warScroll = Math.max(0, warScroll - 1); rebuildWidgets(); })
                        .bounds(x1 - 34, height - 28, 16, 16).build());
                addRenderableWidget(Button.builder(Component.literal("\u25BC"), b -> { warScroll++; rebuildWidgets(); })
                        .bounds(x1 - 16, height - 28, 16, 16).build());
            }
        }
        if (tab == Tab.HISTORY || tab == Tab.REALM || tab == Tab.SERVICE) {
            addRenderableWidget(Button.builder(Component.literal("\u25B2"), b -> { textScroll = Math.max(0, textScroll - 5); })
                    .bounds(x1 - 34, height - 28, 16, 16).build());
            addRenderableWidget(Button.builder(Component.literal("\u25BC"), b -> { textScroll += 5; })
                    .bounds(x1 - 16, height - 28, 16, 16).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 - 40, height - 26, 80, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (mx < listRight + 2 && my >= LIST_TOP) {
            scroll = Math.max(0, scroll - (int) Math.signum(dy) * 2);
            layoutRows();
            return true;
        }
        if (mx >= x0 && mx <= x1) {
            if (tab == Tab.WAR) {
                warScroll = Math.max(0, warScroll - (int) Math.signum(dy));
                rebuildWidgets();
                return true;
            }
            if (tab != Tab.OVERVIEW) {
                textScroll = Math.max(0, textScroll - (int) Math.signum(dy) * 2);
                return true;
            }
        }
        if (mx >= ax - 2) {
            actionScroll = Math.max(0, actionScroll - (int) Math.signum(dy));
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    /** 1.8: keys 1-5 open the tabs (unless typing in the search box). */
    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((search == null || !search.isFocused()) && key >= org.lwjgl.glfw.GLFW.GLFW_KEY_1 && key < org.lwjgl.glfw.GLFW.GLFW_KEY_1 + Tab.values().length) {
            tab(Tab.values()[key - org.lwjgl.glfw.GLFW.GLFW_KEY_1]);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private void tab(Tab t) {
        if (t != tab) {
            textScroll = 0;
            actionScroll = 0;
        }
        tab = t;
        rebuildWidgets();
    }

    /** 1.8: the service's actions (entering, leaving, bribes, counsel) on the Service tab; the others elsewhere. */
    private List<PoliticsSnapshot.ActionRow> actions() {
        return snap.actions().stream().filter(a -> serviceAction(a.action()) == (tab == Tab.SERVICE)).toList();
    }

    private static boolean serviceAction(String a) {
        return a.startsWith("SERVE_") || a.startsWith("BRIBE_") || a.startsWith("COUNSEL_");
    }

    /**
     * Post-M5: the History tab (vassal ties and battle reports), the Realm tab or (1.8) the Service tab (unindented lines are
     * headings), wrapped, from the scroll position.
     */
    private void renderText(GuiGraphics g, int x0, int x1) {
        List<String> lines = new java.util.ArrayList<>();
        boolean realm = tab != Tab.HISTORY;
        for (String h : tab == Tab.REALM ? snap.realm() : tab == Tab.SERVICE ? snap.service() : snap.history()) {
            if (h.isEmpty()) {
                lines.add("");
                continue;
            }
            for (var l : font.split(Component.literal(h), x1 - x0)) {
                StringBuilder sb = new StringBuilder();
                l.accept((i, style, cp) -> { sb.appendCodePoint(cp); return true; });
                boolean head = realm ? !h.startsWith(" ") : h.startsWith("Day ") || h.startsWith("Vassalage") || h.startsWith("Province");
                lines.add((head ? "\u0001" : "") + sb);
            }
        }
        textScroll = Math.min(textScroll, Math.max(0, lines.size() - 1));
        int y = 34, bottom = height - (lastResult.isEmpty() ? 44 : 64);
        for (int i = textScroll; i < lines.size() && y < bottom; i++) {
            String l = lines.get(i);
            boolean head = l.startsWith("\u0001");
            if (head && i > textScroll) {
                y += 3; // a little air before each heading
            }
            g.drawString(font, head ? l.substring(1) : l, x0, y, head ? INK : FADED, false);
            if (head) {
                g.fill(x0, y + 9, x0 + font.width(l.substring(1)), y + 10, 0x603B2A14);
            }
            y += 10;
        }
        if (lines.isEmpty()) {
            g.drawString(font, tab == Tab.HISTORY ? "No history yet." : "Nothing to show.", x0, y, FADED, false);
        }
        if (textScroll > 0 || y >= bottom) {
            String more = (textScroll > 0 ? "\u25B2 " : "") + (y >= bottom ? "\u25BC more" : "");
            g.drawString(font, more, x1 - font.width(more), bottom - 2, FADED, false);
        }
    }

    /**
     * 1.9: the assault bar over the War tab's reports: the rally side, the minutes until the men go in, whether to ask your side
     * for help, then lead an assault on the selected village or call yours off. The buttons flow onto a second line on a narrow
     * panel. Returns the bar's bottom.
     */
    private int assaultBar(int x0, int x1, int y) {
        PoliticsSnapshot.VillageRow sel = snap.villages().stream().filter(v -> v.id().equals(snap.selected())).findFirst().orElse(null);
        String[] labels = {
                "Rally " + dev.hywmill.politics.war.Assault.SIDES[assaultSide],
                "In " + dev.hywmill.politics.war.Assault.MINUTES[assaultMinutes] + " min",
                assaultHelp ? "With help" : "Alone",
                "Lead assault",
                "Call off"};
        String[] tips = {
                "Where your men gather: the " + dev.hywmill.politics.war.Assault.SIDE_NAMES[assaultSide] + " of the village, "
                        + dev.hywmill.politics.war.Assault.RALLY_OUT + " blocks outside it. Click to turn round the compass.",
                "How long until your men go in. Gather them at the rally point by then, and be there to lead them.",
                assaultHelp ? "Your side lends you men (from the village you campaign with, or your realm's nearest)." : "Only your own men go.",
                sel == null ? "Select a village you are at war with in the list." : "Lead your own men (the soldiers you hired, within 96 blocks)"
                        + " against " + sel.name() + ". It will press levies into service when it sees you gather; it yields when its"
                        + " defenders lose heart, and pays you a ransom. Every head your men take earns its price.",
                "Call off your assault: your men stand down, men lent go home."};
        int bx = x0, by = y;
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            int bw = font.width(labels[i]) + 10;
            if (bx + bw > x1 && bx > x0) {
                bx = x0;
                by += 18;
            }
            Button b = Button.builder(Component.literal(labels[i]), btn -> assaultClick(k)).bounds(bx, by, bw, 16)
                    .tooltip(Tooltip.create(Component.literal(tips[i]))).build();
            b.active = i != 3 || sel != null && !sel.id().equals(snap.home());
            addRenderableWidget(b);
            bx += bw + 3;
        }
        return by + 16;
    }

    private void assaultClick(int button) {
        switch (button) {
            case 0 -> assaultSide = (assaultSide + 1) % dev.hywmill.politics.war.Assault.SIDES.length;
            case 1 -> assaultMinutes = (assaultMinutes + 1) % dev.hywmill.politics.war.Assault.MINUTES.length;
            case 2 -> assaultHelp = !assaultHelp;
            case 3 -> {
                UUID target = snap.selected();
                if (target != null) {
                    PacketDistributor.sendToServer(new PoliticsPayloads.SubmitAction("ASSAULT", snap.home() != null ? snap.home() : target, target,
                            dev.hywmill.politics.war.Assault.pack(assaultSide, assaultMinutes, assaultHelp)));
                }
            }
            default -> {
                UUID any = snap.home() != null ? snap.home() : snap.selected() != null ? snap.selected() : new UUID(0, 0);
                PacketDistributor.sendToServer(new PoliticsPayloads.SubmitAction("ASSAULT_CANCEL", any, any, 0));
            }
        }
        if (button < 3) {
            rebuildWidgets();
        }
    }

    private void submitWar(String action, UUID column) {
        PacketDistributor.sendToServer(new PoliticsPayloads.SubmitAction(action, snap.home() != null ? snap.home() : column, column, 0));
    }

    /** Post-M5: the War tab: each scout report (open ones first), two wrapped lines, its buttons on the right. */
    private void renderWar(GuiGraphics g, int x0, int x1) {
        List<PoliticsSnapshot.IntelRow> rows = snap.intel();
        if (rows.isEmpty()) {
            g.drawString(font, "No scout reports. In a war, your side's light horse ride out and report", x0, warTop, FADED, false);
            g.drawString(font, "columns on the road and enemy supply convoys here.", x0, warTop + 10, FADED, false);
            return;
        }
        int fit = Math.max(1, (height - 44 - warTop) / WAR_ROW);
        for (int k = 0; k < fit && warScroll + k < rows.size(); k++) {
            PoliticsSnapshot.IntelRow row = rows.get(warScroll + k);
            int ry = warTop + k * WAR_ROW;
            var lines = font.split(Component.literal(row.text()), x1 - x0 - 80);
            for (int l = 0; l < Math.min(3, lines.size()); l++) {
                g.drawString(font, lines.get(l), x0, ry + l * 10, row.open() ? INK : FADED, false);
            }
        }
    }

    private void select(UUID village) {
        if (snap.home() != null) {
            PacketDistributor.sendToServer(new PoliticsPayloads.SelectVillage(snap.home(), village));
        }
    }

    private void submit(String action) {
        if (snap.home() != null) {
            UUID target = snap.selected() != null ? snap.selected() : snap.home();
            PacketDistributor.sendToServer(new PoliticsPayloads.SubmitAction(action, snap.home(), target, 0));
        }
    }

    /** The vanilla (1.21) background, blurred and darkened, then the parchment panel on top of it. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.renderBackground(g, mouseX, mouseY, partial);
        g.fill(x0 - 6, 28, x1 + 6, height - 32, PARCHMENT);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // background (blur + parchment) and widgets first; the text goes on top (drawn before, the 1.21 blur covered it)
        super.render(g, mouseX, mouseY, partial);
        int x0 = this.x0, x1 = this.x1;
        g.drawString(font, title, 12, 14, 0xFFFFFFFF); // top left: the Overview/History tabs sit above the centre panel
        // each village's livery (two colours) beside its name, and its distance and bearing from the village you are in (or you)
        int total = filtered().size();
        String count = total + (total == snap.villages().size() ? "" : " of " + snap.villages().size()) + " villages, distance from "
                + (snap.home() == null ? "you" : snap.homeName());
        g.drawString(font, font.width(count) > listRight - 12 ? font.plainSubstrByWidth(count, listRight - 20) + ".." : count, 12,
                LIST_TOP - 11, 0xFFD8CFB8, false);
        for (int i = 0; i < shown.size(); i++) {
            PoliticsSnapshot.VillageRow v = shown.get(i);
            int ry = LIST_TOP + i * ROW;
            if (v.id().equals(snap.selected())) {
                g.fill(10, ry - 1, listRight + 2, ry + 19, 0x60F2E3C2);
            }
            swatch(g, 12, ry + 1, v.colour1(), v.colour2());
            String d = v.id().equals(snap.home()) ? "here" : distance(v);
            if (!d.isEmpty()) {
                g.drawString(font, d, listRight - font.width(d), ry + 5, v.id().equals(snap.home()) ? 0xFFF0E070 : 0xFFFFFFFF, true);
            }
        }
        if (tab == Tab.WAR) {
            renderWar(g, x0, x1);
            result(g, x0, x1);
            return;
        }
        if (tab != Tab.OVERVIEW) {
            renderText(g, x0, x1);
            result(g, x0, x1);
            return;
        }
        int y = 34;
        if (snap.home() == null) {
            for (String n : snap.notes()) {
                g.drawString(font, n, x0, y, INK, false);
                y += 11;
            }
        } else {
            g.drawString(font, snap.homeName() + " (" + snap.culture().replace("millenaire:", "") + ")", x0, y, INK, false);
            final int hy = y;
            snap.villages().stream().filter(v -> v.id().equals(snap.home())).findFirst()
                    .ifPresent(v -> swatch(g, x0 + font.width(snap.homeName() + " (" + snap.culture().replace("millenaire:", "") + ")") + 6, hy - 1,
                            v.colour1(), v.colour2()));
            y += 13;
            String st = snap.standing().equals(snap.effective()) ? snap.standing() : snap.standing() + " (treated as " + snap.effective() + ")";
            g.drawString(font, "Standing: " + st.toLowerCase(), x0, y, INK, false);
            y += 11;
            g.drawString(font, "Reputation " + snap.reputation() + "  Favor " + snap.favor()
                    + (snap.diplomacyPoints() >= 0 ? "  Diplomacy points " + snap.diplomacyPoints() : ""), x0, y, INK, false);
            y += 11;
            if (snap.grievance() >= 0.5) {
                g.drawString(font, String.format("Grievance %.0f", snap.grievance()), x0, y, INK, false);
                y += 11;
            }
            for (String n : snap.notes()) {
                y = wrap(g, "Word travels: " + n, x0, y, x1 - x0, FADED);
            }
            for (String l : snap.lent()) {
                y = wrap(g, "Lent: " + l, x0, y, x1 - x0, FADED);
            }
            y += 4;
            g.drawString(font, "Chronicle", x0, y, INK, false);
            y += 11;
            for (String c : snap.chronicle()) {
                y = wrap(g, "\u2022 " + c, x0, y, x1 - x0, FADED);
            }
        }
        for (String e : snap.envoys()) {
            y = wrap(g, "Envoy: " + e, x0, y + 2, x1 - x0, FADED);
        }
        for (String h : snap.honours()) {
            y = wrap(g, "Honour: " + h, x0, y + 2, x1 - x0, FADED);
        }
        result(g, x0, x1);
    }

    /** The last action's result, in a band at the foot of the panel (green: done; red: refused). */
    private void result(GuiGraphics g, int x0, int x1) {
        if (lastResult.isEmpty()) {
            return;
        }
        var lines = font.split(Component.literal(lastResult), x1 - x0 - 8);
        int n = Math.min(2, lines.size()), y = height - 36 - n * 10;
        g.fill(x0 - 4, y - 3, x1 + 4, height - 33, lastOk ? 0x402E5E1E : 0x408A1E1E);
        for (int i = 0; i < n; i++) {
            g.drawString(font, lines.get(i), x0, y + i * 10, lastOk ? 0xFF2E5E1E : 0xFF8A1E1E, false);
        }
    }

    private int wrap(GuiGraphics g, String text, int x, int y, int w, int color) {
        for (var line : font.split(Component.literal(text), w)) {
            g.drawString(font, line, x, y, color, false);
            y += 10;
        }
        return y;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Post-M5: a village's livery as two stacked colour squares (nothing if it has none). */
    private static void swatch(GuiGraphics g, int x, int y, int c1, int c2) {
        if (c1 < 0 || c2 < 0) {
            return;
        }
        g.fill(x - 1, y - 1, x + 9, y + 17, 0xFF3B2A14);
        g.fill(x, y, x + 8, y + 8, 0xFF000000 | c1);
        g.fill(x, y + 8, x + 8, y + 16, 0xFF000000 | c2);
    }
}
