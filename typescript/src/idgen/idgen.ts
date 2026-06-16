// idgen mints opaque, time-ordered, URL-safe string ids. The spec treats ids as
// opaque non-empty strings (02-api.md §0); time-ordering keeps newest-first
// pagination stable. A factory carries a generator id so a second factory (the
// naive test host) never collides with ids minted by the suite's seeding
// factory — the same idea as the Go pilot's idgen.

import { randomBytes } from 'node:crypto';

// Crockford Base32 alphabet (URL-safe, unambiguous).
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

// IdFactory generates time-ordered ids. The generatorId segment guarantees
// distinct ids across factories, so the naive test host (a different id) never
// produces an id equal to one seeded by the default factory.
export class IdFactory {
  private seq: number;

  constructor(private readonly generatorId: number) {
    this.seq = randomBytes(4).readUInt32BE(0);
  }

  // create returns a new id: 48-bit ms timestamp + 16-bit generator id + 32-bit
  // monotonic sequence, Crockford-Base32 encoded. The leading timestamp makes
  // the lexicographic order of the encoded string match creation order.
  create(): string {
    const buf = Buffer.alloc(12);
    const ms = Date.now();
    buf.writeUIntBE(ms, 0, 6);
    buf.writeUInt16BE(this.generatorId & 0xffff, 6);
    this.seq = (this.seq + 1) >>> 0;
    buf.writeUInt32BE(this.seq, 8);
    return encode(buf);
  }
}

function encode(bytes: Buffer): string {
  let out = '';
  let buffer = 0n;
  let bits = 0n;
  for (const byte of bytes) {
    buffer = (buffer << 8n) | BigInt(byte);
    bits += 8n;
    while (bits >= 5n) {
      bits -= 5n;
      out += CROCKFORD[Number((buffer >> bits) & 0x1fn)];
    }
  }
  if (bits > 0n) {
    out += CROCKFORD[Number((buffer << (5n - bits)) & 0x1fn)];
  }
  return out;
}
