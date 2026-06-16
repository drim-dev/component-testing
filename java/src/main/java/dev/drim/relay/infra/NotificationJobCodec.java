package dev.drim.relay.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.drim.relay.domain.Events;

/**
 * The notification-job codec — pure (de)serialization shared by the publisher and the worker. The
 * JSON wire shape is the cross-language contract on {@link Events.NotificationJob}. Mirrors
 * SerializeJob/DeserializeJob in go/src/relay/infra/rabbit.go.
 */
public final class NotificationJobCodec {
  private final ObjectMapper mapper;

  public NotificationJobCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public byte[] serialize(Events.NotificationJob job) {
    try {
      return mapper.writeValueAsBytes(job);
    } catch (Exception e) {
      throw new IllegalStateException("failed to serialize NotificationJob", e);
    }
  }

  public Events.NotificationJob deserialize(byte[] bytes) {
    try {
      return mapper.readValue(bytes, Events.NotificationJob.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("failed to deserialize NotificationJob", e);
    }
  }
}
