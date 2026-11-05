---
navigation:
  title: The Computer
  parent: reference.md
  position: 7
  icon: eln:computer
item_ids:
  - eln:computer
  - eln:ram_chip
  - eln:janet_rom_chip
  - eln:boot_rom_chip
  - eln:floppy_disk
  - eln:elevator_disk
---

# The Computer

A RISC-V machine in a cabinet: eight chip slots, two floppy drives, a terminal, and the violet bus pin. It runs off the
server's main thread, and costs almost nothing while it waits.

## Setting it up

* **Power:** 50 V on the back. It draws 2 W while it waits and up to 60 W flat out. On a brownout it **pauses**,
  keeping everything in its RAM, and carries on when the line comes back.
* **The bus pin** is on the right flank's floor edge, at its right end (as you face that flank), as on the Linear
  Servo Drive. Give it an address in its screen (17 to 255): that is the source of the packets it sends.
* **Chips:** slot 1 holds the ROM it starts from. RAM goes in the slots after it.
  * **ROM Chip: Janet** needs RAM chips in slots 2 and 3 at least (2 MB). More slots, more room.
  * **ROM Chip: Floppy Boot** loads drive A's first sector into slot 2's RAM and runs it (see the end of the
    [Janet page](janet.md)).
  * Changing a chip restarts the computer. RAM is volatile: a chip taken out comes back empty.
* **Floppy disks** go in drives A and B. A blank disk must be formatted before use: `(disk/format :a "LABEL")`.
  Right-click a floppy in your hand to flip its write-protect tab. Rename it on an anvil to label it.

The run lamp on the front: bright green while it works, dim green while it waits, red when halted, dark when off.

## The screen

* **Terminal tab:** what the computer prints, and an input line. Enter sends the line. Up and down arrows recall
  earlier lines, and the mouse wheel scrolls back.
* **Files tab:** a path (such as `a:/main.janet`) and an editor with Janet's colours and bracket checks.
  * **Open** loads the file, **Save** writes it, **Run** starts it as a task (its output shows on the Terminal tab),
    and **List** shows the files beside it.
  * If someone else saved the file since you opened it, your Save is refused once. Save again to overwrite.
  * The Files tab works through the running Janet, so the computer must be on and running the Janet ROM.
* **Reset** starts it again from slot 1.
* **Break** stops what the terminal is running: a loop that never ends, a long sleep.

## At start-up

With the Janet ROM, the computer runs `a:/main.janet` if drive A has one (else `b:/main.janet`) as a task of its
own, then opens the terminal. That is how a program survives a restart: keep it on a floppy as `main.janet`.

Saving the world keeps a running program where it was. A mod update that changes a ROM restarts every computer that
uses it, which runs `main.janet` again.

## The Elevator floppy

A ready-made controller for the elevator of the [RS485 page](rs485-elevator.md). Put it in drive A, then:

1. Open `a:/main.janet` in the Files tab and edit `settings` at the top:
   * the drive's address,
   * the floors' heights in blocks above the drive's foot, lowest first,
   * each floor's landing button address, and its car panel button (`nil` where there is none),
   * how long the car waits at a floor.
2. Save, and press Reset.

Each pressed button lights up until the car arrives. The car carries on in its direction while there are calls
ahead, and turns round when there are none. A fault or stall shows on the terminal, and the car tries again.

[The Janet library](janet.md) · [Back to the reference](../reference.md)
