// Package idgen mints opaque, time-ordered, URL-safe string ids. The spec treats
// ids as opaque non-empty strings (02-api.md §0); time-ordering keeps newest-first
// pagination stable. A Factory carries a generator id so a second factory (the naive
// test host) never collides with ids minted by the suite's seeding factory.
package idgen

import (
	"crypto/rand"
	"encoding/binary"
	"sync/atomic"
	"time"
)

// crockford is the Crockford Base32 alphabet (URL-safe, unambiguous).
const crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// Factory generates time-ordered ids. Safe for concurrent use.
type Factory struct {
	generatorID uint16
	seq         atomic.Uint32
}

// New returns a Factory tagged with generatorID. Distinct ids across factories are
// guaranteed by the generatorID segment, so the naive test host (a different id) never
// produces an id equal to one seeded by the default factory.
func New(generatorID uint16) *Factory {
	f := &Factory{generatorID: generatorID}
	var seed [4]byte
	_, _ = rand.Read(seed[:])
	f.seq.Store(binary.BigEndian.Uint32(seed[:]))
	return f
}

// Create returns a new id: 48-bit millisecond timestamp + 16-bit generator id +
// 32-bit monotonic sequence, Crockford-Base32 encoded. The leading timestamp makes the
// lexicographic order of the encoded string match creation order.
func (f *Factory) Create() string {
	var buf [12]byte
	ms := uint64(time.Now().UnixMilli())
	buf[0] = byte(ms >> 40)
	buf[1] = byte(ms >> 32)
	buf[2] = byte(ms >> 24)
	buf[3] = byte(ms >> 16)
	buf[4] = byte(ms >> 8)
	buf[5] = byte(ms)
	binary.BigEndian.PutUint16(buf[6:8], f.generatorID)
	binary.BigEndian.PutUint32(buf[8:12], f.seq.Add(1))
	return encode(buf[:])
}

func encode(b []byte) string {
	out := make([]byte, 0, len(b)*8/5+1)
	var buffer uint64
	var bits uint
	for _, c := range b {
		buffer = buffer<<8 | uint64(c)
		bits += 8
		for bits >= 5 {
			bits -= 5
			out = append(out, crockford[(buffer>>bits)&0x1f])
		}
	}
	if bits > 0 {
		out = append(out, crockford[(buffer<<(5-bits))&0x1f])
	}
	return string(out)
}
