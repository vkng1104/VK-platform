package persistence

import (
	"database/sql"
	"errors"

	"entgo.io/ent/dialect"
	entsql "entgo.io/ent/dialect/sql"
	persistenceent "github.com/vkng1104/VK-platform/apps/api/internal/platform/persistence/ent"
)

var ErrMissingDatabase = errors.New("SQL database is required")

func NewClient(database *sql.DB) (*persistenceent.Client, error) {
	if database == nil {
		return nil, ErrMissingDatabase
	}

	driver := entsql.OpenDB(dialect.Postgres, database)
	return persistenceent.NewClient(persistenceent.Driver(driver)), nil
}
