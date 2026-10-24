package com.vkplatform.iam.session.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JsonWebKeyResponse(
        @JsonProperty("kty") String keyType,
        @JsonProperty("use") String use,
        @JsonProperty("kid") String keyId,
        @JsonProperty("alg") String algorithm,
        @JsonProperty("n") String modulus,
        @JsonProperty("e") String exponent
) {
}
