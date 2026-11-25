-- DotPath: reference interpreter for the DevOs terminal language.
-- Spec: docs/terminal-language.md. Plain Lua 5.4, no dependencies.
--
--   local dp = require("dotpath")
--   local root = dp.load_dir("/sdcard/DevOs")     -- or dp.tree{...} in tests
--   local ok, report = dp.run(root, 'start .vpn.telegram.write("hi").')

local M = {}

---------------------------------------------------------------- parser

-- One statement:  [verb] path [-> name] [until path] [when cond]
--   cond = path | $name = value | $name != value | $name means value
-- Returns { verb=, segments={ {name=, args=, optional=, timeout=} | {brk=true} ... }, bind=, when= }
function M.parse(src)
  local s = src:match("^%s*(.-)%s*$")
  local verb, rest = s:match("^(%a+)%s+(.*)$")
  if verb then s = rest else verb = "start" end
  if s:sub(1, 1) ~= "." then error("a path starts with '.': " .. s, 0) end

  local segs, i = M._path(s)
  local cmd = { verb = verb, segments = segs }
  while true do
    i = s:match("^%s*()", i)
    if i > #s then break end
    local bind = s:match("^%->%s*([%a_][%w_]*)", i)
    if bind then
      cmd.bind = bind; i = s:match("^%->%s*[%a_][%w_]*()", i)
    elseif s:match("^until%s+%.", i) then
      local j = s:match("^until%s+()", i)
      cmd["until"], i = M._path(s, j)
    elseif s:match("^when%s+%.", i) then
      local j = s:match("^when%s+()", i)
      cmd.when, i = M._path(s, j)
    elseif s:match("^when%s+%$", i) then
      local var, op, j = s:match("^when%s+%$([%a_][%w_]*)%s*([!=]=?)%s*()", i)
      if not var then
        var, op, j = s:match("^when%s+%$([%a_][%w_]*)%s+(means)%s+()", i)
      end
      if not var or not (op == "=" or op == "!=" or op == "==" or op == "means") then
        error("bad condition at column " .. i, 0)
      end
      local val
      if s:sub(j, j) == '"' then
        local q = s:match('^"(.-)"()', j)
        if not q then error("unclosed string", 0) end
        val, i = s:match('^"(.-)"()', j)
      else
        val = s:match("^[^%s]+", j) or ""; i = j + #val
        val = val:gsub("%.$", "")                 -- the final '.' ends the program, not the value
        val = tonumber(val) or val
      end
      cmd.when = { var = var, neg = op == "!=", means = op == "means", value = val }
    else
      error(("unexpected %q at column %d"):format(s:sub(i), i), 0)
    end
  end
  return cmd
end

