// Package dnswire implements a compact, RFC-compliant DNS wire codec supporting
// EDNS0, name compression pointer resolution, and 11 tunnel record types.
package dnswire

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"strings"
)

const (
	ClassIN uint16 = 1

	RRTypeA     uint16 = 1
	RRTypeNS    uint16 = 2
	RRTypeCNAME uint16 = 5
	RRTypeNULL  uint16 = 10
	RRTypeMX    uint16 = 15
	RRTypeTXT   uint16 = 16
	RRTypeAAAA  uint16 = 28
	RRTypeSRV   uint16 = 33
	RRTypeOPT   uint16 = 41
	RRTypeSVCB  uint16 = 64
	RRTypeHTTPS uint16 = 65
	RRTypeCAA   uint16 = 257

	RcodeNoError        uint16 = 0
	RcodeFormatError    uint16 = 1
	RcodeServerFailure  uint16 = 2
	RcodeNameError      uint16 = 3
	RcodeNotImplemented uint16 = 4
	RcodeRefused        uint16 = 5
)

var (
	ErrTruncated       = errors.New("dnswire: truncated packet")
	ErrNameTooLong     = errors.New("dnswire: name exceeds 255 wire bytes")
	ErrLabelTooLong    = errors.New("dnswire: label exceeds 63 bytes")
	ErrEmptyLabel      = errors.New("dnswire: empty non-terminal label")
	ErrPointerLoop     = errors.New("dnswire: compression pointer loop")
	ErrIntegerOverflow = errors.New("dnswire: section or length overflow")
)

// Name represents a DNS domain name as a slice of raw labels without the trailing root label.
type Name [][]byte

// NewName validates and wraps a slice of labels as a DNS Name.
func NewName(labels [][]byte) (Name, error) {
	if len(labels) == 0 {
		return Name{}, nil
	}
	wireLen := 1 // root null byte
	for _, label := range labels {
		if len(label) == 0 {
			return nil, ErrEmptyLabel
		}
		if len(label) > 63 {
			return nil, ErrLabelTooLong
		}
		wireLen += 1 + len(label)
	}
	if wireLen > 255 {
		return nil, ErrNameTooLong
	}
	copied := make(Name, len(labels))
	for i, l := range labels {
		b := make([]byte, len(l))
		copy(b, l)
		copied[i] = b
	}
	return copied, nil
}

// ParseName parses a dotted DNS domain name (optional trailing dot).
func ParseName(s string) (Name, error) {
	s = strings.TrimSpace(s)
	s = strings.TrimSuffix(s, ".")
	if s == "" {
		return Name{}, nil
	}
	parts := strings.Split(s, ".")
	labels := make([][]byte, len(parts))
	for i, p := range parts {
		labels[i] = []byte(p)
	}
	return NewName(labels)
}

func (n Name) String() string {
	if len(n) == 0 {
		return "."
	}
	parts := make([]string, len(n))
	for i, l := range n {
		parts[i] = string(l)
	}
	return strings.Join(parts, ".")
}

// WireLen returns the uncompressed DNS wire length of n (including terminal 0x00).
func (n Name) WireLen() int {
	total := 1
	for _, l := range n {
		total += 1 + len(l)
	}
	return total
}

// WireFormat encodes n in uncompressed DNS wire format.
func (n Name) WireFormat() []byte {
	out := make([]byte, 0, n.WireLen())
	for _, l := range n {
		out = append(out, byte(len(l)))
		out = append(out, l...)
	}
	out = append(out, 0)
	return out
}

// EqualFold returns true if n and other represent the same domain name (case-insensitive).
func (n Name) EqualFold(other Name) bool {
	if len(n) != len(other) {
		return false
	}
	for i := range n {
		if !asciiEqualFold(n[i], other[i]) {
			return false
		}
	}
	return true
}

// TrimSuffix checks whether n ends with suffix (case-insensitive) and returns the prefix labels.
func (n Name) TrimSuffix(suffix Name) (Name, bool) {
	if len(n) < len(suffix) {
		return nil, false
	}
	offset := len(n) - len(suffix)
	for i := range suffix {
		if !asciiEqualFold(n[offset+i], suffix[i]) {
			return nil, false
		}
	}
	return n[:offset], true
}

func asciiEqualFold(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		ca, cb := a[i], b[i]
		if ca >= 'A' && ca <= 'Z' {
			ca += 'a' - 'A'
		}
		if cb >= 'A' && cb <= 'Z' {
			cb += 'a' - 'A'
		}
		if ca != cb {
			return false
		}
	}
	return true
}

// Question represents a DNS Question section entry.
type Question struct {
	Name  Name
	Type  uint16
	Class uint16
}

// RR represents a DNS Resource Record.
type RR struct {
	Name  Name
	Type  uint16
	Class uint16
	TTL   uint32
	Data  []byte
}

// Message represents a DNS wire message.
type Message struct {
	ID         uint16
	Flags      uint16
	Question   []Question
	Answer     []RR
	Authority  []RR
	Additional []RR
}

// Rcode returns the 4-bit DNS response code.
func (m *Message) Rcode() uint16 {
	return m.Flags & 0x000f
}

