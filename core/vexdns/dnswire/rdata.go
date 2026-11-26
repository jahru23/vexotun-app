package dnswire

import (
	"bytes"
	"encoding/base32"
	"encoding/binary"
	"fmt"
	"io"
	"strings"
)

var base32Encoding = base32.StdEncoding.WithPadding(base32.NoPadding)

// AllSupportedRecordTypes lists all 11 supported downstream QTYPEs in solver priority order.
var AllSupportedRecordTypes = []uint16{
	RRTypeHTTPS,
	RRTypeSVCB,
	RRTypeTXT,
	RRTypeNULL,
	RRTypeAAAA,
	RRTypeA,
	RRTypeCNAME,
	RRTypeMX,
	RRTypeNS,
	RRTypeSRV,
	RRTypeCAA,
}

// ParseRecordType converts a record type string (e.g. "https", "txt") to its DNS QTYPE.
func ParseRecordType(s string) (uint16, error) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "https":
		return RRTypeHTTPS, nil
	case "svcb":
		return RRTypeSVCB, nil
	case "txt":
		return RRTypeTXT, nil
	case "null":
		return RRTypeNULL, nil
	case "aaaa":
		return RRTypeAAAA, nil
	case "a":
		return RRTypeA, nil
	case "cname":
		return RRTypeCNAME, nil
	case "mx":
		return RRTypeMX, nil
	case "ns":
		return RRTypeNS, nil
	case "srv":
		return RRTypeSRV, nil
	case "caa":
		return RRTypeCAA, nil
	default:
		return 0, fmt.Errorf("unknown record type %q (supported: https, svcb, txt, null, aaaa, a, cname, mx, ns, srv, caa)", s)
	}
}

// RecordTypeString returns the canonical lowercase name for qtype.
func RecordTypeString(qtype uint16) string {
	switch qtype {
	case RRTypeHTTPS:
		return "https"
	case RRTypeSVCB:
		return "svcb"
	case RRTypeTXT:
		return "txt"
	case RRTypeNULL:
		return "null"
	case RRTypeAAAA:
		return "aaaa"
	case RRTypeA:
		return "a"
	case RRTypeCNAME:
		return "cname"
	case RRTypeMX:
		return "mx"
	case RRTypeNS:
		return "ns"
	case RRTypeSRV:
		return "srv"
	case RRTypeCAA:
		return "caa"
	default:
		return fmt.Sprintf("type%d", qtype)
	}
}

// IsSupportedRecordType returns true if qtype is one of the 11 tunnel record types.
func IsSupportedRecordType(qtype uint16) bool {
	switch qtype {
	case RRTypeHTTPS, RRTypeSVCB, RRTypeTXT, RRTypeNULL, RRTypeAAAA,
		RRTypeA, RRTypeCNAME, RRTypeMX, RRTypeNS, RRTypeSRV, RRTypeCAA:
		return true
	default:
		return false
	}
}

// EncodeQueryName encodes payload into base32 lowercase DNS labels prefixed before domain.
func EncodeQueryName(payload []byte, domain Name) (Name, error) {
	return encodePayloadAsLabels(payload, domain, 63)
}

// DecodeQueryName strips domain from qname and decodes the base32 labels back to raw bytes.
func DecodeQueryName(qname Name, domain Name) ([]byte, error) {
	prefix, ok := qname.TrimSuffix(domain)
	if !ok {
		return nil, fmt.Errorf("dnswire: name %s does not end with %s", qname, domain)
	}
	if len(prefix) == 0 {
		return nil, nil
	}
	return decodeBase32Labels(prefix)
}

// MaxQueryPayloadLen returns the maximum raw bytes that fit in a DNS Query Name under domain.
func MaxQueryPayloadLen(domain Name) int {
	// Total wire name <= 255 bytes; domain consumes domain.WireLen() (which includes trailing 0x00).
	availWire := 255 - domain.WireLen()
	if availWire <= 1 {
		return 0
	}
	// Each up-to-63-char base32 label uses 1 length byte + N char bytes.
	fullLabels := availWire / 64
	rem := availWire % 64
	chars := fullLabels * 63
	if rem > 1 {
		chars += rem - 1
	}
	// Base32 requires 5 bits/char; exact decoded length for `chars` symbols.
	raw := (chars * 5) / 8
	// Verify roundtrip doesn't exceed 255 (safety margin for valid non-partial base32 quanta).
	for raw > 0 {
		b32Len := base32Encoding.EncodedLen(raw)
		labelsNeeded := (b32Len + 62) / 63
		if b32Len+labelsNeeded+domain.WireLen() <= 255 {
			return raw
		}
		raw--
	}
	return 0
}

