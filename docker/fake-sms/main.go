package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
	"github.com/google/uuid"
)

type message struct {
	MessageID string    `json:"messageId"`
	To        string    `json:"to"`
	Content   string    `json:"content"`
	Status    string    `json:"status"`
	CreatedAt time.Time `json:"createdAt"`
}

type store struct { sync.Mutex; messages []message; max int }

func main() {
	st := &store{max: envInt("MAX_MESSAGES", 1000)}
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/messages", st.messagesHandler)
	port := os.Getenv("PORT"); if port == "" { port = "8080" }
	if err := http.ListenAndServe(":"+port, mux); err != nil { panic(err) }
}

func (s *store) messagesHandler(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("X-API-Key") != os.Getenv("API_KEY") { http.Error(w, "unauthorized", http.StatusUnauthorized); return }
	scenario := r.Header.Get("X-Mock-Scenario"); if scenario == "" { scenario = "success" }
	switch scenario { case "success", "provider-error", "timeout", "delivery-failed": default: http.Error(w, "unknown mock scenario", http.StatusBadRequest); return }
	if scenario == "provider-error" { http.Error(w, "provider error", http.StatusServiceUnavailable); return }
	if scenario == "timeout" { time.Sleep(10 * time.Second); return }
	switch r.Method {
	case http.MethodPost: s.create(w, r, scenario == "delivery-failed")
	case http.MethodGet: s.list(w, r)
	case http.MethodDelete: s.clear(w)
	default: w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (s *store) create(w http.ResponseWriter, r *http.Request, failed bool) {
	var req struct { To string `json:"to"`; Content string `json:"content"` }
	if json.NewDecoder(r.Body).Decode(&req) != nil || strings.TrimSpace(req.To) == "" || req.Content == "" { http.Error(w, "invalid message", http.StatusBadRequest); return }
	m := message{MessageID: "mock-"+uuid.NewString(), To: req.To, Content: req.Content, Status: "DELIVERED", CreatedAt: time.Now().UTC()}
	if failed { m.Status = "FAILED" }
	s.Lock(); s.messages = append(s.messages, m); if len(s.messages) > s.max { s.messages = s.messages[len(s.messages)-s.max:] }; s.Unlock()
	w.Header().Set("Content-Type", "application/json"); w.WriteHeader(http.StatusAccepted); json.NewEncoder(w).Encode(map[string]string{"messageId": m.MessageID, "status": "ACCEPTED"})
}

func (s *store) list(w http.ResponseWriter, r *http.Request) { to := r.URL.Query().Get("to"); s.Lock(); defer s.Unlock(); out := make([]message, 0); for _, m := range s.messages { if to == "" || m.To == to { out = append(out, m) } }; w.Header().Set("Content-Type", "application/json"); json.NewEncoder(w).Encode(out) }
func (s *store) clear(w http.ResponseWriter) { s.Lock(); s.messages = nil; s.Unlock(); w.WriteHeader(http.StatusNoContent) }
func envInt(k string, fallback int) int { var n int; if _, err := fmt.Sscan(os.Getenv(k), &n); err != nil || n < 1 { return fallback }; return n }
