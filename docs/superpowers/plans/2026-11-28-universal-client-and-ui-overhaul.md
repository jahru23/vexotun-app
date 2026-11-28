# VexoTun Universal Client & UI Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `vexotun-app` into a reliable, universal Android VPN client (compatible with both third-party Xray/SSH/SlowDNS servers and VexoTun nodes) with real Xray connection verification, internal Xray logging, full protocol parity (Reality, TLS `vcn`/`pcs`/legacy `allowInsecure`, standard `dnstt`, raw HTTP 101 SSH-WS, UDPGW), hardened security, and the WCAG-AA compliant "Matte Network Instrument" UI overhaul.

**Architecture:** Deepen the Go↔Kotlin seam so `core` owns connection lifecycle verification, Xray protobuf patching, internal Xray log bridging, and universal protocol adapters (`StandardDNSTT` vs `VexDNS`, raw 101 WS vs RFC 6455 WS), while Kotlin owns a cleanly typed protocol/link model (`TunnelModels`, `LinkParser`, `LinkExporter`, `XrayConfigBuilder`), single-source UI state (`MainScreenViewModel`, `TunnelState`), and a flat, high-contrast Compose UI without duplicated imports or dead code. Every Go change is verified with `go test -race ./...`; Kotlin unit tests and `assembleRelease` APK builds are verified via GitHub Actions CI (`build-apk.yml`), plus WCAG contrast verification locally via Python.

**Tech Stack:** Go 1.26.3 (`xray-core v1.260327.0`, `x/crypto/ssh`, `dnstt`, `kcp-go`, `smux`, `gobind`), Kotlin 2.3.20, Jetpack Compose Material 3 (`compose-bom 2026.03.01`), `kotlinx.serialization`, `hev-socks5-tunnel 2.20.0`, GitHub Actions.

