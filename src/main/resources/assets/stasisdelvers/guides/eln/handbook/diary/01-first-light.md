---
navigation:
  title: "First Light: Bleeding the Trees"
  parent: diary.md
  position: 1
  icon: eln:tree_resin_collector
---

# First Light: Bleeding the Trees

*Rubber, copper, and the rules of the pipe.*

**Day 2 — Stability at the fencepost: 79%**

Before I build anything that turns or boils, Kid, I need to make sure you don't burn my shed down.

You knew how to solder a board and crimp a lug before you went down into the Bypass, which puts you ahead of ninety percent of the population. Most folks treat electricity as a cross between plumbing and witchcraft. They know it lives in the wall, they know it makes the toaster hot, and they think if they put a bigger fuse in the box, the wires will magically carry more of it without complaining.

Today I tapped six oak trees along the ridge with wooden **Tree Resin Collectors**—each pot takes about twenty-four minutes to dribble two globules of raw sap down a five-log trunk—cooked the resin into black vulcanised **Rubber** in a stone oven, and rolled my first lengths of insulated **Low Voltage Cable**. Tomorrow I’m going to wire the shed.

Tonight, while the resin cooks, I am going to write down the five rules of Direct Current so simply that a cabinet minister could understand them.

## 1. Pressure and Flow (Why the Water Analogy Works)

Everything in my workshop runs on **DC—Direct Current**. Unlike the municipal grid I used to run, which sloshed back and forth fifty times a second, DC flows steadily in one direction, like water in a millrace.

If you cannot see electricity in your head, picture water in a closed loop of garden hose with a rotary pump in the middle:

