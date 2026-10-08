package com.vkplatform.iam.verification.api.command;

public record VerifyEmailCode(String verificationId, String code) {
}
