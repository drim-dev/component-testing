package relaytest

import (
	"encoding/json"
	"net/http"
	"testing"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/app"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
)

func TestS_CH_01_create_owner_membership(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	resp := newClient(t, ada.ID).post("/channels", map[string]any{"name": "general", "private": false})
	resp.expect(http.StatusCreated)
	var ch struct {
		ID string `json:"id"`
	}
	resp.decode(&ch)
	if n := dbCount(t, "channel_members", "channel_id = $1 AND user_id = $2 AND role = 'owner'", ch.ID, ada.ID); n != 1 {
		t.Fatalf("expected exactly one owner membership, got %d", n)
	}
	if n := dbCount(t, "channel_members", "channel_id = $1", ch.ID); n != 1 {
		t.Fatalf("creator should be the sole member, got %d", n)
	}
}

func TestS_CH_02_name_invalid_is_422(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	newClient(t, ada.ID).post("/channels", map[string]any{"name": "", "private": false}).
		expect(http.StatusUnprocessableEntity).expectCode("channel:name:invalid")
	long := make([]rune, 101)
	for i := range long {
		long[i] = 'a'
	}
	newClient(t, ada.ID).post("/channels", map[string]any{"name": string(long), "private": false}).
		expect(http.StatusUnprocessableEntity).expectCode("channel:name:invalid")
}

func TestS_CH_03_list_visibility(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	pub := seedChannel(t, ada, "public", false)
	adaPriv := seedChannel(t, ada, "ada-private", true)
	seedChannel(t, bob, "bob-private", true)

	var page struct {
		Items []struct {
			ID string `json:"id"`
		} `json:"items"`
	}
	newClient(t, ada.ID).get("/channels").expect(http.StatusOK).decode(&page)
	ids := map[string]bool{}
	for _, it := range page.Items {
		ids[it.ID] = true
	}
	if !ids[pub] || !ids[adaPriv] {
		t.Fatalf("ada should see public + her private channel, got %+v", page.Items)
	}
	for _, it := range page.Items {
		if it.ID != pub && it.ID != adaPriv {
			t.Fatalf("ada saw a channel she should not: %s", it.ID)
		}
	}
}

func TestS_CH_04_public_metadata_for_non_member(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	pub := seedChannel(t, ada, "public", false)
	var dto struct {
		MemberCount int `json:"memberCount"`
	}
	newClient(t, bob.ID).get("/channels/" + pub).expect(http.StatusOK).decode(&dto)
	if dto.MemberCount != 1 {
		t.Fatalf("expected memberCount 1, got %d", dto.MemberCount)
	}
}

// ---- G-BOLA-READ: private channel hidden from non-members ----

func TestS_CH_05_private_metadata_hidden(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	priv := seedChannel(t, ada, "secret", true)
	assertPrivateChannelHidden(t, fixture.BaseURL(), bob, priv)
}

func TestS_CH_05_naive_ignores_private_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	priv := seedChannel(t, ada, "secret", true)
	naive := fixture.NaiveApp(func(d *app.Deps) { d.ChannelReadGate = naiveChannelReadGate{store: fixture.Store} })
	defer naive.Close()
	expectCatchToFail(t, "G-BOLA-READ", func(tb testing.TB) {
		assertPrivateChannelHidden(tb, naive.BaseURL(), bob, priv)
	})
}

func assertPrivateChannelHidden(tb testing.TB, baseURL string, outsider domain.User, channelID string) {
	tb.Helper()
	visible := clientAt(tb, baseURL, outsider.ID).get("/channels/" + channelID)
	visible.expect(http.StatusNotFound).expectCode("channel:not_found")
	unknown := clientAt(tb, baseURL, outsider.ID).get("/channels/UNKNOWN0000000")
	if visible.rawBody() != unknown.rawBody() {
		tb.Fatalf("existence-hiding body differs:\n visible: %s\n unknown: %s", visible.rawBody(), unknown.rawBody())
	}
}

func TestS_CH_06_join_public(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	pub := seedChannel(t, ada, "public", false)
	var m struct {
		Role string `json:"role"`
	}
	newClient(t, bob.ID).post("/channels/"+pub+"/join", nil).expect(http.StatusCreated).decode(&m)
	if m.Role != "member" {
		t.Fatalf("expected role member, got %q", m.Role)
	}
}

func TestS_CH_07_join_when_already_member_is_409(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	pub := seedChannel(t, ada, "public", false)
	newClient(t, ada.ID).post("/channels/"+pub+"/join", nil).
		expect(http.StatusConflict).expectCode("channel:member:already")
}

