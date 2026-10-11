gamerule doDaylightCycle false
gamerule doMobSpawning false
time set 6000
gamemode survival @s
effect give @s resistance infinite 4 true
effect give @s saturation infinite 1 true
clear @s
item replace entity @s weapon.mainhand with assaultcrossbow:assault_crossbow
give @s arrow 64
give @s arrow 20
kill @e[type=husk]
kill @e[type=arrow]
tp @s 200.5 -60 0.5 -90 2
summon husk 210.5 -60 0.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["xbt"],Rotation:[90f,0f]}
summon husk 213.5 -60 0.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["xbt"],Rotation:[90f,0f]}
summon husk 216.5 -60 0.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["xbt"],Rotation:[90f,0f]}
summon husk 219.5 -60 0.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["xbt"],Rotation:[90f,0f]}
summon husk 222.5 -60 0.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["xbt"],Rotation:[90f,0f]}
summon husk 225.5 -60 0.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["xbt"],Rotation:[90f,0f]}
summon husk 228.5 -60 0.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["xbt"],Rotation:[90f,0f]}
summon husk 231.5 -60 0.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["xbt"],Rotation:[90f,0f]}
scoreboard objectives add xb dummy
function xbowtest:report
schedule function xbowtest:report 200t append
schedule function xbowtest:report 300t append
schedule function xbowtest:report 600t append
