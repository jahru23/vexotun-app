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
	Host         string `json:"host"`
	Port         int    `json:"port"`
	Username     string `json:"username"`
	Password     string `json:"password"`
	PrivateKey   string `json:"privateKey"`
	Transport    string `json:"transport"` // direct, http_proxy, ssl, ws, wss, slowdns
	ProxyHost    string `json:"proxyHost"`
	ProxyPort    int    `json:"proxyPort"`
	SNI          string `json:"sni"`
	Payload      string `json:"payload"`
	WSPath       string `json:"wsPath"`
	WSHost       string `json:"wsHost"`
	WSRawUpgrade bool   `json:"wsRawUpgrade,omitempty"`
	UDPGWPort    int    `json:"udpgwPort,omitempty"`
	DNSServer    string `json:"dnsServer"`
	FallbackDNS  string `json:"fallbackDns"`
	DNSType      string `json:"dnsType"`
	Nameserver   string `json:"nameserver"`
	PublicKey    string `json:"publicKey"`
	SlowDNSMode  string `json:"slowDnsMode,omitempty"` // auto, vexdns, dnstt
}

type dnsCacheEntry struct {
	resp      []byte
	expiresAt time.Time
}

type sshEngine struct {
	ctx           context.Context
	cancel        context.CancelFunc
	cfg           SSHConfig
	listenHost    string
	transportConn net.Conn
	client        *ssh.Client
	listener      net.Listener
	wg            sync.WaitGroup
	socksAddr     string
	dead          atomic.Bool
	connsMu       sync.Mutex
	activeConns   map[net.Conn]struct{}
	dnsMu         sync.Mutex
	dnsCache      map[string]dnsCacheEntry
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
	logMsg("DEBUG", "Authenticating SSH user %q on %s...", cfg.Username, targetAddr)
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
	logMsg("DEBUG", "SSH connected successfully")

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
		cfg:           cfg,
		listenHost:    listenHost,
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

	logMsg("DEBUG", "Local SOCKS5 listening on %s", socksAddr)
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
		logMsg("DEBUG", "Dialing SSH direct: %s", sshAddr)
		return dialProtectedTCP(ctx, protector, sshAddr, timeout)

	case "http_proxy":
		proxyAddr := sshAddr
		if cfg.ProxyHost != "" && cfg.ProxyPort > 0 {
			proxyAddr = net.JoinHostPort(cfg.ProxyHost, strconv.Itoa(cfg.ProxyPort))
		}
		logMsg("DEBUG", "Dialing HTTP Proxy %s -> %s", proxyAddr, sshAddr)
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
		logMsg("DEBUG", "Dialing SSL/TLS %s (SNI=%s)", dialAddr, sni)
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
		return dialSSHWebSocket(ctx, cfg, protector, transport, timeout)

	case "slowdns":
		return dialSlowDNS(ctx, cfg, protector)

	default:
		return nil, fmt.Errorf("unsupported SSH transport: %s", transport)
	}
}