func TestS_CH_08_join_private_non_member_is_404(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	priv := seedChannel(t, ada, "secret", true)
	newClient(t, bob.ID).post("/channels/"+priv+"/join", nil).
		expect(http.StatusNotFound).expectCode("channel:not_found")
}

func TestS_CH_09_owner_adds_to_private(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	priv := seedChannel(t, ada, "secret", true)
	newClient(t, ada.ID).post("/channels/"+priv+"/members", map[string]string{"userId": bob.ID}).
		expect(http.StatusCreated)
}

func TestS_CH_10_admin_adds_user(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	adm := seedUser(t, "adm")
	cleo := seedUser(t, "cleo")
	ch := seedChannel(t, ada, "team", true)
	seedMember(t, ada, ch, adm)
	newClient(t, ada.ID).post("/channels/"+ch+"/members/"+adm.ID+"/promote", nil).expect(http.StatusOK)
	newClient(t, adm.ID).post("/channels/"+ch+"/members", map[string]string{"userId": cleo.ID}).
		expect(http.StatusCreated)
}

// ---- G-BOLA-ROLE: plain member cannot perform admin actions ----

func TestS_CH_11_member_add_is_403(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	cleo := seedUser(t, "cleo")
	ch := seedChannel(t, ada, "team", false)
	seedJoin(t, bob, ch)
	assertMemberAddForbidden(t, fixture.BaseURL(), ch, bob, cleo)
}

func TestS_CH_11_naive_skips_role_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	cleo := seedUser(t, "cleo")
	ch := seedChannel(t, ada, "team", false)
	seedJoin(t, bob, ch)
	naive := fixture.NaiveApp(func(d *app.Deps) { d.ChannelRoleGate = naiveChannelRoleGate{store: fixture.Store} })
	defer naive.Close()
	expectCatchToFail(t, "G-BOLA-ROLE", func(tb testing.TB) {
		assertMemberAddForbidden(tb, naive.BaseURL(), ch, bob, cleo)
	})
}

func assertMemberAddForbidden(tb testing.TB, baseURL, channelID string, member, target domain.User) {
	tb.Helper()
	clientAt(tb, baseURL, member.ID).post("/channels/"+channelID+"/members", map[string]string{"userId": target.ID}).
		expect(http.StatusForbidden).expectCode("channel:role:forbidden")
	if n := dbCountTB(tb, "channel_members", "channel_id = $1 AND user_id = $2", channelID, target.ID); n != 0 {
		tb.Fatalf("expected no membership written by forbidden add, got %d", n)
	}
}

func TestS_CH_12_add_existing_member_is_409(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	ch := seedChannel(t, ada, "team", true)
	seedMember(t, ada, ch, bob)
	newClient(t, ada.ID).post("/channels/"+ch+"/members", map[string]string{"userId": bob.ID}).
		expect(http.StatusConflict).expectCode("channel:member:already")
}

func TestS_CH_13_promote_owner_only(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	adm := seedUser(t, "adm")
	mem := seedUser(t, "mem")
	ch := seedChannel(t, ada, "team", true)
	seedMember(t, ada, ch, adm)
	seedMember(t, ada, ch, mem)
	var m struct {
		Role string `json:"role"`
	}
	newClient(t, ada.ID).post("/channels/"+ch+"/members/"+adm.ID+"/promote", nil).expect(http.StatusOK).decode(&m)
	if m.Role != "admin" {
		t.Fatalf("expected admin, got %q", m.Role)
	}
	newClient(t, adm.ID).post("/channels/"+ch+"/members/"+mem.ID+"/promote", nil).
		expect(http.StatusForbidden).expectCode("channel:role:forbidden")
}

func TestS_CH_14_admin_kicks_member(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	adm := seedUser(t, "adm")
	mem := seedUser(t, "mem")
	ch := seedChannel(t, ada, "team", true)
	seedMember(t, ada, ch, adm)
	seedMember(t, ada, ch, mem)
	newClient(t, ada.ID).post("/channels/"+ch+"/members/"+adm.ID+"/promote", nil).expect(http.StatusOK)
	newClient(t, adm.ID).del("/channels/" + ch + "/members/" + mem.ID).expect(http.StatusNoContent)
	if n := dbCount(t, "channel_members", "channel_id = $1 AND user_id = $2", ch, mem.ID); n != 0 {
		t.Fatalf("expected membership gone, got %d", n)
	}
}

