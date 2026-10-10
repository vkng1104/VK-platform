package schema

import (
	"time"

	"entgo.io/ent"
	"entgo.io/ent/dialect/entsql"
	"entgo.io/ent/schema"
	"entgo.io/ent/schema/edge"
	"entgo.io/ent/schema/field"
	"entgo.io/ent/schema/index"
	"github.com/google/uuid"
)

// ProjectTechnology defines the attributed project_technologies edge table.
type ProjectTechnology struct {
	ent.Schema
}

func (ProjectTechnology) Fields() []ent.Field {
	return []ent.Field{
		field.UUID("project_id", uuid.UUID{}).Immutable(),
		field.UUID("technology_id", uuid.UUID{}).Immutable(),
		field.Int("display_order").NonNegative(),
		field.Time("created_at").
			Default(time.Now).
			Immutable().
			Annotations(entsql.DefaultExpr("NOW()")),
	}
}

func (ProjectTechnology) Edges() []ent.Edge {
	return []ent.Edge{
		edge.To("project", Project.Type).
			Field("project_id").
			Unique().
			Required().
			Immutable().
			StorageKey(edge.Symbol("project_technologies_project_fk")).
			Annotations(entsql.OnDelete(entsql.Cascade)),
		edge.To("technology", Technology.Type).
			Field("technology_id").
			Unique().
			Required().
			Immutable().
			StorageKey(edge.Symbol("project_technologies_technology_fk")).
			Annotations(entsql.OnDelete(entsql.Restrict)),
	}
}

func (ProjectTechnology) Indexes() []ent.Index {
	return []ent.Index{
		index.Fields("project_id", "display_order").
			Unique().
			StorageKey("project_technologies_project_display_order_unique"),
		index.Fields("technology_id").
			StorageKey("project_technologies_technology_id_idx"),
	}
}

func (ProjectTechnology) Annotations() []schema.Annotation {
	return []schema.Annotation{
		field.ID("project_id", "technology_id"),
		entsql.Annotation{
			Table: "project_technologies",
			Checks: map[string]string{
				"project_technologies_display_order_non_negative_check": "display_order >= 0",
			},
		},
	}
}
