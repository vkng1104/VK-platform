package com.vkplatform.iam.session.application.port.out;

import com.vkplatform.iam.session.api.result.IamRetentionResult;

public interface IamSecurityTelemetry {
    long start();

    void record(Operation operation, Outcome outcome, long startedAt);

    void recordAfterCommit(Operation operation, Outcome outcome, long startedAt);

    void retentionAfterCommit(IamRetentionResult result, long startedAt);

    void recordOperator(Operation operation, Outcome outcome, long startedAt);

    enum Operation {
        AUTHORIZATION_CODE_LOOKUP("authorization_code_lookup"),
        TOKEN_ISSUE("token_issue"),
        REFRESH_ROTATION("refresh_rotation"),
        REFRESH_REPLAY("refresh_replay"),
        SESSION_REVOCATION("session_revocation"),
        SIGNING_KEY_ROTATION("signing_key_rotation"),
        SIGNING_KEY_COMPROMISE("signing_key_compromise"),
        OAUTH_CLIENT_SECRET_ROTATION("oauth_client_secret_rotation"),
        JWK_LOOKUP("jwk_lookup"),
        RETENTION("retention"),
        OPERATOR_BOOTSTRAP_SIGNING_KEY("bootstrap_signing_key"),
        OPERATOR_ROTATE_SIGNING_KEY("rotate_signing_key"),
        OPERATOR_RETIRE_SIGNING_KEYS("retire_signing_keys"),
        OPERATOR_REENCRYPT_SIGNING_KEYS("reencrypt_signing_keys"),
        OPERATOR_COMPROMISE_SIGNING_KEY("compromise_signing_key"),
        OPERATOR_RECONCILE_OAUTH_CLIENTS("reconcile_oauth_clients"),
        OPERATOR_ROTATE_OAUTH_CLIENT_SECRET("rotate_oauth_client_secret"),
        OPERATOR_CLEANUP("cleanup_iam_data");

        private final String tag;

        Operation(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    enum Outcome {
        SUCCESS("success"),
        NOOP("noop"),
        NOT_FOUND("not_found"),
        REPLAY("replay"),
        FAILURE("failure");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }
}
