package core

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"golang.org/x/crypto/ssh"
)

var sshCopyBufPool = sync.Pool{
	New: func() any {
		buf := make([]byte, 64*1024)
		return &buf
	},
}

type SSHConfig struct {
	Host       string `json:"host"`
	Port       int    `json:"port"`
	Username   string `json:"username"`
	Password   string `json:"password"`
	PrivateKey string `json:"privateKey"`
	Transport  string `json:"transport"` // direct, http_proxy, ssl, ws, wss, slowdns
	ProxyHost  string `json:"proxyHost"`
	ProxyPort  int    `json:"proxyPort"`
	SNI        string `json:"sni"`
	Payload    string `json:"payload"`
	WSPath     string `json:"wsPath"`
	WSHost     string `json:"wsHost"`
	DNSServer   string `json:"dnsServer"`
	FallbackDNS string `json:"fallbackDns"`
	DNSType     string `json:"dnsType"`
	Nameserver  string `json:"nameserver"`
	PublicKey   string `json:"publicKey"`
}

type sshEngine struct {
	ctx           context.Context
	cancel        context.CancelFunc
	transportConn net.Conn
	client        *ssh.Client
	listener      net.Listener
	wg            sync.WaitGroup
	socksAddr     string
	dead          atomic.Bool
	connsMu       sync.Mutex
	activeConns   map[net.Conn]struct{}
}

func startSSHEngine(parentCtx context.Context, cfg SSHConfig, listenHost string, socksPort int, protector SocketProtector) (*sshEngine, error) {
	isSlowDNS := strings.EqualFold(strings.TrimSpace(cfg.Transport), "slowdns")
	if !isSlowDNS && (cfg.Host == "" || cfg.Port <= 0) {
		return nil, fmt.Errorf("invalid SSH host or port")
	}

	ctx, cancel := context.WithCancel(parentCtx)
	conn, err := dialSSHTransport(ctx, cfg, protector)
	if err != nil {
		cancel()
		return nil, err
	}

	authMethods := make([]ssh.AuthMethod, 0, 3)
	if strings.TrimSpace(cfg.PrivateKey) != "" {
		signer, err := ssh.ParsePrivateKey([]byte(cfg.PrivateKey))
		if err != nil {
			_ = conn.Close()
			cancel()
			return nil, fmt.Errorf("parse private key: %w", err)
		}
		authMethods = append(authMethods, ssh.PublicKeys(signer))
	}
	if cfg.Password != "" {
		authMethods = append(authMethods, ssh.Password(cfg.Password))
		authMethods = append(authMethods, ssh.KeyboardInteractive(func(user, instruction string, questions []string, echos []bool) ([]string, error) {
			answers := make([]string, len(questions))
			for i := range questions {
				answers[i] = cfg.Password
			}
			return answers, nil
		}))
	}

	sshCfg := &ssh.ClientConfig{
		User:            cfg.Username,
		Auth:            authMethods,
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         15 * time.Second,
	}

	targetAddr := net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port))
	if isSlowDNS && cfg.Host == "" {
		targetAddr = net.JoinHostPort(cfg.Nameserver, "22")
	}
	logMsg("INFO", "Authenticating SSH user %q on %s...", cfg.Username, targetAddr)
	sshDone := make(chan struct{})
	go func() {
		select {
		case <-sshDone:
		case <-ctx.Done():
			_ = conn.Close()
		}
	}()
	c, chans, reqs, err := ssh.NewClientConn(conn, targetAddr, sshCfg)
	close(sshDone)
	if err != nil {
		_ = conn.Close()
		cancel()
		return nil, fmt.Errorf("SSH handshake failed: %w", err)
	}

	client := ssh.NewClient(c, chans, reqs)
	logMsg("INFO", "SSH connected successfully")

	socksAddr := net.JoinHostPort(listenHost, strconv.Itoa(socksPort))
	ln, err := net.Listen("tcp", socksAddr)
	if err != nil {
		_ = client.Close()
		cancel()
		return nil, fmt.Errorf("listen local SOCKS5 %s: %w", socksAddr, err)
	}

	eng := &sshEngine{
		ctx:           ctx,
		cancel:        cancel,
		transportConn: conn,
		client:        client,
		listener:      ln,
		socksAddr:     socksAddr,
		activeConns:   make(map[net.Conn]struct{}),
	}

	eng.wg.Add(3)
	go eng.waitExitLoop()
	go eng.keepAliveLoop()
	go eng.acceptLoop()

	logMsg("INFO", "Local SOCKS5 listening on %s", socksAddr)
	return eng, nil
}

