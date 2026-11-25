package tunnel

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"

	vxcrypto "vexotun/core/vexdns/crypto"
	"vexotun/core/vexdns/dnswire"
	"vexotun/core/vexdns/protocol"
)

// PacketFactory optionally creates protected UDP sockets (e.g. Android VpnService.protect).
type PacketFactory func(ctx context.Context, network, address string) (net.PacketConn, error)

// ClientConfig configures a Vexora Native client tunnel session.
type ClientConfig struct {
	DNSServerAddr  string
	DNSServerAddrs []string // Optional multi-resolver list sharing identical (QType, DownBudget)
	Domain         dnswire.Name
	Pubkey         []byte
	QType          uint16
	DownBudget     uint16 // Maximum downstream DNS UDP bytes solved for this carrier
	MaxQueryUDP    int    // 0 = unrestricted full 255B QNAME; >0 caps query wire size
	IdleTimeout    time.Duration
	KeepAlive      time.Duration
	KCPWindow      int
	MaxInFlight    int
	ListenPacket   PacketFactory
}

type udpTarget struct {
	conn            net.PacketConn
	addr            net.Addr
	consecMisses    atomic.Int64
	cooldownUntilNs atomic.Int64
	lastReplyNs     atomic.Int64
}

type inflightEntry struct {
	sentAt    time.Time
	targetIdx int
}

// Client manages one authenticated KCP + Noise_NK + smux session over DNS.
type Client struct {
	cfg      ClientConfig
	clientID [4]byte
	addr     protocol.ClientAddr

	targets    []*udpTarget
	targetSeq  atomic.Uint64
	ttConn     *QueuePacketConn
	kcpConn    *kcp.UDPSession
	noiseRW    io.ReadWriteCloser
	smuxSess   *smux.Session

	maxUpPayload int
	txNotify     chan struct{}

	inflightMu sync.Mutex
	inflight   map[uint16]inflightEntry

	closeOnce sync.Once
	closeCh   chan struct{}

	SentQueries      atomic.Uint64
	ValidReplies     atomic.Uint64
	EmptyReplies     atomic.Uint64
	ConsecTimeouts   atomic.Int64
	LastValidReplyNs atomic.Int64
	lastDataRxNs     atomic.Int64
	lastDataTxNs     atomic.Int64
	RxBytes          atomic.Uint64
	TxBytes          atomic.Uint64
}

