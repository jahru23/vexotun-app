package core

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/xtls/xray-core/app/proxyman"
	xraylog "github.com/xtls/xray-core/common/log"
	"github.com/xtls/xray-core/transport/internet/tls"
)

func getFreePort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen free port: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()
	return port
}

func TestSelectiveXrayImportsStartStop(t *testing.T) {
	socksPort := getFreePort(t)
	xrayJSON := `{
		"log": {"loglevel": "error"},
		"dns": {"queryStrategy": "UseIPv4", "servers": ["8.8.8.8", "1.1.1.1"]},
		"outbounds": [
			{
				"tag": "proxy",
				"protocol": "vless",
				"settings": {
					"vnext": [{
						"address": "127.0.0.1",
						"port": 443,
						"users": [{"id": "11111111-1111-1111-1111-111111111111", "encryption": "none"}]
					}]
				},
				"streamSettings": {
					"network": "ws",
					"security": "tls",
					"tlsSettings": {"serverName": "example.com", "allowInsecure": false, "fingerprint": "chrome", "alpn": ["http/1.1"]},
					"wsSettings": {"path": "/vless", "headers": {"Host": "example.com"}},
					"sockopt": {"tcpNoDelay": true, "tcpKeepAliveInterval": 30}
				},
				"mux": {"enabled": true, "concurrency": 8, "xudpConcurrency": 8, "xudpProxyUDP443": "reject"}
			},
			{"tag": "direct", "protocol": "freedom", "settings": {"domainStrategy": "AsIs"}}
		],
		"routing": {
			"domainStrategy": "IPIfNonMatch",
			"rules": [{
				"type": "field",
				"ip": ["10.0.0.0/8", "192.168.0.0/16"],
				"outboundTag": "direct"
			}]
		}
	}`

	eng, err := startXrayEngine(xrayJSON, "127.0.0.1", socksPort, nil)
	if err != nil {
		t.Fatalf("startXrayEngine failed: %v", err)
	}
	defer eng.Stop()

	conn, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort))
	if err != nil {
		t.Fatalf("expected socks5 inbound listening on %d: %v", socksPort, err)
	}
	_ = conn.Close()

	TrimMemory()
}

func TestSelectiveXrayXHTTPStartStop(t *testing.T) {
	socksPort := getFreePort(t)
	xrayJSON := `{
		"log": {"loglevel": "error"},
		"outbounds": [
			{
				"tag": "proxy",
				"protocol": "vless",
				"settings": {
					"vnext": [{
						"address": "127.0.0.1",
						"port": 443,
						"users": [{"id": "11111111-1111-1111-1111-111111111111", "encryption": "none"}]
					}]
				},
				"streamSettings": {
					"network": "xhttp",
					"security": "tls",
					"tlsSettings": {"serverName": "example.com", "fingerprint": "chrome", "alpn": ["h2", "http/1.1"]},
					"xhttpSettings": {"path": "/xhvless", "host": "example.com"}
				}
			},
			{"tag": "direct", "protocol": "freedom"}
		]
	}`

	eng, err := startXrayEngine(xrayJSON, "127.0.0.1", socksPort, nil)
	if err != nil {
		t.Fatalf("startXrayEngine XHTTP failed: %v", err)
	}
	eng.Stop()
}

type captureLogHandler struct {
	mu   sync.Mutex
	logs []string
}

func (c *captureLogHandler) OnLog(level string, message string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.logs = append(c.logs, level+": "+message)
}

func (c *captureLogHandler) hasSubstring(sub string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, l := range c.logs {
		if strings.Contains(l, sub) {
			return true
		}
	}
	return false
}

func TestXrayPrepareVerifyFailsOnUnreachableServer(t *testing.T) {
	Stop()
	defer Stop()

	probeSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer probeSrv.Close()

	closedPort := getFreePort(t)
	socksPort := getFreePort(t)
	xrayJSON := fmt.Sprintf(`{
		"log": {"loglevel": "warning"},
		"outbounds": [
			{
				"tag": "proxy",
				"protocol": "vless",
				"settings": {
					"vnext": [{
						"address": "127.0.0.1",
						"port": %d,
						"users": [{"id": "11111111-1111-1111-1111-111111111111", "encryption": "none"}]
					}]
				},
				"streamSettings": {"network": "tcp", "security": "none"},
				"mux": {"enabled": true, "concurrency": 8}
			}
		]
	}`, closedPort)

	verify := true
	cfg := TunnelConfig{
		Mode:           "xray",
		MTU:            1420,
		SocksPort:      socksPort,
		XrayConfigJSON: xrayJSON,
		XrayVerify:     &verify,
		XrayProbeURL:   probeSrv.URL,
	}
	raw, _ := json.Marshal(cfg)
	logger := &captureLogHandler{}

	err := Prepare(string(raw), nil, logger)
	if err == nil {
		t.Fatalf("expected Prepare to fail when remote Xray server is unreachable, but it succeeded")
	}
	if IsRunning() {
		t.Fatalf("expected IsRunning() == false after failed Prepare")
	}
}

