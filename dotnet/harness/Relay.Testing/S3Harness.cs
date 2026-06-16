using Amazon.Runtime;
using Amazon.S3;
using Amazon.S3.Model;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Testcontainers.Minio;

namespace Relay.Testing;

/// <summary>
/// S3 harness (MinIO). The container-vs-fake teaching point lives here: S3 *could* be
/// faked in-process, but MinIO is cheap and the bugs live in real HTTP + auth +
/// streaming. <c>Seed</c> = put objects directly; <c>Assert</c> = get objects back and
/// compare bytes; <c>Reset</c> = delete all objects (the bucket survives the suite).
/// </summary>
[System.Diagnostics.CodeAnalysis.SuppressMessage("Design", "CA1001:Types that own disposable fields should be disposable",
    Justification = "Harness lifecycle is Start/Stop (IDependencyHarness); Stop disposes the client.")]
public sealed class S3Harness<TProgram> : IDependencyHarness<TProgram>
    where TProgram : class
{
    public const string Bucket = "relay-attachments";

    private MinioContainer? _minio;
    private AmazonS3Client? _client;

    public void ConfigureWebHostBuilder(IWebHostBuilder builder)
    {
        builder.UseSetting("S3:ServiceUrl", Minio.GetConnectionString());
        builder.UseSetting("S3:AccessKey", Minio.GetAccessKey());
        builder.UseSetting("S3:SecretKey", Minio.GetSecretKey());
        builder.UseSetting("S3:Bucket", Bucket);
    }

    public async Task Start(WebApplicationFactory<TProgram> factory, CancellationToken cancellationToken)
    {
        _minio = new MinioBuilder(ContainerImages.Minio).Build();
        await _minio.StartAsync(cancellationToken);

        _client = new AmazonS3Client(
            new BasicAWSCredentials(_minio.GetAccessKey(), _minio.GetSecretKey()),
            new AmazonS3Config { ServiceURL = _minio.GetConnectionString(), ForcePathStyle = true });
        await _client.PutBucketAsync(Bucket, cancellationToken);
    }

    public async Task Stop(CancellationToken cancellationToken)
    {
        _client?.Dispose();
        if (_minio is not null)
        {
            await _minio.StopAsync(cancellationToken);
            await _minio.DisposeAsync();
        }
    }

    public async Task PutObject(string key, byte[] content, CancellationToken ct = default)
    {
        using var stream = new MemoryStream(content);
        await Client.PutObjectAsync(new PutObjectRequest
        {
            BucketName = Bucket,
            Key = key,
            InputStream = stream,
        }, ct);
    }

    public async Task<byte[]> GetObjectBytes(string key, CancellationToken ct = default)
    {
        using var response = await Client.GetObjectAsync(Bucket, key, ct);
        using var buffer = new MemoryStream();
        await response.ResponseStream.CopyToAsync(buffer, ct);
        return buffer.ToArray();
    }

    public async Task<bool> ObjectExists(string key, CancellationToken ct = default)
    {
        try
        {
            await Client.GetObjectMetadataAsync(Bucket, key, ct);
            return true;
        }
        catch (AmazonS3Exception ex) when (ex.StatusCode == System.Net.HttpStatusCode.NotFound)
        {
            return false;
        }
    }

    public async Task DeleteAllObjects(CancellationToken ct = default)
    {
        var listed = await Client.ListObjectsV2Async(new ListObjectsV2Request { BucketName = Bucket }, ct);
        if (listed.S3Objects is not { Count: > 0 })
        {
            return;
        }

        await Client.DeleteObjectsAsync(new DeleteObjectsRequest
        {
            BucketName = Bucket,
            Objects = listed.S3Objects.Select(o => new KeyVersion { Key = o.Key }).ToList(),
        }, ct);
    }

    private MinioContainer Minio => _minio ?? throw new InvalidOperationException("S3Harness is not started.");

    private AmazonS3Client Client => _client ?? throw new InvalidOperationException("S3Harness is not started.");
}
