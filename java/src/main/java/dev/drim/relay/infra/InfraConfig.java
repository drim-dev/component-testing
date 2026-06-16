package dev.drim.relay.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import dev.drim.relay.seams.FeedProjector;
import dev.drim.relay.seams.NotificationRecorder;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.net.URI;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The infrastructure composition root: builds the live broker / object-store / gRPC clients the
 * CORRECT seams wire from, and starts the two background workers (feed-fanout consumer +
 * notification worker) with the SAME seams the app uses. Connection URLs are supplied
 * per-environment (the test harness wires Testcontainers); Redis, JPA, and Flyway are Spring Boot
 * auto-config.
 *
 * <p>Mirrors the wiring in go/src/relay/app/build.go — every seam is a plain constructor call, the
 * one place all the bricks meet. A test swaps exactly one correct seam for its naive variant by
 * overriding that one bean (the {@code @TestConfiguration} {@code @Primary} override).
 */
@Configuration
public class InfraConfig {

  @Bean
  public MessagePostedCodec messagePostedCodec(ObjectMapper mapper) {
    return new MessagePostedCodec(mapper);
  }

  @Bean
  public NotificationJobCodec notificationJobCodec(ObjectMapper mapper) {
    return new NotificationJobCodec(mapper);
  }

  @Bean(destroyMethod = "close")
  public Producer<String, byte[]> kafkaProducer(
      @Value("${relay.kafka.bootstrap-servers}") String bootstrap) {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
    props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
    return new KafkaProducer<>(props);
  }

  // destroyMethod="" disables Spring's auto-inferred close(): the FeedConsumer worker thread owns
  // the consumer and closes it when it exits. KafkaConsumer is single-threaded, so a Spring
  // shutdown-hook close on another thread would race the worker's close
  // (ConcurrentModificationException).
  @Bean(destroyMethod = "")
  public KafkaConsumer<String, byte[]> kafkaConsumer(
      @Value("${relay.kafka.bootstrap-servers}") String bootstrap,
      @Value("${relay.kafka.feed-group:feed-fanout}") String group) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    return new KafkaConsumer<>(props);
  }

  @Bean(destroyMethod = "close")
  public Connection rabbitConnection(@Value("${relay.rabbit.uri}") String uri) throws Exception {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setUri(uri);
    return factory.newConnection();
  }

  @Bean(destroyMethod = "shutdown")
  public ManagedChannel presenceChannel(@Value("${relay.presence.target}") String target) {
    return ManagedChannelBuilder.forTarget(target).usePlaintext().build();
  }

  @Bean
  public software.amazon.awssdk.services.s3.S3Client s3Client(
      @Value("${relay.s3.endpoint}") String endpoint,
      @Value("${relay.s3.access-key}") String accessKey,
      @Value("${relay.s3.secret-key}") String secretKey,
      @Value("${relay.s3.region:us-east-1}") String region) {
    return software.amazon.awssdk.services.s3.S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(software.amazon.awssdk.regions.Region.of(region))
        .credentialsProvider(
            software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                    accessKey, secretKey)))
        .serviceConfiguration(
            software.amazon.awssdk.services.s3.S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build())
        .build();
  }

  @Bean
  public BrokerWorkers brokerWorkers(
      KafkaConsumer<String, byte[]> consumer,
      FeedProjector projector,
      MessagePostedCodec messageCodec,
      Connection rabbitConnection,
      NotificationRecorder recorder,
      NotificationJobCodec jobCodec,
      @Value("${relay.rabbit.notify-queue:notify.dm}") String notifyQueue,
      @Value("${relay.kafka.feed-topic:message-posted}") String feedTopic) {
    FeedConsumer feed = new FeedConsumer(consumer, projector, messageCodec, feedTopic);
    NotificationWorker notify =
        new NotificationWorker(rabbitConnection, recorder, jobCodec, notifyQueue);
    return new BrokerWorkers(feed, notify);
  }
}
