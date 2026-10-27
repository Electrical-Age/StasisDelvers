---
navigation:
  title: The Signal Processor Manual
  parent: reference.md
  position: 4
  icon: eln:signal_processor
item_ids:
  - eln:signal_processor
---

# The Signal Processor: Complete Manual

*Reference notes for [Sixty-Four Grains of Red Dust](../diary/07-signal-processor.md).*

## Hardware and pins

Hardware, pins and voltage scaling.

* **Recipe (Shaped):**
  * Top and Bottom rows: `Iron Ingot` – `Signal Cable` – `Iron Ingot`
  * Middle row: `Signal Cable` – `Cheap Chip` – `Signal Cable`
* **Pin Layout** (coloured dots appear when you stand within 15 blocks):
  * **Front (Black dot):** **Output** (0–50 V signal driver, 0.1 A class; drives relays, valves, lamp supplies, etc.).
  * **Right of front (Red dot):** Input **A**
  * **Back / opposite output (Green dot):** Input **B**
  * **Left of front (Blue dot):** Input **C**
  * *Auto-Connection Rule:* An input pin (A, B, or C) physically connects to adjacent signal cables **only if that capital letter appears in your valid formula**. If your formula is `A*0.5`, only the front (Output) and right (A) pins exist; B and C disconnect.
* **Internal 0..1 Scale (Crucial!):**
  * Inside the formula, **50 V equals `1.0`** and **0 V equals `0.0`**.
  * Every input (`A`, `B`, `C`) is read as `U_in / 50`, clamped to `[0, 1]`. Thus **25 V reads as `0.5`**, **10 V reads as `0.2`**, and **40 V reads as `0.8`**.
  * The final result of your formula is clamped to `[0, 1]` and multiplied by **50 V** at the output pin (`0.4` → **20 V**). `NaN` (e.g., `0/0`) outputs **0 V**; `+Infinity` (e.g., `1/0`) clamps to `1.0` (**50 V**).
* **Execution Step:** Runs at **20 Hz** (once per tick, `Δt = 0.05 s`). All time constants, integrals, and derivatives are measured in **seconds**.

> **Picture to come:** Close-up of the Signal Processor GUI showing the 64-character "Output voltage formula" text field containing `rs(A>0.8,A<0.6)`, the green status line reading "5 Redstone(s) required", and 5 Redstone Dust in the component slot.

## Syntax rules and parser traps

* **Max Length:** **64 characters**. Press **Enter** or click outside the box to commit.
* **Case-Sensitive:** Write `A`, `B`, `C`, `daytime`, and all function names (`min`, `rs`, `pid`, `batteryCharge`, etc.) in **exact case**. `a+b` or `MIN(A,B)` gives `Invalid equation!`. (Both `pi` and `PI` work for π ≈ 3.14159.)
* **Spaces:** Spaces are stripped (`A + B` becomes `A+B`), **except between two letters/numbers** where `A B` becomes the invalid identifier `AB`.
* **No Implicit Multiplication:** `2A` or `2(A+B)` is invalid. Always write `*`: `2*A`, `2*(A+B)`.
* **No Negative Scientific Exponents:** `1.5e2` works, but **`1e-3` is INVALID** because the parser splits `-` off as a subtraction operator! Write decimals out in full: `0.001`.
* **Two-Argument Limit on `min` / `max`:** `min(A,B,C)` is invalid. Nest them instead: `min(min(A,B),C)`.
* **Missing Operators:** There is no `>=`, `<=`, `==`, `!=`, `sqrt`, or power symbol. For a square root of `x`, write `pow(x,0.5)`.
* **How to Reset Stuck Memory:** Editing and committing a *changed* formula resets all internal memory (`rs`, `integrate`, `pid`, `rc`, `ramp`) back to `0`. Re-pressing Enter on unchanged text does nothing—**add or remove a trailing space** to force a reset! Pulling the redstone *freezes* memory in place without resetting it.
* **100-Step Parser Complexity Budget:** Even if under 64 characters, an expression with too many operators fails if its internal reduction count exceeds **100** (Brackets/functions = 1 step; `*` `/` unary `-` `!` = 3; `+` `-` = 4; `<` `>` = 5; `=` `^` `&` `|` = 6. E.g., 16 `&` operators works [96 steps], 17 `&` fails [102 steps]).

## Operator precedence and logic traps

Operators are evaluated from **Level 1 (tightest)** to **Level 5 (loosest)**, left-to-right within each level:

| Precedence Level | Operators | Crucial Traps and Behaviour |
| :--- | :--- | :--- |
| **1 (Tightest)** | `func(...)`, `( )` | Brackets cost **0 Redstone**—use them freely! |
| **2** | `-x` (negate), `!x`, `*`, `/` | **`!x` is arithmetic `1 - x`**, NOT boolean NOT! (`!0.3` = `0.7`). |
| **3** | `+`, `-` | Standard addition and subtraction. |
| **4** | `>`, `<` | Strict comparison. Returns `1.0` (50 V) if true, `0.0` (0 V) if false. |
| **5 (Loosest)** | `=`, `^`, `&`, `\|` | **TRAP: All four share the exact same precedence, evaluated left-to-right!** |

