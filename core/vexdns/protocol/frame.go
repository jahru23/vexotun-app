// Package protocol implements Vexora Native framing, stateless anti-amplification
// carrier probes, dnstt-compat demuxing, and dynamic per-session MTU calculation.
package protocol

import (
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"

	"golang.org/x/crypto/blake2s"
)

const (
	// MinUsableKCPMTU is the smallest KCP MTU that still leaves payload room beyond KCP's 24-byte header.
	MinUsableKCPMTU = 40

	// MaxServerUDPPayload is the upper bound for DNS response UDP payloads.
	MaxServerUDPPayload = 1232

	// ProbeRespFixedLen is the minimum signed payload length inside a VXPR probe response.
	ProbeRespFixedLen = 22

	// NativeHeaderLen is len("VXT1") + ClientID(4) + DownBudget(2) + Salt(3).
	NativeHeaderLen = 13

	// CompatClientIDLen is the 8-byte ClientID size used by standard dnstt.
	CompatClientIDLen = 8
)

var (
	MagicData      = [4]byte{'V', 'X', 'T', '1'}
	MagicProbe     = [4]byte{'V', 'X', 'P', '1'}
	MagicProbeResp = [4]byte{'V', 'X', 'P', 'R'}

	ErrMalformedPacket = errors.New("protocol: malformed frame")
	ErrInvalidProbeMAC = errors.New("protocol: invalid probe MAC")
)

// ProbeRequest is a stateless carrier capability & MTU probe sent by vexdns-client.
type ProbeRequest struct {
	TargetRespBytes uint16
	Nonce           [4]byte
	QueryWireLen    int
}

// ProbeResponse is the server's signed echo back through the carrier DNS path.
type ProbeResponse struct {
	Nonce        [4]byte
	ServerMaxUDP uint16
	TimeMsLo     uint32
	PayloadLen   int
}

func computeProbeTag(pubkey []byte, domainTag string, val uint16, nonce [4]byte) ([8]byte, error) {
	var out [8]byte
	h, err := blake2s.New256(pubkey)
	if err != nil {
		return out, err
	}
	_, _ = h.Write([]byte(domainTag))
	var v [2]byte
	binary.BigEndian.PutUint16(v[:], val)
	_, _ = h.Write(v[:])
	_, _ = h.Write(nonce[:])
	sum := h.Sum(nil)
	copy(out[:], sum[:8])
	return out, nil
}

// EncodeProbeRequest creates a signed VXP1 query payload, optionally zero-padded to rawLen
// so the client can test carrier query-length limits.
func EncodeProbeRequest(pubkey []byte, targetRespBytes uint16, desiredRawLen int) ([]byte, ProbeRequest, error) {
	var req ProbeRequest
	req.TargetRespBytes = targetRespBytes
	if _, err := rand.Read(req.Nonce[:]); err != nil {
		return nil, req, err
	}
	tag, err := computeProbeTag(pubkey, "vexdns-probe-v1", targetRespBytes, req.Nonce)
	if err != nil {
		return nil, req, err
	}
	totalLen := 18
	if desiredRawLen > totalLen {
		totalLen = desiredRawLen
	}
	buf := make([]byte, totalLen)
	copy(buf[0:4], MagicProbe[:])
	binary.BigEndian.PutUint16(buf[4:6], targetRespBytes)
	copy(buf[6:10], req.Nonce[:])
	copy(buf[10:18], tag[:])
	return buf, req, nil
}

