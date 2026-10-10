package schema

import (
	"time"

	"entgo.io/ent"
	"entgo.io/ent/dialect"
	"entgo.io/ent/dialect/entsql"
	"entgo.io/ent/schema"
	"entgo.io/ent/schema/edge"
	"entgo.io/ent/schema/field"
	"entgo.io/ent/schema/index"
	"github.com/google/uuid"
)

// Project defines the generated persistence model for the projects table.
type Project struct {
	ent.Schema
}

func (Project) Fields() []ent.Field {
	return []ent.Field{
		field.UUID("id", uuid.UUID{}).
			Default(uuid.New).
			Immutable().
			Annotations(entsql.DefaultExpr("gen_random_uuid()")),
		field.String("slug").
			Unique().
			SchemaType(map[string]string{dialect.Postgres: "text"}),
		field.String("title").
			SchemaType(map[string]string{dialect.Postgres: "text"}),
		field.String("summary").
			SchemaType(map[string]string{dialect.Postgres: "text"}),
		field.String("period").
			SchemaType(map[string]string{dialect.Postgres: "text"}),
		field.String("role").
			SchemaType(map[string]string{dialect.Postgres: "text"}),
		field.String("content_markdown").
			SchemaType(map[string]string{dialect.Postgres: "text"}),
		field.Bool("featured").Default(false),
		field.Bool("published").Default(false),
		field.Int("display_order").Default(0).NonNegative(),
		field.String("repository_url").
			Optional().
			Nillable().
			SchemaType(map[string]string{dialect.Postgres: "text"}),
		field.String("live_url").
			Optional().
			Nillable().
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

func (Project) Edges() []ent.Edge {
	return []ent.Edge{
		edge.To("technologies", Technology.Type).
			Through("technology_links", ProjectTechnology.Type),
	}
}

func (Project) Indexes() []ent.Index {
	return []ent.Index{
		index.Fields("display_order", "title", "id").
			StorageKey("projects_published_display_order_idx").
			Annotations(entsql.IndexWhere("published = TRUE")),
		index.Fields("display_order", "title", "id").
			StorageKey("projects_published_featured_display_order_idx").
			Annotations(entsql.IndexWhere("published = TRUE AND featured = TRUE")),
	}
}

func (Project) Annotations() []schema.Annotation {
	return []schema.Annotation{
		entsql.Annotation{
			Table: "projects",
			Checks: map[string]string{
				"projects_display_order_non_negative_check": "display_order >= 0",
			},
		},
	}
}
