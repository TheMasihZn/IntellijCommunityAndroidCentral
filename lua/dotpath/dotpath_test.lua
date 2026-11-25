-- Run: lua5.4 lua/dotpath/dotpath_test.lua   (from the repo root)
package.path = arg[0]:gsub("[^/]*$", "") .. "?.lua;" .. package.path
local dp = require("dotpath")

local calls = {}
local function act(name, ok, value)
  return { run = function(ctx, args)
    calls[#calls + 1] = name .. (args and "(" .. table.concat(args, ",") .. ")" or "")
    return ok, value or name
  end }
end

local function fresh()
  calls = {}
  return dp.tree{
    [".vpn"] = { { "wireguard", act("wireguard", false, "no handshake") },
                 { "openvpn", act("openvpn", true) },
                 { "tor", act("tor", true) } },
    telegram = { write = { run = function(ctx, args)
      calls[#calls + 1] = "write(" .. args[1] .. ") via " .. tostring(ctx.it); return true end } },
    counterscreen = act("counterscreen", true),
    [".virtual"] = { { "screen", act("virtual.screen", true) } },
    stream = ".vpn.counterscreen.virtual.screen.",
    broken = act("broken", false, "boom"),
  }
end

local quiet = { log = function() end }
local pass, fail = 0, 0
local function test(name, f)
  local ok, err = pcall(f)
  if ok then pass = pass + 1 else fail = fail + 1; print("FAIL " .. name .. ": " .. tostring(err)) end
end
local function eq(a, b) assert(a == b, ("expected %s, got %s"):format(tostring(b), tostring(a))) end

test("first success wins and stops", function()
  local ok, r = dp.run(fresh(), "start .vpn.", quiet)
  assert(ok); eq(r.steps[1].picked, "openvpn"); eq(#calls, 2)
end)

test("explicit pick inside a group", function()
  local ok = dp.run(fresh(), ".vpn.wireguard.", quiet)
  assert(not ok); eq(calls[1], "wireguard"); eq(#calls, 1)
end)

test("stages run in order and pass the value on", function()
  local ok = dp.run(fresh(), 'start .vpn.telegram.write("salam").', quiet)
  assert(ok); eq(calls[3], "write(salam) via openvpn")
end)

test("alias expands", function()
  local ok, r = dp.run(fresh(), "start .stream.", quiet)
  assert(ok, r.error); eq(calls[#calls], "virtual.screen")
end)

test("failure stops the pipeline", function()
  local ok, r = dp.run(fresh(), "start .broken.vpn.", quiet)
  assert(not ok); eq(#calls, 1); assert(r.error:find("boom"))
end)

test("optional stage does not stop it", function()
  local ok = dp.run(fresh(), "start .broken?.vpn.", quiet)
  assert(ok); eq(calls[#calls], "openvpn")
end)

test("never compute twice in one run", function()
  local ok = dp.run(fresh(), "start .vpn.stream.", quiet)
  assert(ok)
  local n = 0; for _, c in ipairs(calls) do if c == "openvpn" then n = n + 1 end end
  eq(n, 1)
end)

test("check() skips work already done", function()
  local root = dp.tree{ [".vpn"] = { { "openvpn", { check = function() return true, "tun0" end,
    run = function() error("should not run") end } } } }
  local ok, r = dp.run(root, ".vpn.", quiet); assert(ok); eq(r.steps[1].value, "tun0")
end)

test("namespace alone is an error", function()
  local ok, r = dp.run(fresh(), ".telegram.", quiet); assert(not ok); assert(r.error:find("write"))
end)

test("'..' forces a new step", function()
  local root = dp.tree{ a = { b = act("a.b", true) }, b = act("b", true) }
  dp.run(root, ".a..b.", quiet) -- a alone is a namespace -> fails
  calls = {}
  local steps = dp.steps(root, dp.parse(".a.b").segments); eq(#steps, 1)
  steps = dp.steps(root, dp.parse(".a..b").segments); eq(#steps, 2)
end)

test("parser: args, optional, timeout", function()
  local s = dp.parse('start .telegram.write("a \\"b\\"", 3, x)?@2m.').segments
  eq(s[2].args[1], 'a "b"'); eq(s[2].args[2], 3); eq(s[2].args[3], "x")
  assert(s[2].optional); eq(s[2].timeout, 120)
end)

test("parser errors", function()
  assert(not pcall(dp.parse, "start vpn")); assert(not pcall(dp.parse, '.a("x')); assert(not pcall(dp.parse, ".a b"))
end)

test("unknown name", function()
  local ok, r = dp.run(fresh(), ".nope.", quiet); assert(not ok); assert(r.error:find("nope"))
end)

test("load_dir reads folders", function()
  local d = os.tmpname(); os.remove(d); os.execute("mkdir -p " .. d .. "/.vpn " .. d .. "/telegram")
  local function w(p, s) local f = io.open(d .. "/" .. p, "w"); f:write(s); f:close() end
  w(".vpn/10-wireguard.sh", "exit 1"); w(".vpn/20-openvpn.sh", "echo up")
  w("telegram/write.lua", "return function(ctx, a) return true, 'sent ' .. a[1] end")
  w("stream.path", ".vpn.telegram.write(hi).\n")
  local root = dp.load_dir(d)
  eq(table.concat(root.children.vpn.order, ","), "wireguard,openvpn")
  local ok, r = dp.run(root, "start .stream.", quiet)
  assert(ok, r.error); eq(r.steps[1].value, "sent hi")
  os.execute("rm -rf " .. d)
end)

local function parsa_tree(filtered)
  calls = {}
  local function rec(name, value) return { run = function(ctx, a)
    calls[#calls + 1] = name .. "(" .. table.concat((function() local t = {}
      for i, v in ipairs(a or {}) do t[i] = tostring(v) end return t end)(), ",") .. ")"
    return true, value or name end } end
  return dp.tree{
    photos = { find = rec("find", "luna.jpg") },
    telegram = { draft = rec("draft", "draft#7"), send = rec("send", "sent") },
    net = { filtered = { run = function() return filtered end } },
    [".vpn"] = { { "openvpn", rec("openvpn") } },
  }
end

local PARSA = [[
# "send Parsa the photo of Luna from last night, he'd like it"
ask   .photos.find("Luna", last-night) -> photo;
ask   .telegram.draft(Parsa, $photo, "فکر کنم اینو دوست داری") -> draft;
later .vpn when .net.filtered =>
later .telegram.send($draft).
]]

test("program: ask first, later after, bindings, notify", function()
  local asked, notified = {}, nil
  local ok, r = dp.run(parsa_tree(true), PARSA, { log = function() end,
    approve = function(text) asked[#asked + 1] = text; return true end,
    notify = function(ok) notified = ok end })
  assert(ok, r.error); eq(#asked, 2); eq(notified, true)
  eq(calls[1], "find(Luna,last-night)")
  eq(calls[2], "draft(Parsa,luna.jpg,فکر کنم اینو دوست داری)")
  eq(calls[3], "openvpn()"); eq(calls[4], "send(draft#7)")
end)

test("program: condition false skips the VPN", function()
  local ok, r = dp.run(parsa_tree(false), PARSA, { log = function() end, approve = function() return true end })
  assert(ok, r.error); eq(#r.skipped, 1); eq(calls[3], "send(draft#7)")
end)

test("program: rejected ask stops before sending", function()
  local ok, r = dp.run(parsa_tree(true), PARSA, { log = function() end,
    approve = function(text) return not text:find("draft") end })
  assert(not ok); assert(r.error:find("not approved")); eq(#calls, 2)
end)

test("split keeps ; inside strings and parens", function()
  local st = dp.split('start .a("x;y");\nstart .b # c;d\n\nstart .c.')
  eq(#st, 3); eq(st[1].text, 'start .a("x;y")'); eq(st[2].text, "start .b"); eq(st[3].text, "start .c.")
  local t = dp.split("start .a =>\nstart .b; start .c.")
  eq(t[1].sep, "=>"); eq(t[2].sep, ";"); eq(#t, 3)
end)

test("unknown variable is an error", function()
  local ok, r = dp.run(parsa_tree(true), "start .telegram.send($nope).", quiet)
  assert(not ok); assert(r.error:find("nope"))
end)

-- The first script file (first.script), compiled for one phone. See docs/terminal-language.md §7.
local FIRST = [[
start .vpn until .telegram.connected =>
start .telegram.write(Pepe, "wanna play CS:GO") =>
start .telegram.wait-reply(Pepe)@30m -> respond;
start .steam.counter-strike when $respond means yes;
start .obs.stream when $respond means yes.
]]

local function first_tree(answer)
  calls = {}
  local connected = false
  local function vpn(name, works) return { run = function()
    calls[#calls + 1] = name; if works then connected = true end; return true end } end
  local function rec(name, value) return { run = function(_, a)
    calls[#calls + 1] = name .. (a and "(" .. table.concat(a, ",") .. ")" or ""); return true, value end } end
  return dp.tree{
    [".vpn"] = { { "free-a", vpn("free-a", false) }, { "free-b", vpn("free-b", true) }, { "paid", vpn("paid", true) } },
    telegram = { connected = { run = function() return connected end },
                 write = rec("write"), ["wait-reply"] = rec("wait-reply", answer) },
    steam = { ["counter-strike"] = rec("cs") }, obs = { stream = rec("stream") },
  }
end

test("first.script: try VPNs until Telegram connects, then play if yes", function()
  local ok, r = dp.run(first_tree("Yes, I'm in!"), FIRST, quiet)
  assert(ok, r.error)
  eq(table.concat(calls, " "), "free-a free-b write(Pepe,wanna play CS:GO) wait-reply(Pepe) cs stream")
end)

test("first.script: answer no skips the game", function()
  local ok, r = dp.run(first_tree("not now, I'm busy"), FIRST, quiet)
  assert(ok, r.error); eq(#r.skipped, 2); eq(calls[#calls], "wait-reply(Pepe)")
end)

test("when with != and quoted value", function()
  local c = dp.parse('start .a when $x != "a b"'); eq(c.when.value, "a b"); assert(c.when.neg)
end)

test("means: free-form answers", function()
  assert(dp.means("yeah sure", "yes")); assert(dp.means("آره پایه‌ام", "yes"))
  assert(not dp.means("no", "yes")); assert(dp.means("الان نه", "no"))
  assert(not dp.means("yes but not now", "yes"))  -- mixed: fallback says unsure; the LLM decides
end)

-- ';' starts and goes on; '=>' waits (natije-giri). Actions that take time call ctx.wait().
local function timeline_tree(log)
  local function slow(name, ticks) return { run = function(ctx)
    log[#log + 1] = name .. "+"
    for _ = 1, ticks do ctx.wait() end
    log[#log + 1] = name .. "-"
    return true, name
  end } end
  return dp.tree{ steam = slow("cs", 2), obs = slow("obs", 2), slow = slow("slow", 3), fast = slow("fast", 0) }
end

test("';' runs in parallel", function()
  local log = {}
  local ok, r = dp.run(timeline_tree(log), "start .steam; start .obs.", quiet)
  assert(ok, r.error); eq(table.concat(log, " "), "cs+ obs+ cs- obs-")
end)

test("';' does not wait for a slow step", function()
  local log = {}
  dp.run(timeline_tree(log), "start .slow; start .fast.", quiet)
  eq(table.concat(log, " "), "slow+ fast+ fast- slow-")
end)

test("'=>' waits and feeds the result", function()
  local log, got = {}, nil
  local root = timeline_tree(log)
  root.children.show = { kind = "action", run = function(ctx) got = ctx.it; return true end }
  local ok, r = dp.run(root, "start .slow =>\nstart .show.", quiet)
  assert(ok, r.error); eq(got, "slow"); eq(table.concat(log, " "), "slow+ slow-")
end)

test("first.script: both actions in the if-block start together", function()
  local log = {}
  local root = timeline_tree(log)
  root.children.ask = { kind = "action", run = function() return true, "yes I'm in" end }
  local ok, r = dp.run(root, [[
start .ask -> respond;
start .steam when $respond means yes;
start .obs when $respond means yes.
]], quiet)
  assert(ok, r.error); eq(table.concat(log, " "), "cs+ obs+ cs- obs-")
end)

print(("%d passed, %d failed"):format(pass, fail))
os.exit(fail == 0 and 0 or 1)
