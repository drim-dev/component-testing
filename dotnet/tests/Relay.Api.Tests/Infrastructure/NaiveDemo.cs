namespace Relay.Api.Tests.Infrastructure;

/// <summary>
/// The expect-failure wrapper that lets a RED demonstration live inside a GREEN suite
/// (design §11.D, gate item). It runs the catching test's own assertion block against
/// an app where the gallery case's correct seam has been replaced by its naive variant,
/// and asserts those assertions FAIL — i.e. the catching test goes red against the bug.
///
/// If the assertions PASS against the naive variant, the demonstration itself fails:
/// it means the catching test does not actually catch the bug (a false proof), which is
/// exactly the failure mode the guide warns about.
/// </summary>
public static class NaiveDemo
{
    public static async Task ExpectCatchToFail(string galleryCaseId, Func<Task> catchingAssertions)
    {
        try
        {
            await catchingAssertions();
        }
        catch (Exception ex) when (ex is not NaiveDemoFalseProofException)
        {
            return;
        }

        throw new NaiveDemoFalseProofException(
            $"Naive-variant demonstration for {galleryCaseId} did NOT go red: the catching " +
            "assertions passed against the naive (buggy) implementation. The catching test " +
            "is not actually catching this gallery case.");
    }
}

public sealed class NaiveDemoFalseProofException(string message) : Exception(message);
