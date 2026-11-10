package airetrieval

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	defaultTopK       = 5
	maximumQueryRunes = 500
	maximumTopK       = 10
)

type Runtime interface {
	Retrieve(ctx context.Context, request Request) (Result, error)
}

type ServiceSettings struct {
	Enabled               bool
	Timeout               time.Duration
	MaxConcurrentRequests int
}

type Service struct {
	runtime  Runtime
	enabled  bool
	timeout  time.Duration
	capacity chan struct{}
}

func NewService(runtime Runtime, settings ServiceSettings) (*Service, error) {
	if settings.Enabled && runtime == nil {
		return nil, ErrMissingRuntime
	}
	if settings.Timeout <= 0 {
		return nil, fmt.Errorf("AI timeout must be positive")
	}
	if settings.MaxConcurrentRequests <= 0 {
		return nil, fmt.Errorf("AI maximum concurrent requests must be positive")
	}
	return &Service{
		runtime:  runtime,
		enabled:  settings.Enabled,
		timeout:  settings.Timeout,
		capacity: make(chan struct{}, settings.MaxConcurrentRequests),
	}, nil
}

func (service *Service) Retrieve(ctx context.Context, request Request) (Result, error) {
	query := strings.TrimSpace(request.Query)
	if query == "" || !utf8.ValidString(query) || utf8.RuneCountInString(query) > maximumQueryRunes {
		return Result{}, ErrInvalidQuery
	}
	topK := request.TopK
	if topK == 0 {
		topK = defaultTopK
	}
	if topK < 1 || topK > maximumTopK {
		return Result{}, ErrInvalidTopK
	}
	if !service.enabled || service.runtime == nil {
		return Result{}, ErrUnavailable
	}

	select {
	case service.capacity <- struct{}{}:
		defer func() { <-service.capacity }()
	default:
		return Result{}, ErrBusy
	}

	retrievalContext, cancel := context.WithTimeout(ctx, service.timeout)
	defer cancel()
	result, err := service.runtime.Retrieve(retrievalContext, Request{Query: query, TopK: topK})
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(retrievalContext.Err(), context.DeadlineExceeded) {
			return Result{}, ErrTimeout
		}
		return Result{}, fmt.Errorf("retrieve AI matches: %w", err)
	}
	result.Query = query
	return result, nil
}
