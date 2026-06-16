using Relay.Api.Common.Exceptions;

namespace Relay.Api.Features.Assistant;

public sealed record SummarySource(string SenderHandle, string Text);

/// <summary>
/// The G-LLM seam: assembles the model request and validates the model's output. The
/// correct implementation keeps instructions and user content separated (prompt
/// injection) and rejects contract-violating output with 502 (never forwards it). The
/// naive variant concatenates raw message text into the instruction prompt and returns
/// the model's output unvalidated.
/// </summary>
public interface ISummarizer
{
    Task<string> Summarize(IReadOnlyList<SummarySource> messages, CancellationToken ct);
}

public sealed class Summarizer(ISummaryModel model) : ISummarizer
{
    public const int MaxSummaryLength = 2000;

    public async Task<string> Summarize(IReadOnlyList<SummarySource> messages, CancellationToken ct)
    {
        var request = new SummaryModelRequest(
            SummaryPrompt.SystemPrompt,
            messages.Select(m => SummaryPrompt.RenderBlock(m.SenderHandle, m.Text)).ToList());

        var summary = await model.Complete(request, ct);

        if (string.IsNullOrWhiteSpace(summary) || summary.Length > MaxSummaryLength)
        {
            throw new UpstreamException(
                "summary:invalid_output", "The model violated the summary output contract.");
        }

        return summary;
    }
}
