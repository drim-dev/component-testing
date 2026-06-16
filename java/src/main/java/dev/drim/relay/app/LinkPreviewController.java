package dev.drim.relay.app;

import dev.drim.relay.domain.Previews;
import dev.drim.relay.seams.LinkPreviewer;
import dev.drim.relay.web.ApiException;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The synchronous unfurl proxy — the only outbound-HTTP critical path (02-api.md §6). Unlike the
 * post-time unfurl, an upstream failure here surfaces as 502 (the caller asked for the title
 * directly), not graceful degradation.
 */
@RestController
public class LinkPreviewController {
  private final LinkPreviewer linkPreviewer;

  public LinkPreviewController(LinkPreviewer linkPreviewer) {
    this.linkPreviewer = linkPreviewer;
  }

  @GetMapping("/links/preview")
  public Map<String, String> preview(@RequestParam(required = false) String url) {
    if (url == null || url.isBlank() || Previews.firstUrl(url) == null) {
      throw ApiException.invalid("unfurl:url:invalid", "A valid http(s) url is required.");
    }
    Optional<String> title = linkPreviewer.preview(url);
    if (title.isEmpty()) {
      throw ApiException.upstream("unfurl:upstream_failed", "The unfurl upstream failed.");
    }
    return Map.of("title", title.get());
  }
}
