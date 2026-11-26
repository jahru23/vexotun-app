package core

import (
	"fmt"
	"net"
	"testing"
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
