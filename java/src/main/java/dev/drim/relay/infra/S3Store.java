package dev.drim.relay.infra;

import dev.drim.relay.seams.AttachmentStore;
import java.util.List;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The MinIO-backed attachment object store (AWS S3 SDK v2 against a MinIO endpoint). Bytes live
 * behind an opaque storage key; the API NEVER derives authorization from key possession (that lives
 * in {@link dev.drim.relay.seams.AttachmentAccess}). Mirrors go/src/relay/infra/s3.go.
 */
@Component
public class S3Store implements AttachmentStore {
  public static final String BUCKET = "relay-attachments";

  private final S3Client client;

  public S3Store(S3Client client) {
    this.client = client;
  }

  @Override
  public void put(String key, byte[] data) {
    client.putObject(
        PutObjectRequest.builder()
            .bucket(BUCKET)
            .key(key)
            .contentType("application/octet-stream")
            .build(),
        RequestBody.fromBytes(data));
  }

  @Override
  public byte[] get(String key) {
    ResponseBytes<?> bytes =
        client.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(key).build());
    return bytes.asByteArray();
  }

  @Override
  public void deleteAll() {
    List<S3Object> objects =
        client.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build()).contents();
    if (objects.isEmpty()) {
      return;
    }
    List<ObjectIdentifier> ids =
        objects.stream().map(o -> ObjectIdentifier.builder().key(o.key()).build()).toList();
    client.deleteObjects(
        DeleteObjectsRequest.builder()
            .bucket(BUCKET)
            .delete(Delete.builder().objects(ids).build())
            .build());
  }
}
