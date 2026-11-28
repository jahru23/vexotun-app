package core

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"sort"
	"strings"
	"sync"
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

const (
	slowDNSPenaltyTTL = 3 * time.Minute
	slowDNSRouteTTL   = 10 * time.Minute
)

type cachedSlowDNSRoute struct {
	cand     solver.Candidate
	striped  []string
	cachedAt time.Time
}

var slowDNSCache = struct {
	mu             sync.Mutex
	pairPenalties  map[string]time.Time
	qtypePenalties map[string]time.Time
	routes         map[string]cachedSlowDNSRoute
}{
	pairPenalties:  make(map[string]time.Time),
	qtypePenalties: make(map[string]time.Time),
	routes:         make(map[string]cachedSlowDNSRoute),
}

func slowDNSRouteKey(ns, typeLabel string, resolvers []string) string {
	return strings.ToLower(ns) + "|" + strings.ToUpper(typeLabel) + "|" + strings.Join(resolvers, ",")
}

func recordSlowDNSPenalty(ns, resolver string, qtype uint16, demoteQType bool) {
	now := time.Now()
	exp := now.Add(slowDNSPenaltyTTL)
	nsKey := strings.ToLower(ns)
	slowDNSCache.mu.Lock()
	defer slowDNSCache.mu.Unlock()
	slowDNSCache.pairPenalties[fmt.Sprintf("%s|%s|%d", nsKey, resolver, qtype)] = exp
	// In AUTO mode, demote non-AAAA QTYPEs after a live stall so reconnect tries AAAA/alternatives.
	if demoteQType && qtype != dnswire.RRTypeAAAA {
		slowDNSCache.qtypePenalties[fmt.Sprintf("%s|%d", nsKey, qtype)] = exp
	}
	for k, r := range slowDNSCache.routes {
		if strings.HasPrefix(k, nsKey+"|") && (r.cand.Resolver == resolver || r.cand.QType == qtype) {
			delete(slowDNSCache.routes, k)
		}
	}
}

func isSlowDNSPenalizedLocked(ns, resolver string, qtype uint16, now time.Time) bool {
	nsKey := strings.ToLower(ns)
	pk := fmt.Sprintf("%s|%s|%d", nsKey, resolver, qtype)
	if exp, ok := slowDNSCache.pairPenalties[pk]; ok {
		if now.Before(exp) {
			return true
		}
		delete(slowDNSCache.pairPenalties, pk)
	}
	qk := fmt.Sprintf("%s|%d", nsKey, qtype)
	if exp, ok := slowDNSCache.qtypePenalties[qk]; ok {
		if now.Before(exp) {
			return true
		}
		delete(slowDNSCache.qtypePenalties, qk)
	}
	return false
}

func getCachedSlowDNSRoute(key, ns string) (cachedSlowDNSRoute, bool) {
	now := time.Now()
	slowDNSCache.mu.Lock()
	defer slowDNSCache.mu.Unlock()
	r, ok := slowDNSCache.routes[key]
	if !ok {
		return cachedSlowDNSRoute{}, false
	}
	if now.Sub(r.cachedAt) > slowDNSRouteTTL || isSlowDNSPenalizedLocked(ns, r.cand.Resolver, r.cand.QType, now) {
		delete(slowDNSCache.routes, key)
		return cachedSlowDNSRoute{}, false
	}
	return r, true
}

func setCachedSlowDNSRoute(key string, cand solver.Candidate, striped []string) {
	cp := append([]string(nil), striped...)
	slowDNSCache.mu.Lock()
	slowDNSCache.routes[key] = cachedSlowDNSRoute{
		cand:     cand,
		striped:  cp,
		cachedAt: time.Now(),
	}
	slowDNSCache.mu.Unlock()
}

func sortCandidatesWithPenalties(ns string, cands []solver.Candidate) []solver.Candidate {
	out := append([]solver.Candidate(nil), cands...)
	now := time.Now()
	slowDNSCache.mu.Lock()
	penalized := make([]bool, len(out))
	for i, c := range out {
		penalized[i] = isSlowDNSPenalizedLocked(ns, c.Resolver, c.QType, now)
	}
	slowDNSCache.mu.Unlock()

	sort.SliceStable(out, func(i, j int) bool {
		pi := false
		pj := false
		for idx, c := range cands {
			if c.Resolver == out[i].Resolver && c.QType == out[i].QType {
				pi = penalized[idx]
			}
			if c.Resolver == out[j].Resolver && c.QType == out[j].QType {
				pj = penalized[idx]
			}
		}
		if pi != pj {
			return !pi
		}
		return false
	})
	return out
}

