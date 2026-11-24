package protocol

import (
	"bytes"
	"encoding/binary"
	"errors"
	"testing"

	"vexotun/core/vexdns/dnswire"
)

func TestProbeMACVerification(t *testing.T) {
	pub := bytes.Repeat([]byte{0x42}, 32)
	otherPub := bytes.Repeat([]byte{0x99}, 32)

	reqBytes, req, err := EncodeProbeRequest(pub, 505, 90)
	if err != nil {
		t.Fatalf("EncodeProbeRequest: %v", err)
	}
	if len(reqBytes) != 90 {
		t.Fatalf("desiredRawLen=90 got %d", len(reqBytes))
	}

	parsedReq, isProbe, err := DecodeProbeRequest(pub, reqBytes)
	if !isProbe || err != nil {
		t.Fatalf("DecodeProbeRequest failed: isProbe=%v err=%v", isProbe, err)
	}
	if parsedReq.TargetRespBytes != 505 || parsedReq.Nonce != req.Nonce {
		t.Fatalf("parsed mismatch: %+v vs %+v", parsedReq, req)
	}

	// Scanner without server pubkey must fail MAC check.
	_, isProbe, err = DecodeProbeRequest(otherPub, reqBytes)
	if !isProbe || !errors.Is(err, ErrInvalidProbeMAC) {
		t.Fatalf("expected ErrInvalidProbeMAC, got isProbe=%v err=%v", isProbe, err)
	}

	respBytes, err := EncodeProbeResponse(pub, parsedReq, dnswire.RRTypeHTTPS, 1232, 9999, 180)
	if err != nil {
		t.Fatalf("EncodeProbeResponse: %v", err)
	}
	if len(respBytes) != 180 {
		t.Fatalf("resp len=%d want 180", len(respBytes))
	}

	resp, err := DecodeProbeResponse(pub, req.Nonce, dnswire.RRTypeHTTPS, respBytes)
	if err != nil {
		t.Fatalf("DecodeProbeResponse: %v", err)
	}
	if resp.ServerMaxUDP != 1232 || resp.TimeMsLo != 9999 {
		t.Fatalf("unexpected probe response: %+v", resp)
	}

	if _, err := DecodeProbeResponse(pub, req.Nonce, dnswire.RRTypeTXT, respBytes); !errors.Is(err, ErrInvalidProbeMAC) {
		t.Fatalf("cross-qtype probe response verification should fail with ErrInvalidProbeMAC, got %v", err)
	}
}

func TestVexoraAndCompatDemux(t *testing.T) {
	id := [4]byte{0xde, 0xad, 0xbe, 0xef}
	pkts := [][]byte{[]byte("kcp-seg-1"), []byte("kcp-seg-2222")}
	vxWire, err := EncodeVexoraQuery(id, 505, pkts)
	if err != nil {
		t.Fatalf("EncodeVexoraQuery: %v", err)
	}
	parsedVX, err := DecodeQueryPayload(vxWire)
	if err != nil {
		t.Fatalf("DecodeQueryPayload(VXT1): %v", err)
	}
	if parsedVX.Mode != ModeVexora || parsedVX.DownBudget != 505 || len(parsedVX.Packets) != 2 {
		t.Fatalf("unexpected Vexora parse: %+v", parsedVX)
	}
	if !bytes.Equal(parsedVX.Packets[0], pkts[0]) || !bytes.Equal(parsedVX.Packets[1], pkts[1]) {
		t.Fatalf("packet mismatch")
	}

	// dnstt compat poll packet: 8B ClientID + padding(224+8) + 8 random bytes
	dtPoll := make([]byte, 8+1+8)
	copy(dtPoll[:8], []byte("12345678"))
	dtPoll[8] = 224 + 8
	parsedDT, err := DecodeQueryPayload(dtPoll)
	if err != nil {
		t.Fatalf("DecodeQueryPayload(compat poll): %v", err)
	}
	if parsedDT.Mode != ModeCompat || len(parsedDT.Packets) != 0 {
		t.Fatalf("unexpected dnstt poll parse: %+v", parsedDT)
	}

	// dnstt compat data packet: 8B ClientID + 3B padding (prefix=227) + pktLen + pkt
	var dtData bytes.Buffer
	dtData.Write([]byte("abcdefgh"))
	dtData.WriteByte(224 + 3)
	dtData.Write([]byte{1, 2, 3})
	dtData.WriteByte(5)
	dtData.Write([]byte("hello"))
	parsedDTData, err := DecodeQueryPayload(dtData.Bytes())
	if err != nil || parsedDTData.Mode != ModeCompat || len(parsedDTData.Packets) != 1 || string(parsedDTData.Packets[0]) != "hello" {
		t.Fatalf("unexpected dnstt data parse: err=%v parsed=%+v", err, parsedDTData)
	}
	_ = binary.BigEndian
}

func TestMTUCalculations(t *testing.T) {
	domain, _ := dnswire.ParseName("ns-rwb.prime.biz.id")
	limits505 := ComputePayloadLimits(domain, 505)
	if SafeKCPMTU(limits505[dnswire.RRTypeHTTPS]) < 150 {
		t.Fatalf("expected >=150 KCP MTU for HTTPS@505, got %d (payload=%d)", SafeKCPMTU(limits505[dnswire.RRTypeHTTPS]), limits505[dnswire.RRTypeHTTPS])
	}
	if SafeKCPMTU(limits505[dnswire.RRTypeA]) < 40 {
		t.Fatalf("expected >=40 KCP MTU for A@505, got %d (payload=%d)", SafeKCPMTU(limits505[dnswire.RRTypeA]), limits505[dnswire.RRTypeA])
	}
	limits1232 := ComputePayloadLimits(domain, 1232)
	if SafeKCPMTU(limits1232[dnswire.RRTypeTXT]) < 850 {
		t.Fatalf("expected >=850 KCP MTU for TXT@1232, got %d", SafeKCPMTU(limits1232[dnswire.RRTypeTXT]))
	}
}
