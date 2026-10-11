execute as @a[tag=!v18] run function armorytest:setup
execute as @a[tag=!v18] run tp @s 25 -59.6 0.6 0 6
execute as @a[tag=!v18] run schedule function armorytest:view2 300t
tag @a add v18
