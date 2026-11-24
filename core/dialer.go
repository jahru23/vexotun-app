package core

import (
	"context"
	"fmt"
	"net"
	"syscall"
	"time"
)

// newProtectedDialer returns a net.Dialer whose sockets are protected via Android VpnService.protect().
func newProtectedDialer(protector SocketProtector, timeout time.Duration) *net.Dialer {
	return &net.Dialer{
		Timeout:   timeout,
		KeepAlive: 30 * time.Second,
		Control: func(network, address string, c syscall.RawConn) error {
			if protector == nil {
				return nil
			}
			var protectErr error
			err := c.Control(func(fd uintptr) {
				if !protector.Protect(int32(fd)) {
					protectErr = fmt.Errorf("failed to protect socket fd=%d", fd)
				}
			})
			if err != nil {
				return err
			}
			return protectErr
		},
	}
}

func dialProtectedTCP(ctx context.Context, protector SocketProtector, addr string, timeout time.Duration) (net.Conn, error) {
	d := newProtectedDialer(protector, timeout)
	return d.DialContext(ctx, "tcp", addr)
}