func dialSSHTransport(ctx context.Context, cfg SSHConfig, protector SocketProtector) (net.Conn, error) {
	transport := strings.ToLower(strings.TrimSpace(cfg.Transport))
	if transport == "" {
		transport = "direct"
	}

	sshAddr := net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port))
	timeout := 15 * time.Second

	switch transport {
	case "direct":
		logMsg("INFO", "Dialing SSH direct: %s", sshAddr)
		return dialProtectedTCP(ctx, protector, sshAddr, timeout)

	case "http_proxy":
		proxyAddr := sshAddr
		if cfg.ProxyHost != "" && cfg.ProxyPort > 0 {
			proxyAddr = net.JoinHostPort(cfg.ProxyHost, strconv.Itoa(cfg.ProxyPort))
		}
		logMsg("INFO", "Dialing HTTP Proxy %s -> %s", proxyAddr, sshAddr)
		rawConn, err := dialProtectedTCP(ctx, protector, proxyAddr, timeout)
		if err != nil {
			return nil, fmt.Errorf("dial proxy %s: %w", proxyAddr, err)
		}
		wrapped, err := injectHTTPPayload(rawConn, cfg.Payload, cfg.Host, cfg.Port, cfg.SNI)
		if err != nil {
			_ = rawConn.Close()
			return nil, err
		}
		return wrapped, nil

	case "ssl":
		dialAddr := sshAddr
		if cfg.ProxyHost != "" && cfg.ProxyPort > 0 {
			dialAddr = net.JoinHostPort(cfg.ProxyHost, strconv.Itoa(cfg.ProxyPort))
		}
		sni := cfg.SNI
		if sni == "" {
			sni = cfg.Host
		}
		logMsg("INFO", "Dialing SSL/TLS %s (SNI=%s)", dialAddr, sni)
		rawConn, err := dialProtectedTCP(ctx, protector, dialAddr, timeout)
		if err != nil {
			return nil, fmt.Errorf("dial SSL %s: %w", dialAddr, err)
		}
		tlsConn := tls.Client(rawConn, &tls.Config{
			ServerName:         sni,
			InsecureSkipVerify: true,
		})
		if err := tlsConn.HandshakeContext(ctx); err != nil {
			_ = rawConn.Close()
			return nil, fmt.Errorf("TLS handshake: %w", err)
		}
		if strings.TrimSpace(cfg.Payload) != "" {
			wrapped, err := injectHTTPPayload(tlsConn, cfg.Payload, cfg.Host, cfg.Port, sni)
			if err != nil {
				_ = tlsConn.Close()
				return nil, err
			}
			return wrapped, nil
		}
		return tlsConn, nil

	case "ws", "wss":
		scheme := transport
		dialHost := cfg.Host
		dialPort := cfg.Port
		if cfg.ProxyHost != "" && cfg.ProxyPort > 0 {
			dialHost = cfg.ProxyHost
			dialPort = cfg.ProxyPort
		}
		path := cfg.WSPath
		if path == "" {
			path = "/"
		}
		if !strings.HasPrefix(path, "/") {
			path = "/" + path
		}
		wsURL := fmt.Sprintf("%s://%s%s", scheme, net.JoinHostPort(dialHost, strconv.Itoa(dialPort)), path)
		sni := cfg.SNI
		if sni == "" {
			sni = cfg.Host
		}
		wsHost := cfg.WSHost
		if wsHost == "" {
			wsHost = sni
		}

		logMsg("INFO", "Dialing WebSocket %s (Host=%s, SNI=%s)", wsURL, wsHost, sni)
		d := newProtectedDialer(protector, timeout)
		wsDialer := websocket.Dialer{
			NetDialContext:   d.DialContext,
			HandshakeTimeout: timeout,
			TLSClientConfig: &tls.Config{
				ServerName:         sni,
				InsecureSkipVerify: true,
			},
		}
		headers := http.Header{}
		headers.Set("Host", wsHost)
		headers.Set("User-Agent", defaultUserAgent)

		wsConn, resp, err := wsDialer.DialContext(ctx, wsURL, headers)
		if err != nil {
			if resp != nil {
				return nil, fmt.Errorf("WS dial failed (%s): %w", resp.Status, err)
			}
			return nil, fmt.Errorf("WS dial failed: %w", err)
		}
		return &wsNetConn{conn: wsConn}, nil

	case "slowdns":
		return dialSlowDNS(ctx, cfg, protector)

	default:
		return nil, fmt.Errorf("unsupported SSH transport: %s", transport)
	}
}

