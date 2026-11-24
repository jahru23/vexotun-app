package core

import (
	"fmt"
	"sync"
	"sync/atomic"
	"time"
)

// LogHandler receives log lines from the Go tunnel core.
type LogHandler interface {
	OnLog(level string, message string)
}

// SocketProtector wraps Android VpnService.protect(fd) to prevent routing loops.
type SocketProtector interface {
	Protect(fd int32) bool
}

type coreLogger struct {
	mu      sync.RWMutex
	handler LogHandler
}

var (
	globalLogger = &coreLogger{}
	bytesUp      atomic.Int64
	bytesDown    atomic.Int64
)

func setLogHandler(h LogHandler) {
	globalLogger.mu.Lock()
	defer globalLogger.mu.Unlock()
	globalLogger.handler = h
}

func logMsg(level, format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	ts := time.Now().Format("15:04:05")
	line := fmt.Sprintf("[%s] %s", ts, msg)

	globalLogger.mu.RLock()
	h := globalLogger.handler
	globalLogger.mu.RUnlock()

	if h != nil {
		h.OnLog(level, line)
	}
}

func resetTraffic() {
	bytesUp.Store(0)
	bytesDown.Store(0)
}
