package store

import (
	"context"
	"errors"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/jackc/pgx/v5"
)

// ---- Users ----

func (s *Store) InsertUser(ctx context.Context, u domain.User) error {
	_, err := s.pool.Exec(ctx,
		`INSERT INTO users (id, handle, display_name, created_at) VALUES ($1, $2, $3, $4)`,
		u.ID, u.Handle, u.DisplayName, u.CreatedAt)
	return err
}

func (s *Store) UserByID(ctx context.Context, id string) (*domain.User, error) {
	return scanUser(s.pool.QueryRow(ctx,
		`SELECT id, handle, display_name, created_at FROM users WHERE id = $1`, id))
}

func (s *Store) UserByHandle(ctx context.Context, handle string) (*domain.User, error) {
	return scanUser(s.pool.QueryRow(ctx,
		`SELECT id, handle, display_name, created_at FROM users WHERE handle = $1`, handle))
}

func scanUser(row pgx.Row) (*domain.User, error) {
	var u domain.User
	if err := row.Scan(&u.ID, &u.Handle, &u.DisplayName, &u.CreatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	return &u, nil
}

// ---- Conversations & DM messages ----

func (s *Store) ConversationByID(ctx context.Context, id string) (*domain.Conversation, error) {
	return scanConversation(s.pool.QueryRow(ctx,
		`SELECT id, user_lo, user_hi, created_at FROM dm_conversations WHERE id = $1`, id))
}

func (s *Store) ConversationByPair(ctx context.Context, lo, hi string) (*domain.Conversation, error) {
	return scanConversation(s.pool.QueryRow(ctx,
		`SELECT id, user_lo, user_hi, created_at FROM dm_conversations WHERE user_lo = $1 AND user_hi = $2`, lo, hi))
}

func scanConversation(row pgx.Row) (*domain.Conversation, error) {
	var c domain.Conversation
	if err := row.Scan(&c.ID, &c.UserLo, &c.UserHi, &c.CreatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	return &c, nil
}

// ConversationsFor lists the caller's conversations newest-first, keyset-paginated.
func (s *Store) ConversationsFor(ctx context.Context, userID string, before string, limit int) ([]domain.Conversation, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT c.id, c.user_lo, c.user_hi, c.created_at
		FROM dm_conversations c
		JOIN dm_participants p ON p.conversation_id = c.id AND p.user_id = $1
		WHERE ($2 = '' OR c.id < $2)
		ORDER BY c.id DESC
		LIMIT $3`, userID, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.Conversation
	for rows.Next() {
		var c domain.Conversation
		if err := rows.Scan(&c.ID, &c.UserLo, &c.UserHi, &c.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func (s *Store) ConversationExists(ctx context.Context, id string) (bool, error) {
	var exists bool
	err := s.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM dm_conversations WHERE id = $1)`, id).Scan(&exists)
	return exists, err
}

func (s *Store) InsertDmMessage(ctx context.Context, m domain.DmMessage) error {
	_, err := s.pool.Exec(ctx,
		`INSERT INTO dm_messages (id, conversation_id, sender_id, text, created_at) VALUES ($1, $2, $3, $4, $5)`,
		m.ID, m.ConversationID, m.SenderID, m.Text, m.CreatedAt)
	return err
}

func (s *Store) DmMessages(ctx context.Context, conversationID, before string, limit int) ([]domain.DmMessage, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id, conversation_id, sender_id, text, created_at
		FROM dm_messages
		WHERE conversation_id = $1 AND ($2 = '' OR id < $2)
		ORDER BY id DESC
		LIMIT $3`, conversationID, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.DmMessage
	for rows.Next() {
		var m domain.DmMessage
		if err := rows.Scan(&m.ID, &m.ConversationID, &m.SenderID, &m.Text, &m.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (s *Store) DmMessageExists(ctx context.Context, conversationID, id string) (bool, error) {
	var exists bool
	err := s.pool.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM dm_messages WHERE conversation_id = $1 AND id = $2)`,
		conversationID, id).Scan(&exists)
	return exists, err
}

// ---- Channels & members ----

