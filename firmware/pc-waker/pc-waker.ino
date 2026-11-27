// PC Waker: an ESP32 that stays at home and turns the PC on (Wake-on-LAN) when the
// PC Ultimate Controller app asks, from anywhere.
//
// First start: it opens a Wi-Fi network "PC-Waker-Setup". Join it from the phone and pick
// the home Wi-Fi. Then the PC app links it (finds it on the home network and gives it the
// relay room and a secret key). From then on it keeps one secure connection to the relay
// and sends the magic packet when an approved phone asks.
//
// Hold the BOOT button for 6 seconds to forget Wi-Fi and the link (factory reset).

#include <WiFi.h>
#include <WiFiUdp.h>
#include <WebServer.h>
#include <Preferences.h>
#include <WiFiManager.h>         // tzapu/WiFiManager
#include <WebSocketsClient.h>    // links2004/WebSockets
#include <ArduinoJson.h>
#include "roots.h"

#define FW_VERSION "1.0.0"
#define LED_PIN 2
#define BOOT_PIN 0
#define DISCOVERY_PORT 8768

Preferences prefs;
WebServer http(80);
WiFiUDP udp;
WebSocketsClient ws;

String relayHost, roomId, wakerKey, pcMac;
bool linked = false;
bool relayUp = false;
unsigned long lastPing = 0, bootDown = 0, lastBlink = 0;

void led(bool on) { digitalWrite(LED_PIN, on ? HIGH : LOW); }

void loadConfig() {
  prefs.begin("waker", true);
  relayHost = prefs.getString("host", "");
  roomId = prefs.getString("room", "");
  wakerKey = prefs.getString("key", "");
  pcMac = prefs.getString("mac", "");
  prefs.end();
  linked = relayHost.length() && roomId.length() && wakerKey.length() >= 24;
}

void saveConfig() {
  prefs.begin("waker", false);
  prefs.putString("host", relayHost);
  prefs.putString("room", roomId);
  prefs.putString("key", wakerKey);
  prefs.putString("mac", pcMac);
  prefs.end();
}

bool parseMac(const String& s, uint8_t mac[6]) {
  int v[6];
  if (sscanf(s.c_str(), "%x%*c%x%*c%x%*c%x%*c%x%*c%x", &v[0], &v[1], &v[2], &v[3], &v[4], &v[5]) != 6) return false;
  for (int i = 0; i < 6; i++) mac[i] = (uint8_t)v[i];
  return true;
}

// 6 x FF, then the PC's MAC 16 times; sent to both broadcast addresses, a few times
bool sendMagic(const String& macText) {
  uint8_t mac[6];
  if (!parseMac(macText, mac)) return false;
  uint8_t pkt[102];
  memset(pkt, 0xFF, 6);
  for (int i = 0; i < 16; i++) memcpy(pkt + 6 + i * 6, mac, 6);
  IPAddress ip = WiFi.localIP(), mask = WiFi.subnetMask();
  IPAddress subnetBcast(ip[0] | ~mask[0] & 0xFF, ip[1] | ~mask[1] & 0xFF, ip[2] | ~mask[2] & 0xFF, ip[3] | ~mask[3] & 0xFF);
  for (int r = 0; r < 3; r++) {
    for (IPAddress dst : {IPAddress(255, 255, 255, 255), subnetBcast}) {
      for (uint16_t port : {9, 7}) {
        udp.beginPacket(dst, port);
        udp.write(pkt, sizeof(pkt));
        udp.endPacket();
      }
    }
    delay(80);
  }
  return true;
}

void onWs(WStype_t type, uint8_t* payload, size_t len) {
  if (type == WStype_CONNECTED) { relayUp = true; return; }
  if (type == WStype_DISCONNECTED) { relayUp = false; return; }
  if (type != WStype_TEXT) return;
  JsonDocument m;
  if (deserializeJson(m, payload, len)) return;
  const char* t = m["t"] | "";
  if (!strcmp(t, "relay_hello") || !strcmp(t, "config")) {
    String mac = m["mac"] | "";
    if (mac.length() && mac != pcMac) { pcMac = mac; saveConfig(); }
  } else if (!strcmp(t, "wake")) {
    String mac = m["mac"] | "";
    if (!mac.length()) mac = pcMac;
    bool ok = sendMagic(mac);
    for (int i = 0; i < 3; i++) { led(true); delay(60); led(false); delay(60); }
    JsonDocument r;
    r["t"] = "woke"; r["to"] = m["from"] | ""; r["ok"] = ok;
    String out; serializeJson(r, out);
    ws.sendTXT(out);
  }
}