func dialSSHWebSocket(ctx context.Context, cfg SSHConfig, protector SocketProtector, transport string, timeout time.Duration) (net.Conn, error) {
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
	sni := cfg.SNI
	if sni == "" {
		sni = cfg.Host
	}
	wsHost := cfg.WSHost
	if wsHost == "" {
		wsHost = sni
	}
	dialAddr := net.JoinHostPort(dialHost, strconv.Itoa(dialPort))

	dialRawWS := func() (net.Conn, error) {
		rawConn, err := dialProtectedTCP(ctx, protector, dialAddr, timeout)
		if err != nil {
			return nil, fmt.Errorf("dial WS raw %s: %w", dialAddr, err)
		}
		var baseConn net.Conn = rawConn
		if transport == "wss" {
			tlsConn := tls.Client(rawConn, &tls.Config{
				ServerName:         sni,
				InsecureSkipVerify: true,
			})
			if err := tlsConn.HandshakeContext(ctx); err != nil {
				_ = rawConn.Close()
				return nil, fmt.Errorf("WSS TLS handshake: %w", err)
			}
			baseConn = tlsConn
		}
		payload := cfg.Payload
		trimmedPayload := strings.TrimSpace(payload)
		if trimmedPayload == "" || (strings.HasPrefix(trimmedPayload, "GET / HTTP/1.1[crlf]Host: [host]") && (wsHost != cfg.Host || path != "/")) {
			payload = fmt.Sprintf(
				"GET %s HTTP/1.1[crlf]Host: %s[crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]User-Agent: [ua][crlf][crlf]",
				path, wsHost,
			)
		}
		wrapped, err := injectHTTPPayload(baseConn, payload, cfg.Host, cfg.Port, wsHost)
		if err != nil {
			_ = baseConn.Close()
			return nil, err
		}
		return wrapped, nil
	}

	if cfg.WSRawUpgrade {
		logMsg("DEBUG", "Dialing SSH WebSocket Raw 101 %s://%s%s (Host=%s, SNI=%s)", transport, dialAddr, path, wsHost, sni)
		return dialRawWS()
	}

	wsURL := fmt.Sprintf("%s://%s%s", transport, dialAddr, path)
	logMsg("DEBUG", "Dialing WebSocket %s (Host=%s, SNI=%s)", wsURL, wsHost, sni)
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
	if err == nil {
		return &wsNetConn{conn: wsConn}, nil
	}
	if ctx.Err() == nil && (errors.Is(err, websocket.ErrBadHandshake) || (resp != nil && resp.StatusCode == http.StatusSwitchingProtocols)) {
		logMsg("DEBUG", "Server WS bukan RFC 6455 (%v), beralih ke Raw HTTP 101 Upgrade...", err)
		rawConn, rawErr := dialRawWS()
		if rawErr == nil {
			return rawConn, nil
		}
		return nil, fmt.Errorf("WS dial failed (%v); raw 101 fallback failed: %w", err, rawErr)
	}
	if resp != nil {
		return nil, fmt.Errorf("WS dial failed (%s): %w", resp.Status, err)
	}
	return nil, fmt.Errorf("WS dial failed: %w", err)
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
			logMsg("DEBUG", "SOCKS5 accept error: %v", err)
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

	if cmd == 0x03 { // UDP ASSOCIATE (relay DNS port 53 over SSH TCP)
		_ = clientConn.SetDeadline(time.Time{})
		e.handleSocks5UDPAssociate(clientConn)
		return
	}
	if cmd != 0x01 { // Only CONNECT and UDP ASSOCIATE supported over SSH
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

func (e *sshEngine) handleSocks5UDPAssociate(clientConn net.Conn) {
	bindIP := net.IPv4(127, 0, 0, 1)
	if e.listenHost == "0.0.0.0" {
		bindIP = net.IPv4zero
	}
	udpConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: bindIP, Port: 0})
	if err != nil {
		_, _ = clientConn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer udpConn.Close()

	uaddr := udpConn.LocalAddr().(*net.UDPAddr)
	replyIP := net.IPv4(127, 0, 0, 1).To4()
	if ta, ok := clientConn.LocalAddr().(*net.TCPAddr); ok {
		if ip4 := ta.IP.To4(); ip4 != nil {
			replyIP = ip4
		}
	}
	reply := []byte{
		0x05, 0x00, 0x00, 0x01,
		replyIP[0], replyIP[1], replyIP[2], replyIP[3],
		byte(uaddr.Port >> 8), byte(uaddr.Port & 0xff),
	}
	if _, err := clientConn.Write(reply); err != nil {
		return
	}

	var udpgw *sshUDPGWRelay
	if e.cfg.UDPGWPort > 0 {
		udpgw = newSSHUDPGWRelay(e.ctx, e.client, e.cfg.UDPGWPort, udpConn)
		defer udpgw.Close()
	}

	// Keep TCP control connection open per RFC 1928; closing it tears down the UDP relay.
	done := make(chan struct{})
	go func() {
		defer close(done)
		var drain [64]byte
		for {
			if _, rerr := clientConn.Read(drain[:]); rerr != nil {
				_ = udpConn.Close()
				return
			}
		}
	}()
	go func() {
		select {
		case <-e.ctx.Done():
			_ = udpConn.Close()
		case <-done:
		}
	}()

	buf := make([]byte, 4096)
	for {
		n, srcAddr, rerr := udpConn.ReadFromUDP(buf)
		if rerr != nil {
			return
		}
		if n < 10 || buf[2] != 0x00 {
			continue
		}
		hdrLen, dstHost, dstPort, ok := parseSocks5UDPHeader(buf[:n])
		if !ok {
			continue
		}
		hdrCopy := append([]byte(nil), buf[:hdrLen]...)
		payloadCopy := append([]byte(nil), buf[hdrLen:n]...)
		if dstPort == 53 && len(payloadCopy) >= 12 {
			go func(src *net.UDPAddr, hdr, q []byte, host string) {
				resp, err := e.resolveDNSOverSSH(host, q)
				if err != nil || len(resp) == 0 {
					return
				}
				pkt := make([]byte, len(hdr)+len(resp))
				copy(pkt, hdr)
				copy(pkt[len(hdr):], resp)
				_, _ = udpConn.WriteToUDP(pkt, src)
			}(srcAddr, hdrCopy, payloadCopy, dstHost)
			continue
		}
		if udpgw != nil {
			go udpgw.Forward(srcAddr, hdrCopy, dstHost, dstPort, payloadCopy)
		}
	}
}

type udpgwEndpoint struct {
	src     *net.UDPAddr
	hdr     []byte
	lastUse time.Time
}

type sshUDPGWRelay struct {
	ctx       context.Context
	client    *ssh.Client
	udpgwPort int
	udpConn   *net.UDPConn
	mu        sync.Mutex
	writeMu   sync.Mutex
	tcpConn   net.Conn
	nextID    uint16
	sessions  map[string]uint16
	reverse   map[uint16]udpgwEndpoint
	closed    bool
}

func newSSHUDPGWRelay(ctx context.Context, client *ssh.Client, udpgwPort int, udpConn *net.UDPConn) *sshUDPGWRelay {
	return &sshUDPGWRelay{
		ctx:       ctx,
		client:    client,
		udpgwPort: udpgwPort,
		udpConn:   udpConn,
		sessions:  make(map[string]uint16),
		reverse:   make(map[uint16]udpgwEndpoint),
	}
}

func (r *sshUDPGWRelay) Close() {
	r.mu.Lock()
	r.closed = true
	c := r.tcpConn
	r.tcpConn = nil
	r.mu.Unlock()
	if c != nil {
		_ = c.Close()
	}
}

func (r *sshUDPGWRelay) ensureConn() (net.Conn, error) {
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return nil, net.ErrClosed
	}
	if r.tcpConn != nil {
		c := r.tcpConn
		r.mu.Unlock()
		return c, nil
	}
	r.mu.Unlock()

	c, err := r.client.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", r.udpgwPort))
	if err != nil {
		return nil, err
	}

	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		_ = c.Close()
		return nil, net.ErrClosed
	}
	if r.tcpConn != nil {
		existing := r.tcpConn
		r.mu.Unlock()
		_ = c.Close()
		return existing, nil
	}
	r.tcpConn = c
	r.mu.Unlock()

	go r.readLoop(c)
	return c, nil
}

