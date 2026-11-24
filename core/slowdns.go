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

func parseSlowDNSType(raw string) (uint16, string) {
	switch strings.ToUpper(strings.TrimSpace(raw)) {
	case "NULL":
		return 10, "NULL"
	case "CNAME":
		return 5, "CNAME"
	case "NS":
		return 2, "NS"
	case "A":
		return 1, "A"
	case "MX":
		return 15, "MX"
	default:
		return dns.RRTypeTXT, "TXT"
	}
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

	rawType := strings.ToUpper(strings.TrimSpace(cfg.DNSType))
	if rawType == "" || rawType == "AUTO" {
		candidates := []string{"TXT", "NULL", "CNAME", "NS", "A", "MX"}
		logMsg("INFO", "SlowDNS (Otomatis): menguji tipe DNS %v ke %s via %s", candidates, ns, dnsHost)
		var lastErr error
		for _, candidate := range candidates {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			conn, dialErr := dialSlowDNSType(ctx, ns, domain, pubkey, dnsHost, remoteAddr, mtu, candidate, 6*time.Second, protector)
			if dialErr == nil {
				logMsg("INFO", "SlowDNS (Otomatis): terhubung menggunakan tipe %s", candidate)
				return conn, nil
			}
			lastErr = dialErr
			logMsg("WARN", "SlowDNS tipe %s gagal: %v", candidate, dialErr)
		}
		return nil, fmt.Errorf("SlowDNS otomatis gagal di semua tipe: %w", lastErr)
	}

	return dialSlowDNSType(ctx, ns, domain, pubkey, dnsHost, remoteAddr, mtu, rawType, 12*time.Second, protector)
}

func dialSlowDNSType(
	ctx context.Context,
	ns string,
	domain dns.Name,
	pubkey []byte,
	dnsHost string,
	remoteAddr *net.UDPAddr,
	mtu int,
	typeName string,
	handshakeTimeout time.Duration,
	protector SocketProtector,
) (net.Conn, error) {
	network := "udp4"
	if remoteAddr.IP != nil && remoteAddr.IP.To4() == nil {
		network = "udp6"
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

	udpConn, err := lc.ListenPacket(ctx, network, ":0")
	if err != nil {
		return nil, fmt.Errorf("buka UDP socket SlowDNS: %w", err)
	}

	rrType, rrLabel := parseSlowDNSType(typeName)
	logMsg("INFO", "SlowDNS (dnstt) dialing %s via DNS %s (Type=%s, MTU=%d)", ns, dnsHost, rrLabel, mtu)
	pconn := NewDNSPacketConn(udpConn, remoteAddr, domain, rrType)

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
	_ = kcpConn.SetDeadline(time.Now().Add(handshakeTimeout))

	// Ensure context cancellation or handshake timeout immediately unblocks Noise/smux.
	handshakeDone := make(chan struct{})
	go func() {
		timer := time.NewTimer(handshakeTimeout)
		defer timer.Stop()
		select {
		case <-handshakeDone:
		case <-ctx.Done():
			_ = kcpConn.Close()
			_ = pconn.Close()
			_ = udpConn.Close()
		case <-timer.C:
			_ = kcpConn.Close()
			_ = pconn.Close()
			_ = udpConn.Close()
		}
	}()

	rw, err := noise.NewClient(kcpConn, pubkey)
	if err != nil {
		close(handshakeDone)
		_ = kcpConn.Close()
		_ = pconn.Close()
		_ = udpConn.Close()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, fmt.Errorf("SlowDNS Noise handshake (%s): %w", rrLabel, err)
	}

	smuxCfg := smux.DefaultConfig()
	smuxCfg.Version = 2
	smuxCfg.KeepAliveTimeout = 2 * time.Minute
	smuxCfg.MaxStreamBuffer = 1 * 1024 * 1024

	sess, err := smux.Client(rw, smuxCfg)
	if err != nil {
		close(handshakeDone)
		_ = kcpConn.Close()
		_ = pconn.Close()
		_ = udpConn.Close()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, fmt.Errorf("SlowDNS smux init (%s): %w", rrLabel, err)
	}

	stream, err := sess.OpenStream()
	if err != nil {
		close(handshakeDone)
		_ = sess.Close()
		_ = kcpConn.Close()
		_ = pconn.Close()
		_ = udpConn.Close()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, fmt.Errorf("SlowDNS open stream (%s): %w", rrLabel, err)
	}

	close(handshakeDone)
	_ = kcpConn.SetDeadline(time.Time{})

	return &slowDNSConn{
		Stream:  stream,
		sess:    sess,
		kcpConn: kcpConn,
		pconn:   pconn,
		udpConn: udpConn,
	}, nil
}
