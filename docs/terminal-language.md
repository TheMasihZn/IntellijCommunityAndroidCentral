# DotPath: the DevOs terminal language, and Intentions

Status: draft 1 (2026-11-25). Reference interpreter: [`lua/dotpath/dotpath.lua`](../lua/dotpath/dotpath.lua),
tests: `lua5.4 lua/dotpath/dotpath_test.lua`. Part of the Launcher plan ([LAUNCHER-PLAN.md](LAUNCHER-PLAN.md)).

## خلاصه به فارسی

**ایده‌ی اصلی.** تا قبل از LLM ها هیچ سیستم‌عاملی نمی‌توانست «نیت» آدم را بفهمد؛ برای همین همه‌چیز
منو و دکمه و دستور بود. DevOs اولین سیستم‌عاملی است که رابط انسان و ماشین در آن یک LLM است. کاربر
**نیت (Intention)** خودش را به زبان خودش می‌گوید، هوش مصنوعی آن را می‌فهمد و به یک خط از زبان
DotPath ترجمه می‌کند، و از آن به بعد اجرا کاملاً خودکار و قطعی است (بدون هوش مصنوعی). شبیه Intent در
اندروید، ولی با زبان آدم.

**زبان DotPath.** فولدرهایی که اسمشان با نقطه شروع می‌شود (مثل `.vpn`) «گروه جایگزین» هستند: داخلشان
چند راه برای یک کار به ترتیب چیده شده. دستور

```
start .vpn.telegram.write("salam").
```

یعنی: برو داخل `.vpn`، گزینه‌ها را به ترتیب امتحان کن، **اولین گزینه‌ای که موفق شد** را نگه دار
(بقیه اجرا نمی‌شوند)، این مرحله تمام است؛ بعد برو سراغ مرحله‌ی بعد (`telegram.write` با متن «salam»).
اگر یک مرحله هیچ گزینه‌ی موفقی نداشت، کل دستور همان‌جا متوقف می‌شود و می‌گوید چرا.

- `.vpn.wireguard` یعنی خودت مشخص کرده‌ای کدام VPN؛ دیگر سراغ بقیه نمی‌رود.
- `.stream` می‌تواند خودش یک مسیر کوتاه‌شده باشد (alias)، مثلاً `.vpn.counterscreen.virtual.screen.`
- `?` بعد از یک مرحله یعنی «اختیاری»: اگر شکست خورد، ادامه بده.
- `@20s` یعنی سقف زمان این مرحله ۲۰ ثانیه.
- کاری که یک بار انجام شده دوباره انجام نمی‌شود (VPN اگر وصل است، دوباره وصل نمی‌شود).

---

## 1. Thesis

Every OS so far is built around menus, buttons and commands because a machine could not understand what a
person *means*. LLMs change that. In DevOs the LLM **is** the human-machine interface:

1. The person states an **Intention** in their own words ("connect a VPN and send Ali my screen stream").
2. The LLM **compiles** it once into a DotPath line, using the person's own folders as its vocabulary.
3. The line runs **deterministically**, with no LLM in the loop. Understanding needs AI; execution does not.

This keeps the system fast, predictable, debuggable, and in line with the project rule *no computation is
ever done twice*: an Intention is understood once, stored, and re-run for free.

## 2. The vocabulary is the file system

The person already organises their phone (One UI) into folders. DotPath reads a folder tree directly; the
tree *is* the program library and the LLM's dictionary. Inside a co-launcher this root is its own
workspace (e.g. `/sdcard/DevOs/`).

| Entry on disk | Node kind | Meaning |
|---|---|---|
| `.name/` (folder, leading dot) | **group** | ordered alternatives; the first that succeeds wins |
| `name/` (plain folder) | **namespace** | a set of named commands; one must be named (`.telegram.write`) |
| `name.lua` | **action** | returns `function(ctx, args)` or a table `{run=, check=, timeout=}` |
| `name.sh` | **action** | shell script (inside the Linux image); success = exit status 0, value = stdout |
| `name.path` | **alias** | file holds a DotPath, e.g. `.vpn.counterscreen.virtual.screen.` |

Order inside a group is the file name order, so number them: `10-wireguard.sh`, `20-openvpn.sh`, `30-tor.lua`.
A leading `NN-` or `NN_` is not part of the name. (Android's file pickers sort the same way, so what the
person sees is the order that runs.)

## 3. Syntax

```
line      = [verb " "] path
verb      = "start" | "explain"                     ; start is the default
path      = "." segment { "." segment | ".." segment } ["."]
segment   = name [ "(" [arg { "," arg }] ")" ] { "?" | "@" number ["s"|"m"] }
name      = 1*( letter | digit | "_" | "-" )
arg       = '"' string '"' | number | bareword       ; \" and \\ escape inside strings
```

