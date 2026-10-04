package project

type Project struct {
	Slug            string
	Title           string
	Summary         string
	Period          string
	Role            string
	ContentMarkdown string
	Technologies    []string
	Featured        bool
	RepositoryURL   *string
	LiveURL         *string
}

type ListFilter struct {
	Featured *bool
}
