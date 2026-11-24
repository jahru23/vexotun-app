package solver

import (
	"bytes"
	"context"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	vxcrypto "vexotun/core/vexdns/crypto"
	"vexotun/core/vexdns/dnswire"
	"vexotun/core/vexdns/tunnel"
)

type carrierRuleFunc func(qtype uint16, queryLen, respLen int) bool

// startCarrierProxy starts an in-process UDP DNS proxy enforcing carrier DPI/MTU rules.
func startCarrierProxy(t *testing.T, target string, allow carrierRuleFunc) string {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("proxy listen: %v", err)
	}
	tAddr, _ := net.ResolveUDPAddr("udp", target)

	var mu sync.Mutex
	var stopOnce sync.Once
	stop := make(chan struct{})

	go func() {
		buf := make([]byte, 4096)
		for {
			n, cliAddr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			pkt := append([]byte(nil), buf[:n]...)
			msg, merr := dnswire.MessageFromWireFormat(pkt)
			if merr != nil || len(msg.Question) != 1 {
				continue
			}
			qt := msg.Question[0].Type
			if !allow(qt, len(pkt), 0) {
				continue
			}
			go func(qPkt []byte, qType uint16, qLen int, cAddr net.Addr) {
				up, err := net.ListenPacket("udp", "127.0.0.1:0")
				if err != nil {
					return
				}
				defer up.Close()
				_ = up.SetDeadline(time.Now().Add(900 * time.Millisecond))
				_, _ = up.WriteTo(qPkt, tAddr)
				rbuf := make([]byte, 4096)
				rn, _, rerr := up.ReadFrom(rbuf)
				if rerr != nil {
					return
				}
				mu.Lock()
				ok := allow(qType, qLen, rn)
				mu.Unlock()
				if ok {
					_, _ = pc.WriteTo(rbuf[:rn], cAddr)
				}
			}(pkt, qt, len(pkt), cliAddr)
		}
	}()

	t.Cleanup(func() {
		stopOnce.Do(func() { close(stop) })
		_ = pc.Close()
	})
	return pc.LocalAddr().String()
}

func startTestServerAndEcho(t *testing.T) (dnsAddr string, domain dnswire.Name, pub []byte, echoAddr string) {
	t.Helper()
	priv, _ := vxcrypto.GeneratePrivkey()
	pub, _ = vxcrypto.PubkeyFromPrivkey(priv)
	domain, _ = dnswire.ParseName("ns-biz.prime.biz.id")

	echoLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("echo listen: %v", err)
	}
	go func() {
		for {
			c, err := echoLn.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}(c)
		}
	}()
	t.Cleanup(func() { _ = echoLn.Close() })

	srv, err := tunnel.NewServer(tunnel.ServerConfig{
		ListenAddr:   "127.0.0.1:0",
		Domain:       domain,
		UpstreamAddr: echoLn.Addr().String(),
		Privkey:      priv,
		Pubkey:       pub,
		MaxUDP:       1232,
		CompatDnstt:  true,
		IdleTimeout:  20 * time.Second,
	}, nil)
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = srv.Serve(ctx) }()
	t.Cleanup(func() {
		cancel()
		_ = srv.Close()
	})
	return srv.LocalUDPAddr().String(), domain, pub, echoLn.Addr().String()
}

func TestSolverIndosatZeroQuotaSimulation(t *testing.T) {
	serverUDP, domain, pub, _ := startTestServerAndEcho(t)

	// Replicate observed Indosat 0-kuota behavior:
	// - TXT/CNAME/MX/NS/SRV/NULL/CAA allow <=85B query polls but drop >85B data queries
	// - A allows long queries, but drops responses >300B
	// - HTTPS & SVCB allow long queries and responses <=505B (and drop >=512B)
	indosatProxy := startCarrierProxy(t, serverUDP, func(qt uint16, qLen, rLen int) bool {
		switch qt {
		case dnswire.RRTypeHTTPS, dnswire.RRTypeSVCB:
			if qLen > 245 {
				return false
			}
			return rLen <= 505
		case dnswire.RRTypeA:
			return qLen <= 245 && rLen <= 300
		default:
			return qLen <= 85 && rLen <= 505
		}
	})

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	cands, err := ProbeAll(ctx, Config{
		Resolvers:    []string{indosatProxy},
		Domain:       domain,
		Pubkey:       pub,
		ProbeTimeout: 700 * time.Millisecond,
	})
	if err != nil {
		t.Fatalf("ProbeAll Indosat: %v", err)
	}
	top := cands[0]
	if (top.QType != dnswire.RRTypeHTTPS && top.QType != dnswire.RRTypeSVCB) || top.DownBudget != 505 {
		t.Fatalf("expected HTTPS or SVCB @ 505B, got %s @ %dB (all=%+v)", top.QTypeName, top.DownBudget, cands)
	}

	// Verify actual tunnel dial + TCP stream works over top candidate!
	cli, err := tunnel.DialClient(ctx, tunnel.ClientConfig{
		DNSServerAddr: top.Resolver,
		Domain:        domain,
		Pubkey:        pub,
		QType:         top.QType,
		DownBudget:    uint16(top.DownBudget),
		MaxQueryUDP:   top.MaxQueryUDP,
	})
	if err != nil {
		t.Fatalf("DialClient solved Indosat path: %v", err)
	}
	defer cli.Close()

	st, err := cli.OpenStream()
	if err != nil {
		t.Fatalf("OpenStream: %v", err)
	}
	defer st.Close()
	payload := bytes.Repeat([]byte("INDOSAT0KUOTA-OK-"), 256)
	go func() { _, _ = st.Write(payload) }()
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(st, got); err != nil {
		t.Fatalf("ReadFull: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("payload mismatch over simulated Indosat path")
	}
}

func TestSolverStrictAAAAOnlyCarrierSimulation(t *testing.T) {
	serverUDP, domain, pub, _ := startTestServerAndEcho(t)

	strictProxy := startCarrierProxy(t, serverUDP, func(qt uint16, qLen, rLen int) bool {
		if qt != dnswire.RRTypeAAAA {
			return false
		}
		return qLen <= 165 && rLen <= 420
	})

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	cands, err := ProbeAll(ctx, Config{
		Resolvers:    []string{strictProxy},
		Domain:       domain,
		Pubkey:       pub,
		ProbeTimeout: 600 * time.Millisecond,
	})
	if err != nil {
		t.Fatalf("ProbeAll strict AAAA: %v", err)
	}
	top := cands[0]
	if top.QType != dnswire.RRTypeAAAA || top.DownBudget > 420 || top.MaxQueryUDP != 165 {
		t.Fatalf("expected AAAA @ <=420B with queryCap=165, got %+v", top)
	}

	cli, err := tunnel.DialClient(ctx, tunnel.ClientConfig{
		DNSServerAddr: top.Resolver,
		Domain:        domain,
		Pubkey:        pub,
		QType:         top.QType,
		DownBudget:    uint16(top.DownBudget),
		MaxQueryUDP:   top.MaxQueryUDP,
	})
	if err != nil {
		t.Fatalf("DialClient strict AAAA: %v", err)
	}
	defer cli.Close()

	st, err := cli.OpenStream()
	if err != nil {
		t.Fatalf("OpenStream: %v", err)
	}
	defer st.Close()
	msg := []byte("strict-carrier-aaaa-420-ok")
	_, _ = st.Write(msg)
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(st, got); err != nil {
		t.Fatalf("ReadFull: %v", err)
	}
	if !bytes.Equal(got, msg) {
		t.Fatalf("got %q want %q", got, msg)
	}
}