func encodePayloadAsLabels(p []byte, domain Name, maxLabel int) (Name, error) {
	if len(p) == 0 {
		return nil, fmt.Errorf("dnswire: cannot encode empty payload as name")
	}
	encoded := make([]byte, base32Encoding.EncodedLen(len(p)))
	base32Encoding.Encode(encoded, p)
	encoded = bytes.ToLower(encoded)

	var labels [][]byte
	for len(encoded) > maxLabel {
		labels = append(labels, encoded[:maxLabel])
		encoded = encoded[maxLabel:]
	}
	if len(encoded) > 0 {
		labels = append(labels, encoded)
	}
	labels = append(labels, domain...)
	return NewName(labels)
}

func decodeBase32Labels(prefix Name) ([]byte, error) {
	joined := bytes.ToUpper(bytes.Join(prefix, nil))
	out := make([]byte, base32Encoding.DecodedLen(len(joined)))
	n, err := base32Encoding.Decode(out, joined)
	if err != nil {
		return nil, err
	}
	return out[:n], nil
}

// EncodeRDataTXT encodes raw bytes as RFC 1035 TXT character-strings.
func EncodeRDataTXT(p []byte) []byte {
	var buf bytes.Buffer
	for len(p) > 255 {
		buf.WriteByte(255)
		buf.Write(p[:255])
		p = p[255:]
	}
	buf.WriteByte(byte(len(p)))
	buf.Write(p)
	return buf.Bytes()
}

// DecodeRDataTXT decodes RFC 1035 TXT RDATA character-strings back into a raw slice.
func DecodeRDataTXT(p []byte) ([]byte, error) {
	var buf bytes.Buffer
	for {
		if len(p) == 0 {
			return nil, io.ErrUnexpectedEOF
		}
		n := int(p[0])
		p = p[1:]
		if len(p) < n {
			return nil, io.ErrUnexpectedEOF
		}
		buf.Write(p[:n])
		p = p[n:]
		if len(p) == 0 {
			break
		}
	}
	return buf.Bytes(), nil
}

// EncodeRDataNULL encodes raw bytes as NULL RDATA.
func EncodeRDataNULL(p []byte) []byte {
	out := make([]byte, len(p))
	copy(out, p)
	return out
}

// DecodeRDataNULL decodes NULL RDATA.
func DecodeRDataNULL(p []byte) ([]byte, error) {
	out := make([]byte, len(p))
	copy(out, p)
	return out, nil
}

// EncodeRDataCAA encodes raw bytes as RFC 8659 CAA RDATA using tag "issue".
func EncodeRDataCAA(p []byte) []byte {
	const tag = "issue"
	rdata := make([]byte, 2+len(tag)+len(p))
	rdata[1] = byte(len(tag))
	copy(rdata[2:], tag)
	copy(rdata[2+len(tag):], p)
	return rdata
}

// DecodeRDataCAA extracts the value portion of RFC 8659 CAA RDATA.
func DecodeRDataCAA(p []byte) ([]byte, error) {
	if len(p) < 2 {
		return nil, io.ErrUnexpectedEOF
	}
	tagLen := int(p[1])
	p = p[2:]
	if len(p) < tagLen {
		return nil, io.ErrUnexpectedEOF
	}
	return p[tagLen:], nil
}

// EncodeRDataSVCB encodes raw bytes as RFC 9460 SVCB/HTTPS RDATA
// (SvcPriority=1, TargetName=".", SvcParamKey=5/ech).
func EncodeRDataSVCB(p []byte) []byte {
	rdata := make([]byte, 7+len(p))
	binary.BigEndian.PutUint16(rdata[0:2], 1)
	rdata[2] = 0
	binary.BigEndian.PutUint16(rdata[3:5], 5)
	binary.BigEndian.PutUint16(rdata[5:7], uint16(len(p)))
	copy(rdata[7:], p)
	return rdata
}

// DecodeRDataSVCB extracts the first SvcParam value from RFC 9460 SVCB/HTTPS RDATA.
func DecodeRDataSVCB(p []byte) ([]byte, error) {
	if len(p) < 7 {
		return nil, io.ErrUnexpectedEOF
	}
	i := 2
	for i < len(p) {
		l := int(p[i])
		i++
		if l == 0 {
			break
		}
		if l&0xc0 == 0xc0 {
			i++
			break
		}
		i += l
	}
	if i+4 > len(p) {
		return nil, io.ErrUnexpectedEOF
	}
	valLen := int(binary.BigEndian.Uint16(p[i+2 : i+4]))
	i += 4
	if i+valLen > len(p) {
		return nil, io.ErrUnexpectedEOF
	}
	return p[i : i+valLen], nil
}

