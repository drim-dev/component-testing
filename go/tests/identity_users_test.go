package relaytest

import (
	"net/http"
	"testing"
)

// ---- Identity (S-ID) ----

func TestS_ID_01_missing_user_id_is_401(t *testing.T) {
	reset(t)
	newClient(t, "").get("/channels").expect(http.StatusUnauthorized).expectCode("identity:missing")
}

func TestS_ID_02_unknown_user_id_is_401(t *testing.T) {
	reset(t)
	newClient(t, "nobody-0000000").get("/channels").expect(http.StatusUnauthorized).expectCode("identity:unknown")
}

// ---- Users (S-US) ----

func TestS_US_01_create_user_valid(t *testing.T) {
	reset(t)
	resp := newClient(t, "").post("/users", map[string]string{"handle": "ada_lovelace", "displayName": "Ada"})
	resp.expect(http.StatusCreated)
	var u struct {
		ID, Handle, DisplayName, CreatedAt string
	}
	resp.decode(&u)
	if u.Handle != "ada_lovelace" || u.DisplayName != "Ada" {
		t.Fatalf("echo mismatch: %+v", u)
	}
	if u.ID == "" || u.CreatedAt == "" {
		t.Fatalf("id/createdAt missing: %+v", u)
	}
}

func TestS_US_02_duplicate_handle_is_409(t *testing.T) {
	reset(t)
	newClient(t, "").post("/users", map[string]string{"handle": "dup", "displayName": "One"}).expect(http.StatusCreated)
	newClient(t, "").post("/users", map[string]string{"handle": "dup", "displayName": "Two"}).
		expect(http.StatusConflict).expectCode("user:handle:taken")
}

func TestS_US_03_invalid_handle_is_422(t *testing.T) {
	reset(t)
	for _, bad := range []string{"ab", "UPPER", "has space"} {
		newClient(t, "").post("/users", map[string]string{"handle": bad, "displayName": "X"}).
			expect(http.StatusUnprocessableEntity).expectCode("user:handle:invalid")
	}
}

func TestS_US_04_invalid_display_name_is_422(t *testing.T) {
	reset(t)
	long := make([]byte, 65)
	for i := range long {
		long[i] = 'a'
	}
	newClient(t, "").post("/users", map[string]string{"handle": "emptyname", "displayName": ""}).
		expect(http.StatusUnprocessableEntity).expectCode("user:display_name:invalid")
	newClient(t, "").post("/users", map[string]string{"handle": "longname", "displayName": string(long)}).
		expect(http.StatusUnprocessableEntity).expectCode("user:display_name:invalid")
}

func TestS_US_05_get_existing_user(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	newClient(t, ada.ID).get("/users/" + ada.ID).expect(http.StatusOK)
}

func TestS_US_06_get_unknown_user_is_404(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	newClient(t, ada.ID).get("/users/UNKNOWN0000000").expect(http.StatusNotFound).expectCode("user:not_found")
}
