package dev.drim.relay.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.drim.relay.domain.Events;

/**
 * The message-posted event codec — pure (de)serialization shared by the Kafka publisher and the
 * feed-fanout consumer. The JSON wire shape is the cross-language contract on {@link
 * Events.MessagePosted}. Mirrors SerializeMessagePosted/DeserializeMessagePosted in
 * go/src/relay/infra/kafka.go.
 */
public final class MessagePostedCodec {
  private final ObjectMapper mapper;

  public MessagePostedCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public byte[] serialize(Events.MessagePosted event) {
    try {
      return mapper.writeValueAsBytes(event);
    } catch (Exception e) {
      throw new IllegalStateException("failed to serialize MessagePosted", e);
    }
  }

  public Events.MessagePosted deserialize(byte[] bytes) {
    try {
      return mapper.readValue(bytes, Events.MessagePosted.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("failed to deserialize MessagePosted", e);
    }
  }
}
