package core

import (
	"context"
	"encoding/json"
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

	vxcrypto "vexotun/core/vexdns/crypto"
	"vexotun/core/vexdns/dnswire"
	"vexotun/core/vexdns/solver"
	vxtunnel "vexotun/core/vexdns/tunnel"
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

type vexSlowDNSConn struct {
	*smux.Stream
	cli *vxtunnel.Client
}

func (c *vexSlowDNSConn) Close() error {
	var err error
	if c.Stream != nil {
		err = c.Stream.Close()
	}
	if c.cli != nil {
		_ = c.cli.Close()
	}
	return err
}

func makeProtectedPacketFactory(protector SocketProtector) vxtunnel.PacketFactory {
	return func(ctx context.Context, network, address string) (net.PacketConn, error) {
		lc := net.ListenConfig{
			Control: func(_, _ string, c syscall.RawConn) error {
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
		return lc.ListenPacket(ctx, network, address)
	}
}

func buildSlowDNSResolvers(cfg SSHConfig) []string {
	dnsRaw := strings.TrimSpace(cfg.DNSServer)
	typeRaw := strings.ToUpper(strings.TrimSpace(cfg.DNSType))
	isAutoMode := dnsRaw == "" || strings.EqualFold(dnsRaw, "auto") || strings.EqualFold(dnsRaw, "otomatis") ||
		typeRaw == "" || typeRaw == "AUTO"

	var raw []string
	if dnsRaw != "" && !strings.EqualFold(dnsRaw, "auto") && !strings.EqualFold(dnsRaw, "otomatis") {
		raw = append(raw, dnsRaw)
	}
	if isAutoMode {
		if strings.TrimSpace(cfg.FallbackDNS) != "" {
			raw = append(raw, cfg.FallbackDNS)
		}
		if h := strings.TrimSpace(cfg.Host); h != "" && !strings.EqualFold(h, cfg.Nameserver) {
			raw = append(raw, net.JoinHostPort(strings.Trim(h, "[]"), "53"))
		}
		raw = append(raw, "1.1.1.1:53", "8.8.8.8:53")
	}
	if len(raw) == 0 {
		raw = []string{"8.8.8.8:53"}
	}
	return solver.NormalizeResolvers(raw)
}

func dialSlowDNS(ctx context.Context, cfg SSHConfig, protector SocketProtector) (net.Conn, error) {
	ns := strings.TrimSpace(cfg.Nameserver)
	pubHex := strings.TrimSpace(cfg.PublicKey)
	if ns == "" || pubHex == "" {
		return nil, fmt.Errorf("SlowDNS membutuhkan Nameserver (NS) dan Public Key")
	}

	vxDomain, err := dnswire.ParseName(ns)
	if err != nil {
		return nil, fmt.Errorf("nameserver tidak valid (%s): %w", ns, err)
	}
	vxPubkey, err := vxcrypto.DecodeKey(pubHex)
	if err != nil {
		return nil, fmt.Errorf("public key tidak valid: %w", err)
	}

	resolvers := buildSlowDNSResolvers(cfg)
	typeRaw := strings.ToUpper(strings.TrimSpace(cfg.DNSType))
	var qtypes []uint16
	if typeRaw != "" && typeRaw != "AUTO" && typeRaw != "ANY" {
		if qt, perr := dnswire.ParseRecordType(typeRaw); perr == nil {
			qtypes = []uint16{qt}
		}
	}

	typeLabel := typeRaw
	if typeLabel == "" {
		typeLabel = "AUTO"
	}
	pktFactory := makeProtectedPacketFactory(protector)
	logMsg("INFO", "SlowDNS VexDNS: memindai %d resolver %v (Type=%s) -> %s",
		len(resolvers), resolvers, typeLabel, ns)

	probeCtx, probeCancel := context.WithTimeout(ctx, 8*time.Second)
	cands, probeErr := solver.ProbeAll(probeCtx, solver.Config{
		Resolvers:    resolvers,
		Domain:       vxDomain,
		Pubkey:       vxPubkey,
		RecordTypes:  qtypes,
		ProbeTimeout: 1200 * time.Millisecond,
		ListenPacket: pktFactory,
	})
	probeCancel()

	if probeErr == nil && len(cands) > 0 {
		maxTries := len(cands)
		if maxTries > 4 {
			maxTries = 4
		}
		var lastDialErr error
		for i := 0; i < maxTries; i++ {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			cand := cands[i]
			striped := solver.StripedResolvers(cands, cand)
			logMsg("INFO", "SlowDNS VexDNS dialing %s via %v (Type=%s, MTU=%d, RTT=%dms)",
				ns, striped, strings.ToUpper(cand.QTypeName), cand.DownBudget, cand.RTT.Milliseconds())

			dialCtx, dialCancel := context.WithTimeout(ctx, 12*time.Second)
			cli, derr := vxtunnel.DialClient(dialCtx, vxtunnel.ClientConfig{
				DNSServerAddr:  cand.Resolver,
				DNSServerAddrs: striped,
				Domain:         vxDomain,
				Pubkey:         vxPubkey,
				QType:          cand.QType,
				DownBudget:     uint16(cand.DownBudget),
				MaxQueryUDP:    cand.MaxQueryUDP,
				ListenPacket:   pktFactory,
			})
			dialCancel()
			if derr != nil {
				lastDialErr = derr
				logMsg("WARN", "SlowDNS VexDNS jalur %s (%s) gagal: %v", cand.Resolver, strings.ToUpper(cand.QTypeName), derr)
				continue
			}

			stream, serr := cli.OpenStream()
			if serr != nil {
				_ = cli.Close()
				lastDialErr = serr
				logMsg("WARN", "SlowDNS VexDNS open stream (%s) gagal: %v", strings.ToUpper(cand.QTypeName), serr)
				continue
			}

			logMsg("INFO", "SlowDNS VexDNS terhubung via %v (%s • MTU %d • %dms)",
				striped, strings.ToUpper(cand.QTypeName), cand.DownBudget, cand.RTT.Milliseconds())
			return &vexSlowDNSConn{Stream: stream, cli: cli}, nil
		}
		logMsg("WARN", "SlowDNS VexDNS kandidat gagal (%v), mencoba fallback dnstt standar...", lastDialErr)
	} else if probeErr != nil {
		logMsg("WARN", "Probe VexDNS tidak merespons (%v), mencoba mode dnstt kompatibel...", probeErr)
	}

	return dialSlowDNSLegacy(ctx, cfg, ns, pubHex, resolvers, protector)
}

// ScanSlowDNS probes all configured resolvers and 11 QTYPEs on demand from UI and returns JSON.
func ScanSlowDNS(configJSON string, protector SocketProtector) string {
	type scanCandidate struct {
		Resolver     string `json:"resolver"`
		QType        string `json:"qtype"`
		MTU          int    `json:"mtu"`
		PayloadBytes int    `json:"payloadBytes"`
		QueryCap     int    `json:"queryCap"`
		RTTMs        int64  `json:"rttMs"`
	}
	type scanResult struct {
		OK         bool            `json:"ok"`
		Candidates []scanCandidate `json:"candidates"`
		Error      string          `json:"error,omitempty"`
	}
	encode := func(r scanResult) string {
		b, _ := json.Marshal(r)
		return string(b)
	}

	var cfg SSHConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return encode(scanResult{OK: false, Error: err.Error()})
	}
	domain, err := dnswire.ParseName(strings.TrimSpace(cfg.Nameserver))
	if err != nil || len(domain) == 0 {
		return encode(scanResult{OK: false, Error: "Nameserver (NS) belum valid"})
	}
	pubkey, err := vxcrypto.DecodeKey(strings.TrimSpace(cfg.PublicKey))
	if err != nil {
		return encode(scanResult{OK: false, Error: "Public Key tidak valid"})
	}
	resolvers := buildSlowDNSResolvers(cfg)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	cands, err := solver.ProbeAll(ctx, solver.Config{
		Resolvers:    resolvers,
		Domain:       domain,
		Pubkey:       pubkey,
		ProbeTimeout: 1200 * time.Millisecond,
		ListenPacket: makeProtectedPacketFactory(protector),
	})
	if err != nil {
		return encode(scanResult{OK: false, Error: err.Error()})
	}
	out := make([]scanCandidate, 0, len(cands))
	for _, c := range cands {
		out = append(out, scanCandidate{
			Resolver:     c.Resolver,
			QType:        strings.ToUpper(c.QTypeName),
			MTU:          c.DownBudget,
			PayloadBytes: c.PayloadBytes,
			QueryCap:     c.MaxQueryUDP,
			RTTMs:        c.RTT.Milliseconds(),
		})
	}
	return encode(scanResult{OK: true, Candidates: out})
}

// --- Legacy dnstt fallback (for older dnstt-server / non-vexdns servers) ---

func dnsNameCapacity(domain dns.Name) int {
	capacity := 255 - 1
	for _, label := range domain {
		capacity -= len(label) + 1
	}
	capacity = capacity * 63 / 64
	capacity = capacity * 5 / 8
	return capacity
}

func parseLegacySlowDNSType(raw string) (uint16, string) {
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

func dialSlowDNSLegacy(
	ctx context.Context,
	cfg SSHConfig,
	ns string,
	pubHex string,
	resolvers []string,
	protector SocketProtector,
) (net.Conn, error) {
	domain, err := dns.ParseName(ns)
	if err != nil {
		return nil, fmt.Errorf("nameserver tidak valid (%s): %w", ns, err)
	}
	pubkey, err := noise.DecodeKey(pubHex)
	if err != nil {
		return nil, fmt.Errorf("public key tidak valid: %w", err)
	}

	dnsHost := resolvers[0]
	remoteAddr, err := net.ResolveUDPAddr("udp", dnsHost)
	if err != nil {
		return nil, fmt.Errorf("DNS resolver tidak valid (%s): %w", dnsHost, err)
	}

	mtu := dnsNameCapacity(domain) - 8 - 1 - numPadding - 1
	if mtu < 80 {
		return nil, fmt.Errorf("domain %s terlalu panjang (MTU=%d)", ns, mtu)
	}

	rawType := strings.ToUpper(strings.TrimSpace(cfg.DNSType))
	if rawType == "" || rawType == "AUTO" || rawType == "HTTPS" || rawType == "SVCB" || rawType == "AAAA" || rawType == "CAA" || rawType == "SRV" {
		candidates := []string{"TXT", "NULL", "CNAME", "NS", "A", "MX"}
		var lastErr error
		for _, candidate := range candidates {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			conn, dialErr := dialLegacySlowDNSType(ctx, ns, domain, pubkey, dnsHost, remoteAddr, mtu, candidate, 8*time.Second, protector)
			if dialErr == nil {
				logMsg("INFO", "SlowDNS (dnstt-compat): terhubung via %s (%s)", dnsHost, candidate)
				return conn, nil
			}
			lastErr = dialErr
		}
		return nil, fmt.Errorf("SlowDNS gagal di semua tipe: %w", lastErr)
	}

	return dialLegacySlowDNSType(ctx, ns, domain, pubkey, dnsHost, remoteAddr, mtu, rawType, 12*time.Second, protector)
}

func dialLegacySlowDNSType(
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
		Control: func(_, _ string, c syscall.RawConn) error {
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

	rrType, rrLabel := parseLegacySlowDNSType(typeName)
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