func (r *sshUDPGWRelay) readLoop(c net.Conn) {
	defer func() {
		_ = c.Close()
		r.mu.Lock()
		if r.tcpConn == c {
			r.tcpConn = nil
		}
		r.mu.Unlock()
	}()
	var lenBuf [2]byte
	for {
		if _, err := io.ReadFull(c, lenBuf[:]); err != nil {
			return
		}
		plen := int(binary.LittleEndian.Uint16(lenBuf[:]))
		if plen < 3 || plen > 8192 {
			return
		}
		body := make([]byte, plen)
		if _, err := io.ReadFull(c, body); err != nil {
			return
		}
		flags := body[0]
		if flags&0x01 != 0 { // keepalive
			continue
		}
		connID := binary.LittleEndian.Uint16(body[1:3])
		addrOff := 9
		if flags&0x08 != 0 {
			addrOff = 21
		}
		if len(body) <= addrOff {
			continue
		}
		payload := body[addrOff:]

		r.mu.Lock()
		ep, ok := r.reverse[connID]
		r.mu.Unlock()
		if !ok {
			continue
		}
		pkt := make([]byte, len(ep.hdr)+len(payload))
		copy(pkt, ep.hdr)
		copy(pkt[len(ep.hdr):], payload)
		_, _ = r.udpConn.WriteToUDP(pkt, ep.src)
	}
}

