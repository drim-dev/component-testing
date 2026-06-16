package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.domain.Events;
import dev.drim.relay.seams.AttachmentAccess;
import dev.drim.relay.seams.ChannelReadGate;
import dev.drim.relay.seams.ChannelRoleGate;
import dev.drim.relay.seams.ConversationWriter;
import dev.drim.relay.seams.FeedProjector;
import dev.drim.relay.seams.MembershipWriter;
import dev.drim.relay.seams.MessagePostedPublisher;
import dev.drim.relay.seams.NotificationRecorder;
import dev.drim.relay.seams.PresenceClient;
import dev.drim.relay.seams.Seams.ConversationCreateResult;
import dev.drim.relay.seams.Seams.PresenceResult;
import dev.drim.relay.seams.Summarizer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LYING TESTS (exhibits, do not copy) — the remaining gallery cases' default-shaped
 * green-but-useless tests, each paired with its catcher:
 *
 * <ul>
 *   <li>G-BOLA-READ — caught by ChannelsTest S-CH-05/21 + GBolaReadNaiveDemoTest
 *   <li>G-BOLA-ROLE — caught by ChannelsTest S-CH-11/15/19 + GBolaRoleNaiveDemoTest
 *   <li>G-CACHE — caught by ChannelsTest S-CH-16 / FeedTest S-FD-06 + GCacheNaiveDemoTest
 *   <li>G-RACE — caught by DirectMessagesTest S-DM-05 + GRaceNaiveDemoTest
 *   <li>G-TX — caught by DirectMessagesTest S-DM-06 + GTxNaiveDemoTest
 *   <li>G-RABBIT — caught by NotificationsTest S-NT-02/03/04 + GRabbitNaiveDemoTest
 *   <li>G-KAFKA producer — caught by FeedTest S-FD-01 + GKafkaProducerNaiveDemoTest
 *   <li>G-KAFKA consumer — caught by FeedTest S-FD-05 + GKafkaConsumerNaiveDemoTest
 *   <li>G-S3 — caught by AttachmentsTest S-AT-06/07 + GS3NaiveDemoTest
 *   <li>G-LLM — caught by SummaryTest S-SM-03/04/05 + GLlmNaiveDemoTest
 *   <li>G-HTTP — caught by LinkPreviewTest S-LP-02/03/04 + GHttpNaiveDemoTest
 *   <li>G-GRPC — caught by PresenceTest S-PR-04 + GGrpcNaiveDemoTest
 * </ul>
 *
 * <p>Every method here is GREEN by construction — it mocks the seam to the desired answer (lift #2:
 * stubbed guard) or asserts the call was made rather than the outcome (lift #3: verify-the-call).
 * The point of the gallery is that these pass against the naive variant too; only the
 * assembled-system catching tests + naive demos detect the real bug.
 */
class GalleryLyingTests {

  @Test
  @DisplayName(
      "G-BOLA-READ LYING: mock read gate returns a public channel → green, never checks private")
  void bolaReadStubbed() {
    ChannelReadGate gate = mock(ChannelReadGate.class);
    Entities.Channel ch = new Entities.Channel("c1", "n", false, Instant.now());
    when(gate.authorizeRead(anyString(), anyString(), anyBooleanLike())).thenReturn(ch);
    assertThat(gate.authorizeRead("c1", "intruder", false)).isEqualTo(ch);
  }

  @Test
  @DisplayName(
      "G-BOLA-ROLE LYING: hand-built admin member → green, the test constructs the authority")
  void bolaRoleSelfGranted() {
    ChannelRoleGate gate = mock(ChannelRoleGate.class);
    Entities.ChannelMember fakeAdmin =
        new Entities.ChannelMember("c1", "u1", dev.drim.relay.domain.Role.ADMIN, Instant.now());
    when(gate.authorizeRole(anyString(), anyString(), any())).thenReturn(fakeAdmin);
    assertThat(gate.authorizeRole("c1", "u1", dev.drim.relay.domain.Role.ADMIN).role())
        .isEqualTo(dev.drim.relay.domain.Role.ADMIN);
  }

  @Test
  @DisplayName("G-CACHE LYING: verify remove() was called → green, never checks the cache state")
  void cacheVerifyTheCall() {
    MembershipWriter writer = mock(MembershipWriter.class);
    writer.remove("c1", "u1");
    verify(writer).remove("c1", "u1"); // proves the call, not that the cache was invalidated
  }

  @Test
  @DisplayName("G-RACE LYING: single create returns one id → green, never exercises concurrency")
  void raceSingleThreaded() {
    ConversationWriter writer = mock(ConversationWriter.class);
    Entities.Conversation conv = new Entities.Conversation("c1", "a", "b", Instant.now());
    when(writer.create("a", "b")).thenReturn(new ConversationCreateResult(conv, true));
    assertThat(writer.create("a", "b").conversation().id()).isEqualTo("c1");
  }

  @Test
  @DisplayName(
      "G-TX LYING: verify three saves were called → green, never injects a mid-write fault")
  void txVerifyTheCalls() {
    ConversationWriter writer = mock(ConversationWriter.class);
    Entities.Conversation conv = new Entities.Conversation("c1", "a", "b", Instant.now());
    when(writer.create("a", "b")).thenReturn(new ConversationCreateResult(conv, true));
    assertThat(writer.create("a", "b").created()).isTrue(); // proves the happy path, not rollback
  }

  @Test
  @DisplayName("G-RABBIT LYING: record() returns normally on a duplicate → green, never replays")
  void rabbitNoReplay() {
    NotificationRecorder recorder = mock(NotificationRecorder.class);
    Events.NotificationJob job = new Events.NotificationJob("m1", "c1", "a", "b", "p");
    recorder.record(job);
    verify(recorder)
        .record(job); // proves one call, says nothing about idempotency under redelivery
  }

  @Test
  @DisplayName("G-KAFKA producer LYING: verify send was called → green, never awaits a down broker")
  void kafkaProducerVerifyTheCall() {
    MessagePostedPublisher publisher = mock(MessagePostedPublisher.class);
    Events.MessagePosted event = new Events.MessagePosted("m1", "c1", "a", "p", Instant.now());
    publisher.publish(event);
    verify(publisher).publish(event); // the call, not the broker-confirmation behavior
  }

  @Test
  @DisplayName("G-KAFKA consumer LYING: apply() once → green, never replays to check idempotency")
  void kafkaConsumerSingleApply() {
    FeedProjector projector = mock(FeedProjector.class);
    Events.MessagePosted event = new Events.MessagePosted("m1", "c1", "a", "p", Instant.now());
    projector.apply(event);
    verify(projector).apply(event); // one apply; divergence shows only on the second delivery
  }

  @Test
  @DisplayName("G-S3 LYING: mock access returns the attachment → green, never checks membership")
  void s3Stubbed() {
    AttachmentAccess access = mock(AttachmentAccess.class);
    Entities.Attachment att =
        new Entities.Attachment("a1", "c1", "u1", null, "f", 1, "c1/a1", Instant.now());
    when(access.authorize(anyString(), anyString())).thenReturn(att);
    assertThat(access.authorize("a1", "intruder")).isEqualTo(att);
  }

  @Test
  @DisplayName(
      "G-LLM LYING: summarizer returns the canned string → green, never inspects the request")
  void llmStubbed() {
    Summarizer summarizer = mock(Summarizer.class);
    when(summarizer.summarize(any())).thenReturn("a summary");
    assertThat(summarizer.summarize(List.of())).isEqualTo("a summary");
  }

  @Test
  @DisplayName(
      "G-HTTP LYING: previewer returns a title → green, never exercises a failing upstream")
  void httpStubbed() {
    dev.drim.relay.seams.LinkPreviewer previewer = mock(dev.drim.relay.seams.LinkPreviewer.class);
    when(previewer.preview(anyString())).thenReturn(Optional.of("Example"));
    assertThat(previewer.preview("http://x")).contains("Example");
  }

  @Test
  @DisplayName(
      "G-GRPC LYING: client returns a full list marked complete → green, never aborts mid-stream")
  void grpcStubbed() {
    PresenceClient client = mock(PresenceClient.class);
    when(client.channelPresence(any()))
        .thenReturn(new PresenceResult(List.of(new Events.PresenceStatus("u1", true)), false));
    assertThat(client.channelPresence(List.of("u1")).incomplete()).isFalse();
  }

  @Test
  @DisplayName("G-TAUT LYING: stub repo returns a message, assert it equals the stub (tautology)")
  void tautologicalMock() {
    @SuppressWarnings("unchecked")
    java.util.function.Function<String, Entities.DmMessage> repo =
        mock(java.util.function.Function.class);
    Entities.DmMessage msg = new Entities.DmMessage("m1", "c1", "a", "hi", Instant.now());
    when(repo.apply("m1")).thenReturn(msg);
    // The assertion mirrors the stub exactly — it can only ever pass; it tests the mock, not the
    // app.
    assertThat(repo.apply("m1").text()).isEqualTo("hi");
  }

  @Test
  @DisplayName("G-WEAKVAL LYING: assert a relaxed limit boundary the spec forbids (axis-1 gaming)")
  void weakenedValidation() {
    // The lie: the test pins limit<=200 (the gamed boundary), so it stays green even though the
    // spec
    // pins 1..100 — the catching pins S-PG-01..04 assert the real boundary through the HTTP route.
    int gamedMax = 200;
    assertThat(101).isLessThanOrEqualTo(gamedMax);
  }

  private static boolean anyBooleanLike() {
    return org.mockito.ArgumentMatchers.anyBoolean();
  }
}
