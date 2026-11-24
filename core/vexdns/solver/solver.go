// Package solver implements multi-carrier DNS reachability probing, MTU staircase
// discovery, candidate scoring, and runtime failover for VexDNS clients.
package solver

import (
	"context"
	"fmt"
	"net"
	"sort"
	"strings"
	"sync"
	"time"

	"vexotun/core/vexdns/dnswire"
	"vexotun/core/vexdns/protocol"
	"vexotun/core/vexdns/tunnel"
)

// MTUStaircase lists descending DNS response UDP payload budgets probed by Phase 2.
var MTUStaircase = []int{1200, 960, 720, 505, 420, 340, 250, 180}

// StatusEvent is emitted as structured progress (rendered cleanly by CLI or APK -status-json).
type StatusEvent struct {
	Event     string  `json:"event"`
	DNS       string  `json:"dns,omitempty"`
	QType     string  `json:"qtype,omitempty"`
	MTU       int     `json:"mtu,omitempty"`
	QueryCap  int     `json:"query_cap,omitempty"`
	RTTMs     int64   `json:"rtt_ms,omitempty"`
	Score     float64 `json:"score,omitempty"`
	Step      string  `json:"step,omitempty"`
	Reason    string  `json:"reason,omitempty"`
	NextQType string  `json:"next_qtype,omitempty"`
	Listen    string  `json:"listen,omitempty"`
}

// Config configures the multi-carrier Auto-Solver.
type Config struct {
	Resolvers    []string
	Domain       dnswire.Name
	Pubkey       []byte
	RecordTypes  []uint16 // Empty = all 11 supported QTYPEs
	ForcedMTU    int      // 0 = auto-discover via staircase
	ProbeTimeout time.Duration
	ListenPacket tunnel.PacketFactory
	OnStatus     func(StatusEvent)
}

// Candidate describes one verified working (resolver, qtype, mtu, queryCap) path.
type Candidate struct {
	Resolver     string
	QType        uint16
	QTypeName    string
	DownBudget   int
	MaxQueryUDP  int
	PayloadBytes int
	KCPMTU       int
	RTT          time.Duration
	Score        float64
}

func emit(cfg Config, ev StatusEvent) {
	if cfg.OnStatus != nil {
		cfg.OnStatus(ev)
	}
}

// NormalizeResolvers deduplicates resolvers and ensures each has a ":port" (default :53).
func NormalizeResolvers(raw []string) []string {
	seen := make(map[string]struct{})
	var out []string
	for _, r := range raw {
		for _, part := range strings.Split(r, ",") {
			s := strings.TrimSpace(part)
			if s == "" {
				continue
			}
			if _, _, err := net.SplitHostPort(s); err != nil {
				// Bare IPv4, hostname, or bracketless IPv6 -> attach port 53 cleanly.
				s = net.JoinHostPort(strings.Trim(s, "[]"), "53")
			}
			if _, ok := seen[s]; ok {
				continue
			}
			seen[s] = struct{}{}
			out = append(out, s)
		}
	}
	return out
}

