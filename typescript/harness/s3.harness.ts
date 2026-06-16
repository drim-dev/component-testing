// The MinIO harness (object storage over real HTTP). Real container via a
// GenericContainer (no dedicated testcontainers module needed); the bucket is
// created at boot. Seed = put objects directly; Assert = get/list + compare
// bytes; Reset = delete all objects (the bucket survives). The teaching point:
// S3 *could* be faked, but real HTTP+auth+streaming is where bugs live (the
// container-vs-fake decision).

import { Client } from 'minio';
import { GenericContainer, type StartedTestContainer, Wait } from 'testcontainers';

import { BUCKET } from '../src/infra/s3-infra.js';
import type { DependencyHarness } from './dependency-harness.js';
import { MINIO_IMAGE } from './images.js';

const ACCESS_KEY = 'relay-minio';
const SECRET_KEY = 'relay-minio-secret';

export class S3Harness implements DependencyHarness {
  private container?: StartedTestContainer;
  private minioClient?: Client;

  get client(): Client {
    if (!this.minioClient) {
      throw new Error('S3Harness not started');
    }
    return this.minioClient;
  }

  async start(): Promise<void> {
    this.container = await new GenericContainer(MINIO_IMAGE)
      .withEnvironment({ MINIO_ROOT_USER: ACCESS_KEY, MINIO_ROOT_PASSWORD: SECRET_KEY })
      .withCommand(['server', '/data'])
      .withExposedPorts(9000)
      .withWaitStrategy(Wait.forHttp('/minio/health/ready', 9000).forStatusCode(200))
      .start();

    this.minioClient = new Client({
      endPoint: this.container.getHost(),
      port: this.container.getMappedPort(9000),
      useSSL: false,
      accessKey: ACCESS_KEY,
      secretKey: SECRET_KEY,
    });

    if (!(await this.minioClient.bucketExists(BUCKET))) {
      await this.minioClient.makeBucket(BUCKET);
    }
  }

  async reset(): Promise<void> {
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

  async stop(): Promise<void> {
    if (this.container) {
      await this.container.stop();
    }
  }

  // objectBytes reads a stored object — an Assert that bytes landed under the key.
  async objectBytes(key: string): Promise<Buffer> {
    const stream = await this.client.getObject(BUCKET, key);
    const chunks: Buffer[] = [];
    for await (const chunk of stream) {
      chunks.push(chunk as Buffer);
    }
    return Buffer.concat(chunks);
  }
}
