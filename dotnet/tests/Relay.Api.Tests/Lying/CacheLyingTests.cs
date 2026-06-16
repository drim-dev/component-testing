using FluentAssertions;
using Relay.Api.Domain.Channels;
using Relay.Api.Features.Channels;

namespace Relay.Api.Tests.Lying;

/// <summary>
/// LYING TESTS (exhibits, do not copy) — see <see cref="MessagesLyingTests"/> for the
/// framing. These are the Redis-flavored mirror: an in-memory "cache" the test itself
/// keeps consistent with the store can never go stale, so the divergence bug —
/// the whole point of G-CACHE — is unrepresentable in its universe.
/// </summary>
public sealed class CacheLyingTests
{
    // LYING TEST (exhibit, do not copy) — gallery case G-CACHE;
    // caught by ChannelCacheTests.S_CH_16_kick_invalidates_membership_cache_immediately
    [Fact]
    public async Task CacheLyingTest_in_memory_cache_mirrors_every_write()
    {
        var membership = new InMemoryMembership(); // one dictionary plays BOTH PostgreSQL and Redis
        IMembershipWriter writer = membership;
        var bob = new ChannelMember { ChannelId = "ch1", UserId = "bob", Role = ChannelRole.Member };

        await writer.Add(bob, CancellationToken.None);
        membership.CachedMembers("ch1").Should().Contain("bob");

        await writer.Remove(bob, CancellationToken.None);

        // Green — and meaningless: the "cache" IS the store, so the kick can never leave
        // it stale. Two real stores can diverge; one dictionary cannot.
        membership.CachedMembers("ch1").Should().NotContain("bob");
    }

    private sealed class InMemoryMembership : IMembershipWriter
    {
        private readonly Dictionary<string, HashSet<string>> _members = [];

        public Task Add(ChannelMember membership, CancellationToken ct)
        {
            if (!_members.TryGetValue(membership.ChannelId, out var channel))
            {
                channel = [];
                _members[membership.ChannelId] = channel;
            }

            channel.Add(membership.UserId);
            return Task.CompletedTask;
        }

        public Task Remove(ChannelMember membership, CancellationToken ct)
        {
            _members[membership.ChannelId].Remove(membership.UserId);
            return Task.CompletedTask;
        }

        public HashSet<string> CachedMembers(string channelId) =>
            _members.TryGetValue(channelId, out var channel) ? channel : [];
    }
}
