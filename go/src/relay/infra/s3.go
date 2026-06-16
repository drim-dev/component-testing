package infra

import (
	"bytes"
	"context"
	"io"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/seams"
	"github.com/minio/minio-go/v7"
)

// Bucket is the attachment bucket (04-dependencies.md §5).
const Bucket = "relay-attachments"

// S3Store is the MinIO-backed attachment object store. Bytes live behind an opaque storage
// key; the API NEVER derives authorization from key possession (that lives in the seam).
type S3Store struct{ client *minio.Client }

func NewS3Store(client *minio.Client) *S3Store { return &S3Store{client: client} }

func (s *S3Store) Put(ctx context.Context, key string, data []byte) error {
	_, err := s.client.PutObject(ctx, Bucket, key, bytes.NewReader(data), int64(len(data)),
		minio.PutObjectOptions{ContentType: "application/octet-stream"})
	return err
}

func (s *S3Store) Get(ctx context.Context, key string) ([]byte, error) {
	obj, err := s.client.GetObject(ctx, Bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return nil, err
	}
	defer func() { _ = obj.Close() }()
	return io.ReadAll(obj)
}

func (s *S3Store) DeleteAll(ctx context.Context) error {
	objects := s.client.ListObjects(ctx, Bucket, minio.ListObjectsOptions{Recursive: true})
	for obj := range objects {
		if obj.Err != nil {
			return obj.Err
		}
		if err := s.client.RemoveObject(ctx, Bucket, obj.Key, minio.RemoveObjectOptions{}); err != nil {
			return err
		}
	}
	return nil
}

var _ seams.AttachmentStore = (*S3Store)(nil)
