// PC Ultimate Controller relay: introduces a phone to its PC so they can open a direct,
// encrypted WebRTC connection. Only small signaling messages pass through here; the
// actual control and screen traffic goes phone <-> PC directly (or via Cloudflare TURN).
//
// One Durable Object ("room") per PC. The PC proves it owns the room with a secret key
// it chose the first time it connected. Phones are not trusted here: the PC itself
// checks the phone's pairing token over the signaling channel before answering.
import { DurableObject } from "cloudflare:workers";

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
    if (url.pathname !== "/pc" && url.pathname !== "/phone") return new Response("not found", { status: 404 });
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

  pc() { return this.ctx.getWebSockets("pc")[0] || null; }

  async fetch(req) {
    const url = new URL(req.url);
    const role = url.pathname === "/pc" ? "pc" : "phone";
    if (role === "pc") {
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
    const sid = role === "pc" ? "pc" : crypto.randomUUID().replace(/-/g, "").slice(0, 12);
    this.ctx.acceptWebSocket(server, [role, "s:" + sid]);
    server.serializeAttachment({ role, sid, n: 0 });
    if (role === "phone") {
      const pc = this.pc();
      server.send(JSON.stringify({ t: "relay_hello", sid, pc_online: !!pc }));
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
      const pc = this.pc();
      if (!pc) { ws.send(JSON.stringify({ t: "pc_offline" })); return; }
      m.from = att.sid;
      pc.send(JSON.stringify(m));
      return;
    }
    // from the PC
    if (m.t === "turn") {
      ws.send(JSON.stringify({ t: "turn", req: m.req || null, iceServers: await this.turn() }));
      return;
    }
    const to = m.to;
    delete m.to;
    const target = to && this.ctx.getWebSockets("s:" + to)[0];
    if (target) target.send(JSON.stringify(m));
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
    }
  }

  async webSocketError(ws) { return this.webSocketClose(ws); }
}
