package core

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"testing"
	"time"

	vxcrypto "vexotun/core/vexdns/crypto"
	"vexotun/core/vexdns/dnswire"
	"vexotun/core/vexdns/protocol"
	vxtunnel "vexotun/core/vexdns/tunnel"
)

func TestDialSlowDNSVexAndScan(t *testing.T) {
	priv, _ := vxcrypto.GeneratePrivkey()
	pub, _ := vxcrypto.PubkeyFromPrivkey(priv)
	pubHex := vxcrypto.EncodeKey(pub)
	domain, _ := dnswire.ParseName("ns-rwb.prime.biz.id")

	echoLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("echo listen: %v", err)
	}
	defer echoLn.Close()
	go func() {
		for {
			c, err := echoLn.Accept()
			if err != nil {
				return
			}
			go func(cn net.Conn) {
				defer cn.Close()
				_, _ = io.Copy(cn, cn)
			}(c)
		}
	}()

	srv, err := vxtunnel.NewServer(vxtunnel.ServerConfig{
		ListenAddr:   "127.0.0.1:0",
		Domain:       domain,
		UpstreamAddr: echoLn.Addr().String(),
		Privkey:      priv,
		Pubkey:       pub,
		MaxUDP:       1232,
		CompatDnstt:  true,
		IdleTimeout:  15 * time.Second,
	}, nil)
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	srvCtx, srvCancel := context.WithCancel(context.Background())
	defer srvCancel()
	go func() { _ = srv.Serve(srvCtx) }()
	defer srv.Close()

	cfg := SSHConfig{
		Host:       "127.0.0.1",
		Port:       443,
		Transport:  "slowdns",
		DNSServer:  srv.LocalUDPAddr().String(),
		DNSType:    "AUTO",
		Nameserver: "ns-rwb.prime.biz.id",
		PublicKey:  pubHex,
	}

	rawCfg, _ := json.Marshal(cfg)
	scanOut := ScanSlowDNS(string(rawCfg), nil)
	var parsed struct {
		OK         bool   `json:"ok"`
		Error      string `json:"error"`
		Candidates []struct {
			QType string `json:"qtype"`
			MTU   int    `json:"mtu"`
		} `json:"candidates"`
	}
	if err := json.Unmarshal([]byte(scanOut), &parsed); err != nil || !parsed.OK || len(parsed.Candidates) == 0 {
		t.Fatalf("ScanSlowDNS failed: %s (%v)", scanOut, err)
	}

	conn, err := dialSlowDNS(context.Background(), cfg, nil)
	if err != nil {
		t.Fatalf("dialSlowDNS: %v", err)
	}
	defer conn.Close()

	want := []byte("vexotun-core-slowdns-echo-ok")
	if _, err := conn.Write(want); err != nil {
		t.Fatalf("conn.Write: %v", err)
	}
	got := make([]byte, len(want))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatalf("conn.ReadFull: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("echo mismatch: got %q want %q", got, want)
	}
}

// dnsttOnlyPacketConn wraps a UDP PacketConn and rejects VexDNS probes and non-TXT queries,
// simulating a standard third-party dnstt-server (TXT only, no VXP1/VXT1).
type dnsttOnlyPacketConn struct {
	net.PacketConn
	domain dnswire.Name
	pubkey []byte
}

func (d *dnsttOnlyPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	for {
		n, addr, err := d.PacketConn.ReadFrom(p)
		if err != nil {
			return 0, nil, err
		}
		msg, merr := dnswire.MessageFromWireFormat(p[:n])
		if merr != nil || len(msg.Question) == 0 {
			continue
		}
		q := msg.Question[0]
		if q.Type != dnswire.RRTypeTXT {
			continue
		}
		rawPayload, derr := dnswire.DecodeQueryName(q.Name, d.domain)
		if derr != nil {
			continue
		}
		if _, isProbe, _ := protocol.DecodeProbeRequest(d.pubkey, rawPayload); isProbe {
			continue
		}
		parsed, perr := protocol.DecodeQueryPayload(rawPayload)
		if perr != nil || parsed.Mode == protocol.ModeVexora {
			continue
		}
		return n, addr, nil
	}
}

