package bandwidth

import "golang.org/x/time/rate"

// minBurst keeps the burst above the largest piece request of the torrent client,
// otherwise a very low limit would block downloads forever.
const minBurst = 64 << 10

// NewLimiter returns a limiter for the given rate in bytes per second.
// A rate of zero or less means no limit and yields nil.
func NewLimiter(bytesPerSecond int64) *rate.Limiter {
	if bytesPerSecond <= 0 {
		return nil
	}
	burst := int(bytesPerSecond)
	if burst < minBurst {
		burst = minBurst
	}
	return rate.NewLimiter(rate.Limit(bytesPerSecond), burst)
}
