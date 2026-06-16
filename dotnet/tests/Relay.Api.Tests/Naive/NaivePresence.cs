using Grpc.Core;
using Relay.Api.Features.Presence;
using Relay.Api.Features.Presence.Grpc;
using PresenceGrpcClient = Relay.Api.Features.Presence.Grpc.Presence.PresenceClient;

namespace Relay.Api.Tests.Naive;

/// <summary>
/// G-GRPC naive variant: collects stream messages in a try/catch that SWALLOWS the
/// mid-stream error and returns whatever arrived — a partial member list presented as
/// complete. The default shape an agent ships when it has only ever seen the stream
/// succeed (a mock never fails midway). The unary path is identical to the correct one;
/// only the streaming consumption is buggy.
/// </summary>
public sealed class NaivePresenceClient(PresenceGrpcClient grpc) : IPresenceClient
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
            // The bug: a mid-stream abort is swallowed and the partial list is returned as
            // if the stream had completed.
        }

        return results;
    }
}
