---
navigation:
  title: "Peak Loads: The Desk That Eats a Plant"
  parent: diary.md
  position: 12
  icon: eln:electrical_anvil
---

# Peak Loads: The Desk That Eats a Plant

**Day 24 — Stability at the fencepost: 22%**

My good pick is on its fourth life. Every time I took it to the old anvil the smith's price went up, until this morning the anvil simply refused: *Too expensive!* Iron doesn't get tired, Kid. That ratchet is a smith's superstition built into the steel, and I wasn't going to lose a pick to it.

So I built the **[Electrical Anvil](../reference/peak-loads.md#the-electrical-anvil)**: a proper workshop desk with a vise, an arc welder and a lamp over the plate. Lay the tool on the plate, put its material beside it, and the arc builds the worn steel back up. It spends no experience and has no ratchet. If anything, an old tool comes off the plate a little *easier* to work than it went on. The catch is the welder. It wants **800 V**, and while the arc burns it pulls **five kilowatts**. That's more than my whole steam plant makes.

The first time I struck it, the lights in the house went brown, the turbine moaned, and the desk gave up after a second with *Supply too weak*. It waited, tried again, browned the house out again, and gave up again. All afternoon.

The welder isn't greedy all the time, though. It wants five kilowatts for eight seconds, then nothing while I fetch the next tool. My plant makes plenty of energy over a minute; it just can't make it *fast enough* in those eight seconds. That's a **peak load**, and there are two ways to meet one: a generator big enough for the peak, or a store that fills slowly and empties fast.

A full size generator bank would cover this. Pity the grid fell when Stockholm died.

So: a store. A battery won't do. Batteries are built for a steady trickle, and they wear when you yank on them. What you want is a **Power Capacitor**, a jar that charges while the desk rests and gives it all back in a rush when the arc strikes. I hung a row of them on the wall behind the desk. Each is packed with a full load of redstone and exactly **sixteen Dielectrics**, so it's rated for 800 V with room to spare, with one leg on the 800 V line and the other to a Ground Cable. The desk waits until the bank is full (the screen says *Waiting for 784V*), then strikes, and the bank carries the arc. If the bank runs low before the weld is done, the desk pauses and keeps its progress, and the plant tops the bank up for the next burst.

Here's what took me all evening to work out: a capacitor only gives the desk the energy between 800 V and 720 V, where the desk lets go. That's less than a fifth of what it holds. A small bank still works, just in bursts; a big one welds in one go. The sums are in [sizing a bank](../reference/peak-loads.md#sizing-a-bank).

Between the turbine and the bank I put a **Power Inductor**. It slows how fast the current can change, so when the bank gulps after a weld, the turbine sees a steady pull instead of a kick in the teeth.

The pick is whole again. The house lights don't even flicker.

Previous: [The Real Steam Plant: The Day the Valve Sang](09-steam-plant.md) · Next: none yet

[Back to the contents](../index.md)
