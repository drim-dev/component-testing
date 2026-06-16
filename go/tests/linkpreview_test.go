package relaytest

import (
	"net/http"
	"testing"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/app"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
)

func TestS_LP_01_unfurl_success_sets_title(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	ch := seedChannel(t, ada, "team", false)
	fixture.Unfurl.ProgramOK("Example")

	var msg struct {
		LinkPreviewTitle *string `json:"linkPreviewTitle"`
	}
	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "see https://example.com here"}).
		expect(http.StatusCreated).decode(&msg)
	if msg.LinkPreviewTitle == nil || *msg.LinkPreviewTitle != "Example" {
		t.Fatalf("expected linkPreviewTitle Example, got %v", msg.LinkPreviewTitle)
	}
	if c := fixture.Unfurl.RequestCount(); c != 1 {
		t.Fatalf("expected exactly one unfurl request, got %d", c)
	}
}

// ---- G-HTTP: a slow/failing unfurl must degrade, never escape or hang ----

func TestS_LP_02_slow_unfurl_degrades_within_deadline(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	ch := seedChannel(t, ada, "team", false)
	assertSlowUnfurlDegrades(t, fixture.BaseURL(), ada, ch)
}

func TestS_LP_02_naive_no_timeout_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	ch := seedChannel(t, ada, "team", false)
	naive := fixture.NaiveApp(func(d *app.Deps) { d.LinkPreviewer = naiveLinkPreviewer{baseURL: fixture.Unfurl.BaseURL()} })
	defer naive.Close()
	expectCatchToFail(t, "G-HTTP", func(tb testing.TB) {
		assertSlowUnfurlDegrades(tb, naive.BaseURL(), ada, ch)
	})
}

func assertSlowUnfurlDegrades(tb testing.TB, baseURL string, sender domain.User, channelID string) {
	tb.Helper()
	fixture.Unfurl.ProgramDelay(2 * time.Second)
	start := time.Now()
	resp := clientAt(tb, baseURL, sender.ID).post("/channels/"+channelID+"/messages", map[string]any{"text": "slow https://example.com"})
	elapsed := time.Since(start)
	resp.expect(http.StatusCreated)
	if elapsed > 1500*time.Millisecond {
		tb.Fatalf("post took %v, exceeding the 1.5 s degradation budget (unfurl was not bounded)", elapsed)
	}
	var msg struct {
		LinkPreviewTitle *string `json:"linkPreviewTitle"`
	}
	resp.decode(&msg)
	if msg.LinkPreviewTitle != nil {
		tb.Fatalf("a timed-out unfurl must degrade to null title, got %v", *msg.LinkPreviewTitle)
	}
}

func TestS_LP_03_server_error_degrades(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	ch := seedChannel(t, ada, "team", false)
	fixture.Unfurl.ProgramServerError()
	var msg struct {
		LinkPreviewTitle *string `json:"linkPreviewTitle"`
	}
	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "broken https://example.com"}).
		expect(http.StatusCreated).decode(&msg)
	if msg.LinkPreviewTitle != nil {
		t.Fatalf("a 500 unfurl must degrade to null title, got %v", *msg.LinkPreviewTitle)
	}
}

func TestS_LP_04_circuit_breaker_opens(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	ch := seedChannel(t, ada, "team", false)
	fixture.Unfurl.ProgramServerError()
	for i := 0; i < 5; i++ {
		newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "fail https://example.com"}).
			expect(http.StatusCreated)
	}
	var msg struct {
		LinkPreviewTitle *string `json:"linkPreviewTitle"`
	}
	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "sixth https://example.com"}).
		expect(http.StatusCreated).decode(&msg)
	if msg.LinkPreviewTitle != nil {
		t.Fatalf("breaker-open post should have a null title, got %v", *msg.LinkPreviewTitle)
	}
	if c := fixture.Unfurl.RequestCount(); c != 5 {
		t.Fatalf("breaker should skip the 6th outbound call: expected 5 requests, got %d", c)
	}
}

func TestS_LP_05_direct_proxy(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	fixture.Unfurl.ProgramOK("Proxied")
	var body struct {
		Title string `json:"title"`
	}
	newClient(t, ada.ID).get("/links/preview?url=https://example.com").expect(http.StatusOK).decode(&body)
	if body.Title != "Proxied" {
		t.Fatalf("expected proxied title, got %q", body.Title)
	}
	fixture.Unfurl.ProgramServerError()
	newClient(t, ada.ID).get("/links/preview?url=https://example.com").
		expect(http.StatusBadGateway).expectCode("unfurl:upstream_failed")
	newClient(t, ada.ID).get("/links/preview").expect(http.StatusUnprocessableEntity)
}
