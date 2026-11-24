package core

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

var httpProxyBufPool = sync.Pool{
	New: func() any {
		b := make([]byte, 32*1024)
		return &b
	},
}

type localHTTPProxy struct {
	server    *http.Server
	socksAddr string
}

func startLocalHTTPProxy(listenHost string, httpPort int, socksPort int) (*localHTTPProxy, error) {
	addr := net.JoinHostPort(listenHost, strconv.Itoa(httpPort))
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("listen local HTTP proxy %s: %w", addr, err)
	}

	p := &localHTTPProxy{
		socksAddr: fmt.Sprintf("127.0.0.1:%d", socksPort),
	}
	p.server = &http.Server{
		Handler:           p,
		ReadHeaderTimeout: 15 * time.Second,
	}

	go func() {
		if err := p.server.Serve(ln); err != nil && err != http.ErrServerClosed {
			logMsg("WARN", "HTTP proxy stopped: %v", err)
		}
	}()

	logMsg("INFO", "Hotspot HTTP proxy active on %s", addr)
	return p, nil
}

func (p *localHTTPProxy) Stop() {
	if p.server != nil {
		_ = p.server.Close()
	}
}

func (p *localHTTPProxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if strings.EqualFold(r.Method, http.MethodConnect) {
		p.handleConnect(w, r)
		return
	}
	p.handleHTTP(w, r)
}

func (p *localHTTPProxy) handleConnect(w http.ResponseWriter, r *http.Request) {
	target := r.Host
	if !strings.Contains(target, ":") {
		target = net.JoinHostPort(target, "443")
	}
	remote, err := p.dialViaSocks5(target)
	if err != nil {
		http.Error(w, "Bad Gateway", http.StatusBadGateway)
		return
	}

	hj, ok := w.(http.Hijacker)
	if !ok {
		_ = remote.Close()
		http.Error(w, "Hijack unsupported", http.StatusInternalServerError)
		return
	}
	clientConn, _, err := hj.Hijack()
	if err != nil {
		_ = remote.Close()
		return
	}

	_, _ = io.WriteString(clientConn, "HTTP/1.1 200 Connection Established\r\n\r\n")
	if tc, ok := clientConn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}

	var wg sync.WaitGroup
	wg.Add(2)
	pipe := func(dst net.Conn, src net.Conn) {
		defer wg.Done()
		bufPtr := httpProxyBufPool.Get().(*[]byte)
		_, _ = io.CopyBuffer(dst, src, *bufPtr)
		httpProxyBufPool.Put(bufPtr)
		if tc, ok := dst.(*net.TCPConn); ok {
			_ = tc.CloseWrite()
		}
	}
	go pipe(remote, clientConn)
	go pipe(clientConn, remote)
	wg.Wait()
	_ = remote.Close()
	_ = clientConn.Close()
}

func (p *localHTTPProxy) handleHTTP(w http.ResponseWriter, r *http.Request) {
	target := r.Host
	if target == "" && r.URL != nil {
		target = r.URL.Host
	}
	if target == "" {
		http.Error(w, "Missing Host", http.StatusBadRequest)
		return
	}
	if !strings.Contains(target, ":") {
		target = net.JoinHostPort(target, "80")
	}

	remote, err := p.dialViaSocks5(target)
	if err != nil {
		http.Error(w, "Bad Gateway", http.StatusBadGateway)
		return
	}
	defer remote.Close()

	outReq := r.Clone(r.Context())
	outReq.RequestURI = ""
	outReq.Header.Del("Proxy-Connection")
	outReq.Header.Del("Proxy-Authenticate")
	outReq.Header.Del("Proxy-Authorization")

	if err := outReq.Write(remote); err != nil {
		http.Error(w, "Bad Gateway", http.StatusBadGateway)
		return
	}

	resp, err := http.ReadResponse(bufio.NewReader(remote), outReq)
	if err != nil {
		http.Error(w, "Bad Gateway", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	for k, vals := range resp.Header {
		for _, v := range vals {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	bufPtr := httpProxyBufPool.Get().(*[]byte)
	_, _ = io.CopyBuffer(w, resp.Body, *bufPtr)
	httpProxyBufPool.Put(bufPtr)
}

func (p *localHTTPProxy) dialViaSocks5(target string) (net.Conn, error) {
	host, portStr, err := net.SplitHostPort(target)
	if err != nil {
		return nil, err
	}
	portNum, err := strconv.Atoi(portStr)
	if err != nil || portNum <= 0 || portNum > 65535 {
		return nil, fmt.Errorf("invalid port: %s", portStr)
	}

	conn, err := net.DialTimeout("tcp", p.socksAddr, 10*time.Second)
	if err != nil {
		return nil, err
	}
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}

	// SOCKS5 greeting: version 5, 1 auth method, no-auth (0x00)
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		_ = conn.Close()
		return nil, err
	}
	var authResp [2]byte
	if _, err := io.ReadFull(conn, authResp[:]); err != nil || authResp[0] != 0x05 || authResp[1] != 0x00 {
		_ = conn.Close()
		return nil, fmt.Errorf("socks5 auth failed")
	}

	req := make([]byte, 0, 7+len(host))
	req = append(req, 0x05, 0x01, 0x00) // VER, CMD=CONNECT, RSV
	if ip := net.ParseIP(host); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			req = append(req, 0x01)
			req = append(req, ip4...)
		} else {
			req = append(req, 0x04)
			req = append(req, ip.To16()...)
		}
	} else {
		if len(host) > 255 {
			_ = conn.Close()
			return nil, fmt.Errorf("hostname too long")
		}
		req = append(req, 0x03, byte(len(host)))
		req = append(req, host...)
	}
	var portBytes [2]byte
	binary.BigEndian.PutUint16(portBytes[:], uint16(portNum))
	req = append(req, portBytes[:]...)

	if _, err := conn.Write(req); err != nil {
		_ = conn.Close()
		return nil, err
	}

	var replyHdr [4]byte
	if _, err := io.ReadFull(conn, replyHdr[:]); err != nil {
		_ = conn.Close()
		return nil, err
	}
	if replyHdr[1] != 0x00 {
		_ = conn.Close()
		return nil, fmt.Errorf("socks5 connect error code=%d", replyHdr[1])
	}

	switch replyHdr[3] {
	case 0x01:
		var discard [6]byte
		_, err = io.ReadFull(conn, discard[:])
	case 0x04:
		var discard [18]byte
		_, err = io.ReadFull(conn, discard[:])
	case 0x03:
		var lenBuf [1]byte
		if _, err = io.ReadFull(conn, lenBuf[:]); err == nil {
			discard := make([]byte, int(lenBuf[0])+2)
			_, err = io.ReadFull(conn, discard)
		}
	}
	if err != nil {
		_ = conn.Close()
		return nil, err
	}

	return conn, nil
}