-- Parses a path starting at i; stops at whitespace. Returns segments, next index.
function M._path(s, i)
  local segs, n = {}, #s
  i = i or 1
  while i <= n do
    local c = s:sub(i, i)
    if c:match("%s") then break
    elseif c == "." then
      if s:sub(i + 1, i + 1) == "." and i + 1 < n then
        segs[#segs + 1] = { brk = true }   -- '..' : force a new step from the root
        i = i + 2
      else
        i = i + 1
      end
    else
      local name = s:match("^[%w_%-]+", i)
      if not name then error(("unexpected %q at column %d"):format(c, i), 0) end
      i = i + #name
      local seg = { name = name }
      if s:sub(i, i) == "(" then
        seg.args, i = M._args(s, i + 1)
      end
      while true do
        local m = s:sub(i, i)
        if m == "?" then
          seg.optional = true; i = i + 1
        elseif m == "@" then
          local num, unit = s:match("^(%d+)([ms]?)", i + 1)
          if not num then error("bad timeout at column " .. i, 0) end
          seg.timeout = tonumber(num) * (unit == "m" and 60 or 1)
          i = i + 1 + #num + #unit
        else
          break
        end
      end
      local nxt = s:sub(i, i)
      if nxt ~= "" and nxt ~= "." and not nxt:match("%s") then
        error(("expected '.' after %s at column %d"):format(name, i), 0)
      end
      segs[#segs + 1] = seg
    end
  end
  return segs, i
end

-- Split a program into statements (outside quotes and parentheses). Separators:
--   ;  or a new line   start this and go on to the next statement without waiting
--   =>                 wait for this; its result feeds into the next statement as $it
-- '#' starts a comment. Returns { {text=, sep=";"|"=>"} ... }.
-- The symbols are configurable per person/language (M.syntax, or opts.syntax in M.run): a Persian
-- (right-to-left) writer may prefer "<=" for natije-giri, and the Persian semicolon "؛" works as ';'.
M.syntax = { ["then"] = { "=>" }, go_on = { ";", "؛" } }

function M.split(text, syntax)
  syntax = syntax or M.syntax
  local function at(i, list)
    for _, op in ipairs(list) do if text:sub(i, i + #op - 1) == op then return op end end
  end
  local out, buf, depth, quoted, i = {}, {}, 0, false, 1
  local function flush(sep)
    local st = table.concat(buf):match("^%s*(.-)%s*$")
    if st ~= "" then out[#out + 1] = { text = st, sep = sep }
    elseif sep == "=>" and #out > 0 then out[#out].sep = "=>" end
    buf = {}
  end
  while i <= #text do
    local c = text:sub(i, i)
    if quoted then
      buf[#buf + 1] = c
      if c == "\\" then buf[#buf + 1] = text:sub(i + 1, i + 1); i = i + 1
      elseif c == '"' then quoted = false end
    elseif c == '"' then quoted = true; buf[#buf + 1] = c
    elseif c == "(" then depth = depth + 1; buf[#buf + 1] = c
    elseif c == ")" then depth = depth - 1; buf[#buf + 1] = c
    elseif c == "#" and depth == 0 then
      while i <= #text and text:sub(i, i) ~= "\n" do i = i + 1 end
      flush(";")
    elseif depth == 0 and at(i, syntax["then"]) then
      local op = at(i, syntax["then"]); flush("=>"); i = i + #op - 1
    elseif depth == 0 and at(i, syntax.go_on) then
      local op = at(i, syntax.go_on); flush(";"); i = i + #op - 1
    elseif c == "\n" and depth == 0 then flush(";")
    else buf[#buf + 1] = c end
    i = i + 1
  end
  flush(";")
  return out
end

-- Arguments: "quoted strings" (with \" and \\), numbers, or bare words; comma separated.
function M._args(s, i)
  local args = {}
  while true do
    i = s:match("^%s*()", i)
    local c = s:sub(i, i)
    if c == ")" then return args, i + 1 end
    if c == "" then error("unclosed '('", 0) end
    if c == '"' then
      local buf, j = {}, i + 1
      while true do
        local d = s:sub(j, j)
        if d == "" then error("unclosed string", 0) end
        if d == "\\" then buf[#buf + 1] = s:sub(j + 1, j + 1); j = j + 2
        elseif d == '"' then break
        else buf[#buf + 1] = d; j = j + 1 end
      end
      args[#args + 1] = table.concat(buf); i = j + 1
    else
      local word = s:match("^[^,%)%s]+", i)
      if not word then error("bad argument at column " .. i, 0) end
      args[#args + 1] = tonumber(word) or word; i = i + #word
    end
    i = s:match("^%s*()", i)
    if s:sub(i, i) == "," then i = i + 1 end
  end
end

---------------------------------------------------------------- tree

-- Node kinds:
--   namespace { kind="namespace", children={name=node}, order={names} }   plain folder
--   group     { kind="group",     children=..., order=... }               '.name' folder
--   action    { kind="action", run=function(ctx,args) -> ok, value, check=fn?, timeout=n? }
--   alias     { kind="alias", path=".a.b." }                               'name.path' file

-- Build a tree from Lua tables (tests, or code-defined launchers):
--   dp.tree{ [".vpn"] = { {"wireguard", action}, {"openvpn", action} }, telegram = {...}, stream = ".vpn.x." }
-- A key starting with '.' is a group; a plain table key is a namespace; a string is an alias;
-- a function or a table with run= is an action. Lists of {name, node} keep their order.
function M.tree(spec)
  local function build(v)
    if type(v) == "string" then return { kind = "alias", path = v } end
    if type(v) == "function" then return { kind = "action", run = v } end
    if v.run then v.kind = "action"; return v end
    local node = { kind = "namespace", children = {}, order = {} }
    local function add(k, x)
      local kind = "namespace"
      if k:sub(1, 1) == "." then k = k:sub(2); kind = "group" end
      local child = build(x)
      if child.kind == "namespace" then child.kind = kind end
      node.children[k] = child; node.order[#node.order + 1] = k
    end
    for _, pair in ipairs(v) do add(pair[1], pair[2]) end
    local keys = {}
    for k in pairs(v) do if type(k) == "string" then keys[#keys + 1] = k end end
    table.sort(keys)
    for _, k in ipairs(keys) do add(k, v[k]) end
    return node
  end
  return build(spec)
end

-- Load a tree from a folder. Entries are sorted by name, so order with prefixes: 10-wireguard.lua.
-- A leading "NN-" or "NN_" is stripped from the name.
--   .name/       group (try children in order, first success wins)
--   name/        namespace
--   name.lua     action: returns function(ctx,args) or {run=, check=, timeout=}
--   name.sh      action: success = exit status 0, value = stdout
--   name.path    alias: file holds a path like .vpn.screen.
function M.load_dir(dir)
  local function list(d)
    local p = io.popen('ls -1A "' .. d:gsub('"', '\\"') .. '" 2>/dev/null')
    local out = {}
    for line in p:lines() do out[#out + 1] = line end
    p:close(); table.sort(out); return out
  end
  local function isdir(path)
    local p = io.popen('[ -d "' .. path:gsub('"', '\\"') .. '" ] && echo y'); local r = p:read("l"); p:close()
    return r == "y"
  end
  local function clean(name) return (name:gsub("^%d+[-_]", "")) end
  local function load(d, kind)
    local node = { kind = kind, children = {}, order = {} }
    for _, entry in ipairs(list(d)) do
      local full, name, child = d .. "/" .. entry, nil, nil
      if isdir(full) then
        if entry:sub(1, 1) == "." then name = clean(entry:sub(2)); child = load(full, "group")
        else name = clean(entry); child = load(full, "namespace") end
      elseif entry:match("%.lua$") then
        name = clean(entry:sub(1, -5))
        local v = dofile(full)
        child = type(v) == "function" and { kind = "action", run = v } or v
        child.kind = "action"
      elseif entry:match("%.sh$") then
        name = clean(entry:sub(1, -4))
        child = { kind = "action", run = function(_, args)
          local q = {}
          for _, a in ipairs(args or {}) do q[#q + 1] = "'" .. tostring(a):gsub("'", "'\\''") .. "'" end
          local p = io.popen('sh "' .. full .. '" ' .. table.concat(q, " "))
          local out = p:read("a"); local ok = p:close()
          return ok == true, out
        end }
      elseif entry:match("%.path$") then
        name = clean(entry:sub(1, -6))
        local f = io.open(full); child = { kind = "alias", path = f:read("a"):match("^%s*(.-)%s*$") }; f:close()
      end
      if name then node.children[name] = child; node.order[#node.order + 1] = name end
    end
    return node
  end
  return load(dir, "namespace")
end

---------------------------------------------------------------- resolution

-- Split segments into steps. A step walks down the tree greedily: the next segment is
-- looked up inside the current node first; only when it is not there (or the current
-- segment had arguments, or a '..' break follows) does a new step start from the root.
function M.steps(root, segs)
  local steps, i = {}, 1
  while i <= #segs do
    if segs[i].brk then i = i + 1 else
      local seg = segs[i]
      local node = root.children[seg.name]
      if not node then error("unknown name ." .. seg.name, 0) end
      local step = { names = { seg.name }, node = node, seg = seg }
      i = i + 1
      while i <= #segs and not segs[i].brk and not step.seg.args
            and node.children and node.children[segs[i].name] do
        seg = segs[i]; node = node.children[seg.name]
        step.names[#step.names + 1] = seg.name; step.node = node; step.seg = seg
        i = i + 1
      end
      step.label = "." .. table.concat(step.names, ".")
      steps[#steps + 1] = step
    end
  end
  return steps
end

---------------------------------------------------------------- execution

local DEFAULT_TIMEOUT = 30

local function exec(root, node, args, ctx, label, timeout, depth)
  if depth > 32 then return false, "alias loop at " .. label end

  if node.kind == "action" then
    if ctx.done[node] then return true, ctx.done[node].value, "reused" end    -- never compute twice
    if node.check then
      local ok, already, value = pcall(node.check, ctx, args)
      if ok and already then
        ctx.done[node] = { value = value }
        return true, value, "already"
      end
    end
    local limit = node.timeout or timeout or DEFAULT_TIMEOUT
    local started = ctx.clock()
    ctx.deadline = started + limit
    local ok, res, value = pcall(node.run, ctx, args)
    if not ok then return false, tostring(res) end
    if ctx.clock() - started > limit then return false, ("timed out after %ss"):format(limit) end
    if res == false or res == nil then return false, value or "failed" end
    if res ~= true and value == nil then value = res end
    ctx.done[node] = { value = value }
    return true, value

  elseif node.kind == "group" then
    local why = {}
    for _, name in ipairs(node.order) do
      local child = node.children[name]
      local ok, value, note = exec(root, child, args, ctx, label .. "." .. name, timeout, depth + 1)
      ctx.log(("  %s %s.%s%s"):format(ok and "✓" or "✗", label, name,
        ok and (note and " (" .. note .. ")" or "") or ": " .. tostring(value)))
      if ok and ctx["until"] then
        local cond = ctx["until"]; ctx["until"] = nil
        local quiet = ctx.log; ctx.log = function() end
        local done = M._pipeline(root, cond, ctx, depth + 1)
        ctx.log = quiet; ctx["until"] = cond
        if not done then ok, value = false, "goal not reached yet" end
      end
      if ok then return true, value, name end
      why[#why + 1] = name .. ": " .. tostring(value)
    end
    return false, "no alternative succeeded (" .. table.concat(why, "; ") .. ")"

  elseif node.kind == "alias" then
    local ok, report = M._pipeline(root, M.parse(node.path).segments, ctx, depth + 1)
    return ok, ok and ctx.it or report.error

  else
    return false, label .. " is a folder of commands; name one of: " .. table.concat(node.order, ", ")
  end
end

function M._pipeline(root, segs, ctx, depth)
  local okp, steps = pcall(M.steps, root, segs)
  if not okp then
    -- Everything is retraceable: if a name is missing, say who removed it, when and why.
    local missing = tostring(steps):match("^unknown name %.(.+)$")
    local h = missing and ctx.history and ctx.history[missing]
    if h then
      steps = ("%s (removed %s by %s%s)"):format(steps, h.when or "?", h.by or "?",
        h.why and (": " .. h.why) or "")
    end
    return false, { error = steps, steps = {} }
  end
  local report = { steps = {} }
  for _, step in ipairs(steps) do
    local args = step.seg.args
    if args then
      local a = {}
      for k, v in ipairs(args) do
        if type(v) == "string" and v:sub(1, 1) == "$" then
          local key = v:sub(2)
          if key == "it" then v = ctx.it else v = ctx.vars[key] end
          if v == nil then return false, { error = step.label .. ": no value named " .. args[k], steps = report.steps } end
        end
        a[k] = v
      end
      args = a
    end
    local ok, value, note = exec(root, step.node, args, ctx, step.label, step.seg.timeout, depth)
    report.steps[#report.steps + 1] = { label = step.label, ok = ok, value = value, picked = note }
    ctx.log(("%s %s%s"):format(ok and "✓" or (step.seg.optional and "–" or "✗"), step.label,
      ok and (note and " → " .. note or "") or ": " .. tostring(value)))
    if ok then
      ctx.it = value
    elseif not step.seg.optional then
      report.error = step.label .. ": " .. tostring(value)
      return false, report
    end
  end
  return true, report
end

-- "means": does a free-form answer mean yes / no? The Android runtime asks the LLM (the only LLM
-- call at run time, and only for this). This token-free fallback knows common words.
local MEANS = {
  yes = { "yes", "yeah", "yep", "sure", "ok", "okay", "i'm in", "im in", "let's go", "lets go", "down",
          "بله", "آره", "اره", "باشه", "حتما", "حتماً", "پایه‌ام", "پایه ام", "پایم", "اوکی" },
  no  = { "no", "nope", "nah", "can't", "cant", "not now", "later", "busy",
          "نه", "نمی‌تونم", "نمیتونم", "الان نه", "بعدا", "بعداً", "سرم شلوغه" },
}
function M.means(value, target)
  local text = " " .. tostring(value or ""):lower() .. " "
  local function has(list)
    for _, w in ipairs(list) do
      local pat = w:gsub("[%^%$%(%)%%%.%[%]%*%+%-%?]", "%%%0")
      if text:find("[^%w]" .. pat .. "[^%w]") then return true end
    end
  end
  target = tostring(target):lower()
  if target == "yes" then return has(MEANS.yes) and not has(MEANS.no) end
  if target == "no" then return has(MEANS.no) and not has(MEANS.yes) end
  return text:find(target, 1, true) ~= nil
end

local function explain(root, cmd, ctx)
  local okx, steps = pcall(M.steps, root, cmd.segments)
  if not okx then return false, steps end
  for _, st in ipairs(steps) do
    local kind = st.node.kind
    local extra = kind == "group" and " try in order: " .. table.concat(st.node.order, ", ")
      or kind == "alias" and " = " .. st.node.path or ""
    ctx.log(("%s  [%s]%s%s"):format(st.label, kind, extra, st.seg.optional and "  (optional)" or ""))
  end
  return true, steps
end

-- Run a program. Statements run as soon as what they depend on is finished, so independent
-- statements run in parallel. A statement depends on:
--   the statement before it, when that one ended with '=>' (its result is $it here)
--   the statement that stored a $name it uses (in arguments or in 'when $name ...')
--   every start/ask statement, if it is a 'later' statement
-- Verbs:
--   start   run automatically
--   ask     run, then the person approves the result before its dependents continue
--   later   automatic work after everything else (the Android runtime picks the moment)
--   explain show what would run, run nothing
-- Parallelism here is cooperative: each statement is a coroutine, and an action that waits
-- (for a reply, a connection) calls ctx.wait() to let the others run. The Android runtime
-- uses real threads/processes with the same dependency rules.
-- opts: log(line), clock() -> seconds, approve(statement, value) -> bool, notify(ok, report),
--       means(value, target) -> bool, syntax (see M.syntax), env,
--       history = { [name] = {when=, by=, why=} }  the removal log, used to explain missing names
function M.run(root, src, opts)
  opts = opts or {}
  local shared = { log = opts.log or print, clock = opts.clock or os.time, done = {},
                   env = opts.env or {}, vars = {}, wait = coroutine.yield,
                   history = opts.history }
  local report = { steps = {}, skipped = {}, order = {} }
  local function finish(ok, err)
    report.error = err
    if opts.notify then opts.notify(ok, report) end
    return ok, report
  end

  local cmds = {}
  for _, st in ipairs(M.split(src, opts.syntax)) do
    local okp, cmd = pcall(M.parse, st.text)
    if not okp then return finish(false, cmd) end
    if not ({ start = 1, ask = 1, later = 1, explain = 1 })[cmd.verb] then
      return finish(false, "unknown verb " .. cmd.verb)
    end
    cmd.text, cmd.sep = st.text, st.sep
    cmds[#cmds + 1] = cmd
  end

  -- dependencies
  local function uses(cmd)
    local names = {}
    local function scan(segs)
      for _, seg in ipairs(segs or {}) do
        for _, a in ipairs(seg.args or {}) do
          if type(a) == "string" and a:sub(1, 1) == "$" then names[#names + 1] = a:sub(2) end
        end
      end
    end
    scan(cmd.segments); scan(cmd["until"])
    if cmd.when then if cmd.when.var then names[#names + 1] = cmd.when.var else scan(cmd.when) end end
    return names
  end
  for i, cmd in ipairs(cmds) do
    local deps = {}
    if i > 1 and cmds[i - 1].sep == "=>" then deps[i - 1] = true; cmd.feed = i - 1 end
    for _, name in ipairs(uses(cmd)) do
      if name == "it" then
        if i > 1 then deps[i - 1] = true; cmd.feed = i - 1 end
      else
        for j = i - 1, 1, -1 do if cmds[j].bind == name then deps[j] = true; break end end
      end
    end
    if cmd.verb == "later" then
      for j, c in ipairs(cmds) do if c.verb ~= "later" and j ~= i then deps[j] = true end end
    end
    cmd.deps = deps
  end

  local function statement(cmd, ctx)
    if cmd.verb == "explain" then
      local ok, steps = explain(root, cmd, ctx)
      if not ok then return false, steps end
      for _, st in ipairs(steps) do report.steps[#report.steps + 1] = st end
      return true
    end
    local go = true
    if cmd.when and cmd.when.var then
      local v = ctx.vars[cmd.when.var]
      if cmd.when.means then
        go = (opts.means or M.means)(v, cmd.when.value) == true
      else
        local same = v == cmd.when.value or tostring(v):lower() == tostring(cmd.when.value):lower()
        go = same ~= cmd.when.neg
      end
    elseif cmd.when then
      local log = ctx.log; ctx.log = function() end
      go = M._pipeline(root, cmd.when, ctx, 0)
      ctx.log = log
    end
    if not go then
      report.skipped[#report.skipped + 1] = cmd.text
      ctx.log("– skipped (condition not met): " .. cmd.text)
      return true, "skipped"
    end
    ctx["until"] = cmd["until"]
    local ok, sub = M._pipeline(root, cmd.segments, ctx, 0)
    ctx["until"] = nil
    for _, st in ipairs(sub.steps) do report.steps[#report.steps + 1] = st end
    if not ok then return false, sub.error end
    if cmd.verb == "ask" then
      local approve = opts.approve or function() return false end
      if not approve(cmd.text, ctx.it) then return false, "not approved: " .. cmd.text end
    end
    if cmd.bind then ctx.vars[cmd.bind] = ctx.it end
    return true
  end

  -- cooperative scheduler
  local state, co, ctxs = {}, {}, {}
  local remaining = #cmds
  while remaining > 0 do
    local progressed = false
    for i, cmd in ipairs(cmds) do
      if not state[i] then
        local ready = true
        for j in pairs(cmd.deps) do if state[j] ~= "done" then ready = false end end
        if ready then
          local ctx = setmetatable({}, { __index = shared })
          if cmd.feed then ctx.it = ctxs[cmd.feed].it end
          ctxs[i] = ctx
          co[i] = coroutine.create(function() return statement(cmd, ctx) end)
          state[i] = "running"
          report.order[#report.order + 1] = "start " .. i
        end
      end
      if state[i] == "running" then
        progressed = true
        local okc, ok, err = coroutine.resume(co[i])
        if not okc then return finish(false, cmd.text .. ": " .. tostring(ok)) end
        if coroutine.status(co[i]) == "dead" then
          if not ok then return finish(false, err) end
          state[i] = "done"; remaining = remaining - 1
          report.order[#report.order + 1] = "end " .. i
        end
      end
    end
    if not progressed then return finish(false, "nothing can run (circular wait)") end
  end
  return finish(true)
end

return M
