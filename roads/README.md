# Village Roads

A NeoForge 1.21.1 mod: roads and bridges between villages, and a staff to lay your own.

## Village roads (with Millénaire)

Each Millénaire village gets a road to its natural neighbours. Two villages are linked when they are within
`maxDistance` and no third village lies nearer to both. So no road runs past one village to reach the next, and no village is
cut off from a close neighbour. Roads are built in the background, one at a time, a few columns a tick. The land they need is
loaded (or generated) as they go, so nobody has to be there.

- **Village edges.** A road ends at a village's edge, just past its outermost buildings, or joins one of the village's own paths
  near the edge. Villages so close that their edges meet have their edges drawn in, so a real road runs between them.
- **Finding the way.** The road goes round buildings, keeping two blocks clear of them, whether they belong to villages or to any
  other mod. It also goes round other villages, steep cliffs (it never takes a step of more than three blocks) and, where it is
  cheap, trees. It keeps a margin from shores and follows an existing road when that road goes its way. The route is then
  straightened wherever a straight line costs about the same, so it bends smoothly instead of zig-zagging.
- **Bridges.** Water is never walked through. From a shore the road may cross straight to the far bank, and it does so exactly
  when going round would cost more. A pond is walked round; a long lake or a river is bridged. Spans of up to 16 blocks get a
  timber bridge, longer ones a stone bridge (`bridgeStyle`).
- **Building.** Trees on the way are felled whole, and their leaves fall as leaves do. Hollows are filled with earth and bumps
  are cut down; the road never climbs more than a block a step. Where it does climb, the blocks between are half steps:
  cobblestone or stone-brick slabs, or Millénaire's path slabs on dirt and gravel roads. Either side, a bank at the road's height
  and a slope a block lower are grassed with the land's own top (grass, sand, podzol, snow...), so the road sits in the country
  while its crown and edges still read clearly as a road. Water that would run into a cutting is banked off.
- **What it never touches.** Only blocks nature made are cut, filled or cleared. Buildings are never touched, and a village's
  own paths are kept as they are and joined.
- A finished road is announced: "A road now links Vaucelles and Itanos (176 blocks of road, a bridge of 13 blocks)."

## The Surveyor's Staff

Crafted from a compass on two sticks.

- Use it on a block to set the first point, then on another block to set the second. This opens the survey screen, where you
  choose:
  - **A road**, which finds its own way between the points, bridging where it must, or **a straight bridge**, which runs from the
    first point to the second over water or land.
  - **Road surface:** dirt path, gravel, cobblestone or stone bricks.
  - **Bridges:** auto, oak, spruce, dark oak or stone.
  - **Width:** 3 or 5.
- **Take up last** puts back every block your last road changed: grass, water, trees and all.
- Sneak and use the staff in the air to forget the first point.
- Server settings: `staff.maxDistance` (512) and `staff.creativeOnly` (false).

## Bridges

A bridge has a deck two blocks above the water (a boat passes under it), level with the lower bank, with ramps up to it. Each
side has a rail (fences, or a stone wall), and pillars stand every five blocks down to the bed. Lanterns light both ends and
every ten blocks.

## Commands (operators)

`/villageroads status | scan | link <near> <near> | lay <a> <b> [road|bridge <surface> <bridge> <width>] | undo | cancel | forget | route <from> <to>`

## Travellers (1.1)

Each road keeps its **way**, its centre line: a village road's way runs from one village's centre to the other's, so the
roads of a village meet there. Roads built with 1.0 have their ways traced from their road columns the first time travellers
need them. The ways make a network: roads that cross or join meet, players' roads count too.

`/villageroads route <from> <to>` shows the journey a traveller would take: across country to the nearest road (at most 160
blocks), along the roads (through the villages they link, over their bridges) and off them to the end. It is not taken when
the roads would make the journey much longer than going straight (1.8 times and 64 blocks more), or barely serve it.

### For other mods

`dev.villageroads.api.Roads` (server side) takes and returns plain types, so a mod can call it by reflection without
depending on this one:

* `int[] route(ServerLevel level, int fromX, int fromZ, int toX, int toZ)`: the journey's points `{x0, z0, x1, z1, ...}`
  from the start to the end (its first and last legs across country, the rest on the road), or `null`.
* `boolean onRoad(ServerLevel level, int x, int z)`: whether a column is part of one of this mod's roads.
* `int roads(ServerLevel level)`: how many roads travellers can follow.

HywMill uses it so that messengers, mercenaries, supply trains and siege hosts travel the roads, where players can wait for
them.

## Settings (`serverconfig/villageroads-server.toml`)

| Section | Settings |
|---|---|
| `villages` | `autoRoads`, `maxDistance` (700), `loneBuildings`, `roadStyle` (COBBLESTONE), `bridgeStyle` (AUTO), `width` (3), `scanTicks`, `announce` |
| `survey` | `corridor` (80: how far the road may wander from the straight line), `maxBridge` (48), `bridgeCost` (1.3: a block of bridge against a block of road), `columnsPerTick` (48) |

## Building

```
../gradlew build            # build/libs/village-roads-<version>.jar; unit tests of the planner (pure Java)
```

Millénaire is optional at run time: without it there is no village network, only the staff. The planner (`plan/`) is plain
Java and unit-tested. The world side (`world/`) is tested on the dedicated server: `devtools/server-harness` scenarios `RD`
(the checks) and `RDV` (pictures from a client).
