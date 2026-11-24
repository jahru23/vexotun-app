package protocol

import (
	"vexotun/core/vexdns/dnswire"
)

// worstCaseQuestionName returns a valid ~253-byte query Name for conservative MTU calculations.
func worstCaseQuestionName() dnswire.Name {
	a63 := make([]byte, 63)
	for i := range a63 {
		a63[i] = 'a'
	}
	a60 := make([]byte, 60)
	for i := range a60 {
		a60[i] = 'a'
	}
	n, _ := dnswire.NewName([][]byte{a63, a63, a63, a60})
	return n
}

// MaxResponsePayload computes the maximum raw downstream frame bytes that can be encoded
// in a single DNS response of qtype without exceeding maxUDPBytes on the wire.
// When q is provided, it uses q's exact Question Name; when nil, it uses worst-case 254B QNAME.
func MaxResponsePayload(domain dnswire.Name, qtype uint16, q *dnswire.Question, maxUDPBytes int) int {
	if maxUDPBytes <= 64 {
		return 0
	}
	var question dnswire.Question
	if q != nil {
		question = *q
	} else {
		question = dnswire.Question{
			Name:  worstCaseQuestionName(),
			Type:  qtype,
			Class: dnswire.ClassIN,
		}
	}

	optClass := uint16(maxUDPBytes)
	if maxUDPBytes > 0xffff {
		optClass = 0xffff
	}

	resp := &dnswire.Message{
		ID:         0x1234,
		Flags:      0x8400,
		Question:   []dnswire.Question{question},
		Additional: []dnswire.RR{{Name: dnswire.Name{}, Type: dnswire.RRTypeOPT, Class: optClass}},
	}

	// Name-based records cannot exceed 255 wire bytes of DNS Name (domain + base32 prefix).
	high := maxUDPBytes
	switch qtype {
	case dnswire.RRTypeCNAME, dnswire.RRTypeNS, dnswire.RRTypeMX, dnswire.RRTypeSRV:
		high = dnswire.MaxQueryPayloadLen(domain)
	}

	low := 0
	for low < high {
		mid := (low + high + 1) / 2
		probeData := make([]byte, mid)
		if err := dnswire.EncodeResponseRRs(resp, question, probeData, domain, 60); err != nil {
			high = mid - 1
			continue
		}
		wire, err := resp.WireFormat()
		if err != nil || len(wire) > maxUDPBytes {
			high = mid - 1
			continue
		}
		low = mid
	}
	return low
}

// ComputePayloadLimits returns the conservative worst-case downstream payload budget (in raw bytes)
// for every supported QTYPE given maxUDPBytes.
func ComputePayloadLimits(domain dnswire.Name, maxUDPBytes int) map[uint16]int {
	limits := make(map[uint16]int, len(dnswire.AllSupportedRecordTypes))
	for _, qt := range dnswire.AllSupportedRecordTypes {
		limits[qt] = MaxResponsePayload(domain, qt, nil, maxUDPBytes)
	}
	return limits
}

// SafeKCPMTU computes the KCP MTU corresponding to a maximum response payload size (subtracting 2B frame len).
// Returns 0 if the capacity is below MinUsableKCPMTU.
func SafeKCPMTU(maxPayloadBytes int) int {
	mtu := maxPayloadBytes - 2
	if mtu < MinUsableKCPMTU {
		return 0
	}
	return mtu
}

// MaxQueryPayloadForUDP computes the safe raw payload bytes that keep an encoded DNS query packet
// within maxQueryUDPBytes (useful on carriers that filter long upstream DNS queries).
func MaxQueryPayloadForUDP(domain dnswire.Name, maxQueryUDPBytes int) int {
	maxNameRaw := dnswire.MaxQueryPayloadLen(domain)
	if maxQueryUDPBytes <= 0 {
		return maxNameRaw
	}
	low, high := 0, maxNameRaw
	for low < high {
		mid := (low + high + 1) / 2
		qname, err := dnswire.EncodeQueryName(make([]byte, mid), domain)
		if err != nil {
			high = mid - 1
			continue
		}
		msg := &dnswire.Message{
			ID:       1,
			Flags:    0x0100,
			Question: []dnswire.Question{{Name: qname, Type: dnswire.RRTypeTXT, Class: dnswire.ClassIN}},
			Additional: []dnswire.RR{{
				Name:  dnswire.Name{},
				Type:  dnswire.RRTypeOPT,
				Class: 1232,
			}},
		}
		wire, err := msg.WireFormat()
		if err != nil || len(wire) > maxQueryUDPBytes {
			high = mid - 1
			continue
		}
		low = mid
	}
	return low
}
