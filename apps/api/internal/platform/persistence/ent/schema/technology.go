package schema

import (
	"time"

	"entgo.io/ent"
	"entgo.io/ent/dialect"
	"entgo.io/ent/dialect/entsql"
	"entgo.io/ent/schema"
	"entgo.io/ent/schema/edge"
	"entgo.io/ent/schema/field"
	"github.com/google/uuid"
)

// Technology defines the generated persistence model for the technologies table.
type Technology struct {
	ent.Schema
}

func (Technology) Fields() []ent.Field {
	return []ent.Field{
		field.UUID("id", uuid.UUID{}).
			Default(uuid.New).
			Immutable().
			Annotations(entsql.DefaultExpr("gen_random_uuid()")),
		field.String("slug").
			Unique().
			SchemaType(map[string]string{dialect.Postgres: "text"}),
		field.String("name").
			SchemaType(map[string]string{dialect.Postgres: "text"}),
		field.String("category").
			SchemaType(map[string]string{dialect.Postgres: "text"}),
		field.Time("created_at").
			Default(time.Now).
			Immutable().
			Annotations(entsql.DefaultExpr("NOW()")),
		field.Time("updated_at").
			Default(time.Now).
			UpdateDefault(time.Now).
			Annotations(entsql.DefaultExpr("NOW()")),
	}
}

func (Technology) Edges() []ent.Edge {
	return []ent.Edge{
		edge.From("projects", Project.Type).
			Ref("technologies").
			Through("project_links", ProjectTechnology.Type),
	}
}

func (Technology) Annotations() []schema.Annotation {
	return []schema.Annotation{
		entsql.Annotation{Table: "technologies"},
	}
}