// ProbeAll runs Phase 1 (multi-QTYPE + query-length reachability) and Phase 2 (MTU staircase)
// and returns candidates sorted descending by estimated throughput score.
func ProbeAll(ctx context.Context, cfg Config) ([]Candidate, error) {
	resolvers := NormalizeResolvers(cfg.Resolvers)
	if len(resolvers) == 0 {
		return nil, fmt.Errorf("solver: no DNS resolvers provided")
	}
	qtypes := cfg.RecordTypes
	if len(qtypes) == 0 {
		qtypes = dnswire.AllSupportedRecordTypes
	}
	timeout := cfg.ProbeTimeout
	if timeout <= 0 {
		timeout = 1400 * time.Millisecond
	}

	type pair struct {
		resolver string
		qtype    uint16
	}
	type phase1Result struct {
		pair
		maxQueryUDP int
		rtt         time.Duration
	}

	// Phase 1: sweep all (resolver, qtype) pairs in parallel testing realistic long data query sizes.
	var pairs []pair
	for _, r := range resolvers {
		for _, qt := range qtypes {
			pairs = append(pairs, pair{resolver: r, qtype: qt})
		}
	}

	emit(cfg, StatusEvent{Event: "probing", Step: fmt.Sprintf("phase1_sweep_%d_pairs", len(pairs))})

	p1Ch := make(chan phase1Result, len(pairs))
	sem := make(chan struct{}, 32)
	var wg sync.WaitGroup

	for _, p := range pairs {
		p := p
		wg.Add(1)
		go func() {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()

			emit(cfg, StatusEvent{
				Event: "probing",
				DNS:   p.resolver,
				QType: dnswire.RecordTypeString(p.qtype),
				Step:  "reachability",
			})

			// Probe 3 realistic query packet caps so DialClient never sends a query larger
			// than what actually cleared carrier DPI: full QNAME (0), 235B UDP, and 165B UDP.
			capFull := dnswire.MaxQueryPayloadLen(cfg.Domain)
			cap235 := protocol.MaxQueryPayloadForUDP(cfg.Domain, 235)
			cap165 := protocol.MaxQueryPayloadForUDP(cfg.Domain, 165)

			var rttFull, rtt235, rtt165 time.Duration
			var okFull, ok235, ok165 bool
			var wgQ sync.WaitGroup
			wgQ.Add(3)
			go func() {
				defer wgQ.Done()
				if rtt, ok := sendProbe(ctx, cfg.ListenPacket, p.resolver, cfg.Domain, cfg.Pubkey, p.qtype, 240, capFull, timeout); ok {
					okFull, rttFull = true, rtt
				}
			}()
			go func() {
				defer wgQ.Done()
				if cap235 >= protocol.NativeHeaderLen+45 {
					if rtt, ok := sendProbe(ctx, cfg.ListenPacket, p.resolver, cfg.Domain, cfg.Pubkey, p.qtype, 240, cap235, timeout); ok {
						ok235, rtt235 = true, rtt
					}
				}
			}()
			go func() {
				defer wgQ.Done()
				if cap165 >= protocol.NativeHeaderLen+40 {
					if rtt, ok := sendProbe(ctx, cfg.ListenPacket, p.resolver, cfg.Domain, cfg.Pubkey, p.qtype, 240, cap165, timeout); ok {
						ok165, rtt165 = true, rtt
					}
				}
			}()
			wgQ.Wait()

			switch {
			case okFull:
				p1Ch <- phase1Result{pair: p, maxQueryUDP: 0, rtt: rttFull}
			case ok235:
				p1Ch <- phase1Result{pair: p, maxQueryUDP: 235, rtt: rtt235}
			case ok165:
				p1Ch <- phase1Result{pair: p, maxQueryUDP: 165, rtt: rtt165}
			}
		}()
	}
	wg.Wait()
	close(p1Ch)

	var survivors []phase1Result
	for res := range p1Ch {
		survivors = append(survivors, res)
	}
	if len(survivors) == 0 {
		return nil, fmt.Errorf("solver: no reachable DNS tunnel path passed query-length check across %d resolvers", len(resolvers))
	}

	// Phase 2: probe downstream MTU staircase concurrently on all surviving (resolver, qtype) pairs.
	staircase := buildStaircase(cfg.ForcedMTU)
	candCh := make(chan Candidate, len(survivors))
	var wg2 sync.WaitGroup

	for _, s := range survivors {
		s := s
		wg2.Add(1)
		go func() {
			defer wg2.Done()

			emit(cfg, StatusEvent{
				Event: "probing",
				DNS:   s.resolver,
				QType: dnswire.RecordTypeString(s.qtype),
				Step:  "mtu_staircase",
			})

			probeRaw := protocol.MaxQueryPayloadForUDP(cfg.Domain, s.maxQueryUDP)

			type stepOut struct {
				mtu int
				ok  bool
				rtt time.Duration
			}
			resCh := make(chan stepOut, len(staircase))
			var swg sync.WaitGroup
			for _, mtuStep := range staircase {
				mtuStep := mtuStep
				// Skip probing an MTU that cannot even hold MinUsableKCPMTU for this QTYPE.
				if protocol.SafeKCPMTU(protocol.MaxResponsePayload(cfg.Domain, s.qtype, nil, mtuStep)) == 0 {
					continue
				}
				swg.Add(1)
				go func() {
					defer swg.Done()
					sem <- struct{}{}
					defer func() { <-sem }()
					rtt, ok := sendProbe(ctx, cfg.ListenPacket, s.resolver, cfg.Domain, cfg.Pubkey, s.qtype, uint16(mtuStep), probeRaw, timeout)
					resCh <- stepOut{mtu: mtuStep, ok: ok, rtt: rtt}
				}()
			}
			swg.Wait()
			close(resCh)

			bestBudget := 0
			bestRTT := s.rtt
			for out := range resCh {
				if out.ok && out.mtu > bestBudget {
					bestBudget = out.mtu
					if out.rtt > 0 {
						bestRTT = out.rtt
					}
				}
			}
			if bestBudget == 0 {
				// Fall back to 240B baseline validated in Phase 1 if staircase steps all exceeded or dropped.
				bestBudget = 240
			}
			payloadBytes := protocol.MaxResponsePayload(cfg.Domain, s.qtype, nil, bestBudget)
			kcpMTU := protocol.SafeKCPMTU(payloadBytes)
			if kcpMTU == 0 {
				return
			}

			score := scoreCandidate(cfg.Domain, s.resolver, resolvers, s.qtype, payloadBytes, s.maxQueryUDP, bestRTT)
			candCh <- Candidate{
				Resolver:     s.resolver,
				QType:        s.qtype,
				QTypeName:    dnswire.RecordTypeString(s.qtype),
				DownBudget:   bestBudget,
				MaxQueryUDP:  s.maxQueryUDP,
				PayloadBytes: payloadBytes,
				KCPMTU:       kcpMTU,
				RTT:          bestRTT,
				Score:        score,
			}
		}()
	}
	wg2.Wait()
	close(candCh)

	var candidates []Candidate
	for c := range candCh {
		candidates = append(candidates, c)
	}
	sort.Slice(candidates, func(i, j int) bool {
		if candidates[i].Score != candidates[j].Score {
			return candidates[i].Score > candidates[j].Score
		}
		return candidates[i].PayloadBytes > candidates[j].PayloadBytes
	})
	if len(candidates) == 0 {
		return nil, fmt.Errorf("solver: resolvers responded but usable KCP MTU could not be established")
	}
	return candidates, nil
}