* **Voltage (`U`, measured in Volts, V)** is **water pressure**. It is how hard the pump is shoving. Even if you close the nozzle at the end of the hose so nothing is moving, the *pressure* is still sitting there in the hose, pushing against the rubber. My starter workshop runs at a nominal pressure of **50 V**.
* **Current (`I`, measured in Amperes or Amps, A)** is **flow rate**—how many gallons per second are actually rushing through the hose. If the switch is open, the voltage is 50 V, the current is **0 A**, and nothing happens.
* **Resistance (`R`, measured in Ohms, Ω)** is a **pinch or a narrow section in the pipe**. Every machine you wire up—a light bulb, a macerator motor, a heater coil—is just a controlled constriction that makes the current do work as it squeezes through. Every block of copper cable also has a tiny bit of friction of its own (**0.025 Ω per block** for [Low Voltage Cable](../reference/electricity.md#voltage-tiers-and-cables)).
* **Power (`P`, measured in Watts, W)** is **how fast work is being done right now**. In a water pipe, if you want to spin a waterwheel hard, you either need high pressure, a massive flow of water, or both. In electricity, it is the cleanest formula in the universe:

  `Power (W) = Voltage (V) × Current (A)`

  If a **50V Macerator** needs **200 W** of power to crush iron ore, and the line pressure is **50 V**, it will draw **4 A** of current (`50 V × 4 A = 200 W`).

## 2. Where the Water Analogy Lies to You

Every textbook gives you the water pipe, Kid, and then leaves out the three places where the analogy will get you killed—or at least leave you staring at a dead circuit in the dark:

1. **A wire does not start "empty."** When you lay a new garden hose, you have to wait for water from the tap to fill the hose before it squirts out the far end. A copper wire is *already packed full* of movable charge from the day it is mined, like a pipe that comes from the factory pre-filled with water from end to end. The instant the generator pushes at one end, the whole column shifts at once.
2. **Current is NEVER "used up."** If you pump four amps of current *into* a light bulb, **four amps of current come back out the other side**. The bulb does not eat the water! What gets "used up" across the bulb is the **pressure (Voltage)**. The charge goes into the bulb at 50 V, spends its pressure pushing through the hot tungsten filament, and comes out the back side at **0 V**.
3. **You cannot spill electricity on the floor; it demands a return path.** If you cut a garden hose in half, water pours onto your boots. If you cut an electrical wire, the flow stops dead everywhere in the pipe. Because charge cannot pile up in mid-air, **every circuit must be a complete loop** from the source, through the load, and back to zero.

And how do we give our circuits a "back to zero" return path without laying two parallel copper cables everywhere in the workshop? We use the earth under our feet.

> **Picture to come:** Simple single-lamp circuit on a stone wall. A power source connects via a Low Voltage Cable through a Low Voltage Switch to a Lamp Socket. A three-pronged Ground Cable block is planted at the base of the circuit to complete the return path.

Craft a **[Ground Cable](../reference/electricity.md#circuit-protection)** (five bare copper cables in the bench) and connect it to the negative reference side of your line. The Ground Cable stakes your circuit to the planet at an ideal **0 V** (behind a tiny LV resistance), acting as the universal return reservoir so you only have to run one live wire out to your machines. Forget the Ground Cable, and your circuit is a dead-end street—voltages will float wildly and nothing will work right.

## 3. Series vs. Parallel (How to Wire a Room)

Suppose you have two 50 V lamps:

* If you wire them **in Series** (one after another in a single chain, so the current goes through Lamp A and then has to go through Lamp B to get to ground), they have to *share* the 50 V pressure. Each lamp only sees **25 V** across it. Since a resistive machine's power drops with the *square* of the voltage (`P = U² / R`), at half voltage each lamp only gets **one quarter** of its rated wattage! They will barely glow.
* So we wire almost everything **in Parallel**: run one main 50 V trunk line down the wall, and branch each machine off that trunk so every single machine sees the full **50 V** pressure between the trunk and Ground. In parallel, the voltages stay at 50 V, and the **currents add up** along the trunk line.

## 4. Why Cables Burn (I²R) and Why High Voltage Exists

Here is the catch with adding up currents on a trunk line.

A **Low Voltage Cable** is rated for **50 V** and **20 A** of current (`50 V × 20 A = 1,000 W`, or **1 kW**).
Why does a 50 V cable care how many amps are flowing through it? Because of friction. As current (`I`) squeezes through the resistance (`R`) of the copper, it generates waste heat equal to:

`Heat Lost in the Wire = I² × R`

Notice that little **2** on the current, Kid. It is the tyrant of electrical engineering. **Double the current, and you get FOUR TIMES the heat in the cable.**

At its nominal **20 A**, an LV cable warms up by about 90 degrees over the room air (sitting around 110 °C in a 20 °C room). Push it just twenty percent harder—to **24 A** (`1.2 × I_nominal`)—and its steady temperature hits **150 °C**. After about half a minute at that load, the rubber insulation bursts into flame, sets any nearby wooden walls on fire, and the cable burns out!

And even before it catches fire, long LV runs suffer from **voltage drop** (`U_drop = I × R`). Run 20 A down a 40-block LV cable (`40 × 0.025 Ω = 1 Ω`), and you lose **20 V** just fighting the wire! Your 50 V macerator at the far end only sees **30 V**, and runs like it's wading through treacle.

How do we send more power—or send it across the valley to a distant Safeguard Tower—without melting the copper or losing half our voltage? **We step up the Voltage.**

Suppose you want to move **2,000 W (2 kW)** of power:

* At **50 V** (Low Voltage), 2,000 W requires **40 A** of current. That will fry an LV cable in seconds.
* Step the pressure up to **200 V** (Medium Voltage, MV) using a **DC-DC Converter**. Now how much current does 2,000 W take?

  `2,000 W ÷ 200 V = 10 A`
* Step it up to **800 V** (High Voltage, HV), and 2,000 W only takes **2.5 A**!
* Step it up to **3,200 V** (Very High Voltage, VHV), and 2,000 W is a mere **0.625 A**—a trickle of current that loses almost nothing over hundreds of paces!

Of course, higher voltage needs thicker rubber insulation so the pressure doesn't punch right through the jacket. Put 200 V onto a 50 V cable (or a 50 V machine) and you violate the **[1.3× Overvoltage Rule](../reference/electricity.md#voltage-tiers-and-cables)**: anything pushed past **130% of its rated voltage** (65 V on a 50 V device) suffers electrical breakdown and burns out within seconds.

## 5. Power vs. Energy (Why Batteries Are Water Towers)

One last distinction before my lamp oil runs out: don't confuse **Power (Watts)** with **Energy (Joules)**.

* **Power (Watts, W)** is how fast you are spending electricity *right now* (like miles per hour, or gallons per minute).
* **Energy (Joules, J, or kilojoules, kJ)** is the *total amount* of electricity stored in the tank (like total miles travelled, or gallons in a water tower). One **Watt** is simply **one Joule per second** (`1 W = 1 J/s`).

A **Cost Oriented Battery** holds **60 kJ** (60,000 Joules) of energy, and its standard power rating is **250 W** (250 Joules per second). Divide 60,000 J by 250 J/s, and you see why every battery in the catalog has a standard discharge time of **240 seconds (4 minutes)** at its rated load. Run a single 20 W light bulb off that same 60 kJ battery, and it will burn for fifty minutes (`60,000 ÷ 20 = 3,000 s`).

Tomorrow I’ll craft a **[MultiMeter](../reference/electricity.md#circuit-protection)**—though annoyance of annoyances, the **Electrical Probe Chip** inside a MultiMeter requires a piece of **High Voltage Cable**, which means I have to wrap a Copper Cable in three successive layers of rubber (LV → MV → HV) before I can even build my first voltmeter. Typical Ministry design: you need high-voltage insulation just to read a fifty-volt battery.

*My tidied-up notes for this chapter: [Electrical Fundamentals, Cables and Protection](../reference/electricity.md), starting with [the four equations you actually need](../reference/electricity.md#the-four-equations).*

---

Previous: [The Workbench Ledger](00-front-matter.md) · Next: [Fire and the Kettle: A Kettle with a Dynamo](02-fire-and-kettle.md)

[Back to the contents](../index.md)
