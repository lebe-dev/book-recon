package bandwidth

import "testing"

func TestNewLimiterDisabled(t *testing.T) {
	for _, rate := range []int64{0, -1} {
		if NewLimiter(rate) != nil {
			t.Errorf("NewLimiter(%d) must be nil (unlimited)", rate)
		}
	}
}

func TestNewLimiterRate(t *testing.T) {
	l := NewLimiter(1 << 20)
	if l == nil {
		t.Fatal("limiter is nil")
	}
	if int64(l.Limit()) != 1<<20 {
		t.Errorf("limit = %v, want 1 MiB/s", l.Limit())
	}
}

func TestNewLimiterBurstCoversChunk(t *testing.T) {
	l := NewLimiter(1024)
	if l.Burst() < minBurst {
		t.Errorf("burst = %d, want at least %d", l.Burst(), minBurst)
	}
}
