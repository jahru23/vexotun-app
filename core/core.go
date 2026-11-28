package core

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"runtime"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"
)

type TunnelConfig struct {
	Mode              string            `json:"mode"` // "ssh" or "xray"
	MTU               int               `json:"mtu"`
	SocksPort         int               `json:"socksPort"`
	HTTPPort          int               `json:"httpPort"`
	AllowLan          bool              `json:"allowLan"`
	SSH               SSHConfig         `json:"ssh"`
	XrayConfigJSON    string            `json:"xrayConfigJson"`
	XrayPayload       XrayPayloadConfig `json:"xrayPayload"`
	XrayVerify        *bool             `json:"xrayVerify,omitempty"`
	XrayAllowInsecure bool              `json:"xrayAllowInsecure,omitempty"`
	XrayProbeURL      string            `json:"xrayProbeUrl,omitempty"`
}

type TunnelStats struct {
	Running   bool  `json:"running"`
	BytesUp   int64 `json:"bytesUp"`
	BytesDown int64 `json:"bytesDown"`
}

var (
	stateMu           sync.Mutex
	isRunning         bool
	isStarting        bool
	stopSeq           uint64
	activeStartCancel context.CancelFunc
	activeStartDone   chan struct{}
	activeSSH         *sshEngine
	activeXray        *xrayEngine
	activeXrayBridge  *xrayPayloadBridge
	activeHTTPProxy   *localHTTPProxy
	activeCfg         TunnelConfig
	activeSocks       int
)