func (s *Store) InsertChannelWithOwner(ctx context.Context, c domain.Channel, owner domain.ChannelMember) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx,
		`INSERT INTO channels (id, name, private, created_at) VALUES ($1, $2, $3, $4)`,
		c.ID, c.Name, c.Private, c.CreatedAt); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO channel_members (channel_id, user_id, role, joined_at) VALUES ($1, $2, $3, $4)`,
		owner.ChannelID, owner.UserID, owner.Role.String(), owner.JoinedAt); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (s *Store) ChannelByID(ctx context.Context, id string) (*domain.Channel, error) {
	var c domain.Channel
	err := s.pool.QueryRow(ctx,
		`SELECT id, name, private, created_at FROM channels WHERE id = $1`, id).
		Scan(&c.ID, &c.Name, &c.Private, &c.CreatedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	return &c, nil
}

// Membership returns the caller's membership of a channel, or nil if not a member.
func (s *Store) Membership(ctx context.Context, channelID, userID string) (*domain.ChannelMember, error) {
	var m domain.ChannelMember
	var role string
	err := s.pool.QueryRow(ctx,
		`SELECT channel_id, user_id, role, joined_at FROM channel_members WHERE channel_id = $1 AND user_id = $2`,
		channelID, userID).Scan(&m.ChannelID, &m.UserID, &role, &m.JoinedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	m.Role = domain.ParseRole(role)
	return &m, nil
}

func (s *Store) MemberCount(ctx context.Context, channelID string) (int, error) {
	var n int
	err := s.pool.QueryRow(ctx, `SELECT COUNT(*) FROM channel_members WHERE channel_id = $1`, channelID).Scan(&n)
	return n, err
}

// MemberIDsExcept returns member ids of a channel except one (the sender) — for fan-out.
func (s *Store) MemberIDsExcept(ctx context.Context, channelID, except string) ([]string, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT user_id FROM channel_members WHERE channel_id = $1 AND user_id <> $2`, channelID, except)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

func (s *Store) InsertMember(ctx context.Context, m domain.ChannelMember) error {
	_, err := s.pool.Exec(ctx,
		`INSERT INTO channel_members (channel_id, user_id, role, joined_at) VALUES ($1, $2, $3, $4)`,
		m.ChannelID, m.UserID, m.Role.String(), m.JoinedAt)
	return err
}

func (s *Store) UpdateMemberRole(ctx context.Context, channelID, userID string, role domain.Role) error {
	_, err := s.pool.Exec(ctx,
		`UPDATE channel_members SET role = $3 WHERE channel_id = $1 AND user_id = $2`,
		channelID, userID, role.String())
	return err
}

func (s *Store) DeleteMember(ctx context.Context, channelID, userID string) error {
	_, err := s.pool.Exec(ctx,
		`DELETE FROM channel_members WHERE channel_id = $1 AND user_id = $2`, channelID, userID)
	return err
}

func (s *Store) DeleteChannel(ctx context.Context, channelID string) error {
	_, err := s.pool.Exec(ctx, `DELETE FROM channels WHERE id = $1`, channelID)
	return err
}

// VisibleChannels lists public channels + the caller's channels, newest-first, paginated.
func (s *Store) VisibleChannels(ctx context.Context, userID, before string, limit int) ([]domain.Channel, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT DISTINCT c.id, c.name, c.private, c.created_at
		FROM channels c
		LEFT JOIN channel_members m ON m.channel_id = c.id AND m.user_id = $1
		WHERE (c.private = false OR m.user_id IS NOT NULL) AND ($2 = '' OR c.id < $2)
		ORDER BY c.id DESC
		LIMIT $3`, userID, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.Channel
	for rows.Next() {
		var c domain.Channel
		if err := rows.Scan(&c.ID, &c.Name, &c.Private, &c.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func (s *Store) ChannelExists(ctx context.Context, id string) (bool, error) {
	var exists bool
	err := s.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM channels WHERE id = $1)`, id).Scan(&exists)
	return exists, err
}

// ---- Channel messages ----

func (s *Store) InsertChannelMessage(ctx context.Context, q Querier, m domain.ChannelMessage) error {
	_, err := q.Exec(ctx,
		`INSERT INTO channel_messages (id, channel_id, sender_id, text, link_preview_title, created_at)
		 VALUES ($1, $2, $3, $4, $5, $6)`,
		m.ID, m.ChannelID, m.SenderID, m.Text, m.LinkPreviewTitle, m.CreatedAt)
	return err
}