func TestS_CH_15_member_kick_is_403(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	cleo := seedUser(t, "cleo")
	ch := seedChannel(t, ada, "team", false)
	seedJoin(t, bob, ch)
	seedJoin(t, cleo, ch)
	assertMemberKickForbidden(t, fixture.BaseURL(), ch, bob, cleo)
}

func TestS_CH_15_naive_member_kick_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	cleo := seedUser(t, "cleo")
	ch := seedChannel(t, ada, "team", false)
	seedJoin(t, bob, ch)
	seedJoin(t, cleo, ch)
	naive := fixture.NaiveApp(func(d *app.Deps) { d.ChannelRoleGate = naiveChannelRoleGate{store: fixture.Store} })
	defer naive.Close()
	expectCatchToFail(t, "G-BOLA-ROLE", func(tb testing.TB) {
		assertMemberKickForbidden(tb, naive.BaseURL(), ch, bob, cleo)
	})
}

func assertMemberKickForbidden(tb testing.TB, baseURL, channelID string, member, target domain.User) {
	tb.Helper()
	clientAt(tb, baseURL, member.ID).del("/channels/" + channelID + "/members/" + target.ID).
		expect(http.StatusForbidden).expectCode("channel:role:forbidden")
	if n := dbCountTB(tb, "channel_members", "channel_id = $1 AND user_id = $2", channelID, target.ID); n != 1 {
		tb.Fatalf("expected membership intact after forbidden kick, got %d", n)
	}
}

func TestS_CH_17_leave_rules(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	ch := seedChannel(t, ada, "team", false)
	seedJoin(t, bob, ch)
	newClient(t, bob.ID).del("/channels/" + ch + "/members/" + bob.ID).expect(http.StatusNoContent)
	newClient(t, ada.ID).del("/channels/" + ch + "/members/" + ada.ID).
		expect(http.StatusConflict).expectCode("channel:owner:cannot_leave")
}

func TestS_CH_18_owner_kicks_admin(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	adm := seedUser(t, "adm")
	adm2 := seedUser(t, "adm2")
	ch := seedChannel(t, ada, "team", true)
	seedMember(t, ada, ch, adm)
	seedMember(t, ada, ch, adm2)
	newClient(t, ada.ID).post("/channels/"+ch+"/members/"+adm.ID+"/promote", nil).expect(http.StatusOK)
	newClient(t, ada.ID).post("/channels/"+ch+"/members/"+adm2.ID+"/promote", nil).expect(http.StatusOK)
	newClient(t, ada.ID).del("/channels/" + ch + "/members/" + adm.ID).expect(http.StatusNoContent)
	newClient(t, adm2.ID).del("/channels/" + ch + "/members/" + ada.ID).
		expect(http.StatusForbidden).expectCode("channel:role:forbidden")
}

// ---- G-BOLA-ROLE: only the owner may delete ----

func TestS_CH_19_delete_owner_only(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	adm := seedUser(t, "adm")
	mem := seedUser(t, "mem")
	ch := seedChannel(t, ada, "team", true)
	seedMember(t, ada, ch, adm)
	seedMember(t, ada, ch, mem)
	newClient(t, ada.ID).post("/channels/"+ch+"/members/"+adm.ID+"/promote", nil).expect(http.StatusOK)
	assertDeleteForbidden(t, fixture.BaseURL(), ch, adm, mem)
}

func TestS_CH_19_naive_delete_skips_role_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	adm := seedUser(t, "adm")
	mem := seedUser(t, "mem")
	ch := seedChannel(t, ada, "team", true)
	seedMember(t, ada, ch, adm)
	seedMember(t, ada, ch, mem)
	newClient(t, ada.ID).post("/channels/"+ch+"/members/"+adm.ID+"/promote", nil).expect(http.StatusOK)
	naive := fixture.NaiveApp(func(d *app.Deps) { d.ChannelRoleGate = naiveChannelRoleGate{store: fixture.Store} })
	defer naive.Close()
	expectCatchToFail(t, "G-BOLA-ROLE", func(tb testing.TB) {
		assertDeleteForbidden(tb, naive.BaseURL(), ch, adm, mem)
	})
}

func assertDeleteForbidden(tb testing.TB, baseURL, channelID string, admin, member domain.User) {
	tb.Helper()
	clientAt(tb, baseURL, admin.ID).del("/channels/" + channelID).
		expect(http.StatusForbidden).expectCode("channel:role:forbidden")
	clientAt(tb, baseURL, member.ID).del("/channels/" + channelID).
		expect(http.StatusForbidden).expectCode("channel:role:forbidden")
	if n := dbCountTB(tb, "channels", "id = $1", channelID); n != 1 {
		tb.Fatalf("channel should be intact after forbidden deletes, got %d", n)
	}
}

