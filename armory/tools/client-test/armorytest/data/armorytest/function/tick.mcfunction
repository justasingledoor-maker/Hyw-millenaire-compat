execute as @a[tag=!v12] run function armorytest:setup
execute as @a[tag=!v12] run tp @s 0 -60 -3.5 0 4
execute as @a[tag=!v12] run schedule function armorytest:view2 600t
tag @a add v12
