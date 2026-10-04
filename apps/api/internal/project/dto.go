package project

type listResponse struct {
	Projects []summaryResponse `json:"projects"`
}

type detailEnvelope struct {
	Project detailResponse `json:"project"`
}

type summaryResponse struct {
	Slug          string   `json:"slug"`
	Title         string   `json:"title"`
	Summary       string   `json:"summary"`
	Period        string   `json:"period"`
	Role          string   `json:"role"`
	Technologies  []string `json:"technologies"`
	Featured      bool     `json:"featured"`
	RepositoryURL *string  `json:"repository_url"`
	LiveURL       *string  `json:"live_url"`
}

type detailResponse struct {
	Slug            string   `json:"slug"`
	Title           string   `json:"title"`
	Summary         string   `json:"summary"`
	Period          string   `json:"period"`
	Role            string   `json:"role"`
	ContentMarkdown string   `json:"content_markdown"`
	Technologies    []string `json:"technologies"`
	Featured        bool     `json:"featured"`
	RepositoryURL   *string  `json:"repository_url"`
	LiveURL         *string  `json:"live_url"`
}

func newListResponse(projects []Project) listResponse {
	responses := make([]summaryResponse, 0, len(projects))
	for _, item := range projects {
		responses = append(responses, newSummaryResponse(item))
	}

	return listResponse{Projects: responses}
}

func newSummaryResponse(result Project) summaryResponse {
	return summaryResponse{
		Slug:          result.Slug,
		Title:         result.Title,
		Summary:       result.Summary,
		Period:        result.Period,
		Role:          result.Role,
		Technologies:  copyStrings(result.Technologies),
		Featured:      result.Featured,
		RepositoryURL: result.RepositoryURL,
		LiveURL:       result.LiveURL,
	}
}

func newDetailEnvelope(result Project) detailEnvelope {
	return detailEnvelope{
		Project: detailResponse{
			Slug:            result.Slug,
			Title:           result.Title,
			Summary:         result.Summary,
			Period:          result.Period,
			Role:            result.Role,
			ContentMarkdown: result.ContentMarkdown,
			Technologies:    copyStrings(result.Technologies),
			Featured:        result.Featured,
			RepositoryURL:   result.RepositoryURL,
			LiveURL:         result.LiveURL,
		},
	}
}

func copyStrings(values []string) []string {
	result := make([]string, len(values))
	copy(result, values)
	return result
}
