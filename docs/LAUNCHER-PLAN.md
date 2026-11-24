# Plan: from IntelliJ-on-Android to the Launcher

Status: proposal, 2026-11-24. Nothing below is built yet except the split keyboard (see
[Phase 0](#phase-0-done-in-this-change-split-see-through-keyboard)). Points that were unclear in the
request are listed under [Open questions](#open-questions) instead of being guessed.

## What we are building

This is **not an operating system**. It is one Android app, the **Launcher**, that hosts any number
of **co-launchers**:

* A **co-launcher** is a Lua program of the kind that would otherwise run on a Kindle (a
  KUAL-style launcher, or a Lua app such as KOReader). Here it runs inside the Launcher, interpreted
  by a Lua runtime that the co-launcher fully controls, including its debugger.
* Each co-launcher instance runs as a **separate user**: its own identity, its own files, its own
  settings, its own process.
* All users sit on a **two-layer** structure, managed by the Launcher as the **master**, with
  **redundant** storage.
* The Launcher ships an **embedded visual Lua debugger** and **settings activities** that expose
  every setting the Lua runtime and the Android SDK give us, with the most freedom a touch screen
  allows.

The current app (IntelliJ in proot Debian, shown through VNC) does not go away: it becomes one kind
of workload the Launcher can run (see [Layer 2](#layer-2-virtual-system-software)).

```
┌──────────────────────────── Launcher (Android app, the master) ───────────────────────────┐
│  Layer 2 · virtual system (software)                                                       │
│   ┌ user A ───────────────┐  ┌ user B ───────────────┐  ┌ user C ─────────────────────┐   │
│   │ co-launcher (Lua)      │  │ co-launcher (Lua)      │  │ Linux desktop (proot+VNC)   │   │
│   │ runtime + debugger     │  │ runtime + debugger     │  │ e.g. IntelliJ (today's app) │   │
│   │ files: overlay on base │  │ files: overlay on base │  │ files: rootfs + /root       │   │
│   └────────────────────────┘  └────────────────────────┘  └─────────────────────────────┘   │
│        ▲ virtual screen / input / storage, routed by the master                            │
│  Layer 1 · input and output devices                                                        │
│   screens (cover, inner, DeX monitor) · touch · keyboards (hardware, split, IME) · mouse   │
│   storage (internal, SD, USB) · audio · sensors · hinge/posture                            │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

## The two layers

### Layer 1: input and output devices

Everything physical, owned only by the Launcher (master). No co-launcher touches a device directly.

| Device | Android source | Already in the app |
|---|---|---|
| Screens / windows | `Activity` windows, `DisplayManager`, DeX, `WindowInfoTracker` (fold posture) | yes: resize on fold/unfold/DeX, flex mode |
| Touch, mouse, stylus | `MotionEvent` | yes, through the WebView viewer |
| Keyboards | hardware keys, system IME (`ImeView`), key bar (`KeyPanel`), split keyboard (`SplitKeyboard`) | yes |
| Storage | app storage, SAF documents, SD/USB, all-files access | yes: archive install |
| Audio, haptics, sensors, camera | `AudioManager`, `Vibrator`, `SensorManager`, CameraX | no |

Phase 1 turns these into a single **device registry**: each device gets an id, a type
(input / output / both / storage) and is *attached* to at most one user at a time, or shared
(read-only) by several. The Overview canvas described in earlier plans (all screens side by side,
one mouse and keyboard moving across them) is a view over this registry.

### Layer 2: virtual system (software)

Everything a user runs. A user only ever sees **virtual devices**: a virtual screen (a surface the
master composes into a real window), virtual input (events the master routes to the focused user),
and a virtual file system (below). This is what lets one physical keyboard move between users and
one user span several screens.

Workload types on layer 2:

1. **Lua co-launcher** (new): the main type.
2. **Linux desktop** (today's app): proot Debian + Xvnc + viewer, e.g. IntelliJ.
3. Further types are an open question (the request was cut off at "this virtual system is going to
   have the ability to run even …").

## Master and file system with redundancy

* **Master**: the Launcher process. It owns the user table, the device registry, the storage base,
  and starts/stops every user. Users never talk to each other directly; they go through the master.
* **Two-layer file system** per user:
  * a **base layer**, read-only and shared: co-launcher code, Lua libraries, the Linux image,
    assets. Stored once, content-addressed (file name = hash), so nothing is stored or unpacked twice;
  * an **upper layer**, writable and private per user, copy-on-write over the base. A user's view
    is base + upper.
* **Redundancy**: every upper-layer write goes to a journal first, and the content store keeps a
  checksum per block. Snapshots are cheap (a snapshot is a list of hashes). A second copy is kept on
  a chosen redundancy target (SD card, USB drive, or later another device, which fits the earlier
  "one shared storage with embedded backup" goal). A corrupted block is detected by checksum and
  restored from the copy.
* For the Linux desktop type, base = the unpacked Debian rootfs and IntelliJ (already installed once
  and shared), upper = `/root` and settings, which matches what the app already does.

Android implementation notes: Android gives apps no overlayfs or FUSE mount without root, so the
layering is done in our own code (a small VFS in the Lua host, path rewriting in proot via `-b`
binds for the Linux type).

## Users

Android lets only system apps create real Android users, so a **user** here is a Launcher-level
identity:

* Each user runs in **its own process** (`android:process=":user1"` … or an isolated service
  per user), so a crash, a runaway loop or a memory blow-up in one co-launcher does not take down
  the others or the master.
* Each has its own data directory (its upper layer), its own preferences, and its own set of
  attached devices.
* Users can be started, paused, snapshotted, cloned (new upper layer over the same base) and
  deleted from the Launcher's home screen.

## Lua runtime and co-launcher API

* **Runtime**: native Lua through JNI, one `lua_State` per user, in that user's process.
  Candidates: **LuaJIT** (what KOReader and most Kindle Lua software use, Lua 5.1 compatible) or
  **PUC Lua 5.4** (cleaner debug API, integers, no JIT). LuaJ (pure Java) is easier to build but
  slower and diverges from Kindle behaviour. The choice is an open question; LuaJIT is the default
  if Kindle compatibility matters most.
* **Full control by the co-launcher**: the co-launcher gets the whole standard library including
  `debug`, can set its own hooks, garbage-collector parameters, memory limit, instruction budget,
  `package.path`, and can load or replace modules at run time. The master enforces only the
  sandbox boundary (its own process and its own file layer), not what the Lua code does inside.
* **Kindle compatibility layer**: a Lua module set that provides what a Kindle co-launcher expects
  (framebuffer drawing, input events, power/battery, file paths), mapped to Layer 2 virtual
  devices. KOReader already has a device abstraction with an Android backend; reusing its
  `koreader-base` approach (draw into a buffer, the host blits it) is the fastest path if the target
  co-launchers are KOReader-like.
* **Launcher API** (`launcher.*` module): open a virtual screen, receive input, read/write the user's
  files, list devices, ask the master for a device, start another workload, talk to other users
  through the master.

## Embedded visual Lua debugger

Built into the Launcher, and the co-launcher can drive it itself.

* **Engine**: `lua_sethook` (line, call, return, count events) inside each user's runtime, with a
  small debug agent per `lua_State`: breakpoints (line, conditional, hit count, log points),
  step in/over/out, pause, stack frames, locals/upvalues/globals, watch expressions, evaluate in a
  frame, change a variable, hot-reload a module.
* **Protocol**: the agent speaks the **Debug Adapter Protocol** (DAP). The touch debugger in the app
  is one DAP client; IntelliJ running in the Linux desktop (with a Lua plugin such as EmmyLua) can
  attach to the same agent as a second client. So the phone's own IDE can debug its co-launchers.
* **Control from Lua**: the co-launcher can call `launcher.debug.*` to set breakpoints, pause
  itself, or replace the hook, which is what "complete control over the runtime and debugger" means
  here.
* **Visual aids** (Android activities):
  * code view with tap-to-set breakpoints in the gutter, long-press for conditions;
  * a floating step bar (pause/step/continue) that can hover over the co-launcher's own screen;
  * variables as an expandable tree, tables shown as editable grids;
  * a call-stack strip, a GC/memory graph, a per-function time profile from count hooks;
  * on the Fold: code on the top half, controls and variables on the bottom half in flex mode,
    same as the IDE layout today.

## Settings activities: every setting, maximum freedom

Settings are not hand-written screens. They are generated from a **settings schema** so that
everything the runtime and SDK expose reaches the UI:

* **Sources**:
  1. Lua runtime: GC mode/pause/step multiplier, memory limit, instruction budget, hook mask and
     count, stdlib modules on/off, `package.path`.
  2. Launcher: users, layers, redundancy target, device attachments, keyboard (key bar, split
     keyboard opacity / see-through width / key height), viewer, fullscreen, Fold layouts.
  3. Android SDK settings the app is allowed to change for itself: window brightness, refresh
     rate (`preferredDisplayModeId`), keep-screen-on, orientation, cutout mode, color mode,
     pointer capture, haptics, audio routing, rotation lock per user.
  4. System settings the app may only read (`Settings.System/Global/Secure`): shown read-only, with
     a button that opens the matching system settings screen (`Settings.ACTION_*` intents).
* **Controls with maximum touch freedom**: every number gets a slider *and* a typed field *and*
  fine steppers, with drag-to-scrub on the label; every color a picker; every setting gets reset,
  per-user override, search, and a live preview where possible. Changes apply immediately and are
  undoable.
* Each user can see and change its own settings; the master's settings are only on the Launcher
  side.

## Keyboard

The split keyboard asked for in this round is done (Phase 0). Next steps for it, in later phases:
make it a Layer 1 input device that can be attached to any user, allow dragging each half
anywhere, per-user layouts, and a Lua-definable layout so a co-launcher can supply its own keys.
Known limits from the keyboard design kit still apply: key-bar/split-keyboard modifiers do not
combine with hardware keys, and the guest window manager may grab Ctrl+Alt+Left/Right.

## Phases

Each phase ends with a working app.

### Phase 0 (done in this change): split, see-through keyboard

`SplitKeyboard.kt`. Toggle it with **⌨⇹** on the key bar. Two halves at the bottom corners like
Samsung Keyboard's split layout; the middle is empty, so the IDE's text stays visible and taps
there go to the IDE. It floats over the desktop instead of resizing it. **⚙** on the keyboard (or
**⋯ › Split keyboard settings**) sets key opacity (15–100 %), the see-through middle (0–60 % of
the width) and key height. Sticky Ctrl/Alt/Shift, Shift shows and sends shifted characters,
Backspace and arrows repeat when held.

### Phase 1: Launcher shell and device registry

* New home activity: list of users (cards with live thumbnails), "new user", start/stop.
* Today's IntelliJ flow becomes the first workload type, "Linux desktop", unchanged inside.
* Device registry for Layer 1; keyboards and screens become attachable devices.
* Rename the app/package (open question: final name).

### Phase 2: Lua runtime and first co-launcher

* Add the NDK build with the chosen Lua (LuaJIT or 5.4) and a JNI bridge.
* One user = one process with one `lua_State`; virtual screen as a `SurfaceView` the Lua side draws
  into; input routed from the master.
* `launcher.*` API v1 and a minimal Kindle compatibility layer; ship one sample co-launcher.

### Phase 3: Users and the two-layer file system

* Per-user processes, base/upper layers, content-addressed base store, journal and checksums.
* Snapshots, clone user, redundancy target (SD/USB), integrity check and repair.
* Move the Linux desktop's `/root` into its user's upper layer.

### Phase 4: Embedded debugger

* Debug agent on `lua_sethook`, DAP server per user.
* Touch debugger activities (code view, step bar, variables, stack, profiler).
* Attach from IntelliJ in the Linux desktop.

### Phase 5: Generated settings activities

* Settings schema, generator, the controls above, per-user overrides.
* Cover every Lua runtime and app-changeable SDK setting; read-only mirrors of system settings.

### Phase 6: Multi-device (from the earlier vision)

* Shared gallery and storage across devices, process and desktop pooling, "never compute twice"
  through the content-addressed store, Overview canvas of all devices' screens.

## Open questions

1. The request was cut off at "this virtual system is going to have the ability to run even …".
   Run even what (Android apps, other Linux distributions, Windows, Kindle firmware)?
2. Which Kindle software are the co-launchers: KUAL extensions, KOReader, or your own Lua code?
   This decides LuaJIT vs Lua 5.4 and how big the compatibility layer is.
3. "Separate users": is a Launcher-level user (separate process and files, as planned above)
   enough, or do you need real Android users (only possible as a system/device-owner app)?
4. "Redundancy": a second copy on SD/USB, on another device, or both? How many copies?
5. Should the Lua co-launcher be able to start and control the Linux desktop (e.g. script IntelliJ),
   or are the two kept apart?
6. App name and package once it is the Launcher rather than "APKintellij".