func TestXrayPrepareVerifyCanBeDisabled(t *testing.T) {
	Stop()
	defer Stop()

	closedPort := getFreePort(t)
	socksPort := getFreePort(t)
	xrayJSON := fmt.Sprintf(`{
		"log": {"loglevel": "error"},
		"outbounds": [{
			"tag": "proxy",
			"protocol": "vless",
			"settings": {
				"vnext": [{
					"address": "127.0.0.1",
					"port": %d,
					"users": [{"id": "11111111-1111-1111-1111-111111111111", "encryption": "none"}]
				}]
			},
			"streamSettings": {"network": "tcp", "security": "none"}
		}]
	}`, closedPort)

	verify := false
	cfg := TunnelConfig{
		Mode:           "xray",
		MTU:            1420,
		SocksPort:      socksPort,
		XrayConfigJSON: xrayJSON,
		XrayVerify:     &verify,
	}
	raw, _ := json.Marshal(cfg)

	if err := Prepare(string(raw), nil, nil); err != nil {
		t.Fatalf("expected Prepare with verify=false to succeed, got: %v", err)
	}
	if !IsRunning() {
		t.Fatalf("expected IsRunning() == true when Xray listener is up")
	}
	Stop()
	if IsRunning() {
		t.Fatalf("expected IsRunning() == false after Stop()")
	}
}

func TestXrayAllowInsecureProtobufApplied(t *testing.T) {
	xrayJSON := `{
		"outbounds": [{
			"tag": "proxy",
			"protocol": "vless",
			"settings": {
				"vnext": [{
					"address": "127.0.0.1",
					"port": 443,
					"users": [{"id": "11111111-1111-1111-1111-111111111111", "encryption": "none"}]
				}]
			},
			"streamSettings": {
				"network": "ws",
				"security": "tls",
				"tlsSettings": {"serverName": "example.com", "fingerprint": "chrome"}
			}
		}]
	}`

	pbCfg, err := loadXrayProtobufConfig(xrayJSON, true)
	if err != nil {
		t.Fatalf("loadXrayProtobufConfig failed: %v", err)
	}
	if len(pbCfg.Outbound) == 0 || pbCfg.Outbound[0].SenderSettings == nil {
		t.Fatalf("missing outbound sender settings")
	}
	inst, err := pbCfg.Outbound[0].SenderSettings.GetInstance()
	if err != nil {
		t.Fatalf("GetInstance senderSettings: %v", err)
	}
	senderCfg := inst.(*proxyman.SenderConfig)
	if len(senderCfg.StreamSettings.SecuritySettings) == 0 {
		t.Fatalf("missing security settings")
	}
	secInst, err := senderCfg.StreamSettings.SecuritySettings[0].GetInstance()
	if err != nil {
		t.Fatalf("GetInstance securitySettings: %v", err)
	}
	tlsCfg, ok := secInst.(*tls.Config)
	if !ok {
		t.Fatalf("expected *tls.Config, got %T", secInst)
	}
	if !tlsCfg.AllowInsecure {
		t.Fatalf("expected tls.Config.AllowInsecure == true after protobuf patch")
	}
}

func TestXrayLogBridgeForwardsWarnings(t *testing.T) {
	logger := &captureLogHandler{}
	setLogHandler(logger)
	defer setLogHandler(nil)

	installXrayLogBridge()
	xraylog.Record(&xraylog.GeneralMessage{
		Severity: xraylog.Severity_Warning,
		Content:  "test-tls-handshake-rejected",
	})

	if !logger.hasSubstring("test-tls-handshake-rejected") {
		t.Fatalf("expected xray log bridge to forward warning message to LogHandler")
	}
}

func TestPrepareStopConcurrencyNoRace(t *testing.T) {
	Stop()
	defer Stop()

	socksPort := getFreePort(t)
	closedPort := getFreePort(t)
	xrayJSON := fmt.Sprintf(`{
		"outbounds": [{
			"tag": "proxy",
			"protocol": "vless",
			"settings": {"vnext": [{"address": "127.0.0.1", "port": %d, "users": [{"id": "11111111-1111-1111-1111-111111111111", "encryption": "none"}]}]},
			"streamSettings": {"network": "tcp", "security": "none"}
		}]
	}`, closedPort)

	verify := false
	raw, _ := json.Marshal(TunnelConfig{
		Mode:           "xray",
		SocksPort:      socksPort,
		XrayConfigJSON: xrayJSON,
		XrayVerify:     &verify,
	})

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_ = Prepare(string(raw), nil, nil)
	}()
	go func() {
		defer wg.Done()
		time.Sleep(5 * time.Millisecond)
		Stop()
	}()
	wg.Wait()

	// Calling Prepare again right after Stop returns must never fail with "tunnel is already running".
	Stop()
	err := Prepare(string(raw), nil, nil)
	if err != nil {
		t.Fatalf("Prepare after Stop failed: %v", err)
	}
	Stop()
}
