package httpx

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"strconv"
	"sync/atomic"
	"time"
)

const RequestIDHeader = "X-Request-ID"

type requestIDContextKey struct{}

var fallbackRequestIDCounter atomic.Uint64

func WithRequestID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		requestID := newRequestID()
		writer.Header().Set(RequestIDHeader, requestID)
		ctx := context.WithValue(request.Context(), requestIDContextKey{}, requestID)
		next.ServeHTTP(writer, request.WithContext(ctx))
	})
}

func RequestID(ctx context.Context) string {
	requestID, _ := ctx.Value(requestIDContextKey{}).(string)
	return requestID
}

func newRequestID() string {
	value := make([]byte, 16)
	if _, err := rand.Read(value); err == nil {
		return hex.EncodeToString(value)
	}

	fallback := strconv.FormatInt(time.Now().UnixNano(), 10) + ":" +
		strconv.FormatUint(fallbackRequestIDCounter.Add(1), 10)
	digest := sha256.Sum256([]byte(fallback))
	return hex.EncodeToString(digest[:16])
}
