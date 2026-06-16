package relaytest

import (
	"net/http"
	"testing"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/app"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
)

// ---- G-CACHE: removing a member must invalidate the Redis membership cache ----
//
// The membership cache (members:{channelId}) is the authorization fast-path. The correct
// MembershipWriter.Remove deletes the member AND invalidates the cache, so the removed user's
// next read is denied immediately. The naive variant writes Postgres and forgets the
// invalidation, leaving the stale cache to grant access until its TTL.

func TestS_CH_16_kick_invalidates_membership_cache(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	ch := seedChannel(t, ada, "team", true)
	seedMember(t, ada, ch, bob)
	assertKickInvalidatesCache(t, fixture.BaseURL(), ch, ada, bob)
}

func TestS_CH_16_naive_no_invalidation_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	ch := seedChannel(t, ada, "team", true)
	seedMember(t, ada, ch, bob)
	naive := fixture.NaiveApp(func(d *app.Deps) { d.MembershipWriter = naiveMembershipWriter{store: fixture.Store} })
	defer naive.Close()
	expectCatchToFail(t, "G-CACHE", func(tb testing.TB) {
		assertKickInvalidatesCache(tb, naive.BaseURL(), ch, ada, bob)
	})
}

func assertKickInvalidatesCache(tb testing.TB, baseURL, channelID string, owner, member domain.User) {
	tb.Helper()
	ctx, cancel := bgCtx()
	defer cancel()
	// Warm the membership cache to the state a prior read would have left (the harness seeds
	// the same key the app's cache uses — 04-dependencies.md / RedisHarness.SeedMembershipCache).
	if err := fixture.Redis.SeedMembershipCache(ctx, channelID, owner.ID, member.ID); err != nil {
		tb.Fatalf("seed cache: %v", err)
	}
	clientAt(tb, baseURL, owner.ID).del("/channels/" + channelID + "/members/" + member.ID).expect(http.StatusNoContent)

	exists, hasMember, err := fixture.Redis.CacheHasMember(ctx, channelID, member.ID)
	if err != nil {
		tb.Fatalf("cache read: %v", err)
	}
	if exists && hasMember {
		tb.Fatalf("membership cache still lists the kicked member: the removal did not invalidate it")
	}
}
