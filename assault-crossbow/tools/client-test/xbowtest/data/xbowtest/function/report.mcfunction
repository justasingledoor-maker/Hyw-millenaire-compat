execute store result score #arrows xb run clear @p arrow 0
execute store result score #husks xb if entity @e[type=husk,tag=xbt]
tellraw @a ["XBTEST arrows=",{"score":{"name":"#arrows","objective":"xb"}}," husks=",{"score":{"name":"#husks","objective":"xb"}}," mag=",{"entity":"@p","nbt":"SelectedItem.components.\"minecraft:charged_projectiles\"[0].count"}," state=",{"entity":"@p","nbt":"SelectedItem.components.\"assaultcrossbow:state\""}]
