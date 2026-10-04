package httpserver

import (
	"net/http"

	"github.com/vkng1104/VK-platform/apps/api/internal/platform/httpx"
)

type healthResponse struct {
	Status  string `json:"status"`
	Service string `json:"service"`
}

type RouteRegistrar interface {
	RegisterRoutes(mux *http.ServeMux)
}

func NewHandler(registrars ...RouteRegistrar) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", handleHealth)
	for _, registrar := range registrars {
		if registrar != nil {
			registrar.RegisterRoutes(mux)
		}
	}

	return mux
}

func handleHealth(writer http.ResponseWriter, _ *http.Request) {
	_ = httpx.WriteJSON(writer, http.StatusOK, healthResponse{
		Status:  "ok",
		Service: "api",
	})
}
