---
navigation:
  title: Boilers, Pressure and Condensers
  parent: reference.md
  position: 3
  icon: eln:50v_steam_turbine
item_ids:
  - eln:boiler
  - eln:50v_steam_turbine
  - eln:200v_steam_turbine
  - eln:small_steam_condenser
  - eln:steam_condenser
  - eln:50v_submersible_pump
  - eln:200v_submersible_pump
  - eln:pipe_kit_iron
  - eln:pipe_kit_alloy
  - eln:pipe_valve
  - eln:pipe_adapter
  - eln:pipe_safety_valve
  - eln:analogic_regulator
  - eln:on_off_regulator_1
  - eln:on_off_regulator_10
  - eln:fuel_heat_furnace
  - eln:50v_electric_heater
  - eln:200v_electric_heater
---

# Boilers, Pressure, Condensers and the Regulator

*Reference notes for [The Real Steam Plant](../diary/09-steam-plant.md).*

## The boiling-point table

Temperature and pressure: in a closed boiler containing both liquid water and steam, **pressure and boiling temperature are locked together**. A boiler cannot reach a given pressure unless its water is at least this hot. (The percentages marked "Jade" are Jade's *pressure efficiency*, not thermodynamic efficiency; see [thermodynamic efficiency](steam.md#thermodynamic-efficiency) below.)

| Absolute Pressure | Boiling Water Temp (°C) | Key Plant Milestone |
| :--- | :--- | :--- |
| **0.1 bar** | **46 °C** | **Condenser vacuum** (at 20 °C ambient air). |
| **1 atm (~1 bar)** | **100 °C** | Boiling begins; boiler capability starts offering steam. |
| **2 bar** | **120 °C** | Minimum safety valve setting; poor turbine efficiency (Jade 29% to air, under 3% thermodynamic). |
| **5 bar** | **152 °C** | Threshold to clear the *"Under Pressure"* warning (Jade 69% to air / 148% condensing). |
| **6 bar** | **159 °C** | **50V Submersible Pump shut-off limit!** (Flow drops to 0 mB/s here). |
| **10 bar** | **180 °C** | **Rated Turbine Inlet** (Jade 100% to air / 176% condensing; ~10% / ~18% thermodynamic); Iron Pipe working rating. |
| **15 bar** | **198 °C** | **200V Submersible Pump shut-off limit!** (Jade 119% to air). |
| **20 bar** | **212 °C** | High-pressure running (Jade 132% to air / 206% condensing). |
| **25 bar** | **224 °C** | **Maximum Small Steam Turbine inlet!** Above 25 bar, the turbine casing **EXPLODES**. |
| **40 bar** | **250 °C** | **Iron Pipe BURST pressure** (at cool temps; derates above 300 °C). |
| **100 bar** | **311 °C** | **Alloy Pipe** working rating (bursts at 300 bar; softens above 600 °C). |

*Note on Heat Offset:* Because heat must flow through the metal shell, **boiler water runs ~5 °C cooler than the boiler shell per kW** flowing through it. Set your furnace's regulator well above the boiling point you want (I run mine at 250 °C for 10 bar) so the heat moves briskly.

## Thermodynamic efficiency

True thermodynamic efficiency vs. Jade "pressure efficiency":

* **Thermodynamic Efficiency** (heat in from fuel → electrical watts out):
  * **50V Compact Steam Turbine:** **10%** (1 furnace with 4 chambers ≈ 2.7 kW net heat → **~270 W** sustained, 300 W peak).
  * **Real Steam Plant at 10 bar (venting to air):** **~10%** (requires **3.5 g/s of steam** [= 52 mB/s feedwater] per kW of electrical output).
  * **Real Steam Plant at 10 bar (with Condenser at 0.1 bar / 46 °C):** **~18%** (nearly **2× the electrical power** from the same fuel, plus closed-loop water recovery).
* **Jade "Pressure Efficiency"** shows how much work the turbine extracts per gram of steam *relative to its 10 bar open-air rating*:

| Inlet Pressure (bar of steam) | Boiling Temp (°C) | Jade Pressure Eff. (Venting to Air) | Jade Pressure Eff. (With Condenser) | Approx. Thermodynamic Eff. (Heat → Elec) |
| :--- | :--- | :--- | :--- | :--- |
| **2 bar** | 120 °C | 29% | 111% | ~2.9% (air) / ~11% (cond.) |
| **5 bar** | 152 °C | 69% | 148% | ~6.9% (air) / ~15% (cond.) |
| **10 bar (Rated)** | **180 °C** | **100%** | **176%** | **~10% (air) / ~18% (cond.)** |
| **15 bar** | 198 °C | 119% | — | ~12% (air) |
| **20 bar** | 212 °C | 132% | 206% | ~13% (air) / ~21% (cond.) |
| **25 bar (Max)** | 224 °C | 143% | — | *Above 25 bar, small turbine casing EXPLODES!* |

## The three-part control rule

Why players get stuck:

1. **Sliders Do Nothing Without a Regulator Item:** The **Stone Heat Furnace / Fuel Heat Furnace** (and the Electric Heater) and the **Steam Turbine** all have a slot for a Regulator component (`On/OFF Regulator 1%`, `10%`, or `Analogic Regulator`). If that slot is empty, the furnace ignores its temperature set point and burns according to its manual gain slider (an Electric Heater runs at **100% power**), and the turbine runs on a manual **0–100% throttle**.
2. **The Furnace Regulator Protects the Boiler (Ceiling):** Put a regulator in the furnace and set it to ~210–280 °C. This stops the fire from cooking the boiler past 10–15 bar when electrical demand is low. **It does NOT force the pressure up if you draw too much steam.**
3. **The Turbine Regulator Holds the Pressure (Floor):** Put an **Analogic Regulator** in the **50V / 200V Steam Turbine** and set **"Hold the inlet at 10 bar"**. If your machines pull more power than your furnaces can supply, the turbine will automatically throttle back to keep the boiler at 10 bar (180 °C) where expansion is efficient, rather than draining the boiler down to 2 bar. *(Caution: Do not set the turbine's bar target higher than your furnace's temperature can actually boil, or the turbine valve will stay shut forever!)*

## Firing the boiler

Furnaces vs. Electric Heaters:

* **Stone Heat Furnace** (up to 3 kW with 4 Combustion Chambers; ~2.7 kW net; see [the early heat notes](early-heat.md#device-numbers)) or **Fuel Heat Furnace** (burns piped gasoline, diesel, or heavy oil; Small **5 kW**, Medium **10 kW**, or Big **25 kW** Fuel Burner; 25 mB internal tank requires continuous piping; 20 s fire lag, 3 s to light; fails at **800 °C**): Place against the boiler's **back or left/right flanks**, or connect via **Copper Thermal Cable**.
* **50V (1 kW) / 200V (4 kW) Electric Heaters:** Convert electricity 100% into heat. An Electric Heater runs **~25 °C hotter than the water per kW** flowing through it (to hold 180 °C water at 10 bar under load, set a 1 kW heater's regulator to **~205 °C**, or a 4 kW heater to **~280 °C**). **Never use an Electric Heater to generate electricity in a steam loop** (100% electric → heat → 18% electric loses 82% of your power!). Use them only to spend surplus renewable/grid electricity on process heat (e.g., distilling oil or baking), or as a small control trim.

## Plumbing and hardware

Rules of thumb for plumbing and ports.

* **High-Quality Plates Required:** Pipe Kits (`Iron Pipe Kit`, `Alloy Pipe Kit`), Pipe Valves, Pipe Adapters, and both Steam Condensers require plates matching `#eln:high_quality_plates/<metal>` (made in EA's **Plate Machine**; the item tooltip explicitly notes that hammered plates will not work).

### Boiler

8,000 mB capacity.

* *Heat In:* Back and both side flanks (direct furnace/heater or Copper Thermal Cable).
* *Feedwater In:* Right flank flange (built-in check valve).
* *Steam Out:* Bottom dome port (place the boiler **directly on top of** a 50V/200V Steam Turbine) or Top flange port via **EA Pipes**.
* *Level Control:* The **front pin** outputs a 50 V logic HIGH signal whenever water is above 50% glass (±3% deadband). Invert this signal with a NOT chip or an inverted Relay to switch your feedwater pump on when water drops below half!
* *Overheat:* Boiling completely dry pushes the shell past **420 °C**, burning out the boiler.

### Steam turbines

The 50V (1 kW) and 200V (4 kW) Steam Turbines:

* *Inlet:* **Top port ONLY**, fed directly by a Boiler on top or an **EA Pipe** (ignore the old tooltip; other mods' fluid pipes cannot connect to small turbines!). Requires **52 mB/s of feedwater per kW** at 10 bar.
* *Water Hammer / Flooding:* If liquid water in the steam inlet exceeds **10% while spinning above 20% speed**, the turbine suffers water damage and burns out (Repair: 1 × **Electrical Motor**; repairing also drains the water). Let the boiler reach 100 °C before piping steam!
* *Note on the Big Shaft Steam Turbine (16 kW):* The large mechanical shaft turbine is the exact opposite—it takes steam **only** via fluid capability (other mods' pipes, or an EA pipe fitted with a **Pipe Adapter**), and **EXPLODES from overspeed (above 1000 rad/s) if run unloaded!**

### Condensers

The Small (4.2 kW) and Standard (16.9 kW) Steam Condensers:

* Place flush against the **right flank** of the 50V/200V Steam Turbine.
* *Cold Start Rule:* The condenser's return pump (front flange, 15 bar max) is driven by the turbine shaft. When the turbine is stopped, the pump is stopped! **Fill the boiler directly on a cold start.**
* *Makeup Water & Flooding:* Feed makeup water into the condenser's **top port** (its internal float valve opens below 15% and shuts at 25%). Never force water in past **50% fill**, or it will back up and flood the turbine!

### Pipe safety valve

* **Boilers have NO built-in safety valve.** Always right-click a **Pipe Safety Valve** onto a straight stretch of steam pipe.
* Empty-hand click cycles the set point (`2, 5, 8, 10, 15, 20, 30, 50, 75, 100, 150 bar`; defaults to 10 bar on iron pipe).
* **SCALD HAZARD:** When pressure exceeds +5% of the set point (or a pipe bursts), it vents a steam jet up to **4 blocks** long. Deals up to 8 damage every 0.25 s **straight through all armour** (including E-Coal). Aim safety valves toward a solid wall or the ceiling!

### Jade fluid readouts

Jade displays steam content in **grams (`g`)** and flow in **grams per second (`g/s`)**, and explicitly labels every pressure reading with its medium (e.g., `10.0 bar of steam`).

---

[Reference index](../reference.md) · [Back to the contents](../index.md)
