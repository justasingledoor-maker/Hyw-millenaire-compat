execute as @a[tag=!v23] run function armorytest:setup
execute as @a[tag=!v23] run setblock 0 -60 -30 hywmill:muster_roll
execute as @a[tag=!v23] run tp @s 0.5 -60 -27.5 180 20
execute as @a[tag=!v23] run schedule function armorytest:muster 100t
tag @a add v23
