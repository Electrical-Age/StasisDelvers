---
navigation:
  title: "Fire and the Kettle: A Kettle with a Dynamo"
  parent: diary.md
  position: 2
  icon: eln:50v_compact_steam_turbine
---

# Fire and the Kettle: A Kettle with a Dynamo

**Day 4 — Stability at the fencepost: 74%**

The old spawn valley went still at four o’clock this morning.

I didn't hear it happen—that’s the worst part about the Stasis, it doesn't make a sound when it takes a place—but I woke up because the wind stopped rattling the corrugated iron on the woodshed. When I looked out the kitchen window, the smoke from my charcoal pit down by the birches was standing straight up in the air like a twisted grey pillar, frozen solid. Half the sheep in the lower paddock are statues now. I’d go down with a lead and drag them up the hill like the pamphlet suggests, except I don't need mutton right now; I need wool, and you can't shear a sheep that has the structural properties of granite.

I moved my bedroll and the crates up here to the highlands ridge. The air still moves up here. The grass still shivers. For now.

If I am going to run a **Macerator** to double my ore yield and a **Plate Machine** to roll proper high-grade iron sheets, I cannot rely on the little **[Water Turbine](../reference/early-heat.md#device-numbers)** I left down in the creek—mostly because the creek is currently an ice sculpture at twenty degrees Celsius, and even when it was flowing it barely gave me thirty watts unless it was pouring rain. And the **[Wind Turbine](../reference/early-heat.md#device-numbers)** takes an Electrical Motor and rolled iron plates I don't have yet.

So today I built a kettle.

Well. Technically it is a **50V Compact Steam Turbine**, married to a **Stone Heat Furnace**. Back at the Authority we would have called it a packaged cogeneration unit, though if you showed this particular contraption to a boiler inspector he would have a stroke on the spot. It is an all-in-one bootstrap box: tiny boiler, miniature governor, dynamo, and a little air-cooled condenser coil all crammed into one iron casing.

> **Picture to come:** Early bootstrap setup in a cramped wooden shed. A Stone Heat Furnace sits flush against the LEFT side of a 50V Compact Steam Turbine. Low Voltage Cable runs from the turbine's output to a Ground Cable staked into the floor and up to a wall-mounted Lamp Socket A holding a glowing 50V Carbon Incandescent Light Bulb.

You don't even have to pipe it. You set the **Stone Heat Furnace** flush against the **left flank** of the Compact Turbine, tip a single bucket of water into the turbine, and shove coal into the firebox. Heat conducts straight across the stone-to-iron face. The water boils, spins the little impeller, condenses against the air fins, and drips back down to boil again. One bucket of water lasts for hours. (The step-by-step is in [the reference notes](../reference/early-heat.md#building-the-bootstrap-plant).)

It’s foolproof. Which is why I spent two hours this afternoon wondering why my light bulb looked like a dying glow-worm.

Let me explain what went wrong, Kid, because if you don't understand the difference between **temperature** and **heat**, this mod will drive you to drink.

I had wired a **Low Voltage Cable** from the turbine to a **Lamp Socket** on the rafters (after remembering to open the socket's little panel and flip it from wireless *Lamp Supply* mode to *Cable* mode—every socket comes out of the box expecting a wireless transmitter for reasons known only to God), screwed in a **25 W Carbon Incandescent Bulb**, and hooked up my first **50V Electrical Furnace** beside it.

I lit the Stone Heat Furnace. I watched the thermometer climb. Three hundred degrees. Four hundred. Five hundred degrees Celsius. The furnace was roaring, glowing cherry-red behind its grate, and through the side of the **50V Compact Steam Turbine** I could see its little spoked flywheel spinning like a sewing machine.

Then I switched on the Electric Furnace, and the light bulb immediately dimmed to the colour of cold tea. I tapped the turbine with my **MultiMeter**.

*U = 31.0 V. P = 81 W.*

Eighty-one watts! The handbook sketch I’d seen for the Compact Turbine said it topped out at **300 W**—or **~270 W** sustained on a single furnace. My furnace was five hundred degrees! Why was I getting less than a third of the rated power?

I crouched down and looked closely at the diagnostic readout on the turbine casing. Right there at the bottom, plain as paint:

`Short of heat: add combustion chambers.`

(What that line and its neighbours mean is written up under [reading the gauges](../reference/early-heat.md#reading-the-gauges).)

**Day 5 — Stability at the fencepost: 71%**

I had a cup of chicory coffee this morning and apologized to Carnot.

Here is the trap every layman falls into: you think **temperature** (°C) is the same thing as **heat flow** (Watts). It isn't.

Imagine you are trying to fill a bathtub. **Temperature** is how *high* you hold the jug above the tub. **Heat** (thermal power, measured in Watts, or Joules per second) is how *wide the spout is*—how much water is actually pouring out of the jug every second. A candle flame is a thousand degrees Celsius, Kid. Its "height" is enormous. Try boiling a five-gallon soup pot with a single birthday candle and tell me when dinner is ready.

A bare **Stone Heat Furnace**, straight out of the crafting bench, only has a 1,000-watt fire inside it (1 kW of heat)—and once the stone shell warms up and loses a bit of heat to the room, it pushes roughly **800 W** into the turbine.

Now look at the **50V Compact Steam Turbine**. Why is it so easy to build and so forgiving to run? Because its **thermodynamic efficiency is ten percent (10%)**. Write that phrase down, because we are going to measure every heat engine in this ledger by the same yardstick: *for every ten watts of heat you put into the machine, how many watts of electricity come out the wires?* In the Compact Turbine, one watt in ten comes out the front as 50 V electricity, and the other nine watts are thrown away into the room through its little air-cooled condenser fins.

Do the arithmetic on the back of an envelope:

* A bare Stone Heat Furnace delivers about **800 W** of net heat into the boiler → **~80 W** of electricity.
* Every **Combustion Chamber** you slide into the furnace's chamber slot adds **500 W** to the fire (up to **4 chambers**, for a 3,000 W fire that delivers **~2.7 kW** of net heat after shell losses).
* Ten percent of 2.7 kW is **~270 W** of continuous, rock-steady 50 V power (and it will briefly surge to its full **300 W** ceiling using the heat stored in the water before settling back to 270 W).

> **Picture to come:** Close-up of the Stone Heat Furnace GUI showing 4 Combustion Chambers installed in the chamber slot, coal in the fuel slot, and the manual gain slider pushed to 100%.

I cobbled together four more Combustion Chambers before noon, shoved them into the furnace, and waited about three minutes for the coal bed to catch up. By one o’clock the little spoked flywheel was a blur, holding a steady **50 V** at **270 W**. When I switched the loads off, the turbine didn't even blow off steam; it just idled quietly at **155 °C**, recycling its condensate so cleanly that a single bucket of water lasts nearly two and a half hours at no load. (All the furnace and turbine figures are in [the device table](../reference/early-heat.md#device-numbers).)

Better yet: now that the **Lamp Socket** above the shed door has a steady fifty volts and a lit **Carbon Incandescent Bulb** burning inside it, I can finally sleep through the night. Torchlight never kept the deeper things out of the yard for long, but there is something about real electric light—a lit EA bulb in a powered socket—that the things in the dark cannot stomach. While that lamp is lit, nothing foul can spawn within nine paces of the socket. Tonight a pair of zombies shambled up from the treeline, squinted into the glare of the twenty-five-watt filament, and turned right back into the pines. (It won't stop the Endermen, mind you. Nothing stops the Endermen.)

*My tidied-up notes for this chapter: [Early Heat and the 50V Compact Steam Turbine](../reference/early-heat.md).*

---

Previous: [First Light: Bleeding the Trees](01-first-light.md) · Next: [The Signal Processor: Sixty-Four Grains of Red Dust](07-signal-processor.md)

[Back to the contents](../index.md)
