---
navigation:
  title: Applied Schematicannon
  icon: create:schematicannon
  position: 60
item_ids:
- create:schematicannon
---

# Applied Schematicannon

## Schematicannon

<Row gap="20">
<BlockImage id="create:schematicannon" scale="8" />
</Row>

The [Schematicannon](https://github.com/Creators-of-Create/Create) is a Create machine that builds a deployed
schematic block by block, taking the materials it needs from the inventories next to it.

With Applied Schematicannon installed, an ME Interface placed next to the cannon counts as one of those inventories, so
the cannon can build directly from the ME network. To make it auto-craft missing materials, install a **Crafting Card**
in that interface.

## Requesting items from the ME network

When the cannon needs a material that is not in the network, it asks the network to craft it:

* the interface next to the cannon must have a **Crafting Card** installed,
* the network needs a **crafting CPU** and an **encoded pattern** for the item,
* the crafted items are delivered into the ME network, and the cannon picks them up from there,
* if the cannon is set to *Skip Missing Blocks*, it never requests anything and simply skips what it cannot get.

See the [Autocrafting](ae2:ae2-mechanics/autocrafting.md) page for how to encode patterns and set up a crafting CPU.