// DecodeProbeRequest verifies and parses a VXP1 query payload against serverPubkey.
func DecodeProbeRequest(pubkey, payload []byte) (ProbeRequest, bool, error) {
	var req ProbeRequest
	if len(payload) < 4 || [4]byte(payload[0:4]) != MagicProbe {
		return req, false, nil
	}
	if len(payload) < 18 {
		return req, true, ErrMalformedPacket
	}
	req.TargetRespBytes = binary.BigEndian.Uint16(payload[4:6])
	copy(req.Nonce[:], payload[6:10])
	expected, err := computeProbeTag(pubkey, "vexdns-probe-v1", req.TargetRespBytes, req.Nonce)
	if err != nil {
		return req, true, err
	}
	// Constant-time compare on 8-byte tag.
	var diff byte
	for i := 0; i < 8; i++ {
		diff |= expected[i] ^ payload[10+i]
	}
	if diff != 0 {
		return req, true, ErrInvalidProbeMAC
	}
	return req, true, nil
}

// EncodeProbeResponse builds a signed VXPR response payload padded deterministically up to payloadSize.
func EncodeProbeResponse(pubkey []byte, req ProbeRequest, qtype, serverMaxUDP uint16, timeMsLo uint32, payloadSize int) ([]byte, error) {
	if payloadSize < ProbeRespFixedLen {
		payloadSize = ProbeRespFixedLen
	}
	tag, err := computeProbeTag(pubkey, "vexdns-probe-resp-v1", qtype, req.Nonce)
	if err != nil {
		return nil, err
	}
	buf := make([]byte, payloadSize)
	copy(buf[0:4], MagicProbeResp[:])
	copy(buf[4:8], req.Nonce[:])
	binary.BigEndian.PutUint16(buf[8:10], serverMaxUDP)
	binary.BigEndian.PutUint32(buf[10:14], timeMsLo)
	copy(buf[14:22], tag[:])
	for i := ProbeRespFixedLen; i < payloadSize; i++ {
		buf[i] = byte((i*31 + int(req.Nonce[i&3])) & 0x3f) // printable/benign pattern (base32-friendly for name records too)
	}
	return buf, nil
}

// DecodeProbeResponse verifies a VXPR response payload against serverPubkey, req.Nonce, and qtype.
func DecodeProbeResponse(pubkey []byte, nonce [4]byte, qtype uint16, payload []byte) (ProbeResponse, error) {
	var resp ProbeResponse
	if len(payload) < ProbeRespFixedLen || [4]byte(payload[0:4]) != MagicProbeResp {
		return resp, ErrMalformedPacket
	}
	copy(resp.Nonce[:], payload[4:8])
	if resp.Nonce != nonce {
		return resp, ErrMalformedPacket
	}
	resp.ServerMaxUDP = binary.BigEndian.Uint16(payload[8:10])
	resp.TimeMsLo = binary.BigEndian.Uint32(payload[10:14])
	resp.PayloadLen = len(payload)

	expected, err := computeProbeTag(pubkey, "vexdns-probe-resp-v1", qtype, nonce)
	if err != nil {
		return resp, err
	}
	var diff byte
	for i := 0; i < 8; i++ {
		diff |= expected[i] ^ payload[14+i]
	}
	if diff != 0 {
		return resp, ErrInvalidProbeMAC
	}
	return resp, nil
}

// ClientMode identifies whether a tunnel session uses Vexora Native or standard dnstt framing.
type ClientMode uint8

const (
	ModeCompat ClientMode = iota
	ModeVexora
)

// ClientAddr uniquely identifies a session in TurboTunnel (separating VXT1 vs compat IDs).
type ClientAddr struct {
	Mode ClientMode
	ID   [8]byte
}

func (a ClientAddr) Network() string { return "vexdns" }
func (a ClientAddr) String() string {
	if a.Mode == ModeVexora {
		return fmt.Sprintf("vx:%x", a.ID[:4])
	}
	return fmt.Sprintf("dt:%x", a.ID[:])
}

// ParsedQuery holds the extracted mode, session key, negotiated downstream UDP budget, and KCP packets.
type ParsedQuery struct {
	Mode       ClientMode
	Addr       ClientAddr
	DownBudget uint16 // Vexora Native only; 0 in compat mode
	Packets    [][]byte
}

