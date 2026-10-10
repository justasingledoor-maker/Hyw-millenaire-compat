gamerule doDaylightCycle false
gamerule doWeatherCycle false
gamerule doMobSpawning false
time set 6000
weather clear
setworldspawn 0 -60 0
kill @e[type=armor_stand]
summon armor_stand -3 -60 4 {Rotation:[180f,0f],ShowArms:1b,NoBasePlate:1b,ArmorItems:[{id:"culturesarmory:samurai_suneate",count:1},{id:"culturesarmory:samurai_haidate",count:1},{id:"culturesarmory:samurai_do",count:1},{id:"culturesarmory:kabuto",count:1}]}
summon armor_stand -1 -60 4 {Rotation:[160f,0f],ShowArms:1b,NoBasePlate:1b,ArmorItems:[{id:"culturesarmory:samurai_suneate",count:1},{id:"culturesarmory:samurai_haidate",count:1},{id:"culturesarmory:samurai_do",count:1},{id:"culturesarmory:menpo_kabuto",count:1}]}
summon armor_stand 1 -60 4 {Rotation:[200f,0f],ShowArms:1b,NoBasePlate:1b,ArmorItems:[{id:"culturesarmory:samurai_suneate",count:1},{},{id:"culturesarmory:okegawa_do",count:1},{id:"culturesarmory:jingasa",count:1}]}
summon armor_stand 3 -60 4 {Rotation:[180f,0f],ShowArms:1b,NoBasePlate:1b,ArmorItems:[{id:"culturesarmory:samurai_suneate",count:1,components:{"minecraft:dyed_color":{rgb:2251856}}},{id:"culturesarmory:samurai_haidate",count:1,components:{"minecraft:dyed_color":{rgb:2251856}}},{id:"culturesarmory:samurai_do",count:1,components:{"minecraft:dyed_color":{rgb:2251856}}},{id:"culturesarmory:kabuto",count:1,components:{"minecraft:dyed_color":{rgb:2251856}}}]}
summon armor_stand 0 -60 10 {Rotation:[0f,0f],ShowArms:1b,NoBasePlate:1b,ArmorItems:[{id:"culturesarmory:samurai_suneate",count:1},{id:"culturesarmory:samurai_haidate",count:1},{id:"culturesarmory:samurai_do",count:1},{id:"culturesarmory:kabuto",count:1}]}
gamerule spawnRadius 0