**Spec:** [vexotun-full-audit-and-redesign.md](file:///home/jae/.gemini/antigravity/brain/50f4f1de-ee1e-4624-8a8f-7ff3288a5f4c/vexotun-full-audit-and-redesign.md)

## Global Constraints

- Do NOT break backward compatibility with existing saved `vexotun_config.json` or `.vxt` v1/v2 encrypted files (`@Serializable` new fields MUST have sensible defaults matching legacy behavior where migration isn't explicit).
- Preserve all existing `core` exported `gobind` signatures (`Prepare`, `Stop`, `TrimMemory`, `IsRunning`, `GetStats`, `PingTCP`, `PingHTTP`, `FetchSocksURL`, `SpeedTestSocks`, `PingSocksHTTP`, `ScanSlowDNS`) or add strictly backward-compatible exports without removing types used by Android.
- Xray-core `v1.260327.0` rejects `"allowInsecure": true` in JSON config after `2026-06-01` (`infra/conf/transport_internet.go:709`), so `AllowInsecure` MUST be applied in Go on the compiled `*xraycore.Config` protobuf (`tls.Config.AllowInsecure`) when requested, alongside first-class JSON support for `verifyPeerCertByName` (`vcn`) and `pinnedPeerCertSha256` (`pcs`).
- Local Gradle offline builds fail in this sandbox (Maven 403); `go test -race ./...` MUST pass locally after every Go change, and `.github/workflows/build-apk.yml` MUST run `go test -race ./...`, `./gradlew testDebugUnitTest --no-daemon`, `gomobile bind`, and `./gradlew assembleRelease --no-daemon`.
- All UI color pairs MUST pass WCAG 2.1 AA (`>= 4.5:1` normal text, `>= 3.0:1` large text & interactive/status non-text edges) in both Dark (`#121417`) and Light (`#F6F7F5`) modes.

## Review Focus

1. **Xray link remarks containing spaces, `[ ]`, `|`, emoji, or unencoded query characters** (e.g. `vless://uuid@1.2.3.4:443?type=ws#SG 01 [VIP] 🚀`): `LinkParser` must parse host/query/remark cleanly without truncating at the first space or throwing `URISyntaxException`.
2. **Trojan share links omitting `?security=tls`** (`trojan://password@example.com:443#Node`): must default TLS to enabled (`security = "tls"`), not `"none"`.
3. **Rapid Connect -> Stop -> Reconnect cycles & dead Xray remote endpoints**: `Stop()` must wait for any in-flight `Prepare()` to abort before returning, and Xray with `verify=true` must fail cleanly with an actionable error instead of reporting `CONNECTED` when the server/TLS/WS/UUID fails.
4. **External `.vxt` file intents requiring a PIN**: opening a `VXT2:` `.vxt` file via `ACTION_VIEW` in `MainActivity` must prompt for the PIN via `VxtPinPromptDialog` instead of failing with a Toast.
5. **Xray Custom Payload bridge enabled on WebSocket/TLS profiles**: must not inject a double HTTP `Upgrade: websocket` request inside an already-upgraded stream or collide with Reality.

---

### Task 1: Deep Go Core — Xray Internal Log Bridge, Real Connection Verification, Protobuf AllowInsecure & Stop/Start Concurrency

**Files:**
- Modify: `core/xray_engine.go`
- Modify: `core/core.go`
- Modify: `core/xray_payload_bridge.go`
- Test: `core/core_test.go`

**Interfaces:**
- Consumes: `logMsg` (`core/logger.go`), `dialViaSocks5Addr` (`core/http_proxy.go`), `xraycore.LoadConfig` / `xraycore.New`.
- Produces:
  - Updated `TunnelConfig` JSON fields (additive): `xrayVerify bool`, `xrayAllowInsecure bool`, `xrayProbeUrl string`.
  - Safe `Prepare(...)` verifying Xray remote reachability when `xrayVerify == true` (default `true` for Xray if `XrayVerify == nil`), Mux auto-fallback + re-verification, `applyXrayAllowInsecurePb(pb *xraycore.Config)` + `installXrayLogBridge()`, and `startDone` wait in `Stop()` + `IsRunning()` listener probe for Xray.

- [ ] **Step 1: Write failing Go tests in `core/core_test.go`**

Add unit tests covering:
1. `TestXrayPrepareVerifyFailsOnUnreachableServer`: configures Xray (`vless` -> closed localhost port) with `xrayVerify: true` and asserts `Prepare()` returns a descriptive error (not `nil` fake-connect) and cleans up `isRunning` / listener.
2. `TestXrayPrepareVerifyCanBeDisabled`: configures Xray -> closed localhost port with `xrayVerify: false` and asserts `Prepare()` succeeds and `IsRunning()` is `true` until `Stop()`.
3. `TestXrayAllowInsecureProtobufApplied`: builds an Xray JSON without JSON `allowInsecure` (which v26 blocks post-2026-06-01), parses with `loadXrayProtobufConfig(json, allowInsecure=true)`, and asserts `tls.Config.AllowInsecure == true` inside the protobuf outbound `StreamSettings`.
4. `TestXrayLogBridgeForwardsWarnings`: emits an `xraylog.GeneralMessage` while `setLogHandler` is active and asserts `LogHandler.OnLog` receives it.
5. `TestPrepareStopConcurrencyNoRace`: runs concurrent `Prepare` + `Stop` calls under `go test -race` and verifies `Prepare` never leaks a running engine after `Stop` returns.

- [ ] **Step 2: Run Go tests to confirm failure**

Run: `cd core && go test -race -v -run "TestXray|TestPrepareStop" ./...`
Expected: FAIL (new functions/fields undefined).

- [ ] **Step 3: Implement Xray log bridge, protobuf `AllowInsecure`, real probe verification, and race-safe `Stop()`**

- In `core/xray_engine.go`:
  - Add `xrayLogBridge` implementing `xraylog.Handler` and `installXrayLogBridge()` forwarding `*xraylog.GeneralMessage` (`Severity <= Warning` as `"WARN"`/`"ERROR"` `"Xray: ..."`, `Info` as `"DEBUG"`). Invoke after `xraycore.New(pbConfig)` and `inst.Start()`.
  - Add `loadXrayProtobufConfig(rawJSON string, allowInsecure bool) (*xraycore.Config, error)` using `xraycore.LoadConfig("json", ...)`. When `allowInsecure` is `true`, iterate `pbConfig.Outbound` and unpack `StreamSettings.SecuritySettings` (`*internet_tls.Config` via `GetInstance()`), set `AllowInsecure = true`, and re-wrap with `serial.ToTypedMessage(...)`.
  - Add `socksAddr string` on `xrayEngine` and `(x *xrayEngine) Alive() bool` that dials TCP `x.socksAddr` (1s timeout, immediately closes) to confirm local SOCKS5 listener is actually alive.
- In `core/core.go`:
  - Update `TunnelConfig` with `XrayVerify *bool` (nil defaults to `true`), `XrayAllowInsecure bool`, `XrayProbeURL string`.
  - Add `startDone chan struct{}` closed by `defer close(startDone)` inside `Prepare()`. In `Stop()`, after cancelling `activeStartCancel`, copy `done := activeStartDone` under `stateMu`, release `stateMu`, and wait up to `3 * time.Second` on `<-done` before returning so callers can immediately reconnect without `"tunnel is already running"`.
  - In `Prepare()` for `case "xray"`:
    - Check if `XrayVerify == nil || *XrayVerify`.
    - Run `verifyXraySocks(ctx, listenHost, cfg.SocksPort, probeURL)`: test SOCKS5 HTTP `HEAD` (`http://connectivitycheck.gstatic.com/generate_204`, 5s timeout) and TCP dial check; if Mux was enabled in `xrayJSON` and probe fails, log `"Xray dengan Mux gagal, mencoba ulang tanpa Mux..."`, rebuild JSON stripping `"mux"`, restart `xrayEngine`, and **re-verify** via `verifyXraySocks(...)`. If still failing, stop the engine and return a clear `fmt.Errorf("verifikasi koneksi Xray gagal: %w", verr)`.
    - Wait: keep `stripXrayMux(rawJSON string) (hadMux bool, stripped string, err error)` in `core/xray_engine.go` so Go handles Mux fallback cleanly inside `Prepare()`!
  - Update `IsRunning()` & `GetStats()` to check `xrayEng.Alive()` when `xrayEng != nil`.

- [ ] **Step 4: Run Go tests with race detector**

Run: `cd core && go test -race ./...`
Expected: PASS across all packages cleanly.

- [ ] **Step 5: Commit**

```bash
git add core/xray_engine.go core/core.go core/core_test.go
git commit -m "fix(core): verify Xray connectivity, bridge internal Xray logs, patch protobuf allowInsecure, and synchronize Stop/Prepare"
```

---

### Task 2: Universal HTTP Payload Injector, SSH-WS Raw 101 Fallback, UDPGW & Socket Deadlines

**Files:**
- Modify: `core/payload.go`
- Modify: `core/ssh_engine.go`
- Modify: `core/http_proxy.go`
- Modify: `core/xray_payload_bridge.go`
- Create: `core/payload_test.go`

**Interfaces:**
- Consumes: `dialProtectedTCP` (`core/dialer.go`).
- Produces:
  - Updated `SSHConfig` JSON fields (additive): `wsRawUpgrade bool`, `udpgwPort int`, `slowDnsMode string` (consumed in Task 3).
  - `expandPayload(template, targetHost string, targetPort int, sni string) []string` preserving `[host]` = `targetHost` and `[sni]` = `sni` (fallback `targetHost`).
  - `parseHTTPStatusCode(statusLine string) (int, bool)` accepting `HTTP/1.x 200` and `HTTP/1.x 101` with or without reason phrase.

- [ ] **Step 1: Write failing tests in `core/payload_test.go`**

Cover:
1. `TestExpandPayloadPlaceholders`: verifies `[host]` resolves to `targetHost` (not overwritten by `sni`), `[sni]` resolves to `sni`, `[port]`, `[host_port]`, `[lfcr]`, `[netData]`, `[realData]`, `[rotate=a;b]` work as expected, and `[split]`/`[delay_split]`/`[splitDelay]`/`[instant_split]` split cleanly.
2. `TestInjectHTTPPayloadAcceptsStatusWithoutReasonPhrase`: spins up local pipe server returning `"HTTP/1.1 101\r\n\r\n"` and `"HTTP/1.1 200\r\n\r\n"`, verifies both succeed, while `"HTTP/1.1 403 Forbidden\r\n\r\n"` returns an error.
3. `TestSSHRawWSUpgradeFallback`: spins up a mock HTTP 101 raw-TCP SSH-WS server (no `Sec-WebSocket-Accept` header, just `"HTTP/1.1 101 Switching Protocols\r\n\r\n"` followed by echoed SSH bytes) and verifies `dialSSHWS` connects cleanly and passes bidirectional bytes when `WSRawUpgrade` is enabled or via auto-fallback.
4. `TestHTTPProxySocksDeadline`: verifies `dialViaSocks5Addr` fails fast on an unresponsive dummy SOCKS listener instead of hanging forever.

- [ ] **Step 2: Run new tests to verify failure**

Run: `cd core && go test -race -v -run "TestExpandPayload|TestInjectHTTPPayload|TestSSHRawWS|TestHTTPProxy" ./...`
Expected: FAIL.

- [ ] **Step 3: Implement fixes in `payload.go`, `ssh_engine.go`, `http_proxy.go`, and `xray_payload_bridge.go`**

- In `core/payload.go`:
  - Fix `expandPayload`: keep `[host]` as `targetHost`, add `[sni]` (`sni` or fallback `targetHost`), `[port]` (`strconv.Itoa(targetPort)`), `[lfcr]` (`"\n\r"`), `[netData]` / `[realData]` (same as `[raw]`), `[instant_split]` / `[splitDelay]` handling, and `[rotate=opt1;opt2]` round-robin atomic counter.
  - Fix `injectHTTPPayload` status validation to parse HTTP status code `200` or `101` cleanly regardless of trailing space/reason text (`strings.Fields(firstLine)`).
- In `core/ssh_engine.go`:
  - Add `WSRawUpgrade bool`, `UDPGWPort int`, `SlowDNSMode string` to `SSHConfig`.
  - Extract `dialSSHWebSocket(ctx, cfg, protector, scheme, rawMode)` and update `case "ws", "wss"`: if `cfg.WSRawUpgrade` is `true`, do direct raw HTTP 101 Upgrade (`GET path HTTP/1.1` + `Host` + `Upgrade: websocket` + `Connection: Upgrade` + custom payload headers); if `false`, attempt RFC 6455 `gorilla/websocket` first and if handshake fails with missing `Sec-WebSocket-Accept` / bad upgrade response, automatically fall back to raw HTTP 101 Upgrade before failing.
  - Extract UDPGW relay helper if `cfg.UDPGWPort > 0`: bind `handleSocks5UDPAssociate` to `listenHost` (`e.listenHost`) instead of hardcoded `127.0.0.1` so `AllowLan` hotspot clients can resolve DNS/UDP; route port 53 via `resolveDNSOverSSH`, and when `e.cfg.UDPGWPort > 0`, implement minimal `badvpn-udpgw` TCP stream multiplexing (2-byte len, 1-byte flags, 2-byte connid, IPv4/port header + UDP payload) to `127.0.0.1:<udpgwPort>` over `e.client.Dial("tcp", ...)` for general UDP packets.
- In `core/http_proxy.go`:
  - Set `10s` handshake deadline during `dialViaSocks5Addr` (`SetDeadline` then reset `SetDeadline(time.Time{})` before returning `conn`).
- In `core/xray_payload_bridge.go`:
  - Add guard/warning log if custom payload starts with `Upgrade: websocket` while outbound is already WS, so users get an explicit log line explaining potential double-upgrade conflicts.

- [ ] **Step 4: Run Go test suite**

Run: `cd core && go test -race ./...`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/payload.go core/ssh_engine.go core/http_proxy.go core/xray_payload_bridge.go core/payload_test.go
git commit -m "feat(core): universal payload tags, raw 101 SSH-WS fallback, optional UDPGW relay, and SOCKS5 handshake deadlines"
```

---

### Task 3: Universal SlowDNS — Fast Parallel `dnstt` Fallback & Universal DNS Scanner

**Files:**
- Modify: `core/slowdns.go`
- Modify: `core/slowdns_test.go`

**Interfaces:**
- Consumes: `SSHConfig.SlowDNSMode` (`"auto"`, `"vexdns"`, `"dnstt"`).
- Produces:
  - Updated `ScanSlowDNS` returning `"mode": "vexdns" | "dnstt"` in each candidate JSON object so standard `dnstt-server` deployments produce usable scan results in the UI.
  - Parallel multi-resolver `dialSlowDNSLegacy` trying all normalized resolvers concurrently with `TXT` first (~1-3s instead of hanging on `resolvers[0]`).

- [ ] **Step 1: Write failing SlowDNS tests in `core/slowdns_test.go`**

1. `TestDialSlowDNSModeDnsttParallelResolvers`: sets `SlowDNSMode: "dnstt"` with `DNSServer` containing `"127.0.0.1:<deadPort>,<liveServerAddr>"` (first resolver dead, second live standard `dnstt`-compat server), asserts `dialSlowDNS` connects rapidly via the working second resolver instead of hanging on `resolvers[0]`.
2. `TestScanSlowDNSDnsttCompat`: scans a standard `dnstt`-compatible listener (rejecting VexDNS probe packets / answering only standard `TXT` queries) and asserts `ScanSlowDNS` returns `ok: true` with candidate `mode: "dnstt"`.

- [ ] **Step 2: Run SlowDNS tests to verify failure**

Run: `cd core && go test -race -v -run "TestDialSlowDNSModeDnstt|TestScanSlowDNSDnstt" ./...`
Expected: FAIL.

- [ ] **Step 3: Implement universal SlowDNS resolver parallelism and `dnstt` scan in `core/slowdns.go`**

- Respect `cfg.SlowDNSMode`:
  - `"dnstt"`: skip VexDNS solver directly to parallel `dialSlowDNSLegacy`.
  - `"vexdns"`: VexDNS solver only.
  - `""` / `"auto"`: try VexDNS solver (shorter 4.5s probe window when auto, or cached route), then fall back to fast parallel `dialSlowDNSLegacy`.
- In `dialSlowDNSLegacy`: probe candidate `(resolver, qtype)` pairs across ALL normalized `resolvers` (trying `TXT` across all resolvers concurrently first when `AUTO` or `TXT`, with a 4s per-attempt timeout) and return the first established Noise+smux stream, closing losers cleanly via context cancellation.
- In `ScanSlowDNS`: run `solver.ProbeAll`; if 0 VexDNS candidates respond (or always as fallback when `cfg.SlowDNSMode != "vexdns"`), run lightweight parallel standard `dnstt` Noise handshakes across `resolvers` (using `TXT` and any explicit `DNSType`) and include `{resolver, qtype, mtu, mode: "dnstt", rttMs}` in `Candidates`.

- [ ] **Step 4: Run Go test suite**

Run: `cd core && go test -race ./...`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/slowdns.go core/slowdns_test.go
git commit -m "feat(slowdns): support standard dnstt mode, multi-resolver parallel fallback, and universal dnstt scanner"
```

---

### Task 4: Typed Protocol Model, Universal `LinkParser`/`LinkExporter`, `XrayConfigBuilder` & Kotlin Unit Tests

**Files:**
- Modify: `app/src/main/kotlin/com/vexotun/app/model/TunnelModels.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/model/LinkParser.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/model/LinkExporter.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/model/XrayConfigBuilder.kt`
- Create: `app/src/test/kotlin/com/vexotun/app/model/LinkParserTest.kt`
- Create: `app/src/test/kotlin/com/vexotun/app/model/XrayConfigBuilderTest.kt`
- Create: `app/src/test/kotlin/com/vexotun/app/model/VxtCodecTest.kt`

**Interfaces:**
- Produces:
  - `XraySecurityMode` enum (`NONE`, `TLS`, `REALITY`) + `XrayProfile` additive fields (`securityMode: XraySecurityMode? = null`, `fingerprint: String = ""`, `alpn: String = ""`, `verifyPeerCertByName: String = ""`, `pinnedPeerCertSha256: String = ""`, `realityPublicKey: String = ""`, `realityShortId: String = ""`, `realitySpiderX: String = "/"`, `headerType: String = ""`, `grpcMultiMode: Boolean = false`, `xhttpMode: String = ""`, `muxEnabled: Boolean = false`, plus `fun effectiveSecurityMode(): XraySecurityMode` resolving `securityMode ?: if (tls) TLS else NONE`).
  - `SshProfile` additive fields: `wsRawUpgrade: Boolean = false`, `udpgwPort: Int = 0`, `slowDnsMode: String = "auto"`.
  - `VexotunConfig` additive fields: `verifyXrayConnection: Boolean = true`, `allowIpv6Routing: Boolean = false`.
  - `CoreSshPayload` additive fields: `wsRawUpgrade`, `udpgwPort`, `slowDnsMode`; `CoreTunnelConfig` additive fields: `xrayVerify: Boolean = true`, `xrayAllowInsecure: Boolean = false`.
  - `XrayConfigBuilder.buildJson(profile, dnsServer, bypassLan, muxEnabled, useDohDns)`: emits `tlsSettings` (with optional `verifyPeerCertByName`, `pinnedPeerCertSha256`, `fingerprint`, `alpn`), `realitySettings` (`show: false`, `fingerprint`, `serverName`, `publicKey`, `shortId`, `spiderX`), `tcpSettings` (HTTP header obfs when `headerType == "http"`), `grpcSettings` (`serviceName`, `multiMode`, `authority`), `xhttpSettings` (`path`, `host`, `mode`), and skips `mux` whenever `protocol == SHADOWSOCKS || flow.isNotBlank() || network == GRPC || network == XHTTP`.

- [ ] **Step 1: Write unit tests first (`LinkParserTest.kt`, `XrayConfigBuilderTest.kt`)**

Include tests pinning Review Focus items #1, #2, and all Xray compatibility requirements:
- `parseVlessRealityLinkWithSpacesAndEmojiInRemark`: verifies `vless://uuid@1.2.3.4:443?security=reality&sni=google.com&fp=chrome&pbk=abc...&sid=1234&spx=%2F#SG 01 [VIP] 🚀` extracts remark `"SG 01 [VIP] 🚀"`, `REALITY`, `pbk`, `sid`, `spx`.
- `parseTrojanWithoutSecurityParamDefaultsToTls`: verifies `trojan://pass@sg.example.com:443#MyTrojan` sets TLS enabled.
- `parseShadowsocksIpv6AndSip002`: verifies `ss://method:pass@[2001:db8::1]:8388#SS6` preserves IPv6 host & port `8388`.
- `exportAndReimportRoundTrip`: verifies VMess, VLESS Reality, Trojan TLS, Shadowsocks round-trip cleanly.
- `buildJsonRealityAndVcnPcs`: verifies `XrayConfigBuilder.buildJson` generates valid `realitySettings`, `tlsSettings.verifyPeerCertByName`, `tlsSettings.pinnedPeerCertSha256`, omits `"allowInsecure"` from JSON (protobuf handles legacy fallback), sets `tcpSettings.header.type = "http"`, `grpcSettings.multiMode`, `xhttpSettings.mode`, and never injects `mux` for Shadowsocks.

- [ ] **Step 2: Update `TunnelModels.kt`, `LinkParser.kt`, `LinkExporter.kt`, and `XrayConfigBuilder.kt`**

- Ensure `LinkParser.parseShareLinks` splits lines / extracts schemes without truncating `#remark` at spaces (regex matching `(vmess|vless|trojan|ss)://[^\r\n]+` per line or up to next scheme delimiter), and parses `scheme://userinfo@host:port?query#fragment` manually instead of strict `java.net.URI` so unencoded `[ ]`, spaces, and emoji in remarks never throw.
- Ensure `TunnelProfile.summary()` reflects `Reality` badge/tag when `effectiveSecurityMode() == XraySecurityMode.REALITY`.
- Ensure `VexotunConfig` legacy deserialization still works when `securityMode`, `verifyXrayConnection`, `allowIpv6Routing` are absent from old JSON.

- [ ] **Step 3: Fix Android Unit Test source (`MainScreenViewModelTest.kt`)**

Update `MainScreenViewModelTest.kt` (or replace broken template assertions) to test `VexotunConfig` backward compatibility and `LinkParser` / `XrayConfigBuilder` without referencing removed template classes (`DataRepository`, `MainScreenUiState`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/vexotun/app/model/ app/src/test/
git commit -m "feat(model): support VLESS Reality, Trojan default TLS, TCP HTTP, vcn/pcs, resilient LinkParser, and unit tests"
```

---

### Task 5: Security, Service Lifecycle, Host/SNI Checker, External `.vxt` PIN Flow & CI Test Gate

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`
- Modify: `app/proguard-rules.pro`
- Modify: `app/src/main/kotlin/com/vexotun/app/data/NetworkTools.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/vpn/TunnelState.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/vpn/VexotunVpnService.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/MainActivity.kt`
- Modify: `.github/workflows/build-apk.yml`

**Interfaces:**
- Produces:
  - `HostCheckResult` expanded with `tlsVerified: Boolean = false` and clean `Vexotun/1.0` User-Agent.
  - `VexotunVpnService`: validates profile fields before `Core.prepare()`, cancels & joins previous `startJob` on reconnect, checks `config.allowIpv6Routing` before adding `fd00:1::2`/`::/0` routes, validates `dnsServer` is a literal IP (falling back to `8.8.8.8` for Android `Builder.addDnsServer` while passing full DNS config to Xray), passes `xrayVerify = config.verifyXrayConnection`, `xrayAllowInsecure = profile.xray.allowInsecure`, `wsRawUpgrade`, `udpgwPort`, `slowDnsMode`, and stops restarting Mux from Kotlin (Go owns it).
  - `MainActivity`: passes `pendingExternalPinUri` or delegates external `.vxt` URI PIN prompt cleanly into `MainScreen`.

- [ ] **Step 1: Harden Manifest, backup XML, and ProGuard**

- `AndroidManifest.xml`: set `android:allowBackup="false"`.
- `backup_rules.xml` & `data_extraction_rules.xml`: explicitly `<exclude domain="file" path="vexotun_config.json"/>` and `logs/`.
- `proguard-rules.pro`: add keep rules for `com.vexotun.app.ui.main.**$$serializer` and `com.vexotun.app.**` `@kotlinx.serialization.Serializable` classes so R8 minification never strips `SlowDnsScanCandidateUi` / `SlowDnsScanResponseUi`.

- [ ] **Step 2: Fix `NetworkTools.checkBugHost`, `TunnelState`, `VexotunVpnService`, and `MainActivity`**

- In `NetworkTools.kt`: replace `Vexotun/1.7` with `Vexotun/1.0`; in `checkBugHost()`, measure strict verification separately (`tlsVerified`) using a custom `SSLContext` for TLS inspection so self-signed or SNI-mismatched CDN/bug hosts complete the handshake and show their certificate `Subject`, `TLS protocol`, and `HTTP` response headers instead of throwing immediately.
- In `TunnelState.kt`: expose `private val _pendingPinUri = MutableStateFlow<Uri?>(null)` (`pendingExternalPinUri: StateFlow<Uri?>`) or handle directly in `MainActivity` -> `MainScreenViewModel`, and surface connection stage updates (`statusMessage`) cleanly. Keep `DEBUG` file logging while ensuring important Xray `WARN`/`ERROR`/`INFO` lines always show in UI Log.
- In `VexotunVpnService.kt`:
  - Cancel & `join()` active `startJob` inside `reconnectTunnel()` before starting a new tunnel.
  - Validate profile (`validateProfileForStart(profile)` — checks non-empty address/host, valid port 1..65535, non-empty UUID/password for Xray, NS/PubKey for SlowDNS) returning user-friendly Indonesian errors immediately.
  - Remove the broken Kotlin `if (canUseMux) { Core.pingSocksHTTP ... Core.stop() ... Core.prepare() }` block (now handled properly inside Go `Prepare()` with full retry + failure propagation).
  - Only call `builder.addAddress("fd00:1::2", 64).addRoute("::", 0)` when `config.allowIpv6Routing` is `true`.
  - Guard `builder.addDnsServer(...)` with `InetAddresses`/regex IP validation so custom hostnames in DNS never crash `VpnService.Builder`.
- In `MainActivity.kt`: if `importVxtUri` fails with `VxtPinRequiredException`, pass the `Uri` to `viewModel.requestPinForUri(uri)` so `MainScreen` immediately opens `VxtPinPromptDialog`.

- [ ] **Step 3: Update `.github/workflows/build-apk.yml` CI gate**

Add `go test -race ./...` (before `gomobile bind`) and `./gradlew testDebugUnitTest --no-daemon` (before `assembleRelease`) so CI blocks broken tests on every push.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/ app/proguard-rules.pro app/src/main/kotlin/com/vexotun/app/data/NetworkTools.kt app/src/main/kotlin/com/vexotun/app/vpn/ app/src/main/kotlin/com/vexotun/app/MainActivity.kt .github/workflows/build-apk.yml
git commit -m "fix(security,vpn,ci): disable unsafe backup, fix ProGuard rules, real Xray lifecycle in VpnService, SNI checker, external PIN prompt, and CI test gate"
```

---

### Task 6: UI/UX Overhaul ("Matte Network Instrument") & Dead Code Cleanup

**Files:**
- Modify: `app/src/main/kotlin/com/vexotun/app/ui/theme/Color.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/ui/theme/Theme.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/ui/theme/Type.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/ui/main/MainScreenViewModel.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/ui/main/MainScreen.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/ui/main/TunnelPage.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/ui/main/ConfigListPage.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/ui/main/ConfigEditorPage.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/ui/main/ConfigDialogs.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/ui/main/SettingsPages.kt`
- Modify: `app/src/main/kotlin/com/vexotun/app/ui/main/ToolsPage.kt`
- Modify: `app/src/androidTest/kotlin/com/vexotun/app/ui/main/MainScreenTest.kt`

**Interfaces:**
- Consumes: updated `TunnelModels`, `MainScreenViewModel`, `TunnelState`.
- Produces:
  - WCAG AA verified color tokens in `Theme.kt` / `Color.kt`.
  - Clean 4-tab navigation (`Tunnel`, `Config`, `Tools`, `Setelan` with nested `Filter Aplikasi` sub-page) with zero hidden drawer hamburger.
  - Live telemetry strip (`Unduh`, `Unggah`, `Ping`), uptime counter while `CONNECTED`, full Xray/SSH editor sections, and clean non-duplicated imports in every file.

- [ ] **Step 1: Verify exact Matte Network Instrument contrast tokens via `contrast-check.py` and rewrite `Color.kt`, `Theme.kt`, `Type.kt`**

Target tokens to verify with `contrast-check.py` (`>= 4.5:1` text, `>= 3.0:1` UI borders/components) using **Signal Amber + Graphite (Industrial Instrument)**:
- **Dark:** `background = #111214`, `surface = #111214`, `surfaceVariant = #191B1F`, `onSurface = #F3F4F6`, `onSurfaceVariant = #9CA3AF`, `primary = #F59E0B`, `onPrimary = #111214`, `primaryContainer = #3A2706`, `onPrimaryContainer = #FDE68A`, `outline = #636B78` (3.21:1 >= 3:1 on `#191B1F`), `outlineVariant = #282C34` (decorative hairline divider), `error = #F87171`, `onError = #2D0707`.
- **Light:** `background = #F7F6F2`, `surface = #F7F6F2`, `surfaceVariant = #FFFFFF`, `onSurface = #111214`, `onSurfaceVariant = #4B505A`, `primary = #9A4A06`, `onPrimary = #FFFFFF`, `primaryContainer = #FEF3C7`, `onPrimaryContainer = #451A03`, `outline = #7B818C` (3.92:1 >= 3:1 on `#FFFFFF`), `outlineVariant = #E2DFD7` (decorative hairline), `error = #B91C1C`, `onError = #FFFFFF`.
- Delete all 240 lines of unused Material Theme Builder boilerplate from `Color.kt`. Define tabular `FontFamily.Monospace` text styles in `Type.kt`.

- [ ] **Step 2: Move clipboard parsing, SlowDNS scan helper, and external PIN URI state into `MainScreenViewModel.kt`**

- Add `pendingPinUri: StateFlow<Uri?>`, `requestPinForUri(Uri)`, `clearPendingPinUri()`, `scanSlowDns(SshProfile)`, and `applySshClipboardPaste(profileId, clipText)` to `MainScreenViewModel` so Composables remain pure UI.
- Update `runBatchPingCheck()`: mark Xray TCP reachability cleanly or test via SOCKS if connected; label clearly as TCP check when disconnected.

- [ ] **Step 3: Implement Matte Network Instrument UI across `MainScreen.kt`, `TunnelPage.kt`, `ConfigListPage.kt`, `ConfigEditorPage.kt`, `ConfigDialogs.kt`, `SettingsPages.kt`, `ToolsPage.kt`**

- Strip all unused/duplicated imports from every file.
- `MainScreen.kt`: 4-tab `NavigationBar` (`Tunnel`, `Config`, `Tools`, `Setelan`), nested `APPS` sub-page reachable from `Setelan`, TopAppBar with single-source status dot + back button on sub-pages, observe `pendingPinUri` from `viewModel` for external `.vxt` intents.
- `TunnelPage.kt`: 3 flat hairline-separated zones:
  1. Compact Config Selector + Connection Status & Uptime / Stage message + 48dp Connect/Disconnect CTA (8dp radius).
  2. 3-column Tabular Telemetry Strip (`↓ Speed / Total`, `↑ Speed / Total`, `Ping ms` tappable to re-ping).
  3. Full-bleed monospace Log console (`Semua / Info / Error` underlined tabs + `Salin Debug` / `Salin` / `Bersihkan` buttons with Toast confirmation).
- `ConfigListPage.kt` & `ConfigDialogs.kt`: Flat hairline list with 3dp left accent bar on active profile, Batch Ping indicator, `ExportVxtDialog` allowing `hidePayload` on Xray custom-payload profiles too.
- `ConfigEditorPage.kt`: Collapsible grouped sections for Xray (`Server & Akun`, `Transport`, `Keamanan TLS / Reality`, `Payload & Mux`) including `flow`, `alterId`, `security`, `securityMode` (`None`/`TLS`/`Reality`), `fp`, `alpn`, `vcn`, `pcs`, `allowInsecure`, `headerType` (`none`/`http`), `grpcMultiMode`, `xhttpMode`, `muxEnabled` (off by default); and grouped SSH sections (`Direct`/`HTTP Proxy`/`SSL`/`WS`/`WSS`/`SlowDNS`, `wsRawUpgrade`, `udpgwPort`, `slowDnsMode` + Scan DNS).
- `SettingsPages.kt`: Add `Verifikasi koneksi Xray` switch (default ON), `Routing IPv6` switch (default OFF), `Filter Aplikasi` entry row showing mode/app count, `Auto-Reconnect`, `Bypass LAN`, `WakeLock`, `Optimasi Baterai`, `Hotspot HTTP/SOCKS5`, `DNS`, `MTU`.
- `ToolsPage.kt`: Featured `Host / Bug & SNI Checker` block (displaying `tlsVerified` + cert subject + headers) + flat expandable utility rows (`Cek IP & ISP`, `DNS Lookup`, `Tes Kecepatan`, `Baterai & Autostart`).
- Update `MainScreenTest.kt` to match actual `MainScreen` signature cleanly so `compileDebugAndroidTestKotlin` succeeds too.

- [ ] **Step 4: Run local Go tests + WCAG contrast verification script**

Run: `cd core && go test -race ./...` and run `python3 /home/jae/.gemini/config/skills/antislop-human/contrast-check.py` against all Dark & Light theme pairs.
Expected: All Go tests PASS, all UI text/interactive contrast ratios PASS WCAG AA.

- [ ] **Step 5: Push to GitHub and verify full CI (`go test -race`, `testDebugUnitTest`, `gomobile bind`, `assembleRelease`)**

Run:
```bash
git add app/ .github/workflows/build-apk.yml
git commit -m "feat(ui): Matte Network Instrument overhaul, 4-tab nav, live telemetry, universal Xray/SSH editor, and WCAG AA theme"
git push origin main
gh run watch --exit-status
```
Expected: GitHub Actions run passes completely (Go tests, Kotlin unit tests, `tunelcore.aar` build, and signed release APKs).

---

## Self-Review Check

- **Spec coverage:** Every finding in `vexotun-full-audit-and-redesign.md` (Section 1 root causes 1–4, Section 2 X1–X9 & S1–S5, Section 3 R1–R7, Section 4 Deepening #1–#4, Section 5 WCAG AA contrast, telemetry, 4-tab nav, Matte Network Instrument UI) maps directly to Tasks 1–6.
- **Type & Interface consistency:** All additive JSON fields (`xrayVerify`, `xrayAllowInsecure`, `wsRawUpgrade`, `udpgwPort`, `slowDnsMode`, `securityMode`, `verifyPeerCertByName`, `pinnedPeerCertSha256`, `realityPublicKey`, `realityShortId`, `realitySpiderX`, `headerType`, `grpcMultiMode`, `xhttpMode`) match exact names across `TunnelModels.kt`, `VexotunVpnService.kt`, `core.go`, `xray_engine.go`, `ssh_engine.go`, and `slowdns.go`.
- **Review Focus coverage:** All 5 failure modes have explicit unit/Go tests pinned in Tasks 1, 2, 4, and 5.
