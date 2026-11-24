// Package tunnel implements the KCP + Noise_NK + smux DNS tunnel transport
// for both vexdns-server and vexdns-client.
package tunnel

import (
	"container/list"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

var ErrQueueFull = errors.New("tunnel: outgoing packet queue full")

type incomingPacket struct {
	data []byte
	addr net.Addr
}

// peerState holds outgoing KCP datagrams and a wake signal for a single peer address.
type peerState struct {
	addr     net.Addr
	mu       sync.Mutex
	pkts     [][]byte
	notifyCh chan struct{}
	lastSeen time.Time
}

// QueuePacketConn adapts DNS request-response transport to net.PacketConn for kcp-go.
type QueuePacketConn struct {
	localAddr   net.Addr
	recvCh      chan incomingPacket
	mu          sync.RWMutex
	peers       map[string]*peerState
	peerLRU     *list.List
	lruIndex    map[string]*list.Element
	idleTimeout time.Duration
	maxQueue    int
	onWrite     func(addr net.Addr)
	onFirstPeer func(addr net.Addr)

	closed    atomic.Bool
	closeOnce sync.Once
	closeCh   chan struct{}

	readDeadlineMu sync.RWMutex
	readDeadline   time.Time
}

// NewQueuePacketConn creates a new TurboTunnel PacketConn.
func NewQueuePacketConn(localAddr net.Addr, idleTimeout time.Duration, maxQueue int) *QueuePacketConn {
	if maxQueue < 64 {
		maxQueue = 64
	}
	if idleTimeout <= 0 {
		idleTimeout = 2 * time.Minute
	}
	q := &QueuePacketConn{
		localAddr:   localAddr,
		recvCh:      make(chan incomingPacket, 4096),
		peers:       make(map[string]*peerState),
		peerLRU:     list.New(),
		lruIndex:    make(map[string]*list.Element),
		idleTimeout: idleTimeout,
		maxQueue:    maxQueue,
		closeCh:     make(chan struct{}),
	}
	go q.reapLoop()
	return q
}

// SetOnWrite registers a callback invoked whenever KCP writes an outgoing datagram.
func (q *QueuePacketConn) SetOnWrite(fn func(addr net.Addr)) {
	q.mu.Lock()
	q.onWrite = fn
	q.mu.Unlock()
}

// SetOnFirstPeer registers a callback invoked once when a new peer address appears.
func (q *QueuePacketConn) SetOnFirstPeer(fn func(addr net.Addr)) {
	q.mu.Lock()
	q.onFirstPeer = fn
	q.mu.Unlock()
}

func (q *QueuePacketConn) getOrCreatePeer(addr net.Addr) (*peerState, bool) {
	key := addr.String()
	q.mu.Lock()
	defer q.mu.Unlock()
	if ps, ok := q.peers[key]; ok {
		if el, ok := q.lruIndex[key]; ok {
			q.peerLRU.MoveToFront(el)
		}
		ps.mu.Lock()
		ps.lastSeen = time.Now()
		ps.mu.Unlock()
		return ps, false
	}
	ps := &peerState{
		addr:     addr,
		notifyCh: make(chan struct{}, 1),
		lastSeen: time.Now(),
	}
	q.peers[key] = ps
	el := q.peerLRU.PushFront(key)
	q.lruIndex[key] = el
	return ps, true
}

// QueueIncoming pushes a decrypted DNS-extracted KCP datagram into the KCP listener/session.
func (q *QueuePacketConn) QueueIncoming(data []byte, addr net.Addr) {
	if q.closed.Load() || len(data) == 0 {
		return
	}
	_, isNew := q.getOrCreatePeer(addr)
	if isNew {
		q.mu.RLock()
		fn := q.onFirstPeer
		q.mu.RUnlock()
		if fn != nil {
			fn(addr)
		}
	}
	copied := make([]byte, len(data))
	copy(copied, data)
	select {
	case q.recvCh <- incomingPacket{data: copied, addr: addr}:
	default:
		// Drop oldest packet cleanly if saturated rather than blocking UDP recvLoop.
		select {
		case <-q.recvCh:
		default:
		}
		select {
		case q.recvCh <- incomingPacket{data: copied, addr: addr}:
		default:
		}
	}
}

// NotifyChan returns a signaling channel triggered when outgoing packets are queued for addr.
func (q *QueuePacketConn) NotifyChan(addr net.Addr) <-chan struct{} {
	ps, _ := q.getOrCreatePeer(addr)
	return ps.notifyCh
}

// PopOutgoingUpTo drains outgoing packets for addr whose 2-byte-prefixed packed total stays <= maxBytes.
// It always pops at least 0 packets cleanly without splitting a datagram.
func (q *QueuePacketConn) PopOutgoingUpTo(addr net.Addr, maxBytes int) [][]byte {
	key := addr.String()
	q.mu.RLock()
	ps := q.peers[key]
	q.mu.RUnlock()
	if ps == nil {
		return nil
	}

	ps.mu.Lock()
	defer ps.mu.Unlock()
	ps.lastSeen = time.Now()
	if len(ps.pkts) == 0 {
		return nil
	}

	var taken [][]byte
	used := 0
	for len(ps.pkts) > 0 {
		pkt := ps.pkts[0]
		need := 2 + len(pkt)
		if need > maxBytes {
			if len(taken) == 0 {
				// Impossible to fit even alone under maxBytes; drop oversized stale packet so queue doesn't wedge.
				ps.pkts = ps.pkts[1:]
				continue
			}
			break
		}
		if used+need > maxBytes {
			break
		}
		used += need
		taken = append(taken, pkt)
		ps.pkts = ps.pkts[1:]
	}
	return taken
}

// PopUpstreamUpTo drains outgoing KCP packets for addr using 1-byte length headers (for VXT1 DNS queries).
func (q *QueuePacketConn) PopUpstreamUpTo(addr net.Addr, maxBytes int) [][]byte {
	key := addr.String()
	q.mu.RLock()
	ps := q.peers[key]
	q.mu.RUnlock()
	if ps == nil {
		return nil
	}

	ps.mu.Lock()
	defer ps.mu.Unlock()
	ps.lastSeen = time.Now()
	if len(ps.pkts) == 0 {
		return nil
	}

	var taken [][]byte
	used := 0
	for len(ps.pkts) > 0 {
		pkt := ps.pkts[0]
		need := 1 + len(pkt)
		if len(pkt) > 255 || need > maxBytes {
			if len(taken) == 0 {
				ps.pkts = ps.pkts[1:]
				continue
			}
			break
		}
		if used+need > maxBytes {
			break
		}
		used += need
		taken = append(taken, pkt)
		ps.pkts = ps.pkts[1:]
	}
	return taken
}

// HasPending returns whether outgoing packets are waiting for addr.
func (q *QueuePacketConn) HasPending(addr net.Addr) bool {
	q.mu.RLock()
	ps := q.peers[addr.String()]
	q.mu.RUnlock()
	if ps == nil {
		return false
	}
	ps.mu.Lock()
	has := len(ps.pkts) > 0
	ps.mu.Unlock()
	return has
}

func (q *QueuePacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	for {
		if q.closed.Load() {
			return 0, nil, net.ErrClosed
		}
		q.readDeadlineMu.RLock()
		dl := q.readDeadline
		q.readDeadlineMu.RUnlock()

		var timer *time.Timer
		var timerCh <-chan time.Time
		if !dl.IsZero() {
			wait := time.Until(dl)
			if wait <= 0 {
				return 0, nil, timeoutError{}
			}
			timer = time.NewTimer(wait)
			timerCh = timer.C
		}

		select {
		case <-q.closeCh:
			if timer != nil {
				timer.Stop()
			}
			return 0, nil, net.ErrClosed
		case <-timerCh:
			return 0, nil, timeoutError{}
		case pkt := <-q.recvCh:
			if timer != nil {
				timer.Stop()
			}
			n := copy(p, pkt.data)
			return n, pkt.addr, nil
		}
	}
}

func (q *QueuePacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	if q.closed.Load() {
		return 0, net.ErrClosed
	}
	if addr == nil {
		return 0, errors.New("tunnel: nil destination address")
	}
	ps, _ := q.getOrCreatePeer(addr)
	copied := make([]byte, len(p))
	copy(copied, p)

	ps.mu.Lock()
	if len(ps.pkts) >= q.maxQueue {
		ps.pkts = ps.pkts[1:]
	}
	ps.pkts = append(ps.pkts, copied)
	ps.lastSeen = time.Now()
	ps.mu.Unlock()

	select {
	case ps.notifyCh <- struct{}{}:
	default:
	}

	q.mu.RLock()
	fn := q.onWrite
	q.mu.RUnlock()
	if fn != nil {
		fn(addr)
	}
	return len(p), nil
}

func (q *QueuePacketConn) Close() error {
	q.closeOnce.Do(func() {
		q.closed.Store(true)
		close(q.closeCh)
	})
	return nil
}

func (q *QueuePacketConn) LocalAddr() net.Addr { return q.localAddr }

func (q *QueuePacketConn) SetDeadline(t time.Time) error {
	return q.SetReadDeadline(t)
}

func (q *QueuePacketConn) SetReadDeadline(t time.Time) error {
	q.readDeadlineMu.Lock()
	q.readDeadline = t
	q.readDeadlineMu.Unlock()
	return nil
}

func (q *QueuePacketConn) SetWriteDeadline(t time.Time) error { return nil }

func (q *QueuePacketConn) reapLoop() {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-q.closeCh:
			return
		case <-ticker.C:
			cutoff := time.Now().Add(-q.idleTimeout)
			q.mu.Lock()
			for el := q.peerLRU.Back(); el != nil; {
				prev := el.Prev()
				key := el.Value.(string)
				ps := q.peers[key]
				ps.mu.Lock()
				stale := ps.lastSeen.Before(cutoff)
				ps.mu.Unlock()
				if !stale {
					break
				}
				q.peerLRU.Remove(el)
				delete(q.lruIndex, key)
				delete(q.peers, key)
				el = prev
			}
			q.mu.Unlock()
		}
	}
}

type timeoutError struct{}

func (timeoutError) Error() string   { return "i/o timeout" }
func (timeoutError) Timeout() bool   { return true }
func (timeoutError) Temporary() bool { return true }
