package com.vkplatform.iam.session.api;

import com.vkplatform.iam.session.api.result.SigningKeySummary;
import com.vkplatform.iam.session.api.command.OperatorContext;

public interface SigningKeyOperations {
    SigningKeySummary bootstrap(OperatorContext context);

    SigningKeySummary rotate(OperatorContext context);

    int retireExpired(OperatorContext context);

    int reencrypt(int batchSize, OperatorContext context);

    SigningKeySummary compromise(String keyId, OperatorContext context);
}
