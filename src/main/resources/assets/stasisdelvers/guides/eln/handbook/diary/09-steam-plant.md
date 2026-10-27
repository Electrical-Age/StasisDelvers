---
navigation:
  title: "The Real Steam Plant: The Day the Valve Sang"
  parent: diary.md
  position: 9
  icon: eln:boiler
---

# The Real Steam Plant: The Day the Valve Sang

**Day 16 — Stability at the fencepost: 38%**

The grey took the creek bottom on Wednesday and crept halfway up the orchard wall before noon. There is a cow standing in the clover thirty yards from my south fence, head down, tongue curled around a tuft of grass that will never come up by the roots. From the kitchen window she looks completely normal until the wind blows and her tail doesn't twitch.

I needed more power. The Safeguard Tower up on the knoll is hungry now that the front is pressing against its perimeter, and my 50 V workshop was choking whenever the Macerator and the Compressor came on together. The little **[50V Compact Steam Turbine](../reference/early-heat.md)** got me on my feet, bless its stout little heart, but one furnace only sustains **270 W** on it, and its **10% thermodynamic efficiency** means ninety percent of my coal goes straight out the cooling fins into the shed air. That stops being funny when half the coal seams in the county are locked inside stopped stone.

So I built a real steam plant: two **Stone Heat Furnaces** (packed with four Combustion Chambers apiece) set flush against the back and left flank of an eight-litre **Boiler**, sitting directly on top of a **50V Steam Turbine** rated for a full **1,000 W**, with a **Small Steam Condenser** bolted to the turbine's right side and a **Submersible Pump** down in the cistern.

