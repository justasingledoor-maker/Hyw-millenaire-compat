execute as @a[tag=!v14] run function armorytest:setup
execute as @a[tag=!v14] run tp @s 0 -60 -3.5 0 4
execute as @a[tag=!v14] run schedule function armorytest:view2 500t
tag @a add v14
