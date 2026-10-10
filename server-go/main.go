// iv-server — Plan B inference front-end (PRD §19). Runs as the shell user on the board, binds 127.0.0.1 only,
// and proxies HTTP to a single long-lived `iv_cli serve` worker (JSON lines, PRD §19.3) that keeps models warm.
//
// API (matches android/engine HttpInferenceEngine):
//   GET  /v1/health
//   POST /v1/asr/transcribe?lang_mode=auto|en|hi&sr=16000   body: raw PCM16LE mono
//   POST /v1/llm/generate?stream=1   body: {"prompt","task","sampler","max_tokens"} → NDJSON {"tok"}… {"done","text","stats"}
//   POST /v1/llm/abort
//
// Build: CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o iv-server .
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

type worker struct {
	mu      sync.Mutex // one request at a time on the NPU
	cmd     *exec.Cmd
	in      io.WriteCloser
	out     *bufio.Scanner
	nextID  int64
	info    json.RawMessage
	started time.Time
}

var (
	cliPath  = flag.String("cli", "/data/local/tmp/iv/run.sh", "absolute path to iv_cli wrapper (never a bare name)")
	models   = flag.String("models", "/data/local/tmp/iv/models", "models dir")
	listen   = flag.String("listen", "127.0.0.1:8765", "listen address (loopback only)")
	tmpDir   = flag.String("tmp", "/data/local/tmp/iv/run", "temp dir for request payloads")
	token    = flag.String("token", "", "optional X-IV-Token")
	restarts int64
	w        *worker
	abortID  atomic.Int64
)

func (wk *worker) start() error {
	wk.cmd = exec.Command(*cliPath, "serve")
	wk.cmd.Stderr = os.Stderr
	var err error
	if wk.in, err = wk.cmd.StdinPipe(); err != nil {
		return err
	}
	stdout, err := wk.cmd.StdoutPipe()
	if err != nil {
		return err
	}
	wk.out = bufio.NewScanner(stdout)
	wk.out.Buffer(make([]byte, 1<<20), 16<<20)
	if err = wk.cmd.Start(); err != nil {
		return err
	}
	wk.started = time.Now()
	res, err := wk.call(map[string]any{"op": "init", "models": *models}, nil)
	if err != nil {
		return err
	}
	wk.info = res["info"]
	return nil
}

// call sends one request and reads lines until a terminal line for this id; onTok gets streamed pieces.
func (wk *worker) call(req map[string]any, onTok func(string)) (map[string]json.RawMessage, error) {
	wk.nextID++
	id := wk.nextID
	req["id"] = id
	b, _ := json.Marshal(req)
	if _, err := wk.in.Write(append(b, '\n')); err != nil {
		return nil, err
	}
	for wk.out.Scan() {
		var m map[string]json.RawMessage
		if json.Unmarshal(wk.out.Bytes(), &m) != nil {
			continue
		}
		if tok, ok := m["tok"]; ok {
			var s string
			_ = json.Unmarshal(tok, &s)
			if onTok != nil {
				onTok(s)
			}
			continue
		}
		return m, nil
	}
	return nil, fmt.Errorf("worker exited: %v", wk.out.Err())
}

func ensureWorker() error {
	if w != nil && w.cmd != nil && w.cmd.ProcessState == nil {
		return nil
	}
	backoff := []time.Duration{0, time.Second, 3 * time.Second, 7 * time.Second, 15 * time.Second}
	var err error
	for _, d := range backoff {
		time.Sleep(d)
		nw := &worker{}
		if err = nw.start(); err == nil {
			w = nw
			atomic.AddInt64(&restarts, 1)
			return nil
		}
		log.Printf("worker start failed: %v", err)
	}
	return err
}

func writeErr(rw http.ResponseWriter, code int, ivCode, msg string) {
	rw.Header().Set("Content-Type", "application/json")
	rw.WriteHeader(code)
	_ = json.NewEncoder(rw).Encode(map[string]any{"error": map[string]any{"code": ivCode, "message": msg, "retryable": code == 503}})
}

func auth(h http.HandlerFunc) http.HandlerFunc {
	return func(rw http.ResponseWriter, r *http.Request) {
		if *token != "" && r.Header.Get("X-IV-Token") != *token {
			writeErr(rw, 401, "IV-E605", "bad token")
			return
		}
		h(rw, r)
	}
}

