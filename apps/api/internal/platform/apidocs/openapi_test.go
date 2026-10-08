package apidocs

import (
	"context"
	"fmt"
	"reflect"
	"sort"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
)

func TestOpenAPIContractIsValid(t *testing.T) {
	t.Parallel()

	document := loadOpenAPIDocument(t)
	if err := document.Validate(context.Background()); err != nil {
		t.Fatalf("validate OpenAPI document: %v", err)
	}
}

func TestOpenAPIContractHasExpectedOperations(t *testing.T) {
	t.Parallel()

	document := loadOpenAPIDocument(t)
	want := map[string]string{
		"GET /healthz":                "getHealth",
		"GET /api/v1/projects":        "listProjects",
		"GET /api/v1/projects/{slug}": "getProjectBySlug",
	}

	got := make(map[string]string)
	operationIDs := make(map[string]string)
	for path, pathItem := range document.Paths.Map() {
		for method, operation := range pathItem.Operations() {
			key := fmt.Sprintf("%s %s", method, path)
			got[key] = operation.OperationID

			if operation.OperationID == "" {
				t.Errorf("operation %s has no operationId", key)
			}
			if previous, exists := operationIDs[operation.OperationID]; exists {
				t.Errorf("operationId %q is shared by %s and %s", operation.OperationID, previous, key)
			}
			operationIDs[operation.OperationID] = key

			if operation.Responses == nil || operation.Responses.Len() == 0 {
				t.Errorf("operation %s has no responses", key)
				continue
			}
			for status, responseReference := range operation.Responses.Map() {
				response := responseReference.Value
				if response == nil {
					t.Errorf("operation %s response %s did not resolve", key, status)
					continue
				}
				mediaType := response.Content.Get("application/json")
				if mediaType == nil || mediaType.Schema == nil || mediaType.Schema.Value == nil {
					t.Errorf("operation %s response %s has no resolved JSON schema", key, status)
				}
			}
		}
	}

	if !reflect.DeepEqual(got, want) {
		t.Fatalf("documented operations = %#v, want %#v", got, want)
	}
}

func TestOpenAPIContractPreservesPublicSchemaRules(t *testing.T) {
	t.Parallel()

	document := loadOpenAPIDocument(t)
	if document.OpenAPI != "3.1.2" {
		t.Errorf("OpenAPI version = %q, want 3.1.2", document.OpenAPI)
	}
	if len(document.Servers) != 1 || document.Servers[0].URL != "/" {
		t.Errorf("servers = %#v, want one same-origin server", document.Servers)
	}
	if document.Components == nil {
		t.Fatal("components are missing")
	}
	if len(document.Components.SecuritySchemes) != 0 {
		t.Errorf("security schemes = %#v, want none", document.Components.SecuritySchemes)
	}

	publicError := componentSchema(t, document, "PublicError")
	wantRequired := []string{"code", "message", "request_id", "retryable"}
	gotRequired := append([]string(nil), publicError.Required...)
	sort.Strings(gotRequired)
	sort.Strings(wantRequired)
	if !reflect.DeepEqual(gotRequired, wantRequired) {
		t.Errorf("PublicError required fields = %#v, want %#v", gotRequired, wantRequired)
	}

	for _, schemaName := range []string{"ProjectSummary", "ProjectDetail"} {
		for _, propertyName := range []string{"repository_url", "live_url"} {
			property := schemaProperty(t, document, schemaName, propertyName)
			if property.Type == nil || !property.Type.Includes("string") || !property.Type.IncludesNull() {
				t.Errorf("%s.%s types = %#v, want string and null", schemaName, propertyName, property.Type)
			}
			if property.Format != "uri" {
				t.Errorf("%s.%s format = %q, want uri", schemaName, propertyName, property.Format)
			}
		}
	}
}

func loadOpenAPIDocument(t *testing.T) *openapi3.T {
	t.Helper()

	loader := openapi3.NewLoader()
	loader.IsExternalRefsAllowed = false
	document, err := loader.LoadFromData(openAPISpec)
	if err != nil {
		t.Fatalf("load embedded OpenAPI document: %v", err)
	}

	return document
}

func componentSchema(t *testing.T, document *openapi3.T, name string) *openapi3.Schema {
	t.Helper()

	reference := document.Components.Schemas[name]
	if reference == nil || reference.Value == nil {
		t.Fatalf("component schema %q did not resolve", name)
	}

	return reference.Value
}

func schemaProperty(
	t *testing.T,
	document *openapi3.T,
	schemaName string,
	propertyName string,
) *openapi3.Schema {
	t.Helper()

	schema := componentSchema(t, document, schemaName)
	reference := schema.Properties[propertyName]
	if reference == nil || reference.Value == nil {
		t.Fatalf("schema property %s.%s did not resolve", schemaName, propertyName)
	}

	return reference.Value
}
