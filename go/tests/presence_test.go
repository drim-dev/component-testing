package relaytest

import (
	"encoding/json"
	"net/http"
	"testing"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/app"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
)

func TestS_PR_01_unary_online_from_presence(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	fixture.Presence.SetOnline(bob.ID)
	var body struct {
		Status string `json:"status"`
	}
	newClient(t, ada.ID).get("/users/" + bob.ID + "/presence").expect(http.StatusOK).decode(&body)
	if body.Status != "online" {
		t.Fatalf("expected online when the presence service reports online, got %q", body.Status)
	}
}

func TestS_PR_02_no_heartbeat_is_offline(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	cleo := seedUser(t, "cleo")
	var body struct {
		Status string `json:"status"`
	}
	newClient(t, ada.ID).get("/users/" + cleo.ID + "/presence").expect(http.StatusOK).decode(&body)
	if body.Status != "offline" {
		t.Fatalf("expected offline without heartbeat, got %q", body.Status)
	}
}

func TestS_PR_03_channel_presence_complete(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	ch := seedChannel(t, ada, "team", false)
	members := []domain.User{ada}
	for _, h := range []string{"b", "c", "d", "e"} {
		u := seedUser(t, "user_"+h)
		seedMember(t, ada, ch, u)
		members = append(members, u)
	}
	fixture.Presence.SetOnline(members[1].ID)
	fixture.Presence.SetOnline(members[2].ID)

	var body struct {
		Members []struct {
			UserID string `json:"userId"`
			Status string `json:"status"`
		} `json:"members"`
	}
	newClient(t, ada.ID).get("/channels/" + ch + "/presence").expect(http.StatusOK).decode(&body)
	if len(body.Members) != 5 {
		t.Fatalf("expected 5 presence entries, got %d", len(body.Members))
	}
	online := 0
	for _, m := range body.Members {
		if m.Status == "online" {
			online++
		}
	}
	if online != 2 {
		t.Fatalf("expected exactly 2 online, got %d", online)
	}
}

// ---- G-GRPC: a mid-stream error must surface as 502, never a partial list ----

func TestS_PR_04_partial_stream_is_502(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	ch := seedChannel(t, ada, "team", false)
	for _, h := range []string{"b", "c", "d"} {
		seedMember(t, ada, ch, seedUser(t, "user_"+h))
	}
	assertPartialStreamRejected(t, fixture.BaseURL(), ada, ch)
}

func TestS_PR_04_naive_swallows_partial_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	ch := seedChannel(t, ada, "team", false)
	for _, h := range []string{"b", "c", "d"} {
		seedMember(t, ada, ch, seedUser(t, "user_"+h))
	}
	naive := fixture.NaiveApp(func(d *app.Deps) { d.Presence = naivePresenceClient{correct: d.Presence} })
	defer naive.Close()
	expectCatchToFail(t, "G-GRPC", func(tb testing.TB) {
		assertPartialStreamRejected(tb, naive.BaseURL(), ada, ch)
	})
}

func assertPartialStreamRejected(tb testing.TB, baseURL string, caller domain.User, channelID string) {
	tb.Helper()
	fixture.Presence.FailStreamAfter(2)
	resp := clientAt(tb, baseURL, caller.ID).get("/channels/" + channelID + "/presence")
	resp.expect(http.StatusBadGateway).expectCode("presence:incomplete")
	var body struct {
		Members []json.RawMessage `json:"members"`
	}
	if len(resp.body) > 0 && resp.body[0] == '{' {
		_ = json.Unmarshal(resp.body, &body)
	}
	if len(body.Members) != 0 {
		tb.Fatalf("a 502 must carry NO partial member list, got %d entries", len(body.Members))
	}
}

func TestS_PR_05_channel_presence_visibility(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	pub := seedChannel(t, ada, "public", false)
	priv := seedChannel(t, ada, "secret", true)
	newClient(t, bob.ID).get("/channels/" + pub + "/presence").
		expect(http.StatusForbidden).expectCode("channel:membership_required")
	newClient(t, bob.ID).get("/channels/" + priv + "/presence").
		expect(http.StatusNotFound).expectCode("channel:not_found")
}
