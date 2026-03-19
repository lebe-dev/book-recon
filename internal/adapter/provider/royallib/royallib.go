package royallib

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"github.com/charmbracelet/log"
	"github.com/gocolly/colly/v2"
	"github.com/lebe-dev/book-recon/internal/domain"
)

const (
	baseURL     = "https://royallib.com"
	providerName = "royallib"
)

type Provider struct {
	logger *log.Logger
}

func New(logger *log.Logger) *Provider {
	return &Provider{logger: logger}
}

func (p *Provider) Name() string {
	return providerName
}

func (p *Provider) Search(ctx context.Context, query string, limit int) ([]domain.SearchResult, error) {
	c := colly.NewCollector(
		colly.AllowedDomains("royallib.com", "www.royallib.com"),
	)

	var results []domain.SearchResult

	c.OnHTML(".book-item, .search-result, .entry", func(e *colly.HTMLElement) {
		if len(results) >= limit {
			return
		}

		title := strings.TrimSpace(e.ChildText(".book-title, .title, h3 a"))
		author := strings.TrimSpace(e.ChildText(".book-author, .author, .subtitle a"))
		link := e.ChildAttr("a", "href")

		if title == "" || link == "" {
			return
		}

		if !strings.HasPrefix(link, "http") {
			link = baseURL + link
		}

		book := domain.Book{
			Title:     title,
			Author:    author,
			Formats:   []domain.Format{domain.FormatFB2, domain.FormatEPUB},
			Provider:  providerName,
			SourceURL: link,
		}

		results = append(results, domain.NewSearchResult(book))
	})

	searchURL := fmt.Sprintf("%s/search/?q=%s", baseURL, url.QueryEscape(query))

	c.OnRequest(func(r *colly.Request) {
		if ctx.Err() != nil {
			r.Abort()
		}
	})

	if err := c.Visit(searchURL); err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, "royallib search failed", err)
	}

	return results, nil
}

func (p *Provider) Download(ctx context.Context, result domain.SearchResult, format domain.Format) (io.ReadCloser, string, error) {
	downloadURL := result.Book.SourceURL
	if !strings.HasSuffix(downloadURL, "/") {
		downloadURL += "/"
	}
	downloadURL += fmt.Sprintf("download.%s/", string(format))

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, downloadURL, nil)
	if err != nil {
		return nil, "", domain.WrapError(domain.ErrCodeProviderError, "create request", err)
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, "", domain.WrapError(domain.ErrCodeProviderError, "download request failed", err)
	}

	if resp.StatusCode != http.StatusOK {
		_ = resp.Body.Close()
		return nil, "", domain.NewError(domain.ErrCodeProviderError,
			fmt.Sprintf("unexpected status: %d", resp.StatusCode))
	}

	filename := fmt.Sprintf("%s - %s.%s", result.Book.Author, result.Book.Title, string(format))

	return resp.Body, filename, nil
}
