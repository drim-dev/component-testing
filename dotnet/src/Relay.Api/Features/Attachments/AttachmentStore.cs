using Amazon.S3;
using Amazon.S3.Model;
using Microsoft.Extensions.Configuration;

namespace Relay.Api.Features.Attachments;

/// <summary>
/// The narrow object-store port the app actually needs (put/get by opaque key).
/// Backed by REAL S3 (MinIO in tests — spec/04-dependencies.md §5: containerizable →
/// real); the port exists because the app only needs two verbs of S3's hundred, not to
/// enable mocking it in tests.
/// </summary>
public interface IAttachmentStore
{
    Task Save(string storageKey, byte[] content, CancellationToken ct);

    Task<byte[]> Read(string storageKey, CancellationToken ct);
}

public sealed class S3AttachmentStore(IAmazonS3 s3, IConfiguration configuration) : IAttachmentStore
{
    private readonly string _bucket = configuration.GetAttachmentBucket();

    public async Task Save(string storageKey, byte[] content, CancellationToken ct)
    {
        using var stream = new MemoryStream(content);
        await s3.PutObjectAsync(new PutObjectRequest
        {
            BucketName = _bucket,
            Key = storageKey,
            InputStream = stream,
        }, ct);
    }

    public async Task<byte[]> Read(string storageKey, CancellationToken ct)
    {
        using var response = await s3.GetObjectAsync(_bucket, storageKey, ct);
        using var buffer = new MemoryStream();
        await response.ResponseStream.CopyToAsync(buffer, ct);
        return buffer.ToArray();
    }
}

public static class AttachmentStorage
{
    public static string GetAttachmentBucket(this IConfiguration configuration) =>
        configuration["S3:Bucket"] ?? "relay-attachments";
}
