package airetrieval

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"strings"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpx"
)

const maximumRuntimeResponseBytes int64 = 4 << 20

type RuntimeClientSettings struct {
	BaseURL    string
	Token      string
	HTTPClient *http.Client
}

type RuntimeClient struct {
	endpoint   string
	token      string
	httpClient *http.Client
}

type runtimeRequest struct {
	Query string `json:"query"`
	TopK  int    `json:"top_k"`
}

type runtimeResponse struct {
	Matches []runtimeMatch `json:"matches"`
}

type runtimeMatch struct {
	ChunkID   string  `json:"chunk_id"`
	Title     string  `json:"title"`
	Heading   string  `json:"heading"`
	Excerpt   string  `json:"excerpt"`
	SourceURL string  `json:"source_url"`
	Score     float64 `json:"score"`
}

func NewRuntimeClient(settings RuntimeClientSettings) (*RuntimeClient, error) {
	parsed, err := url.Parse(strings.TrimSpace(settings.BaseURL))
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return nil, fmt.Errorf("AI runtime base URL must be an absolute HTTP URL")
	}
	if strings.TrimSpace(settings.Token) == "" {
		return nil, fmt.Errorf("AI runtime token is required")
	}
	if settings.HTTPClient == nil {
		return nil, fmt.Errorf("AI runtime HTTP client is required")
	}
	endpoint, err := url.JoinPath(strings.TrimRight(settings.BaseURL, "/"), "internal/v1/retrievals")
	if err != nil {
		return nil, fmt.Errorf("construct AI runtime URL: %w", err)
	}
	return &RuntimeClient{
		endpoint:   endpoint,
		token:      strings.TrimSpace(settings.Token),
		httpClient: settings.HTTPClient,
	}, nil
}

func (client *RuntimeClient) Retrieve(ctx context.Context, request Request) (Result, error) {
	payload, err := json.Marshal(runtimeRequest{Query: request.Query, TopK: request.TopK})
	if err != nil {
		return Result{}, fmt.Errorf("marshal AI runtime request: %w", err)
	}
	httpRequest, err := http.NewRequestWithContext(ctx, http.MethodPost, client.endpoint, bytes.NewReader(payload))
	if err != nil {
		return Result{}, fmt.Errorf("construct AI runtime request: %w", err)
	}
	httpRequest.Header.Set("Accept", "application/json")
	httpRequest.Header.Set("Authorization", "Bearer "+client.token)
	httpRequest.Header.Set("Content-Type", "application/json")
	if requestID := httpx.RequestID(ctx); requestID != "" {
		httpRequest.Header.Set(httpx.RequestIDHeader, requestID)
	}

	response, err := client.httpClient.Do(httpRequest)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return Result{}, ErrTimeout
		}
		return Result{}, fmt.Errorf("call AI runtime: %w", ErrUnavailable)
	}
	defer response.Body.Close()

	switch response.StatusCode {
	case http.StatusOK:
	case http.StatusTooManyRequests:
		return Result{}, ErrBusy
	case http.StatusGatewayTimeout:
		return Result{}, ErrTimeout
	case http.StatusUnauthorized, http.StatusForbidden, http.StatusServiceUnavailable:
		return Result{}, ErrUnavailable
	default:
		return Result{}, fmt.Errorf("AI runtime returned status %d: %w", response.StatusCode, ErrUnavailable)
	}

	limited := io.LimitReader(response.Body, maximumRuntimeResponseBytes+1)
	body, err := io.ReadAll(limited)
	if err != nil {
		return Result{}, fmt.Errorf("read AI runtime response: %w", ErrUnavailable)
	}
	if int64(len(body)) > maximumRuntimeResponseBytes {
		return Result{}, fmt.Errorf("AI runtime response exceeded limit: %w", ErrUnavailable)
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	var decoded runtimeResponse
	if err := decoder.Decode(&decoded); err != nil {
		return Result{}, fmt.Errorf("decode AI runtime response: %w", ErrUnavailable)
	}
	if err := ensureJSONEOF(decoder); err != nil {
		return Result{}, fmt.Errorf("validate AI runtime response: %w", ErrUnavailable)
	}

	matches := make([]Match, 0, len(decoded.Matches))
	for _, match := range decoded.Matches {
		if strings.TrimSpace(match.ChunkID) == "" || strings.TrimSpace(match.Title) == "" ||
			strings.TrimSpace(match.Excerpt) == "" || !strings.HasPrefix(match.SourceURL, "/projects/") ||
			math.IsNaN(match.Score) || math.IsInf(match.Score, 0) || match.Score < -1 || match.Score > 1 {
			return Result{}, fmt.Errorf("AI runtime returned an invalid match: %w", ErrUnavailable)
		}
		matches = append(matches, Match{
			ChunkID: match.ChunkID, Title: match.Title, Heading: match.Heading,
			Excerpt: match.Excerpt, SourceURL: match.SourceURL, Score: match.Score,
		})
	}
	return Result{Matches: matches}, nil
}

func ensureJSONEOF(decoder *json.Decoder) error {
	var extra json.RawMessage
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		if err == nil {
			return errors.New("multiple JSON values")
		}
		return err
	}
	return nil
}
