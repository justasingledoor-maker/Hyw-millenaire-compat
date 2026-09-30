package dev.hywmill.garrison;

import dev.hywmill.garrison.equip.Livery;
import dev.hywmill.garrison.equip.VillageLivery;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Post-M5 village liveries: neighbours are told apart, the colours contrast, shields stay in the village's colours. */
class VillageLiveryTest {
    static final List<DyeColor> TASTE = List.of(DyeColor.RED, DyeColor.BLUE, DyeColor.YELLOW, DyeColor.WHITE);

    @Test
    void firstVillageTakesItsCultureFavouriteWithAContrastingSecond() {
        int[] l = VillageLivery.choose(TASTE, List.of(), UUID.nameUUIDFromBytes("a".getBytes()));
        assertEquals(DyeColor.RED.getId(), l[0]);
        assertTrue(VillageLivery.metal(DyeColor.byId(l[1])), "a colour takes a metal");
    }

    @Test
    void neighboursNeverShareAFirstColourWhileAnyIsFree() {
        List<int[]> around = new ArrayList<>();
        Set<Integer> primaries = new HashSet<>();
        for (int i = 0; i < 16; i++) {
            int[] l = VillageLivery.choose(TASTE, around, UUID.nameUUIDFromBytes(("v" + i).getBytes()));
            assertTrue(primaries.add(l[0]), "village " + i + " repeats a neighbour's first colour");
            assertNotEquals(VillageLivery.metal(DyeColor.byId(l[0])), VillageLivery.metal(DyeColor.byId(l[1])), "metal on colour");
            around.add(l);
        }
    }

    @Test
    void evenWithManyNeighboursThePairIsUnique() {
        List<int[]> around = new ArrayList<>();
        Set<String> pairs = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            int[] l = VillageLivery.choose(TASTE, around, UUID.nameUUIDFromBytes(("w" + i).getBytes()));
            assertTrue(pairs.add(l[0] + "/" + l[1]), "village " + i + " repeats a neighbour's livery");
            around.add(l);
        }
    }

    @Test
    void villageArmsUseOnlyTheTwoColours() {
        for (int i = 0; i < 50; i++) {
            Livery.Arms a = Livery.villageArms(UUID.nameUUIDFromBytes(("s" + i).getBytes()), DyeColor.GREEN, DyeColor.WHITE,
                    List.of("minecraft:cross", "minecraft:stripe_center"), List.of("minecraft:flower"));
            assertTrue(a.base() == DyeColor.GREEN || a.base() == DyeColor.WHITE);
            assertFalse(a.layers().isEmpty());
            for (Livery.Layer l : a.layers()) {
                assertNotEquals(a.base(), l.color(), "figures contrast with the field");
            }
        }
    }
}
