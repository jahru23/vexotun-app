package crypto

import (
	"encoding/binary"
	"errors"
	"io"
	"time"

	"github.com/flynn/noise"
)

var (
	cipherSuite = noise.NewCipherSuite(noise.DH25519, noise.CipherChaChaPoly, noise.HashBLAKE2s)
	// dnsttPrologue keeps Noise_NK wire compatibility with standard dnstt-client and /etc/dnstt/server.key.
	dnsttPrologue = []byte("dnstt 2020-04-13")
)

type socket struct {
	recvPipe   *io.PipeReader
	sendCipher *noise.CipherState
	io.ReadWriteCloser
}

func newNoiseSocket(rwc io.ReadWriteCloser, recvCipher, sendCipher *noise.CipherState) *socket {
	pr, pw := io.Pipe()
	go func() {
		var closeErr error
		defer func() {
			_ = pw.CloseWithError(closeErr)
		}()
		for {
			msg, err := readNoiseFrame(rwc)
			if err != nil {
				closeErr = err
				return
			}
			plain, err := recvCipher.Decrypt(nil, nil, msg)
			if err != nil {
				closeErr = err
				return
			}
			if _, err := pw.Write(plain); err != nil {
				closeErr = err
				return
			}
		}
	}()
	return &socket{
		recvPipe:        pr,
		sendCipher:      sendCipher,
		ReadWriteCloser: rwc,
	}
}

func (s *socket) Read(p []byte) (int, error) {
	return s.recvPipe.Read(p)
}

func (s *socket) Write(p []byte) (int, error) {
	total := 0
	for len(p) > 0 {
		n := len(p)
		if n > 4096 {
			n = 4096
		}
		cipherMsg, err := s.sendCipher.Encrypt(nil, nil, p[:n])
		if err != nil {
			return total, err
		}
		if err := writeNoiseFrame(s.ReadWriteCloser, cipherMsg); err != nil {
			return total, err
		}
		total += n
		p = p[n:]
	}
	return total, nil
}

func readNoiseFrame(r io.Reader) ([]byte, error) {
	var length uint16
	if err := binary.Read(r, binary.BigEndian, &length); err != nil {
		return nil, err
	}
	msg := make([]byte, int(length))
	if _, err := io.ReadFull(r, msg); err != nil {
		if err == io.EOF {
			return nil, io.ErrUnexpectedEOF
		}
		return nil, err
	}
	return msg, nil
}

func writeNoiseFrame(w io.Writer, msg []byte) error {
	if len(msg) > 0xffff {
		return errors.New("crypto: noise message exceeds 65535 bytes")
	}
	var hdr [2]byte
	binary.BigEndian.PutUint16(hdr[:], uint16(len(msg)))
	if _, err := w.Write(hdr[:]); err != nil {
		return err
	}
	_, err := w.Write(msg)
	return err
}

func newNoiseConfig() noise.Config {
	return noise.Config{
		CipherSuite: cipherSuite,
		Pattern:     noise.HandshakeNK,
		Prologue:    dnsttPrologue,
	}
}

// WrapClient runs the initiator Noise_NK handshake over rwc using serverPubkey.
func WrapClient(rwc io.ReadWriteCloser, serverPubkey []byte) (io.ReadWriteCloser, error) {
	if len(serverPubkey) != KeyLen {
		return nil, errors.New("crypto: invalid server pubkey length")
	}
	cfg := newNoiseConfig()
	cfg.Initiator = true
	cfg.PeerStatic = serverPubkey
	hs, err := noise.NewHandshakeState(cfg)
	if err != nil {
		return nil, err
	}

	msg, _, _, err := hs.WriteMessage(nil, nil)
	if err != nil {
		return nil, err
	}
	if err := writeNoiseFrame(rwc, msg); err != nil {
		return nil, err
	}

	respMsg, err := readNoiseFrame(rwc)
	if err != nil {
		return nil, err
	}
	payload, sendCipher, recvCipher, err := hs.ReadMessage(nil, respMsg)
	if err != nil {
		return nil, err
	}
	if len(payload) != 0 {
		return nil, errors.New("crypto: unexpected server noise handshake payload")
	}
	if dc, ok := rwc.(interface{ SetDeadline(time.Time) error }); ok {
		_ = dc.SetDeadline(time.Time{})
	}
	return newNoiseSocket(rwc, recvCipher, sendCipher), nil
}

// WrapServer runs the responder Noise_NK handshake over rwc using serverPrivkey.
func WrapServer(rwc io.ReadWriteCloser, serverPrivkey []byte) (io.ReadWriteCloser, error) {
	pub, err := PubkeyFromPrivkey(serverPrivkey)
	if err != nil {
		return nil, err
	}
	cfg := newNoiseConfig()
	cfg.Initiator = false
	cfg.StaticKeypair = noise.DHKey{
		Private: serverPrivkey,
		Public:  pub,
	}
	hs, err := noise.NewHandshakeState(cfg)
	if err != nil {
		return nil, err
	}

	reqMsg, err := readNoiseFrame(rwc)
	if err != nil {
		return nil, err
	}
	payload, _, _, err := hs.ReadMessage(nil, reqMsg)
	if err != nil {
		return nil, err
	}
	if len(payload) != 0 {
		return nil, errors.New("crypto: unexpected client noise handshake payload")
	}

	respMsg, recvCipher, sendCipher, err := hs.WriteMessage(nil, nil)
	if err != nil {
		return nil, err
	}
	if err := writeNoiseFrame(rwc, respMsg); err != nil {
		return nil, err
	}
	if dc, ok := rwc.(interface{ SetDeadline(time.Time) error }); ok {
		_ = dc.SetDeadline(time.Time{})
	}
	return newNoiseSocket(rwc, recvCipher, sendCipher), nil
}
