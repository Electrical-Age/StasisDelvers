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

The welder isn't greedy all the time, though. It welds in two bursts of four seconds for each piece of material, rests between them, then wants nothing while I fetch the next tool. My plant makes plenty of energy over a minute; it just can't make it *fast enough* in those four seconds. That's a **peak load**, and there are two ways to meet one: a generator big enough for the peak, or a store that fills slowly and empties fast.

A full size generator bank would cover this. Pity the grid fell when Stockholm died.

So: a store. A battery won't do. Batteries are built for a steady trickle, and they wear when you yank on them. What you want is a capacitor, a jar that charges while the desk rests and gives it all back in a rush when the arc strikes. My first try was a row of the little wall **Power Capacitors**. The trouble is that a capacitor only gives the desk the energy between 800 V and 720 V, where the desk lets go. That's less than a fifth of what it holds, so one burst wanted nine of them on the wall.

So I built a proper **[Capacitor Bank](../reference/peak-loads.md#the-capacitor-bank)**: six fat cans on a steel rack, plus to the 800 V line and minus to a Ground Cable. One bank carries one whole burst. The desk waits for the line to come up, strikes, and the bank carries the arc; in the rest between bursts the bank fills again. If it runs low before the burst is done, the desk pauses and keeps its progress. Don't think of it as a battery, mind: it leaks, and by morning it's flat. It's for the punch, not the pantry. The sums are in [sizing](../reference/peak-loads.md#sizing).

The first time I hooked the empty bank straight to the line, the high-voltage cable went cherry red and burnt through before I could reach the lever. An empty capacitor is a dead short until it fills. Now a **Large Rheostat** sits between the plant and the bank, with a **Power Inductor** behind it. I start the rheostat at three hundred-odd ohms and wind it down to a hundred once the bank is past six hundred volts; a probe on the bank does that for me now. The rheostat takes the inrush and runs warm doing it, and the inductor slows how fast the current can change. When the bank gulps after a weld, the turbine sees a steady pull instead of a kick in the teeth.

The pick is whole again. The house lights don't even flicker.

Previous: [The Real Steam Plant: The Day the Valve Sang](09-steam-plant.md) · Next: none yet

[Back to the contents](../index.md)
