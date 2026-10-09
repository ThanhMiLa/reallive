package com.reallive.userservice.user.controller;

import com.reallive.userservice.auth.entity.UserEntity;
import com.reallive.userservice.auth.repository.UserRepository;
import com.reallive.userservice.common.security.CustomJwtDecoder;
import com.reallive.userservice.user.dto.message.EmailVerificationRequestedMessage;
import com.reallive.userservice.user.util.EmailVerificationOtpHasher;
import jakarta.servlet.http.Cookie;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:email_otp_flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.open-in-view=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "email-verification.otp.hmac-key=isolated-integration-test-secret",
        "email-verification.topic=test.user.email-verification.requested",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.acks=all",
        "spring.kafka.producer.properties.enable.idempotence=true",
        "spring.kafka.producer.properties.request.timeout.ms=5000",
        "spring.kafka.producer.properties.delivery.timeout.ms=15000",
        "spring.kafka.producer.properties.max.block.ms=5000",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.database=15"
})
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = "test.user.email-verification.requested",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@EnabledIfSystemProperty(named = "user.test.redis-port", matches = "[0-9]+")
@DirtiesContext
class EmailVerificationOtpIntegrationTest {

    private static final String TOPIC = "test.user.email-verification.requested";
    private static final String ENDPOINT = "/api/users/me/email-verification/otp";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository repository;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private EmbeddedKafkaBroker broker;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private EmailVerificationOtpHasher hasher;
    @MockitoBean
    private CustomJwtDecoder decoder;

    private MockMvc mvc;
    private UserEntity user;

    @DynamicPropertySource
    static void redisPort(DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.port", () -> System.getProperty("user.test.redis-port"));
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        user = repository.saveAndFlush(UserEntity.builder().username("flow-" + UUID.randomUUID())
                .email(UUID.randomUUID() + "@example.com").passwordHash("test-hash").build());
        when(decoder.decode("flow-token")).thenReturn(Jwt.withTokenValue("flow-token").header("alg", "HS512")
                .subject(user.getId().toString()).claim("scope", "USER").build());
    }

    @AfterEach
    void cleanUp() {
        redis.delete(key());
    }

    @Test
    void acceptedHttpRequestPublishesMatchingOtpAndCooldownDoesNotSendAgain() throws Exception {
        var properties = KafkaTestUtils.consumerProps(broker, "otp-http-" + UUID.randomUUID(), false);
        try (var consumer = new DefaultKafkaConsumerFactory<>(properties,
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            broker.consumeFromEmbeddedTopics(consumer, true, TOPIC);
            var response = mvc.perform(post(ENDPOINT).cookie(cookie())).andReturn().getResponse();
            assertEquals(202, response.getStatus());
            var record = KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(10));
            var message = mapper.readValue(record.value(), EmailVerificationRequestedMessage.class);
            assertEquals(user.getId().toString(), record.key());
            assertNull(record.headers().lastHeader("__TypeId__"));
            assertEquals(user.getEmail(), message.getRecipientEmail());
            assertTrue(message.getOtp().matches("[0-9]{6}"));
            assertEquals(hasher.hash(user.getId(), user.getEmail(), message.getOtp()), redis.opsForValue().get(key()));
            assertEquals(message.getExpiresAt().toString(),
                    mapper.readTree(response.getContentAsString()).get("result").get("expiresAt").asText());
            long ttl = redis.getExpire(key(), TimeUnit.MILLISECONDS);
            assertTrue(ttl > 0 && ttl <= 60000);
            String storedHash = redis.opsForValue().get(key());

            assertEquals(429, mvc.perform(post(ENDPOINT).cookie(cookie())).andReturn().getResponse().getStatus());

            assertEquals(storedHash, redis.opsForValue().get(key()));
            assertTrue(redis.getExpire(key(), TimeUnit.MILLISECONDS) <= ttl);
            assertTrue(consumer.poll(Duration.ofMillis(500)).isEmpty());
            assertFalse(repository.findById(user.getId()).orElseThrow().isEmailVerified());
        }
    }

    @Test
    void concurrentHttpRequestsProduceExactlyOneMessage() throws Exception {
        var properties = KafkaTestUtils.consumerProps(broker, "otp-race-" + UUID.randomUUID(), false);
        try (var consumer = new DefaultKafkaConsumerFactory<>(properties,
                new StringDeserializer(), new StringDeserializer()).createConsumer();
                var executor = Executors.newFixedThreadPool(2)) {
            broker.consumeFromEmbeddedTopics(consumer, true, TOPIC);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> requestAfterBarrier(ready, start));
            var second = executor.submit(() -> requestAfterBarrier(ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> statuses = List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS));
            assertTrue(statuses.contains(202) && statuses.contains(429));
            var record = KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(10));
            assertEquals(user.getId().toString(), record.key());
            assertTrue(consumer.poll(Duration.ofMillis(500)).isEmpty());
            assertFalse(repository.findById(user.getId()).orElseThrow().isEmailVerified());
        }
    }

    private int requestAfterBarrier(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return mvc.perform(post(ENDPOINT).cookie(cookie())).andReturn().getResponse().getStatus();
    }

    private Cookie cookie() {
        return new Cookie("access_token", "flow-token");
    }

    private String key() {
        return "otp:email-verification:cooldown:" + user.getId();
    }
}