// DialClient performs the UDP bind + KCP + Noise_NK + smux setup over cfg.DNSServerAddr.
func DialClient(ctx context.Context, cfg ClientConfig) (*Client, error) {
	if len(cfg.Pubkey) != vxcrypto.KeyLen {
		return nil, errors.New("tunnel: invalid server pubkey")
	}
	if !dnswire.IsSupportedRecordType(cfg.QType) {
		return nil, fmt.Errorf("tunnel: unsupported QTYPE %d", cfg.QType)
	}
	if cfg.DownBudget == 0 {
		cfg.DownBudget = protocol.MaxServerUDPPayload
	}
	if cfg.IdleTimeout <= 0 {
		cfg.IdleTimeout = defaultIdleTimeout
	}
	if cfg.KeepAlive <= 0 {
		cfg.KeepAlive = defaultKeepAlive
	}
	if cfg.KCPWindow <= 0 {
		cfg.KCPWindow = 512
	}
	addrsRaw := cfg.DNSServerAddrs
	if len(addrsRaw) == 0 && cfg.DNSServerAddr != "" {
		addrsRaw = []string{cfg.DNSServerAddr}
	}
	if len(addrsRaw) == 0 {
		return nil, errors.New("tunnel: no DNS server address specified")
	}
	if cfg.MaxInFlight <= 0 {
		if len(addrsRaw) >= 2 {
			cfg.MaxInFlight = 72
		} else {
			cfg.MaxInFlight = 64
		}
	}

	openPacket := cfg.ListenPacket
	if openPacket == nil {
		openPacket = func(_ context.Context, network, address string) (net.PacketConn, error) {
			return net.ListenPacket(network, address)
		}
	}

	targets := make([]*udpTarget, 0, len(addrsRaw))
	for _, rawAddr := range addrsRaw {
		rAddr, err := net.ResolveUDPAddr("udp", rawAddr)
		if err != nil {
			for _, t := range targets {
				_ = t.conn.Close()
			}
			return nil, fmt.Errorf("tunnel: resolve dns addr %q: %w", rawAddr, err)
		}
		netFam := "udp4"
		if rAddr.IP != nil && rAddr.IP.To4() == nil && !strings.Contains(rawAddr, ".") {
			netFam = "udp6"
		}
		pc, err := openPacket(ctx, netFam, ":0")
		if err != nil {
			for _, t := range targets {
				_ = t.conn.Close()
			}
			return nil, fmt.Errorf("tunnel: listen %s for %s: %w", netFam, rawAddr, err)
		}
		targets = append(targets, &udpTarget{conn: pc, addr: rAddr})
	}
	closeTargets := func() {
		for _, t := range targets {
			_ = t.conn.Close()
		}
	}

	var id [4]byte
	if _, err := rand.Read(id[:]); err != nil {
		closeTargets()
		return nil, err
	}
	var cid [8]byte
	copy(cid[:4], id[:])
	remoteAddr := protocol.ClientAddr{Mode: protocol.ModeVexora, ID: cid}

	maxRawQ := protocol.MaxQueryPayloadForUDP(cfg.Domain, cfg.MaxQueryUDP)
	upChunkCap := maxRawQ - protocol.NativeHeaderLen - 1 // 1B pktLen
	if upChunkCap < 28 {
		closeTargets()
		return nil, fmt.Errorf("tunnel: query budget %d leaves insufficient upstream payload (%dB)", cfg.MaxQueryUDP, upChunkCap)
	}

	downLimit := protocol.MaxResponsePayload(cfg.Domain, cfg.QType, nil, int(cfg.DownBudget))
	if protocol.SafeKCPMTU(downLimit) == 0 {
		closeTargets()
		return nil, fmt.Errorf("tunnel: down budget %d insufficient for %s", cfg.DownBudget, dnswire.RecordTypeString(cfg.QType))
	}
	// Upstream QNAME segments must fit inside upChunkCap while downstream segments independently use Server's downMTU.
	sessionMTU := upChunkCap
	if sessionMTU < protocol.MinUsableKCPMTU {
		sessionMTU = protocol.MinUsableKCPMTU
	}

	ttConn := NewQueuePacketConn(&dummyAddr{s: "vexdns-client"}, cfg.IdleTimeout, 1024)
	txNotify := make(chan struct{}, 1)
	ttConn.SetOnWrite(func(_ net.Addr) {
		select {
		case txNotify <- struct{}{}:
		default:
		}
	})

	var conv uint32
	if err := binary.Read(rand.Reader, binary.BigEndian, &conv); err != nil {
		_ = ttConn.Close()
		closeTargets()
		return nil, err
	}
	if conv == 0 {
		conv = 1
	}

	kcpConn, err := kcp.NewConn2(remoteAddr, nil, 0, 0, ttConn)
	if err != nil {
		_ = ttConn.Close()
		closeTargets()
		return nil, err
	}
	kcpConn.SetStreamMode(true)
	kcpConn.SetNoDelay(1, 10, 1, 1)
	kcpConn.SetWriteDelay(false)
	kcpConn.SetACKNoDelay(true)
	kcpConn.SetWindowSize(cfg.KCPWindow, cfg.KCPWindow)
	if !kcpConn.SetMtu(sessionMTU) {
		kcpConn.SetMtu(protocol.MinUsableKCPMTU)
	}

	c := &Client{
		cfg:          cfg,
		clientID:     id,
		addr:         remoteAddr,
		targets:      targets,
		ttConn:       ttConn,
		kcpConn:      kcpConn,
		maxUpPayload: maxRawQ - protocol.NativeHeaderLen,
		txNotify:     txNotify,
		inflight:     make(map[uint16]inflightEntry, 96),
		closeCh:      make(chan struct{}),
	}
	nowNs := time.Now().UnixNano()
	c.LastValidReplyNs.Store(nowNs)

	for i := range c.targets {
		c.targets[i].lastReplyNs.Store(nowNs)
		go c.dnsRecvLoop(i, c.targets[i].conn)
	}
	go c.dnsPollLoop()

	handshakeTimeout := 12 * time.Second
	if dl, ok := ctx.Deadline(); ok {
		if d := time.Until(dl); d > 0 && d < handshakeTimeout {
			handshakeTimeout = d
		}
	}
	_ = kcpConn.SetDeadline(time.Now().Add(handshakeTimeout))
	noiseRW, err := vxcrypto.WrapClient(kcpConn, cfg.Pubkey)
	if err != nil {
		_ = kcpConn.SetDeadline(time.Time{})
		_ = c.Close()
		return nil, fmt.Errorf("tunnel: noise handshake (%s mtu=%d): %w", dnswire.RecordTypeString(cfg.QType), sessionMTU, err)
	}
	c.noiseRW = noiseRW

	smuxCfg := smux.DefaultConfig()
	smuxCfg.Version = 2
	smuxCfg.KeepAliveInterval = cfg.KeepAlive
	smuxCfg.KeepAliveTimeout = cfg.IdleTimeout
	smuxCfg.MaxStreamBuffer = 1 * 1024 * 1024
	sess, err := smux.Client(noiseRW, smuxCfg)
	if err != nil {
		_ = c.Close()
		return nil, fmt.Errorf("tunnel: smux init: %w", err)
	}
	c.smuxSess = sess
	return c, nil
}