// EncodeRDataName encodes a payload as an uncompressed DNS domain name under domain (for CNAME/NS).
func EncodeRDataName(p []byte, domain Name) ([]byte, error) {
	n, err := encodePayloadAsLabels(p, domain, 63)
	if err != nil {
		return nil, err
	}
	return n.WireFormat(), nil
}

// DecodeRDataName decodes an uncompressed DNS domain name (CNAME/NS RDATA) back into payload.
func DecodeRDataName(data []byte, domain Name) ([]byte, error) {
	n, err := NameFromWireFormat(data)
	if err != nil {
		return nil, err
	}
	return DecodeQueryName(n, domain)
}

// EncodeRDataMX encodes a payload as MX RDATA ([preference:2][name]).
func EncodeRDataMX(p []byte, domain Name) ([]byte, error) {
	w, err := EncodeRDataName(p, domain)
	if err != nil {
		return nil, err
	}
	rdata := make([]byte, 2+len(w))
	binary.BigEndian.PutUint16(rdata[0:2], 10)
	copy(rdata[2:], w)
	return rdata, nil
}

// DecodeRDataMX decodes MX RDATA back into payload.
func DecodeRDataMX(data []byte, domain Name) ([]byte, error) {
	if len(data) < 3 {
		return nil, io.ErrUnexpectedEOF
	}
	return DecodeRDataName(data[2:], domain)
}

// EncodeRDataSRV encodes a payload as SRV RDATA ([priority:2][weight:2][port:2][target]).
func EncodeRDataSRV(p []byte, domain Name) ([]byte, error) {
	w, err := EncodeRDataName(p, domain)
	if err != nil {
		return nil, err
	}
	rdata := make([]byte, 6+len(w))
	binary.BigEndian.PutUint16(rdata[4:6], 443)
	copy(rdata[6:], w)
	return rdata, nil
}

// DecodeRDataSRV decodes SRV RDATA back into payload.
func DecodeRDataSRV(data []byte, domain Name) ([]byte, error) {
	if len(data) < 7 {
		return nil, io.ErrUnexpectedEOF
	}
	return DecodeRDataName(data[6:], domain)
}

// EncodeRDataMultiRR prefixes p with uint16 BE length and splits into chunkSize RRs.
// For AAAA (chunkSize >= 8), each RR reserves byte 0 as a sequence index (0..255)
// so recursive resolvers that reorder AAAA records never corrupt the payload.
func EncodeRDataMultiRR(p []byte, chunkSize int) [][]byte {
	buf := make([]byte, 2+len(p))
	binary.BigEndian.PutUint16(buf[0:2], uint16(len(p)))
	copy(buf[2:], p)
	if chunkSize < 8 {
		chunks := make([][]byte, 0, (len(buf)+chunkSize-1)/chunkSize)
		for len(buf) > 0 {
			chunk := make([]byte, chunkSize)
			n := copy(chunk, buf)
			buf = buf[n:]
			chunks = append(chunks, chunk)
		}
		return chunks
	}
	dataPerChunk := chunkSize - 1
	chunks := make([][]byte, 0, (len(buf)+dataPerChunk-1)/dataPerChunk)
	for idx := 0; len(buf) > 0 && idx < 256; idx++ {
		chunk := make([]byte, chunkSize)
		chunk[0] = byte(idx)
		n := copy(chunk[1:], buf)
		buf = buf[n:]
		chunks = append(chunks, chunk)
	}
	return chunks
}

// DecodeRDataMultiRR reassembles fixed-size RRs (A=4B, AAAA=16B) and strips trailing zero-padding.
// For AAAA (16B chunks), chunks are reordered by their byte-0 sequence index.
func DecodeRDataMultiRR(chunks [][]byte) ([]byte, error) {
	if len(chunks) == 0 || len(chunks) > 256 {
		return nil, io.ErrUnexpectedEOF
	}
	var total bytes.Buffer
	if len(chunks[0]) < 8 {
		for _, c := range chunks {
			total.Write(c)
		}
	} else {
		ordered := make([][]byte, len(chunks))
		seen := make([]bool, len(chunks))
		for _, c := range chunks {
			if len(c) < 8 {
				return nil, io.ErrUnexpectedEOF
			}
			idx := int(c[0])
			if idx >= len(chunks) || seen[idx] {
				return nil, io.ErrUnexpectedEOF
			}
			seen[idx] = true
			ordered[idx] = c[1:]
		}
		for _, part := range ordered {
			total.Write(part)
		}
	}
	b := total.Bytes()
	if len(b) < 2 {
		return nil, io.ErrUnexpectedEOF
	}
	n := int(binary.BigEndian.Uint16(b[0:2]))
	b = b[2:]
	if len(b) < n {
		return nil, io.ErrUnexpectedEOF
	}
	return b[:n], nil
}