The trailing `.` is optional and means "end". Spaces are allowed only between the verb and the path and
inside `( )`.

## 4. Meaning

### 4.1 Segments become steps
Segments are grouped into **steps** by walking the tree greedily:

1. A step starts by looking the segment up at the **root**.
2. While the next segment exists **inside** the node just reached, it is taken into the same step
   (`.telegram.write` is one step; `.vpn.wireguard` is one step that names the alternative explicitly).
3. Otherwise the next segment starts a new step from the root (`.vpn.telegram` is two steps).
4. Arguments end a step. `..` always ends a step, for the rare case where a child and a root entry share
   a name and you want the root one.

`explain <path>` prints this split without running anything; it is how the person (and the LLM) check
what a line will do.

### 4.2 Running a step
* **action**: if it has `check(ctx, args)` and that returns `true[, value]`, the work is already done
  (VPN already connected), so it is not run. Otherwise `run(ctx, args)` is called. It **succeeds** when it
  returns `true` or any value other than `false`/`nil`, and finishes within its timeout. An error thrown is
  a failure.
* **group**: children are tried in order with the same arguments; the first success is the step's result
  and the rest are **not** run. If every child fails, the step fails with every child's reason.
  Children may themselves be groups, aliases or namespaces' members, so fallbacks nest.
* **alias**: its path runs as a sub-pipeline in the same context. It succeeds when that whole
  pipeline succeeds.
* **namespace** named alone: error, listing its members.

### 4.3 Running a line
Steps run left to right. Each step's value is available to the next as `ctx.it` (the VPN that connected,
the stream URL, …). A failing step stops the line and reports `step: reason`, unless it is marked `?`
(optional), in which case it is shown as skipped and the line continues.

### 4.4 Never twice
Within one run, an action reached a second time (for example through an alias) returns its first value
without running. Across runs, `check` is the place to say "already done". A runtime may add a cache with a
time to live per action; the interpreter keeps that hook in `ctx.done`.

### 4.5 Timeouts
Default 30 s per action. An action may set `timeout`; a step may override with `@20s` / `@2m`, which
applies to each alternative it tries. The reference interpreter measures after the call returns; the
Android runtime runs each action in its own coroutine/process and cancels it at the deadline.

### 4.6 Errors (all reported, none silent)
Unknown name · syntax error with column · namespace named alone · every alternative failed (with each
reason) · timeout · alias loop (depth > 32).

## 5. Examples

```
start .vpn.                                     # first VPN that connects
start .vpn.wireguard.                           # only WireGuard
start .stream.                                  # alias: .vpn.counterscreen.virtual.screen.
start .vpn.telegram.write("رسیدم").             # connect, then send
start .backup?.vpn@20s.telegram.write("done").  # backup may fail; VPN gets 20 s per try
explain .stream.telegram.write("hi").           # show the steps, run nothing
```

A disk layout for these:

```
DevOs/
  .vpn/        10-wireguard.sh   20-openvpn.sh   30-tor.lua
  .virtual/    screen.lua
  telegram/    write.lua   send-file.lua
  counterscreen.lua
  stream.path              -> .vpn.counterscreen.virtual.screen.
```

## 6. Intentions (the LLM layer)

An **Intention** is a stored record:

```
intention/
  send-ali-my-screen.intent      # what the person said, in their words
  send-ali-my-screen.path        # what it compiled to: .stream.telegram.send-file(ali, it).
```

1. **Understand**: the LLM receives the person's words, the folder tree (names, kinds, order, and each
   action's one-line description), and this document as the grammar. It answers with one DotPath line,
   or with a question if the request is ambiguous.
2. **Show**: the launcher shows the line and `explain` output; the person approves once.
3. **Store**: the pair is saved; next time the same Intention runs with no LLM call (never twice).
4. **Grow the vocabulary**: when no folder fits, the LLM proposes a new action (`.lua` / `.sh`) or a new
   group, shown as a file to approve, so the language grows with the person's own work.
5. **Run**: deterministic, as above. Failures come back to the LLM only if the person asks for help.

Because the grammar is tiny and the vocabulary is the person's own folders, an LLM can produce valid lines
reliably, and a person can read and edit every line it produced.

## 7. Open questions
* Should `.vpn.telegram` ever mean "telegram *inside* vpn" when both exist? Today: inside wins, `..` forces root.
* Parallel alternatives ("start all, keep the fastest") as a group option, e.g. a `.race` marker file.
* Stopping what a line started (`stop .vpn.`) via an optional `stop(ctx)` on actions.
* Where Intentions are shared across devices (the shared storage from the multi-device plan).