// OpenStream opens a multiplexed stream over the tunnel (typically to upstream SSH Dropbear).
func (c *Client) OpenStream() (*smux.Stream, error) {
	if c.smuxSess == nil {
		return nil, errors.New("tunnel: session not ready")
	}
	return c.smuxSess.OpenStream()
}

// ServeTCP accepts TCP connections on ln and forwards them through the tunnel until Close or ln error.
func (c *Client) ServeTCP(ln net.Listener) error {
	for {
		tcpConn, err := ln.Accept()
		if err != nil {
			select {
			case <-c.closeCh:
				return nil
			default:
			}
			return err
		}
		go c.handleTCP(tcpConn)
	}
}

func (c *Client) handleTCP(tcpConn net.Conn) {
	defer tcpConn.Close()
	stream, err := c.OpenStream()
	if err != nil {
		return
	}
	defer stream.Close()

	// Wake poll loop immediately when TCP traffic begins.
	select {
	case c.txNotify <- struct{}{}:
	default:
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		n, _ := io.Copy(stream, tcpConn)
		c.TxBytes.Add(uint64(n))
		_ = stream.Close()
	}()
	go func() {
		defer wg.Done()
		n, _ := io.Copy(tcpConn, stream)
		c.RxBytes.Add(uint64(n))
		if tc, ok := tcpConn.(*net.TCPConn); ok {
			_ = tc.CloseWrite()
		}
	}()
	wg.Wait()
}

// IsClosed returns true if the client session has terminated or smux closed.
func (c *Client) IsClosed() bool {
	select {
	case <-c.closeCh:
		return true
	default:
	}
	return c.smuxSess != nil && c.smuxSess.IsClosed()
}

// IsHealthy returns false if the client has sent queries recently without any valid DNS reply for maxSilence.
func (c *Client) IsHealthy(maxSilence time.Duration) bool {
	if c.IsClosed() {
		return false
	}
	if c.SentQueries.Load() <= 8 {
		return true
	}
	lastReply := time.Unix(0, c.LastValidReplyNs.Load())
	return time.Since(lastReply) <= maxSilence
}

