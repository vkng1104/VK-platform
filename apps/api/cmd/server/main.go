package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpserver"
)

const (
	defaultAddress  = ":8080"
	shutdownTimeout = 10 * time.Second
)

func main() {
	address := os.Getenv("API_ADDR")
	if address == "" {
		address = defaultAddress
	}

	server := &http.Server{
		Addr:              address,
		Handler:           httpserver.NewHandler(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	shutdownContext, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	go func() {
		<-shutdownContext.Done()

		ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
		defer cancel()

		if err := server.Shutdown(ctx); err != nil {
			slog.Error("api shutdown failed", "error", err)
		}
	}()

	slog.Info("api listening", "address", address)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("api stopped unexpectedly", "error", err)
		os.Exit(1)
	}
}