// ExtendedRcode returns the 12-bit extended RCODE including OPT header bits if present.
func (m *Message) ExtendedRcode() uint16 {
	rc := m.Rcode()
	for _, rr := range m.Additional {
		if rr.Type == RRTypeOPT {
			rc |= uint16((rr.TTL>>24)&0xff) << 4
			break
		}
	}
	return rc
}

// EDNS0UDPSize returns the requester's advertised EDNS0 UDP payload size (512 if no OPT).
func (m *Message) EDNS0UDPSize() int {
	for _, rr := range m.Additional {
		if rr.Type == RRTypeOPT {
			if rr.Class < 512 {
				return 512
			}
			return int(rr.Class)
		}
	}
	return 512
}

// WireFormat serializes m to DNS wire format with pointer compression on repeated RR names.
func (m *Message) WireFormat() ([]byte, error) {
	if len(m.Question) > 0xffff || len(m.Answer) > 0xffff || len(m.Authority) > 0xffff || len(m.Additional) > 0xffff {
		return nil, ErrIntegerOverflow
	}
	var buf bytes.Buffer
	var hdr [12]byte
	binary.BigEndian.PutUint16(hdr[0:2], m.ID)
	binary.BigEndian.PutUint16(hdr[2:4], m.Flags)
	binary.BigEndian.PutUint16(hdr[4:6], uint16(len(m.Question)))
	binary.BigEndian.PutUint16(hdr[6:8], uint16(len(m.Answer)))
	binary.BigEndian.PutUint16(hdr[8:10], uint16(len(m.Authority)))
	binary.BigEndian.PutUint16(hdr[10:12], uint16(len(m.Additional)))
	buf.Write(hdr[:])

	var firstQName Name
	firstQOffset := -1
	for i, q := range m.Question {
		if i == 0 {
			firstQName = q.Name
			firstQOffset = buf.Len()
			writeNameUncompressed(&buf, q.Name)
		} else {
			writeNameMaybeCompress(&buf, q.Name, firstQName, firstQOffset)
		}
		var qmeta [4]byte
		binary.BigEndian.PutUint16(qmeta[0:2], q.Type)
		binary.BigEndian.PutUint16(qmeta[2:4], q.Class)
		buf.Write(qmeta[:])
	}

	writeRRs := func(rrs []RR) error {
		for _, rr := range rrs {
			if len(rr.Data) > 0xffff {
				return ErrIntegerOverflow
			}
			writeNameMaybeCompress(&buf, rr.Name, firstQName, firstQOffset)
			var meta [10]byte
			binary.BigEndian.PutUint16(meta[0:2], rr.Type)
			binary.BigEndian.PutUint16(meta[2:4], rr.Class)
			binary.BigEndian.PutUint32(meta[4:8], rr.TTL)
			binary.BigEndian.PutUint16(meta[8:10], uint16(len(rr.Data)))
			buf.Write(meta[:])
			buf.Write(rr.Data)
		}
		return nil
	}

	if err := writeRRs(m.Answer); err != nil {
		return nil, err
	}
	if err := writeRRs(m.Authority); err != nil {
		return nil, err
	}
	if err := writeRRs(m.Additional); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func writeNameUncompressed(buf *bytes.Buffer, n Name) {
	for _, l := range n {
		buf.WriteByte(byte(len(l)))
		buf.Write(l)
	}
	buf.WriteByte(0)
}

func writeNameMaybeCompress(buf *bytes.Buffer, n, ref Name, refOff int) {
	if len(n) > 0 && refOff >= 12 && refOff < 0x3fff && n.EqualFold(ref) {
		var ptr [2]byte
		binary.BigEndian.PutUint16(ptr[:], 0xc000|uint16(refOff))
		buf.Write(ptr[:])
		return
	}
	writeNameUncompressed(buf, n)
}

// MessageFromWireFormat parses a DNS wire-format packet and expands compressed
// domain names inside CNAME/NS/MX/SRV RDATA so resolvers that apply compression remain decodable.
func MessageFromWireFormat(pkt []byte) (Message, error) {
	if len(pkt) < 12 {
		return Message{}, ErrTruncated
	}
	msg := Message{
		ID:    binary.BigEndian.Uint16(pkt[0:2]),
		Flags: binary.BigEndian.Uint16(pkt[2:4]),
	}
	qdCount := int(binary.BigEndian.Uint16(pkt[4:6]))
	anCount := int(binary.BigEndian.Uint16(pkt[6:8]))
	nsCount := int(binary.BigEndian.Uint16(pkt[8:10]))
	arCount := int(binary.BigEndian.Uint16(pkt[10:12]))

	off := 12
	if qdCount > 64 || anCount > 512 || nsCount > 128 || arCount > 128 {
		return Message{}, fmt.Errorf("dnswire: excessive section counts")
	}

	msg.Question = make([]Question, 0, qdCount)
	for i := 0; i < qdCount; i++ {
		name, nextOff, err := readName(pkt, off)
		if err != nil {
			return Message{}, err
		}
		off = nextOff
		if off+4 > len(pkt) {
			return Message{}, ErrTruncated
		}
		q := Question{
			Name:  name,
			Type:  binary.BigEndian.Uint16(pkt[off : off+2]),
			Class: binary.BigEndian.Uint16(pkt[off+2 : off+4]),
		}
		off += 4
		msg.Question = append(msg.Question, q)
	}

	readRRs := func(count int) ([]RR, error) {
		out := make([]RR, 0, count)
		for i := 0; i < count; i++ {
			name, nextOff, err := readName(pkt, off)
			if err != nil {
				return nil, err
			}
			off = nextOff
			if off+10 > len(pkt) {
				return nil, ErrTruncated
			}
			rrType := binary.BigEndian.Uint16(pkt[off : off+2])
			rrClass := binary.BigEndian.Uint16(pkt[off+2 : off+4])
			rrTTL := binary.BigEndian.Uint32(pkt[off+4 : off+8])
			rdLen := int(binary.BigEndian.Uint16(pkt[off+8 : off+10]))
			off += 10
			if off+rdLen > len(pkt) {
				return nil, ErrTruncated
			}
			rdata, err := expandCompressedRData(pkt, off, rdLen, rrType)
			if err != nil {
				rdata = append([]byte(nil), pkt[off:off+rdLen]...)
			}
			off += rdLen
			out = append(out, RR{
				Name:  name,
				Type:  rrType,
				Class: rrClass,
				TTL:   rrTTL,
				Data:  rdata,
			})
		}
		return out, nil
	}

	var err error
	if msg.Answer, err = readRRs(anCount); err != nil {
		return Message{}, err
	}
	if msg.Authority, err = readRRs(nsCount); err != nil {
		return Message{}, err
	}
	if msg.Additional, err = readRRs(arCount); err != nil {
		return Message{}, err
	}
	return msg, nil
}

func expandCompressedRData(pkt []byte, rdataOff, rdLen int, rrType uint16) ([]byte, error) {
	switch rrType {
	case RRTypeCNAME, RRTypeNS:
		name, endOff, err := readName(pkt, rdataOff)
		if err != nil || endOff != rdataOff+rdLen {
			return nil, ErrTruncated
		}
		return name.WireFormat(), nil
	case RRTypeMX:
		if rdLen < 3 {
			return nil, io.ErrUnexpectedEOF
		}
		name, endOff, err := readName(pkt, rdataOff+2)
		if err != nil || endOff != rdataOff+rdLen {
			return nil, ErrTruncated
		}
		w := name.WireFormat()
		out := make([]byte, 2+len(w))
		copy(out[0:2], pkt[rdataOff:rdataOff+2])
		copy(out[2:], w)
		return out, nil
	case RRTypeSRV:
		if rdLen < 7 {
			return nil, io.ErrUnexpectedEOF
		}
		name, endOff, err := readName(pkt, rdataOff+6)
		if err != nil || endOff != rdataOff+rdLen {
			return nil, ErrTruncated
		}
		w := name.WireFormat()
		out := make([]byte, 6+len(w))
		copy(out[0:6], pkt[rdataOff:rdataOff+6])
		copy(out[6:], w)
		return out, nil
	default:
		out := make([]byte, rdLen)
		copy(out, pkt[rdataOff:rdataOff+rdLen])
		return out, nil
	}
}

func readName(pkt []byte, offset int) (Name, int, error) {
	var labels [][]byte
	wireLen := 1
	cur := offset
	nextOff := -1
	jumps := 0

	for {
		if cur >= len(pkt) {
			return nil, 0, ErrTruncated
		}
		b := pkt[cur]
		switch b & 0xc0 {
		case 0x00:
			if b == 0 {
				if nextOff < 0 {
					nextOff = cur + 1
				}
				name, err := NewName(labels)
				return name, nextOff, err
			}
			l := int(b)
			if l > 63 {
				return nil, 0, ErrLabelTooLong
			}
			cur++
			if cur+l > len(pkt) {
				return nil, 0, ErrTruncated
			}
			wireLen += 1 + l
			if wireLen > 255 {
				return nil, 0, ErrNameTooLong
			}
			label := make([]byte, l)
			copy(label, pkt[cur:cur+l])
			labels = append(labels, label)
			cur += l
		case 0xc0:
			if cur+1 >= len(pkt) {
				return nil, 0, ErrTruncated
			}
			if nextOff < 0 {
				nextOff = cur + 2
			}
			ptr := int(binary.BigEndian.Uint16(pkt[cur:cur+2]) & 0x3fff)
			if ptr >= len(pkt) {
				return nil, 0, ErrTruncated
			}
			jumps++
			if jumps > 32 {
				return nil, 0, ErrPointerLoop
			}
			cur = ptr
		default:
			return nil, 0, fmt.Errorf("dnswire: invalid label prefix 0x%02x", b)
		}
	}
}

// NameFromWireFormat parses an uncompressed DNS wire-format name slice.
func NameFromWireFormat(data []byte) (Name, error) {
	name, endOff, err := readName(data, 0)
	if err != nil {
		return nil, err
	}
	if endOff != len(data) {
		return nil, fmt.Errorf("dnswire: trailing bytes after name")
	}
	return name, nil
}
