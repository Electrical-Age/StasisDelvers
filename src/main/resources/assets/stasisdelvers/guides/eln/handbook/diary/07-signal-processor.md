---
navigation:
  title: "The Signal Processor: Sixty-Four Grains of Red Dust"
  parent: diary.md
  position: 7
  icon: eln:signal_processor
---

# The Signal Processor: Sixty-Four Grains of Red Dust

**Day 19 — Stability at the fencepost: 29%**

The drifting clouds are thicker this week. One of them—a low, bruise-coloured bank of mist forty paces wide—slid right across the lower pasture at noon. While it passed over the fence, the crows in the rowan tree stopped mid-caw, beaks open, and the rain hung in the air like bead curtains. Ten minutes later the cloud drifted east over the ravine, the rain splashed into the mud all at once, and the crows finished swearing and flew off.

I spent the afternoon crouched behind the boiler with a soldering iron because my wall of discrete logic chips was turning into a rat's nest.

To keep the steam plant and the battery bank happy while I’m down in the mine, I needed a circuit that would watch the **50 V battery bank**, shed the non-essential workshop loads when the charge dropped below thirty percent, wait until the batteries recovered past sixty percent before reconnecting them, and smoothly throttle the cooling water valve using a proportional-integral loop. Doing that with discrete **Schmitt Triggers**, **RS Latches**, **Amplifiers**, and **PID chips** took half a wall of stone and a dozen lengths of Signal Cable.

Then I remembered the **Signal Processor**.

It’s a single six-node block no bigger than a dinner plate—four iron ingots, four lengths of Signal Cable, and one **Cheap Chip** in the middle. You slap it on the wall, right-click it, and instead of wiring ten different logic gates together, you type a single line of mathematics—up to **64 characters** long—into its little green screen.

> **Picture to come:** A Signal Processor mounted on a stone wall. Three Signal Cables enter its sides: Red dot on the right (Input A), Green dot on the back (Input B), Blue dot on the left (Input C). A fourth Signal Cable leaves the Black dot on the front (Output) to drive a Low Voltage Relay.

(Which dot is which is written down under [hardware and pins](../reference/signal-processor.md#hardware-and-pins).)

Of course, the engineers who designed the firmware for this thing back in the eighties were clearly paid by the booby trap, and they didn't leave a manual in the box. It took me two hours of probing the pins with a MultiMeter to figure out all its quirks:

1. **It runs on a deposit of Redstone Dust.** Every plus sign, every comparison, and every function call in your formula has a "complexity price." A simple `A+B` costs **1 Redstone Dust**. A latching `rs(A>0.8,A<0.6)` costs **5 Redstone Dust**. You don't *burn* the redstone—it sits in the processor's slot forever like a deposit in a vending machine—but if the slot holds even one grain less than your formula demands, the screen flashes red (`N Redstone(s) required`) and the output drops dead to **0 V**! (Every price is in [the cost table](../reference/signal-processor.md#functions-and-redstone-cost).)
2. **Whoever wrote the `rs()` latch function put Reset first.** Every textbook since 1960 writes Set-Reset. This box wants `rs(reset, set)`. Put them the textbook way around, and your heater will turn *off* when the boiler is cold and turn *on* when it’s about to explode! (See [the logic traps](../reference/signal-processor.md#operator-precedence-and-logic-traps).)
3. **Its clock starts the day at dawn, not midnight.** There is a built-in variable called `daytime`: `0` at sunrise, `0.25` at noon, `0.5` at sunset, `0.75` at midnight, and back to `0` with the next sunrise. Railway time it is not, but it means `daytime>0.5` switches the yard lamps on at dusk and off at dawn, for the price of one grain of redstone.


Because there is no way on God’s earth you would ever guess those rules by staring at the little 64-character text box, I have copied the entire instruction manual for the Signal Processor into the reference pages: [The Signal Processor: Complete Manual](../reference/signal-processor.md). Keep it open beside you when you program one.

---

Previous: [Fire and the Kettle: A Kettle with a Dynamo](02-fire-and-kettle.md) · Next: [The Real Steam Plant: The Day the Valve Sang](09-steam-plant.md)

[Back to the contents](../index.md)
