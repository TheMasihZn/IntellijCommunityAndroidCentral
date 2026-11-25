-- DotPath: reference interpreter for the DevOs terminal language.
-- Spec: docs/terminal-language.md. Plain Lua 5.4, no dependencies.
--
--   local dp = require("dotpath")
--   local root = dp.load_dir("/sdcard/DevOs")     -- or dp.tree{...} in tests
--   local ok, report = dp.run(root, 'start .vpn.telegram.write("hi").')

local M = {}

---------------------------------------------------------------- parser

-- Returns { verb = "start", segments = { {name=, args=, optional=, timeout=} | {brk=true} ... } }
function M.parse(src)
  local s = src:match("^%s*(.-)%s*$")
  local verb, rest = s:match("^(%a+)%s+(.*)$")
  if verb then s = rest else verb = "start" end
  if s:sub(1, 1) ~= "." then error("a path starts with '.': " .. s, 0) end

  local segs, i, n = {}, 1, #s
  while i <= n do
    local c = s:sub(i, i)
    if c == "." then
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
      if nxt ~= "" and nxt ~= "." then
        error(("expected '.' after %s at column %d"):format(name, i), 0)
      end
      segs[#segs + 1] = seg
    end
  end
  return { verb = verb, segments = segs }
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
  if not okp then return false, { error = steps, steps = {} } end
  local report = { steps = {} }
  for _, step in ipairs(steps) do
    local ok, value, note = exec(root, step.node, step.seg.args, ctx, step.label, step.seg.timeout, depth)
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

-- Run a command line. opts: log=function(line), clock=function()->seconds, env=table
function M.run(root, src, opts)
  opts = opts or {}
  local okp, cmd = pcall(M.parse, src)
  if not okp then return false, { error = cmd, steps = {} } end
  local ctx = { log = opts.log or print, clock = opts.clock or os.time, done = {}, env = opts.env or {} }
  if cmd.verb == "explain" then
    local okx, steps = pcall(M.steps, root, cmd.segments)
    if not okx then return false, { error = steps, steps = {} } end
    for _, st in ipairs(steps) do
      local kind = st.node.kind
      local extra = kind == "group" and " try in order: " .. table.concat(st.node.order, ", ")
        or kind == "alias" and " = " .. st.node.path or ""
      ctx.log(("%s  [%s]%s%s"):format(st.label, kind, extra, st.seg.optional and "  (optional)" or ""))
    end
    return true, { steps = steps }
  elseif cmd.verb ~= "start" then
    return false, { error = "unknown verb " .. cmd.verb, steps = {} }
  end
  return M._pipeline(root, cmd.segments, ctx, 0)
end

return M
