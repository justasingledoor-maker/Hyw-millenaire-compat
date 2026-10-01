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
    private static final int ROWS = 12;
    private static final int PARCHMENT = 0xE0F2E3C2;
    private static final int INK = 0xFF3B2A14;
    private static final int FADED = 0xFF7A6548;
    private PoliticsSnapshot snap;
    private int scroll;
    /** Post-M5: the History tab (vassal ties and battle reports) instead of the overview, and its scroll. */
    private boolean history;
    private int historyScroll;
    /** Post-M5: the War tab (scout reports: columns on the road, convoys), and its scroll (rows). */
    private boolean war;
    private int warScroll;
    private static final int WAR_ROW = 34;
    private String lastResult = "";
    private boolean lastOk = true;

    public PoliticsScreen(PoliticsSnapshot snap) {
        super(Component.translatable("screen.hywmill.politics"));
        this.snap = snap;
    }

    public void update(PoliticsSnapshot s) {
        this.snap = s;
        rebuildWidgets();
    }

    public void result(PoliticsPayloads.ActionResult r) {
        lastResult = r.message();
        lastOk = r.ok();
    }

    @Override
    protected void init() {
        int left = 12, top = 34;
        List<PoliticsSnapshot.VillageRow> vs = snap.villages();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, vs.size() - ROWS)));
        for (int i = 0; i < Math.min(ROWS, vs.size() - scroll); i++) {
            PoliticsSnapshot.VillageRow v = vs.get(scroll + i);
            String rel = v.relation() == PoliticsSnapshot.VillageRow.NO_RELATION ? "" : " " + v.relation();
            String dist = v.distance() < 0 ? "" : " " + (v.distance() >= 1000 ? String.format("%.1f km", v.distance() / 1000.0) : v.distance() + " m");
            Button b = Button.builder(Component.literal((v.id().equals(snap.selected()) ? "> " : "") + v.name() + rel + (v.truce() ? " [truce]" : "")),
                            btn -> select(v.id()))
                    .bounds(left + 12, top + i * 20, 138, 18)
                    .tooltip(Tooltip.create(Component.literal("Your standing: " + v.standing().toLowerCase()
                            + (v.distance() < 0 ? "" : "\nDistance from " + snap.homeName() + ":" + dist)))).build();
            addRenderableWidget(b);
        }
        if (vs.size() > ROWS) {
            addRenderableWidget(Button.builder(Component.literal("\u25B2"), b -> { scroll--; rebuildWidgets(); }).bounds(left + 152, top, 16, 18).build());
            addRenderableWidget(Button.builder(Component.literal("\u25BC"), b -> { scroll++; rebuildWidgets(); })
                    .bounds(left + 152, top + (ROWS - 1) * 20, 16, 18).build());
        }
        int ax = width - 182, ay = 34;
        int i = 0;
        for (PoliticsSnapshot.ActionRow a : snap.actions()) {
            Button b = Button.builder(Component.literal(a.label()), btn -> submit(a.action()))
                    .bounds(ax, ay + i * 22, 170, 20)
                    .tooltip(Tooltip.create(Component.literal(a.requirement() + (a.outcome().isEmpty() ? "" : "\nOutcome: " + a.outcome())))).build();
            b.active = a.available() && snap.home() != null;
            addRenderableWidget(b);
            i++;
        }
        int x0 = 190;
        addRenderableWidget(Button.builder(Component.literal((history || war ? "" : "> ") + "Overview"), b -> { history = false; war = false; rebuildWidgets(); })
                .bounds(x0, 12, 70, 16).build());
        addRenderableWidget(Button.builder(Component.literal((history ? "> " : "") + "History"), b -> { history = true; war = false; rebuildWidgets(); })
                .bounds(x0 + 74, 12, 70, 16).build());
        long openCount = snap.intel().stream().filter(PoliticsSnapshot.IntelRow::open).count();
        addRenderableWidget(Button.builder(Component.literal((war ? "> " : "") + "War" + (openCount > 0 ? " (" + openCount + ")" : "")),
                b -> { war = true; history = false; rebuildWidgets(); }).bounds(x0 + 148, 12, 70, 16).build());
        if (war) {
            List<PoliticsSnapshot.IntelRow> rows = snap.intel();
            int fit = Math.max(1, (height - 44 - 34) / WAR_ROW);
            warScroll = Math.max(0, Math.min(warScroll, Math.max(0, rows.size() - fit)));
            int bx = width - 190 - 74;
            for (int k = 0; k < fit && warScroll + k < rows.size(); k++) {
                PoliticsSnapshot.IntelRow row = rows.get(warScroll + k);
                int ry = 34 + k * WAR_ROW;
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
                        .bounds(width - 190 - 18, 12, 16, 16).build());
                addRenderableWidget(Button.builder(Component.literal("\u25BC"), b -> { warScroll++; rebuildWidgets(); })
                        .bounds(width - 190 - 36, 12, 16, 16).build());
            }
        }
        if (history) {
            addRenderableWidget(Button.builder(Component.literal("\u25B2"), b -> { historyScroll = Math.max(0, historyScroll - 5); })
                    .bounds(width - 190 - 18, 12, 16, 16).build());
            addRenderableWidget(Button.builder(Component.literal("\u25BC"), b -> { historyScroll += 5; })
                    .bounds(width - 190 - 36, 12, 16, 16).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 - 40, height - 26, 80, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (history && mx >= 190 && mx <= width - 190) {
            historyScroll = Math.max(0, historyScroll - (int) Math.signum(dy) * 2);
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    /** Post-M5: the History tab: vassal ties and battle reports, wrapped, from the scroll position. */
    private void renderHistory(GuiGraphics g, int x0, int x1) {
        List<String> lines = new java.util.ArrayList<>();
        for (String h : snap.history()) {
            if (h.isEmpty()) {
                lines.add("");
                continue;
            }
            for (var l : font.split(Component.literal(h), x1 - x0)) {
                StringBuilder sb = new StringBuilder();
                l.accept((i, style, cp) -> { sb.appendCodePoint(cp); return true; });
                lines.add((h.startsWith("Day ") || h.startsWith("Vassalage") ? "\u0001" : "") + sb);
            }
        }
        historyScroll = Math.min(historyScroll, Math.max(0, lines.size() - 1));
        int y = 34;
        for (int i = historyScroll; i < lines.size() && y < height - 44; i++) {
            String l = lines.get(i);
            boolean head = l.startsWith("\u0001");
            g.drawString(font, head ? l.substring(1) : l, x0, y, head ? INK : FADED, false);
            y += 10;
        }
    }

    private void submitWar(String action, UUID column) {
        PacketDistributor.sendToServer(new PoliticsPayloads.SubmitAction(action, snap.home() != null ? snap.home() : column, column, 0));
    }

    /** Post-M5: the War tab: each scout report (open ones first), two wrapped lines, its buttons on the right. */
    private void renderWar(GuiGraphics g, int x0, int x1) {
        List<PoliticsSnapshot.IntelRow> rows = snap.intel();
        if (rows.isEmpty()) {
            g.drawString(font, "No scout reports. In a war, your side's light horse ride out and report", x0, 34, FADED, false);
            g.drawString(font, "columns on the road and enemy supply convoys here.", x0, 44, FADED, false);
            return;
        }
        int fit = Math.max(1, (height - 44 - 34) / WAR_ROW);
        for (int k = 0; k < fit && warScroll + k < rows.size(); k++) {
            PoliticsSnapshot.IntelRow row = rows.get(warScroll + k);
            int ry = 34 + k * WAR_ROW;
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
        g.fill(190 - 6, 28, width - 190 + 6, height - 32, PARCHMENT);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // background (blur + parchment) and widgets first; the text goes on top (drawn before, the 1.21 blur covered it)
        super.render(g, mouseX, mouseY, partial);
        int x0 = 190, x1 = width - 190;
        g.drawString(font, title, 12, 14, 0xFFFFFFFF); // top left: the Overview/History tabs sit above the centre panel
        // post-M5: each village's livery (two colours) beside its name, and its distance from home
        List<PoliticsSnapshot.VillageRow> vs = snap.villages();
        for (int i = 0; i < Math.min(ROWS, vs.size() - scroll); i++) {
            PoliticsSnapshot.VillageRow v = vs.get(scroll + i);
            int ry = 34 + i * 20;
            swatch(g, 12, ry + 1, v.colour1(), v.colour2());
            if (v.distance() >= 0) {
                String d = v.distance() >= 1000 ? String.format("%.1f km", v.distance() / 1000.0) : v.distance() + " m";
                g.drawString(font, d, 12 + 138 - font.width(d) - 3, ry + 5, 0xFFD8CFB8, false);
            }
        }
        if (war) {
            renderWar(g, x0, x1);
            if (!lastResult.isEmpty()) {
                wrap(g, lastResult, x0, height - 58, x1 - x0, lastOk ? 0xFF2E5E1E : 0xFF8A1E1E);
            }
            return;
        }
        if (history) {
            renderHistory(g, x0, x1);
            if (!lastResult.isEmpty()) {
                wrap(g, lastResult, x0, height - 58, x1 - x0, lastOk ? 0xFF2E5E1E : 0xFF8A1E1E);
            }
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
        if (!lastResult.isEmpty()) {
            wrap(g, lastResult, x0, height - 58, x1 - x0, lastOk ? 0xFF2E5E1E : 0xFF8A1E1E);
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
