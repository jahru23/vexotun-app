package core

import (
	"context"
	"fmt"
	"net"
	"strings"
	"syscall"
	"time"

	"github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"
	"www.bamsoftware.com/git/dnstt.git/dns"
	"www.bamsoftware.com/git/dnstt.git/noise"
	"www.bamsoftware.com/git/dnstt.git/turbotunnel"
)

type slowDNSConn struct {
	*smux.Stream
	sess    *smux.Session
	kcpConn *kcp.UDPSession
	pconn   net.PacketConn
	udpConn net.PacketConn
}

func (c *slowDNSConn) Close() error {
	var err error
	if c.Stream != nil {
		err = c.Stream.Close()
	}
	if c.sess != nil {
		_ = c.sess.Close()
	}
	if c.kcpConn != nil {
		_ = c.kcpConn.Close()
	}
	if c.pconn != nil {
		_ = c.pconn.Close()
	}
	if c.udpConn != nil {
		_ = c.udpConn.Close()
	}
	return err
}

func dnsNameCapacity(domain dns.Name) int {
	capacity := 255 - 1
	for _, label := range domain {
		capacity -= len(label) + 1
	}
	capacity = capacity * 63 / 64
	capacity = capacity * 5 / 8
	return capacity
}

func dialSlowDNS(ctx context.Context, cfg SSHConfig, protector SocketProtector) (net.Conn, error) {
	ns := strings.TrimSpace(cfg.Nameserver)
	pubHex := strings.TrimSpace(cfg.PublicKey)
	if ns == "" || pubHex == "" {
		return nil, fmt.Errorf("SlowDNS membutuhkan Nameserver (NS) dan Public Key")
	}

	domain, err := dns.ParseName(ns)
	if err != nil {
		return nil, fmt.Errorf("nameserver tidak valid (%s): %w", ns, err)
	}

	pubkey, err := noise.DecodeKey(pubHex)
	if err != nil {
		return nil, fmt.Errorf("public key tidak valid: %w", err)
	}

	dnsHost := strings.TrimSpace(cfg.DNSServer)
	if dnsHost == "" {
		dnsHost = "8.8.8.8"
	}
	if !strings.Contains(dnsHost, ":") {
		dnsHost = net.JoinHostPort(dnsHost, "53")
	}

	remoteAddr, err := net.ResolveUDPAddr("udp", dnsHost)
	if err != nil {
		return nil, fmt.Errorf("DNS resolver tidak valid (%s): %w", dnsHost, err)
	}

	mtu := dnsNameCapacity(domain) - 8 - 1 - numPadding - 1
	if mtu < 80 {
		return nil, fmt.Errorf("domain %s terlalu panjang (MTU=%d)", ns, mtu)
	}

	lc := net.ListenConfig{
		Control: func(network, address string, c syscall.RawConn) error {
			if protector == nil {
				return nil
			}
			var protectErr error
			if err := c.Control(func(fd uintptr) {
				if !protector.Protect(int32(fd)) {
					protectErr = fmt.Errorf("gagal protect UDP socket fd=%d", fd)
				}
			}); err != nil {
				return err
			}
			return protectErr
		},
	}

	udpConn, err := lc.ListenPacket(ctx, "udp", ":0")
	if err != nil {
		return nil, fmt.Errorf("buka UDP socket SlowDNS: %w", err)
	}

	logMsg("INFO", "SlowDNS (dnstt) dialing %s via DNS %s (MTU=%d)", ns, dnsHost, mtu)
	pconn := NewDNSPacketConn(udpConn, remoteAddr, domain)

	kcpConn, err := kcp.NewConn2(remoteAddr, nil, 0, 0, pconn)
	if err != nil {
		_ = pconn.Close()
		_ = udpConn.Close()
		return nil, fmt.Errorf("SlowDNS KCP init: %w", err)
	}
	kcpConn.SetStreamMode(true)
	kcpConn.SetNoDelay(0, 0, 0, 1)
	kcpConn.SetWindowSize(turbotunnel.QueueSize/2, turbotunnel.QueueSize/2)
	kcpConn.SetMtu(mtu)

	rw, err := noise.NewClient(kcpConn, pubkey)
	if err != nil {
		_ = kcpConn.Close()
		_ = pconn.Close()
		_ = udpConn.Close()
		return nil, fmt.Errorf("SlowDNS Noise handshake: %w", err)
	}

	smuxCfg := smux.DefaultConfig()
	smuxCfg.Version = 2
	smuxCfg.KeepAliveTimeout = 2 * time.Minute
	smuxCfg.MaxStreamBuffer = 1 * 1024 * 1024

	sess, err := smux.Client(rw, smuxCfg)
	if err != nil {
		_ = kcpConn.Close()
		_ = pconn.Close()
		_ = udpConn.Close()
		return nil, fmt.Errorf("SlowDNS smux init: %w", err)
	}

	stream, err := sess.OpenStream()
	if err != nil {
		_ = sess.Close()
		_ = kcpConn.Close()
		_ = pconn.Close()
		_ = udpConn.Close()
		return nil, fmt.Errorf("SlowDNS open stream: %w", err)
	}

	return &slowDNSConn{
		Stream:  stream,
		sess:    sess,
		kcpConn: kcpConn,
		pconn:   pconn,
		udpConn: udpConn,
	}, nil
}
