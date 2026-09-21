package org.thesis.research.litreview.venue.grobid;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Turns GROBID TEI XML into a {@link TeiDocument}.
 *
 * <p>Uses plain JDK DOM rather than a mapping library: the TEI subset we care
 * about is tiny, and GROBID's output has quirks (nested {@code <div>}s, headings
 * split across {@code <head>} and {@code @n}, reference markers interleaved with
 * text) that are easier to handle explicitly than to configure around.
 */
@Component
public class TeiParser {

    private static final Logger log = LoggerFactory.getLogger(TeiParser.class);

    private static final String TEI_NS = "http://www.tei-c.org/ns/1.0";

    /** Leftover inline citation noise, e.g. "[12]", "(Smith et al., 2020)". */
    private static final Pattern BRACKET_CITATION = Pattern.compile("\\[\\s*\\d+(\\s*[,-]\\s*\\d+)*\\s*]");
    private static final Pattern PAREN_CITATION =
            Pattern.compile("\\([^()]*?(?:et al\\.|,\\s*\\d{4})[^()]*?\\)");

    /** GROBID coords look like "p;1,72.00,183.79,467.72,86.30" - page is field 2. */
    private static final Pattern COORD_PAGE = Pattern.compile("^[^;]*;(\\d+),");

    public TeiDocument parse(String teiXml) {
        Document dom = toDom(teiXml);

        Element root = dom.getDocumentElement();
        Element header = firstChild(root, "teiHeader");

        return new TeiDocument(
                parseTitle(header),
                parseAuthors(header),
                parseYear(header),
                parseDoi(header),
                parseAbstract(header),
                parseSections(firstChild(root, "text")));
    }

    // ---------------------------------------------------------------- header

    private String parseTitle(Element header) {
        if (header == null) {
            return null;
        }
        Element titleStmt = descendant(header, "titleStmt");
        Element title = titleStmt == null ? null : firstChild(titleStmt, "title");
        String text = title == null ? null : clean(textOf(title));
        return text == null || text.isBlank() ? null : text;
    }

    private List<String> parseAuthors(Element header) {
        List<String> authors = new ArrayList<>();
        if (header == null) {
            return authors;
        }
        Element analytic = descendant(header, "analytic");
        Element scope = analytic != null ? analytic : header;

        for (Element author : childrenByTag(scope, "author")) {
            Element persName = descendant(author, "persName");
            if (persName == null) {
                continue;
            }
            String surname = textOfFirst(persName, "surname");
            List<String> forenames = childrenByTag(persName, "forename").stream()
                    .map(this::textOf)
                    .filter(s -> s != null && !s.isBlank())
                    .toList();

            if (surname == null || surname.isBlank()) {
                continue;
            }
            // "Smith, J. R." - initials only, matching the BibTeX normalization
            String initials = forenames.stream()
                    .map(f -> f.substring(0, 1).toUpperCase() + ".")
                    .reduce((a, b) -> a + " " + b)
                    .orElse("");
            authors.add(initials.isBlank() ? surname.trim() : surname.trim() + ", " + initials);
        }
        return authors;
    }

    private Integer parseYear(Element header) {
        if (header == null) {
            return null;
        }
        for (Element date : descendantsByTag(header, "date")) {
            String when = date.getAttribute("when");
            Matcher m = Pattern.compile("(\\d{4})").matcher(when.isBlank() ? textOf(date) : when);
            if (m.find()) {
                int year = Integer.parseInt(m.group(1));
                if (year >= 1900 && year <= 2100) {
                    return year;
                }
            }
        }
        return null;
    }

    private String parseDoi(Element header) {
        if (header == null) {
            return null;
        }
        for (Element idno : descendantsByTag(header, "idno")) {
            if ("DOI".equalsIgnoreCase(idno.getAttribute("type"))) {
                String doi = clean(textOf(idno));
                if (doi != null && !doi.isBlank()) {
                    return doi;
                }
            }
        }
        return null;
    }

    private String parseAbstract(Element header) {
        if (header == null) {
            return null;
        }
        Element abstractEl = descendant(header, "abstract");
        if (abstractEl == null) {
            return null;
        }
        String text = paragraphsOf(abstractEl).stream()
                .map(TeiDocument.Paragraph::text)
                .reduce((a, b) -> a + "\n\n" + b)
                .orElseGet(() -> clean(textOf(abstractEl)));
        return text == null || text.isBlank() ? null : text;
    }

    // ------------------------------------------------------------------ body

