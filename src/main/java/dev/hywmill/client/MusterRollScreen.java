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
 * The Muster Roll screen. Two tabs: Soldiers (single hires: unit, gear tier, price, a quantity 1-32 and Hire) and Squads
 * (post-M5: the village culture's 16 squads, hired whole; one that cannot be hired shows why). The spawn radius (2-36) is the
 * placer's. Everything shown comes from the server; it decides and re-validates.
 */
public final class MusterRollScreen extends Screen {
    private static final int PARCHMENT = 0xE0F2E3C2;
    private static final int INK = 0xFF3B2A14;
    private static final int FADED = 0xFF7A6548;
    private static final int RED = 0xFF8A1E1E;
    private static final int MAX_QTY = 32;
    private static final int MIN_RADIUS = 2, MAX_RADIUS = 36;

    private RecruitPayloads.View view;
    private List<RecruitPayloads.SquadView> squads;
    private boolean squadTab;
    private int selected;
    private int squadSelected;
    private int qty = 1;
    private int scroll;
    private String lastResult = "";
    private boolean lastOk = true;

    public MusterRollScreen(RecruitPayloads.View view) {
        super(Component.literal("Muster Roll"));
        this.view = view;
        this.squads = RecruitClient.pendingFor(view.pos());
    }

    public BlockPos pos() {
        return view.pos();
    }

    public void update(RecruitPayloads.View v) {
        this.view = v;
        selected = Math.min(selected, Math.max(0, v.offers().size() - 1));
        rebuildWidgets();
    }

    public void squads(List<RecruitPayloads.SquadView> s) {
        this.squads = s;
        squadSelected = Math.min(squadSelected, Math.max(0, s.size() - 1));
        rebuildWidgets();
    }

    public void result(RecruitPayloads.Result r) {
        lastResult = r.message();
        lastOk = r.ok();
    }

    private int rows() {
        return Math.max(1, (height - 150) / 22);
    }

    private int count() {
        return squadTab ? squads.size() : view.offers().size();
    }

    @Override
    protected void init() {
        int left = 20, top = 54;
        addRenderableWidget(Button.builder(Component.literal((squadTab ? "" : "> ") + "Soldiers"), b -> tab(false)).bounds(left, 32, 90, 18).build());
        addRenderableWidget(Button.builder(Component.literal((squadTab ? "> " : "") + "Squads (" + squads.size() + ")"), b -> tab(true))
                .bounds(left + 94, 32, 110, 18).build());
        int rows = rows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, count() - rows)));
        for (int i = scroll; i < Math.min(count(), scroll + rows); i++) {
            int idx = i;
            String label;
            if (squadTab) {
                RecruitPayloads.SquadView s = squads.get(i);
                label = (i == squadSelected ? "> " : "") + (s.refusal().isEmpty() ? "" : "x ") + "[" + cat(s.category()) + "] " + s.name()
                        + " - " + RecruitOffers.money(s.price());
            } else {
                RecruitPayloads.OfferView o = view.offers().get(i);
                label = (i == selected ? "> " : "") + o.label() + " - " + RecruitOffers.money(o.price());
            }
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                if (squadTab) {
                    squadSelected = idx;
                } else {
                    selected = idx;
                }
                rebuildWidgets();
            }).bounds(left, top + (i - scroll) * 22, 250, 20).build());
        }
        if (count() > rows) {
            addRenderableWidget(Button.builder(Component.literal("▲"), b -> { scroll--; rebuildWidgets(); }).bounds(left + 254, top, 16, 18).build());
            addRenderableWidget(Button.builder(Component.literal("▼"), b -> { scroll++; rebuildWidgets(); })
                    .bounds(left + 254, top + (rows - 1) * 22, 16, 18).build());
        }
        int x = width - 230, y = height - 110;
        if (squadTab) {
            if (squadSelected < squads.size() && squads.get(squadSelected).refusal().isEmpty()) {
                addRenderableWidget(Button.builder(Component.literal("Hire this squad"), b -> hireSquad()).bounds(x, y + 24, 200, 20).build());
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
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 - 40, height - 26, 80, 20).build());
    }

    private static String cat(String category) {
        return switch (category) {
            case "INFANTRY" -> "Foot";
            case "RANGED" -> "Missile";
            case "CAVALRY" -> "Horse";
            default -> "Special";
        };
    }

    private void tab(boolean squadsTab) {
        if (squadTab != squadsTab) {
            squadTab = squadsTab;
            scroll = 0;
            rebuildWidgets();
        }
    }

    private void setQty(int q) {
        qty = Math.max(1, Math.min(MAX_QTY, q));
    }

    private void hire() {
        if (selected < view.offers().size()) {
            PacketDistributor.sendToServer(new RecruitPayloads.Hire(view.pos(), view.offers().get(selected).key(), qty));
        }
    }

    private void hireSquad() {
        if (squadSelected < squads.size()) {
            PacketDistributor.sendToServer(new RecruitPayloads.Hire(view.pos(), "squad:" + squads.get(squadSelected).id(), 1));
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
        int x = width - 230, py = 40;
        g.drawString(font, "Your money: " + RecruitOffers.money(view.money()), x, py, INK, false);
        py += 14;
        if (squadTab) {
            renderSquad(g, x, py);
        } else if (view.offers().isEmpty()) {
            g.drawString(font, "Nothing on offer to you here.", x, py, FADED, false);
        } else if (selected < view.offers().size()) {
            RecruitPayloads.OfferView o = view.offers().get(selected);
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
        if (!lastResult.isEmpty()) {
            int ry = height - 46 - 10 * Math.max(0, font.split(Component.literal(lastResult), 210).size() - 1);
            for (var line : font.split(Component.literal(lastResult), 210)) {
                g.drawString(font, line, x, ry - 30, lastOk ? 0xFF2E5E1E : RED, false);
                ry += 10;
            }
        }
    }

    private void renderSquad(GuiGraphics g, int x, int py) {
        if (squads.isEmpty()) {
            g.drawString(font, "No squads are raised here.", x, py, FADED, false);
            return;
        }
        if (squadSelected >= squads.size()) {
            return;
        }
        RecruitPayloads.SquadView s = squads.get(squadSelected);
        g.drawString(font, s.name(), x, py, INK, false);
        py += 12;
        g.drawString(font, cat(s.category()) + ", " + s.quality().toLowerCase() + " quality, " + s.size() + " soldiers", x, py, FADED, false);
        py += 12;
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
