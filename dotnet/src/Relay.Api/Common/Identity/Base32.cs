namespace Relay.Api.Common.Identity;

/// <summary>
/// Crockford Base32 over a non-negative 63-bit id, zero-padded to 13 chars so the
/// encoded form sorts lexicographically the same as the numeric value (keeps id a
/// valid pagination tiebreak).
/// </summary>
public static class Base32
{
    private const string Alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private const int Width = 13;

    public static string Encode(long value)
    {
        if (value < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(value), "Id must be non-negative.");
        }

        Span<char> buffer = stackalloc char[Width];
        for (var i = Width - 1; i >= 0; i--)
        {
            buffer[i] = Alphabet[(int)(value & 0x1F)];
            value >>= 5;
        }

        return new string(buffer);
    }
}
