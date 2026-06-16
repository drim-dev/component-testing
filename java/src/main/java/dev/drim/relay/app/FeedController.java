package dev.drim.relay.app;

import dev.drim.relay.seams.UnreadCounters;
import dev.drim.relay.store.FeedEntryRepository;
import dev.drim.relay.store.NotificationRepository;
import dev.drim.relay.store.entity.FeedEntryRow;
import dev.drim.relay.web.CurrentUser;
import dev.drim.relay.web.Paging;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Limit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's notifications feed, channel feed, and per-channel unread counts (02-api.md §5). */
@RestController
public class FeedController {
  private final CurrentUser currentUser;
  private final NotificationRepository notifications;
  private final FeedEntryRepository feed;
  private final UnreadCounters unread;

  public FeedController(
      CurrentUser currentUser,
      NotificationRepository notifications,
      FeedEntryRepository feed,
      UnreadCounters unread) {
    this.currentUser = currentUser;
    this.notifications = notifications;
    this.feed = feed;
    this.unread = unread;
  }

  public record NotificationDto(
      String id,
      String type,
      String dmMessageId,
      String conversationId,
      String senderId,
      String preview,
      Instant createdAt) {}

  public record FeedEntryDto(
      String channelId, String messageId, String senderId, String preview, Instant createdAt) {}

  @GetMapping("/notifications")
  public Paging.Page<NotificationDto> notifications(
      @RequestParam(required = false) String limit, @RequestParam(required = false) String before) {
    int parsedLimit = Paging.parseLimit(limit);
    List<NotificationDto> rows =
        notifications.page(currentUser.id(), emptyToNull(before), fetchLimit(parsedLimit)).stream()
            .map(
                n ->
                    new NotificationDto(
                        n.getId(),
                        "dm.message",
                        n.getDmMessageId(),
                        n.getConversationId(),
                        n.getSenderId(),
                        n.getPreview(),
                        n.getCreatedAt()))
            .toList();
    return Paging.build(rows, parsedLimit, NotificationDto::id);
  }

  @GetMapping("/feed")
  public Paging.Page<FeedEntryDto> feed(
      @RequestParam(required = false) String limit, @RequestParam(required = false) String before) {
    int parsedLimit = Paging.parseLimit(limit);
    List<FeedEntryRow> rows =
        feed.page(currentUser.id(), emptyToNull(before), fetchLimit(parsedLimit));
    // The cursor (feed_entries.id) is not part of the DTO shape, so it is carried alongside.
    if (rows.size() > parsedLimit) {
      List<FeedEntryRow> kept = rows.subList(0, parsedLimit);
      String next = kept.get(kept.size() - 1).getId();
      return new Paging.Page<>(kept.stream().map(FeedController::toFeedDto).toList(), next);
    }
    return new Paging.Page<>(rows.stream().map(FeedController::toFeedDto).toList(), null);
  }

  @GetMapping("/me/unread")
  public Map<String, Object> unread() {
    Map<String, Long> counts = unread.forUser(currentUser.id());
    return Map.of("channels", counts == null ? Map.of() : counts);
  }

  private static FeedEntryDto toFeedDto(FeedEntryRow f) {
    return new FeedEntryDto(
        f.getChannelId(), f.getMessageId(), f.getSenderId(), f.getPreview(), f.getCreatedAt());
  }

  private static String emptyToNull(String s) {
    return (s == null || s.isEmpty()) ? null : s;
  }

  private static Limit fetchLimit(int limit) {
    return Limit.of(limit + 1);
  }
}
