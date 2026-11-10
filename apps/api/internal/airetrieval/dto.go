package airetrieval

import "math"

type retrievalRequest struct {
	Query string `json:"query"`
	TopK  *int   `json:"top_k,omitempty"`
}

type retrievalEnvelope struct {
	Retrieval retrievalResponse `json:"retrieval"`
}

type retrievalResponse struct {
	Query   string          `json:"query"`
	Matches []matchResponse `json:"matches"`
}

type matchResponse struct {
	ChunkID   string  `json:"chunk_id"`
	Title     string  `json:"title"`
	Heading   string  `json:"heading"`
	Excerpt   string  `json:"excerpt"`
	SourceURL string  `json:"source_url"`
	Score     float64 `json:"score"`
}

func (request retrievalRequest) toServiceRequest() Request {
	topK := 0
	if request.TopK != nil {
		topK = *request.TopK
	}
	return Request{Query: request.Query, TopK: topK}
}

func newRetrievalEnvelope(result Result) retrievalEnvelope {
	matches := make([]matchResponse, 0, len(result.Matches))
	for _, match := range result.Matches {
		matches = append(matches, matchResponse{
			ChunkID: match.ChunkID, Title: match.Title, Heading: match.Heading,
			Excerpt: match.Excerpt, SourceURL: match.SourceURL,
			Score: math.Round(match.Score*10_000) / 10_000,
		})
	}
	return retrievalEnvelope{Retrieval: retrievalResponse{Query: result.Query, Matches: matches}}
}
