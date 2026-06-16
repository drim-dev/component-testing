package dev.drim.relay.harness;

import dev.drim.relay.infra.S3Store;
import java.net.URI;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The MinIO harness (object storage over real HTTP, AWS S3 SDK v2). Real container; the bucket is
 * created at boot. Seed = put objects directly; Assert = get/list + compare bytes; Reset = delete
 * all objects (the bucket survives). The teaching point: S3 *could* be faked, but real
 * HTTP+auth+streaming is where bugs live (the container-vs-fake decision). Mirrors
 * go/harness/s3.go.
 */
public final class S3Harness implements DependencyHarness {
  private final MinIOContainer container =
      new MinIOContainer(
          DockerImageName.parse(HarnessImages.MINIO).asCompatibleSubstituteFor("minio/minio"));

  private S3Client client;

  @Override
  public void start() {
    container.start();
    client =
        S3Client.builder()
            .endpointOverride(URI.create(endpoint()))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey(), secretKey())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    try {
      client.headBucket(b -> b.bucket(S3Store.BUCKET));
    } catch (NoSuchBucketException e) {
      client.createBucket(CreateBucketRequest.builder().bucket(S3Store.BUCKET).build());
    } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
      if (e.statusCode() == 404) {
        client.createBucket(CreateBucketRequest.builder().bucket(S3Store.BUCKET).build());
      } else {
        throw e;
      }
    }
  }

  public String endpoint() {
    return container.getS3URL();
  }

  public String accessKey() {
    return container.getUserName();
  }

  public String secretKey() {
    return container.getPassword();
  }

  @Override
  public void reset() {
    var objects =
        client
            .listObjectsV2(ListObjectsV2Request.builder().bucket(S3Store.BUCKET).build())
            .contents();
    if (objects.isEmpty()) {
      return;
    }
    var ids = objects.stream().map(o -> ObjectIdentifier.builder().key(o.key()).build()).toList();
    client.deleteObjects(
        DeleteObjectsRequest.builder()
            .bucket(S3Store.BUCKET)
            .delete(Delete.builder().objects(ids).build())
            .build());
  }

  @Override
  public void stop() {
    if (client != null) {
      client.close();
    }
    container.stop();
  }

  /** Stores an object directly — a seed. */
  public void putObject(String key, byte[] data) {
    client.putObject(
        PutObjectRequest.builder().bucket(S3Store.BUCKET).key(key).build(),
        RequestBody.fromBytes(data));
  }

  /** Reads a stored object — an Assert that bytes landed under the storage key. */
  public byte[] objectBytes(String key) {
    return client
        .getObjectAsBytes(GetObjectRequest.builder().bucket(S3Store.BUCKET).key(key).build())
        .asByteArray();
  }
}
