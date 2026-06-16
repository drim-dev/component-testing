namespace Relay.Api.Tests.Infrastructure;

/// <summary>
/// The canonical eventual-consistency assertion (spec/04-dependencies.md §9): poll the
/// OBSERVABLE state with a deadline — poll interval ≤ 100 ms, deadline 10 s by default,
/// never <c>sleep</c>-and-hope. Used for positive "the effect eventually appears" awaits;
/// settled/negative cases use the harnesses' lag/queue-drain awaits instead.
/// </summary>
public static class Awaiting
{
    public static async Task Until(Func<Task<bool>> condition, string description, TimeSpan? deadline = null)
    {
        var limit = DateTime.UtcNow + (deadline ?? TimeSpan.FromSeconds(10));
        while (!await condition())
        {
            if (DateTime.UtcNow > limit)
            {
                throw new TimeoutException($"Await-until deadline exceeded: {description}");
            }

            await Task.Delay(100);
        }
    }
}
