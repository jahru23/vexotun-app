package core

import (
	"bufio"
	"bytes"
	"fmt"
	"io"
	"net"
	"regexp"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

const defaultUserAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

var (
	rotateRegex  = regexp.MustCompile(`\[rotate=([^\]]+)\]`)
	rotateSeq    atomic.Uint64
	splitReplacer = strings.NewReplacer(
		"[instant_split]", "[split]",
		"[splitDelay]", "[delay_split]",
	)
)

// expandPayload replaces HTTP Injector / HTTP Custom style placeholders and splits into chunks.
// [host] stays targetHost, while [sni] resolves to sni (falling back to targetHost).
func expandPayload(template, targetHost string, targetPort int, sni string) []string {
	if strings.TrimSpace(template) == "" {
		return []string{
			fmt.Sprintf("CONNECT %s:%d HTTP/1.1\r\nHost: %s:%d\r\n\r\n", targetHost, targetPort, targetHost, targetPort),
		}
	}

	hostPort := fmt.Sprintf("%s:%d", targetHost, targetPort)
	portStr := strconv.Itoa(targetPort)
	effectiveSNI := strings.TrimSpace(sni)
	if effectiveSNI == "" {
		effectiveSNI = targetHost
	}
	rawConnect := fmt.Sprintf("CONNECT %s HTTP/1.1\r\nHost: %s\r\n\r\n", hostPort, hostPort)

	withRotate := rotateRegex.ReplaceAllStringFunc(template, func(match string) string {
		sub := rotateRegex.FindStringSubmatch(match)
		if len(sub) < 2 {
			return match
		}
		parts := strings.Split(sub[1], ";")
		if len(parts) == 0 {
			return ""
		}
		idx := rotateSeq.Add(1) - 1
		return strings.TrimSpace(parts[idx%uint64(len(parts))])
	})

	replacer := strings.NewReplacer(
		"[crlf]", "\r\n",
		"[lfcr]", "\n\r",
		"[lf]", "\n",
		"[cr]", "\r",
		"[host_port]", hostPort,
		"[ssh]", hostPort,
		"[host]", targetHost,
		"[sni]", effectiveSNI,
		"[port]", portStr,
		"[protocol]", "HTTP/1.1",
		"[ua]", defaultUserAgent,
		"[raw]", rawConnect,
		"[real_raw]", rawConnect,
		"[netData]", rawConnect,
		"[realData]", rawConnect,
	)

	expanded := replacer.Replace(splitReplacer.Replace(withRotate))
	return strings.Split(expanded, "[split]")
}

func parseHTTPStatusCode(statusLine string) (int, bool) {
	fields := strings.Fields(strings.TrimSpace(statusLine))
	if len(fields) < 2 || !strings.HasPrefix(strings.ToUpper(fields[0]), "HTTP/") {
		return 0, false
	}
	code, err := strconv.Atoi(fields[1])
	if err != nil {
		return 0, false
	}
	return code, true
}

// injectHTTPPayload writes the expanded payload chunks (honoring [split]/[delay_split]) and reads the proxy HTTP response.
// It returns a net.Conn that preserves any buffered bytes already read ahead by bufio.Reader.
func injectHTTPPayload(conn net.Conn, payloadTemplate, targetHost string, targetPort int, sni string) (net.Conn, error) {
	chunks := expandPayload(payloadTemplate, targetHost, targetPort, sni)

	for i, chunk := range chunks {
		subChunks := strings.Split(chunk, "[delay_split]")
		for j, part := range subChunks {
			if part != "" {
				if _, err := conn.Write([]byte(part)); err != nil {
					return nil, fmt.Errorf("write payload chunk: %w", err)
				}
			}
			if j < len(subChunks)-1 {
				time.Sleep(200 * time.Millisecond)
			}
		}
		if i < len(chunks)-1 {
			time.Sleep(80 * time.Millisecond)
		}
	}

	// Read HTTP response header until \r\n\r\n
	_ = conn.SetReadDeadline(time.Now().Add(12 * time.Second))
	defer func() { _ = conn.SetReadDeadline(time.Time{}) }()

	reader := bufio.NewReader(conn)
	var headerBuf bytes.Buffer
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return nil, fmt.Errorf("read proxy response: %w", err)
		}
		headerBuf.WriteString(line)
		if line == "\r\n" || line == "\n" {
			break
		}
		if headerBuf.Len() > 16*1024 {
			return nil, fmt.Errorf("proxy response header too large")
		}
	}

	firstLine := strings.TrimSpace(strings.SplitN(headerBuf.String(), "\n", 2)[0])
	logMsg("DEBUG", "Proxy response: %s", firstLine)

	code, ok := parseHTTPStatusCode(firstLine)
	if !ok || (code != 200 && code != 101) {
		return nil, fmt.Errorf("proxy rejected tunnel: %s", firstLine)
	}
	return &bufferedConn{Conn: conn, r: reader}, nil
}

// bufferedConn wraps a net.Conn with any unread bytes if needed.
type bufferedConn struct {
	net.Conn
	r io.Reader
}

func (b *bufferedConn) Read(p []byte) (int, error) {
	return b.r.Read(p)
}
