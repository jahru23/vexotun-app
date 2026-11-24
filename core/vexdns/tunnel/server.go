package tunnel

import (
	"context"
	"errors"
	"io"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"

	vxcrypto "vexotun/core/vexdns/crypto"
	"vexotun/core/vexdns/dnswire"
	"vexotun/core/vexdns/protocol"
)

const (
	defaultIdleTimeout = 2 * time.Minute
	defaultKeepAlive   = 10 * time.Second
	defaultPollHold    = 600 * time.Millisecond
	defaultCoalesce    = 2 * time.Millisecond
	responseTTL        = 60
)

// ServerConfig configures the dual-mode VexDNS server.
type ServerConfig struct {
	ListenAddr   string
	Domain       dnswire.Name
	UpstreamAddr string
	Privkey      []byte
	Pubkey       []byte
	MaxUDP       int
	CompatDnstt  bool
	IdleTimeout  time.Duration
	KeepAlive    time.Duration
	KCPWindow    int
	QueueSize    int
	Debug        bool
}

type peerMeta struct {
	mu         sync.RWMutex
	mode       protocol.ClientMode
	qtype      uint16
	downBudget uint16
	ednsUDP    int
}

type querySlot struct {
	query      dnswire.Message
	udpAddr    net.Addr
	clientAddr protocol.ClientAddr
	qtype      uint16
	maxUDP     int
	receivedAt time.Time
}

type clientResponder struct {
	server *Server
	addr   protocol.ClientAddr
	slotCh chan *querySlot
	stopCh chan struct{}
}

// Server terminates Vexora Native & dnstt-compat DNS tunnels and forwards TCP streams to UpstreamAddr.
type Server struct {
	cfg        ServerConfig
	dnsConn    net.PacketConn
	ttConn     *QueuePacketConn
	kcpLn      *kcp.Listener
	peerMetas  sync.Map // clientAddr.String() -> *peerMeta
	responders sync.Map // clientAddr.String() -> *clientResponder
	wg         sync.WaitGroup
	stopOnce   sync.Once
	stopCh     chan struct{}

	TotalQueries atomic.Uint64
	ProbeReplies atomic.Uint64
	TunnelQueries atomic.Uint64
	ActiveSess   atomic.Int64
}

