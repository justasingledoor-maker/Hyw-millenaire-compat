package dev.hywmill.garrison;

import dev.hywmill.garrison.equip.EquipmentProfiles;
import dev.hywmill.garrison.equip.Livery;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Unit livery: dye colours and shield arms, deterministic per unit, varied across units, heraldically sound. */
class LiveryTest {
    static final List<String> ORDINARIES = List.of("minecraft:cross", "minecraft:stripe_center", "minecraft:half_horizontal", "minecraft:chevron",
            "minecraft:diagonal_left", "minecraft:border");
    static final List<String> CHARGES = List.of("magistuarmory:lion1", "magistuarmory:eagle", "magistuarmory:lily", "magistuarmory:tower");

    @Test
    void coloursAreDeterministicDifferentAndFromThePalette() {
        List<Integer> pal = EquipmentProfiles.DEFAULT_PALETTE;
        Set<Integer> seen = new HashSet<>();
        for (long i = 0; i < 200; i++) {
            UUID id = new UUID(i * 7919, i);
            int[] c = Livery.colours(id, pal);
            assertArrayEquals(c, Livery.colours(id, pal), "same unit, same colours");
            assertTrue(pal.contains(c[0]) && pal.contains(c[1]));
            seen.add(c[0]);
        }
        assertTrue(seen.size() >= 8, "units wear many different colours: " + seen.size());
        assertEquals(0x123456, Livery.colours(UUID.randomUUID(), List.of(0x123456))[1], "a one-colour palette still works");
    }

    @Test
    void armsFollowTheRuleOfTinctureAndVary() {
        Set<String> designs = new HashSet<>();
        for (long i = 0; i < 300; i++) {
            UUID id = new UUID(i, i * 104729);
            Livery.Arms a = Livery.arms(id, ORDINARIES, CHARGES);
            assertEquals(a, Livery.arms(id, ORDINARIES, CHARGES), "deterministic");
            assertTrue(a.layers().size() >= 1 && a.layers().size() <= Livery.MAX_LAYERS, a.toString());
            boolean metalField = Livery.METALS.contains(a.base());
            for (Livery.Layer l : a.layers()) {
                assertEquals(!metalField, Livery.METALS.contains(l.color()), "metal on colour or colour on metal: " + a);
                assertTrue(ORDINARIES.contains(l.pattern()) || CHARGES.contains(l.pattern()) || l.pattern().equals("minecraft:border"));
            }
            designs.add(a.toString());
        }
        assertTrue(designs.size() > 150, "shields are varied: " + designs.size());
        assertFalse(Livery.arms(UUID.randomUUID(), List.of(), List.of()).base() == null);
        assertTrue(Livery.METALS.contains(DyeColor.WHITE));
    }
}
