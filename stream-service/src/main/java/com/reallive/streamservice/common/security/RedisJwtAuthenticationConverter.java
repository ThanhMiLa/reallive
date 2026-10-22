package com.reallive.streamservice.common.security;

import com.reallive.streamservice.common.adapter.redis.RedisAuthoritiesReader;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;

@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class RedisJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    RedisAuthoritiesReader authoritiesReader;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        var authorities = new ArrayList<>(new RedisJwtGrantedAuthoritiesConverter(authoritiesReader).convert(jwt));
        authorities.add(FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.BEARER_AUTHORITY));
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
