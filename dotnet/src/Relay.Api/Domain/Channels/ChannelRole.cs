namespace Relay.Api.Domain.Channels;

/// <summary>
/// Channel roles, ordered <c>Owner &gt; Admin &gt; Member</c>. The numeric ordering is the
/// pure predicate the BOLA-ROLE case unit-tests; whether a route consults it is a
/// system property the catching test verifies.
/// </summary>
public enum ChannelRole
{
    Member = 0,
    Admin = 1,
    Owner = 2,
}

public static class ChannelRoleNames
{
    public const string Owner = "owner";
    public const string Admin = "admin";
    public const string Member = "member";

    public static string ToDbValue(ChannelRole role) => role switch
    {
        ChannelRole.Owner => Owner,
        ChannelRole.Admin => Admin,
        ChannelRole.Member => Member,
        _ => throw new ArgumentOutOfRangeException(nameof(role), role, null),
    };

    public static ChannelRole FromDbValue(string value) => value switch
    {
        Owner => ChannelRole.Owner,
        Admin => ChannelRole.Admin,
        Member => ChannelRole.Member,
        _ => throw new ArgumentOutOfRangeException(nameof(value), value, null),
    };
}
