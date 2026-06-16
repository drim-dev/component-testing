// Package domain holds Relay's entities and the pure predicates the gallery's honesty
// notes call out as the legitimate home of unit tests (participant check, role ordering,
// preview truncation). Whether a route WIRES these in is a system property the component
// tests verify; that the predicates are correct is unit territory.
package domain

import (
	"strings"
	"time"
)

// Role is a channel membership role, ordered Owner > Admin > Member.
type Role int

const (
	RoleMember Role = iota
	RoleAdmin
	RoleOwner
)

// AtLeast reports whether r is at least as privileged as min — the pure ordering predicate
// the G-BOLA-ROLE honesty note unit-tests.
func (r Role) AtLeast(min Role) bool { return r >= min }

func (r Role) String() string {
	switch r {
	case RoleOwner:
		return "owner"
	case RoleAdmin:
		return "admin"
	default:
		return "member"
	}
}

// ParseRole maps a DB/string value to a Role.
func ParseRole(s string) Role {
	switch s {
	case "owner":
		return RoleOwner
	case "admin":
		return RoleAdmin
	default:
		return RoleMember
	}
}

// User is a Relay account; handle is unique.
type User struct {
	ID          string
	Handle      string
	DisplayName string
	CreatedAt   time.Time
}

// Conversation is a 1:1 DM, the pair stored normalized (UserLo < UserHi).
type Conversation struct {
	ID        string
	UserLo    string
	UserHi    string
	CreatedAt time.Time
}

// IsParticipant is the DM access predicate — pure logic, the G-IDOR honesty note's unit
// target. A read path that never calls it is the bug, not this function.
func (c Conversation) IsParticipant(userID string) bool {
	return c.UserLo == userID || c.UserHi == userID
}

// NormalizePair returns the two ids in lexicographic order (lo, hi).
func NormalizePair(a, b string) (lo, hi string) {
	if a < b {
		return a, b
	}
	return b, a
}

// DmMessage is one message in a conversation.
type DmMessage struct {
	ID             string
	ConversationID string
	SenderID       string
	Text           string
	CreatedAt      time.Time
}

// Channel is a community space.
type Channel struct {
	ID        string
	Name      string
	Private   bool
	CreatedAt time.Time
}

// ChannelMember is a (channel, user) membership with a role.
type ChannelMember struct {
	ChannelID string
	UserID    string
	Role      Role
	JoinedAt  time.Time
}

// ChannelMessage is a message in a channel; LinkPreviewTitle is nil unless an unfurl ran.
type ChannelMessage struct {
	ID               string
	ChannelID        string
	SenderID         string
	Text             string
	LinkPreviewTitle *string
	CreatedAt        time.Time
}

// Attachment is the metadata row for a stored file; access derives from channel
// membership, NEVER from possession of StorageKey (G-S3).
type Attachment struct {
	ID         string
	ChannelID  string
	UploaderID string
	MessageID  *string
	Filename   string
	SizeBytes  int64
	StorageKey string
	CreatedAt  time.Time
}

// Notification is a recipient's record of one DM message; DmMessageID is the unique
// idempotency anchor (G-RABBIT).
type Notification struct {
	ID             string
	UserID         string
	DmMessageID    string
	ConversationID string
	SenderID       string
	Preview        string
	CreatedAt      time.Time
}

// FeedEntry is a channel-fanout projection row; (UserID, MessageID) is unique (G-KAFKA).
type FeedEntry struct {
	ID        string
	UserID    string
	ChannelID string
	MessageID string
	SenderID  string
	Preview   string
	CreatedAt time.Time
}

// PreviewMaxLength bounds feed/notification previews.
const PreviewMaxLength = 100

// Preview truncates to the first 100 runes — the pure function the gallery honesty notes
// unit-test; the component tests only assert it is wired into the event/notification paths.
func Preview(text string) string {
	runes := []rune(text)
	if len(runes) <= PreviewMaxLength {
		return text
	}
	return string(runes[:PreviewMaxLength])
}

// FirstURL returns the first http(s):// token in text, or "" — the trigger for link unfurl.
func FirstURL(text string) string {
	for _, field := range strings.Fields(text) {
		if strings.HasPrefix(field, "http://") || strings.HasPrefix(field, "https://") {
			return field
		}
	}
	return ""
}
