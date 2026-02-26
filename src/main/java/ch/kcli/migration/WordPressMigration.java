package ch.kcli.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WordPressMigration {

    private static final String WP_BASE_URL = "https://www.kcli.ch";
    private static final String WP_API_URL = WP_BASE_URL + "/wp-json/wp/v2";
    private static final Path CONTENT_DIR = Path.of("src/main/resources/content");
    private static final Path POSTS_DIR = CONTENT_DIR.resolve("posts");
    private static final Path IMAGES_DIR = Path.of("src/main/resources/static/images");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, String> mediaUrlMap = new HashMap<>();

    public static void main(String[] args) throws Exception {
        WordPressMigration migration = new WordPressMigration();
        migration.run();
    }

    public WordPressMigration() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public void run() throws Exception {
        System.out.println("Starting WordPress to Roq migration...");

        Files.createDirectories(CONTENT_DIR);
        Files.createDirectories(POSTS_DIR);
        Files.createDirectories(IMAGES_DIR);

        System.out.println("\n=== Fetching media ===");
        fetchAndDownloadMedia();

        System.out.println("\n=== Migrating pages ===");
        migratePages();

        System.out.println("\n=== Migrating posts ===");
        migratePosts();

        System.out.println("\n=== Migration complete! ===");
    }

    private void fetchAndDownloadMedia() throws Exception {
        String url = WP_API_URL + "/media?per_page=100";
        JsonNode media = fetchJson(url);

        for (JsonNode item : media) {
            String sourceUrl = item.get("source_url").asText();
            String filename = extractFilename(sourceUrl);
            Path localPath = IMAGES_DIR.resolve(filename);

            mediaUrlMap.put(sourceUrl, "/static/images/" + filename);

            if (!Files.exists(localPath)) {
                System.out.println("Downloading: " + filename);
                downloadFile(sourceUrl, localPath);
            } else {
                System.out.println("Already exists: " + filename);
            }
        }
    }

    private void migratePages() throws Exception {
        String url = WP_API_URL + "/pages?per_page=100";
        JsonNode pages = fetchJson(url);

        for (JsonNode page : pages) {
            String slug = page.get("slug").asText();
            String title = page.get("title").get("rendered").asText();
            String htmlContent = page.get("content").get("rendered").asText();

            String filename = mapSlugToFilename(slug);
            String markdown = convertToMarkdown(htmlContent);
            String frontmatter = buildFrontmatter(title, "main", null);

            Path filePath = CONTENT_DIR.resolve(filename);
            String content = frontmatter + markdown;
            Files.writeString(filePath, content);

            System.out.println("Created: " + filename + " (" + title + ")");
        }
    }

    private void migratePosts() throws Exception {
        String url = WP_API_URL + "/posts?per_page=100";
        JsonNode posts = fetchJson(url);

        for (JsonNode post : posts) {
            String slug = post.get("slug").asText();
            String title = post.get("title").get("rendered").asText();
            String htmlContent = post.get("content").get("rendered").asText();
            String dateStr = post.get("date").asText();

            LocalDate date = LocalDate.parse(dateStr.substring(0, 10));
            String filename = date.format(DateTimeFormatter.ISO_LOCAL_DATE) + "-" + slug + ".md";

            String markdown = convertToMarkdown(htmlContent);
            String frontmatter = buildFrontmatter(title, "post", date);

            Path filePath = POSTS_DIR.resolve(filename);
            String content = frontmatter + markdown;
            Files.writeString(filePath, content);

            System.out.println("Created: posts/" + filename + " (" + title + ")");
        }
    }

    private String mapSlugToFilename(String slug) {
        return switch (slug) {
            case "der-club" -> "index.md";
            default -> slug + ".md";
        };
    }

    private String buildFrontmatter(String title, String layout, LocalDate date) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: \"").append(escapeYaml(title)).append("\"\n");
        sb.append("layout: ").append(layout).append("\n");
        if (date != null) {
            sb.append("date: ").append(date.format(DateTimeFormatter.ISO_LOCAL_DATE)).append("\n");
        }
        sb.append("---\n\n");
        return sb.toString();
    }

    private String escapeYaml(String text) {
        return text.replace("\"", "\\\"");
    }

    private String convertToMarkdown(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        html = replaceImageUrls(html);
        Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings().indentAmount(0).outline(false);

        StringBuilder markdown = new StringBuilder();
        convertNode(doc.body(), markdown);

        return markdown.toString()
                .replaceAll("\n{3,}", "\n\n")
                .trim() + "\n";
    }

    private String replaceImageUrls(String html) {
        for (Map.Entry<String, String> entry : mediaUrlMap.entrySet()) {
            html = html.replace(entry.getKey(), entry.getValue());
        }
        return html;
    }

    private void convertNode(Element element, StringBuilder sb) {
        for (Node child : element.childNodes()) {
            if (child instanceof TextNode textNode) {
                String text = textNode.text();
                if (!text.isBlank()) {
                    sb.append(text);
                }
            } else if (child instanceof Element el) {
                convertElement(el, sb);
            }
        }
    }

    private void convertElement(Element el, StringBuilder sb) {
        String tag = el.tagName().toLowerCase();

        switch (tag) {
            case "h1" -> {
                sb.append("\n\n# ");
                convertNode(el, sb);
                sb.append("\n\n");
            }
            case "h2" -> {
                sb.append("\n\n## ");
                convertNode(el, sb);
                sb.append("\n\n");
            }
            case "h3" -> {
                sb.append("\n\n### ");
                convertNode(el, sb);
                sb.append("\n\n");
            }
            case "h4" -> {
                sb.append("\n\n#### ");
                convertNode(el, sb);
                sb.append("\n\n");
            }
            case "p" -> {
                sb.append("\n\n");
                convertNode(el, sb);
                sb.append("\n\n");
            }
            case "br" -> sb.append("\n");
            case "strong", "b" -> {
                sb.append("**");
                convertNode(el, sb);
                sb.append("**");
            }
            case "em", "i" -> {
                sb.append("*");
                convertNode(el, sb);
                sb.append("*");
            }
            case "a" -> {
                String href = el.attr("href");
                sb.append("[");
                convertNode(el, sb);
                sb.append("](").append(href).append(")");
            }
            case "img" -> {
                String src = el.attr("src");
                String alt = el.attr("alt");
                sb.append("![").append(alt).append("](").append(src).append(")");
            }
            case "ul" -> {
                sb.append("\n");
                for (Element li : el.children()) {
                    if (li.tagName().equalsIgnoreCase("li")) {
                        sb.append("\n- ");
                        convertNode(li, sb);
                    }
                }
                sb.append("\n");
            }
            case "ol" -> {
                sb.append("\n");
                int i = 1;
                for (Element li : el.children()) {
                    if (li.tagName().equalsIgnoreCase("li")) {
                        sb.append("\n").append(i++).append(". ");
                        convertNode(li, sb);
                    }
                }
                sb.append("\n");
            }
            case "table" -> {
                sb.append("\n\n");
                convertTable(el, sb);
                sb.append("\n\n");
            }
            case "figure" -> {
                Elements imgs = el.getElementsByTag("img");
                Elements captions = el.getElementsByTag("figcaption");
                for (Element img : imgs) {
                    String src = img.attr("src");
                    String alt = img.attr("alt");
                    if (captions.size() > 0) {
                        alt = captions.first().text();
                    }
                    sb.append("\n\n![").append(alt).append("](").append(src).append(")\n\n");
                }
            }
            case "video" -> {
                String src = el.attr("src");
                if (src.isEmpty()) {
                    Elements sources = el.getElementsByTag("source");
                    if (!sources.isEmpty()) {
                        src = sources.first().attr("src");
                    }
                }
                if (!src.isEmpty()) {
                    sb.append("\n\n[Video](").append(src).append(")\n\n");
                }
            }
            case "blockquote" -> {
                sb.append("\n\n> ");
                String content = el.text().replace("\n", "\n> ");
                sb.append(content);
                sb.append("\n\n");
            }
            case "script", "style" -> {
                // Skip scripts and styles
            }
            case "div", "span", "section", "article", "header", "footer", "main", "nav" -> {
                convertNode(el, sb);
            }
            default -> convertNode(el, sb);
        }
    }

    private void convertTable(Element table, StringBuilder sb) {
        Elements rows = table.getElementsByTag("tr");
        if (rows.isEmpty()) return;

        Element headerRow = rows.first();
        Elements headerCells = headerRow.getElementsByTag("th");
        if (headerCells.isEmpty()) {
            headerCells = headerRow.getElementsByTag("td");
        }

        if (!headerCells.isEmpty()) {
            sb.append("| ");
            for (Element cell : headerCells) {
                sb.append(cell.text()).append(" | ");
            }
            sb.append("\n|");
            for (int i = 0; i < headerCells.size(); i++) {
                sb.append(" --- |");
            }
            sb.append("\n");
        }

        for (int i = 1; i < rows.size(); i++) {
            Element row = rows.get(i);
            Elements cells = row.getElementsByTag("td");
            if (!cells.isEmpty()) {
                sb.append("| ");
                for (Element cell : cells) {
                    sb.append(cell.text()).append(" | ");
                }
                sb.append("\n");
            }
        }
    }

    private JsonNode fetchJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch " + url + ": " + response.statusCode());
        }

        return objectMapper.readTree(response.body());
    }

    private void downloadFile(String url, Path destination) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() == 200) {
            Files.copy(response.body(), destination, StandardCopyOption.REPLACE_EXISTING);
        } else {
            System.err.println("Failed to download: " + url + " (" + response.statusCode() + ")");
        }
    }

    private String extractFilename(String url) {
        String path = URI.create(url).getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
