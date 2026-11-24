package core

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"runtime/debug"
	"strconv"
	"sync"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/engine"
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
	activeSocks       int
)

// Start initializes the selected tunnel core (SSH or Xray) and attaches tun2socks to tunFd.
func Start(tunFd int32, configJSON string, protector SocketProtector, logger LogHandler) error {
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

	debug.SetMemoryLimit(96 << 20)
	debug.SetGCPercent(60)

	setLogHandler(logger)
	resetTraffic()

	var cfg TunnelConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		cleanupStart()
		return fmt.Errorf("parse tunnel config: %w", err)
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

	logMsg("INFO", "Starting Vexotun core (mode=%s, mtu=%d, lan=%v)", cfg.Mode, cfg.MTU, cfg.AllowLan)

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

	key := &engine.Key{
		Device:   fmt.Sprintf("fd://%d", tunFd),
		Proxy:    fmt.Sprintf("socks5://127.0.0.1:%d", cfg.SocksPort),
		MTU:      cfg.MTU,
		LogLevel: "silent",
	}
	engine.Insert(key)
	engine.Start()

	activeSSH = newSSH
	activeXray = newXray
	activeXrayBridge = newBridge
	activeHTTPProxy = newHTTPProxy
	activeSocks = cfg.SocksPort
	isStarting = false
	isRunning = true
	activeStartCancel = nil
	stateMu.Unlock()

	logMsg("INFO", "tun2socks started on fd://%d -> socks5://127.0.0.1:%d", tunFd, cfg.SocksPort)
	return nil
}

// Stop shuts down tun2socks and the active tunnel core, aborting any in-progress Start immediately.
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

	logMsg("INFO", "Stopping Vexotun core...")
	if wasRunning {
		engine.Stop()
	}
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
	logMsg("INFO", "Vexotun core stopped")
}

// IsRunning returns whether the tunnel core is currently active.
func IsRunning() bool {
	stateMu.Lock()
	defer stateMu.Unlock()
	return isRunning
}

// GetStats returns JSON-encoded traffic and state stats.
func GetStats() string {
	stateMu.Lock()
	running := isRunning
	stateMu.Unlock()

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
