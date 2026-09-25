package dev.hywmill.military.defense;

import dev.hywmill.military.doctrine.DoctrineResolver;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runtime defense state of one village (never persisted). Server thread only. */
public final class VillageDefenseState {
    final UUID village;

    public UUID village() {
        return village;
    }
    final AlertStateMachine machine = new AlertStateMachine();
    DoctrineResolver.Resolved doctrine;
    DefenseCoordinator.Pos center;
    DefenseCoordinator.Pos defendingPos;
    Map<UUID, UUID> assignments = Map.of();
    Set<UUID> reserve = Set.of();
    Map<UUID, List<UUID>> byThreat = Map.of();
    int eligible;
    List<DefenseCoordinator.Pos> threatPositions = List.of();
    boolean engageSignal;
    long lastScanTick = -1;

    VillageDefenseState(UUID village) {
        this.village = village;
    }

    public AlertState state() {
        return machine.state();
    }

    public long stateSince() {
        return machine.since();
    }

    public DoctrineResolver.Resolved doctrine() {
        return doctrine;
    }

    public Map<UUID, UUID> assignments() {
        return assignments;
    }

    public Set<UUID> reserve() {
        return reserve;
    }

    public Map<UUID, List<UUID>> byThreat() {
        return byThreat;
    }

    public int eligible() {
        return eligible;
    }

    public DefenseCoordinator.Pos defendingPos() {
        return defendingPos;
    }
}
