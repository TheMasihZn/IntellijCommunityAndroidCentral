# DotPath: the DevOs command language, and Intentions

Status: draft 3 (2026-11-25). Reference interpreter: [`lua/dotpath/dotpath.lua`](../lua/dotpath/dotpath.lua),
tests: `lua5.4 lua/dotpath/dotpath_test.lua`. Part of the Launcher plan (`docs/LAUNCHER-PLAN.md`, PR #2).

## خلاصه به فارسی

**ایده‌ی اصلی.** کیبورد و موس فقط یک راه دادن دستور به کامپیوتر هستند. تا قبل از LLM ها هیچ
سیستم‌عاملی نمی‌توانست «نیت» آدم را بفهمد. DevOs اولین سیستم‌عاملی است که **روش اصلی ورودی‌اش حرف
زدن یا نوشتن آزاد است** و رابط انسان و ماشین در آن یک LLM است. یک جمله می‌گویی و می‌روی؛ وقتی کار تمام
شد خبرت می‌کند.

**نیت (Intention) = زمینه + عمل.** هر چیزی که می‌گویی یا می‌نویسی (به ترتیب زمانی، با همه‌ی
اصلاح‌هایی که بعداً می‌کنی) یک جریان است. هوش مصنوعی آن‌قدر جای خالی را پر می‌کند (یا از تو
می‌پرسد) تا یک **زمینه** (چه کسی، کدام عکس، کجا) و یک **عمل** (برنامه‌ی DotPath) کامل داشته باشد. هر
کدام می‌تواند اول خالی باشد. خروجی هر عمل می‌تواند ورودی عمل بعدی باشد. فهمیدن کار هوش مصنوعی است، ولی
اجرا قطعی و بدون هوش مصنوعی است، پس اگر نخواهی توکن خرج کنی کار عقب نمی‌افتد.

**زبان DotPath.** فولدرهایی که اسمشان با نقطه شروع می‌شود (مثل `.vpn`) «گروه جایگزین» هستند. هر خط
یک تابع است:

```
ask   .photos.find("Luna", last-night) -> photo;
ask   .telegram.draft(Parsa, $photo, "فکر کنم اینو دوست داری") -> draft;
later .vpn when .net.filtered;
later .telegram.send($draft).
```

- `;` آخر خط یعنی «این مرحله تمام شد ولی کار من هنوز تمام نشده». `.` آخر یعنی «کار تمام شد».
- `.vpn` یعنی برو داخل فولدر، فیلترشکن‌ها را به ترتیب امتحان کن، **اولین موفق** را نگه دار.
- `ask` یعنی همین الان انجامش بده و نشانم بده تا تأیید کنم (مثل نوشتن پیش‌نویس و نزدن ارسال).
- `later` یعنی کار خودکاری که بعداً، هر وقت وقتش شد، انجام می‌شود (وصل کردن VPN، ارسال).
- `when` شرط است: VPN فقط اگر اینترنت فیلتر است.
- `-> photo` نتیجه را اسم‌گذاری می‌کند و `$photo` بعداً از آن استفاده می‌کند.
- هیچ کاری دو بار انجام نمی‌شود.

متن طوری است که یک آدم غیربرنامه‌نویس هم بخواند و بفهمد قرار بوده چه اتفاقی بیفتد.

---

## 1. Thesis

Keyboards and mice are one way of giving a computer orders. Every OS so far is built around them because a
machine could not understand what a person *means*. LLMs change that. In DevOs the LLM **is** the
human-machine interface and free speech or writing is the primary input method:

1. The person says or writes what they want, once, and walks away.
2. The LLM turns it into an **Intention**: a *context* plus an *action* (a DotPath program).
3. The program runs **deterministically**, with no LLM in the loop, and the person is notified when it is done.

Understanding needs AI; execution does not. This keeps the system fast, predictable, readable, and in line
with the project rule *no computation is ever done twice*: an Intention is understood once, stored, and can
run again with zero tokens.

## 2. The vocabulary is the file system

The person already organises their phone (One UI) into folders. DotPath reads a folder tree directly; the
tree *is* the program library and the LLM's dictionary. Each co-launcher has its own root (e.g. `/sdcard/DevOs/`).

| Entry on disk | Node kind | Meaning |
|---|---|---|
| `.name/` (folder, leading dot) | **group** | ordered alternatives; the first that succeeds wins |
| `name/` (plain folder) | **namespace** | a set of named commands; one must be named (`.telegram.send`) |
| `name.lua` | **action** | returns `function(ctx, args)` or a table `{run=, check=, timeout=}` |
| `name.sh` | **action** | shell script (inside the Linux image); success = exit status 0, value = stdout |
| `name.path` | **alias** | file holds a DotPath, e.g. `.vpn.counterscreen.virtual.screen.` |

Order inside a group is file name order, so number them: `10-wireguard.sh`, `20-openvpn.sh`, `30-tor.lua`.
A leading `NN-` / `NN_` is not part of the name. What the person sees in the file manager is the order that runs.

## 3. Syntax

```
program   = statement { (";" | newline) statement } ["."]
statement = [verb] path ["->" var] ["until" path] ["when" cond]
cond      = path | "$" var ("=" | "!=" | "means") (bareword | '"' string '"')
verb      = "start" | "ask" | "later" | "explain"      ; start is the default
path      = "." segment { "." segment | ".." segment } ["."]
segment   = name [ "(" [arg { "," arg }] ")" ] { "?" | "@" number ["s"|"m"] }
name      = 1*( letter | digit | "_" | "-" )
arg       = '"' string '"' | number | "$" var | bareword    ; \" and \\ escape inside strings
comment   = "#" to end of line
```

Reading rule for people: **one line is one function**. `;` ends a line whose task is not finished yet
("VPN found, but I still have to send the message"); the final `.` ends the whole task.

## 4. Meaning

### 4.1 Segments become steps
Segments are grouped into **steps** by walking the tree greedily:

1. A step starts by looking the segment up at the **root**.
2. While the next segment exists **inside** the node just reached, it joins the same step
   (`.telegram.send` is one step; `.vpn.wireguard` is one step that picks the alternative explicitly).
3. Otherwise the next segment starts a new step from the root (`.vpn.telegram.send` is two steps).
4. Arguments end a step. `..` always ends a step, for when a child and a root entry share a name and
   you want the root one.

### 4.2 Running a step
* **action**: if it has `check(ctx, args)` and that returns `true[, value]`, the work is already done
  (VPN already connected) and it is not run. Otherwise `run(ctx, args)` is called. It **succeeds** when it
  returns `true` or any value other than `false`/`nil`, within its timeout. A thrown error is a failure.
* **group**: children are tried in order with the same arguments; the first success is the step's result and
  the rest are **not** run. If every child fails, the step fails and reports every child's reason. Groups nest.
* **alias**: its path runs as a sub-pipeline in the same context.
* **namespace** named alone: error, listing its members.

Each step's value is passed on as `$it`. A failing step stops the program and reports `step: reason`, unless
the segment is marked `?` (optional).

### 4.3 Statements and verbs
| Verb | When it runs | Person involved |
|---|---|---|
| `start` | now, in order | no |
| `ask` | now, in order; its result is shown and the person approves before anything continues | **yes** |
| `later` | after every `start`/`ask`; the runtime picks the moment (network, battery, idle, or when its output is needed) | no |
| `explain` | prints the steps; runs nothing | reads it |

`when <path>` runs the path as a silent test first; if it fails, the statement is skipped (not an error).
`when $name = value` compares a stored result instead (case-insensitive, `!=` negates).
`when $name means yes` asks whether a free-form answer *means* yes ("Yes, I'm in!", «آره پایه‌ام»). This is
the only place the LLM is used at run time, and only to classify one answer; without tokens or network a
word-list fallback (`dp.means`) decides, and an unclear answer counts as not meeting the condition.
`until <path>` changes what "success" means for a group: after each alternative runs, the path is tested,
and only if it passes does the group stop (`start .vpn until .telegram.connected` keeps starting VPNs, in
order, until Telegram reports connected).
`-> name` stores the statement's result; `$name` uses it later. At the end the runtime notifies the person.

### 4.4 The procedure: attention first, automation later
This is the procedure masih uses by hand and the one the LLM must produce:

1. Everything that needs the person's **attention** (choosing the photo, writing and editing the message)
   is done **now** with `ask`, while they are there, and stops at a saved draft. Nothing irreversible is
   done in this phase.
2. Everything **automatic and default** (in Iran: start a VPN before Telegram, trying free ones then paid
   ones until one connects; then press Send) is `later`, with its own `when` condition.

So the person spends attention once, leaves, and the rest happens without them.

### 4.5 Never twice, and only when needed
* Within a run, an action reached again (for example through an alias) returns its first value.
* `check` says "already done" across runs.
* `later` work is computed only when its time comes or its output is needed: no computation for things that
  do not matter right now.
* A compiled Intention needs no tokens to run again.

### 4.6 Timeouts and errors
Default 30 s per action, overridable by the action (`timeout`) or the segment (`@20s`, `@2m`, applied to each
alternative). Reported errors: unknown name, syntax error with column, namespace named alone, every
alternative failed (with reasons), timeout, unknown `$name`, alias loop, not approved.

## 5. Intentions (the LLM layer)

### 5.1 Context + action
An Intention is a pair; either half may start empty:

| | Example |
|---|---|
| **context** | who: Parsa · what: the photo of Luna from last night · why: he'd like it |
| **action** | the DotPath program below |

The LLM keeps completing the missing half (from memory, files, earlier Intentions, or by asking one short
question) until both are valid: the program parses, every name exists in the tree, and every `$name` has a
value. Only then is it shown. The output of one Intention's action can be the input of another's.

### 5.2 Input is a chronological stream
Everything the person says or writes is kept as one time-ordered stream: spoken first and typed later,
typed then partly corrected, a thought added an hour later. The person's information leaves their head
once. The stream is shown live as a **tree** (stream → Intentions → statements → steps); a tree is not the
prettiest view, but it is the one that lets the person follow without breaking focus. Editing any node
re-compiles only what depends on it.

### 5.3 Worked example
Said: *"send Parsa the photo I took of Luna last night, he'd like it"*

```
# send Parsa the photo of Luna from last night
ask   .photos.find("Luna", last-night) -> photo;
ask   .telegram.draft(Parsa, $photo, "فکر کنم اینو دوست داری") -> draft;
later .vpn when .net.filtered;
later .telegram.send($draft).
```

Run: the photo is found and shown → approved; the draft is written in Telegram and shown → approved; the person
leaves; when the phone is online the VPNs in `.vpn/` are tried in order until one connects (skipped if the
network is not filtered); the draft is sent; the person gets "Sent to Parsa". This exact program is a test in
`dotpath_test.lua`.

### 5.4 Stored form

```
intentions/2026-11-25-parsa-luna/
  said.txt         # the stream excerpt, in the person's words
  context.json     # {"to":"Parsa","photo":"…/IMG_2231.jpg"}
  action.dp        # the program above
```

Next time the same Intention runs without an LLM call. When no folder fits, the LLM proposes a new action
(`.lua` / `.sh`) or group as a file to approve, so the vocabulary grows from the person's own work.

## 6. Disk layout for the examples

```
DevOs/
  .vpn/        10-free-a.sh   20-free-b.sh   30-paid.lua
  net/         filtered.lua
  photos/      find.lua
  telegram/    draft.lua   send.lua
  stream.path              -> .vpn.counterscreen.virtual.screen.
```

## 7. Seed scripts: what the person writes

There are two layers. The person writes (or says) a **seed script**; the AI decodes it for the machine it
runs on, using the context file (§8), into a **DotPath program**, which runs deterministically. The seed is
the source of truth; the compiled program is a per-machine cache that is rebuilt only when the seed or the
context changes (never compute twice).

The seed is written the way masih thinks, with programming habits where they help:

| In a seed | Means | Read aloud |
|---|---|---|
| `vpn()` | start it | "start VPN" |
| `.vpn` after a place (`HomeScreen.vpn`) | look it up there | "in the home screen, the VPN folder" |
| `.forEach` | each alternative, in order | "for each one" |
| `.while(X != Y)` | keep going **until** the goal holds (masih writes *while* and means *until*) | "until Telegram is connected" |
| `=>` | and then; the result feeds into the next action | "and then" |
| `if(x=yes) { … }` | condition on an earlier result, judged by meaning; if it fails the whole `{ }` block is skipped | "if the answer means yes" |
| `;` | this step is done, the task continues | (pause) |
| `.` at the end, nothing after | the script ends ("bye-bye") | "done" |

The seed does not have to be exact. Where the person was brief, lazy, or wrote a typo (`OBS.stream():`), the
AI infers the intention from the context file and earlier scripts, and asks one short question only when two
readings would do different things. A seed can also include an image or a voice note: a picture is worth a
thousand words, so the AI reads context from whatever input method was used.

### 7.1 Example: the first script file

`first.script`, as masih wrote it:

```
HomeScreen
.vpn
.forEach
.while(Telegram.connection != Connected)() =>Telegram.Pepe.write
("wanna play CS:GO").
if(respond=yes) {
Steam.CounterStrike();
OBS.stream():
}.
```

Compiled for this phone (`first.dp`; this exact program is a test in `dotpath_test.lua`):

```
start .vpn until .telegram.connected;
start .telegram.write(Pepe, "wanna play CS:GO");
start .telegram.wait-reply(Pepe)@30m -> respond;
start .steam.counter-strike when $respond means yes;
start .obs.stream when $respond means yes.
```

How the AI reads it back, in English:

> **The first script file** says: on the home screen, find `.vpn`. The context file tells me that is a folder
> of VPN apps you put there on purpose and have used this way before. For each app in it, in order, start it,
> until Telegram is connected; the moment Telegram connects, stop trying. And then, feeding into Telegram,
> find Pepe and write: "wanna play CS:GO". That step is done, but the task is not. Wait for Pepe's reply. If
> it means yes, in any words ("yes, I'm in"), start Counter-Strike in Steam, then start streaming in OBS. If
> it means no (he is busy), everything inside the curly braces is skipped. The script ends with a dot and
> nothing after it, so we are done. Bye-bye.

و به فارسی:

> **اسکریپت اول** می‌گوید: در صفحه‌ی اصلی، `.vpn` را پیدا کن. فایل زمینه می‌گوید این پوشه‌ای از
> فیلترشکن‌هاست که خودت عمداً آنجا گذاشته‌ای. فیلترشکن‌ها را یکی‌یکی و به ترتیب روشن کن تا وقتی که تلگرام
> وصل شود؛ همین که تلگرام وصل شد، بس است. و بعد، در تلگرام Pepe را پیدا کن و بنویس: «wanna play CS:GO».
> این مرحله تمام شد ولی کار نه. منتظر جواب Pepe بمان. اگر معنی جوابش «بله» بود، با هر عبارتی
> («yes, I'm in»)، Counter-Strike را در Steam باز کن و بعد پخش زنده را در OBS شروع کن. اگر معنی‌اش «نه»
> بود، همه‌ی داخل آکولاد نادیده گرفته می‌شود. اسکریپت با یک نقطه و بدون ادامه تمام می‌شود. خداحافظ.

**Naming rule for reading:** a file is read by what its name means, not spelled out. `first.script` is
"the first script file" in English and «اسکریپت اول» in Persian; `vpn()` is "start VPN". Every character
tells part of the story, so the AI narrates it rather than reading symbols.

## 8. The context file

Before any script, the AI is given the **context file** of the OS: what exists and how it was used. For
example: the home screen has a folder `.vpn` holding these apps, in this order; Telegram has a contact
Pepe; `.telegram.connected` is how to test the connection. It is updated by the result of every action,
and apps can register **triggers** (like Android intents: an app declares what it reacts to, and is woken
when that happens). The context file is what lets a seed say `.vpn` without saying what a VPN is.

## 9. Folders as lazy filters

A folder like `.vpn` on the home screen is a hand-made filter over a potentially infinite amount of
information: instead of scanning and indexing every app on the device, the AI only looks at what the person
put in the folder. The folder is re-read **only when a script runs it**, and the result is cached until the
next run. Nothing is indexed in the background.

## 10. A script is one file that expands into a tree

On disk, a script is a single file of bytes. When it is given to the launcher, it is mounted as a virtual
file tree: each statement is a node, each block (`{ … }`) a folder, and the context it carries is loaded
with it. A memory file, a project, or a whole workflow can be one such file, and a file can live inside
another. So information is kept together by meaning ("defragmented") without touching how the disk stores
it. (Flash storage on phones does not need classic defragmentation anyway; the gain here is locality of
*meaning*: everything a task needs is in one file, and only what the person intended is written.)

## 11. Learning apps by demonstration

To teach the system an app, the person does the task once while the screen is recorded. The AI turns the
recording into an action (`telegram/write.lua`, or steps for the Android accessibility layer) and adds it to
the tree. After that, seeds can name it.

## 12. Naming the idea

Suggested titles for a paper:

1. *Intention-Compiled Computing: Natural Language as the Primary Input Method of an Operating System*
2. *Seed Scripts: Human-Level Automation by Compiling Spoken Intentions into Deterministic Programs*
3. *Understand Once, Run Forever: An LLM-Compiled Command Language over the User's Own File System*

Possible name for the field: **Intention-oriented computing**. Related work to cite and compare against:
programming by demonstration, Android intents, Tasker and Apple Shortcuts, LLM agents that operate apps
(computer use). What is new here is the combination: the person's own folders as the vocabulary, the AI
compiling once and then stepping out of execution, attention-first scheduling, and the readable seed.

## 13. Open questions
* Shadowing: when a child and a root entry share a name, the child wins and `..` forces the root. (Default; not confirmed.)
* Parallel alternatives ("start all, keep the fastest"), e.g. a `.race` marker file in a group.
* Undo for what a program started (`stop .vpn.`) via an optional `stop(ctx)` on actions.
* Where Intentions and the stream live across devices (the shared storage of the multi-device plan).
