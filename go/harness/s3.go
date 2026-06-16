package harness

import (
	"context"
	"fmt"
	"io"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/infra"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
	"github.com/testcontainers/testcontainers-go"
	tcminio "github.com/testcontainers/testcontainers-go/modules/minio"
	"github.com/testcontainers/testcontainers-go/wait"
)

// S3Harness is the MinIO harness (object storage over real HTTP). Real container; the bucket
// is created at boot. Seed = put objects directly; Assert = get/list + compare bytes;
// Reset = delete all objects (the bucket survives). The teaching point: S3 *could* be faked,
// but real HTTP+auth+streaming is where bugs live (the container-vs-fake decision).
type S3Harness struct {
	container *tcminio.MinioContainer
	client    *minio.Client
	endpoint  string
	accessKey string
	secretKey string
}

func (h *S3Harness) Client() *minio.Client { return h.client }

func (h *S3Harness) Start(ctx context.Context) error {
	container, err := tcminio.Run(ctx, MinioImage,
		testcontainers.WithWaitStrategy(wait.ForHTTP("/minio/health/ready").WithPort("9000").WithStartupTimeout(60*time.Second)),
	)
	if err != nil {
		return fmt.Errorf("start minio: %w", err)
	}
	h.container = container
	h.accessKey = container.Username
	h.secretKey = container.Password

	endpoint, err := container.ConnectionString(ctx)
	if err != nil {
		return fmt.Errorf("minio endpoint: %w", err)
	}
	h.endpoint = endpoint

	client, err := minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(h.accessKey, h.secretKey, ""),
		Secure: false,
	})
	if err != nil {
		return fmt.Errorf("minio client: %w", err)
	}
	h.client = client

	exists, err := client.BucketExists(ctx, infra.Bucket)
	if err != nil {
		return fmt.Errorf("bucket exists: %w", err)
	}
	if !exists {
		if err := client.MakeBucket(ctx, infra.Bucket, minio.MakeBucketOptions{}); err != nil {
			return fmt.Errorf("make bucket: %w", err)
		}
	}
	return nil
}

func (h *S3Harness) Reset(ctx context.Context) error {
	objects := h.client.ListObjects(ctx, infra.Bucket, minio.ListObjectsOptions{Recursive: true})
	for obj := range objects {
		if obj.Err != nil {
			return obj.Err
		}
		if err := h.client.RemoveObject(ctx, infra.Bucket, obj.Key, minio.RemoveObjectOptions{}); err != nil {
			return err
		}
	}
	return nil
}

func (h *S3Harness) Stop(ctx context.Context) error {
	if h.container != nil {
		return h.container.Terminate(ctx)
	}
	return nil
}

func (h *S3Harness) Endpoint() string  { return h.endpoint }
func (h *S3Harness) AccessKey() string { return h.accessKey }
func (h *S3Harness) SecretKey() string { return h.secretKey }

// ObjectBytes reads a stored object — an Assert that bytes landed under the storage key.
func (h *S3Harness) ObjectBytes(ctx context.Context, key string) ([]byte, error) {
	obj, err := h.client.GetObject(ctx, infra.Bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return nil, err
	}
	defer func() { _ = obj.Close() }()
	return io.ReadAll(obj)
}

var _ DependencyHarness = (*S3Harness)(nil)
