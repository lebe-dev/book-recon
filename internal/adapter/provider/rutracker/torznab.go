package rutracker

import (
	"encoding/xml"
	"fmt"
	"io"
	"strconv"
	"strings"
)

// TorznabResponse is the top-level RSS response from Jackett Torznab API.
type TorznabResponse struct {
	XMLName xml.Name       `xml:"rss"`
	Channel TorznabChannel `xml:"channel"`
}

type TorznabChannel struct {
	Items []TorznabItem `xml:"item"`
}

// TorznabItem represents a single search result from Jackett.
type TorznabItem struct {
	Title    string `xml:"title"`
	Size     int64  `xml:"size"`
	Link     string `xml:"link"`
	Category string `xml:"category"`
	PubDate  string `xml:"pubDate"`

	// Parsed from torznab:attr elements.
	Seeders int
	Peers   int

	// Raw attributes for custom parsing.
	Attrs []TorznabAttr `xml:"http://torznab.com/schemas/2015/feed attr"`
}

// TorznabAttr is a torznab:attr element.
type TorznabAttr struct {
	Name  string `xml:"name,attr"`
	Value string `xml:"value,attr"`
}

// TorznabError represents an error response from Jackett Torznab API.
type TorznabError struct {
	XMLName     xml.Name `xml:"error"`
	Code        string   `xml:"code,attr"`
	Description string   `xml:"description,attr"`
}

func (e *TorznabError) Error() string {
	return fmt.Sprintf("jackett error (code %s): %s", e.Code, e.Description)
}

// ParseTorznab parses Jackett Torznab XML response into a slice of TorznabItem.
func ParseTorznab(r io.Reader) ([]TorznabItem, error) {
	data, err := io.ReadAll(r)
	if err != nil {
		return nil, fmt.Errorf("torznab: read response: %w", err)
	}

	// Check if the response is a Jackett error.
	var torznabErr TorznabError
	if xml.Unmarshal(data, &torznabErr) == nil && torznabErr.Code != "" {
		return nil, &torznabErr
	}

	var resp TorznabResponse
	if err := xml.Unmarshal(data, &resp); err != nil {
		return nil, fmt.Errorf("torznab: decode XML: %w", err)
	}

	items := resp.Channel.Items
	for i := range items {
		for _, attr := range items[i].Attrs {
			switch attr.Name {
			case "seeders":
				items[i].Seeders, _ = strconv.Atoi(attr.Value)
			case "peers":
				items[i].Peers, _ = strconv.Atoi(attr.Value)
			}
		}
	}

	return items, nil
}

// DetectFormats tries to detect book formats from the torrent title.
// RuTracker titles often contain format hints like "(fb2)", "(epub, fb2)", etc.
func DetectFormats(title string) []string {
	lower := strings.ToLower(title)
	known := []string{"epub", "fb2", "mobi", "pdf", "djvu"}

	var found []string
	for _, f := range known {
		if strings.Contains(lower, f) {
			found = append(found, f)
		}
	}
	return found
}
