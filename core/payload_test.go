package core

import (
	"context"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestExpandPayloadPlaceholders(t *testing.T) {
	tpl := "GET / HTTP/1.1[crlf]Host: [host][crlf]X-SNI: [sni][crlf]X-Port: [port][crlf]X-Rot: [rotate=bug1.com;bug2.com][lfcr][split]CONNECT [host_port] [protocol][crlf][crlf][netData]"
	c1 := expandPayload(tpl, "ssh.example.com", 443, "bug.cdn.com")
	if len(c1) != 2 {
		t.Fatalf("expected 2 split chunks, got %d: %#v", len(c1), c1)
	}
	if !strings.Contains(c1[0], "Host: ssh.example.com\r\n") {
		t.Fatalf("expected [host] to stay targetHost, got %q", c1[0])
	}
	if !strings.Contains(c1[0], "X-SNI: bug.cdn.com\r\n") {
		t.Fatalf("expected [sni] to be bug.cdn.com, got %q", c1[0])
	}
	if !strings.Contains(c1[0], "X-Port: 443\r\n") {
		t.Fatalf("expected [port] to be 443, got %q", c1[0])
	}
	if !strings.Contains(c1[0], "X-Rot: bug1.com\n\r") {
		t.Fatalf("expected first rotate choice + [lfcr], got %q", c1[0])
	}
	if !strings.Contains(c1[1], "CONNECT ssh.example.com:443 HTTP/1.1\r\n\r\nCONNECT ssh.example.com:443 HTTP/1.1\r\nHost: ssh.example.com:443\r\n\r\n") {
		t.Fatalf("expected [netData] expansion in chunk 2, got %q", c1[1])
	}

	c2 := expandPayload(tpl, "ssh.example.com", 443, "bug.cdn.com")
	if !strings.Contains(c2[0], "X-Rot: bug2.com") {
		t.Fatalf("expected second rotate choice bug2.com, got %q", c2[0])
	}
}

func TestInjectHTTPPayloadAcceptsStatusWithoutReasonPhrase(t *testing.T) {
	if code, ok := parseHTTPStatusCode("HTTP/1.1 101"); !ok || code != 101 {
		t.Fatalf("expected HTTP/1.1 101 -> (101, true), got (%d, %v)", code, ok)
	}
	if code, ok := parseHTTPStatusCode("HTTP/1.0 200"); !ok || code != 200 {
		t.Fatalf("expected HTTP/1.0 200 -> (200, true), got (%d, %v)", code, ok)
	}

	for _, status := range []string{"HTTP/1.1 101\r\n\r\n", "HTTP/1.1 200\r\n\r\n"} {
		c1, c2 := net.Pipe()
		go func(resp string) {
			buf := make([]byte, 1024)
			_, _ = c2.Read(buf)
			_, _ = c2.Write([]byte(resp + "SSH-2.0-Dropbear"))
			_ = c2.Close()
		}(status)

		wrapped, err := injectHTTPPayload(c1, "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]", "1.2.3.4", 80, "")
		if err != nil {
			_ = c1.Close()
			t.Fatalf("expected status %q to be accepted, got error: %v", status, err)
		}
		tail, _ := io.ReadAll(wrapped)
		_ = wrapped.Close()
		if string(tail) != "SSH-2.0-Dropbear" {
			t.Fatalf("expected buffered SSH banner, got %q", string(tail))
		}
	}

	// 403 must still be rejected
	c1, c2 := net.Pipe()
	go func() {
		buf := make([]byte, 1024)
		_, _ = c2.Read(buf)
		_, _ = c2.Write([]byte("HTTP/1.1 403 Forbidden\r\n\r\n"))
		_ = c2.Close()
	}()
	if wrapped, err := injectHTTPPayload(c1, "", "1.2.3.4", 80, ""); err == nil {
		_ = wrapped.Close()
		t.Fatalf("expected 403 response to fail")
	}
	_ = c1.Close()
}

func TestSSHRawWSUpgradeFallback(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()

	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(cn net.Conn) {
				defer cn.Close()
				buf := make([]byte, 4096)
				n, rerr := cn.Read(buf)
				if rerr != nil || n == 0 {
					return
				}
				// Respond like Python ws-dropbear: 101 Switching Protocols WITHOUT Sec-WebSocket-Accept
				_, _ = cn.Write([]byte("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"))
				_, _ = io.Copy(cn, cn)
			}(c)
		}
	}()

	host, portStr, _ := net.SplitHostPort(ln.Addr().String())
	port, _ := strconv.Atoi(portStr)

	for _, rawMode := range []bool{true, false} {
		cfg := SSHConfig{
			Host:         host,
			Port:         port,
			Transport:    "ws",
			WSPath:       "/ws",
			WSHost:       "cdn.example.com",
			WSRawUpgrade: rawMode,
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		conn, err := dialSSHTransport(ctx, cfg, nil)
		cancel()
		if err != nil {
			t.Fatalf("dialSSHTransport(rawMode=%v) failed: %v", rawMode, err)
		}
		msg := fmt.Sprintf("hello-raw-ws-%v", rawMode)
		_, _ = conn.Write([]byte(msg))
		got := make([]byte, len(msg))
		if _, err := io.ReadFull(conn, got); err != nil {
			_ = conn.Close()
			t.Fatalf("echo ReadFull failed (rawMode=%v): %v", rawMode, err)
		}
		_ = conn.Close()
		if string(got) != msg {
			t.Fatalf("echo mismatch: got %q want %q", string(got), msg)
		}
	}
}