// Prepare initializes the tunnel engine (SSH/SlowDNS or Xray) and local SOCKS5/HTTP proxies
// BEFORE the Android VPN TUN interface is established, so dial traffic never loops into tun0.
func Prepare(configJSON string, protector SocketProtector, logger LogHandler) error {
	stateMu.Lock()
	if isRunning || isStarting {
		stateMu.Unlock()
		return fmt.Errorf("tunnel is already running")
	}
	isStarting = true
	mySeq := stopSeq
	startCtx, cancel := context.WithCancel(context.Background())
	startDone := make(chan struct{})
	activeStartCancel = cancel
	activeStartDone = startDone
	stateMu.Unlock()

	var finishOnce sync.Once
	finishStart := func() {
		finishOnce.Do(func() {
			cancel()
			stateMu.Lock()
			isStarting = false
			if stopSeq == mySeq {
				activeStartCancel = nil
				activeStartDone = nil
			}
			stateMu.Unlock()
			close(startDone)
		})
	}
	defer finishStart()

	setLogHandler(logger)
	resetTraffic()

	var cfg TunnelConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return fmt.Errorf("parse tunnel config: %w", err)
	}
	switch cfg.Mode {
	case "ssh":
		debug.SetMemoryLimit(40 << 20)
		debug.SetGCPercent(50)
	default:
		debug.SetMemoryLimit(56 << 20)
		debug.SetGCPercent(50)
	}
	if cfg.MTU <= 0 {
		cfg.MTU = 1500
	}
	if cfg.SocksPort <= 0 {
		cfg.SocksPort = 10808
	}
	if cfg.HTTPPort <= 0 {
		cfg.HTTPPort = 10809
	}

	listenHost := "127.0.0.1"
	if cfg.AllowLan {
		listenHost = "0.0.0.0"
	}

	logMsg("DEBUG", "Starting Vexotun core (mode=%s, mtu=%d, lan=%v)", cfg.Mode, cfg.MTU, cfg.AllowLan)

	var newSSH *sshEngine
	var newXray *xrayEngine
	var newBridge *xrayPayloadBridge
	var newHTTPProxy *localHTTPProxy

	switch cfg.Mode {
	case "ssh":
		sshEng, err := startSSHEngine(startCtx, cfg.SSH, listenHost, cfg.SocksPort, protector)
		if err != nil {
			return err
		}
		newSSH = sshEng

	case "xray":
		xrayJSON := cfg.XrayConfigJSON
		if cfg.XrayPayload.Enabled {
			if err := validateXrayPayloadCompatibility(xrayJSON, cfg.XrayPayload); err != nil {
				return err
			}
			bridge, err := startXrayPayloadBridge(startCtx, cfg.XrayPayload, protector)
			if err != nil {
				return err
			}
			rewritten, err := rewriteXrayOutboundEndpoint(xrayJSON, bridge.port)
			if err != nil {
				bridge.Stop()
				return err
			}
			newBridge = bridge
			xrayJSON = rewritten
		}
		xrayEng, err := startXrayEngineWithOptions(xrayJSON, listenHost, cfg.SocksPort, cfg.XrayAllowInsecure, protector)
		if err != nil {
			if newBridge != nil {
				newBridge.Stop()
			}
			return err
		}
		newXray = xrayEng

		shouldVerify := cfg.XrayVerify == nil || *cfg.XrayVerify
		if shouldVerify {
			probeURL := strings.TrimSpace(cfg.XrayProbeURL)
			if probeURL == "" {
				probeURL = "http://connectivitycheck.gstatic.com/generate_204"
			}
			logMsg("INFO", "Memverifikasi koneksi Xray ke server...")
			probeMs, verr := verifyXraySocks(startCtx, cfg.SocksPort, probeURL, 5500*time.Millisecond)
			if verr != nil {
				if startCtx.Err() != nil {
					newXray.Stop()
					if newBridge != nil {
						newBridge.Stop()
					}
					return context.Canceled
				}
				hadMux, strippedJSON, serr := stripXrayMux(xrayJSON)
				if serr == nil && hadMux {
					logMsg("WARN", "Xray dengan Mux gagal (%v), mencoba ulang tanpa Mux...", verr)
					newXray.Stop()
					retryEng, rerr := startXrayEngineWithOptions(strippedJSON, listenHost, cfg.SocksPort, cfg.XrayAllowInsecure, protector)
					if rerr != nil {
						if newBridge != nil {
							newBridge.Stop()
						}
						return rerr
					}
					newXray = retryEng
					probeMs, verr = verifyXraySocks(startCtx, cfg.SocksPort, probeURL, 5500*time.Millisecond)
					if verr == nil {
						logMsg("INFO", "Xray terhubung tanpa Mux (%d ms)", probeMs)
					}
				}
			} else {
				logMsg("INFO", "Verifikasi Xray berhasil (%d ms)", probeMs)
			}

			if verr != nil {
				newXray.Stop()
				if newBridge != nil {
					newBridge.Stop()
				}
				return fmt.Errorf("verifikasi koneksi Xray gagal: %w", verr)
			}
		}

	default:
		return fmt.Errorf("unsupported tunnel mode: %s", cfg.Mode)
	}

	stateMu.Lock()
	aborted := stopSeq != mySeq || startCtx.Err() != nil
	if aborted {
		stateMu.Unlock()
		if newSSH != nil {
			newSSH.Stop()
		}
		if newXray != nil {
			newXray.Stop()
		}
		if newBridge != nil {
			newBridge.Stop()
		}
		return context.Canceled
	}

	if cfg.AllowLan {
		httpProxy, err := startLocalHTTPProxy(listenHost, cfg.HTTPPort, cfg.SocksPort)
		if err != nil {
			logMsg("WARN", "Failed to start Hotspot HTTP proxy: %v", err)
		} else {
			newHTTPProxy = httpProxy
		}
	}

	activeSSH = newSSH
	activeXray = newXray
	activeXrayBridge = newBridge
	activeHTTPProxy = newHTTPProxy
	activeCfg = cfg
	activeSocks = cfg.SocksPort
	isRunning = true
	stateMu.Unlock()

	return nil
}

// TrimMemory triggers garbage collection and returns unused memory pages to the OS.
func TrimMemory() {
	runtime.GC()
	debug.FreeOSMemory()
}

