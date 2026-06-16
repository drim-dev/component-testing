package relaytest

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"testing"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
)

// client drives the assembled correct app's real HTTP boundary as a given user. It takes a
// testing.TB (not *testing.T) so the SAME assertion blocks run both as ordinary tests and,
// against a recording TB, inside the naive-variant expect-failure wrapper.
type client struct {
	t       testing.TB
	baseURL string
	userID  string
}

func newClient(t testing.TB, userID string) *client {
	return &client{t: t, baseURL: fixture.BaseURL(), userID: userID}
}

// clientAt drives an arbitrary base URL (e.g. a naive host) as a given user.
func clientAt(t testing.TB, baseURL, userID string) *client {
	return &client{t: t, baseURL: baseURL, userID: userID}
}

func (c *client) do(method, path string, body any) *response {
	c.t.Helper()
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			c.t.Fatalf("marshal body: %v", err)
		}
		reader = bytes.NewReader(raw)
	}
	req, err := http.NewRequest(method, c.baseURL+path, reader)
	if err != nil {
		c.t.Fatalf("new request: %v", err)
	}
	if c.userID != "" {
		req.Header.Set("X-User-Id", c.userID)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	return c.send(req)
}

func (c *client) doRaw(req *http.Request) *response {
	c.t.Helper()
	if c.userID != "" {
		req.Header.Set("X-User-Id", c.userID)
	}
	return c.send(req)
}

func (c *client) send(req *http.Request) *response {
	c.t.Helper()
	resp, err := (&http.Client{Timeout: 15 * time.Second}).Do(req)
	if err != nil {
		c.t.Fatalf("%s %s: %v", req.Method, req.URL.Path, err)
	}
	defer func() { _ = resp.Body.Close() }()
	raw, _ := io.ReadAll(resp.Body)
	return &response{t: c.t, status: resp.StatusCode, body: raw, header: resp.Header}
}

func (c *client) get(path string) *response         { return c.do(http.MethodGet, path, nil) }
func (c *client) post(path string, b any) *response { return c.do(http.MethodPost, path, b) }
func (c *client) del(path string) *response         { return c.do(http.MethodDelete, path, nil) }

// response wraps an HTTP reply with assertion helpers.
type response struct {
	t      testing.TB
	status int
	body   []byte
	header http.Header
}

func (r *response) expect(status int) *response {
	r.t.Helper()
	if r.status != status {
		r.t.Fatalf("expected status %d, got %d (body: %s)", status, r.status, string(r.body))
	}
	return r
}

func (r *response) expectCode(code string) *response {
	r.t.Helper()
	var body struct {
		Code string `json:"code"`
	}
	if err := json.Unmarshal(r.body, &body); err != nil {
		r.t.Fatalf("decode error body: %v (raw: %s)", err, string(r.body))
	}
	if body.Code != code {
		r.t.Fatalf("expected error code %q, got %q", code, body.Code)
	}
	return r
}

func (r *response) decode(dst any) {
	r.t.Helper()
	if err := json.Unmarshal(r.body, dst); err != nil {
		r.t.Fatalf("decode body: %v (raw: %s)", err, string(r.body))
	}
}

func (r *response) rawBody() string { return string(r.body) }

// ---- seed helpers (write THROUGH the real constraints, so seeded states are reachable) ----

func seedUser(t *testing.T, handle string) domain.User {
	t.Helper()
	resp := newClient(t, "").post("/users", map[string]string{"handle": handle, "displayName": handle})
	resp.expect(http.StatusCreated)
	var u struct {
		ID          string `json:"id"`
		Handle      string `json:"handle"`
		DisplayName string `json:"displayName"`
	}
	resp.decode(&u)
	return domain.User{ID: u.ID, Handle: u.Handle, DisplayName: u.DisplayName}
}

func seedChannel(t *testing.T, owner domain.User, name string, private bool) string {
	t.Helper()
	resp := newClient(t, owner.ID).post("/channels", map[string]any{"name": name, "private": private})
	resp.expect(http.StatusCreated)
	var ch struct {
		ID string `json:"id"`
	}
	resp.decode(&ch)
	return ch.ID
}

// seedMember adds target to a channel as a plain member (owner/admin caller performs the add).
func seedMember(t *testing.T, by domain.User, channelID string, target domain.User) {
	t.Helper()
	newClient(t, by.ID).post("/channels/"+channelID+"/members", map[string]string{"userId": target.ID}).
		expect(http.StatusCreated)
}

// seedConversation creates (or returns the existing) DM conversation for a–b and returns its id.
func seedConversation(t *testing.T, a, b domain.User) string {
	t.Helper()
	resp := newClient(t, a.ID).post("/dm/conversations", map[string]string{"recipientId": b.ID})
	if resp.status != http.StatusCreated && resp.status != http.StatusOK {
		t.Fatalf("seed conversation: status %d (%s)", resp.status, resp.rawBody())
	}
	var c struct {
		ID string `json:"id"`
	}
	resp.decode(&c)
	return c.ID
}

func seedDmMessage(t *testing.T, sender domain.User, conversationID, text string) {
	t.Helper()
	newClient(t, sender.ID).post("/dm/conversations/"+conversationID+"/messages", map[string]string{"text": text}).
		expect(http.StatusCreated)
}

func dbCount(t *testing.T, table, where string, args ...any) int {
	t.Helper()
	return dbCountTB(t, table, where, args...)
}

// dbCountTB is the testing.TB variant so DB-state asserts run inside the naive-demo recorder.
func dbCountTB(tb testing.TB, table, where string, args ...any) int {
	tb.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	n, err := fixture.Database.Count(ctx, table, where, args...)
	if err != nil {
		tb.Fatalf("count %s: %v", table, err)
	}
	return n
}

// bgCtx is a short-lived background context for harness calls inside assertions.
func bgCtx() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), 10*time.Second)
}