func TestHTTPProxySocksDeadline(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()

	// Accept connection and never respond
	done := make(chan struct{})
	defer close(done)
	go func() {
		c, aerr := ln.Accept()
		if aerr != nil {
			return
		}
		defer c.Close()
		<-done
	}()

	start := time.Now()
	_, err = dialViaSocks5AddrWithTimeout(ln.Addr().String(), "example.com:80", 250*time.Millisecond)
	if err == nil {
		t.Fatalf("expected timeout error from unresponsive SOCKS5 listener")
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("SOCKS5 handshake did not enforce deadline promptly: took %v", time.Since(start))
	}
}

func TestSSHRawWSRootPathSendsWSHost(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()

	reqCh := make(chan string, 1)
	go func() {
		c, aerr := ln.Accept()
		if aerr != nil {
			return
		}
		defer c.Close()
		buf := make([]byte, 4096)
		n, _ := c.Read(buf)
		reqCh <- string(buf[:n])
		_, _ = c.Write([]byte("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"))
		_, _ = io.Copy(c, c)
	}()

	host, portStr, _ := net.SplitHostPort(ln.Addr().String())
	port, _ := strconv.Atoi(portStr)
	cfg := SSHConfig{
		Host:         host,
		Port:         port,
		Transport:    "ws",
		WSPath:       "/",
		WSHost:       "cdn.root.example",
		Payload:      "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]",
		WSRawUpgrade: true,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, err := dialSSHTransport(ctx, cfg, nil)
	if err != nil {
		t.Fatalf("dialSSHTransport failed: %v", err)
	}
	_ = conn.Close()

	rawReq := <-reqCh
	if !strings.Contains(rawReq, "Host: cdn.root.example\r\n") {
		t.Fatalf("expected raw WS on / to send Host: cdn.root.example, got:\n%s", rawReq)
	}
}

func TestSSHUDPGWSeparateSessionsByDst(t *testing.T) {
	relay := newSSHUDPGWRelay(context.Background(), nil, 7300, nil)
	src := &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 50000}

	hdr1 := []byte{0, 0, 0, 1, 1, 1, 1, 1, 0x00, 0x50}
	hdr2 := []byte{0, 0, 0, 1, 8, 8, 4, 4, 0x01, 0xbb}
	id1, ok1 := relay.allocSession(src, hdr1, "1.1.1.1", 80)
	id2, ok2 := relay.allocSession(src, hdr2, "8.8.4.4", 443)
	if !ok1 || !ok2 {
		t.Fatalf("allocSession failed")
	}
	if id1 == id2 {
		t.Fatalf("expected distinct UDPGW connIDs for different destinations from same src, both got %d", id1)
	}
	id1Again, _ := relay.allocSession(src, hdr1, "1.1.1.1", 80)
	if id1Again != id1 {
		t.Fatalf("expected identical tuple to reuse connID %d, got %d", id1, id1Again)
	}
}