func buildStaircase(forcedMTU int) []int {
	if forcedMTU <= 0 {
		out := make([]int, len(MTUStaircase))
		copy(out, MTUStaircase)
		return out
	}
	seen := make(map[int]struct{})
	out := []int{forcedMTU}
	seen[forcedMTU] = struct{}{}
	for _, s := range MTUStaircase {
		if s < forcedMTU {
			if _, ok := seen[s]; !ok {
				out = append(out, s)
				seen[s] = struct{}{}
			}
		}
	}
	return out
}

func scoreCandidate(domain dnswire.Name, resolver string, orderedResolvers []string, qtype uint16, payloadBytes, maxQueryUDP int, rtt time.Duration) float64 {
	rttMs := float64(rtt.Milliseconds())
	if rttMs < 15 {
		rttMs = 15 // dampen sub-ms local/edge anomalies so tiny probes don't falsely beat 4x bigger payload
	}
	upBytes := float64(protocol.MaxQueryPayloadForUDP(domain, maxQueryUDP) - protocol.NativeHeaderLen)
	if upBytes < 20 {
		upBytes = 20
	}
	// Primary metric: usable downstream payload per packet adjusted gently by RTT and upstream bandwidth.
	score := (float64(payloadBytes) * (1.0 + upBytes/200.0)) / (1.0 + rttMs/250.0)

	// Prefer HTTPS / SVCB / TXT / NULL on near-ties since single-RR binary records avoid name fragmentation.
	switch qtype {
	case dnswire.RRTypeHTTPS, dnswire.RRTypeSVCB, dnswire.RRTypeTXT:
		score *= 1.08
	case dnswire.RRTypeNULL:
		score *= 1.04
	}
	// Slight preference for earlier user-listed resolvers (e.g., active carrier DNS before public fallback).
	for idx, r := range orderedResolvers {
		if r == resolver {
			score *= 1.0 + float64(max(0, 5-idx))*0.01
			break
		}
	}
	return score
}

