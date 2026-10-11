execute as @a[tag=!v21] run function armorytest:setup
execute as @a[tag=!v21] run tp @s 4.0 -59.6 13.4 0 4
execute as @a[tag=!v21] run schedule function armorytest:view2 300t
tag @a add v21
