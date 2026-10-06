package emailverification

import (
	"context"
	"time"
)

type EmailMessage struct {
	To        string
	Code      string
	ExpiresAt time.Time
}

type Sender interface {
	SendVerificationCode(ctx context.Context, message EmailMessage) error
}
