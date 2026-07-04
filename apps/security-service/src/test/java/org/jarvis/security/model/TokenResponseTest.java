package org.jarvis.security.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link TokenResponse} DTO: constructors, the "Bearer"
 * type default, and the generated accessors/equality contract.
 */
class TokenResponseTest {

    @Test
    void noArgsConstructorDefaultsTypeToBearer() {
        TokenResponse response = new TokenResponse();

        assertThat(response.getType()).isEqualTo("Bearer");
        assertThat(response.getToken()).isNull();
        assertThat(response.getExpiresIn()).isZero();
    }

    @Test
    void twoArgConstructorKeepsDefaultTypeAndSetsTokenFields() {
        TokenResponse response = new TokenResponse("jwt-token-value", 3600L);

        assertThat(response.getToken()).isEqualTo("jwt-token-value");
        assertThat(response.getExpiresIn()).isEqualTo(3600L);
        assertThat(response.getType()).isEqualTo("Bearer");
    }

    @Test
    void allArgsConstructorSetsEveryField() {
        TokenResponse response = new TokenResponse("jwt-token-value", "Custom", 60L);

        assertThat(response.getToken()).isEqualTo("jwt-token-value");
        assertThat(response.getType()).isEqualTo("Custom");
        assertThat(response.getExpiresIn()).isEqualTo(60L);
    }

    @Test
    void settersMutateFields() {
        TokenResponse response = new TokenResponse();

        response.setToken("new-token");
        response.setType("Bearer2");
        response.setExpiresIn(120L);

        assertThat(response.getToken()).isEqualTo("new-token");
        assertThat(response.getType()).isEqualTo("Bearer2");
        assertThat(response.getExpiresIn()).isEqualTo(120L);
    }

    @Test
    void equalsAndHashCodeHonorAllFields() {
        TokenResponse a = new TokenResponse("tok", "Bearer", 60L);
        TokenResponse b = new TokenResponse("tok", "Bearer", 60L);
        TokenResponse different = new TokenResponse("other", "Bearer", 60L);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(different);
    }

    @Test
    void toStringIncludesFieldValues() {
        TokenResponse response = new TokenResponse("tok", "Bearer", 60L);

        assertThat(response.toString())
                .contains("tok")
                .contains("Bearer")
                .contains("60");
    }
}