func (e *sshEngine) Alive() bool {
	if e.dead.Load() || e.ctx.Err() != nil {
		return false
	}
	if hc, ok := e.transportConn.(interface{ IsHealthy(time.Duration) bool }); ok {
		if !hc.IsHealthy(12 * time.Second) {
			return false
		}
	}
	return true
}

func (e *sshEngine) waitExitLoop() {
	defer e.wg.Done()
	_ = e.client.Wait()
	if e.ctx.Err() == nil {
		if !e.dead.Swap(true) {
			logMsg("WARN", "Koneksi SSH tertutup dari server")
		}
		e.cancel()
		if e.listener != nil {
			_ = e.listener.Close()
		}
	}
}

func (e *sshEngine) keepAliveLoop() {
	defer e.wg.Done()
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	ticks := 0

	for {
		select {
		case <-e.ctx.Done():
			return
		case <-ticker.C:
			if e.dead.Load() {
				return
			}
			if hc, ok := e.transportConn.(interface{ IsHealthy(time.Duration) bool }); ok && !hc.IsHealthy(12 * time.Second) {
				if e.ctx.Err() == nil && !e.dead.Swap(true) {
					logMsg("WARN", "Jalur SlowDNS terhenti (timeout), memutus sesi untuk reconnect...")
				}
				e.cancel()
				_ = e.transportConn.Close()
				_ = e.client.Close()
				if e.listener != nil {
					_ = e.listener.Close()
				}
				return
			}
			ticks++
			if ticks%3 != 0 {
				continue
			}

			// Note: Dropbear does not reply to global keepalive requests when wantReply=true
			// (or answers with SSH_MSG_UNIMPLEMENTED, leaving x/crypto/ssh blocked indefinitely).
			// Send wantReply=false to keep NAT/idle timers alive and detect broken transport writes.
			kaDone := make(chan error, 1)
			go func() {
				_, _, err := e.client.SendRequest("keepalive@openssh.com", false, nil)
				kaDone <- err
			}()

			select {
			case <-e.ctx.Done():
				return
			case err := <-kaDone:
				if err != nil {
					if e.ctx.Err() == nil && !e.dead.Swap(true) {
						logMsg("WARN", "SSH keepalive failed: %v", err)
					}
					e.cancel()
					_ = e.transportConn.Close()
					_ = e.client.Close()
					if e.listener != nil {
						_ = e.listener.Close()
					}
					return
				}
			case <-time.After(8 * time.Second):
				if e.ctx.Err() == nil && !e.dead.Swap(true) {
					logMsg("WARN", "SSH keepalive write timeout (>8s)")
				}
				e.cancel()
				_ = e.transportConn.Close()
				_ = e.client.Close()
				if e.listener != nil {
					_ = e.listener.Close()
				}
				return
			}
		}
	}
}

func (e *sshEngine) acceptLoop() {
	defer e.wg.Done()
	for {
		c, err := e.listener.Accept()
		if err != nil {
			if e.dead.Load() || e.ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
				return
			}
			logMsg("WARN", "SOCKS5 accept error: %v", err)
			time.Sleep(15 * time.Millisecond)
			continue
		}
		e.wg.Add(1)
		go func() {
			defer e.wg.Done()
			e.handleSocks5(c)
		}()
	}
}

