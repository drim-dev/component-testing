package app

import (
	"net/http"
	"strings"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/apierr"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
)

// getLinkPreview is the synchronous unfurl proxy — the only outbound-HTTP critical path
// (02-api.md §6). Unlike the post-time unfurl, an upstream failure here surfaces as 502
// (the caller asked for the title directly), not graceful degradation.
func (a *App) getLinkPreview(w http.ResponseWriter, r *http.Request) {
	url := r.URL.Query().Get("url")
	if strings.TrimSpace(url) == "" || domain.FirstURL(url) == "" {
		apierr.Write(w, apierr.Invalid("unfurl:url:invalid", "A valid http(s) url is required."))
		return
	}
	title, err := a.d.LinkPreviewer.Preview(r.Context(), url)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if title == nil {
		apierr.Write(w, apierr.Upstream("unfurl:upstream_failed", "The unfurl upstream failed."))
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"title": *title})
}