func (r *sshUDPGWRelay) allocSession(src *net.UDPAddr, hdr []byte, dstHost string, dstPort uint16) (uint16, bool) {
	key := fmt.Sprintf("%s|%s:%d", src.String(), dstHost, dstPort)
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return 0, false
	}
	connID, ok := r.sessions[key]
	if !ok {
		if len(r.sessions) >= 128 {
			cutoff := time.Now().Add(-30 * time.Second)
			for k, id := range r.sessions {
				if ep, exists := r.reverse[id]; !exists || ep.lastUse.Before(cutoff) {
					delete(r.sessions, k)
					delete(r.reverse, id)
				}
			}
		}
		for i := 0; i < 65535; i++ {
			r.nextID++
			if r.nextID == 0 {
				r.nextID = 1
			}
			if _, used := r.reverse[r.nextID]; !used {
				connID = r.nextID
				break
			}
		}
		if connID == 0 {
			return 0, false
		}
		r.sessions[key] = connID
	}
	r.reverse[connID] = udpgwEndpoint{src: src, hdr: hdr, lastUse: time.Now()}
	return connID, true
}

func (r *sshUDPGWRelay) Forward(src *net.UDPAddr, hdr []byte, dstHost string, dstPort uint16, payload []byte) {
	bodyLen := 3 + 4 + 2 + len(payload)
	if bodyLen > 8192 {
		return
	}
	ip := net.ParseIP(dstHost)
	if ip == nil {
		return
	}
	ip4 := ip.To4()
	if ip4 == nil {
		return
	}
	tc, err := r.ensureConn()
	if err != nil {
		return
	}

	connID, ok := r.allocSession(src, hdr, dstHost, dstPort)
	if !ok {
		return
	}

	pkt := make([]byte, 2+bodyLen)
	binary.LittleEndian.PutUint16(pkt[0:2], uint16(bodyLen))
	pkt[2] = 0x00
	binary.LittleEndian.PutUint16(pkt[3:5], connID)
	copy(pkt[5:9], ip4)
	binary.BigEndian.PutUint16(pkt[9:11], dstPort)
	copy(pkt[11:], payload)

	r.writeMu.Lock()
	_, werr := tc.Write(pkt)
	r.writeMu.Unlock()
	if werr == nil {
		bytesUp.Add(int64(len(payload)))
	}
}

