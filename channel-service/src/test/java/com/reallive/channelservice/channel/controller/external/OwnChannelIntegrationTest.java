package com.reallive.channelservice.channel.controller.external;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.reallive.channelservice.common.adapter.redis.RedisAuthoritiesReader;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "server.servlet.context-path=/channel",
        "spring.datasource.url=jdbc:h2:mem:own_channel_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OwnChannelIntegrationTest {

    private static final String PATH = "/channel/api/channels/me";

    @MockitoBean
    private RedisAuthoritiesReader authorities;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void allowOwnChannelView() {
        when(authorities.read(any())).thenReturn(List.of(new SimpleGrantedAuthority("CHANNEL_VIEW_OWN")));
    }

    @Test
    void returnsOnlyTheAuthenticatedOwnersChannelWithOrderedCategorySummaries() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID channelId = insertChannel(owner, "BANNED", null);
        UUID otherChannelId = insertChannel(UUID.randomUUID(), "ACTIVE", null);
        UUID zulu = insertCategory("Zulu", "INACTIVE");
        UUID alpha = insertCategory("Alpha", "ACTIVE");
        link(channelId, zulu);
        link(channelId, alpha);
        link(otherChannelId, insertCategory("Other", "ACTIVE"));

        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(owner.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result", aMapWithSize(10)))
                .andExpect(jsonPath("$.result.id").value(channelId.toString()))
                .andExpect(jsonPath("$.result.streamerId").isNotEmpty())
                .andExpect(jsonPath("$.result.name").value("My channel"))
                .andExpect(jsonPath("$.result.slug").value(channelId.toString()))
                .andExpect(jsonPath("$.result.description").value("About me"))
                .andExpect(jsonPath("$.result.avatarUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.result.bannerUrl").value("https://example.com/banner.png"))
                .andExpect(jsonPath("$.result.status").value("BANNED"))
                .andExpect(jsonPath("$.result.numberOfFollower").value(42))
                .andExpect(jsonPath("$.result.categories", hasSize(2)))
                .andExpect(jsonPath("$.result.categories[0]", aMapWithSize(2)))
                .andExpect(jsonPath("$.result.categories[0].id").value(alpha.toString()))
                .andExpect(jsonPath("$.result.categories[0].name").value("Alpha"))
                .andExpect(jsonPath("$.result.categories[1].id").value(zulu.toString()))
                .andExpect(jsonPath("$.result.categories[1].name").value("Zulu"));
    }

    @Test
    void returnsEmptyCategoriesWhenNoneAreAssigned() throws Exception {
        UUID owner = UUID.randomUUID();
        insertChannel(owner, "ACTIVE", null);

        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(owner.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.categories", hasSize(0)));
    }

    @Test
    void rejectsMissingAndSoftDeletedChannels() throws Exception {
        UUID owner = UUID.randomUUID();
        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(owner.toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1009));

        insertChannel(owner, "ACTIVE", LocalDateTime.now());
        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(owner.toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1009));
    }

    @Test
    void requiresAuthenticationAndOwnChannelPermission() throws Exception {
        mvc.perform(get(PATH).contextPath("/channel"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie("invalid-user-id")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1001));

        when(authorities.read(any())).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_STREAMER")));
        mvc.perform(get(PATH).contextPath("/channel").cookie(accessCookie(UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
    }

    private UUID insertChannel(UUID owner, String status, LocalDateTime deletedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO channels (id, streamer_id, user_id, name, slug, description, avatar_url, " +
                        "banner_url, status, follower_count, deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, UUID.randomUUID(), owner, "My channel", id.toString(), "About me",
                "https://example.com/avatar.png", "https://example.com/banner.png", status, 42L, deletedAt);
        return id;
    }

    private UUID insertCategory(String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, name, slug, status) VALUES (?, ?, ?, ?)",
                id, name, id.toString(), status);
        return id;
    }

    private void link(UUID channelId, UUID categoryId) {
        jdbc.update("INSERT INTO channel_categories (channel_id, category_id) VALUES (?, ?)", channelId, categoryId);
    }

    private Cookie accessCookie(String subject) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject(subject).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300))).build();
        var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signed.sign(new MACSigner("own-channel-test-secret-".repeat(4)));
        return new Cookie("access_token", signed.serialize());
    }
}
