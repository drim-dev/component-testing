package relaytest

import (
	"bytes"
	"mime/multipart"
	"net/http"
	"testing"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/app"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
)

func TestS_AT_01_upload_stores_bytes(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	ch := seedChannel(t, ada, "team", false)
	content := bytes.Repeat([]byte("x"), 10*1024)
	id := uploadAttachment(t, ada, ch, "notes.txt", content).expect(http.StatusCreated).attachmentID(t)

	ctx, cancel := bgCtx()
	defer cancel()
	key := ch + "/" + id
	got, err := fixture.S3.ObjectBytes(ctx, key)
	if err != nil {
		t.Fatalf("object bytes: %v", err)
	}
	if !bytes.Equal(got, content) {
		t.Fatalf("stored bytes differ from upload (%d vs %d)", len(got), len(content))
	}
	if n := dbCount(t, "attachments", "id = $1 AND channel_id = $2 AND uploader_id = $3", id, ch, ada.ID); n != 1 {
		t.Fatalf("expected one attachment metadata row, got %d", n)
	}
}

func TestS_AT_02_upload_visibility(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	pub := seedChannel(t, ada, "public", false)
	priv := seedChannel(t, ada, "secret", true)
	uploadAttachment(t, bob, pub, "f.txt", []byte("hi")).
		expect(http.StatusForbidden).expectCode("channel:membership_required")
	uploadAttachment(t, bob, priv, "f.txt", []byte("hi")).
		expect(http.StatusNotFound).expectCode("channel:not_found")
}

func TestS_AT_03_upload_size_limits(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	ch := seedChannel(t, ada, "team", false)
	tooBig := bytes.Repeat([]byte("x"), (1<<20)+1)
	uploadAttachment(t, ada, ch, "big.bin", tooBig).
		expect(http.StatusRequestEntityTooLarge).expectCode("attachment:too_large")
	uploadAttachment(t, ada, ch, "empty.txt", []byte{}).
		expect(http.StatusUnprocessableEntity).expectCode("attachment:empty")
}

func TestS_AT_04_message_attachment_reference(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	ch := seedChannel(t, ada, "team", false)
	other := seedChannel(t, ada, "other", false)
	seedJoin(t, bob, ch)

	mine := uploadAttachment(t, ada, ch, "mine.txt", []byte("mine")).expect(http.StatusCreated).attachmentID(t)
	bobs := uploadAttachment(t, bob, ch, "bobs.txt", []byte("bobs")).expect(http.StatusCreated).attachmentID(t)
	elsewhere := uploadAttachment(t, ada, other, "x.txt", []byte("x")).expect(http.StatusCreated).attachmentID(t)

	var msg struct {
		ID string `json:"id"`
	}
	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "see attached", "attachmentIds": []string{mine}}).
		expect(http.StatusCreated).decode(&msg)
	if n := dbCount(t, "attachments", "id = $1 AND message_id = $2", mine, msg.ID); n != 1 {
		t.Fatalf("attachment should be bound to the message, got %d", n)
	}
	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "x", "attachmentIds": []string{bobs}}).
		expect(http.StatusUnprocessableEntity).expectCode("message:attachment:invalid")
	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "x", "attachmentIds": []string{elsewhere}}).
		expect(http.StatusUnprocessableEntity).expectCode("message:attachment:invalid")
}

func TestS_AT_05_download_returns_bytes(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	ch := seedChannel(t, ada, "team", false)
	content := []byte("hello attachment")
	id := uploadAttachment(t, ada, ch, "greeting.txt", content).expect(http.StatusCreated).attachmentID(t)

	resp := newClient(t, ada.ID).get("/attachments/" + id)
	resp.expect(http.StatusOK)
	if !bytes.Equal(resp.body, content) {
		t.Fatalf("downloaded bytes differ from upload")
	}
	if cd := resp.header.Get("Content-Disposition"); cd == "" || !bytes.Contains([]byte(cd), []byte("greeting.txt")) {
		t.Fatalf("expected filename in Content-Disposition, got %q", cd)
	}
}

