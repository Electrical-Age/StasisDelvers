---
navigation:
  title: Electricity, Cables and Protection
  parent: reference.md
  position: 1
  icon: eln:low_voltage_cable
item_ids:
  - eln:tree_resin
  - eln:tree_resin_collector
  - eln:rubber
  - eln:copper_cable
  - eln:iron_cable
  - eln:tungsten_cable
  - eln:copper_coil
  - eln:signal_cable
  - eln:low_voltage_cable
  - eln:medium_voltage_cable
  - eln:high_voltage_cable
  - eln:very_high_voltage_cable
  - eln:cable_kit_signal
  - eln:cable_kit_lv
  - eln:cable_kit_mv
  - eln:cable_kit_hv
  - eln:cable_kit_vhv
  - eln:chisel
  - eln:ground_cable
  - eln:electrical_fuse_holder
  - eln:lead_fuse_for_low_voltage_cables
  - eln:lead_fuse_for_medium_voltage_cables
  - eln:lead_fuse_for_high_voltage_cables
  - eln:lead_fuse_for_very_high_voltage_cables
  - eln:blown_lead_fuse
  - eln:electrical_breaker
  - eln:multimeter
  - eln:universal_meter
  - eln:electrical_probe_chip
---

# Electrical Fundamentals, Cables and Protection

*Reference notes for [First Light](../diary/01-first-light.md).*

## The four equations

The four equations you actually need:

* **Power:** `P = U × I` *(Watts = Volts × Amps)*
* **Voltage Drop along a cable:** `U_drop = I × R_cable` *(Current × Resistance per block × Length in blocks)*
* **Cable Heating:** `Heat = I² × R_cable` *(At `I_nominal`, cable rises ~90 °C over ambient; at `1.2 × I_nominal`, it reaches **150 °C** and burns, setting nearby flammables on fire. 30 s thermal time constant.)*
* **Stored Energy:** `Energy (J) = Power (W) × Time (s)` *(`1 kJ = 1,000 J`, which is 1,000 W for 1 second, or 100 W for 10 seconds.)*

## Voltage tiers and cables

Every electrical cable can be placed old-style (six-node, paintable with **Brushes**) or packed into a **Cable Kit** (holds up to 64 blocks / 1024 voxels; routes along solid faces via right-click waypoints; cannot be painted; **burnt voxel cables cannot be repaired** and drop nothing when broken).

| Cable Tier | Nominal U | Nominal Power | Nominal I | Burns at Sustained I (1.2×) | Burns at Overvoltage (1.3×) | Resistance (R per block) | Recipe (for 6 coils, or 1 upgrade) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Signal Cable** | **5 V** | 0.5 W | 0.1 A | *No heat fail* | **6.5 V** | 0.1 Ω | 6 Iron Ingots + 6 Rubber (or 1 Iron Cable + 1 Rubber) |
| **Low Voltage (LV)** | **48 V** | **1 kW** | **20.8 A** | **25 A** (150 °C) | **62.4 V** | **0.023 Ω** | 3 Copper Ingots + 6 Rubber (or 1 Copper Cable + 1 Rubber) |
| **Medium Voltage (MV)** | **230 V** | **3 kW** | **13 A** | **15.7 A** (150 °C) | **299 V** | **0.088 Ω** | 1 LV Cable + 1 Rubber |
| **High Voltage (HV)** | **400 V** | **5 kW** | **12.5 A** | **15 A** (150 °C) | **520 V** | **0.05 Ω** | 1 MV Cable + 1 Rubber |
| **Very High (VHV)** | **11 kV** | **15 kW** | **1.36 A** | **1.64 A** (150 °C) | **14.3 kV** | **1.58 Ω** | 3 Alloy Ingots + 6 Rubber (6) |

* **Bare Crafting Wires (NOT placeable):** **Copper Cable** (3 copper ingots → 3), **Iron Cable** (3 iron ingots → 6), **Tungsten Cable** (3 tungsten ingots → 6).
* **Copper Coil** (4 copper ingots around a stick → 4): Fine winding wire used in Heating Corps, DC-DC Converters, Resistors, Inductors, and Thermistors. *(Do not confuse with Copper Cable!)*
* **Chisel** (2 iron ingots + 1 stick; press **C** to open radial menu for 1/2/4/8 voxel cube, flat, or **tunnel**): Carves a "cable hole" through solid walls that imitates the original block while letting voxel cables and pipes pass through.

## DC-DC converters

A **DC-DC Converter** joins two tiers. Each **cable in a winding slot is one turn**, up to 64 a side, and the voltage
ratio is the ratio of the turns: 5 turns of LV in and 24 of MV out make 230 V from 48 V.

* **The core sets how many volts a turn may carry:** Cheap **4 V**, Average **8 V**, Optimal **16 V** per turn. 230 V
  needs 58 turns on a cheap core, 29 on an average one, 15 on an optimal one. 48 V needs 12 turns on a cheap core.
* **Too few turns and the core saturates:** Jade says **SATURATED**, the converter pulls a heavy extra current from its
  primary, heats up, and burns within seconds. Add turns, or fit a better core.
* **The windings heat with their current:** run a winding over about 1.3 × its cable's rated current and the converter
  overheats and burns. Jade shows its temperature.
* A converter built before the move to 48 V keeps working as it was until you change its windings or core.

The **Variable DC-DC Converter** (a DC-DC Converter, an Advanced Chip and a Signal Cable) does the same, and a signal on
its **front** chooses how many secondary turns are used: 0 V one turn, 5 V all of them. Feed it from a Signal
Processor to hold an output voltage. It always runs isolated.

## Circuit protection

Circuit protection and failure rules: when a network is overloaded, **only ONE failure occurs per tick—the single weakest component on the net**, which relieves the stress on everything else. Put a cheap **Fuse** or **Breaker** at the head of your line so it trips before your cables or machines burn!

> **Picture to come:** A wall distribution panel. A Low Voltage Cable enters from the left into an Electrical Fuse Holder (holding a Lead Fuse), passes through an Electrical Breaker and an inline Electrical Probe, and branches out via a Hub to workshop machines, with a Ground Cable fixed at the bottom.

* **Electrical Fuse Holder + Lead Fuse:** Blows cleanly at **~1.08× nominal current** (**LV: 22.5 A**, **MV: 14.1 A**, **HV: 13.5 A**, **VHV: 1.47 A**). Right-click with a new Lead Fuse to swap; empty-hand click removes the `Blown Lead Fuse`. The holder never burns.
* **Electrical Breaker:** Place a cable of the desired tier in its internal slot to set the current trip (**~1.05× nominal**, LV ≈ 21.9 A). Also trips outside its `U_min` / `U_max` voltage window. Its default `U_max` is **48 V**: on a 230 V or 400 V line, open its GUI and raise `U_max` first, or it trips at once.
* **Ground Cable:** Required 0 V circuit return reference. Cannot burn.
* **MultiMeter / AllMeter:** Right-click any device or cable to print Voltage (`U`), Current (`I`), and Power (`P`) to your log without opening its GUI. *(Tip: Crafting the Electrical Probe Chip for the MultiMeter requires 1 × **High Voltage Cable**.)*

---

[Reference index](../reference.md) · [Back to the contents](../index.md)
