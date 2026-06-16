using Relay.Api.Features.Assistant;

namespace Relay.Api.Tests.Naive;

/// <summary>
/// G-LLM naive variant, both beats: (a) raw message text is concatenated into the
/// INSTRUCTION prompt — a message saying "ignore previous instructions…" becomes
/// instructions; (b) the model's output goes straight to the client, unvalidated.
/// </summary>
public sealed class NaiveSummarizer(ISummaryModel model) : ISummarizer
{
    public async Task<string> Summarize(IReadOnlyList<SummarySource> messages, CancellationToken ct)
    {
        var prompt = SummaryPrompt.SystemPrompt
            + "\n\nSummarize this conversation:\n"
            + string.Join("\n", messages.Select(m => $"{m.SenderHandle}: {m.Text}"));

        return await model.Complete(new SummaryModelRequest(prompt, []), ct);
    }
}
