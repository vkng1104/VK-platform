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

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/apidocs"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/config"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/database"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpserver"
	"github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence"
	"github.com/vkng1104/VK-platform/apps/api/internal/project"
)

const (
	startupTimeout     = 10 * time.Second
	shutdownTimeout    = 10 * time.Second
	readHeaderTimeout  = 5 * time.Second
	readTimeout        = 10 * time.Second
	writeTimeout       = 15 * time.Second
	idleTimeout        = 60 * time.Second
	maximumHeaderBytes = 1 << 20
)

func main() {
	configuration, err := config.Load()
	if err != nil {
		slog.Error("load API configuration failed", "error", err)
		os.Exit(1)
	}

	startupContext, cancelStartup := context.WithTimeout(context.Background(), startupTimeout)
	defer cancelStartup()

	sqlDatabase, err := database.Open(startupContext, configuration.Database)
	if err != nil {
		slog.Error("open API database failed", "error", err)
		os.Exit(1)
	}

	persistenceClient, err := persistence.NewClient(sqlDatabase)
	if err != nil {
		_ = sqlDatabase.Close()
		slog.Error("construct API persistence client failed", "error", err)
		os.Exit(1)
	}
	defer func() {
		if err := persistenceClient.Close(); err != nil {
			slog.Warn("close API database failed", "error", err)
		}
	}()

	projectRepository, err := project.NewEntRepository(persistenceClient)
	if err != nil {
		slog.Error("construct project repository failed", "error", err)
		os.Exit(1)
	}

	projectService, err := project.NewService(projectRepository)
	if err != nil {
		slog.Error("construct project service failed", "error", err)
		os.Exit(1)
	}

	projectHandler, err := project.NewHandler(projectService, slog.Default())
	if err != nil {
		slog.Error("construct project handler failed", "error", err)
		os.Exit(1)
	}
	registrars := []httpserver.RouteRegistrar{apidocs.NewHandler(), projectHandler}

	server := &http.Server{
		Addr:              configuration.Address,
		Handler:           httpserver.NewHandler(registrars...),
		ReadHeaderTimeout: readHeaderTimeout,
		ReadTimeout:       readTimeout,
		WriteTimeout:      writeTimeout,
		IdleTimeout:       idleTimeout,
		MaxHeaderBytes:    maximumHeaderBytes,
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

	slog.Info("api listening", "address", configuration.Address)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("api stopped unexpectedly", "error", err)
		os.Exit(1)
	}
}
