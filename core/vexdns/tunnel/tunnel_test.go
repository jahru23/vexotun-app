package tunnel

import (
	"bytes"
	"context"
	"crypto/rand"
	"fmt"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"

	vxcrypto "vexotun/core/vexdns/crypto"
	"vexotun/core/vexdns/dnswire"
	"vexotun/core/vexdns/protocol"
)

func startEchoUpstream(t *testing.T) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen tcp: %v", err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}(c)
		}
	}()
	t.Cleanup(func() { _ = ln.Close() })
	return ln
}

func startTestServer(t *testing.T, domain dnswire.Name, upstreamAddr string, priv, pub []byte) *Server {
	t.Helper()
	srv, err := NewServer(ServerConfig{
		ListenAddr:   "127.0.0.1:0",
		Domain:       domain,
		UpstreamAddr: upstreamAddr,
		Privkey:      priv,
		Pubkey:       pub,
		MaxUDP:       1232,
		CompatDnstt:  true,
		IdleTimeout:  30 * time.Second,
		KeepAlive:    5 * time.Second,
		Debug:        true,
	}, nil)
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		_ = srv.Serve(ctx)
	}()
	t.Cleanup(func() {
		cancel()
		_ = srv.Close()
	})
	return srv
}

func verifyEchoOverClient(t *testing.T, cli *Client, payloadSize int) {
	t.Helper()
	stream, err := cli.OpenStream()
	if err != nil {
		t.Fatalf("OpenStream: %v", err)
	}
	defer stream.Close()

	want := make([]byte, payloadSize)
	for i := range want {
		want[i] = byte((i*73 + 19) & 0xff)
	}

	errCh := make(chan error, 1)
	go func() {
		_, werr := stream.Write(want)
		errCh <- werr
	}()

	got := make([]byte, payloadSize)
	if _, err := io.ReadFull(stream, got); err != nil {
		t.Fatalf("ReadFull(%dB): %v", payloadSize, err)
	}
	if werr := <-errCh; werr != nil {
		t.Fatalf("Write(%dB): %v", payloadSize, werr)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("echo mismatch for %d bytes", payloadSize)
	}
}

func TestDualModeAndMultiMTUEcho(t *testing.T) {
	priv, _ := vxcrypto.GeneratePrivkey()
	pub, _ := vxcrypto.PubkeyFromPrivkey(priv)
	domain, _ := dnswire.ParseName("ns-rwb.prime.biz.id")
	echoLn := startEchoUpstream(t)
	srv := startTestServer(t, domain, echoLn.Addr().String(), priv, pub)
	dnsAddr := srv.LocalUDPAddr().String()

	cases := []struct {
		name       string
		qtype      uint16
		downBudget uint16
		maxQuery   int
		size       int
	}{
		{"HTTPS_505_IndosatLike", dnswire.RRTypeHTTPS, 505, 225, 32 * 1024},
		{"TXT_1232_Full", dnswire.RRTypeTXT, 1232, 0, 32 * 1024},
		{"A_505_LowMTU", dnswire.RRTypeA, 505, 220, 4 * 1024},
		{"AAAA_420_Tight", dnswire.RRTypeAAAA, 420, 0, 8 * 1024},
	}

	var wg sync.WaitGroup
	for _, tc := range cases {
		tc := tc
		wg.Add(1)
		go func() {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			cli, err := DialClient(ctx, ClientConfig{
				DNSServerAddr: dnsAddr,
				Domain:        domain,
				Pubkey:        pub,
				QType:         tc.qtype,
				DownBudget:    tc.downBudget,
				MaxQueryUDP:   tc.maxQuery,
			})
			if err != nil {
				t.Errorf("%s DialClient: %v", tc.name, err)
				return
			}
			defer cli.Close()
			verifyEchoOverClient(t, cli, tc.size)
		}()
	}

	// Also run a standard dnstt-compat 8-byte client simultaneously against the exact same server port!
	wg.Add(1)
	go func() {
		defer wg.Done()
		runCompatClientTest(t, dnsAddr, domain, pub)
	}()

	wg.Wait()
}