func sendProbe(ctx context.Context, listenPacket tunnel.PacketFactory, resolver string, domain dnswire.Name, pubkey []byte, qtype uint16, targetRespBytes uint16, rawLen int, timeout time.Duration) (time.Duration, bool) {
	pCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	rAddr, err := net.ResolveUDPAddr("udp", resolver)
	if err != nil {
		return 0, false
	}
	netFam := "udp4"
	if rAddr.IP != nil && rAddr.IP.To4() == nil && !strings.Contains(resolver, ".") {
		netFam = "udp6"
	}
	var conn net.PacketConn
	if listenPacket != nil {
		conn, err = listenPacket(pCtx, netFam, ":0")
	} else {
		conn, err = net.ListenPacket(netFam, ":0")
	}
	if err != nil {
		return 0, false
	}
	defer conn.Close()

	reqPayload, req, err := protocol.EncodeProbeRequest(pubkey, targetRespBytes, rawLen)
	if err != nil {
		return 0, false
	}
	qname, err := dnswire.EncodeQueryName(reqPayload, domain)
	if err != nil {
		return 0, false
	}
	msg := dnswire.Message{
		ID:         uint16(time.Now().UnixNano() ^ int64(targetRespBytes) ^ int64(qtype)),
		Flags:      0x0100,
		Question:   []dnswire.Question{{Name: qname, Type: qtype, Class: dnswire.ClassIN}},
		Additional: []dnswire.RR{{Type: dnswire.RRTypeOPT, Class: max(uint16(512), targetRespBytes)}},
	}
	wire, err := msg.WireFormat()
	if err != nil {
		return 0, false
	}

	dl, _ := pCtx.Deadline()
	_ = conn.SetDeadline(dl)

	start := time.Now()
	if _, err := conn.WriteTo(wire, rAddr); err != nil {
		return 0, false
	}

	buf := make([]byte, 4096)
	for {
		n, _, err := conn.ReadFrom(buf)
		if err != nil {
			return 0, false
		}
		resp, err := dnswire.MessageFromWireFormat(buf[:n])
		if err != nil || resp.ID != msg.ID || resp.Flags&0x8000 == 0 || resp.Rcode() != dnswire.RcodeNoError {
			continue
		}
		payload, err := dnswire.DecodeResponseRRs(&resp, qtype, domain)
		if err != nil {
			continue
		}
		if _, err := protocol.DecodeProbeResponse(pubkey, req.Nonce, qtype, payload); err != nil {
			continue
		}
		return time.Since(start), true
	}
}

// StripedResolvers selects up to 3 verified resolvers matching cand's (QType, DownBudget, MaxQueryUDP)
// to multiply effective throughput across per-IP recursive QPS limits.
func StripedResolvers(candidates []Candidate, cand Candidate) []string {
	out := []string{cand.Resolver}
	maxRTT := cand.RTT * 2
	if maxRTT < 120*time.Millisecond {
		maxRTT = 120 * time.Millisecond
	}
	for _, other := range candidates {
		if len(out) >= 3 {
			break
		}
		if other.Resolver == cand.Resolver || other.QType != cand.QType ||
			other.DownBudget != cand.DownBudget || other.MaxQueryUDP != cand.MaxQueryUDP {
			continue
		}
		if other.RTT <= maxRTT {
			out = append(out, other.Resolver)
		}
	}
	return out
}

