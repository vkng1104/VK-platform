package airetrieval

import (
	"context"
	"errors"
	"testing"
	"time"
)

type runtimeStub struct {
	retrieve func(context.Context, Request) (Result, error)
}

func (stub *runtimeStub) Retrieve(ctx context.Context, request Request) (Result, error) {
	return stub.retrieve(ctx, request)
}

func TestServiceNormalizesQueryAndDefaultsTopK(t *testing.T) {
	t.Parallel()
	var got Request
	service := newTestService(t, &runtimeStub{retrieve: func(_ context.Context, request Request) (Result, error) {
		got = request
		return Result{Matches: []Match{}}, nil
	}}, ServiceSettings{Enabled: true, Timeout: time.Second, MaxConcurrentRequests: 1})

	result, err := service.Retrieve(context.Background(), Request{Query: "  architecture  "})
	if err != nil {
		t.Fatalf("Retrieve() error = %v", err)
	}
	if got.Query != "architecture" || got.TopK != 5 {
		t.Fatalf("runtime request = %#v, want normalized query and default top_k", got)
	}
	if result.Query != "architecture" || result.Matches == nil {
		t.Fatalf("result = %#v", result)
	}
}

func TestServiceValidatesQueryAndTopKBeforeRuntime(t *testing.T) {
	t.Parallel()
	calls := 0
	service := newTestService(t, &runtimeStub{retrieve: func(_ context.Context, _ Request) (Result, error) {
		calls++
		return Result{}, nil
	}}, ServiceSettings{Enabled: true, Timeout: time.Second, MaxConcurrentRequests: 1})

	if _, err := service.Retrieve(context.Background(), Request{Query: " "}); !errors.Is(err, ErrInvalidQuery) {
		t.Fatalf("empty query error = %v, want ErrInvalidQuery", err)
	}
	if _, err := service.Retrieve(context.Background(), Request{Query: "valid", TopK: 11}); !errors.Is(err, ErrInvalidTopK) {
		t.Fatalf("top_k error = %v, want ErrInvalidTopK", err)
	}
	if calls != 0 {
		t.Fatalf("runtime calls = %d, want 0", calls)
	}
}

func TestServiceReturnsUnavailableWhenDisabled(t *testing.T) {
	t.Parallel()
	service := newTestService(t, nil, ServiceSettings{
		Enabled: false, Timeout: time.Second, MaxConcurrentRequests: 1,
	})

	_, err := service.Retrieve(context.Background(), Request{Query: "architecture"})
	if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("Retrieve() error = %v, want ErrUnavailable", err)
	}
}

func TestServiceBoundsConcurrentRequests(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	service := newTestService(t, &runtimeStub{retrieve: func(_ context.Context, _ Request) (Result, error) {
		close(started)
		<-release
		return Result{}, nil
	}}, ServiceSettings{Enabled: true, Timeout: time.Second, MaxConcurrentRequests: 1})

	done := make(chan error, 1)
	go func() {
		_, err := service.Retrieve(context.Background(), Request{Query: "first"})
		done <- err
	}()
	<-started
	if _, err := service.Retrieve(context.Background(), Request{Query: "second"}); !errors.Is(err, ErrBusy) {
		t.Fatalf("second Retrieve() error = %v, want ErrBusy", err)
	}
	close(release)
	if err := <-done; err != nil {
		t.Fatalf("first Retrieve() error = %v", err)
	}
}

func TestServiceOwnsRetrievalDeadline(t *testing.T) {
	t.Parallel()
	service := newTestService(t, &runtimeStub{retrieve: func(ctx context.Context, _ Request) (Result, error) {
		<-ctx.Done()
		return Result{}, ctx.Err()
	}}, ServiceSettings{Enabled: true, Timeout: time.Millisecond, MaxConcurrentRequests: 1})

	_, err := service.Retrieve(context.Background(), Request{Query: "architecture"})
	if !errors.Is(err, ErrTimeout) {
		t.Fatalf("Retrieve() error = %v, want ErrTimeout", err)
	}
}

func newTestService(t *testing.T, runtime Runtime, settings ServiceSettings) *Service {
	t.Helper()
	service, err := NewService(runtime, settings)
	if err != nil {
		t.Fatalf("NewService() error = %v", err)
	}
	return service
}
