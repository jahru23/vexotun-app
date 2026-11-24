package core

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/engine"
)

type TunnelConfig struct {
	Mode           string    `json:"mode"` // "ssh" or "xray"
	MTU            int       `json:"mtu"`
	SocksPort      int       `json:"socksPort"`
	SSH            SSHConfig `json:"ssh"`
	XrayConfigJSON string    `json:"xrayConfigJson"`
}

type TunnelStats struct {
	Running   bool  `json:"running"`
	BytesUp   int64 `json:"bytesUp"`
	BytesDown int64 `json:"bytesDown"`
}

var (
	tunnelMu    sync.Mutex
	isRunning   bool
	activeSSH   *sshEngine
	activeXray  *xrayEngine
	activeSocks int
)

// Start initializes the selected tunnel core (SSH or Xray) and attaches tun2socks to tunFd.
func Start(tunFd int32, configJSON string, protector SocketProtector, logger LogHandler) error {
	tunnelMu.Lock()
	defer tunnelMu.Unlock()

	if isRunning {
		return fmt.Errorf("tunnel is already running")
	}

	setLogHandler(logger)
	resetTraffic()

	var cfg TunnelConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return fmt.Errorf("parse tunnel config: %w", err)
	}
	if cfg.MTU <= 0 {
		cfg.MTU = 1500
	}
	if cfg.SocksPort <= 0 {
		cfg.SocksPort = 10808
	}

	logMsg("INFO", "Starting Vexotun core (mode=%s, mtu=%d)", cfg.Mode, cfg.MTU)

	switch cfg.Mode {
	case "ssh":
		sshEng, err := startSSHEngine(context.Background(), cfg.SSH, cfg.SocksPort, protector)
		if err != nil {
			return err
		}
		activeSSH = sshEng

	case "xray":
		xrayEng, err := startXrayEngine(cfg.XrayConfigJSON, cfg.SocksPort, protector)
		if err != nil {
			return err
		}
		activeXray = xrayEng

	default:
		return fmt.Errorf("unsupported tunnel mode: %s", cfg.Mode)
	}

	key := &engine.Key{
		Device:   fmt.Sprintf("fd://%d", tunFd),
		Proxy:    fmt.Sprintf("socks5://127.0.0.1:%d", cfg.SocksPort),
		MTU:      cfg.MTU,
		LogLevel: "silent",
	}
	engine.Insert(key)
	engine.Start()

	activeSocks = cfg.SocksPort
	isRunning = true
	logMsg("INFO", "tun2socks started on fd://%d -> socks5://127.0.0.1:%d", tunFd, cfg.SocksPort)
	return nil
}

// Stop shuts down tun2socks and the active tunnel core.
func Stop() {
	tunnelMu.Lock()
	defer tunnelMu.Unlock()

	if !isRunning {
		return
	}

	logMsg("INFO", "Stopping Vexotun core...")
	engine.Stop()

	if activeSSH != nil {
		activeSSH.Stop()
		activeSSH = nil
	}
	if activeXray != nil {
		activeXray.Stop()
		activeXray = nil
	}

	activeSocks = 0
	isRunning = false
	logMsg("INFO", "Vexotun core stopped")
}

// IsRunning returns whether the tunnel core is currently active.
func IsRunning() bool {
	tunnelMu.Lock()
	defer tunnelMu.Unlock()
	return isRunning
}

// GetStats returns JSON-encoded traffic and state stats.
func GetStats() string {
	tunnelMu.Lock()
	running := isRunning
	tunnelMu.Unlock()

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