func runCompatClientTest(t *testing.T, dnsAddr string, domain dnswire.Name, pub []byte) {
	t.Helper()
	udpConn, err := net.ListenPacket("udp", ":0")
	if err != nil {
		t.Errorf("compat listen: %v", err)
		return
	}
	defer udpConn.Close()
	rAddr, _ := net.ResolveUDPAddr("udp", dnsAddr)

	var cid [8]byte
	_, _ = rand.Read(cid[:])
	peerAddr := protocol.ClientAddr{Mode: protocol.ModeCompat, ID: cid}
	tt := NewQueuePacketConn(&dummyAddr{s: "compat-cli"}, 15*time.Second, 256)
	defer tt.Close()

	kcpConn, err := kcp.NewConn2(peerAddr, nil, 0, 0, tt)
	if err != nil {
		t.Errorf("compat NewConn2: %v", err)
		return
	}
	defer kcpConn.Close()
	kcpConn.SetStreamMode(true)
	kcpConn.SetNoDelay(1, 20, 2, 1)
	kcpConn.SetMtu(100)

	stop := make(chan struct{})
	defer close(stop)

	go func() {
		buf := make([]byte, 4096)
		for {
			n, _, err := udpConn.ReadFrom(buf)
			if err != nil {
				return
			}
			msg, err := dnswire.MessageFromWireFormat(buf[:n])
			if err != nil || len(msg.Answer) == 0 {
				continue
			}
			raw, err := dnswire.DecodeResponseRRs(&msg, dnswire.RRTypeTXT, domain)
			if err != nil {
				continue
			}
			pkts, err := protocol.UnpackDownstreamFrames(raw)
			if err != nil {
				continue
			}
			for _, p := range pkts {
				tt.QueueIncoming(p, peerAddr)
			}
		}
	}()

	go func() {
		var id uint16 = 1
		for {
			select {
			case <-stop:
				return
			default:
			}
			pkts := tt.PopUpstreamUpTo(peerAddr, 110)
			var body bytes.Buffer
			body.Write(cid[:])
			if len(pkts) == 0 {
				body.WriteByte(224 + 8)
				var pad [8]byte
				_, _ = rand.Read(pad[:])
				body.Write(pad[:])
			} else {
				body.WriteByte(224 + 3)
				var pad [3]byte
				_, _ = rand.Read(pad[:])
				body.Write(pad[:])
				for _, p := range pkts {
					body.WriteByte(byte(len(p)))
					body.Write(p)
				}
			}
			qn, err := dnswire.EncodeQueryName(body.Bytes(), domain)
			if err != nil {
				return
			}
			id++
			msg := dnswire.Message{
				ID:         id,
				Flags:      0x0100,
				Question:   []dnswire.Question{{Name: qn, Type: dnswire.RRTypeTXT, Class: dnswire.ClassIN}},
				Additional: []dnswire.RR{{Type: dnswire.RRTypeOPT, Class: 1232}},
			}
			wire, _ := msg.WireFormat()
			_, _ = udpConn.WriteTo(wire, rAddr)
			if len(pkts) == 0 {
				time.Sleep(25 * time.Millisecond)
			}
		}
	}()

	rw, err := vxcrypto.WrapClient(kcpConn, pub)
	if err != nil {
		t.Errorf("compat Noise: %v", err)
		return
	}
	defer rw.Close()
	sc := smux.DefaultConfig()
	sc.Version = 2
	sess, err := smux.Client(rw, sc)
	if err != nil {
		t.Errorf("compat smux: %v", err)
		return
	}
	defer sess.Close()
	st, err := sess.OpenStream()
	if err != nil {
		t.Errorf("compat OpenStream: %v", err)
		return
	}
	msg := []byte(fmt.Sprintf("compat-hello-%d", time.Now().UnixNano()))
	_, _ = st.Write(msg)
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(st, got); err != nil {
		t.Errorf("compat ReadFull: %v", err)
		return
	}
	if !bytes.Equal(got, msg) {
		t.Errorf("compat mismatch: got %q want %q", got, msg)
	}
}

func TestStatelessProbeOverUDP(t *testing.T) {
	priv, _ := vxcrypto.GeneratePrivkey()
	pub, _ := vxcrypto.PubkeyFromPrivkey(priv)
	domain, _ := dnswire.ParseName("ns-biz.prime.biz.id")
	srv := startTestServer(t, domain, "127.0.0.1:1", priv, pub)

	c, err := net.ListenPacket("udp", ":0")
	if err != nil {
		t.Fatalf("ListenPacket: %v", err)
	}
	defer c.Close()

	// 1) Valid probe asking for 505B HTTPS reply with 210B query wire size.
	raw, req, err := protocol.EncodeProbeRequest(pub, 505, 110)
	if err != nil {
		t.Fatalf("EncodeProbeRequest: %v", err)
	}
	qn, err := dnswire.EncodeQueryName(raw, domain)
	if err != nil {
		t.Fatalf("EncodeQueryName: %v", err)
	}
	q := dnswire.Message{
		ID:         77,
		Flags:      0x0100,
		Question:   []dnswire.Question{{Name: qn, Type: dnswire.RRTypeHTTPS, Class: dnswire.ClassIN}},
		Additional: []dnswire.RR{{Type: dnswire.RRTypeOPT, Class: 1232}},
	}
	wire, _ := q.WireFormat()
	_, _ = c.WriteTo(wire, srv.LocalUDPAddr())

	buf := make([]byte, 2048)
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := c.ReadFrom(buf)
	if err != nil {
		t.Fatalf("ReadFrom valid probe: %v", err)
	}
	if n > 505 || n < 450 {
		t.Fatalf("expected valid probe reply sized near 505B, got %dB", n)
	}
	respMsg, err := dnswire.MessageFromWireFormat(buf[:n])
	if err != nil {
		t.Fatalf("MessageFromWireFormat: %v", err)
	}
	respPayload, err := dnswire.DecodeResponseRRs(&respMsg, dnswire.RRTypeHTTPS, domain)
	if err != nil {
		t.Fatalf("DecodeResponseRRs: %v", err)
	}
	if _, err := protocol.DecodeProbeResponse(pub, req.Nonce, dnswire.RRTypeHTTPS, respPayload); err != nil {
		t.Fatalf("DecodeProbeResponse: %v", err)
	}

	// 2) Invalid-pubkey probe must receive NO reply (anti-amplification).
	wrongPub := bytes.Repeat([]byte{0x11}, 32)
	badRaw, _, _ := protocol.EncodeProbeRequest(wrongPub, 1200, 18)
	badQN, _ := dnswire.EncodeQueryName(badRaw, domain)
	badMsg := dnswire.Message{
		ID:       88,
		Flags:    0x0100,
		Question: []dnswire.Question{{Name: badQN, Type: dnswire.RRTypeTXT, Class: dnswire.ClassIN}},
	}
	badWire, _ := badMsg.WireFormat()
	_, _ = c.WriteTo(badWire, srv.LocalUDPAddr())
	_ = c.SetReadDeadline(time.Now().Add(150 * time.Millisecond))
	if _, _, err := c.ReadFrom(buf); err == nil {
		t.Fatalf("expected no response for unauthenticated probe")
	}
}
