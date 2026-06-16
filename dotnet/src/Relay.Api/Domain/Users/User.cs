namespace Relay.Api.Domain.Users;

public sealed class User
{
    public required string Id { get; init; }
    public required string Handle { get; set; }
    public required string DisplayName { get; set; }
    public DateTime CreatedAt { get; init; }
}