    private List<TeiDocument.Section> parseSections(Element text) {
        List<TeiDocument.Section> sections = new ArrayList<>();
        if (text == null) {
            return sections;
        }
        Element body = firstChild(text, "body");
        if (body == null) {
            return sections;
        }

        // GROBID emits a flat list of <div>s for the body; nested <div>s appear
        // occasionally for subsections, so recurse to catch those too.
        collectDivs(body, sections);

        // Some PDFs yield paragraphs directly under <body> with no <div> at all.
        if (sections.isEmpty()) {
            List<TeiDocument.Paragraph> loose = paragraphsOf(body);
            if (!loose.isEmpty()) {
                sections.add(new TeiDocument.Section("Body", null, loose));
            }
        }
        return sections;
    }

    private void collectDivs(Element parent, List<TeiDocument.Section> out) {
        for (Element div : childrenByTag(parent, "div")) {
            Element head = firstChild(div, "head");
            String heading = head == null ? null : clean(textOf(head));
            String number = head == null ? null : blankToNull(head.getAttribute("n"));

            List<TeiDocument.Paragraph> paragraphs = paragraphsOf(div);
            if (!paragraphs.isEmpty()) {
                out.add(new TeiDocument.Section(
                        heading == null || heading.isBlank() ? "Body" : heading,
                        number,
                        paragraphs));
            }
            // recurse for subsections
            collectDivs(div, out);
        }
    }

    /** Direct {@code <p>} children, cleaned, with a page number where available. */
    private List<TeiDocument.Paragraph> paragraphsOf(Element parent) {
        List<TeiDocument.Paragraph> paragraphs = new ArrayList<>();
        for (Element p : childrenByTag(parent, "p")) {
            String text = clean(textOf(p));
            if (text == null || text.isBlank()) {
                continue;
            }
            paragraphs.add(new TeiDocument.Paragraph(text, pageOf(p)));
        }
        return paragraphs;
    }

    private Integer pageOf(Element element) {
        String coords = element.getAttribute("coords");
        if (coords.isBlank()) {
            return null;
        }
        Matcher m = COORD_PAGE.matcher(coords);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    // --------------------------------------------------------------- helpers

    /**
     * Concatenates descendant text, but drops {@code <ref>} elements so
     * bibliography markers do not pollute the embedded text. A chunk reading
     * "as shown by [12], [13], [14]" embeds badly and retrieves nothing useful.
     */
    private String textOf(Node node) {
        StringBuilder sb = new StringBuilder();
        appendText(node, sb);
        return sb.toString();
    }

    private void appendText(Node node, StringBuilder sb) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            switch (child.getNodeType()) {
                case Node.TEXT_NODE -> sb.append(child.getNodeValue());
                case Node.ELEMENT_NODE -> {
                    String name = localName(child);
                    // <ref> = citation/figure marker, <formula> = unreadable as text
                    if ("ref".equals(name) || "formula".equals(name)) {
                        sb.append(' ');
                    }
                    else {
                        appendText(child, sb);
                        if ("s".equals(name)) {
                            // sentence-segmented mode: keep sentences apart
                            sb.append(' ');
                        }
                    }
                }
                default -> {
                    // comments, PIs - ignore
                }
            }
        }
    }

    /** Collapses whitespace, removes de-hyphenation artefacts and citation noise. */
    private String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace('\u00a0', ' ');
        s = BRACKET_CITATION.matcher(s).replaceAll("");
        s = PAREN_CITATION.matcher(s).replaceAll("");
        // PDF line-break hyphenation: "trans- port" -> "transport"
        s = s.replaceAll("(\\p{L})-\\s+(\\p{L})", "$1$2");
        s = s.replaceAll("\\s+([,.;:)])", "$1");
        s = s.replaceAll("\\s+", " ");
        return s.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String localName(Node node) {
        return node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
    }

    private Element firstChild(Element parent, String tag) {
        if (parent == null) {
            return null;
        }
        List<Element> found = childrenByTag(parent, tag);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Direct element children with the given local name. */
    private List<Element> childrenByTag(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tag.equals(localName(child))) {
                out.add((Element) child);
            }
        }
        return out;
    }

    private Element descendant(Element parent, String tag) {
        List<Element> all = descendantsByTag(parent, tag);
        return all.isEmpty() ? null : all.get(0);
    }

    private List<Element> descendantsByTag(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        NodeList nodes = parent.getElementsByTagNameNS(TEI_NS, tag);
        if (nodes.getLength() == 0) {
            nodes = parent.getElementsByTagName(tag);
        }
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    private String textOfFirst(Element parent, String tag) {
        Element el = firstChild(parent, tag);
        return el == null ? null : clean(textOf(el));
    }

    private Document toDom(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // TEI from a local service, but harden anyway: no DTDs, no entities
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document dom = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            dom.getDocumentElement().normalize();
            return dom;
        }
        catch (ParserConfigurationException | SAXException | IOException e) {
            log.error("Could not parse TEI XML ({} chars)", xml.length());
            throw new TeiParseException("Malformed TEI XML from GROBID", e);
        }
    }

    /** TEI that GROBID produced but we could not read. */
    public static class TeiParseException extends RuntimeException {
        public TeiParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
