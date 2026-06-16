package dev.drim.relay.app;

import dev.drim.relay.domain.Role;
import dev.drim.relay.seams.ChannelRoleGate;
import dev.drim.relay.seams.Seams.SummarySource;
import dev.drim.relay.seams.Summarizer;
import dev.drim.relay.store.ChannelMessageRepository;
import dev.drim.relay.store.UserRepository;
import dev.drim.relay.store.entity.ChannelMessageRow;
import dev.drim.relay.web.ApiException;
import dev.drim.relay.web.CurrentUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Limit;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI channel summary (02-api.md §10): role gate (member+), gather the newest messages
 * oldest-first with resolved sender handles, then hand them to the {@link Summarizer} seam — which
 * owns the prompt assembly and output validation (G-LLM). The handler never builds a prompt string.
 */
@RestController
public class SummaryController {
  private final CurrentUser currentUser;
  private final ChannelRoleGate roleGate;
  private final ChannelMessageRepository messages;
  private final UserRepository users;
  private final Summarizer summarizer;

  public SummaryController(
      CurrentUser currentUser,
      ChannelRoleGate roleGate,
      ChannelMessageRepository messages,
      UserRepository users,
      Summarizer summarizer) {
    this.currentUser = currentUser;
    this.roleGate = roleGate;
    this.messages = messages;
    this.users = users;
    this.summarizer = summarizer;
  }

  public record SummaryBody(Integer messageLimit) {}

  @PostMapping("/channels/{id}/summary")
  public Map<String, String> summary(
      @PathVariable String id, @RequestBody(required = false) SummaryBody body) {
    roleGate.authorizeRole(id, currentUser.id(), Role.MEMBER);

    int limit = SummaryPrompt.DEFAULT_MESSAGE_LIMIT;
    if (body != null && body.messageLimit() != null) {
      limit = body.messageLimit();
      if (limit < 1 || limit > 200) {
        throw ApiException.invalid(
            "summary:message_limit:out_of_range", "messageLimit must be 1–200.");
      }
    }

    List<ChannelMessageRow> newest = messages.newest(id, Limit.of(limit));
    if (newest.isEmpty()) {
      throw ApiException.invalid("summary:no_messages", "There is nothing to summarize.");
    }

    List<SummarySource> sources = new ArrayList<>(newest.size());
    for (int i = newest.size() - 1; i >= 0; i--) {
      ChannelMessageRow m = newest.get(i);
      String handle =
          users.findById(m.getSenderId()).map(u -> u.toDomain().handle()).orElse(m.getSenderId());
      sources.add(new SummarySource(handle, m.getText()));
    }

    return Map.of("summary", summarizer.summarize(sources));
  }
}
