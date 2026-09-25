package dev.hywmill.military.defense;

import dev.hywmill.military.MilitaryTier;
import dev.hywmill.military.ThreatTracker.Reason;
import dev.hywmill.military.classify.VillagerRole;
import dev.hywmill.military.doctrine.Doctrine;
import dev.hywmill.military.doctrine.DoctrineDefaults;
import dev.hywmill.military.doctrine.DoctrineField;
import dev.hywmill.military.doctrine.DoctrinePatch;
import dev.hywmill.military.doctrine.DoctrineResolver;

import java.util.EnumSet;
import java.util.UUID;

final class DefenseTestSupport {
    private DefenseTestSupport() {}

    static Doctrine baseline() {
        return with(DoctrinePatch.EMPTY);
    }

    static Doctrine with(DoctrinePatch p) {
        return DoctrineResolver.resolve(DoctrineDefaults.BUILTIN,
                new DoctrineResolver.Context("millenaire:norman", "millenaire:norman/agricole", 90, false, MilitaryTier.GUARD_POST), p).doctrine();
    }

    static Doctrine with(DoctrineField f, Object v) {
        return with(DoctrinePatch.EMPTY.with(f, v));
    }

    static UUID id(int n) {
        return new UUID(0, n);
    }

    static DefenseCoordinator.Pos at(double x, double z) {
        return new DefenseCoordinator.Pos(x, 64, z);
    }

    static DefenseCoordinator.DefenderView def(int n, VillagerRole role, double x, double z) {
        return new DefenseCoordinator.DefenderView(id(n), role, at(x, z));
    }

    static DefenseCoordinator.ThreatView threat(int n, double x, double z, Reason... reasons) {
        EnumSet<Reason> r = EnumSet.noneOf(Reason.class);
        java.util.Collections.addAll(r, reasons);
        return new DefenseCoordinator.ThreatView(id(1000 + n), at(x, z), r);
    }
}