* **Trap 1 (`&` vs `|`):** In this processor, `&` (AND) does **not** bind tighter than `|` (OR)! `A|B&C` is evaluated as `(A|B)&C`. **Always put brackets around `&` terms:** `A|(B&C)`.
* **Trap 2 (`^` is XOR, NOT Power):** `A^B` means logical Exclusive-OR (true if one input is `> 0.5` and the other is `≤ 0.5`). For exponents (A to the power B), use `pow(A,B)`.
* **Trap 3 (`=` is Logical XNOR, NOT Numeric Equality):** `A=B` tests whether `(A>0.5)` has the same truth value as `(B>0.5)`. Thus **`0.6=0.9` evaluates to `1` (True)** because both are `> 0.5`!
* **Logic Threshold:** All logic operators (`&`, `|`, `^`, `=`, `if`) treat an input as **True when `> 0.5`** (`> 25 V`). The sole exception is `rs()`, which triggers at **`> 0.6`** (`> 30 V`).

## Functions and redstone cost

Your slot must contain **at least the sum of the Redstone costs** of every operator and function in your formula (max **64**). Variables (`A,B,C,pi,daytime`), numbers, and brackets `( )` cost **0**.

| Syntax | Redstone Cost | What It Computes and State Rules |
| :--- | :--- | :--- |
| `+`, `-`, `*`, `/`, `-x` | **1** each | Basic arithmetic. |
| `!x` | **1** | Inverter: computes **`1 - x`**. |
| `>`, `<`, `=`, `^`, `&`, `\|` | **1** each | Comparisons and logic (return `0` or `1`). Note: `&` and `\|` **short-circuit** (skip evaluating the right side if the left side decides the result). |
| `abs(a)` | **1** | Absolute value of `a`. |
| `min(a,b)`, `max(a,b)` | **2** each | Smaller or larger of two values. |
| `sin(a)`, `cos(a)`, `asin(a)`, `acos(a)` | **2** each | Trig functions in **radians** (`asin`/`acos` outside `[-1,1]` return `NaN` → 0 V). |
| `pow(a,b)` | **2** | `a` to the power `b`. Use `pow(A,0.5)` for the square root of `A`. |
| `if(c,t,e)` | **2** | Returns `t` if `c > 0.5`, else `e`. *(Only the chosen branch is evaluated!)* |
| `ramp(T)` | **3** | **[Stateful]** Sawtooth wave rising `0` → `1` over `T` seconds, then dropping to `0`. |
| `derivate(x)` | **3** | **[Stateful]** Rate of change `dx/dt` in **units per second**. Spikes by `20 × Δx` on sudden jumps; filter noisy inputs with `rc()` first! |
| `rc(tau,x)` | **3** | **[Stateful]** Low-pass smoothing filter. **Order: time constant `tau` (seconds) FIRST, signal `x` SECOND!** Reaches ~63% of a step change after `tau` seconds. |
| `rs(reset,set)` | **3** | **[Stateful]** SR Latch. **TRAP: `reset` is the FIRST argument, `set` is SECOND!** Triggers when inputs exceed **`0.6`** (30 V). If both `> 0.6`, **reset wins** (outputs `0`). *Warning:* Must be evaluated every tick to see its inputs—do not bury `rs()` inside an unchosen `if()` branch or the right side of a short-circuited `&`/`\|`! |
| `integrate(x,r)` | **4** | **[Stateful]** Running integral of `x dt` (adds `x × 0.05` each tick). Held at `0` while reset `r > 0.5`. Unbounded. |
| `integrate(x,min,max)` | **4** | **[Stateful]** Running integral of `x dt` clamped between `min` and `max` (prevents wind-up; no reset pin). |
| `scale(x,in0,in1,out0,out1)` | **5** | Linearly maps `x` from `[in0, in1]` onto `[out0, out1]`. Not clamped internally. |
| `batteryCharge(u)` | **8** | Converts normalized battery voltage ratio `u = U / U_nominal` into state-of-charge `0..1` (`u=0.90` → `0.10`; `u=1.00` → `0.31`; `u=1.05` → `1.00`). See Example 6 for probe scaling! |
| `pid(target,meas,P,I,D)` | **12** | **[Stateful]** PID controller on `error = target - meas`. Integral accumulator `Iacc` is **clamped to `0..1`** (can only push output positive). Updates every tick even inside an untaken `if()`. |
| `pid(target,meas,P,I,D,min,max)` | **14** | **[Stateful]** Same as above, with final output clamped to `[min, max]`. |

### The daytime clock

