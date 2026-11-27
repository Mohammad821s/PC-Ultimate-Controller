// PC Ultimate Controller relay: introduces a phone to its PC so they can open a direct,
// encrypted WebRTC connection. Only small signaling messages pass through here; the
// actual control and screen traffic goes phone <-> PC directly (or via Cloudflare TURN).
//
// One Durable Object ("room") per PC. The PC proves it owns the room with a secret key
// it chose the first time it connected. Phones are not trusted here: the PC itself
// checks the phone's pairing token over the signaling channel before answering.
//
// Optional "waker": a small ESP32 at home that turns the PC on (Wake-on-LAN) while the PC
// is off. The PC registers it (hash of its key) together with hashes of the phones it has
// approved, so a wake request is accepted only from those phones even when the PC is off.
import { DurableObject } from "cloudflare:workers";
import FLASH_HTML from "./flash.html";

const FW_URL = "https://github.com/Mohammad821s/PC-Ultimate-Controller/releases/download/waker-fw/pc-waker.bin";

const ID_RE = /^[a-z0-9]{16,40}$/;
const MAX_MSG = 64 * 1024;
const MAX_PHONE_MSGS = 400;      // a WebRTC handshake needs a few dozen messages
const MAX_PHONES = 4;
const TURN_PER_DAY = 60;         // TURN credential requests per PC per day

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname === "/" || url.pathname === "/health") {
      return new Response("PC Ultimate Controller relay\n", { headers: { "content-type": "text/plain" } });
    }
    // ESP32 flasher (ESP Web Tools): the page, its manifest, and the firmware passed through with CORS
    if (url.pathname === "/flash" || url.pathname === "/flash/") {
      return new Response(FLASH_HTML, { headers: { "content-type": "text/html; charset=utf-8" } });
    }
    if (url.pathname === "/flash/manifest.json") {
      return Response.json({
        name: "PC Waker", version: "1", new_install_prompt_erase: true,
        builds: [{ chipFamily: "ESP32", parts: [{ path: "/flash/pc-waker.bin", offset: 0 }] }],
      }, { headers: { "access-control-allow-origin": "*", "cache-control": "no-store" } });
    }
    if (url.pathname === "/flash/pc-waker.bin") {
      const r = await fetch(FW_URL, { cf: { cacheTtl: 300 } });
      if (!r.ok) return new Response("firmware not built yet", { status: 404 });
      return new Response(r.body, { headers: { "content-type": "application/octet-stream", "access-control-allow-origin": "*" } });
    }
    if (url.pathname !== "/pc" && url.pathname !== "/phone" && url.pathname !== "/waker") return new Response("not found", { status: 404 });
    const id = url.searchParams.get("id") || "";
    if (!ID_RE.test(id)) return new Response("bad id", { status: 400 });
    if ((req.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    return env.ROOM.getByName(id).fetch(req);
  },
};