// Stop shuts down the active tunnel core, aborting any in-progress Prepare immediately and waiting for it to settle.
func Stop() {
	stateMu.Lock()
	stopSeq++
	if activeStartCancel != nil {
		activeStartCancel()
		activeStartCancel = nil
	}
	waitStartDone := activeStartDone
	activeStartDone = nil
	wasRunning := isRunning
	isRunning = false
	activeSocks = 0
	sshEng := activeSSH
	activeSSH = nil
	xrayEng := activeXray
	activeXray = nil
	bridge := activeXrayBridge
	activeXrayBridge = nil
	httpProxy := activeHTTPProxy
	activeHTTPProxy = nil
	stateMu.Unlock()

	if waitStartDone != nil {
		select {
		case <-waitStartDone:
		case <-time.After(3 * time.Second):
		}
		// Pick up any engines committed right before cancellation took effect.
		stateMu.Lock()
		isRunning = false
		if activeSSH != nil {
			sshEng = activeSSH
			activeSSH = nil
		}
		if activeXray != nil {
			xrayEng = activeXray
			activeXray = nil
		}
		if activeXrayBridge != nil {
			bridge = activeXrayBridge
			activeXrayBridge = nil
		}
		if activeHTTPProxy != nil {
			httpProxy = activeHTTPProxy
			activeHTTPProxy = nil
		}
		stateMu.Unlock()
	}

	if !wasRunning && sshEng == nil && xrayEng == nil && bridge == nil && httpProxy == nil {
		return
	}

	logMsg("DEBUG", "Stopping Vexotun core...")
	if httpProxy != nil {
		httpProxy.Stop()
	}
	if sshEng != nil {
		sshEng.Stop()
	}
	if xrayEng != nil {
		xrayEng.Stop()
	}
	if bridge != nil {
		bridge.Stop()
	}
	TrimMemory()
	logMsg("DEBUG", "Vexotun core stopped")
}

// IsRunning returns whether the tunnel core is currently active.
func IsRunning() bool {
	stateMu.Lock()
	running := isRunning
	sshEng := activeSSH
	xrayEng := activeXray
	stateMu.Unlock()
	if running && sshEng != nil && !sshEng.Alive() {
		return false
	}
	if running && xrayEng != nil && !xrayEng.Alive() {
		return false
	}
	return running
}

// GetStats returns JSON-encoded traffic and state stats.
func GetStats() string {
	stateMu.Lock()
	running := isRunning
	sshEng := activeSSH
	xrayEng := activeXray
	stateMu.Unlock()
	if running && sshEng != nil && !sshEng.Alive() {
		running = false
	}
	if running && xrayEng != nil && !xrayEng.Alive() {
		running = false
	}

	b, _ := json.Marshal(TunnelStats{
		Running:   running,
		BytesUp:   bytesUp.Load(),
		BytesDown: bytesDown.Load(),
	})
	return string(b)
}

// PingTCP measures TCP handshake latency to host:port in milliseconds, or -1 on error.
func PingTCP(host string, port int32, timeoutMs int32) int32 {
	if timeoutMs <= 0 {
		timeoutMs = 3000
	}
	addr := net.JoinHostPort(host, strconv.Itoa(int(port)))
	start := time.Now()
	conn, err := net.DialTimeout("tcp", addr, time.Duration(timeoutMs)*time.Millisecond)
	if err != nil {
		return -1
	}
	_ = conn.Close()
	return int32(time.Since(start).Milliseconds())
}

// PingHTTP performs an HTTP GET/HEAD latency check (in ms) and returns -1 on error.
func PingHTTP(targetURL string, timeoutMs int32) int32 {
	if timeoutMs <= 0 {
		timeoutMs = 5000
	}
	client := &http.Client{
		Timeout: time.Duration(timeoutMs) * time.Millisecond,
	}
	start := time.Now()
	resp, err := client.Head(targetURL)
	if err != nil {
		return -1
	}
	_ = resp.Body.Close()
	return int32(time.Since(start).Milliseconds())
}

func newSocksHTTPClient(socksPort int32, timeoutMs int32) (*http.Client, func()) {
	if socksPort <= 0 {
		socksPort = 10808
	}
	if timeoutMs <= 0 {
		timeoutMs = 8000
	}
	socksAddr := fmt.Sprintf("127.0.0.1:%d", socksPort)
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return dialViaSocks5Addr(socksAddr, addr)
		},
		DisableKeepAlives: true,
	}
	client := &http.Client{
		Transport: transport,
		Timeout:   time.Duration(timeoutMs) * time.Millisecond,
	}
	return client, transport.CloseIdleConnections
}

