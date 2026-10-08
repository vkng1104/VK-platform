package com.vkplatform.iam.verification.web.dto;

public record StartEmailVerificationRequest(String email, String purpose) {
}
