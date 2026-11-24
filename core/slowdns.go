package core

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"strings"
	"syscall"
	"time"

	vayclient "github.com/net2share/vaydns/client"
)

type vaydnsConn struct {
	net.Conn
	tunnel *vayclient.Tunnel
}

func (c *vaydnsConn) Close() error {
	var err error
	if c.Conn != nil {
		err = c.Conn.Close()
	}
	if c.tunnel != nil {
		_ = c.tunnel.Close()
	}
	return err
}

func normalizeVaydnsRecordType(raw string) string {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "txt", "null", "ns", "cname", "mx", "srv", "a", "aaaa", "caa":
		return strings.ToLower(strings.TrimSpace(raw))
	default:
		return "txt"
	}
}

func buildResolver(cfg SSHConfig, protector SocketProtector) (vayclient.Resolver, string, error) {
	transport := strings.ToLower(strings.TrimSpace(cfg.DNSTransport))
	addr := strings.TrimSpace(cfg.DNSServer)

	var rType vayclient.ResolverType
	switch transport {
	case "doh":
		rType = vayclient.ResolverTypeDOH
		if addr == "" {
			addr = "https://dns.google/dns-query"
		} else if !strings.HasPrefix(addr, "http://") && !strings.HasPrefix(addr, "https://") {
			addr = "https://" + addr + "/dns-query"
		}
	case "dot":
		rType = vayclient.ResolverTypeDOT
		if addr == "" {
			addr = "dns.google:853"
		} else if !strings.Contains(addr, ":") {
			addr = net.JoinHostPort(addr, "853")
		}
	default:
		rType = vayclient.ResolverTypeUDP
		if addr == "" {
			addr = "8.8.8.8:53"
		} else if !strings.Contains(addr, ":") {
			addr = net.JoinHostPort(addr, "53")
		}
	}

	resolver, err := vayclient.NewResolver(rType, addr)
	if err != nil {
		return vayclient.Resolver{}, "", err
	}

	resolver.DialerControl = func(network, address string, c syscall.RawConn) error {
		if protector == nil {
			return nil
		}
		var protectErr error
		if err := c.Control(func(fd uintptr) {
			if !protector.Protect(int32(fd)) {
				protectErr = fmt.Errorf("protect socket fd=%d failed", fd)
			}
		}); err != nil {
			return err
		}
		return protectErr
	}

	if rType == vayclient.ResolverTypeDOH {
		pd := newProtectedDialer(protector, 15*time.Second)
		resolver.RoundTripper = &http.Transport{
			DialContext:           pd.DialContext,
			ForceAttemptHTTP2:     true,
			MaxIdleConns:          10,
			IdleConnTimeout:       30 * time.Second,
			TLSHandshakeTimeout:   10 * time.Second,
			ExpectContinueTimeout: 1 * time.Second,
		}
	}

	return resolver, addr, nil
}

func dialSlowDNS(ctx context.Context, cfg SSHConfig, protector SocketProtector) (net.Conn, error) {
	ns := strings.TrimSpace(cfg.Nameserver)
	pubHex := strings.TrimSpace(cfg.PublicKey)
	if ns == "" || pubHex == "" {
		return nil, fmt.Errorf("SlowDNS membutuhkan Nameserver (NS) dan Public Key")
	}

	resolver, resolvedAddr, err := buildResolver(cfg, protector)
	if err != nil {
		return nil, fmt.Errorf("SlowDNS resolver: %w", err)
	}

	ts, err := vayclient.NewTunnelServer(ns, pubHex)
	if err != nil {
		return nil, fmt.Errorf("SlowDNS server config: %w", err)
	}

	dnsMode := strings.ToLower(strings.TrimSpace(cfg.DNSMode))
	isVaydns := dnsMode == "vaydns"
	if isVaydns {
		ts.DnsttCompat = false
		ts.RecordType = normalizeVaydnsRecordType(cfg.DNSType)
	} else {
		dnsMode = "dnstt"
		ts.DnsttCompat = true
		ts.RecordType = "txt"
	}

	t, err := vayclient.NewTunnel(resolver, ts)
	if err != nil {
		return nil, fmt.Errorf("SlowDNS tunnel init: %w", err)
	}

	if isVaydns {
		t.IdleTimeout = vayclient.DefaultIdleTimeout // 10s
		t.KeepAlive = vayclient.DefaultKeepAlive     // 2s
	} else {
		t.IdleTimeout = vayclient.DnsttIdleTimeout   // 2m
		t.KeepAlive = vayclient.DnsttKeepAlive       // 10s
	}

	logMsg("INFO", "SlowDNS (%s/%s) dialing %s via %s (type=%s)", dnsMode, resolver.ResolverType, ns, resolvedAddr, ts.RecordType)

	if err := t.InitiateResolverConnection(); err != nil {
		_ = t.Close()
		return nil, fmt.Errorf("SlowDNS resolver connect: %w", err)
	}
	if err := t.InitiateDNSPacketConn(ts.Addr); err != nil {
		_ = t.Close()
		return nil, fmt.Errorf("SlowDNS DNS packet conn: %w", err)
	}
	if err := t.InitiateKCPConn(ts.MTU); err != nil {
		_ = t.Close()
		return nil, fmt.Errorf("SlowDNS KCP init: %w", err)
	}
	if err := t.InitiateNoiseChannel(); err != nil {
		_ = t.Close()
		return nil, fmt.Errorf("SlowDNS Noise handshake: %w", err)
	}
	if err := t.InitiateSmuxSession(); err != nil {
		_ = t.Close()
		return nil, fmt.Errorf("SlowDNS smux session: %w", err)
	}

	stream, err := t.OpenStream()
	if err != nil {
		_ = t.Close()
		return nil, fmt.Errorf("SlowDNS open stream: %w", err)
	}

	_ = ctx
	return &vaydnsConn{Conn: stream, tunnel: t}, nil
}
