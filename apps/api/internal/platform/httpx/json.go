package httpx

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
)

const DefaultMaxBodyBytes int64 = 1 << 20

var (
	ErrEmptyBody       = errors.New("request body must contain one JSON object")
	ErrMultipleObjects = errors.New("request body must contain exactly one JSON object")
)

type ErrorResponse struct {
	Code      string              `json:"code"`
	Message   string              `json:"message"`
	RequestID string              `json:"request_id"`
	Retryable bool                `json:"retryable"`
	Fields    map[string][]string `json:"fields,omitempty"`
}

type PublicError struct {
	Code      string
	Message   string
	Retryable bool
	Fields    map[string][]string
}

func DecodeJSON(writer http.ResponseWriter, request *http.Request, destination any) error {
	request.Body = http.MaxBytesReader(writer, request.Body, DefaultMaxBodyBytes)

	decoder := json.NewDecoder(request.Body)
	decoder.DisallowUnknownFields()

	if err := decoder.Decode(destination); err != nil {
		if errors.Is(err, io.EOF) {
			return ErrEmptyBody
		}

		return fmt.Errorf("decode JSON body: %w", err)
	}

	var extra json.RawMessage
	if err := decoder.Decode(&extra); err != nil {
		if errors.Is(err, io.EOF) {
			return nil
		}

		return fmt.Errorf("check trailing JSON body: %w", err)
	}

	return ErrMultipleObjects
}

func WriteJSON(writer http.ResponseWriter, status int, value any) error {
	payload, err := json.Marshal(value)
	if err != nil {
		return fmt.Errorf("marshal JSON response: %w", err)
	}

	payload = append(payload, '\n')
	writer.Header().Set("Content-Type", "application/json")
	writer.WriteHeader(status)

	if _, err := writer.Write(payload); err != nil {
		return fmt.Errorf("write JSON response: %w", err)
	}

	return nil
}

func WriteError(
	writer http.ResponseWriter,
	request *http.Request,
	status int,
	publicError PublicError,
) error {
	return WriteJSON(writer, status, ErrorResponse{
		Code:      publicError.Code,
		Message:   publicError.Message,
		RequestID: RequestID(request.Context()),
		Retryable: publicError.Retryable,
		Fields:    publicError.Fields,
	})
}
