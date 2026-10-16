package com.infrrd.loansearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** One Lucene document per source page. Reloads publish a complete immutable generation. */
@Component
public class PackageIndex implements AutoCloseable {
    private static final String CONTENT = "content";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private volatile String manifestFingerprint;
    private Path manifestPath() { return Path.of(System.getProperty("loan.package.manifest", "data/package.json")).toAbsolutePath(); }
    public Path pdfPath() { return Path.of(System.getProperty("loan.package.pdf", "../package_seed20260911 (1).pdf")).toAbsolutePath().normalize(); }
    private Path layoutPath() { return Path.of(System.getProperty("loan.package.layout", "../package_seed20260911_layout (2).txt")).toAbsolutePath().normalize(); }

    public synchronized Snapshot snapshot() throws IOException {
        Path manifest = manifestPath();
        Path pdf = pdfPath();
        Path layout = layoutPath();
        String fingerprint = fingerprint(manifest) + ":" + fileFingerprint(pdf) + ":" + fileFingerprint(layout);
        Snapshot existing = current.get();
        if (existing == null || !Objects.equals(fingerprint, manifestFingerprint)) reload(manifest, fingerprint);
        return current.get();
    }

    private void reload(Path manifest, String fingerprint) throws IOException {
        long started = System.nanoTime();
        Directory dir = new ByteBuffersDirectory();
        StandardAnalyzer analyzer = new StandardAnalyzer();
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer));
        List<Map<String,Object>> tree = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int pages = 0;
        if (Files.isRegularFile(manifest)) {
            JsonNode root = mapper.readTree(manifest.toFile());
            JsonNode docs = root.path("documents");
            if (docs.isArray()) for (JsonNode doc : docs) {
                String docId = value(doc, "id", value(doc,"name","document"));
                String docName = value(doc,"name",docId), type = value(doc,"type","Other");
                List<Map<String,Object>> versions = new ArrayList<>();
                JsonNode versionNodes = doc.path("versions");
                if (versionNodes.isArray()) for (JsonNode version : versionNodes) {
                    String versionId = value(version,"id",value(version,"name","version"));
                    String versionName = value(version,"name",versionId);
                    List<Integer> pageNumbers = new ArrayList<>();
                    JsonNode pageNodes = version.path("pages");
                    if (pageNodes.isArray()) for (JsonNode page : pageNodes) {
                        int number = page.path("number").asInt(page.path("pageNumber").asInt(pageNumbers.size()+1));
                        String rawPath = page.isTextual() ? page.asText() : value(page,"path",value(page,"file",""));
                        if (rawPath.isBlank()) continue;
                        Path textPath = manifest.getParent().resolve(rawPath).normalize();
                        if (!textPath.startsWith(manifest.getParent().normalize())) { warnings.add("Ignored path outside package: " + rawPath); continue; }
                        if (!Files.isRegularFile(textPath)) { warnings.add("Missing text: " + rawPath); continue; }
                        String text = Files.readString(textPath);
                        Document indexed = new Document();
                        indexed.add(new StringField("pageId", docId+":"+versionId+":"+number, Field.Store.YES));
                        indexed.add(new StringField("documentId", docId, Field.Store.YES));
                        indexed.add(new StringField("versionId", versionId, Field.Store.YES));
                        indexed.add(new StringField("documentName", docName, Field.Store.YES));
                        indexed.add(new StringField("documentType", type, Field.Store.YES));
                        indexed.add(new StringField("versionName", versionName, Field.Store.YES));
                        indexed.add(new IntPoint("pageNumber", number));
                        indexed.add(new StoredField("pageNumberStored", number));
                        indexed.add(new StoredField(CONTENT, text));
                        indexed.add(new TextField(CONTENT, text, Field.Store.NO));
                        writer.addDocument(indexed); pageNumbers.add(number); pages++;
                    }
                    Map<String,Object> versionMap = new LinkedHashMap<>();
                    versionMap.put("id",versionId); versionMap.put("name",versionName); versionMap.put("pageCount",pageNumbers.size()); versionMap.put("pages",pageNumbers);
                    versions.add(versionMap);
                }
                Map<String,Object> docMap = new LinkedHashMap<>();
                docMap.put("id",docId); docMap.put("name",docName); docMap.put("type",type); docMap.put("versions",versions);
                tree.add(docMap);
            }
        } else {
            Path pdf = pdfPath();
            Path layout = layoutPath();
            if (Files.isRegularFile(pdf)) pages = indexPdf(pdf, layout, writer, warnings);
            else warnings.add("Neither package manifest nor sample PDF was found. Expected " + manifest + " or " + pdf);
            if (pages > 0) {
                List<Map<String,Object>> versions = List.of(Map.of("id","original","name","Original PDF","pageCount",pages));
                tree.add(Map.of("id","sample-loan","name",pdf.getFileName().toString(),"type","Loan package","versions",versions));
            }
        }
        writer.close();
        DirectoryReader reader = DirectoryReader.open(dir);
        Snapshot next = new Snapshot(dir, analyzer, reader, tree, warnings, pages, (System.nanoTime()-started)/1_000_000);
        Snapshot old = current.getAndSet(next);
        manifestFingerprint = fingerprint;
        if (old != null) old.close();
    }

    private static String fingerprint(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return "missing";
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String fileFingerprint(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return "missing";
        return Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis();
    }

    private static int indexPdf(Path pdf, Path layout, IndexWriter writer, List<String> warnings) throws IOException {
        String[] layoutPages = Files.isRegularFile(layout) ? Files.readString(layout).split(String.valueOf((char) 12), -1) : new String[0];
        try (PDDocument document = PDDocument.load(pdf.toFile(), MemoryUsageSetting.setupTempFileOnly())) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                String text = page <= layoutPages.length && !layoutPages[page-1].isBlank()
                        ? layoutPages[page-1].strip() : stripper.getText(document).strip();
                if (text.isBlank()) { warnings.add("No extractable text on PDF page " + page); continue; }
                Document indexed = new Document();
                indexed.add(new StringField("pageId", "sample-loan:original:"+page, Field.Store.YES));
                indexed.add(new StringField("documentId", "sample-loan", Field.Store.YES));
                indexed.add(new StringField("versionId", "original", Field.Store.YES));
                indexed.add(new StringField("documentName", pdf.getFileName().toString(), Field.Store.YES));
                indexed.add(new StringField("documentType", "Loan package", Field.Store.YES));
                indexed.add(new StringField("versionName", "Original PDF", Field.Store.YES));
                indexed.add(new IntPoint("pageNumber", page));
                indexed.add(new StoredField("pageNumberStored", page));
                indexed.add(new StoredField(CONTENT, text));
                indexed.add(new TextField(CONTENT, text, Field.Store.NO));
                writer.addDocument(indexed);
            }
            return document.getNumberOfPages();
        }
    }

    private static String value(JsonNode node, String key, String fallback) {
        JsonNode v = node.path(key); return v.isMissingNode() || v.isNull() ? fallback : v.asText(fallback);
    }
    @Override public void close() { Snapshot s=current.getAndSet(null); if(s!=null) s.close(); }

    public static final class Snapshot implements AutoCloseable {
        final Directory directory; final StandardAnalyzer analyzer; final DirectoryReader reader; final IndexSearcher searcher;
        final List<Map<String,Object>> tree; final List<String> warnings; final int pageCount; final long buildMs;
        Snapshot(Directory d, StandardAnalyzer a, DirectoryReader r, List<Map<String,Object>> t, List<String> w, int p, long ms) {
            directory=d; analyzer=a; reader=r; searcher=new IndexSearcher(r); tree=List.copyOf(t); warnings=List.copyOf(w); pageCount=p; buildMs=ms;
        }
        @Override public void close() { try { reader.close(); directory.close(); analyzer.close(); } catch(IOException ignored) {} }
    }
}