The built-in variable `daytime` equals `totalWorldTicks / 23999`. Because it never resets at midnight (`0.25` = Day 1 noon, `5.25` = Day 6 noon), **never write `daytime>0.5`**. Always wrap it in a periodic trig function: **`sin(2*pi*daytime)`** is `> 0` during the day and `< 0` at night on every day (drifting by ~1 game hour every 1,000 days because of the `23999` divisor).

{/* TODO: daytime fix ships -> remove this */}

## Cookbook formulas

Ten verified cookbook formulas. Every formula below has been tested against the processor's parser.

1. **Heater Temperature Hysteresis (Thermostat Latch)**
   * **Formula:** `rs(A>0.8,A<0.6)` *(Cost: **5 Redstone**)*
   * **How it works:** Wire a **Thermal Probe** into **A**, and Output to your heater/furnace control. Because `reset` comes first, when temperature **A** climbs above `0.8` (40 V), the latch **resets to 0 V** (heat OFF). It stays off until **A** cools below `0.6` (30 V), which **sets it to 50 V** (heat ON).
2. **Automatic Night-Time Yard Lamp Switch**
   * **Formula:** `sin(2*pi*daytime)<0` *(Cost: **5 Redstone**)*
   * **How it works:** Outputs **50 V** from sunset to sunrise every day, and **0 V** during daylight. (Use `sin(2*pi*daytime)<0.2` to switch lamps on slightly before dusk, or use an **Electrical Daylight Sensor** to track actual sky light.)

   {/* TODO: daytime fix ships -> remove this */}
3. **Proportional-Integral (PI) Temperature / Flow Controller**
   * **Formula:** `pid(0.7,A,4,0.5,0,0,1)` *(Cost: **14 Redstone**)*
   * **How it works:** Holds sensor **A** at `0.7` (35 V) using proportional gain `P=4`, integral gain `I=0.5/s`, `D=0`, clamped `0..1`. Ideal for driving a **Pipe Control Valve** or **Submersible Pump** speed input.
4. **Sensor Averaging and Noise Smoothing**
   * **Mean of 3 sensors:** `(A+B+C)/3` *(Cost: **3 Redstone**)*
   * **2-Second Low-Pass Filter:** `rc(2,A)` *(Cost: **3 Redstone** — note `2` seconds comes first, `A` second!)*
5. **2-out-of-3 Redundant Sensor Vote**
   * **Digital inputs:** `(A&B)|(A&C)|(B&C)` *(Cost: **5 Redstone** — brackets are mandatory because `&` and `|` have equal precedence!)*
   * **Analog inputs with custom threshold:** `(A>0.5)+(B>0.5)+(C>0.5)>1.5` *(Cost: **6 Redstone**)*
6. **Battery-Charge Load Shedding (Drop below 30%, Reconnect above 60%)**
   * **Formula:** `rs(batteryCharge(0.8+0.4*A)<0.3,batteryCharge(0.8+0.4*A)>0.6)` *(Cost: **25 Redstone**)*
   * **Wiring:** Place an **Electrical Probe** (voltage mode) on a **50 V battery bank** with its probe range set tightly to **40 V – 60 V** (or **160 V – 240 V** on a 200 V bank) and wire it to **A**. The expression `0.8+0.4*A` converts that 40–60 V probe signal into the `U / U_nom` ratio that `batteryCharge()` expects. Output drives the relay for non-essential workshop machines.
7. **Differential Gain Amplifier**
   * **Formula:** `(A-B)*3` *(Cost: **2 Redstone**; or `min(1,max(0,(A-B)*3))` at Cost **6** if feeding another calculation inside the same formula.)*
8. **Sensor Window Stretcher (Re-Ranging)**
   * **Formula:** `scale(A,0.2,0.8,0,1)` *(Cost: **5 Redstone**)*
   * **How it works:** Stretches the 10 V – 40 V (`0.2` – `0.8`) band of input **A** across the full 0 V – 50 V (`0` – `1`) output range.
9. **10-Second Off-Delay / Run-On Timer**
   * **Formula:** `rs(A>0.5,integrate(1,A>0.5)>10)` *(Cost: **10 Redstone**)*
   * **How it works:** While **A** is high (`> 25 V`), the latch is held reset (`0 V`) and the integrator is held at `0`. When **A** drops low, `integrate(1, ...)` counts up at 1.0 per second; after **10 seconds** of continuous low input, the latch sets and outputs **50 V**. (For a simple square-wave flasher with period `T` seconds, use `ramp(T)<0.5`, Cost **4**.)
10. **Sudden Rate-of-Change Alarm**
    * **Formula:** `abs(derivate(rc(1,A)))>0.05` *(Cost: **8 Redstone**)*
    * **How it works:** Smooths **A** with a 1-second filter (`rc(1,A)`) so step-ticks don't cause false spikes, then trips high (**50 V**) whenever **A** rises or falls faster than `0.05` (2.5 V) per second.

---

[Reference index](../reference.md) · [Back to the contents](../index.md)
