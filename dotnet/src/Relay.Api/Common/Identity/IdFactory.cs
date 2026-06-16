using IdGen;

namespace Relay.Api.Common.Identity;

/// <summary>
/// Produces opaque, time-ordered, URL-safe string ids (Crockford Base32 over an IdGen
/// 63-bit value). Time-ordering gives newest-first pagination a stable tiebreak; the
/// string stays opaque to clients (spec/02-api.md §0).
/// </summary>
public sealed class IdFactory(IIdGenerator<long> generator)
{
    public string Create() => Base32.Encode(generator.CreateId());
}
