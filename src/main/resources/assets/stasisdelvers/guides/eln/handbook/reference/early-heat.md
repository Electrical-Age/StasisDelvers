---
navigation:
  title: Early Heat and the Compact Turbine
  parent: reference.md
  position: 2
  icon: eln:stone_heat_furnace
item_ids:
  - eln:stone_heat_furnace
  - eln:combustion_chamber
  - eln:thermal_insulation
  - eln:copper_thermal_cable
  - eln:cable_kit_thermal
  - eln:50v_compact_steam_turbine
  - eln:wind_turbine
  - eln:water_turbine
  - eln:lamp_socket_a
  - eln:50v_carbon_incandescent_light_bulb
---

# Early Heat and the 50V Compact Steam Turbine

*Reference notes for [Fire and the Kettle](../diary/02-fire-and-kettle.md).*

## Building the bootstrap plant

How to build the bootstrap power plant. Use this setup for your first 100–300 W of 50 V power before you have the plates, pumps, and regulators for a high-pressure boiler plant.

> **Picture to come:** Top-down and front isometric diagram of the 50V Compact Steam Turbine setup. Left block: Stone Heat Furnace (back face or heat face oriented into the turbine's left flank). Center block: 50V Compact Steam Turbine. Right/Front: Low Voltage Cable connected to a Ground Cable and loads.

1. **Place the 50V Compact Steam Turbine.** Note which way the front faces.
2. **Place the Stone Heat Furnace directly against the LEFT side** of the Compact Turbine (with the furnace's heat-output back face coupled to the turbine). For separated placement, bridge them with **Copper Thermal Cable** (limit 1000 °C; loses 0.001 K/W per block), though direct placement has zero line loss.
3. **Add Water:** Right-click the Compact Turbine with a **Water Bucket**. Because it has a built-in air-cooled condenser, it recycles almost all its water; one bucket lasts for hours unless you severely overheat it and it vents from the stack.
4. **Upgrade the Furnace:** Craft **4 × Combustion Chamber** (stone rings) and insert all 4 into the Stone Heat Furnace's chamber slot.
5. **Wire the Output:** Run **Low Voltage Cable** (or a **Low Voltage Cable Kit**) from the turbine's electrical port to your 50 V net, and attach a **[Ground Cable](electricity.md#circuit-protection)** (5 copper cables) anywhere on the net to give the circuit a 0 V reference.

## Device numbers

| Device / Parameter | Value / Rating | Notes and Failure Limits |
| :--- | :--- | :--- |
| **Stone Heat Furnace** | **1,000 W** base heat | About **800 W** net into a boiler after shell losses. Burns any solid vanilla fuel (burn time × **2.5**). Takes ~30 s to kindle, ~180 s lag to adjust. Dies out if set below 15%. |
| **Combustion Chamber slot** | **+500 W** per chamber | Max **4 chambers** = **3,000 W (3 kW)** total heat output, about **2.7 kW** net after shell losses. |
| **Thermal Insulation slot** | Halves heat loss | Crafted from 4 wool (any colour) + stone. **Warning:** Consumes 1 insulation item whenever furnace exceeds **520 °C**. |
| **Furnace Failure Limit** | **800 °C** (shown) | **TRAP:** The GUI set-point slider goes up to **1000 °C**! Never set it above 750 °C or it will burn + catch fire (Repair: 1 × **Machine Block**). |
| **50V Compact Steam Turbine** | **50 V**, **~270 W** sustained, **300 W** peak | **10% thermodynamic efficiency.** One 4-chamber Stone Heat Furnace (~2.7 kW net) sustains **~270 W**, briefly surging to **300 W** on heat stored in the water; a bare furnace (0 chambers) gives **~80 W**. Idles at **~155 °C** with no load; one bucket of water lasts **~2.4 h** at no load. Built-in boiler, governor, and air condenser. |
| **Wind Turbine** *(Alternative)* | **50 V** (59 V max), ~160 W at 10 m/s | 4-block tall multiblock. Scale: `min(y, 100)/100`. Needs a clear 15×5×5 air box (−7% per solid block past 3). Boosted in rain/thunder. Cannot fail. |
| **Water Turbine** *(Alternative)* | **50 V** (59 V max), **30 W** | Needs **flowing** water 1 block out and 1 block down from front. Up to ~3× in local rain (10 s lag). Cannot fail. |
| **Lamp Sockets** *(First Load)* | **50 V** built-in LV wire | **Default GUI mode is Wireless ("Lamp Supply")!** Open GUI and switch to **Cable** if wiring directly. Suppresses monster spawns within **9 blocks only while the lamp is lit** (Endermen/Withers exempt). |

## Reading the gauges

What the Jade and meter readings mean:

* **`Short of heat: add combustion chambers`**: Your turbine is demanding more thermal wattage than the furnace can produce. Add Combustion Chambers (up to 4) or stoke the fire slider to 100%.
* **`Ambient: XX °C`**: Shown on all thermal devices (crouch to expand Jade). Devices cool toward local ambient (Plains 20 °C, Desert 50 °C, Snow 0 °C, Nether 90 °C; underground blends to 15 °C + 0.1 °C per block depth). Hot biomes reduce your cooling margin!
* **High Furnace °C with Low Turbine W**: You are lightly loaded or disconnected. Heat has nowhere to go, so temperature climbs. Without a **Regulator** item (On/Off 1%, On/Off 10%, or Analogic) in the furnace's regulator slot, the furnace ignores temperature and burns according to the manual gain slider. (More on regulators in [the three-part control rule](steam.md#the-three-part-control-rule).)

---

[Reference index](../reference.md) · [Back to the contents](../index.md)
