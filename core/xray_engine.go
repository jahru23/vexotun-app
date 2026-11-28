package core

import (
	"encoding/json"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/xtls/xray-core/app/proxyman"
	xraylog "github.com/xtls/xray-core/common/log"
	"github.com/xtls/xray-core/common/serial"
	xraycore "github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/transport/internet"
	internet_tls "github.com/xtls/xray-core/transport/internet/tls"

	// Mandatory core features (replaces bloated distro/all)
	_ "github.com/xtls/xray-core/app/dispatcher"
	_ "github.com/xtls/xray-core/app/dns"
	_ "github.com/xtls/xray-core/app/dns/fakedns"
	_ "github.com/xtls/xray-core/app/log"
	_ "github.com/xtls/xray-core/app/policy"
	_ "github.com/xtls/xray-core/app/proxyman/inbound"
	_ "github.com/xtls/xray-core/app/proxyman/outbound"
	_ "github.com/xtls/xray-core/app/router"
	_ "github.com/xtls/xray-core/app/stats"
	_ "github.com/xtls/xray-core/main/json"

	// Client inbound / outbound protocols
	_ "github.com/xtls/xray-core/proxy/blackhole"
	_ "github.com/xtls/xray-core/proxy/dns"
	_ "github.com/xtls/xray-core/proxy/dokodemo"
	_ "github.com/xtls/xray-core/proxy/freedom"
	_ "github.com/xtls/xray-core/proxy/http"
	_ "github.com/xtls/xray-core/proxy/loopback"
	_ "github.com/xtls/xray-core/proxy/shadowsocks"
	_ "github.com/xtls/xray-core/proxy/socks"
	_ "github.com/xtls/xray-core/proxy/trojan"
	_ "github.com/xtls/xray-core/proxy/vless/outbound"
	_ "github.com/xtls/xray-core/proxy/vmess/outbound"

	// Transports
	_ "github.com/xtls/xray-core/transport/internet/grpc"
	_ "github.com/xtls/xray-core/transport/internet/headers/http"
	_ "github.com/xtls/xray-core/transport/internet/headers/noop"
	_ "github.com/xtls/xray-core/transport/internet/httpupgrade"
	_ "github.com/xtls/xray-core/transport/internet/reality"
	_ "github.com/xtls/xray-core/transport/internet/splithttp"
	_ "github.com/xtls/xray-core/transport/internet/tagged/taggedimpl"
	_ "github.com/xtls/xray-core/transport/internet/tcp"
	_ "github.com/xtls/xray-core/transport/internet/tls"
	_ "github.com/xtls/xray-core/transport/internet/udp"
	_ "github.com/xtls/xray-core/transport/internet/websocket"
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

type xrayLogBridge struct{}

func (b *xrayLogBridge) Handle(msg xraylog.Message) {
	if msg == nil {
		return
	}
	if gm, ok := msg.(*xraylog.GeneralMessage); ok {
		text := strings.TrimSpace(fmt.Sprint(gm.Content))
		if text == "" {
			return
		}
		switch gm.Severity {
		case xraylog.Severity_Error:
			logMsg("ERROR", "Xray: %s", text)
		case xraylog.Severity_Warning:
			logMsg("WARN", "Xray: %s", text)
		case xraylog.Severity_Info:
			logMsg("DEBUG", "Xray: %s", text)
		}
		return
	}
	text := strings.TrimSpace(msg.String())
	if text == "" || strings.HasPrefix(text, "[Debug]") {
		return
	}
	logMsg("DEBUG", "Xray: %s", text)
}

func installXrayLogBridge() {
	xraylog.RegisterHandler(&xrayLogBridge{})
}

func loadXrayProtobufConfig(rawJSON string, allowInsecure bool) (*xraycore.Config, error) {
	pbConfig, err := xraycore.LoadConfig("json", strings.NewReader(rawJSON))
	if err != nil {
		return nil, err
	}
	if !allowInsecure || pbConfig == nil {
		return pbConfig, nil
	}
	for _, out := range pbConfig.Outbound {
		if out == nil || out.SenderSettings == nil {
			continue
		}
		senderInst, err := out.SenderSettings.GetInstance()
		if err != nil {
			continue
		}
		senderCfg, ok := senderInst.(*proxyman.SenderConfig)
		if !ok || senderCfg.StreamSettings == nil {
			continue
		}
		modified := false
		for i, secMsg := range senderCfg.StreamSettings.SecuritySettings {
			if secMsg == nil {
				continue
			}
			secInst, err := secMsg.GetInstance()
			if err != nil {
				continue
			}
			if tlsCfg, ok := secInst.(*internet_tls.Config); ok {
				tlsCfg.AllowInsecure = true
				senderCfg.StreamSettings.SecuritySettings[i] = serial.ToTypedMessage(tlsCfg)
				modified = true
			}
		}
		if modified {
			out.SenderSettings = serial.ToTypedMessage(senderCfg)
		}
	}
	return pbConfig, nil
}

func stripXrayMux(rawJSON string) (bool, string, error) {
	var root map[string]any
	if err := json.Unmarshal([]byte(rawJSON), &root); err != nil {
		return false, "", err
	}
	hadMux := false
	outbounds, _ := root["outbounds"].([]any)
	for _, item := range outbounds {
		m, ok := item.(map[string]any)
		if !ok {
			continue
		}
		if muxObj, ok := m["mux"].(map[string]any); ok {
			if enabled, _ := muxObj["enabled"].(bool); enabled {
				hadMux = true
			}
			delete(m, "mux")
		}
	}
	if !hadMux {
		return false, rawJSON, nil
	}
	out, err := json.Marshal(root)
	if err != nil {
		return false, "", err
	}
	return true, string(out), nil
}

type xrayEngine struct {
	instance  *xraycore.Instance
	socksAddr string
}

func startXrayEngine(configJSON string, listenHost string, socksPort int, protector SocketProtector) (*xrayEngine, error) {
	return startXrayEngineWithOptions(configJSON, listenHost, socksPort, false, protector)
}

func startXrayEngineWithOptions(configJSON string, listenHost string, socksPort int, allowInsecure bool, protector SocketProtector) (*xrayEngine, error) {
	if strings.TrimSpace(configJSON) == "" {
		return nil, fmt.Errorf("Xray config JSON is empty")
	}

	registerXraySocketProtector(protector)

	// Ensure a socks inbound on <listenHost>:<socksPort> exists for tun2socks (and LAN hotspot if 0.0.0.0).
	normalized, err := ensureSocksInbound(configJSON, listenHost, socksPort)
	if err != nil {
		return nil, fmt.Errorf("invalid Xray JSON config: %w", err)
	}

	pbConfig, err := loadXrayProtobufConfig(normalized, allowInsecure)
	if err != nil {
		return nil, fmt.Errorf("load Xray config: %w", err)
	}

	inst, err := xraycore.New(pbConfig)
	if err != nil {
		return nil, fmt.Errorf("create Xray instance: %w", err)
	}
	installXrayLogBridge()

	if err := inst.Start(); err != nil {
		_ = inst.Close()
		return nil, fmt.Errorf("start Xray instance: %w", err)
	}
	installXrayLogBridge()

	dialHost := listenHost
	if dialHost == "" || dialHost == "0.0.0.0" || dialHost == "::" {
		dialHost = "127.0.0.1"
	}
	socksAddr := net.JoinHostPort(dialHost, strconv.Itoa(socksPort))

	logMsg("DEBUG", "Xray-core started (SOCKS5 on %s:%d, allowInsecure=%v)", listenHost, socksPort, allowInsecure)
	return &xrayEngine{instance: inst, socksAddr: socksAddr}, nil
}

func (x *xrayEngine) Alive() bool {
	if x == nil || x.instance == nil || x.socksAddr == "" {
		return false
	}
	c, err := net.DialTimeout("tcp", x.socksAddr, 800*time.Millisecond)
	if err != nil {
		return false
	}
	_ = c.Close()
	return true
}

func (x *xrayEngine) Stop() {
	if x.instance != nil {
		_ = x.instance.Close()
	}
	xrayProtectMu.Lock()
	xrayProtector = nil
	xrayProtectMu.Unlock()
}

// ensureSocksInbound injects a <listenHost>:<socksPort> socks inbound if the user's Xray JSON doesn't define one.
func ensureSocksInbound(rawJSON string, listenHost string, socksPort int) (string, error) {
	var root map[string]any
	if err := json.Unmarshal([]byte(rawJSON), &root); err != nil {
		return "", err
	}

	inbounds, _ := root["inbounds"].([]any)
	hasSocks := false
	for _, item := range inbounds {
		if m, ok := item.(map[string]any); ok {
			if proto, _ := m["protocol"].(string); strings.EqualFold(proto, "socks") {
				m["listen"] = listenHost
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
			"listen":   listenHost,
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
	return string(out), nil
}