// FetchSocksURL fetches a small HTTP/HTTPS response body through the local SOCKS5 proxy.
func FetchSocksURL(socksPort int32, targetURL string, timeoutMs int32) (string, error) {
	client, cleanup := newSocksHTTPClient(socksPort, timeoutMs)
	defer cleanup()

	resp, err := client.Get(targetURL)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 256*1024))
	if err != nil {
		return "", err
	}
	return string(body), nil
}

// SpeedTestSocks downloads up to 8 MiB through the local SOCKS5 proxy and returns JSON
// {"ok":true,"bytes":N,"ms":T,"mbps":M} or {"ok":false,"error":"..."}.
func SpeedTestSocks(socksPort int32, targetURL string, timeoutMs int32) string {
	if timeoutMs <= 0 {
		timeoutMs = 12000
	}
	client, cleanup := newSocksHTTPClient(socksPort, timeoutMs)
	defer cleanup()

	start := time.Now()
	resp, err := client.Get(targetURL)
	if err != nil {
		b, _ := json.Marshal(map[string]any{"ok": false, "error": err.Error()})
		return string(b)
	}
	defer resp.Body.Close()
	n, err := io.Copy(io.Discard, io.LimitReader(resp.Body, 8*1024*1024))
	elapsedMs := time.Since(start).Milliseconds()
	if err != nil && n == 0 {
		b, _ := json.Marshal(map[string]any{"ok": false, "error": err.Error()})
		return string(b)
	}
	if elapsedMs <= 0 {
		elapsedMs = 1
	}
	mbps := (float64(n) * 8.0) / (float64(elapsedMs) * 1000.0)
	b, _ := json.Marshal(map[string]any{
		"ok":    true,
		"bytes": n,
		"ms":    elapsedMs,
		"mbps":  mbps,
	})
	return string(b)
}

// PingSocksHTTP probes an HTTP URL through the local SOCKS5 proxy (127.0.0.1:<socksPort>)
// to verify tunnel connectivity (e.g. for Xray Mux auto-fallback). Returns latency in ms or -1.
func PingSocksHTTP(socksPort int32, targetURL string, timeoutMs int32) int32 {
	if timeoutMs <= 0 {
		timeoutMs = 3500
	}
	ms, err := verifyXraySocks(context.Background(), int(socksPort), targetURL, time.Duration(timeoutMs)*time.Millisecond)
	if err != nil {
		return -1
	}
	return int32(ms)
}

func verifyXraySocks(ctx context.Context, socksPort int, targetURL string, timeout time.Duration) (int64, error) {
	if socksPort <= 0 {
		socksPort = 10808
	}
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	socksAddr := fmt.Sprintf("127.0.0.1:%d", socksPort)
	probeCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	transport := &http.Transport{
		DialContext: func(dctx context.Context, network, addr string) (net.Conn, error) {
			type dialRes struct {
				c   net.Conn
				err error
			}
			ch := make(chan dialRes, 1)
			go func() {
				dialTimeout := timeout
				if dl, ok := dctx.Deadline(); ok {
					rem := time.Until(dl)
					if rem <= 0 {
						rem = 250 * time.Millisecond
					}
					if rem < dialTimeout {
						dialTimeout = rem
					}
				}
				c, derr := dialViaSocks5AddrWithTimeout(socksAddr, addr, dialTimeout)
				ch <- dialRes{c: c, err: derr}
			}()
			select {
			case <-dctx.Done():
				go func() {
					if r := <-ch; r.c != nil {
						_ = r.c.Close()
					}
				}()
				return nil, dctx.Err()
			case r := <-ch:
				return r.c, r.err
			}
		},
		DisableKeepAlives: true,
	}
	defer transport.CloseIdleConnections()

	client := &http.Client{
		Transport: transport,
		Timeout:   timeout,
	}
	req, err := http.NewRequestWithContext(probeCtx, http.MethodHead, targetURL, nil)
	if err != nil {
		return -1, err
	}
	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return -1, err
	}
	_ = resp.Body.Close()
	if resp.StatusCode >= 400 {
		return -1, fmt.Errorf("probe HTTP status %d", resp.StatusCode)
	}
	elapsed := time.Since(start).Milliseconds()
	if elapsed <= 0 {
		elapsed = 1
	}
	return elapsed, nil
}
