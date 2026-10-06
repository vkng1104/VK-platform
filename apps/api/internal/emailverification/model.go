package emailverification

import "time"

type Purpose string

const PurposeRestrictedResourceAccess Purpose = "restricted_resource_access"

type DeliveryStatus string

const (
	DeliveryStatusPending DeliveryStatus = "pending"
	DeliveryStatusSent    DeliveryStatus = "sent"
	DeliveryStatusFailed  DeliveryStatus = "failed"
)

type Challenge struct {
	ID                   string
	Purpose              Purpose
	EmailFingerprint     []byte
	RequesterFingerprint []byte
	OTPDigest            []byte
	AttemptCount         int
	MaxAttempts          int
	ExpiresAt            time.Time
	ResendNotBefore      time.Time
	DeliveryStatus       DeliveryStatus
	SentAt               *time.Time
	VerifiedAt           *time.Time
	InvalidatedAt        *time.Time
	CreatedAt            time.Time
}

type StartRequest struct {
	Email            string
	Purpose          Purpose
	RequesterAddress string
}

type StartResult struct {
	ID              string
	MaskedEmail     string
	ExpiresAt       time.Time
	ResendNotBefore time.Time
}

type VerifyRequest struct {
	ID   string
	Code string
}

type VerificationResult struct {
	ID         string
	Purpose    Purpose
	VerifiedAt time.Time
}

type StartPolicy struct {
	ChallengeTTL      time.Duration
	ResendCooldown    time.Duration
	EmailWindow       time.Duration
	MaxStartsPerEmail int
	RequesterWindow   time.Duration
	MaxStartsPerIP    int
	GlobalWindow      time.Duration
	MaxGlobalStarts   int
	MaxAttempts       int
}

func DefaultStartPolicy() StartPolicy {
	return StartPolicy{
		ChallengeTTL:      5 * time.Minute,
		ResendCooldown:    time.Minute,
		EmailWindow:       15 * time.Minute,
		MaxStartsPerEmail: 3,
		RequesterWindow:   time.Hour,
		MaxStartsPerIP:    10,
		GlobalWindow:      24 * time.Hour,
		MaxGlobalStarts:   100,
		MaxAttempts:       5,
	}
}
