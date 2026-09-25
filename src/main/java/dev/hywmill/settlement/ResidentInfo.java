package dev.hywmill.settlement;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * What the settlement mod says about an entity.
 *
 * @param settlementId       settlement the entity is registered to (for Millénaire raid clones this is the TARGET village)
 * @param raider             entity is a raid clone attacking {@code settlementId}
 * @param originSettlementId for raiders, the village they came from; otherwise same as settlementId
 * @param defender           villager type defends the village (Millénaire tag helpInAttacks)
 * @param typeId             settlement-mod villager type id, for logs
 */
public record ResidentInfo(UUID settlementId, boolean raider, @Nullable UUID originSettlementId, boolean defender, String typeId) {}
