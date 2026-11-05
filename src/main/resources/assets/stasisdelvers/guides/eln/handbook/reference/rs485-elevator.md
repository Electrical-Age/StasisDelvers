---
navigation:
  title: The RS485 Bus and the Elevator
  parent: reference.md
  position: 6
  icon: eln:linear_servo_drive
item_ids:
  - eln:field_bus_cable
  - eln:bus_io_module
  - eln:addressable_button
  - eln:addressable_light
  - eln:floor_sensor
  - eln:linear_servo_drive
---

# The RS485 Bus and the Elevator

One violet cable, any number of devices. It works in voxel blocks only.

## The bus

A run of **Field Bus Cable** and every device whose bus pin touches it form one bus. The bus carries two things:

* **16 colour channels.** Levels from 0 to 1, a share of 50 V, numbered like the dyes: 0 white to 15 black. A channel
  holds its level. When two writers disagree, the **last change wins**, and when a writer stops, the others' levels
  come back. A channel nobody writes falls to 0.
* **Packets** sent to an address: `[target][source][type][payload]`.

| target | meaning |
|---|---|
| 0 | everyone (broadcast) |
| 1 to 16 | colour channel 0 to 15 |
| 17 to 255 | one device, by the address set in its screen |

| type | name | payload | what it does |
|---|---|---|---|
| 1 | signal | a level 0 to 1 | to 1–16: sets that channel |
| 2 | press | button index | a button was pressed (sent to everyone) |
| 3 | power | a level 0 to 1 | a lamp's brightness, a button's light |
| 4 | write | register, value | sets a device register |
| 5 | read | register | asks for a register |
| 6 | reply | register, value | the answer, sent back to whoever asked |
| 7 | fault | code | a device reports a fault (sent to everyone) |
| 8 | arrive | sensor index | a Floor Sensor saw the car stop (sent to everyone) |

Packets sent in one tick arrive at the next, sorted by sender. A bus carries **64 packets a tick**; more are dropped,
and Jade on the cable counts them. Jade on the cable also lists the device addresses on the bus. Two devices with
one address are flagged as **DUPLICATE**.

## The devices

* **Bus I/O Module:** nine signal terminals onto colour channels, in or out. Its pins wear their channel's dye.
* **Addressable Button:** a click sends `press` from its address. Its lamp lights when it is sent `power`. Sneak and
  click it to set its address.
* **Addressable Light:** as bright as the last `power` sent to it.
* **Floor Sensor:** on a landing beside the shaft. Register 0 is 1 while the car stands level with its floor, and it
  sends `arrive` when the car stops there.
* **Linear Servo Drive:** an addressed device. Click it to set its address.

| register | drive |
|---|---|
| 0 | target height, blocks above its foot (write) |
| 1 | position (read) |
| 2 | speed limit, m/s, 0 for none (write) |
| 3 | state: 0 idle, 1 in position, 2 moving, 3 fault |
| 4 | load, kg |

Fault codes: 1 something in the way, 2 too hot, 3 rail missing or broken, 4 line voltage too high.

## Building the elevator

1. **The drive** goes at the foot of the shaft, with a **Servo Rail** mast of 2 to 32 blocks on top and **200 V on
   its back**. The cabin is **3 × 3 × 3**. It rides over the three rows of blocks in front of the drive, open at the
   front, so keep that 3 × 3 shaft clear up to 3 blocks above the top stop.
2. **The pins** are all on the floor edges of the drive's flanks, three per flank. Left and right are as you face
   that flank:
   * **Left flank** (seen from the car side): in-position, **setpoint** (middle), position.
   * **Right flank:** fault, **enable** (middle), and the violet **bus** pin.
   A plain signal cable reaches only the middle pins. Voxel cables can end at any of the three.
3. **Without a computer:** a signal on the setpoint is a share of the travel (0 V the bottom, 50 V the top), rounded
   to the nearest block. A connected setpoint overrides the bus.
4. **The car's panel** is the board inside the back wall: 12 slots, 3 across and 4 down. Hold an Addressable Button or
   Light and click a slot to put it in. Sneak and click with an empty hand to take it back. In the drive's screen,
   **"Car panel from"** sets the first slot's address. The others follow left to right, top to bottom. The panel
   rides on the drive's own bus (the travelling cable), so it needs no cable.
5. **On the landings:** an Addressable Button by each door, on the bus run up the shaft. A Floor Sensor at each
   landing, if you want the program to find the floors by itself.

A program on the bus does the rest. It listens for `press`, lights the buttons whose calls it has taken, and writes
the drive's target. It reads register 3 until the car is in position, then serves the next call.