func startDnsttOnlyTestServer(t *testing.T, domainStr string) (srvAddr string, pubHex string, cleanup func()) {
	t.Helper()
	priv, _ := vxcrypto.GeneratePrivkey()
	pub, _ := vxcrypto.PubkeyFromPrivkey(priv)
	pubHex = vxcrypto.EncodeKey(pub)
	domain, _ := dnswire.ParseName(domainStr)

	echoLn, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("echo listen: %v", err)
	}
	go func() {
		for {
			c, aerr := echoLn.Accept()
			if aerr != nil {
				return
			}
			go func(cn net.Conn) {
				defer cn.Close()
				_, _ = io.Copy(cn, cn)
			}(c)
		}
	}()

	rawUDP, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		_ = echoLn.Close()
		t.Fatalf("udp listen: %v", err)
	}
	filtered := &dnsttOnlyPacketConn{PacketConn: rawUDP, domain: domain, pubkey: pub}

	srv, err := vxtunnel.NewServer(vxtunnel.ServerConfig{
		ListenAddr:   rawUDP.LocalAddr().String(),
		Domain:       domain,
		UpstreamAddr: echoLn.Addr().String(),
		Privkey:      priv,
		Pubkey:       pub,
		MaxUDP:       1232,
		CompatDnstt:  true,
		IdleTimeout:  15 * time.Second,
	}, filtered)
	if err != nil {
		_ = rawUDP.Close()
		_ = echoLn.Close()
		t.Fatalf("NewServer: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() { _ = srv.Serve(ctx) }()

	return rawUDP.LocalAddr().String(), pubHex, func() {
		cancel()
		_ = srv.Close()
		_ = echoLn.Close()
	}
}

func TestDialSlowDNSModeDnsttParallelResolvers(t *testing.T) {
	liveAddr, pubHex, stopSrv := startDnsttOnlyTestServer(t, "ns-thirdparty.example.com")
	defer stopSrv()

	// Dead local UDP port as first resolver (previously blocked dialSlowDNSLegacy completely!).
	deadConn, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("dead listen: %v", err)
	}
	deadAddr := deadConn.LocalAddr().String()
	_ = deadConn.Close()

	cfg := SSHConfig{
		Host:        "127.0.0.1",
		Port:        22,
		Transport:   "slowdns",
		SlowDNSMode: "dnstt",
		DNSServer:   fmt.Sprintf("%s,%s", deadAddr, liveAddr),
		DNSType:     "AUTO",
		Nameserver:  "ns-thirdparty.example.com",
		PublicKey:   pubHex,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	conn, err := dialSlowDNS(ctx, cfg, nil)
	if err != nil {
		t.Fatalf("expected parallel dnstt dial to succeed via second resolver (%s), got: %v", liveAddr, err)
	}
	defer conn.Close()

	want := []byte("thirdparty-dnstt-ok")
	if _, err := conn.Write(want); err != nil {
		t.Fatalf("conn.Write: %v", err)
	}
	got := make([]byte, len(want))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatalf("conn.ReadFull: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("echo mismatch: got %q want %q", got, want)
	}
}

func TestScanSlowDNSDnsttCompat(t *testing.T) {
	liveAddr, pubHex, stopSrv := startDnsttOnlyTestServer(t, "ns-thirdparty.example.com")
	defer stopSrv()

	cfg := SSHConfig{
		Host:        "127.0.0.1",
		Port:        22,
		Transport:   "slowdns",
		SlowDNSMode: "auto",
		DNSServer:   liveAddr,
		DNSType:     "AUTO",
		Nameserver:  "ns-thirdparty.example.com",
		PublicKey:   pubHex,
	}

	rawCfg, _ := json.Marshal(cfg)
	scanOut := ScanSlowDNS(string(rawCfg), nil)
	var parsed struct {
		OK         bool   `json:"ok"`
		Error      string `json:"error"`
		Candidates []struct {
			Resolver string `json:"resolver"`
			QType    string `json:"qtype"`
			Mode     string `json:"mode"`
			MTU      int    `json:"mtu"`
		} `json:"candidates"`
	}
	if err := json.Unmarshal([]byte(scanOut), &parsed); err != nil {
		t.Fatalf("unmarshal scanOut %s: %v", scanOut, err)
	}
	if !parsed.OK || len(parsed.Candidates) == 0 {
		t.Fatalf("expected dnstt fallback scan to find candidate on %s, got: %s", liveAddr, scanOut)
	}
	if parsed.Candidates[0].Mode != "dnstt" || parsed.Candidates[0].QType != "TXT" {
		t.Fatalf("expected first candidate mode=dnstt qtype=TXT, got: %+v", parsed.Candidates[0])
	}
}
