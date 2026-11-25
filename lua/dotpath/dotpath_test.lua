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

print(("%d passed, %d failed"):format(pass, fail))
os.exit(fail == 0 and 0 or 1)
