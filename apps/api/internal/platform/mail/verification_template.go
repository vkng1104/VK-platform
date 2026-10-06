package mail

import (
	"bytes"
	_ "embed"
	"errors"
	"fmt"
	htmltemplate "html/template"
	"strings"
	texttemplate "text/template"
)

//go:embed email_templates/verification-code.subject.tmpl
var verificationSubjectTemplateSource string

//go:embed email_templates/verification-code.txt.tmpl
var verificationTextTemplateSource string

//go:embed email_templates/verification-code.html.tmpl
var verificationHTMLTemplateSource string

type verificationEmailTemplateData struct {
	Code      string
	ExpiresAt string
}

type renderedVerificationEmail struct {
	Subject   string
	PlainText string
	HTML      string
}

type verificationEmailRenderer struct {
	subject *texttemplate.Template
	plain   *texttemplate.Template
	html    *htmltemplate.Template
}

func newVerificationEmailRenderer() (*verificationEmailRenderer, error) {
	subject, err := texttemplate.New("verification-code-subject").
		Option("missingkey=error").
		Parse(verificationSubjectTemplateSource)
	if err != nil {
		return nil, fmt.Errorf("parse verification email subject template: %w", err)
	}

	plain, err := texttemplate.New("verification-code-text").
		Option("missingkey=error").
		Parse(verificationTextTemplateSource)
	if err != nil {
		return nil, fmt.Errorf("parse verification email text template: %w", err)
	}

	html, err := htmltemplate.New("verification-code-html").
		Option("missingkey=error").
		Parse(verificationHTMLTemplateSource)
	if err != nil {
		return nil, fmt.Errorf("parse verification email HTML template: %w", err)
	}

	return &verificationEmailRenderer{subject: subject, plain: plain, html: html}, nil
}

func (renderer *verificationEmailRenderer) Render(
	data verificationEmailTemplateData,
) (renderedVerificationEmail, error) {
	if renderer == nil || renderer.subject == nil || renderer.plain == nil || renderer.html == nil {
		return renderedVerificationEmail{}, errors.New("verification email renderer is not configured")
	}

	var subject bytes.Buffer
	if err := renderer.subject.Execute(&subject, data); err != nil {
		return renderedVerificationEmail{}, fmt.Errorf("render verification email subject: %w", err)
	}
	renderedSubject := strings.TrimSpace(subject.String())
	if renderedSubject == "" || strings.ContainsAny(renderedSubject, "\r\n") {
		return renderedVerificationEmail{}, errors.New("rendered verification email subject is invalid")
	}

	var plain bytes.Buffer
	if err := renderer.plain.Execute(&plain, data); err != nil {
		return renderedVerificationEmail{}, fmt.Errorf("render verification email text: %w", err)
	}

	var html bytes.Buffer
	if err := renderer.html.Execute(&html, data); err != nil {
		return renderedVerificationEmail{}, fmt.Errorf("render verification email HTML: %w", err)
	}

	return renderedVerificationEmail{
		Subject:   renderedSubject,
		PlainText: plain.String(),
		HTML:      html.String(),
	}, nil
}