// NewServer binds dnsConn (optional pre-bound PacketConn or nil to bind ListenAddr) and initializes Server.
func NewServer(cfg ServerConfig, dnsConn net.PacketConn) (*Server, error) {
	if len(cfg.Privkey) != vxcrypto.KeyLen {
		return nil, errors.New("tunnel: invalid server private key")
	}
	if len(cfg.Pubkey) != vxcrypto.KeyLen {
		pub, err := vxcrypto.PubkeyFromPrivkey(cfg.Privkey)
		if err != nil {
			return nil, err
		}
		cfg.Pubkey = pub
	}
	if cfg.MaxUDP < 256 || cfg.MaxUDP > protocol.MaxServerUDPPayload {
		if cfg.MaxUDP <= 0 {
			cfg.MaxUDP = protocol.MaxServerUDPPayload
		}
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
	if cfg.QueueSize <= 0 {
		cfg.QueueSize = 1024
	}
	var err error
	if dnsConn == nil {
		dnsConn, err = net.ListenPacket("udp", cfg.ListenAddr)
		if err != nil {
			return nil, err
		}
	}

	ttConn := NewQueuePacketConn(&dummyAddr{s: "vexdns-server"}, cfg.IdleTimeout, cfg.QueueSize)
	ln, err := kcp.ServeConn(nil, 0, 0, ttConn)
	if err != nil {
		_ = ttConn.Close()
		_ = dnsConn.Close()
		return nil, err
	}

	s := &Server{
		cfg:     cfg,
		dnsConn: dnsConn,
		ttConn:  ttConn,
		kcpLn:   ln,
		stopCh:  make(chan struct{}),
	}
	return s, nil
}

// LocalUDPAddr returns the bound UDP address of the DNS socket (useful in tests using ":0").
func (s *Server) LocalUDPAddr() net.Addr {
	return s.dnsConn.LocalAddr()
}

// Serve runs recvLoop and KCP acceptSessions until ctx is canceled or Close is called.
func (s *Server) Serve(ctx context.Context) error {
	go func() {
		select {
		case <-ctx.Done():
			_ = s.Close()
		case <-s.stopCh:
		}
	}()

	errCh := make(chan error, 2)
	s.wg.Add(2)
	go func() {
		defer s.wg.Done()
		errCh <- s.recvLoop()
	}()
	go func() {
		defer s.wg.Done()
		errCh <- s.acceptSessions()
	}()

	err := <-errCh
	_ = s.Close()
	if errors.Is(err, net.ErrClosed) || ctx.Err() != nil {
		return nil
	}
	return err
}

// Close shuts down listeners and active responders.
func (s *Server) Close() error {
	s.stopOnce.Do(func() {
		close(s.stopCh)
		_ = s.kcpLn.Close()
		_ = s.ttConn.Close()
		_ = s.dnsConn.Close()
		s.responders.Range(func(k, v any) bool {
			r := v.(*clientResponder)
			close(r.stopCh)
			s.responders.Delete(k)
			return true
		})
	})
	return nil
}

func (s *Server) recvLoop() error {
	buf := make([]byte, 4096)
	for {
		n, addr, err := s.dnsConn.ReadFrom(buf)
		if err != nil {
			if ne, ok := err.(net.Error); ok && ne.Temporary() {
				continue
			}
			return err
		}
		s.TotalQueries.Add(1)

		msg, err := dnswire.MessageFromWireFormat(buf[:n])
		if err != nil || len(msg.Question) != 1 || msg.Flags&0x8000 != 0 {
			continue
		}
		q := msg.Question[0]
		if q.Class != dnswire.ClassIN || !dnswire.IsSupportedRecordType(q.Type) {
			s.sendSimpleError(&msg, dnswire.RcodeNameError, addr)
			continue
		}

		rawPayload, err := dnswire.DecodeQueryName(q.Name, s.cfg.Domain)
		if err != nil {
			s.sendSimpleError(&msg, dnswire.RcodeNameError, addr)
			continue
		}

		// Fast path 1: Vexora Stateless Probe (VXP1)
		if req, isProbe, perr := protocol.DecodeProbeRequest(s.cfg.Pubkey, rawPayload); isProbe {
			if perr != nil {
				// Invalid MAC: silent drop prevents third-party DNS amplification abuse.
				continue
			}
			s.handleProbe(&msg, q, req, addr)
			continue
		}

		// Path 2: Vexora Native (VXT1) or dnstt-compat
		parsed, perr := protocol.DecodeQueryPayload(rawPayload)
		if perr != nil {
			s.sendSimpleError(&msg, dnswire.RcodeNameError, addr)
			continue
		}
		if parsed.Mode == protocol.ModeCompat && !s.cfg.CompatDnstt {
			s.sendSimpleError(&msg, dnswire.RcodeRefused, addr)
			continue
		}

		ednsUDP := msg.EDNS0UDPSize()
		s.TunnelQueries.Add(1)
		s.updatePeerMeta(parsed, q.Type, ednsUDP)

		for _, pkt := range parsed.Packets {
			s.ttConn.QueueIncoming(pkt, parsed.Addr)
		}

		effUDP := s.effectiveUDPLimit(parsed.Mode, parsed.DownBudget, ednsUDP)
		slot := &querySlot{
			query:      msg,
			udpAddr:    addr,
			clientAddr: parsed.Addr,
			qtype:      q.Type,
			maxUDP:     effUDP,
			receivedAt: time.Now(),
		}
		s.dispatchSlot(slot)
	}
}

func (s *Server) effectiveUDPLimit(mode protocol.ClientMode, downBudget uint16, ednsUDP int) int {
	limit := s.cfg.MaxUDP
	if ednsUDP > 256 && ednsUDP < limit {
		limit = ednsUDP
	}
	if mode == protocol.ModeVexora && downBudget > 128 && int(downBudget) < limit {
		limit = int(downBudget)
	}
	return limit
}

func (s *Server) handleProbe(query *dnswire.Message, q dnswire.Question, req protocol.ProbeRequest, addr net.Addr) {
	ednsUDP := query.EDNS0UDPSize()
	budget := int(req.TargetRespBytes)
	if budget <= 0 || budget > s.cfg.MaxUDP {
		budget = s.cfg.MaxUDP
	}
	if ednsUDP > 256 && budget > ednsUDP {
		budget = ednsUDP
	}

	payloadCap := protocol.MaxResponsePayload(s.cfg.Domain, q.Type, &q, budget)
	if payloadCap < protocol.ProbeRespFixedLen {
		payloadCap = protocol.ProbeRespFixedLen
	}
	// Avoid integer wrap on large responses for tiny TargetRespBytes.
	maxByName := payloadCap
	timeLo := uint32(time.Now().UnixMilli() & 0xffffffff)
	data, err := protocol.EncodeProbeResponse(s.cfg.Pubkey, req, q.Type, uint16(s.cfg.MaxUDP), timeLo, maxByName)
	if err != nil {
		return
	}

	resp := s.buildBaseResponse(query)
	if err := dnswire.EncodeResponseRRs(resp, q, data, s.cfg.Domain, 0); err != nil {
		return
	}
	wire, err := resp.WireFormat()
	if err != nil {
		return
	}
	s.ProbeReplies.Add(1)
	_, _ = s.dnsConn.WriteTo(wire, addr)
}

func (s *Server) updatePeerMeta(parsed protocol.ParsedQuery, qtype uint16, ednsUDP int) {
	key := parsed.Addr.String()
	val, ok := s.peerMetas.Load(key)
	if !ok {
		pm := &peerMeta{
			mode:       parsed.Mode,
			qtype:      qtype,
			downBudget: parsed.DownBudget,
			ednsUDP:    ednsUDP,
		}
		actual, loaded := s.peerMetas.LoadOrStore(key, pm)
		if !loaded {
			return
		}
		val = actual
	}
	pm := val.(*peerMeta)
	pm.mu.Lock()
	pm.mode = parsed.Mode
	pm.qtype = qtype
	if parsed.DownBudget > 0 {
		pm.downBudget = parsed.DownBudget
	}
	if ednsUDP > 0 {
		pm.ednsUDP = ednsUDP
	}
	pm.mu.Unlock()
}

func (s *Server) dispatchSlot(slot *querySlot) {
	key := slot.clientAddr.String()
	if val, ok := s.responders.Load(key); ok {
		r := val.(*clientResponder)
		select {
		case r.slotCh <- slot:
			return
		default:
			// Drop oldest held query slot by sending empty reply on next or let it overflow cleanly.
			select {
			case old := <-r.slotCh:
				s.sendEmptyNoError(old)
			default:
			}
			select {
			case r.slotCh <- slot:
			default:
				s.sendEmptyNoError(slot)
			}
			return
		}
	}

	r := &clientResponder{
		server: s,
		addr:   slot.clientAddr,
		slotCh: make(chan *querySlot, 64),
		stopCh: make(chan struct{}),
	}
	actual, loaded := s.responders.LoadOrStore(key, r)
	if loaded {
		r = actual.(*clientResponder)
	} else {
		go r.run(key)
	}
	select {
	case r.slotCh <- slot:
	default:
		s.sendEmptyNoError(slot)
	}
}

func (r *clientResponder) run(key string) {
	defer func() {
		r.server.responders.Delete(key)
		for {
			select {
			case slot := <-r.slotCh:
				r.server.sendEmptyNoError(slot)
			default:
				return
			}
		}
	}()

	notifyCh := r.server.ttConn.NotifyChan(r.addr)
	idleTimer := time.NewTimer(r.server.cfg.IdleTimeout)
	defer idleTimer.Stop()

	for {
		var slot *querySlot
		select {
		case <-r.stopCh:
			return
		case <-r.server.stopCh:
			return
		case <-idleTimer.C:
			return
		case slot = <-r.slotCh:
			if !idleTimer.Stop() {
				select {
				case <-idleTimer.C:
				default:
				}
			}
			idleTimer.Reset(r.server.cfg.IdleTimeout)
		}

		// Wait until either outgoing data arrives or pollHold expires.
		if !r.server.ttConn.HasPending(slot.clientAddr) {
			remainingHold := time.Until(slot.receivedAt.Add(defaultPollHold))
			if remainingHold > 0 {
				holdTimer := time.NewTimer(remainingHold)
				select {
				case <-r.stopCh:
					holdTimer.Stop()
					return
				case <-r.server.stopCh:
					holdTimer.Stop()
					return
				case <-notifyCh:
					holdTimer.Stop()
					// Tiny 1ms coalesce only if queue just woke up with a single small packet.
					time.Sleep(1 * time.Millisecond)
				case <-holdTimer.C:
				}
			}
		}

		maxPayload := protocol.MaxResponsePayload(r.server.cfg.Domain, slot.qtype, &slot.query.Question[0], slot.maxUDP)
		pkts := r.server.ttConn.PopOutgoingUpTo(slot.clientAddr, maxPayload)
		r.server.sendDataResponse(slot, pkts)
	}
}

func (s *Server) buildBaseResponse(query *dnswire.Message) *dnswire.Message {
	resp := &dnswire.Message{
		ID:       query.ID,
		Flags:    0x8400 | (query.Flags & 0x0100), // QR=1, AA=1, copy RD, RCODE=0
		Question: query.Question,
	}
	for _, rr := range query.Additional {
		if rr.Type == dnswire.RRTypeOPT {
			udp := uint16(s.cfg.MaxUDP)
			if int(udp) != s.cfg.MaxUDP {
				udp = 0xffff
			}
			resp.Additional = []dnswire.RR{{
				Name:  dnswire.Name{},
				Type:  dnswire.RRTypeOPT,
				Class: udp,
				TTL:   0,
				Data:  nil,
			}}
			break
		}
	}
	return resp
}

func (s *Server) sendSimpleError(query *dnswire.Message, rcode uint16, addr net.Addr) {
	resp := s.buildBaseResponse(query)
	resp.Flags = (resp.Flags &^ 0x000f) | (rcode & 0x000f)
	if wire, err := resp.WireFormat(); err == nil {
		_, _ = s.dnsConn.WriteTo(wire, addr)
	}
}

func (s *Server) sendEmptyNoError(slot *querySlot) {
	s.sendDataResponse(slot, nil)
}

func (s *Server) sendDataResponse(slot *querySlot, pkts [][]byte) {
	resp := s.buildBaseResponse(&slot.query)
	if len(pkts) > 0 {
		packed := protocol.PackDownstreamFrames(pkts)
		if err := dnswire.EncodeResponseRRs(resp, slot.query.Question[0], packed, s.cfg.Domain, responseTTL); err != nil {
			if s.cfg.Debug {
				log.Printf("vexdns encode response error: %v", err)
			}
			return
		}
	}
	wire, err := resp.WireFormat()
	if err != nil {
		return
	}
	_, _ = s.dnsConn.WriteTo(wire, slot.udpAddr)
}

func (s *Server) acceptSessions() error {
	for {
		conn, err := s.kcpLn.AcceptKCP()
		if err != nil {
			return err
		}
		sessionMTU := s.computeSessionMTU(conn.RemoteAddr().String())
		conn.SetStreamMode(true)
		conn.SetNoDelay(1, 10, 2, 1)
		conn.SetACKNoDelay(true)
		conn.SetWindowSize(s.cfg.KCPWindow, s.cfg.KCPWindow)
		if !conn.SetMtu(sessionMTU) {
			conn.SetMtu(protocol.MinUsableKCPMTU)
		}

		if s.cfg.Debug {
			log.Printf("vexdns session %08x (%s) mtu=%d", conn.GetConv(), conn.RemoteAddr(), sessionMTU)
		}

		go func(c *kcp.UDPSession) {
			s.ActiveSess.Add(1)
			defer func() {
				s.ActiveSess.Add(-1)
				s.peerMetas.Delete(c.RemoteAddr().String())
				_ = c.Close()
			}()
			if err := s.serveSession(c); err != nil && s.cfg.Debug && !errors.Is(err, io.EOF) {
				log.Printf("vexdns session %08x closed: %v", c.GetConv(), err)
			}
		}(conn)
	}
}

func (s *Server) computeSessionMTU(peerKey string) int {
	val, ok := s.peerMetas.Load(peerKey)
	if !ok {
		limit := protocol.MaxResponsePayload(s.cfg.Domain, dnswire.RRTypeTXT, nil, s.cfg.MaxUDP)
		if m := protocol.SafeKCPMTU(limit); m > 0 {
			return m
		}
		return protocol.MinUsableKCPMTU
	}
	pm := val.(*peerMeta)
	pm.mu.RLock()
	mode := pm.mode
	qtype := pm.qtype
	budget := pm.downBudget
	ednsUDP := pm.ednsUDP
	pm.mu.RUnlock()

	effUDP := s.effectiveUDPLimit(mode, budget, ednsUDP)
	limit := protocol.MaxResponsePayload(s.cfg.Domain, qtype, nil, effUDP)
	if m := protocol.SafeKCPMTU(limit); m > 0 {
		return m
	}
	return protocol.MinUsableKCPMTU
}

func (s *Server) serveSession(conn *kcp.UDPSession) error {
	_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
	rw, err := vxcrypto.WrapServer(conn, s.cfg.Privkey)
	_ = conn.SetDeadline(time.Time{})
	if err != nil {
		return err
	}
	defer rw.Close()

	smuxCfg := smux.DefaultConfig()
	smuxCfg.Version = 2
	smuxCfg.KeepAliveInterval = s.cfg.KeepAlive
	smuxCfg.KeepAliveTimeout = s.cfg.IdleTimeout
	smuxCfg.MaxStreamBuffer = 1 * 1024 * 1024
	sess, err := smux.Server(rw, smuxCfg)
	if err != nil {
		return err
	}
	defer sess.Close()

	for {
		stream, err := sess.AcceptStream()
		if err != nil {
			if ne, ok := err.(net.Error); ok && ne.Temporary() {
				continue
			}
			return err
		}
		go s.proxyStream(stream)
	}
}

func (s *Server) proxyStream(stream *smux.Stream) {
	defer stream.Close()
	upstreamConn, err := net.DialTimeout("tcp", s.cfg.UpstreamAddr, 10*time.Second)
	if err != nil {
		if s.cfg.Debug {
			log.Printf("vexdns upstream dial %s: %v", s.cfg.UpstreamAddr, err)
		}
		return
	}
	defer upstreamConn.Close()

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(stream, upstreamConn)
		if tc, ok := upstreamConn.(*net.TCPConn); ok {
			_ = tc.CloseRead()
		}
		_ = stream.Close()
	}()
	go func() {
		defer wg.Done()
		_, _ = io.Copy(upstreamConn, stream)
		if tc, ok := upstreamConn.(*net.TCPConn); ok {
			_ = tc.CloseWrite()
		}
	}()
	wg.Wait()
}

type dummyAddr struct{ s string }

func (d *dummyAddr) Network() string { return "vexdns" }
func (d *dummyAddr) String() string  { return d.s }