// RunManaged starts a persistent local TCP forwarder backed by automatic candidate selection
// and runtime failover when the carrier stalls or drops a previously working path.
func RunManaged(ctx context.Context, cfg Config, listenAddr string, stallTimeouts int) error {
	if stallTimeouts <= 0 {
		stallTimeouts = 18
	}
	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		return fmt.Errorf("solver: listen %s: %w", listenAddr, err)
	}
	defer ln.Close()

	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()

	var mu sync.RWMutex
	var active *tunnel.Client

	// Accept incoming local TCP connections cleanly and hand them to whichever tunnel Client is active.
	go func() {
		for {
			tc, err := ln.Accept()
			if err != nil {
				return
			}
			mu.RLock()
			cli := active
			mu.RUnlock()
			if cli == nil || cli.IsClosed() {
				_ = tc.Close()
				continue
			}
			go forwardConn(cli, tc)
		}
	}()

	for ctx.Err() == nil {
		candidates, err := ProbeAll(ctx, cfg)
		if err != nil {
			emit(cfg, StatusEvent{Event: "error", Reason: err.Error()})
			select {
			case <-ctx.Done():
				return nil
			case <-time.After(2 * time.Second):
				continue
			}
		}

		for i, cand := range candidates {
			if ctx.Err() != nil {
				return nil
			}
			emit(cfg, StatusEvent{
				Event:    "selected",
				DNS:      cand.Resolver,
				QType:    cand.QTypeName,
				MTU:      cand.DownBudget,
				QueryCap: cand.MaxQueryUDP,
				RTTMs:    cand.RTT.Milliseconds(),
				Score:    cand.Score,
			})

			dialCtx, cancelDial := context.WithTimeout(ctx, 12*time.Second)
			cli, err := tunnel.DialClient(dialCtx, tunnel.ClientConfig{
				DNSServerAddr:  cand.Resolver,
				DNSServerAddrs: StripedResolvers(candidates, cand),
				Domain:         cfg.Domain,
				Pubkey:         cfg.Pubkey,
				QType:          cand.QType,
				DownBudget:     uint16(cand.DownBudget),
				MaxQueryUDP:    cand.MaxQueryUDP,
				ListenPacket:   cfg.ListenPacket,
			})
			cancelDial()
			if err != nil {
				nextQT := ""
				if i+1 < len(candidates) {
					nextQT = candidates[i+1].QTypeName
				}
				emit(cfg, StatusEvent{
					Event:     "fallback",
					DNS:       cand.Resolver,
					QType:     cand.QTypeName,
					Reason:    err.Error(),
					NextQType: nextQT,
				})
				continue
			}

			mu.Lock()
			active = cli
			mu.Unlock()

			emit(cfg, StatusEvent{
				Event:  "connected",
				DNS:    cand.Resolver,
				QType:  cand.QTypeName,
				MTU:    cand.DownBudget,
				Listen: ln.Addr().String(),
			})

			reason := waitUntilStallOrClosed(ctx, cli, stallTimeouts)
			mu.Lock()
			if active == cli {
				active = nil
			}
			mu.Unlock()
			_ = cli.Close()

			if ctx.Err() != nil {
				return nil
			}
			nextQT := ""
			if i+1 < len(candidates) {
				nextQT = candidates[i+1].QTypeName
			}
			emit(cfg, StatusEvent{
				Event:     "fallback",
				DNS:       cand.Resolver,
				QType:     cand.QTypeName,
				Reason:    reason,
				NextQType: nextQT,
			})
		}
	}
	return nil
}

func waitUntilStallOrClosed(ctx context.Context, cli *tunnel.Client, stallSeconds int) string {
	if stallSeconds <= 0 {
		stallSeconds = 12
	}
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return "canceled"
		case <-ticker.C:
			if cli.IsClosed() {
				return "session_closed"
			}
			lastReply := time.Unix(0, cli.LastValidReplyNs.Load())
			if cli.SentQueries.Load() > 5 && time.Since(lastReply) >= time.Duration(stallSeconds)*time.Second {
				return "carrier_stall"
			}
		}
	}
}

func forwardConn(cli *tunnel.Client, tc net.Conn) {
	defer tc.Close()
	st, err := cli.OpenStream()
	if err != nil {
		return
	}
	defer st.Close()

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_, _ = copyBuf(st, tc)
		_ = st.Close()
	}()
	go func() {
		defer wg.Done()
		_, _ = copyBuf(tc, st)
		if tconn, ok := tc.(*net.TCPConn); ok {
			_ = tconn.CloseWrite()
		}
	}()
	wg.Wait()
}

func copyBuf(dst interface{ Write([]byte) (int, error) }, src interface{ Read([]byte) (int, error) }) (int64, error) {
	buf := make([]byte, 32*1024)
	var written int64
	for {
		nr, er := src.Read(buf)
		if nr > 0 {
			nw, ew := dst.Write(buf[:nr])
			if nw > 0 {
				written += int64(nw)
			}
			if ew != nil {
				return written, ew
			}
			if nr != nw {
				return written, fmt.Errorf("short write")
			}
		}
		if er != nil {
			return written, er
		}
	}
}
