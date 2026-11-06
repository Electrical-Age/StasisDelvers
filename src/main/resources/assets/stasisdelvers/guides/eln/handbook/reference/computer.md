---
navigation:
  title: The Computer
  parent: reference.md
  position: 7
  icon: eln:computer
item_ids:
  - eln:computer
  - eln:ram_chip
  - eln:boot_rom_chip
  - eln:floppy_disk
  - eln:elevator_disk
  - eln:applications_disk
---

# The Computer

A RISC-V machine in a cabinet: eight chip slots, two floppy drives, a screen, and the violet bus pin. It runs off the
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

The screen is drawn by your own client, so typing never waits for the server. The computer sends what to show (see
[The Screen Protocol](screen-protocol.md)), and with the Janet ROM that is a set of tabs:

* **REPL:** type a Janet form and press Enter. Your line shows grey until the computer takes it. Up and down recall
  earlier lines, Tab completes a name (press it again to see the choices), and the mouse wheel scrolls back.
  Ctrl+A and Ctrl+E jump to the start and end of the line, and Ctrl+Shift+C copies it.
* **Console:** what the machine itself prints (start-up, crashes). It appears when there is something to show.
* **A tab per program** (a process): what it prints. Right-click a program's tab to end it.
* **Editors:** `(edit "a:/main.janet")` opens a file with Janet's colours. Ctrl+S saves it, and if the file changed
  on the disk since you opened it, the save is refused once: press Ctrl+S again to overwrite. **Run** runs the
  saved file as a process. `(files)` lists a floppy's files: click one to edit it.
* **Inspector:** `(inspect)` lists the processes and their tasks. Click down into a task's stack, a frame's local
  values and what is inside them. **Back** goes up, and **REPL here** opens a REPL inside that program, to look
  around and change it while it runs.

**Ctrl+C** on a tab stops what runs there: the REPL's current form, or every task of that tab's program.
**Reset** starts the computer again from slot 1, and the power button turns it off (its RAM is lost) or on.
Your half-typed lines and unsaved edits stay with you when you close the screen.

## At start-up

With the Janet ROM, the computer looks for `startup.janet` on drive A, then on drive B, and runs the first it finds
as a process with its own tab. Then the REPL opens. (A floppy with `main.janet` instead still works.) That is how a
program survives a restart: keep it on a floppy as `startup.janet`.

Saving the world keeps a running program where it was. A mod update that changes a ROM restarts every computer that
uses it, which runs `startup.janet` again.

## The Applications floppy

A menu of small programs, which opens when the floppy is in drive A at start-up (or type
`(proc/start "menu" run-file "a:/startup.janet")`). Each program is a file you can open in the Files tab, to read
how it is done, change it, or press **Run**.

* **Calculator:** click its keys, or type digits, `+ - * / %`, Enter and Backspace (C clears). The tape on the right
  keeps your sums.
* **BBS:** message boards shared by every computer on the bus that runs it. Pick a board on the left and type a post
  on the bottom line; `/board NAME` opens or starts a board, and `/name NAME` signs your posts. Each computer keeps
  every post it has seen on its floppy, so the boards live on while computers come and go. Posts can't be edited or
  deleted.
* **Snake:** steer with the arrow keys. The keys go over the network, so it plays best on a computer near you.
* **Gorillas:** for two players, each at a computer on the same bus with an address set. The two find each other,
  then take turns throwing a banana at the other's gorilla: type an angle (degrees up from the ground, towards the
  other gorilla) and a speed. Mind the wind.

Click a game's field before you play, so it gets the keys.

## The Elevator floppy

A ready-made controller for the elevator of the [RS485 page](rs485-elevator.md). Put it in drive A, then:

1. Type `(edit "a:/main.janet")` and change `settings` at the top:
   * the drive's address,
   * the floors' heights in blocks above the drive's foot, lowest first,
   * each floor's landing button address, and its car panel button (`nil` where there is none),
   * how long the car waits at a floor.
2. Press Ctrl+S, then Reset.

Each pressed button lights up until the car arrives. The car carries on in its direction while there are calls
ahead, and turns round when there are none. A fault or stall shows in its tab, and the car tries again.

[The Janet library](janet.md) · [The Screen Protocol](screen-protocol.md) · [Back to the reference](../reference.md)