// EncodeResponseRRs populates Answer RRs on resp using the query's QTYPE.
func EncodeResponseRRs(resp *Message, q Question, data []byte, domain Name, ttl uint32) error {
	switch q.Type {
	case RRTypeA, RRTypeAAAA:
		chunkSize := 4
		if q.Type == RRTypeAAAA {
			chunkSize = 16
		}
		chunks := EncodeRDataMultiRR(data, chunkSize)
		resp.Answer = make([]RR, len(chunks))
		for i, c := range chunks {
			resp.Answer[i] = RR{Name: q.Name, Type: q.Type, Class: q.Class, TTL: ttl, Data: c}
		}
		return nil
	case RRTypeHTTPS, RRTypeSVCB:
		resp.Answer = []RR{{Name: q.Name, Type: q.Type, Class: q.Class, TTL: ttl, Data: EncodeRDataSVCB(data)}}
		return nil
	case RRTypeTXT:
		resp.Answer = []RR{{Name: q.Name, Type: q.Type, Class: q.Class, TTL: ttl, Data: EncodeRDataTXT(data)}}
		return nil
	case RRTypeNULL:
		resp.Answer = []RR{{Name: q.Name, Type: q.Type, Class: q.Class, TTL: ttl, Data: EncodeRDataNULL(data)}}
		return nil
	case RRTypeCAA:
		resp.Answer = []RR{{Name: q.Name, Type: q.Type, Class: q.Class, TTL: ttl, Data: EncodeRDataCAA(data)}}
		return nil
	case RRTypeCNAME, RRTypeNS:
		rdata, err := EncodeRDataName(data, domain)
		if err != nil {
			return err
		}
		resp.Answer = []RR{{Name: q.Name, Type: q.Type, Class: q.Class, TTL: ttl, Data: rdata}}
		return nil
	case RRTypeMX:
		rdata, err := EncodeRDataMX(data, domain)
		if err != nil {
			return err
		}
		resp.Answer = []RR{{Name: q.Name, Type: q.Type, Class: q.Class, TTL: ttl, Data: rdata}}
		return nil
	case RRTypeSRV:
		rdata, err := EncodeRDataSRV(data, domain)
		if err != nil {
			return err
		}
		resp.Answer = []RR{{Name: q.Name, Type: q.Type, Class: q.Class, TTL: ttl, Data: rdata}}
		return nil
	default:
		return fmt.Errorf("dnswire: unsupported QTYPE %d", q.Type)
	}
}

// DecodeResponseRRs extracts the encoded payload from a DNS response's Answer RRs.
func DecodeResponseRRs(resp *Message, expectedType uint16, domain Name) ([]byte, error) {
	if len(resp.Answer) == 0 {
		return nil, io.ErrUnexpectedEOF
	}
	switch expectedType {
	case RRTypeA, RRTypeAAAA:
		var chunks [][]byte
		for _, rr := range resp.Answer {
			if rr.Type == expectedType {
				chunks = append(chunks, rr.Data)
			}
		}
		if len(chunks) == 0 {
			return nil, io.ErrUnexpectedEOF
		}
		return DecodeRDataMultiRR(chunks)
	case RRTypeHTTPS, RRTypeSVCB:
		for _, rr := range resp.Answer {
			if rr.Type == expectedType {
				return DecodeRDataSVCB(rr.Data)
			}
		}
	case RRTypeTXT:
		for _, rr := range resp.Answer {
			if rr.Type == expectedType {
				return DecodeRDataTXT(rr.Data)
			}
		}
	case RRTypeNULL:
		for _, rr := range resp.Answer {
			if rr.Type == expectedType {
				return DecodeRDataNULL(rr.Data)
			}
		}
	case RRTypeCAA:
		for _, rr := range resp.Answer {
			if rr.Type == expectedType {
				return DecodeRDataCAA(rr.Data)
			}
		}
	case RRTypeCNAME, RRTypeNS:
		for _, rr := range resp.Answer {
			if rr.Type == expectedType {
				return DecodeRDataName(rr.Data, domain)
			}
		}
	case RRTypeMX:
		for _, rr := range resp.Answer {
			if rr.Type == expectedType {
				return DecodeRDataMX(rr.Data, domain)
			}
		}
	case RRTypeSRV:
		for _, rr := range resp.Answer {
			if rr.Type == expectedType {
				return DecodeRDataSRV(rr.Data, domain)
			}
		}
	}
	return nil, fmt.Errorf("dnswire: no matching Answer RR for type %s", RecordTypeString(expectedType))
}