void startRelay() {
  if (!linked) return;
  ws.disconnect();
  String path = "/waker?id=" + roomId + "&key=" + wakerKey;
  ws.beginSslWithCA(relayHost.c_str(), 443, path.c_str(), ROOT_CAS);
  ws.onEvent(onWs);
  ws.setReconnectInterval(5000);
}

// ---- linking from the PC (home network only)

String infoJson() {
  JsonDocument d;
  d["t"] = "waker"; d["fw"] = FW_VERSION; d["linked"] = linked; d["relay"] = relayUp;
  d["id"] = String((uint32_t)(ESP.getEfuseMac() & 0xFFFFFFFF), HEX);
  String s; serializeJson(d, s); return s;
}

void handleInfo() { http.send(200, "application/json", infoJson()); }

void handleLink() {
  JsonDocument d;
  if (deserializeJson(d, http.arg("plain"))) { http.send(400, "text/plain", "bad json"); return; }
  // once linked, only the same PC (that knows the current key) can re-link it
  if (linked && wakerKey != String(d["old_key"] | "")) { http.send(403, "text/plain", "already linked"); return; }
  String host = d["host"] | "", room = d["room"] | "", key = d["key"] | "";
  if (!host.length() || !room.length() || key.length() < 24) { http.send(400, "text/plain", "missing fields"); return; }
  relayHost = host; roomId = room; wakerKey = key; pcMac = String(d["mac"] | "");
  saveConfig();
  linked = true;
  http.send(200, "application/json", infoJson());
  startRelay();
}

void handleUdp() {
  int n = udp.parsePacket();
  if (n <= 0) return;
  char buf[32] = {0};
  udp.read(buf, sizeof(buf) - 1);
  if (strncmp(buf, "PUC_WAKER?", 10)) return;
  String s = infoJson();
  udp.beginPacket(udp.remoteIP(), udp.remotePort());
  udp.write((const uint8_t*)s.c_str(), s.length());
  udp.endPacket();
}

void factoryReset() {
  for (int i = 0; i < 10; i++) { led(i % 2); delay(100); }
  prefs.begin("waker", false); prefs.clear(); prefs.end();
  WiFiManager wm; wm.resetSettings();
  ESP.restart();
}

void setup() {
  pinMode(LED_PIN, OUTPUT);
  pinMode(BOOT_PIN, INPUT_PULLUP);
  led(true);
  loadConfig();
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFiManager wm;
  wm.setConfigPortalTimeout(600);
  wm.setTitle("PC Waker");
  if (!wm.autoConnect("PC-Waker-Setup")) ESP.restart();
  led(false);
  udp.begin(DISCOVERY_PORT);
  http.on("/", HTTP_GET, handleInfo);
  http.on("/info", HTTP_GET, handleInfo);
  http.on("/link", HTTP_POST, handleLink);
  http.begin();
  startRelay();
}

void loop() {
  http.handleClient();
  handleUdp();
  if (linked) ws.loop();
  unsigned long now = millis();
  if (relayUp && now - lastPing > 30000) { lastPing = now; ws.sendTXT("{\"t\":\"ping\"}"); }
  // LED: off = all good; slow blink = waiting for link; fast blink = no connection to the relay
  if (!linked) { if (now - lastBlink > 1000) { lastBlink = now; led(!digitalRead(LED_PIN)); } }
  else if (!relayUp) { if (now - lastBlink > 250) { lastBlink = now; led(!digitalRead(LED_PIN)); } }
  else led(false);
  if (WiFi.status() != WL_CONNECTED) { WiFi.reconnect(); delay(500); }
  if (digitalRead(BOOT_PIN) == LOW) {
    if (!bootDown) bootDown = now;
    else if (now - bootDown > 6000) factoryReset();
  } else bootDown = 0;
  delay(2);
}
