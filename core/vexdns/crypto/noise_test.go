package crypto

import (
	"bytes"
	"io"
	"net"
	"testing"
)

func TestNoiseRoundtrip(t *testing.T) {
	priv, err := GeneratePrivkey()
	if err != nil {
		t.Fatalf("GeneratePrivkey: %v", err)
	}
	pub, err := PubkeyFromPrivkey(priv)
	if err != nil {
		t.Fatalf("PubkeyFromPrivkey: %v", err)
	}

	cConn, sConn := net.Pipe()
	errCh := make(chan error, 1)
	go func() {
		srv, err := WrapServer(sConn, priv)
		if err != nil {
			errCh <- err
			return
		}
		defer srv.Close()
		buf := make([]byte, 64)
		n, err := srv.Read(buf)
		if err != nil {
			errCh <- err
			return
		}
		_, err = srv.Write(bytes.ToUpper(buf[:n]))
		errCh <- err
	}()

	cli, err := WrapClient(cConn, pub)
	if err != nil {
		t.Fatalf("WrapClient: %v", err)
	}
	defer cli.Close()

	if _, err := cli.Write([]byte("vexdns-secret-hello")); err != nil {
		t.Fatalf("cli.Write: %v", err)
	}
	buf := make([]byte, 64)
	n, err := io.ReadAtLeast(cli, buf, 19)
	if err != nil {
		t.Fatalf("cli.Read: %v", err)
	}
	if string(buf[:n]) != "VEXDNS-SECRET-HELLO" {
		t.Fatalf("got %q want VEXDNS-SECRET-HELLO", string(buf[:n]))
	}
	if err := <-errCh; err != nil {
		t.Fatalf("server error: %v", err)
	}
}
