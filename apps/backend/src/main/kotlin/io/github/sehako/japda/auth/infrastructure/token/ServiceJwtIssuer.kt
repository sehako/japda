package io.github.sehako.japda.auth.infrastructure.token

import com.nimbusds.jose.jwk.source.ImmutableSecret
import java.time.Clock
import java.time.Duration
import javax.crypto.SecretKey
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder

class ServiceJwtIssuer(
    key: SecretKey,
    private val issuer: String,
    private val audience: String,
    private val clock: Clock,
) {
    private val encoder = NimbusJwtEncoder(ImmutableSecret(key))

    fun issue(userId: Long): String {
        val issuedAt = clock.instant()
        val claims = JwtClaimsSet.builder()
            .subject(userId.toString())
            .issuer(issuer)
            .audience(listOf(audience))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(TTL))
            .build()
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
    }

    companion object {
        val TTL: Duration = Duration.ofHours(1)
    }
}
