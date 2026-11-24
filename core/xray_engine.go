package core

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"syscall"

	xraycore "github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
	"github.com/xtls/xray-core/transport/internet"
)

var (
	xrayProtectMu sync.RWMutex
	xrayProtector SocketProtector
	xrayHookOnce  sync.Once
)

func registerXraySocketProtector(p SocketProtector) {
	xrayProtectMu.Lock()
	xrayProtector = p
	xrayProtectMu.Unlock()

	xrayHookOnce.Do(func() {
		_ = internet.RegisterDialerController(func(network, address string, conn syscall.RawConn) error {
			xrayProtectMu.RLock()
			prot := xrayProtector
			xrayProtectMu.RUnlock()
			if prot == nil {
				return nil
			}
			var protectErr error
			if err := conn.Control(func(fd uintptr) {
				if !prot.Protect(int32(fd)) {
					protectErr = fmt.Errorf("xray failed to protect socket fd=%d", fd)
				}
			}); err != nil {
				return err
			}
			return protectErr
		})
	})
}

type xrayEngine struct {
	instance *xraycore.Instance
}

func startXrayEngine(configJSON string, socksPort int, protector SocketProtector) (*xrayEngine, error) {
	if strings.TrimSpace(configJSON) == "" {
		return nil, fmt.Errorf("Xray config JSON is empty")
	}

	registerXraySocketProtector(protector)

	// Ensure a local socks inbound on 127.0.0.1:<socksPort> exists for tun2socks.
	normalized, err := ensureSocksInbound(configJSON, socksPort)
	if err != nil {
		return nil, fmt.Errorf("invalid Xray JSON config: %w", err)
	}

	pbConfig, err := xraycore.LoadConfig("json", strings.NewReader(normalized))
	if err != nil {
		return nil, fmt.Errorf("load Xray config: %w", err)
	}

	inst, err := xraycore.New(pbConfig)
	if err != nil {
		return nil, fmt.Errorf("create Xray instance: %w", err)
	}

	if err := inst.Start(); err != nil {
		_ = inst.Close()
		return nil, fmt.Errorf("start Xray instance: %w", err)
	}

	logMsg("INFO", "Xray-core started (local SOCKS5 on 127.0.0.1:%d)", socksPort)
	return &xrayEngine{instance: inst}, nil
}

func (x *xrayEngine) Stop() {
	if x.instance != nil {
		_ = x.instance.Close()
	}
	xrayProtectMu.Lock()
	xrayProtector = nil
	xrayProtectMu.Unlock()
}

// ensureSocksInbound injects a 127.0.0.1:<socksPort> socks inbound if the user's Xray JSON doesn't define one.
func ensureSocksInbound(rawJSON string, socksPort int) (string, error) {
	var root map[string]any
	if err := json.Unmarshal([]byte(rawJSON), &root); err != nil {
		return "", err
	}

	inbounds, _ := root["inbounds"].([]any)
	hasSocks := false
	for _, item := range inbounds {
		if m, ok := item.(map[string]any); ok {
			if proto, _ := m["protocol"].(string); strings.EqualFold(proto, "socks") {
				m["listen"] = "127.0.0.1"
				m["port"] = socksPort
				hasSocks = true
				break
			}
		}
	}

	if !hasSocks {
		socksInbound := map[string]any{
			"tag":      "socks-in",
			"port":     socksPort,
			"listen":   "127.0.0.1",
			"protocol": "socks",
			"settings": map[string]any{
				"auth": "noauth",
				"udp":  true,
			},
			"sniffing": map[string]any{
				"enabled":      true,
				"destOverride": []string{"http", "tls", "quic"},
				"routeOnly":    true,
			},
		}
		inbounds = append([]any{socksInbound}, inbounds...)
		root["inbounds"] = inbounds
	}

	out, err := json.Marshal(root)
	if err != nil {
		return "", err
	}
	_ = context.Background()
	return string(out), nil
}
