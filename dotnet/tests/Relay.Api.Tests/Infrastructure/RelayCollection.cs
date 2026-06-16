namespace Relay.Api.Tests.Infrastructure;

/// <summary>
/// One collection for the whole Relay suite: a single shared <see cref="TestFixture"/>
/// (one Docker host, one suite — spec/04-dependencies.md §9). Tests in this collection
/// run sequentially, which is also what the broker zero-flake gate needs.
/// </summary>
[CollectionDefinition(Name)]
public sealed class RelayCollection : ICollectionFixture<TestFixture>
{
    public const string Name = nameof(RelayCollection);
}
