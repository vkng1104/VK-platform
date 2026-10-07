package apidocs

import (
	_ "embed"
	"net/http"

	"github.com/swaggest/swgui"
	"github.com/swaggest/swgui/v5emb"
)

const (
	docsBasePath = "/api/docs/"
	docsPath     = "/api/docs"
	specPath     = "/api/docs/openapi.yaml"
)

//go:embed openapi.yaml
var openAPISpec []byte

type Handler struct {
	swaggerUI http.Handler
}

func NewHandler() *Handler {
	newSwaggerUI := v5emb.NewWithConfig(swgui.Config{
		SettingsUI: map[string]string{
			"deepLinking":            "true",
			"displayOperationId":     "true",
			"displayRequestDuration": "true",
			"supportedSubmitMethods": `["get"]`,
		},
	})

	return &Handler{
		swaggerUI: newSwaggerUI("VK Platform API", specPath, docsBasePath),
	}
}

func (handler *Handler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET "+docsPath, redirectToDocs)
	mux.HandleFunc("GET "+specPath, serveOpenAPISpec)
	mux.Handle("GET "+docsBasePath, handler.swaggerUI)
}

func redirectToDocs(writer http.ResponseWriter, request *http.Request) {
	http.Redirect(writer, request, docsBasePath, http.StatusPermanentRedirect)
}

func serveOpenAPISpec(writer http.ResponseWriter, _ *http.Request) {
	writer.Header().Set("Content-Type", "application/yaml; charset=utf-8")
	_, _ = writer.Write(openAPISpec)
}