// Close shuts down smux, Noise, KCP, and the underlying UDP socket.
func (c *Client) Close() error {
	c.closeOnce.Do(func() {
		close(c.closeCh)
		if c.smuxSess != nil {
			_ = c.smuxSess.Close()
		}
		if c.noiseRW != nil {
			_ = c.noiseRW.Close()
		}
		if c.kcpConn != nil {
			_ = c.kcpConn.Close()
		}
		if c.ttConn != nil {
			_ = c.ttConn.Close()
		}
		for _, t := range c.targets {
			_ = t.conn.Close()
		}
	})
	return nil
}

func (c *Client) dnsRecvLoop(targetIdx int, conn net.PacketConn) {
	buf := make([]byte, 4096)
	for {
		n, _, err := conn.ReadFrom(buf)
		if err != nil {
			return
		}
		msg, err := dnswire.MessageFromWireFormat(buf[:n])
		if err != nil || msg.Flags&0x8000 == 0 {
			continue
		}
		c.inflightMu.Lock()
		delete(c.inflight, msg.ID)
		c.inflightMu.Unlock()

		if msg.Rcode() != dnswire.RcodeNoError {
			continue
		}
		now := time.Now().UnixNano()
		c.ValidReplies.Add(1)
		c.ConsecTimeouts.Store(0)
		c.LastValidReplyNs.Store(now)
		if targetIdx >= 0 && targetIdx < len(c.targets) {
			c.targets[targetIdx].consecMisses.Store(0)
			c.targets[targetIdx].cooldownUntilNs.Store(0)
			c.targets[targetIdx].lastReplyNs.Store(now)
		}

		if len(msg.Answer) == 0 {
			c.EmptyReplies.Add(1)
			continue
		}
		raw, err := dnswire.DecodeResponseRRs(&msg, c.cfg.QType, c.cfg.Domain)
		if err != nil || len(raw) == 0 {
			c.EmptyReplies.Add(1)
			continue
		}
		pkts, err := protocol.UnpackDownstreamFrames(raw)
		if err != nil {
			continue
		}
		for _, pkt := range pkts {
			c.ttConn.QueueIncoming(pkt, c.addr)
		}
		if len(pkts) > 0 {
			c.lastDataRxNs.Store(now)
			select {
			case c.txNotify <- struct{}{}:
			default:
			}
		}
	}
}

const (
	inflightExpire   = 900 * time.Millisecond
	targetMissLimit  = 6
	targetCooldown   = 15 * time.Second
)

func (c *Client) activeInFlight() (int, int) {
	now := time.Now()
	nowNs := now.UnixNano()
	expired := 0
	c.inflightMu.Lock()
	for id, entry := range c.inflight {
		if now.Sub(entry.sentAt) > inflightExpire {
			delete(c.inflight, id)
			expired++
			if entry.targetIdx >= 0 && entry.targetIdx < len(c.targets) {
				misses := c.targets[entry.targetIdx].consecMisses.Add(1)
				if len(c.targets) > 1 && misses >= targetMissLimit {
					c.targets[entry.targetIdx].cooldownUntilNs.Store(nowNs + int64(targetCooldown))
				}
			}
		}
	}
	n := len(c.inflight)
	c.inflightMu.Unlock()
	return n, expired
}

func (c *Client) pickTargetIdx() int {
	n := len(c.targets)
	if n <= 1 {
		return 0
	}
	nowNs := time.Now().UnixNano()
	start := int((c.targetSeq.Add(1) - 1) % uint64(n))
	for offset := 0; offset < n; offset++ {
		idx := (start + offset) % n
		if c.targets[idx].cooldownUntilNs.Load() <= nowNs {
			return idx
		}
	}
	// All targets in cooldown: reset cooldowns and fall back to round-robin.
	for i := range c.targets {
		c.targets[i].cooldownUntilNs.Store(0)
		c.targets[i].consecMisses.Store(0)
	}
	return start
}

