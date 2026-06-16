package infra

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/seams"
	"github.com/redis/go-redis/v9"
)

const (
	unfurlTimeout       = 800 * time.Millisecond
	breakerThreshold    = 5
	breakerWindow       = 30 * time.Second
	breakerFailuresKey  = "unfurl:breaker:failures"
	breakerOpenUntilKey = "unfurl:breaker:open_until"
)

// LinkPreviewer is the correct G-HTTP seam: a REAL HTTP client with an 800 ms timeout that
// degrades gracefully (timeout / 5xx / network error → nil title, never escapes), behind a
// circuit breaker (5 consecutive failures → skip the call for 30 s). Breaker state lives in
// Redis (resettable by the harness FLUSHDB). The naive variant has no timeout/guard.
type LinkPreviewer struct {
	http    *http.Client
	redis   *redis.Client
	baseURL string
}

func NewLinkPreviewer(redisClient *redis.Client, baseURL string) *LinkPreviewer {
	return &LinkPreviewer{
		http:    &http.Client{Timeout: unfurlTimeout},
		redis:   redisClient,
		baseURL: baseURL,
	}
}

func (p *LinkPreviewer) Preview(ctx context.Context, target string) (*string, error) {
	open, err := p.breakerOpen(ctx)
	if err != nil {
		return nil, err
	}
	if open {
		return nil, nil // breaker open: skip the call, degrade to no preview
	}

	title, fetchErr := p.fetch(ctx, target)
	if fetchErr != nil {
		if err := p.recordFailure(ctx); err != nil {
			return nil, err
		}
		return nil, nil // graceful degradation — never surface the upstream failure here
	}
	if err := p.recordSuccess(ctx); err != nil {
		return nil, err
	}
	return title, nil
}

func (p *LinkPreviewer) fetch(ctx context.Context, target string) (*string, error) {
	reqCtx, cancel := context.WithTimeout(ctx, unfurlTimeout)
	defer cancel()
	endpoint := p.baseURL + "/unfurl?url=" + url.QueryEscape(target)
	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	resp, err := p.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, errors.New("unfurl upstream returned " + strconv.Itoa(resp.StatusCode))
	}
	var payload struct {
		Title string `json:"title"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return nil, err
	}
	return &payload.Title, nil
}

func (p *LinkPreviewer) breakerOpen(ctx context.Context) (bool, error) {
	raw, err := p.redis.Get(ctx, breakerOpenUntilKey).Result()
	if errors.Is(err, redis.Nil) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	openUntil, _ := strconv.ParseInt(raw, 10, 64)
	return time.UnixMilli(openUntil).After(time.Now()), nil
}

func (p *LinkPreviewer) recordSuccess(ctx context.Context) error {
	return p.redis.Del(ctx, breakerFailuresKey).Err()
}

func (p *LinkPreviewer) recordFailure(ctx context.Context) error {
	failures, err := p.redis.Incr(ctx, breakerFailuresKey).Result()
	if err != nil {
		return err
	}
	if failures >= breakerThreshold {
		openUntil := time.Now().Add(breakerWindow).UnixMilli()
		return p.redis.Set(ctx, breakerOpenUntilKey, openUntil, breakerWindow).Err()
	}
	return nil
}

var _ seams.LinkPreviewer = (*LinkPreviewer)(nil)