// ---- G-S3: download authorization is channel membership, never id possession ----

func TestS_AT_06_private_download_hidden(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	priv := seedChannel(t, ada, "secret", true)
	id := uploadAttachment(t, ada, priv, "secret.bin", []byte("classified")).expect(http.StatusCreated).attachmentID(t)
	assertPrivateAttachmentHidden(t, fixture.BaseURL(), bob, id)
}

func TestS_AT_06_naive_id_possession_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	priv := seedChannel(t, ada, "secret", true)
	id := uploadAttachment(t, ada, priv, "secret.bin", []byte("classified")).expect(http.StatusCreated).attachmentID(t)
	naive := fixture.NaiveApp(func(d *app.Deps) { d.AttachmentAccess = naiveAttachmentAccess{store: fixture.Store} })
	defer naive.Close()
	expectCatchToFail(t, "G-S3", func(tb testing.TB) {
		assertPrivateAttachmentHidden(tb, naive.BaseURL(), bob, id)
	})
}

func assertPrivateAttachmentHidden(tb testing.TB, baseURL string, outsider domain.User, attachmentID string) {
	tb.Helper()
	visible := clientAt(tb, baseURL, outsider.ID).get("/attachments/" + attachmentID)
	visible.expect(http.StatusNotFound).expectCode("attachment:not_found")
	if bytes.Contains(visible.body, []byte("classified")) {
		tb.Fatalf("private attachment bytes leaked to a non-member")
	}
	unknown := clientAt(tb, baseURL, outsider.ID).get("/attachments/UNKNOWN0000000")
	if visible.rawBody() != unknown.rawBody() {
		tb.Fatalf("existence-hiding body differs:\n visible: %s\n unknown: %s", visible.rawBody(), unknown.rawBody())
	}
}

func TestS_AT_07_public_download_requires_membership(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	pub := seedChannel(t, ada, "public", false)
	id := uploadAttachment(t, ada, pub, "shared.bin", []byte("shared")).expect(http.StatusCreated).attachmentID(t)
	assertPublicAttachmentForbidden(t, fixture.BaseURL(), bob, id)
}

func TestS_AT_07_naive_public_id_possession_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	pub := seedChannel(t, ada, "public", false)
	id := uploadAttachment(t, ada, pub, "shared.bin", []byte("shared")).expect(http.StatusCreated).attachmentID(t)
	naive := fixture.NaiveApp(func(d *app.Deps) { d.AttachmentAccess = naiveAttachmentAccess{store: fixture.Store} })
	defer naive.Close()
	expectCatchToFail(t, "G-S3", func(tb testing.TB) {
		assertPublicAttachmentForbidden(tb, naive.BaseURL(), bob, id)
	})
}

func assertPublicAttachmentForbidden(tb testing.TB, baseURL string, outsider domain.User, attachmentID string) {
	tb.Helper()
	resp := clientAt(tb, baseURL, outsider.ID).get("/attachments/" + attachmentID)
	resp.expect(http.StatusForbidden).expectCode("channel:membership_required")
	if bytes.Contains(resp.body, []byte("shared")) {
		tb.Fatalf("public attachment bytes leaked to a non-member")
	}
}

// uploadAttachment posts a multipart file as the given user and returns the response.
func uploadAttachment(t *testing.T, by domain.User, channelID, filename string, content []byte) *response {
	t.Helper()
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, err := mw.CreateFormFile("file", filename)
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	if _, err := fw.Write(content); err != nil {
		t.Fatalf("write form file: %v", err)
	}
	if err := mw.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}
	req, err := http.NewRequest(http.MethodPost, fixture.BaseURL()+"/channels/"+channelID+"/attachments", &buf)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	return newClient(t, by.ID).doRaw(req)
}

func (r *response) attachmentID(t *testing.T) string {
	t.Helper()
	var a struct {
		ID string `json:"id"`
	}
	r.decode(&a)
	return a.ID
}