func (s *Store) ChannelMessages(ctx context.Context, channelID, before string, limit int) ([]domain.ChannelMessage, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id, channel_id, sender_id, text, link_preview_title, created_at
		FROM channel_messages
		WHERE channel_id = $1 AND ($2 = '' OR id < $2)
		ORDER BY id DESC
		LIMIT $3`, channelID, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.ChannelMessage
	for rows.Next() {
		var m domain.ChannelMessage
		if err := rows.Scan(&m.ID, &m.ChannelID, &m.SenderID, &m.Text, &m.LinkPreviewTitle, &m.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (s *Store) ChannelMessageExists(ctx context.Context, channelID, id string) (bool, error) {
	var exists bool
	err := s.pool.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM channel_messages WHERE channel_id = $1 AND id = $2)`, channelID, id).Scan(&exists)
	return exists, err
}

func (s *Store) AttachMessageToAttachments(ctx context.Context, q Querier, messageID string, attachmentIDs []string) error {
	if len(attachmentIDs) == 0 {
		return nil
	}
	_, err := q.Exec(ctx,
		`UPDATE attachments SET message_id = $1 WHERE id = ANY($2)`, messageID, attachmentIDs)
	return err
}

// ---- Attachments ----

func (s *Store) InsertAttachment(ctx context.Context, a domain.Attachment) error {
	_, err := s.pool.Exec(ctx,
		`INSERT INTO attachments (id, channel_id, uploader_id, message_id, filename, size_bytes, storage_key, created_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
		a.ID, a.ChannelID, a.UploaderID, a.MessageID, a.Filename, a.SizeBytes, a.StorageKey, a.CreatedAt)
	return err
}

func (s *Store) AttachmentByID(ctx context.Context, id string) (*domain.Attachment, error) {
	var a domain.Attachment
	err := s.pool.QueryRow(ctx,
		`SELECT id, channel_id, uploader_id, message_id, filename, size_bytes, storage_key, created_at
		 FROM attachments WHERE id = $1`, id).
		Scan(&a.ID, &a.ChannelID, &a.UploaderID, &a.MessageID, &a.Filename, &a.SizeBytes, &a.StorageKey, &a.CreatedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	return &a, nil
}

// AttachmentsOwnedInChannel returns ids among attachmentIDs that the uploader owns in this
// channel and that are not yet bound to a message — for message-create attachment validation.
func (s *Store) AttachmentsOwnedInChannel(ctx context.Context, channelID, uploaderID string, ids []string) ([]string, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT id FROM attachments WHERE channel_id = $1 AND uploader_id = $2 AND id = ANY($3)`,
		channelID, uploaderID, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// ---- Notifications ----

func (s *Store) InsertNotification(ctx context.Context, n domain.Notification) error {
	_, err := s.pool.Exec(ctx,
		`INSERT INTO notifications (id, user_id, dm_message_id, conversation_id, sender_id, preview, created_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		n.ID, n.UserID, n.DmMessageID, n.ConversationID, n.SenderID, n.Preview, n.CreatedAt)
	return err
}

func (s *Store) NotificationsFor(ctx context.Context, userID, before string, limit int) ([]domain.Notification, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id, user_id, dm_message_id, conversation_id, sender_id, preview, created_at
		FROM notifications
		WHERE user_id = $1 AND ($2 = '' OR id < $2)
		ORDER BY id DESC
		LIMIT $3`, userID, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.Notification
	for rows.Next() {
		var n domain.Notification
		if err := rows.Scan(&n.ID, &n.UserID, &n.DmMessageID, &n.ConversationID, &n.SenderID, &n.Preview, &n.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}

// ---- Feed ----

func (s *Store) InsertFeedEntry(ctx context.Context, f domain.FeedEntry) error {
	_, err := s.pool.Exec(ctx,
		`INSERT INTO feed_entries (id, user_id, channel_id, message_id, sender_id, preview, created_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		f.ID, f.UserID, f.ChannelID, f.MessageID, f.SenderID, f.Preview, f.CreatedAt)
	return err
}

func (s *Store) FeedFor(ctx context.Context, userID, before string, limit int) ([]domain.FeedEntry, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id, user_id, channel_id, message_id, sender_id, preview, created_at
		FROM feed_entries
		WHERE user_id = $1 AND ($2 = '' OR id < $2)
		ORDER BY id DESC
		LIMIT $3`, userID, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []domain.FeedEntry
	for rows.Next() {
		var f domain.FeedEntry
		if err := rows.Scan(&f.ID, &f.UserID, &f.ChannelID, &f.MessageID, &f.SenderID, &f.Preview, &f.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}