// EncodeVexoraQuery builds a VXT1 query payload with random 3-byte salt for cache busting.
func EncodeVexoraQuery(id [4]byte, downBudget uint16, packets [][]byte) ([]byte, error) {
	total := NativeHeaderLen
	for _, p := range packets {
		if len(p) == 0 || len(p) > 255 {
			return nil, fmt.Errorf("protocol: invalid upstream packet size %d", len(p))
		}
		total += 1 + len(p)
	}
	buf := make([]byte, total)
	copy(buf[0:4], MagicData[:])
	copy(buf[4:8], id[:])
	binary.BigEndian.PutUint16(buf[8:10], downBudget)
	if _, err := rand.Read(buf[10:13]); err != nil {
		return nil, err
	}
	off := NativeHeaderLen
	for _, p := range packets {
		buf[off] = byte(len(p))
		copy(buf[off+1:], p)
		off += 1 + len(p)
	}
	return buf, nil
}

// DecodeQueryPayload demuxes Vexora Native ("VXT1") and dnstt-compat upstream packets.
func DecodeQueryPayload(payload []byte) (ParsedQuery, error) {
	var out ParsedQuery
	if len(payload) >= NativeHeaderLen && [4]byte(payload[0:4]) == MagicData {
		out.Mode = ModeVexora
		out.Addr.Mode = ModeVexora
		copy(out.Addr.ID[:4], payload[4:8])
		out.DownBudget = binary.BigEndian.Uint16(payload[8:10])
		rest := payload[NativeHeaderLen:]
		for len(rest) > 0 {
			n := int(rest[0])
			rest = rest[1:]
			if n == 0 || n > len(rest) {
				return out, io.ErrUnexpectedEOF
			}
			pkt := make([]byte, n)
			copy(pkt, rest[:n])
			out.Packets = append(out.Packets, pkt)
			rest = rest[n:]
		}
		return out, nil
	}

	// dnstt standard wire format: 8-byte ClientID + [padding>=224 or length<224][packet]
	if len(payload) < CompatClientIDLen {
		return out, io.ErrUnexpectedEOF
	}
	out.Mode = ModeCompat
	out.Addr.Mode = ModeCompat
	copy(out.Addr.ID[:], payload[:CompatClientIDLen])
	rest := payload[CompatClientIDLen:]
	for len(rest) > 0 {
		prefix := int(rest[0])
		rest = rest[1:]
		if prefix >= 224 {
			padLen := prefix - 224
			if padLen > len(rest) {
				return out, io.ErrUnexpectedEOF
			}
			rest = rest[padLen:]
		} else {
			if prefix > len(rest) {
				return out, io.ErrUnexpectedEOF
			}
			pkt := make([]byte, prefix)
			copy(pkt, rest[:prefix])
			out.Packets = append(out.Packets, pkt)
			rest = rest[prefix:]
		}
	}
	return out, nil
}

// PackDownstreamFrames packs one or more KCP datagrams with 2-byte big-endian length prefixes.
func PackDownstreamFrames(packets [][]byte) []byte {
	total := 0
	for _, p := range packets {
		total += 2 + len(p)
	}
	buf := make([]byte, total)
	off := 0
	for _, p := range packets {
		binary.BigEndian.PutUint16(buf[off:off+2], uint16(len(p)))
		copy(buf[off+2:], p)
		off += 2 + len(p)
	}
	return buf
}

// UnpackDownstreamFrames unpacks 2-byte length-prefixed datagrams from a DNS response payload.
func UnpackDownstreamFrames(data []byte) ([][]byte, error) {
	var pkts [][]byte
	for len(data) > 0 {
		if len(data) < 2 {
			return nil, io.ErrUnexpectedEOF
		}
		n := int(binary.BigEndian.Uint16(data[0:2]))
		data = data[2:]
		if n > len(data) {
			return nil, io.ErrUnexpectedEOF
		}
		pkt := make([]byte, n)
		copy(pkt, data[:n])
		pkts = append(pkts, pkt)
		data = data[n:]
	}
	return pkts, nil
}