func (c *Client) dnsPollLoop() {
	var msgID uint16 = 1
	idleWait := 12 * time.Millisecond
	const maxIdleWait = 280 * time.Millisecond
	curWindow := 32
	if c.cfg.MaxInFlight < curWindow {
		curWindow = c.cfg.MaxInFlight
	}

	ticker := time.NewTimer(idleWait)
	defer ticker.Stop()

	sendOne := func(pkts [][]byte) {
		payload, err := protocol.EncodeVexoraQuery(c.clientID, c.cfg.DownBudget, pkts)
		if err != nil {
			return
		}
		qname, err := dnswire.EncodeQueryName(payload, c.cfg.Domain)
		if err != nil {
			return
		}
		msgID++
		if msgID == 0 {
			msgID = 1
		}
		q := dnswire.Message{
			ID:       msgID,
			Flags:    0x0100, // RD=1
			Question: []dnswire.Question{{Name: qname, Type: c.cfg.QType, Class: dnswire.ClassIN}},
			Additional: []dnswire.RR{{
				Name:  dnswire.Name{},
				Type:  dnswire.RRTypeOPT,
				Class: c.cfg.DownBudget,
			}},
		}
		wire, err := q.WireFormat()
		if err != nil {
			return
		}
		tIdx := c.pickTargetIdx()
		c.inflightMu.Lock()
		c.inflight[msgID] = inflightEntry{sentAt: time.Now(), targetIdx: tIdx}
		c.inflightMu.Unlock()
		c.SentQueries.Add(1)
		tgt := c.targets[tIdx]
		_, _ = tgt.conn.WriteTo(wire, tgt.addr)
	}

	isBurstActive := func() bool {
		now := time.Now().UnixNano()
		const burstWindow = int64(1200 * time.Millisecond)
		return (now-c.lastDataRxNs.Load()) < burstWindow || (now-c.lastDataTxNs.Load()) < burstWindow
	}

	// Kick off initial queries to prime downstream server slots for fast handshake.
	sendOne(nil)
	sendOne(nil)

	lastReplies := uint64(0)
	for {
		active, expired := c.activeInFlight()
		if expired > 0 {
			curWindow -= max(2, expired/2)
			if curWindow < 12 {
				curWindow = 12
			}
		} else if isBurstActive() && curWindow < c.cfg.MaxInFlight {
			curWindow += 2
			if curWindow > c.cfg.MaxInFlight {
				curWindow = c.cfg.MaxInFlight
			}
		}

		// 1. Drain any queued upstream KCP segments cleanly up to generous upstream cap.
		sentBurst := false
		maxUpInFlight := curWindow + 16
		for active < maxUpInFlight {
			pkts := c.ttConn.PopUpstreamUpTo(c.addr, c.maxUpPayload)
			if len(pkts) == 0 {
				break
			}
			c.lastDataTxNs.Store(time.Now().UnixNano())
			sendOne(pkts)
			active++
			sentBurst = true
		}

		// 2. Smooth paced pipeline refill (max 8 polls per 5ms tick to avoid microburst drops).
		if isBurstActive() && active < curWindow {
			need := curWindow - active
			if need > 8 {
				need = 8
			}
			for i := 0; i < need; i++ {
				sendOne(nil)
				sentBurst = true
			}
		}
		if sentBurst || isBurstActive() {
			idleWait = 5 * time.Millisecond
		}

		if !ticker.Stop() {
			select {
			case <-ticker.C:
			default:
			}
		}
		ticker.Reset(idleWait)

		select {
		case <-c.closeCh:
			return
		case <-c.txNotify:
			idleWait = 5 * time.Millisecond
		case <-ticker.C:
			curReplies := c.ValidReplies.Load()
			if curReplies == lastReplies {
				if time.Since(time.Unix(0, c.LastValidReplyNs.Load())) >= time.Second {
					c.ConsecTimeouts.Add(1)
				}
			}
			lastReplies = curReplies

			if active == 0 && !isBurstActive() {
				sendOne(nil)
			}
			if !isBurstActive() && idleWait < maxIdleWait {
				idleWait = minDuration(idleWait*2, maxIdleWait)
			}
		}
	}
}

func minDuration(a, b time.Duration) time.Duration {
	if a < b {
		return a
	}
	return b
}