async function sha256(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export class Room extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.env = env;
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)");
    // keepalive pings are answered without waking the object (no compute billed)
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('{"t":"ping"}', '{"t":"pong"}'));
  }

  meta(k) {
    const r = this.ctx.storage.sql.exec("SELECT v FROM meta WHERE k = ?", k).toArray()[0];
    return r ? r.v : null;
  }
  setMeta(k, v) {
    this.ctx.storage.sql.exec("INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v", k, v);
  }

  // A PC that is switched off can leave its socket open here (no close frame arrives).
  // It pings every 25 s, so a socket silent for 90 s is treated as gone and closed.
  pc() {
    const pc = this.ctx.getWebSockets("pc")[0];
    if (!pc) return null;
    const att = pc.deserializeAttachment() || {};
    const pinged = this.ctx.getWebSocketAutoResponseTimestamp(pc);
    const last = Math.max(pinged ? pinged.getTime() : 0, att.since || 0);
    if (Date.now() - last > 90000) {
      try { pc.close(4001, "silent"); } catch {}
      return null;
    }
    return pc;
  }
  waker() { return this.ctx.getWebSockets("waker")[0] || null; }

  wakerState() {
    return { t: "waker_state", waker: this.meta("waker_key") !== null, waker_online: !!this.waker() };
  }

  toPhones(m) { for (const p of this.ctx.getWebSockets("phone")) p.send(JSON.stringify(m)); }

  async fetch(req) {
    const url = new URL(req.url);
    const role = url.pathname === "/pc" ? "pc" : url.pathname === "/waker" ? "waker" : "phone";
    if (role === "waker") {
      const known = this.meta("waker_key");
      const key = url.searchParams.get("key") || "";
      if (known === null || key.length < 24 || (await sha256(key)) !== known) return new Response("forbidden", { status: 403 });
      for (const old of this.ctx.getWebSockets("waker")) old.close(4000, "replaced");
    } else if (role === "pc") {
      const key = url.searchParams.get("key") || "";
      if (key.length < 24) return new Response("bad key", { status: 400 });
      const h = await sha256(key);
      const known = this.meta("pc_key");
      if (known === null) this.setMeta("pc_key", h);
      else if (known !== h) return new Response("forbidden", { status: 403 });
      for (const old of this.ctx.getWebSockets("pc")) old.close(4000, "replaced");
    } else if (this.ctx.getWebSockets("phone").length >= MAX_PHONES) {
      return new Response("busy", { status: 429 });
    }
    const [client, server] = Object.values(new WebSocketPair());
    const sid = role === "pc" || role === "waker" ? role : crypto.randomUUID().replace(/-/g, "").slice(0, 12);
    this.ctx.acceptWebSocket(server, [role, "s:" + sid]);
    server.serializeAttachment({ role, sid, n: 0, since: Date.now() });
    if (role === "waker") {
      server.send(JSON.stringify({ t: "relay_hello", sid: "waker", mac: this.meta("pc_mac") || "" }));
      this.toPhones(this.wakerState());
      const pc = this.pc();
      if (pc) pc.send(JSON.stringify(this.wakerState()));
    } else if (role === "phone") {
      const pc = this.pc();
      server.send(JSON.stringify({ t: "relay_hello", sid, pc_online: !!pc,
        waker: this.meta("waker_key") !== null, waker_online: !!this.waker() }));
      if (pc) pc.send(JSON.stringify({ t: "peer_join", from: sid }));
    } else {
      server.send(JSON.stringify({ t: "relay_hello", sid: "pc", turn: !!(this.env.TURN_KEY_ID && this.env.TURN_KEY_TOKEN) }));
      for (const p of this.ctx.getWebSockets("phone")) p.send(JSON.stringify({ t: "pc_online" }));
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, msg) {
    if (typeof msg !== "string" || msg.length > MAX_MSG) return;
    const att = ws.deserializeAttachment();
    let m;
    try { m = JSON.parse(msg); } catch { return; }
    if (att.role === "phone") {
      att.n += 1;
      ws.serializeAttachment(att);
      if (att.n > MAX_PHONE_MSGS) { ws.close(4008, "too many messages"); return; }
      if (m.t === "wake") { await this.wake(ws, att, m); return; }
      if (m.t === "waker_state") { ws.send(JSON.stringify(this.wakerState())); return; }
      const pc = this.pc();
      if (!pc) { ws.send(JSON.stringify({ t: "pc_offline" })); return; }
      m.from = att.sid;
      pc.send(JSON.stringify(m));
      return;
    }
    if (att.role === "waker") {
      // "woke": the magic packet went out; tell whoever asked
      if (m.t === "woke" && m.to) {
        const target = this.ctx.getWebSockets("s:" + m.to)[0];
        if (target) target.send(JSON.stringify({ t: "wake_result", ok: true }));
      }
      return;
    }
    // from the PC
    if (m.t === "waker_set") {
      // register (or replace) the waker and the approved phones; "key_hash" null removes it
      if (m.key_hash === null) {
        this.ctx.storage.sql.exec("DELETE FROM meta WHERE k IN ('waker_key','phones','pc_mac')");
        for (const w of this.ctx.getWebSockets("waker")) w.close(4003, "removed");
      } else {
        if (typeof m.key_hash === "string" && /^[0-9a-f]{64}$/.test(m.key_hash)) this.setMeta("waker_key", m.key_hash);
        if (Array.isArray(m.phones)) this.setMeta("phones", JSON.stringify(m.phones.filter((h) => /^[0-9a-f]{64}$/.test(h)).slice(0, 32)));
        if (typeof m.mac === "string" && /^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$/.test(m.mac)) this.setMeta("pc_mac", m.mac);
        const w = this.waker();
        if (w) w.send(JSON.stringify({ t: "config", mac: this.meta("pc_mac") || "" }));
      }
      ws.send(JSON.stringify(this.wakerState()));
      this.toPhones(this.wakerState());
      return;
    }
    if (m.t === "waker_state") { ws.send(JSON.stringify(this.wakerState())); return; }
    if (m.t === "turn") {
      ws.send(JSON.stringify({ t: "turn", req: m.req || null, iceServers: await this.turn() }));
      return;
    }
    const to = m.to;
    delete m.to;
    const target = to && this.ctx.getWebSockets("s:" + to)[0];
    if (target) target.send(JSON.stringify(m));
  }

  async wake(ws, att, m) {
    const reply = (o) => ws.send(JSON.stringify({ t: "wake_result", ...o }));
    let phones = [];
    try { phones = JSON.parse(this.meta("phones") || "[]"); } catch {}
    const token = typeof m.token === "string" ? m.token : "";
    if (!token || !phones.includes(await sha256(token))) { reply({ ok: false, error: "not_allowed" }); return; }
    if (this.pc()) { reply({ ok: true, already_on: true }); return; }
    const w = this.waker();
    if (!w) { reply({ ok: false, error: "waker_offline" }); return; }
    w.send(JSON.stringify({ t: "wake", from: att.sid, mac: this.meta("pc_mac") || "" }));
  }

  async turn() {
    const stun = [{ urls: ["stun:stun.cloudflare.com:3478"] }];
    if (!this.env.TURN_KEY_ID || !this.env.TURN_KEY_TOKEN) return stun;
    const day = new Date().toISOString().slice(0, 10);
    const used = this.meta("turn_day") === day ? Number(this.meta("turn_n") || 0) : 0;
    if (used >= TURN_PER_DAY) return stun;
    this.setMeta("turn_day", day);
    this.setMeta("turn_n", String(used + 1));
    try {
      const r = await fetch(
        `https://rtc.live.cloudflare.com/v1/turn/keys/${this.env.TURN_KEY_ID}/credentials/generate-ice-servers`,
        {
          method: "POST",
          headers: { Authorization: `Bearer ${this.env.TURN_KEY_TOKEN}`, "Content-Type": "application/json" },
          body: JSON.stringify({ ttl: 6 * 3600 }),
        },
      );
      if (!r.ok) return stun;
      const j = await r.json();
      // port 53 is blocked on many networks and only causes timeouts
      return (j.iceServers || []).map((s) => ({
        ...s,
        urls: (Array.isArray(s.urls) ? s.urls : [s.urls]).filter((u) => !u.includes(":53")),
      }));
    } catch {
      return stun;
    }
  }

  async webSocketClose(ws) {
    const att = ws.deserializeAttachment() || {};
    if (att.role === "phone") {
      const pc = this.pc();
      if (pc) pc.send(JSON.stringify({ t: "peer_left", from: att.sid }));
    } else if (att.role === "pc") {
      for (const p of this.ctx.getWebSockets("phone")) p.send(JSON.stringify({ t: "pc_offline" }));
    } else if (att.role === "waker") {
      this.toPhones({ t: "waker_state", waker: this.meta("waker_key") !== null, waker_online: false });
    }
  }

  async webSocketError(ws) { return this.webSocketClose(ws); }
}
