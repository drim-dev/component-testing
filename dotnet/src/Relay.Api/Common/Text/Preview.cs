namespace Relay.Api.Common.Text;

/// <summary>
/// The 100-char preview used by feed entries and notifications. Pure function — exactly
/// the kind of logic a unit test covers well (the gallery honesty notes say so); the
/// component tests only assert it is WIRED into the event/notification paths.
/// </summary>
public static class Preview
{
    public const int MaxLength = 100;

    public static string Of(string text) =>
        text.Length <= MaxLength ? text : text[..MaxLength];
}