func main() {
	flag.Parse()
	_ = os.MkdirAll(*tmpDir, 0o755)
	if !filepath.IsAbs(*cliPath) {
		log.Fatal("-cli must be an absolute path")
	}
	if err := ensureWorker(); err != nil {
		log.Printf("initial worker start failed (will retry on demand): %v", err)
	}

	http.HandleFunc("/v1/health", auth(func(rw http.ResponseWriter, r *http.Request) {
		status := "ok"
		var info json.RawMessage
		if w == nil {
			status = "error"
		} else {
			info = w.info
		}
		rw.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(rw).Encode(map[string]any{"status": status, "info": info, "worker_restarts": atomic.LoadInt64(&restarts) - 1})
	}))

	http.HandleFunc("/v1/asr/transcribe", auth(func(rw http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		p := filepath.Join(*tmpDir, fmt.Sprintf("asr-%d.pcm", time.Now().UnixNano()))
		if err := os.WriteFile(p, body, 0o644); err != nil {
			writeErr(rw, 500, "IV-E202", err.Error())
			return
		}
		defer os.Remove(p)
		sr, _ := strconv.Atoi(r.URL.Query().Get("sr"))
		if sr == 0 {
			sr = 16000
		}
		mode := r.URL.Query().Get("lang_mode")
		if mode == "" {
			mode = "auto"
		}
		if err := ensureWorker(); err != nil {
			writeErr(rw, 503, "IV-E605", err.Error())
			return
		}
		w.mu.Lock()
		defer w.mu.Unlock()
		res, err := w.call(map[string]any{"op": "asr", "pcm_path": p, "sr": sr, "lang_mode": mode}, nil)
		if err != nil {
			w = nil
			writeErr(rw, 503, "IV-E202", err.Error())
			return
		}
		if e, ok := res["error"]; ok {
			rw.WriteHeader(503)
			_, _ = rw.Write([]byte(`{"error":` + string(e) + `}`))
			return
		}
		rw.Header().Set("Content-Type", "application/json")
		_, _ = rw.Write(res["result"])
	}))

	http.HandleFunc("/v1/llm/generate", auth(func(rw http.ResponseWriter, r *http.Request) {
		var req struct {
			Prompt    string `json:"prompt"`
			Task      string `json:"task"`
			Sampler   string `json:"sampler"`
			MaxTokens int    `json:"max_tokens"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeErr(rw, 400, "IV-E301", err.Error())
			return
		}
		if err := ensureWorker(); err != nil {
			writeErr(rw, 503, "IV-E605", err.Error())
			return
		}
		p := filepath.Join(*tmpDir, fmt.Sprintf("prompt-%d.txt", time.Now().UnixNano()))
		_ = os.WriteFile(p, []byte(req.Prompt), 0o644)
		defer os.Remove(p)
		stream := r.URL.Query().Get("stream") == "1"
		fl, _ := rw.(http.Flusher)
		rw.Header().Set("Content-Type", "application/x-ndjson")
		enc := json.NewEncoder(rw)
		w.mu.Lock()
		defer w.mu.Unlock()
		res, err := w.call(map[string]any{"op": "generate", "prompt_path": p, "task": req.Task, "sampler": req.Sampler, "max_tokens": req.MaxTokens},
			func(tok string) {
				if stream {
					_ = enc.Encode(map[string]string{"tok": tok})
					if fl != nil {
						fl.Flush()
					}
				}
			})
		if err != nil {
			w = nil
			_ = enc.Encode(map[string]any{"error": map[string]any{"code": "IV-E301", "message": err.Error(), "retryable": true}})
			return
		}
		if e, ok := res["error"]; ok {
			_, _ = rw.Write([]byte(`{"error":` + string(e) + "}\n"))
			return
		}
		_ = enc.Encode(map[string]any{"done": true, "text": res["text"], "stats": res["stats"]})
	}))

	http.HandleFunc("/v1/llm/abort", auth(func(rw http.ResponseWriter, r *http.Request) {
		// The worker reads stdin only between requests; abort is best-effort (next request resets the dialog).
		abortID.Add(1)
		_, _ = rw.Write([]byte(`{"aborted":true}`))
	}))

	log.Printf("iv-server listening on %s (cli=%s)", *listen, *cliPath)
	log.Fatal(http.ListenAndServe(*listen, nil))
}
