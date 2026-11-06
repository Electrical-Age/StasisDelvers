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

Cables are laid with a **Cable Kit** (it holds up to 64 blocks / 1024 voxels; right-click faces to route along solid
faces via waypoints). **Burnt voxel cables cannot be repaired** and drop nothing when broken.

A power cable is a **specification**: its kit's tier is its **insulation** (48 V, 230 V, 400 V or 11 kV), and it has a
**copper cross-section** and a **colour**. The kit's tooltip says which, e.g. "2.5 mm², 48 V, 21 A, brown". Crafted
kits are the presets below; other specs will come from the wire-drawing machine. A brush paints a placed cable.

| Kit (preset) | Insulation (burns at 1.3×) | Copper | Rated current (burns at 1.2×) | Power | R per block | Colour |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Signal** | 5 V (6.5 V) | – | 0.1 A | 0.5 W | 0.1 Ω | black |
| **Low Voltage** | 48 V (62.4 V) | 2.5 mm² | 20.8 A (25 A) | 1 kW | 0.023 Ω | brown |
| **Medium Voltage** | 230 V (299 V) | 1.5 mm² | 13 A (15.7 A) | 3 kW | 0.038 Ω | black |
| **High Voltage** | 400 V (520 V) | 1.5 mm² | 13 A (15.7 A) | 5.2 kW | 0.038 Ω | grey |
| **Very High Voltage** | 11 kV (14.3 kV) | 0.5 mm² | 3 A (3.6 A) | 33 kW | 0.115 Ω | red |

Copper's rating by cross-section: 0.5 mm² 3 A, 0.75 mm² 6 A, 1 mm² 10 A, 1.5 mm² 13 A, 2.5 mm² 21 A, 4 mm² 28 A,
6 mm² 36 A, 10 mm² 50 A, 16 mm² 66 A, 25 mm² 88 A, 35 mm² 109 A, 50 mm² 133 A, 70 mm² 170 A, 95 mm² 205 A. The
resistance falls with the cross-section, as in real copper: the same power at a higher voltage loses less a block.
Thick copper makes a wider cable. A thin cable may branch off a thick one of the same insulation.

Kit recipes (6 coils' worth, or 1 upgrade): Signal 6 Iron Ingots + 6 Rubber (or 1 Iron Cable + 1 Rubber); LV 3 Copper
Ingots + 6 Rubber (or 1 Copper Cable + 1 Rubber); MV 1 LV Cable + 1 Rubber; HV 1 MV Cable + 1 Rubber; VHV 3 Alloy
Ingots + 6 Rubber.

* **Bare Crafting Wires (NOT placeable):** **Copper Cable** (3 copper ingots → 3), **Iron Cable** (3 iron ingots → 6), **Tungsten Cable** (3 tungsten ingots → 6).
* **Copper Coil** (4 copper ingots around a stick → 4): Fine winding wire used in Heating Corps, DC-DC Converters, Resistors, Inductors, and Thermistors. *(Do not confuse with Copper Cable!)*
* **Chisel** (2 iron ingots + 1 stick; press **C** to open radial menu for 1/2/4/8 voxel cube, flat, or **tunnel**): Carves a "cable hole" through solid walls that imitates the original block while letting voxel cables and pipes pass through.

## DC-DC converters

A **DC-DC Converter** joins two tiers. Each **cable in a winding slot is one turn**, up to 64 a side, and the voltage
ratio is the ratio of the turns: 5 turns of LV in and 24 of MV out make 230 V from 48 V.

* **The core sets how many volts a turn may carry:** Cheap **4 V**, Average **8 V**, Optimal **16 V** per turn. 230 V
  needs 58 turns on a cheap core, 29 on an average one, 15 on an optimal one. 48 V needs 12 turns on a cheap core.
* **Making the cores:**
  * **Cheap** (solid iron): seven iron ingots.
  * **Average** (laminated silicon steel): craft **Silicon Steel Laminations** (6 Iron Plates, 2 Silicon Plates, 1
    Dielectric), then anneal them in an **Electrical Furnace** between **550 and 700 °C**. They make no progress below
    550 °C and spoil to Iron Dust above 750 °C. A 230V Tungsten Heating Corp with Thermal Insulation and a regulator
    set to about 650 °C does it.
  * **Optimal** (nanocrystalline): craft a **Nanocrystalline Core Blank** (4 Iron Dust, 2 Silicon Dust, 1 Copper Dust,
    1 Tungsten Dust, 1 Alloy Plate), then give it a long pass through a **Magnetizer** (200 kJ): it is annealed in a
    magnetic field.
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

* **Electrical Fuse Holder + Lead Fuse:** one fuse for every cable. Right-click the holder with a Lead Fuse to put it in; an empty-hand click opens its screen, where you set its **rating** in amps and take the fuse out (a `Blown Lead Fuse` too). It blows at about **1.08× its rating**; unset, it takes the fuse's own rating (21 A). Set it to your cable's rated current. The holder never burns.
* **Electrical Breaker:** Place a cable in its slot (it sets the contacts' resistance), and set the **trip current** in amps in its screen; unset, it trips at about 1.05× that cable's nominal current (LV ≈ 21.9 A). It also trips outside its `U_min` / `U_max` voltage window. Its default `U_max` is **48 V**: on a 230 V or 400 V line, open its GUI and raise `U_max` first, or it trips at once.
* **Ground Cable:** Required 0 V circuit return reference. Cannot burn.
* **MultiMeter / AllMeter:** Right-click any device or cable to print Voltage (`U`), Current (`I`), and Power (`P`) to your log without opening its GUI. *(Tip: Crafting the Electrical Probe Chip for the MultiMeter requires 1 × **High Voltage Cable**.)*

---

[Reference index](../reference.md) · [Back to the contents](../index.md)
