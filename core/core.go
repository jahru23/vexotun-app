package core

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"runtime"
	"runtime/debug"
	"strconv"
	"sync"
	"time"
)

type TunnelConfig struct {
	Mode           string            `json:"mode"` // "ssh" or "xray"
	MTU            int               `json:"mtu"`
	SocksPort      int               `json:"socksPort"`
	HTTPPort       int               `json:"httpPort"`
	AllowLan       bool              `json:"allowLan"`
	SSH            SSHConfig         `json:"ssh"`
	XrayConfigJSON string            `json:"xrayConfigJson"`
	XrayPayload    XrayPayloadConfig `json:"xrayPayload"`
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
	activeStartCancel = cancel
	stateMu.Unlock()

	cleanupStart := func() {
		cancel()
		stateMu.Lock()
		isStarting = false
		if stopSeq == mySeq {
			activeStartCancel = nil
		}
		stateMu.Unlock()
	}

	setLogHandler(logger)
	resetTraffic()

	var cfg TunnelConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		cleanupStart()
		return fmt.Errorf("parse tunnel config: %w", err)
	}
	switch cfg.Mode {
	case "ssh":
		debug.SetMemoryLimit(16 << 20)
		debug.SetGCPercent(25)
	default:
		debug.SetMemoryLimit(28 << 20)
		debug.SetGCPercent(30)
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
			cleanupStart()
			return err
		}
		newSSH = sshEng

	case "xray":
		xrayJSON := cfg.XrayConfigJSON
		if cfg.XrayPayload.Enabled {
			bridge, err := startXrayPayloadBridge(startCtx, cfg.XrayPayload, protector)
			if err != nil {
				cleanupStart()
				return err
			}
			rewritten, err := rewriteXrayOutboundEndpoint(xrayJSON, bridge.port)
			if err != nil {
				bridge.Stop()
				cleanupStart()
				return err
			}
			newBridge = bridge
			xrayJSON = rewritten
		}
		xrayEng, err := startXrayEngine(xrayJSON, listenHost, cfg.SocksPort, protector)
		if err != nil {
			if newBridge != nil {
				newBridge.Stop()
			}
			cleanupStart()
			return err
		}
		newXray = xrayEng

	default:
		cleanupStart()
		return fmt.Errorf("unsupported tunnel mode: %s", cfg.Mode)
	}

	stateMu.Lock()
	aborted := stopSeq != mySeq || startCtx.Err() != nil
	if aborted {
		isStarting = false
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
	isStarting = false
	activeStartCancel = nil
	stateMu.Unlock()

	return nil
}

// TrimMemory triggers garbage collection and returns unused memory pages to the OS.
func TrimMemory() {
	runtime.GC()
	debug.FreeOSMemory()
}

// Stop shuts down the active tunnel core, aborting any in-progress Prepare immediately.
func Stop() {
	stateMu.Lock()
	stopSeq++
	if activeStartCancel != nil {
		activeStartCancel()
		activeStartCancel = nil
	}
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
	stateMu.Unlock()
	if running && sshEng != nil && !sshEng.Alive() {
		return false
	}
	return running
}

// GetStats returns JSON-encoded traffic and state stats.
func GetStats() string {
	stateMu.Lock()
	running := isRunning
	sshEng := activeSSH
	stateMu.Unlock()
	if running && sshEng != nil && !sshEng.Alive() {
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

// PingSocksHTTP probes an HTTP URL through the local SOCKS5 proxy (127.0.0.1:<socksPort>)
// to verify tunnel connectivity (e.g. for Xray Mux auto-fallback). Returns latency in ms or -1.
func PingSocksHTTP(socksPort int32, targetURL string, timeoutMs int32) int32 {
	if socksPort <= 0 {
		socksPort = 10808
	}
	if timeoutMs <= 0 {
		timeoutMs = 3500
	}
	socksAddr := fmt.Sprintf("127.0.0.1:%d", socksPort)
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return dialViaSocks5Addr(socksAddr, addr)
		},
		DisableKeepAlives: true,
	}
	defer transport.CloseIdleConnections()

	client := &http.Client{
		Transport: transport,
		Timeout:   time.Duration(timeoutMs) * time.Millisecond,
	}
	start := time.Now()
	resp, err := client.Head(targetURL)
	if err != nil {
		return -1
	}
	_ = resp.Body.Close()
	return int32(time.Since(start).Milliseconds())
}
