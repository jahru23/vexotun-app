package core

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"testing"
	"time"

	vxcrypto "vexotun/core/vexdns/crypto"
	"vexotun/core/vexdns/dnswire"
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