func (e *sshEngine) handleSocks5(clientConn net.Conn) {
	e.connsMu.Lock()
	e.activeConns[clientConn] = struct{}{}
	e.connsMu.Unlock()
	defer func() {
		e.connsMu.Lock()
		delete(e.activeConns, clientConn)
		e.connsMu.Unlock()
		_ = clientConn.Close()
	}()
	_ = clientConn.SetDeadline(time.Now().Add(15 * time.Second))

	var hdr [2]byte
	if _, err := io.ReadFull(clientConn, hdr[:]); err != nil || hdr[0] != 0x05 {
		return
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(clientConn, methods); err != nil {
		return
	}
	// No authentication required (0x00)
	if _, err := clientConn.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	var req [4]byte
	if _, err := io.ReadFull(clientConn, req[:]); err != nil || req[0] != 0x05 {
		return
	}
	cmd, atyp := req[1], req[3]

	var host string
	switch atyp {
	case 0x01: // IPv4
		var ip [4]byte
		if _, err := io.ReadFull(clientConn, ip[:]); err != nil {
			return
		}
		host = net.IP(ip[:]).String()
	case 0x03: // Domain
		var lenBuf [1]byte
		if _, err := io.ReadFull(clientConn, lenBuf[:]); err != nil {
			return
		}
		domain := make([]byte, lenBuf[0])
		if _, err := io.ReadFull(clientConn, domain); err != nil {
			return
		}
		host = string(domain)
	case 0x04: // IPv6
		var ip [16]byte
		if _, err := io.ReadFull(clientConn, ip[:]); err != nil {
			return
		}
		host = net.IP(ip[:]).String()
	default:
		_, _ = clientConn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var portBuf [2]byte
	if _, err := io.ReadFull(clientConn, portBuf[:]); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(portBuf[:])

	if cmd != 0x01 { // Only CONNECT supported over SSH
		_, _ = clientConn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	_ = clientConn.SetDeadline(time.Time{})
	target := net.JoinHostPort(host, strconv.Itoa(int(port)))
	type dialRes struct {
		c   net.Conn
		err error
	}
	dCh := make(chan dialRes, 1)
	go func() {
		rc, derr := e.client.Dial("tcp", target)
		dCh <- dialRes{c: rc, err: derr}
	}()
	var remoteConn net.Conn
	select {
	case <-e.ctx.Done():
		go func() {
			if r := <-dCh; r.c != nil {
				_ = r.c.Close()
			}
		}()
		return
	case <-time.After(12 * time.Second):
		go func() {
			if r := <-dCh; r.c != nil {
				_ = r.c.Close()
			}
		}()
		_, _ = clientConn.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	case r := <-dCh:
		if r.err != nil {
			_, _ = clientConn.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
			return
		}
		remoteConn = r.c
	}
	defer remoteConn.Close()

	if tcpConn, ok := clientConn.(*net.TCPConn); ok {
		_ = tcpConn.SetNoDelay(true)
	}

	if _, err := clientConn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	var copyWg sync.WaitGroup
	copyWg.Add(2)
	go func() {
		defer copyWg.Done()
		bufPtr := sshCopyBufPool.Get().(*[]byte)
		defer sshCopyBufPool.Put(bufPtr)
		n, _ := io.CopyBuffer(remoteConn, clientConn, *bufPtr)
		bytesUp.Add(n)
	}()
	go func() {
		defer copyWg.Done()
		bufPtr := sshCopyBufPool.Get().(*[]byte)
		defer sshCopyBufPool.Put(bufPtr)
		n, _ := io.CopyBuffer(clientConn, remoteConn, *bufPtr)
		bytesDown.Add(n)
	}()
	copyWg.Wait()
}

func (e *sshEngine) Stop() {
	e.dead.Store(true)
	e.cancel()
	if e.listener != nil {
		_ = e.listener.Close()
	}
	if e.transportConn != nil {
		_ = e.transportConn.Close()
	}
	if e.client != nil {
		_ = e.client.Close()
	}
	e.connsMu.Lock()
	for c := range e.activeConns {
		_ = c.Close()
	}
	e.connsMu.Unlock()

	done := make(chan struct{})
	go func() {
		e.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(500 * time.Millisecond):
	}
}

// wsNetConn adapts *websocket.Conn to net.Conn for ssh.NewClientConn.
type wsNetConn struct {
	conn   *websocket.Conn
	reader io.Reader
	mu     sync.Mutex
}

func (w *wsNetConn) Read(p []byte) (int, error) {
	for {
		if w.reader == nil {
			_, r, err := w.conn.NextReader()
			if err != nil {
				return 0, err
			}
			w.reader = r
		}
		n, err := w.reader.Read(p)
		if err == io.EOF {
			w.reader = nil
			if n > 0 {
				return n, nil
			}
			continue
		}
		return n, err
	}
}

func (w *wsNetConn) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if err := w.conn.WriteMessage(websocket.BinaryMessage, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (w *wsNetConn) Close() error                       { return w.conn.Close() }
func (w *wsNetConn) LocalAddr() net.Addr                { return w.conn.LocalAddr() }
func (w *wsNetConn) RemoteAddr() net.Addr               { return w.conn.RemoteAddr() }
func (w *wsNetConn) SetDeadline(t time.Time) error      { return w.conn.UnderlyingConn().SetDeadline(t) }
func (w *wsNetConn) SetReadDeadline(t time.Time) error  { return w.conn.SetReadDeadline(t) }
func (w *wsNetConn) SetWriteDeadline(t time.Time) error { return w.conn.SetWriteDeadline(t) }
