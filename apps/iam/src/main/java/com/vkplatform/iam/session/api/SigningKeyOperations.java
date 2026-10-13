package com.vkplatform.iam.session.api;

import com.vkplatform.iam.session.api.result.SigningKeySummary;

public interface SigningKeyOperations {
    SigningKeySummary bootstrap();

    SigningKeySummary rotate();

    int retireExpired();
}
