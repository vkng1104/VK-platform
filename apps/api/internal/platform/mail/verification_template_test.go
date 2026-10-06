package mail

import (
	"strings"
	"testing"
)

func TestVerificationEmailRendererMapsTemplateData(t *testing.T) {
	t.Parallel()

	rendered, err := newTestEmailRenderer(t).Render(verificationEmailTemplateData{
		Code:      "123456",
		ExpiresAt: "09:05 UTC",
	})
	if err != nil {
		t.Fatalf("Render() error = %v", err)
	}

	if rendered.Subject != "Your VK Platform verification code" {
		t.Errorf("subject = %q", rendered.Subject)
	}
	for name, body := range map[string]string{
		"plain text": rendered.PlainText,
		"HTML":       rendered.HTML,
	} {
		if !strings.Contains(body, "123456") || !strings.Contains(body, "09:05 UTC") {
			t.Errorf("%s body did not map template data: %q", name, body)
		}
	}
}

func TestVerificationHTMLTemplateEscapesMappedValues(t *testing.T) {
	t.Parallel()

	rendered, err := newTestEmailRenderer(t).Render(verificationEmailTemplateData{
		Code:      "<code>",
		ExpiresAt: "<&>",
	})
	if err != nil {
		t.Fatalf("Render() error = %v", err)
	}
	if !strings.Contains(rendered.HTML, "&lt;code&gt;") ||
		!strings.Contains(rendered.HTML, "&lt;&amp;&gt;") {
		t.Fatalf("HTML template did not escape values: %s", rendered.HTML)
	}
	if strings.Contains(rendered.HTML, "<code>") {
		t.Fatalf("HTML template emitted an unescaped mapped value: %s", rendered.HTML)
	}
}