func parseSocks5UDPHeader(pkt []byte) (hdrLen int, host string, port uint16, ok bool) {
	if len(pkt) < 10 {
		return 0, "", 0, false
	}
	atyp := pkt[3]
	off := 4
	switch atyp {
	case 0x01: // IPv4
		if len(pkt) < off+4+2 {
			return 0, "", 0, false
		}
		host = net.IP(pkt[off : off+4]).String()
		off += 4
	case 0x03: // Domain
		if len(pkt) < off+1 {
			return 0, "", 0, false
		}
		dlen := int(pkt[off])
		off++
		if len(pkt) < off+dlen+2 {
			return 0, "", 0, false
		}
		host = string(pkt[off : off+dlen])
		off += dlen
	case 0x04: // IPv6
		if len(pkt) < off+16+2 {
			return 0, "", 0, false
		}
		host = net.IP(pkt[off : off+16]).String()
		off += 16
	default:
		return 0, "", 0, false
	}
	port = binary.BigEndian.Uint16(pkt[off : off+2])
	off += 2
	return off, host, port, true
}

func (e *sshEngine) resolveDNSOverSSH(dstHost string, query []byte) ([]byte, error) {
	if len(query) < 12 {
		return nil, fmt.Errorf("short dns query")
	}
	cacheKey := string(query[2:])
	now := time.Now()
	e.dnsMu.Lock()
	if e.dnsCache != nil {
		if ent, ok := e.dnsCache[cacheKey]; ok && now.Before(ent.expiresAt) {
			e.dnsMu.Unlock()
			out := append([]byte(nil), ent.resp...)
			if len(out) >= 2 {
				out[0], out[1] = query[0], query[1]
			}
			return out, nil
		}
	}
	e.dnsMu.Unlock()

	target := "1.1.1.1:53"
	if dstHost != "" && dstHost != "127.0.0.1" && dstHost != "::1" {
		target = net.JoinHostPort(dstHost, "53")
	}

	type dialRes struct {
		c   net.Conn
		err error
	}
	dCh := make(chan dialRes, 1)
	go func() {
		rc, derr := e.client.Dial("tcp", target)
		if derr != nil && target != "1.1.1.1:53" {
			rc, derr = e.client.Dial("tcp", "1.1.1.1:53")
		}
		dCh <- dialRes{c: rc, err: derr}
	}()

	var rc net.Conn
	select {
	case <-e.ctx.Done():
		go func() {
			if r := <-dCh; r.c != nil {
				_ = r.c.Close()
			}
		}()
		return nil, e.ctx.Err()
	case <-time.After(8 * time.Second):
		go func() {
			if r := <-dCh; r.c != nil {
				_ = r.c.Close()
			}
		}()
		return nil, fmt.Errorf("dns dial timeout")
	case r := <-dCh:
		if r.err != nil {
			return nil, r.err
		}
		rc = r.c
	}
	defer rc.Close()
	_ = rc.SetDeadline(time.Now().Add(8 * time.Second))

	frame := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(frame[0:2], uint16(len(query)))
	copy(frame[2:], query)
	if _, err := rc.Write(frame); err != nil {
		return nil, err
	}
	bytesUp.Add(int64(len(frame)))

	var lenHdr [2]byte
	if _, err := io.ReadFull(rc, lenHdr[:]); err != nil {
		return nil, err
	}
	rlen := int(binary.BigEndian.Uint16(lenHdr[:]))
	if rlen < 12 || rlen > 4096 {
		return nil, fmt.Errorf("invalid dns tcp response length %d", rlen)
	}
	resp := make([]byte, rlen)
	if _, err := io.ReadFull(rc, resp); err != nil {
		return nil, err
	}
	bytesDown.Add(int64(2 + rlen))

	e.dnsMu.Lock()
	if e.dnsCache == nil {
		e.dnsCache = make(map[string]dnsCacheEntry, 128)
	}
	if len(e.dnsCache) > 512 {
		for k, v := range e.dnsCache {
			if now.After(v.expiresAt) || len(e.dnsCache) > 512 {
				delete(e.dnsCache, k)
			}
		}
	}
	e.dnsCache[cacheKey] = dnsCacheEntry{
		resp:      append([]byte(nil), resp...),
		expiresAt: time.Now().Add(30 * time.Second),
	}
	e.dnsMu.Unlock()
	return resp, nil
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
