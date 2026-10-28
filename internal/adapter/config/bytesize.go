package config

import (
	"fmt"
	"strconv"
	"strings"
)

// ByteSize is a size in bytes parsed from a human-readable string.
// Accepts plain byte counts ("52428800") or a number with a binary unit suffix:
// B, K/KB/KiB, M/MB/MiB, G/GB/GiB, T/TB/TiB (case-insensitive, 1 KB = 1024 B).
type ByteSize int64

var byteUnits = map[string]int64{
	"":    1,
	"b":   1,
	"k":   1 << 10,
	"kb":  1 << 10,
	"kib": 1 << 10,
	"m":   1 << 20,
	"mb":  1 << 20,
	"mib": 1 << 20,
	"g":   1 << 30,
	"gb":  1 << 30,
	"gib": 1 << 30,
	"t":   1 << 40,
	"tb":  1 << 40,
	"tib": 1 << 40,
}

func (s *ByteSize) UnmarshalText(text []byte) error {
	v, err := ParseByteSize(string(text))
	if err != nil {
		return err
	}
	*s = v
	return nil
}

// ParseByteSize parses a human-readable size, see ByteSize.
func ParseByteSize(raw string) (ByteSize, error) {
	str := strings.TrimSpace(raw)
	if str == "" {
		return 0, fmt.Errorf("empty size")
	}

	i := 0
	for i < len(str) && (str[i] >= '0' && str[i] <= '9' || str[i] == '.') {
		i++
	}
	num, unit := str[:i], strings.ToLower(strings.TrimSpace(str[i:]))

	mult, ok := byteUnits[unit]
	if !ok {
		return 0, fmt.Errorf("invalid size %q: unknown unit %q", raw, unit)
	}

	n, err := strconv.ParseFloat(num, 64)
	if err != nil || n < 0 {
		return 0, fmt.Errorf("invalid size %q", raw)
	}

	return ByteSize(n * float64(mult)), nil
}
