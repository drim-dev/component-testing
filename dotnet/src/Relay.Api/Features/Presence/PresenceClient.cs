using Grpc.Core;
using Relay.Api.Common.Exceptions;
using Relay.Api.Features.Presence.Grpc;

namespace Relay.Api.Features.Presence;

/// <summary>One member's presence as the API exposes it.</summary>
public sealed record MemberPresence(string UserId, bool Online);

/// <summary>
/// The G-GRPC seam: the API's consumption of the presence service. The correct
/// implementation consumes the server stream to its clean end-of-stream and surfaces a
/// mid-stream transport error as a contract violation (502 <c>presence:incomplete</c>) —
/// it NEVER presents a partial member list as complete. The naive variant swallows the
/// stream error in a try/catch and returns whatever arrived.
/// </summary>
public interface IPresenceClient
{
    Task<bool> IsOnline(string userId, CancellationToken ct);

    Task<IReadOnlyList<MemberPresence>> StreamPresence(IReadOnlyList<string> userIds, CancellationToken ct);
}

public sealed class PresenceClient(Grpc.Presence.PresenceClient grpc) : IPresenceClient
{
    public async Task<bool> IsOnline(string userId, CancellationToken ct)
    {
        var status = await grpc.GetPresenceAsync(new GetPresenceRequest { UserId = userId }, cancellationToken: ct);
        return status.Online;
    }

    public async Task<IReadOnlyList<MemberPresence>> StreamPresence(
        IReadOnlyList<string> userIds, CancellationToken ct)
    {
        var request = new StreamChannelPresenceRequest();
        request.UserIds.AddRange(userIds);

        var results = new List<MemberPresence>();
        try
        {
            using var call = grpc.StreamChannelPresence(request, cancellationToken: ct);
            await foreach (var status in call.ResponseStream.ReadAllAsync(ct))
            {
                results.Add(new MemberPresence(status.UserId, status.Online));
            }
        }
        catch (RpcException)
        {
            // A mid-stream abort means we did NOT consume to clean end-of-stream: the list
            // we hold is partial. Surfacing it as complete is the gallery bug — fail loudly.
            throw new UpstreamException(
                "presence:incomplete", "The presence stream terminated before completion.");
        }

        return results;
    }
}
