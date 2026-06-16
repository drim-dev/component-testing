// MinIO-backed attachment object store. Bytes live behind an opaque storage key;
// the API NEVER derives authorization from key possession (that lives in the
// AttachmentAccess seam).

import type { Client } from 'minio';

import type { AttachmentStore } from '../seams/seams.js';

export const BUCKET = 'relay-attachments';

export class S3Store implements AttachmentStore {
  constructor(private readonly client: Client) {}

  async put(key: string, data: Buffer): Promise<void> {
    await this.client.putObject(BUCKET, key, data, data.length, {
      'Content-Type': 'application/octet-stream',
    });
  }

  async get(key: string): Promise<Buffer> {
    const stream = await this.client.getObject(BUCKET, key);
    const chunks: Buffer[] = [];
    for await (const chunk of stream) {
      chunks.push(chunk as Buffer);
    }
    return Buffer.concat(chunks);
  }

  async deleteAll(): Promise<void> {
    const keys: string[] = [];
    const stream = this.client.listObjectsV2(BUCKET, '', true);
    await new Promise<void>((resolve, reject) => {
      stream.on('data', (obj) => obj.name && keys.push(obj.name));
      stream.on('end', () => resolve());
      stream.on('error', reject);
    });
    if (keys.length > 0) {
      await this.client.removeObjects(BUCKET, keys);
    }
  }
}