func TestS_CH_20_owner_deletes_cascades(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	ch := seedChannel(t, ada, "team", false)
	seedJoin(t, bob, ch)
	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "hello"}).expect(http.StatusCreated)

	newClient(t, ada.ID).del("/channels/" + ch).expect(http.StatusNoContent)
	if n := dbCount(t, "channel_members", "channel_id = $1", ch); n != 0 {
		t.Fatalf("memberships should be gone, got %d", n)
	}
	if n := dbCount(t, "channel_messages", "channel_id = $1", ch); n != 0 {
		t.Fatalf("messages should be gone, got %d", n)
	}
	newClient(t, ada.ID).get("/channels/" + ch).expect(http.StatusNotFound)
}

// ---- G-BOLA-READ: private messages hidden ----

func TestS_CH_21_private_messages_hidden(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	priv := seedChannel(t, ada, "secret", true)
	newClient(t, ada.ID).post("/channels/"+priv+"/messages", map[string]any{"text": "classified"}).expect(http.StatusCreated)
	assertPrivateMessagesHidden(t, fixture.BaseURL(), bob, priv)
}

func TestS_CH_21_naive_private_messages_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	priv := seedChannel(t, ada, "secret", true)
	newClient(t, ada.ID).post("/channels/"+priv+"/messages", map[string]any{"text": "classified"}).expect(http.StatusCreated)
	naive := fixture.NaiveApp(func(d *app.Deps) { d.ChannelReadGate = naiveChannelReadGate{store: fixture.Store} })
	defer naive.Close()
	expectCatchToFail(t, "G-BOLA-READ", func(tb testing.TB) {
		assertPrivateMessagesHidden(tb, naive.BaseURL(), bob, priv)
	})
}

func assertPrivateMessagesHidden(tb testing.TB, baseURL string, outsider domain.User, channelID string) {
	tb.Helper()
	resp := clientAt(tb, baseURL, outsider.ID).get("/channels/" + channelID + "/messages")
	resp.expect(http.StatusNotFound).expectCode("channel:not_found")
	var page struct {
		Items []json.RawMessage `json:"items"`
	}
	if len(resp.body) > 0 && resp.body[0] == '{' {
		_ = json.Unmarshal(resp.body, &page)
	}
	if len(page.Items) != 0 {
		tb.Fatalf("private messages leaked to a non-member: %d items", len(page.Items))
	}
}

func TestS_CH_22_public_messages_require_membership(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	pub := seedChannel(t, ada, "public", false)
	newClient(t, bob.ID).get("/channels/" + pub + "/messages").
		expect(http.StatusForbidden).expectCode("channel:membership_required")
}

func TestS_CH_23_post_visibility(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	pub := seedChannel(t, ada, "public", false)
	priv := seedChannel(t, ada, "secret", true)
	newClient(t, ada.ID).post("/channels/"+pub+"/messages", map[string]any{"text": "hi"}).expect(http.StatusCreated)
	newClient(t, bob.ID).post("/channels/"+pub+"/messages", map[string]any{"text": "intrude"}).
		expect(http.StatusForbidden).expectCode("channel:membership_required")
	newClient(t, bob.ID).post("/channels/"+priv+"/messages", map[string]any{"text": "intrude"}).
		expect(http.StatusNotFound).expectCode("channel:not_found")
	if n := dbCount(t, "channel_messages", "sender_id = $1", bob.ID); n != 0 {
		t.Fatalf("expected no message written by non-member, got %d", n)
	}
}

func TestS_CH_24_post_validation(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	ch := seedChannel(t, ada, "team", false)
	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": ""}).
		expect(http.StatusUnprocessableEntity).expectCode("message:text:invalid")
	long := make([]byte, 4001)
	for i := range long {
		long[i] = 'a'
	}
	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": string(long)}).
		expect(http.StatusUnprocessableEntity).expectCode("message:text:invalid")
	ids := make([]string, 11)
	for i := range ids {
		ids[i] = "att0000000000"
	}
	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "hi", "attachmentIds": ids}).
		expect(http.StatusUnprocessableEntity).expectCode("message:attachment:invalid")
}

// seedJoin makes target join a public channel as a plain member (self-service join).
func seedJoin(t *testing.T, target domain.User, channelID string) {
	t.Helper()
	newClient(t, target.ID).post("/channels/"+channelID+"/join", nil).expect(http.StatusCreated)
}