type vexSlowDNSConn struct {
	*smux.Stream
	cli       *vxtunnel.Client
	ns        string
	resolver  string
	qtype     uint16
	autoMode  bool
	penalized bool
	mu        sync.Mutex
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

func (c *vexSlowDNSConn) IsHealthy(maxSilence time.Duration) bool {
	if c.cli == nil {
		return false
	}
	ok := c.cli.IsHealthy(maxSilence)
	if !ok {
		c.mu.Lock()
		already := c.penalized
		c.penalized = true
		c.mu.Unlock()
		if !already && c.ns != "" {
			recordSlowDNSPenalty(c.ns, c.resolver, c.qtype, c.autoMode)
			logMsg("WARN", "SlowDNS menandai jalur macet (%s / %s), reconnect akan beralih tipe/resolver",
				c.resolver, strings.ToUpper(dnswire.RecordTypeString(c.qtype)))
		}
	}
	return ok
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
	hasManualDNS := dnsRaw != "" && !strings.EqualFold(dnsRaw, "auto") && !strings.EqualFold(dnsRaw, "otomatis")

	var raw []string
	if hasManualDNS {
		raw = append(raw, dnsRaw)
	} else {
		if strings.TrimSpace(cfg.FallbackDNS) != "" {
			raw = append(raw, cfg.FallbackDNS)
		}
		if h := strings.TrimSpace(cfg.Host); h != "" && !strings.EqualFold(h, cfg.Nameserver) {
			raw = append(raw, net.JoinHostPort(strings.Trim(h, "[]"), "53"))
		}
		// TKP Indonesia public & anycast DNS candidates probed in parallel (~1.2s total).
		raw = append(raw,
			"1.1.1.1:53",
			"1.0.0.1:53",
			"8.8.8.8:53",
			"8.8.4.4:53",
			"9.9.9.9:53",
			"94.140.14.14:53",
			"208.67.222.222:53",
		)
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
	dnsMode := strings.ToLower(strings.TrimSpace(cfg.SlowDNSMode))
	if dnsMode == "dnstt" {
		logMsg("DEBUG", "SlowDNS mode=dnstt: langsung dial dnstt standar paralel ke %d resolver", len(resolvers))
		return dialSlowDNSLegacy(ctx, cfg, ns, pubHex, resolvers, protector)
	}

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
	autoMode := typeLabel == "AUTO" || typeLabel == "ANY"
	pktFactory := makeProtectedPacketFactory(protector)
	routeKey := slowDNSRouteKey(ns, typeLabel, resolvers)

	if cached, ok := getCachedSlowDNSRoute(routeKey, ns); ok {
		logMsg("DEBUG", "SlowDNS VexDNS fast-dial cached %s via %v (Type=%s, MTU=%d)",
			ns, cached.striped, strings.ToUpper(cached.cand.QTypeName), cached.cand.DownBudget)
		dialCtx, dialCancel := context.WithTimeout(ctx, 8*time.Second)
		cli, derr := vxtunnel.DialClient(dialCtx, vxtunnel.ClientConfig{
			DNSServerAddr:  cached.cand.Resolver,
			DNSServerAddrs: cached.striped,
			Domain:         vxDomain,
			Pubkey:         vxPubkey,
			QType:          cached.cand.QType,
			DownBudget:     uint16(cached.cand.DownBudget),
			MaxQueryUDP:    cached.cand.MaxQueryUDP,
			ListenPacket:   pktFactory,
		})
		dialCancel()
		if derr == nil {
			if stream, serr := cli.OpenStream(); serr == nil {
				logMsg("INFO", "SlowDNS terhubung")
				logMsg("DEBUG", "SlowDNS VexDNS terhubung cepat via %v (%s • MTU %d)",
					cached.striped, strings.ToUpper(cached.cand.QTypeName), cached.cand.DownBudget)
				return &vexSlowDNSConn{
					Stream:   stream,
					cli:      cli,
					ns:       ns,
					resolver: cached.cand.Resolver,
					qtype:    cached.cand.QType,
					autoMode: autoMode,
				}, nil
			}
			_ = cli.Close()
		}
		recordSlowDNSPenalty(ns, cached.cand.Resolver, cached.cand.QType, false)
	}

	logMsg("DEBUG", "SlowDNS VexDNS: memindai %d resolver %v (Type=%s) -> %s",
		len(resolvers), resolvers, typeLabel, ns)

	probeWindow := 4500 * time.Millisecond
	if dnsMode == "vexdns" {
		probeWindow = 8 * time.Second
	}
	probeCtx, probeCancel := context.WithTimeout(ctx, probeWindow)
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
		ordered := sortCandidatesWithPenalties(ns, cands)
		maxTries := len(ordered)
		if maxTries > 6 {
			maxTries = 6
		}
		var lastDialErr error
		for i := 0; i < maxTries; i++ {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			cand := ordered[i]
			striped := solver.StripedResolvers(ordered, cand)
			logMsg("DEBUG", "SlowDNS VexDNS dialing %s via %v (Type=%s, MTU=%d, RTT=%dms)",
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
				recordSlowDNSPenalty(ns, cand.Resolver, cand.QType, false)
				logMsg("DEBUG", "SlowDNS VexDNS jalur %s (%s) gagal: %v", cand.Resolver, strings.ToUpper(cand.QTypeName), derr)
				continue
			}

			stream, serr := cli.OpenStream()
			if serr != nil {
				_ = cli.Close()
				lastDialErr = serr
				recordSlowDNSPenalty(ns, cand.Resolver, cand.QType, false)
				logMsg("DEBUG", "SlowDNS VexDNS open stream (%s) gagal: %v", strings.ToUpper(cand.QTypeName), serr)
				continue
			}

			setCachedSlowDNSRoute(routeKey, cand, striped)
			logMsg("INFO", "SlowDNS terhubung")
			logMsg("DEBUG", "SlowDNS VexDNS terhubung via %v (%s • MTU %d • %dms)",
				striped, strings.ToUpper(cand.QTypeName), cand.DownBudget, cand.RTT.Milliseconds())
			return &vexSlowDNSConn{
				Stream:   stream,
				cli:      cli,
				ns:       ns,
				resolver: cand.Resolver,
				qtype:    cand.QType,
				autoMode: autoMode,
			}, nil
		}
		logMsg("DEBUG", "SlowDNS VexDNS kandidat gagal (%v), mencoba fallback dnstt standar...", lastDialErr)
	} else if probeErr != nil {
		logMsg("DEBUG", "Probe VexDNS tidak merespons (%v), mencoba mode dnstt kompatibel...", probeErr)
	}

	if dnsMode == "vexdns" {
		if probeErr != nil {
			return nil, probeErr
		}
		return nil, fmt.Errorf("SlowDNS VexDNS gagal di semua kandidat")
	}

	return dialSlowDNSLegacy(ctx, cfg, ns, pubHex, resolvers, protector)
}

// ScanSlowDNS probes all configured resolvers and 11 QTYPEs (VexDNS + standard dnstt fallback) and returns JSON.
func ScanSlowDNS(configJSON string, protector SocketProtector) string {
	type scanCandidate struct {
		Resolver     string `json:"resolver"`
		QType        string `json:"qtype"`
		Mode         string `json:"mode,omitempty"`
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
	ns := strings.TrimSpace(cfg.Nameserver)
	domain, err := dnswire.ParseName(ns)
	if err != nil || len(domain) == 0 {
		return encode(scanResult{OK: false, Error: "Nameserver (NS) belum valid"})
	}
	pubHex := strings.TrimSpace(cfg.PublicKey)
	pubkey, err := vxcrypto.DecodeKey(pubHex)
	if err != nil {
		return encode(scanResult{OK: false, Error: "Public Key tidak valid"})
	}
	resolvers := buildSlowDNSResolvers(cfg)
	dnsMode := strings.ToLower(strings.TrimSpace(cfg.SlowDNSMode))
	out := make([]scanCandidate, 0, 8)
	var lastErr error

	if dnsMode != "dnstt" {
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
		cands, perr := solver.ProbeAll(ctx, solver.Config{
			Resolvers:    resolvers,
			Domain:       domain,
			Pubkey:       pubkey,
			ProbeTimeout: 1200 * time.Millisecond,
			ListenPacket: makeProtectedPacketFactory(protector),
		})
		cancel()
		lastErr = perr
		for _, c := range cands {
			out = append(out, scanCandidate{
				Resolver:     c.Resolver,
				QType:        strings.ToUpper(c.QTypeName),
				Mode:         "vexdns",
				MTU:          c.DownBudget,
				PayloadBytes: c.PayloadBytes,
				QueryCap:     c.MaxQueryUDP,
				RTTMs:        c.RTT.Milliseconds(),
			})
		}
	}

	if dnsMode == "dnstt" || (dnsMode != "vexdns" && len(out) == 0) {
		dnsttCands, derr := scanLegacyDnsttCandidates(context.Background(), cfg, ns, pubHex, resolvers, protector)
		if derr != nil && lastErr == nil {
			lastErr = derr
		}
		for _, dc := range dnsttCands {
			out = append(out, scanCandidate{
				Resolver: dc.resolver,
				QType:    dc.qtype,
				Mode:     "dnstt",
				MTU:      dc.mtu,
				RTTMs:    dc.rttMs,
			})
		}
	}

	if len(out) == 0 {
		errMsg := "Tidak ada jalur DNS yang merespons"
		if lastErr != nil {
			errMsg = lastErr.Error()
		}
		return encode(scanResult{OK: false, Error: errMsg})
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

type dnsttScanCandidate struct {
	resolver string
	qtype    string
	mtu      int
	rttMs    int64
}

func scanLegacyDnsttCandidates(
	parentCtx context.Context,
	cfg SSHConfig,
	ns string,
	pubHex string,
	resolvers []string,
	protector SocketProtector,
) ([]dnsttScanCandidate, error) {
	domain, err := dns.ParseName(ns)
	if err != nil {
		return nil, fmt.Errorf("nameserver tidak valid (%s): %w", ns, err)
	}
	pubkey, err := noise.DecodeKey(pubHex)
	if err != nil {
		return nil, fmt.Errorf("public key tidak valid: %w", err)
	}
	mtu := dnsNameCapacity(domain) - 8 - 1 - numPadding - 1
	if mtu < 80 {
		return nil, fmt.Errorf("domain %s terlalu panjang (MTU=%d)", ns, mtu)
	}

	rawType := strings.ToUpper(strings.TrimSpace(cfg.DNSType))
	qtypeName := "TXT"
	if rawType == "NULL" || rawType == "CNAME" || rawType == "NS" || rawType == "A" || rawType == "MX" {
		qtypeName = rawType
	}

	ctx, cancel := context.WithTimeout(parentCtx, 3500*time.Millisecond)
	defer cancel()

	ch := make(chan dnsttScanCandidate, len(resolvers))
	var wg sync.WaitGroup
	for _, res := range resolvers {
		res := res
		remoteAddr, rerr := net.ResolveUDPAddr("udp", res)
		if rerr != nil {
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			start := time.Now()
			conn, derr := dialLegacySlowDNSType(ctx, ns, domain, pubkey, res, remoteAddr, mtu, qtypeName, 3*time.Second, protector)
			if derr != nil {
				return
			}
			_ = conn.Close()
			ch <- dnsttScanCandidate{
				resolver: res,
				qtype:    qtypeName,
				mtu:      mtu,
				rttMs:    time.Since(start).Milliseconds(),
			}
		}()
	}
	wg.Wait()
	close(ch)

	var out []dnsttScanCandidate
	for c := range ch {
		out = append(out, c)
	}
	sort.Slice(out, func(i, j int) bool {
		return out[i].rttMs < out[j].rttMs
	})
	return out, nil
}

func dialSlowDNSLegacyParallelBatch(
	ctx context.Context,
	ns string,
	domain dns.Name,
	pubkey []byte,
	resolvers []string,
	mtu int,
	qtypes []string,
	perDialTimeout time.Duration,
	protector SocketProtector,
) (net.Conn, string, string, error) {
	type attempt struct {
		resolver string
		addr     *net.UDPAddr
		qtype    string
	}
	var attempts []attempt
	for _, qt := range qtypes {
		for _, res := range resolvers {
			rAddr, err := net.ResolveUDPAddr("udp", res)
			if err != nil {
				continue
			}
			attempts = append(attempts, attempt{resolver: res, addr: rAddr, qtype: qt})
		}
	}
	if len(attempts) == 0 {
		return nil, "", "", fmt.Errorf("tidak ada DNS resolver valid")
	}

	batchCtx, batchCancel := context.WithCancel(ctx)
	defer batchCancel()

	type resItem struct {
		conn     net.Conn
		resolver string
		qtype    string
		err      error
	}
	resCh := make(chan resItem, len(attempts))
	var wg sync.WaitGroup
	for _, a := range attempts {
		a := a
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn, err := dialLegacySlowDNSType(batchCtx, ns, domain, pubkey, a.resolver, a.addr, mtu, a.qtype, perDialTimeout, protector)
			resCh <- resItem{conn: conn, resolver: a.resolver, qtype: a.qtype, err: err}
		}()
	}
	go func() {
		wg.Wait()
		close(resCh)
	}()

	var lastErr error
	var winner net.Conn
	var winRes, winType string
	for item := range resCh {
		if item.err == nil && item.conn != nil {
			if winner == nil {
				winner = item.conn
				winRes = item.resolver
				winType = item.qtype
				batchCancel()
			} else {
				_ = item.conn.Close()
			}
		} else if item.err != nil {
			lastErr = item.err
		}
	}
	if winner != nil {
		return winner, winRes, winType, nil
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("SlowDNS dnstt gagal")
	}
	return nil, "", "", lastErr
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

	mtu := dnsNameCapacity(domain) - 8 - 1 - numPadding - 1
	if mtu < 80 {
		return nil, fmt.Errorf("domain %s terlalu panjang (MTU=%d)", ns, mtu)
	}

	rawType := strings.ToUpper(strings.TrimSpace(cfg.DNSType))
	if rawType == "" || rawType == "AUTO" || rawType == "HTTPS" || rawType == "SVCB" || rawType == "AAAA" || rawType == "CAA" || rawType == "SRV" {
		// Fast pass: TXT across all resolvers concurrently (standard dnstt-server default).
		conn, usedRes, usedType, derr := dialSlowDNSLegacyParallelBatch(ctx, ns, domain, pubkey, resolvers, mtu, []string{"TXT"}, 4*time.Second, protector)
		if derr == nil {
			logMsg("INFO", "SlowDNS terhubung")
			logMsg("DEBUG", "SlowDNS (dnstt): terhubung via %s (%s)", usedRes, usedType)
			return conn, nil
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		// Fallback pass across alternative legacy QTYPEs.
		conn, usedRes, usedType, derr2 := dialSlowDNSLegacyParallelBatch(ctx, ns, domain, pubkey, resolvers, mtu, []string{"NULL", "CNAME", "NS", "A", "MX"}, 5*time.Second, protector)
		if derr2 == nil {
			logMsg("INFO", "SlowDNS terhubung")
			logMsg("DEBUG", "SlowDNS (dnstt): terhubung via %s (%s)", usedRes, usedType)
			return conn, nil
		}
		return nil, fmt.Errorf("SlowDNS gagal di semua resolver & tipe: %w", derr2)
	}

	conn, usedRes, usedType, derr := dialSlowDNSLegacyParallelBatch(ctx, ns, domain, pubkey, resolvers, mtu, []string{rawType}, 6*time.Second, protector)
	if derr != nil {
		return nil, derr
	}
	logMsg("INFO", "SlowDNS terhubung")
	logMsg("DEBUG", "SlowDNS (dnstt): terhubung via %s (%s)", usedRes, usedType)
	return conn, nil
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

	return &slowDNSConn{
		Stream:  stream,
		sess:    sess,
		kcpConn: kcpConn,
		pconn:   pconn,
		udpConn: udpConn,
	}, nil
}
