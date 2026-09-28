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
            Button b = Button.builder(Component.literal((v.id().equals(snap.selected()) ? "> " : "") + v.name() + rel + (v.truce() ? " [truce]" : "")),
                            btn -> select(v.id()))
                    .bounds(left, top + i * 20, 150, 18)
                    .tooltip(Tooltip.create(Component.literal("Your standing: " + v.standing().toLowerCase()))).build();
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
                    .tooltip(Tooltip.create(Component.literal(a.requirement() + (a.outcome().isEmpty() ? "" : "\\nOutcome: " + a.outcome())))).build();
            b.active = a.available() && snap.home() != null;
            addRenderableWidget(b);
            i++;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 - 40, height - 26, 80, 20).build());
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
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
        int y = 34;
        if (snap.home() == null) {
            for (String n : snap.notes()) {
                g.drawString(font, n, x0, y, INK, false);
                y += 11;
            }
        } else {
            g.drawString(font, snap.homeName() + " (" + snap.culture().replace("millenaire:", "") + ")", x0, y, INK, false);
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
}
