execute as @a[tag=!v20] run function armorytest:setup
execute as @a[tag=!v20] run tp @s 31 -59.6 1.6 30 6
execute as @a[tag=!v20] run schedule function armorytest:view2 300t
tag @a add v20
