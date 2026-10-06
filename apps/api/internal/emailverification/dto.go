package emailverification

import "time"

type startRequestDTO struct {
	Email   string  `json:"email"`
	Purpose Purpose `json:"purpose"`
}

type verifyRequestDTO struct {
	Code string `json:"code"`
}

type challengeEnvelope struct {
	Verification challengeResponse `json:"verification"`
}

type verificationEnvelope struct {
	Verification verificationResponse `json:"verification"`
}

type challengeResponse struct {
	ID              string    `json:"id"`
	MaskedEmail     string    `json:"masked_email"`
	ExpiresAt       time.Time `json:"expires_at"`
	ResendNotBefore time.Time `json:"resend_after"`
}

type verificationResponse struct {
	ID         string    `json:"id"`
	Purpose    Purpose   `json:"purpose"`
	VerifiedAt time.Time `json:"verified_at"`
}

func newChallengeEnvelope(result StartResult) challengeEnvelope {
	return challengeEnvelope{Verification: challengeResponse{
		ID:              result.ID,
		MaskedEmail:     result.MaskedEmail,
		ExpiresAt:       result.ExpiresAt,
		ResendNotBefore: result.ResendNotBefore,
	}}
}

func newVerificationEnvelope(result VerificationResult) verificationEnvelope {
	return verificationEnvelope{Verification: verificationResponse{
		ID:         result.ID,
		Purpose:    result.Purpose,
		VerifiedAt: result.VerifiedAt,
	}}
}
