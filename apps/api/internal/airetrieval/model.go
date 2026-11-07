package airetrieval

type Request struct {
	Query string
	TopK  int
}

type Result struct {
	Query   string
	Matches []Match
}

type Match struct {
	ChunkID   string
	Title     string
	Heading   string
	Excerpt   string
	SourceURL string
	Score     float64
}
