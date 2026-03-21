package domain

import "context"

// HealthStatus represents the health check result for a single component.
type HealthStatus struct {
	Name    string
	Healthy bool
	Detail  string // e.g. "HTTP 200" or error message
}

// HealthChecker is an optional interface that providers can implement
// to support health checks.
type HealthChecker interface {
	CheckHealth(ctx context.Context) []HealthStatus
}
