// Package crypto manages X25519 keys compatible with dnstt and Noise_NK channels.
package crypto

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"strings"

	"golang.org/x/crypto/curve25519"
)

// KeyLen is the X25519 key length in bytes.
const KeyLen = 32

// GeneratePrivkey creates a random 32-byte X25519 private key.
func GeneratePrivkey() ([]byte, error) {
	key := make([]byte, KeyLen)
	if _, err := rand.Read(key); err != nil {
		return nil, err
	}
	return key, nil
}

// PubkeyFromPrivkey derives the X25519 public key corresponding to privkey.
func PubkeyFromPrivkey(privkey []byte) ([]byte, error) {
	if len(privkey) != KeyLen {
		return nil, fmt.Errorf("crypto: invalid private key length %d", len(privkey))
	}
	return curve25519.X25519(privkey, curve25519.Basepoint)
}

// DecodeKey parses a 64-char hex-encoded 32-byte key (ignoring surrounding whitespace).
func DecodeKey(s string) ([]byte, error) {
	clean := strings.TrimSpace(s)
	b, err := hex.DecodeString(clean)
	if err != nil {
		return nil, fmt.Errorf("crypto: invalid hex key: %w", err)
	}
	if len(b) != KeyLen {
		return nil, fmt.Errorf("crypto: expected %d bytes, got %d", KeyLen, len(b))
	}
	return b, nil
}

// EncodeKey formats a 32-byte key as lowercase hex.
func EncodeKey(key []byte) string {
	return hex.EncodeToString(key)
}

// ReadKeyFile loads a hex-encoded 32-byte key from path.
func ReadKeyFile(path string) ([]byte, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return DecodeKey(string(data))
}

// WriteKeyFile saves a 32-byte key as lowercase hex with a trailing newline.
func WriteKeyFile(path string, key []byte, perm os.FileMode) error {
	if len(key) != KeyLen {
		return fmt.Errorf("crypto: invalid key length %d", len(key))
	}
	return os.WriteFile(path, []byte(EncodeKey(key)+"\n"), perm)
}