(A word to the wise before we go any further: you will see a **50V Electric Heater** and a **200V Electric Heater** in the crafting tables. Do *not* build an Electric Heater to fire a steam power plant! Burning electricity to boil water to spin a turbine that turns at best eighteen percent of that heat *back* into electricity is a fool’s errand—you lose four-fifths of your power every time around the loop. Electric Heaters are for turning surplus solar or grid electricity into process heat later on, or for fine temperature trim. A real power plant runs on **fuel**: coal in Stone Heat Furnaces now, and diesel or heavy oil in a **Fuel Heat Furnace** once we drill an oil well. See [firing the boiler](../reference/steam.md#firing-the-boiler).)

Even running on honest coal, for three solid hours this morning I stood in front of my new boiler like a first-year apprentice, watching the pressure gauge collapse to **2 bar of steam** while a mocking little placard flashed in the corner of my visor: *Under Pressure — asks for more steam than its boiler makes.*

Listen to me very carefully, Kid, because I have watched grown men with engineering degrees get these three things backwards: **Furnace Temperature**, **Boiler Pressure**, and **The Turbine Regulator**. If you muddle them up, your plant will either sit there stone-cold or choke itself to death on wet, flabby steam. (The short version is [the three-part control rule](../reference/steam.md#the-three-part-control-rule).)

Here is what I did wrong on my first try.

I wanted **10 bar of steam**. Why 10 bar? Because a steam turbine works by **expansion**—it wants tightly packed, high-pressure steam pushing hard on its inlet blades and expanding down into a low-pressure exhaust. In the diagnostic visor, Jade shows a number called **"pressure efficiency"**. Don't confuse that with true **[thermodynamic efficiency](../reference/steam.md#thermodynamic-efficiency)** (heat in → electricity out):

* A **50V Steam Turbine** fed at its rated **10 bar** and venting to the open air has a Jade "pressure efficiency" of **100%**—which gives a true thermodynamic efficiency of **~10%** (about the same coal-to-watts ratio as the Compact Turbine, just capable of a full 1,000 W instead of 270 W).
* Let the boiler pressure sag to **2 bar**, and Jade's pressure efficiency drops to **29%**—meaning your true thermodynamic efficiency falls below **3%**! You are shovelling coal into a black hole.
* Bolt a **Condenser** onto the exhaust at 10 bar, and Jade shows **176%** of the open-air rating—which brings the plant's true thermodynamic efficiency up to **~18%**, nearly **twice the electrical power from the exact same coal**!

So I looked at my [boiling-point table](../reference/steam.md#the-boiling-point-table). To boil water at **10 bar** of pressure, the water inside the boiler *must* reach **180 °C**.

"Right," I said to myself. I opened my **Stone Heat Furnaces**, dragged their temperature set-point sliders to **250 °C** (giving plenty of thermal head above 180 °C so heat would flow fast across the boiler shell—and staying well clear of the **800 °C** mark where a furnace burns itself down, even though the slider stupidly lets you dial it to 1000 °C), hooked my workshop loads to the turbine, and stoked the coal.

Two things happened:

1. The furnaces ignored my 250 °C slider completely and roared at full blast. Why? **Because I hadn't put a Regulator item in the slot next to the slider!** Without a physical regulator component (an `On/OFF Regulator 1%`, `10%`, or `Analogic Regulator`) sitting in the furnace's regulator slot, the temperature set-point slider is disconnected; the fire only listens to the manual gain slider.
2. Even with the fires roaring, the moment my workshop machines kicked in and asked the turbine for heavy power, the boiler pressure plummeted from 9 bar down to **2.1 bar of steam**, the steam flow in Jade spiked as the turbine gasped for vapour, the voltage sagged, and everything ground to a crawl.

Why did the pressure collapse when the furnaces were hot?

Think of the boiler as a lungs-and-bagpipe problem. You are blowing air into the bag with your mouth (**the furnaces**), and the horn is letting air out to make music (**the turbine**).

The **furnace temperature set point does not create pressure directly; it only sets the ceiling** on how high the pressure *could* climb if the bag were corked shut. If the turbine has its throttle wide open and gulps grams of steam per second faster than your furnaces can boil water, the steam rushes out of the boiler before it can ever pack itself up to 10 bar! And once the pressure drops to 2 bar, the steam is so thin and gutless (29% rated work per gram!) that the turbine has to gulp *more than three times as much steam* to try to make the same wattage, which drags the boiler pressure down even harder. It’s a death spiral.

How do you break the spiral? You put an **Analogic Regulator** inside the **Steam Turbine's** slot—not just the furnace's—and switch the turbine screen to **"Hold the inlet at X bar"**.

> **Picture to come:** The 50V Steam Turbine GUI showing an Analogic Regulator installed in its component slot, the control mode set to Internal, and the inlet pressure target set to "Hold the inlet at 10 bar".

I popped an Analogic Regulator into the turbine and set the target to **10 bar**.

Instantly, the turbine pinched its own inlet valve shut. With the exit corked, the boiler stopped bleeding steam. As the coal fires soaked into the drum, the water temperature climbed past 150 °C, 170 °C, **180 °C**—and the pressure gauge marched right up to **10 bar of steam**. Only *then* did the turbine regulator crack the throttle open, letting out only as many grams per second of steam as the furnaces could actually boil at 10 bar, never letting the boiler collapse!

Of course, five minutes later, the boiler water level dropped below the glass and the safety valve started screaming.

Because I had made mistake number two: I had put a **50V Submersible Pump** in the cistern to feed a **10 bar** boiler.

A 50V Submersible Pump will happily push 181 millilitres of water a second into an open tank. **It stalls dead at 6 bar.** Once my boiler hit 10 bar, the steam pressure inside the drum was pushing back against the check valve harder than the little 50 V pump could shove! I had to step a line up to 200 V with a **DC-DC Converter** and drop a **200V Submersible Pump** into the cistern—that one pushes all the way to **15 bar**—before the water glass finally rose back to the half-way mark. (Both shut-off pressures are marked in [the boiling-point table](../reference/steam.md#the-boiling-point-table).)

> **Picture to come:** Complete fuel-fired condensing steam plant in a brick boiler room. Two Stone Heat Furnaces (with 4 Combustion Chambers each) sit flush against the back and left flank of a Boiler. The Boiler sits directly on top of a 50V Steam Turbine. A Small Steam Condenser is attached flush against the RIGHT side of the turbine. Iron Pipes with a Pipe Safety Valve (set to 10 bar) connect the condenser's front condensate pump flange and a 200V Submersible Pump (submerged in a floor water source block) into the boiler's right-flank feedwater port.

Then I bolted the **[Small Steam Condenser](../reference/steam.md#condensers)** onto the turbine’s right side (built with proper **high-quality iron plates** from my Plate Machine—the tooltip on them warns you straight out that hammered plates won't do for EA steam gear or pipe kits), piped its front pump flange back into the boiler feed, and watched the magic happen.

When a turbine vents to the room, the steam has to push against the outside atmosphere (1 bar). When it exhausts into a **Condenser**, the condenser cools that exhaust steam back into liquid water at **46 °C**, collapsing the exhaust pressure down to a near-vacuum (**0.1 bar of steam**). Instead of expanding from 10 bar down to 1 bar, the steam expands all the way down to 0.1 bar! My plant's true thermodynamic efficiency jumped from **~10% to ~18%**—nearly doubling my electrical output from the same coal, while pumping the condensed water straight back into the boiler in a closed loop.

The **[Pipe Safety Valve](../reference/steam.md#pipe-safety-valve)** on the top header let out one brief, ear-splitting whistle at 10.5 bar when I disconnected the macerator—I gave that white plume a four-block berth so it wouldn't scald me through my coat—and then the furnace regulators eased back and the whole room settled into a deep, steady purr.

For ten minutes I just stood there with my hands in my pockets, listening to the pump tick and watching the water bounce in the gauge glass. Half a mile away the birches are frozen in the middle of autumn, and the sky over the Bypass site is the colour of iodine, but in this room the water is 180 degrees and the needle is pinned on the mark.

*My tidied-up notes for this chapter: [Boilers, Pressure, Condensers and the Regulator](../reference/steam.md).*

---

Previous: [The Signal Processor: Sixty-Four Grains of Red Dust](07-signal-processor.md) · Next: none yet

[Back to the contents](../index.md)
