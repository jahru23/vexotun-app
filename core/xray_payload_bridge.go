package core

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

type XrayPayloadConfig struct {
	Enabled    bool   `json:"enabled"`
	TargetHost string `json:"targetHost"`
	TargetPort int    `json:"targetPort"`
	ProxyHost  string `json:"proxyHost"`
	ProxyPort  int    `json:"proxyPort"`
	Payload    string `json:"payload"`
	SNI        string `json:"sni"`
}

var xrayBridgeBufPool = sync.Pool{
	New: func() any {
		b := make([]byte, 64*1024)
		return &b
	},
}

type xrayPayloadBridge struct {
	ctx       context.Context
	cancel    context.CancelFunc
	listener  net.Listener
	cfg       XrayPayloadConfig
	protector SocketProtector
	port      int
	wg        sync.WaitGroup
}

func startXrayPayloadBridge(parentCtx context.Context, cfg XrayPayloadConfig, protector SocketProtector) (*xrayPayloadBridge, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("listen xray payload bridge: %w", err)
	}
	tcpAddr := ln.Addr().(*net.TCPAddr)
	ctx, cancel := context.WithCancel(parentCtx)

	b := &xrayPayloadBridge{
		ctx:       ctx,
		cancel:    cancel,
		listener:  ln,
		cfg:       cfg,
		protector: protector,
		port:      tcpAddr.Port,
	}
	b.wg.Add(1)
	go b.acceptLoop()

	logMsg("DEBUG", "Xray custom payload bridge active on 127.0.0.1:%d", b.port)
	return b, nil
}

func (b *xrayPayloadBridge) Stop() {
	b.cancel()
	if b.listener != nil {
		_ = b.listener.Close()
	}
	b.wg.Wait()
}

func (b *xrayPayloadBridge) acceptLoop() {
	defer b.wg.Done()
	for {
		c, err := b.listener.Accept()
		if err != nil {
			select {
			case <-b.ctx.Done():
				return
			default:
				continue
			}
		}
		b.wg.Add(1)
		go func(localConn net.Conn) {
			defer b.wg.Done()
			b.handleConn(localConn)
		}(c)
	}
}

func (b *xrayPayloadBridge) handleConn(localConn net.Conn) {
	defer localConn.Close()
	if tc, ok := localConn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}

	dialHost := strings.TrimSpace(b.cfg.ProxyHost)
	dialPort := b.cfg.ProxyPort
	if dialHost == "" || dialPort <= 0 {
		dialHost = strings.TrimSpace(b.cfg.TargetHost)
		dialPort = b.cfg.TargetPort
	}
	if dialHost == "" || dialPort <= 0 {
		logMsg("ERROR", "Xray payload bridge: host/port kosong")
		return
	}

	dialAddr := net.JoinHostPort(dialHost, strconv.Itoa(dialPort))
	rawConn, err := dialProtectedTCP(b.ctx, b.protector, dialAddr, 12*time.Second)
	if err != nil {
		logMsg("ERROR", "Xray payload dial gagal (%s): %v", dialAddr, err)
		return
	}

	var remoteConn net.Conn = rawConn
	if strings.TrimSpace(b.cfg.Payload) != "" {
		sni := strings.TrimSpace(b.cfg.SNI)
		if sni == "" {
			sni = strings.TrimSpace(b.cfg.TargetHost)
		}
		wrapped, err := injectHTTPPayload(rawConn, b.cfg.Payload, b.cfg.TargetHost, b.cfg.TargetPort, sni)
		if err != nil {
			_ = rawConn.Close()
			logMsg("ERROR", "Xray payload ditolak: %v", err)
			return
		}
		remoteConn = wrapped
	}
	defer remoteConn.Close()

	var pipeWg sync.WaitGroup
	pipeWg.Add(2)
	pipe := func(dst net.Conn, src net.Conn) {
		defer pipeWg.Done()
		bufPtr := xrayBridgeBufPool.Get().(*[]byte)
		_, _ = io.CopyBuffer(dst, src, *bufPtr)
		xrayBridgeBufPool.Put(bufPtr)
		if tc, ok := dst.(*net.TCPConn); ok {
			_ = tc.CloseWrite()
		}
	}
	go pipe(remoteConn, localConn)
	go pipe(localConn, remoteConn)
	pipeWg.Wait()
}

// rewriteXrayOutboundEndpoint redirects the primary "proxy" outbound to 127.0.0.1:<bridgePort>
// while preserving Host/SNI headers inside streamSettings.
func rewriteXrayOutboundEndpoint(rawJSON string, bridgePort int) (string, error) {
	var root map[string]any
	if err := json.Unmarshal([]byte(rawJSON), &root); err != nil {
		return "", err
	}
	outbounds, _ := root["outbounds"].([]any)
	for _, item := range outbounds {
		m, ok := item.(map[string]any)
		if !ok {
			continue
		}
		tag, _ := m["tag"].(string)
		if tag != "proxy" {
			continue
		}
		settings, ok := m["settings"].(map[string]any)
		if !ok {
			continue
		}
		if vnext, ok := settings["vnext"].([]any); ok && len(vnext) > 0 {
			if node, ok := vnext[0].(map[string]any); ok {
				node["address"] = "127.0.0.1"
				node["port"] = bridgePort
			}
		}
		if servers, ok := settings["servers"].([]any); ok && len(servers) > 0 {
			if node, ok := servers[0].(map[string]any); ok {
				node["address"] = "127.0.0.1"
				node["port"] = bridgePort
			}
		}
		break
	}
	out, err := json.Marshal(root)
	if err != nil {
		return "", err
	}
	return string(out), nil
}
