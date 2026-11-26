package dnswire

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func TestAll11RecordTypesRoundtrip(t *testing.T) {
	domain, err := ParseName("ns-rwb.prime.biz.id")
	if err != nil {
		t.Fatalf("ParseName: %v", err)
	}

	payloadSmall := []byte("vexdns-hello-1234567890")
	payloadLarge := bytes.Repeat([]byte{0xab, 0xcd, 0xef, 0x01, 0x23}, 80) // 400 bytes

	for _, qt := range AllSupportedRecordTypes {
		payload := payloadLarge
		switch qt {
		case RRTypeCNAME, RRTypeNS, RRTypeMX, RRTypeSRV:
			payload = payloadSmall
		}

		qname, err := EncodeQueryName([]byte("VXT1\x01\x02\x03\x04\x01\xf9"), domain)
		if err != nil {
			t.Fatalf("%s EncodeQueryName: %v", RecordTypeString(qt), err)
		}
		q := Question{Name: qname, Type: qt, Class: ClassIN}
		resp := &Message{
			ID:       0x1337,
			Flags:    0x8400,
			Question: []Question{q},
			Additional: []RR{{
				Name:  Name{},
				Type:  RRTypeOPT,
				Class: 1232,
			}},
		}
		if err := EncodeResponseRRs(resp, q, payload, domain, 60); err != nil {
			t.Fatalf("%s EncodeResponseRRs: %v", RecordTypeString(qt), err)
		}
		wire, err := resp.WireFormat()
		if err != nil {
			t.Fatalf("%s WireFormat: %v", RecordTypeString(qt), err)
		}
		decodedMsg, err := MessageFromWireFormat(wire)
		if err != nil {
			t.Fatalf("%s MessageFromWireFormat: %v", RecordTypeString(qt), err)
		}
		got, err := DecodeResponseRRs(&decodedMsg, qt, domain)
		if err != nil {
			t.Fatalf("%s DecodeResponseRRs: %v", RecordTypeString(qt), err)
		}
		if !bytes.Equal(got, payload) {
			t.Fatalf("%s mismatch: got %d bytes, want %d bytes", RecordTypeString(qt), len(got), len(payload))
		}
	}
}

func TestCompressedCNAMEResponseDecode(t *testing.T) {
	domain, _ := ParseName("ns-rwb.prime.biz.id")
	payload := []byte("hello-compressed-cname")
	qname, _ := EncodeQueryName([]byte("VXT1test"), domain)
	q := Question{Name: qname, Type: RRTypeCNAME, Class: ClassIN}

	// Simulate an intermediate recursive resolver compressing the CNAME domain suffix to offset 12 + queryPrefixLen.
	qWire := qname.WireFormat()
	prefix, _ := qname.TrimSuffix(domain)
	domainOffsetInPkt := 12 + prefix.WireLen() - 1 // points to "ns-rwb.prime.biz.id" inside Question[0].Name

	fullTarget, _ := EncodeRDataName(payload, domain)
	targetName, _ := NameFromWireFormat(fullTarget)
	targetPrefix, _ := targetName.TrimSuffix(domain)
	// Build compressed RDATA: [targetPrefix without trailing 0x00] + [pointer to domainOffsetInPkt]
	tpWire := targetPrefix.WireFormat()
	compRData := append([]byte{}, tpWire[:len(tpWire)-1]...)
	var ptr [2]byte
	binary.BigEndian.PutUint16(ptr[:], 0xc000|uint16(domainOffsetInPkt))
	compRData = append(compRData, ptr[:]...)

	msg := &Message{
		ID:       0x4242,
		Flags:    0x8180,
		Question: []Question{q},
		Answer: []RR{{
			Name:  q.Name,
			Type:  RRTypeCNAME,
			Class: ClassIN,
			TTL:   60,
			Data:  compRData,
		}},
	}
	wire, err := msg.WireFormat()
	if err != nil {
		t.Fatalf("WireFormat: %v", err)
	}
	_ = qWire
	parsed, err := MessageFromWireFormat(wire)
	if err != nil {
		t.Fatalf("MessageFromWireFormat compressed CNAME: %v", err)
	}
	got, err := DecodeResponseRRs(&parsed, RRTypeCNAME, domain)
	if err != nil {
		t.Fatalf("DecodeResponseRRs compressed CNAME: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("compressed CNAME payload mismatch: got %q want %q", got, payload)
	}
}

func TestQueryNameRoundtripAndMaxPayload(t *testing.T) {
	domain, _ := ParseName("ns-biz.prime.biz.id")
	maxLen := MaxQueryPayloadLen(domain)
	if maxLen < 120 || maxLen > 145 {
		t.Fatalf("unexpected MaxQueryPayloadLen(%s) = %d", domain, maxLen)
	}
	raw := bytes.Repeat([]byte{0x5a}, maxLen)
	qname, err := EncodeQueryName(raw, domain)
	if err != nil {
		t.Fatalf("EncodeQueryName maxLen=%d: %v", maxLen, err)
	}
	decoded, err := DecodeQueryName(qname, domain)
	if err != nil {
		t.Fatalf("DecodeQueryName: %v", err)
	}
	if !bytes.Equal(decoded, raw) {
		t.Fatalf("decoded len=%d want %d", len(decoded), len(raw))
	}
}

func TestMultiRRReorderedDecode(t *testing.T) {
	payload := []byte("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
	chunks := EncodeRDataMultiRR(payload, 16)
	if len(chunks) < 3 {
		t.Fatalf("expected >= 3 chunks for AAAA")
	}
	// Reverse order to simulate aggressive recursive DNS RR reordering.
	shuffled := make([][]byte, len(chunks))
	for i := range chunks {
		shuffled[i] = chunks[len(chunks)-1-i]
	}
	got, err := DecodeRDataMultiRR(shuffled)
	if err != nil {
		t.Fatalf("DecodeRDataMultiRR(AAAA) shuffled: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("shuffled AAAA mismatch: got %q want %q", got, payload)
	}
}
