package dev.villageroads.client;

import dev.villageroads.net.RoadPayloads;
import dev.villageroads.net.RoadPayloads.Choices;
import dev.villageroads.world.Styles.BridgeStyle;
import dev.villageroads.world.Styles.RoadStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** The Surveyor's Staff's screen: road or bridge between the two points, what it is made of, how wide; build, or take up the last. */
public final class SurveyScreen extends Screen {
    private final BlockPos a, b;
    private int mode, road, bridge, wide;

    public SurveyScreen(RoadPayloads.OpenSurvey p) {
        super(Component.literal("Survey"));
        this.a = p.a();
        this.b = p.b();
        Choices c = Choices.unpack(p.choices());
        this.mode = Math.min(1, c.mode());
        this.road = Math.min(RoadStyle.values().length - 1, c.road());
        this.bridge = Math.min(BridgeStyle.values().length - 1, c.bridge());
        this.wide = c.width() == 5 ? 5 : 3;
    }

    @Override
    protected void init() {
        int w = Math.min(260, this.width - 20), x = (this.width - w) / 2, y = height / 2 - 62;
        addRenderableWidget(CycleButton.<Integer>builder(v -> Component.literal(v == 0 ? "A road (finds its own way)" : "A straight bridge"))
                .withValues(0, 1).withInitialValue(mode).create(x, y, w, 20, Component.literal("Build"), (btn, v) -> mode = v));
        addRenderableWidget(CycleButton.<RoadStyle>builder(s -> Component.literal(s.label))
                .withValues(RoadStyle.values()).withInitialValue(RoadStyle.values()[road])
                .create(x, y + 24, w, 20, Component.literal("Road"), (btn, v) -> road = v.ordinal()));
        addRenderableWidget(CycleButton.<BridgeStyle>builder(s -> Component.literal(s.label))
                .withValues(BridgeStyle.values()).withInitialValue(BridgeStyle.values()[bridge])
                .create(x, y + 48, w, 20, Component.literal("Bridges"), (btn, v) -> bridge = v.ordinal()));
        addRenderableWidget(CycleButton.<Integer>builder(v -> Component.literal(v + " blocks"))
                .withValues(3, 5).withInitialValue(wide).create(x, y + 72, w, 20, Component.literal("Width"), (btn, v) -> wide = v));
        int half = (w - 8) / 3;
        addRenderableWidget(Button.builder(Component.literal("Build"), btn -> {
            PacketDistributor.sendToServer(new RoadPayloads.Build(a, b, new Choices(mode, road, bridge, wide).pack()));
            onClose();
        }).bounds(x, y + 104, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Take up last"), btn -> {
            PacketDistributor.sendToServer(new RoadPayloads.Undo());
            onClose();
        }).bounds(x + half + 4, y + 104, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> onClose()).bounds(x + 2 * (half + 4), y + 104, half, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int y = height / 2 - 62;
        int d = (int) Math.round(Math.sqrt(a.distSqr(b)));
        g.drawCenteredString(font, Component.literal("Surveyor's Staff"), width / 2, y - 34, 0xFFE0C070);
        g.drawCenteredString(font, Component.literal("(" + a.getX() + ", " + a.getY() + ", " + a.getZ() + ") to (" + b.getX() + ", " + b.getY()
                + ", " + b.getZ() + "): " + d + " blocks"), width / 2, y - 20, 0xFFFFFFFF);
        String hint = mode == 0 ? "Goes round buildings and water; bridges a lake where that is shorter."
                : "Straight from the first point to the second, over water or land.";
        g.drawCenteredString(font, Component.literal(hint), width / 2, y + 130, 0xFFA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
