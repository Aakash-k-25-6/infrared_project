package com.infrrd.loansearch;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.highlight.*;
import org.apache.lucene.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.*;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class SearchController {
    private final PackageIndex index;
    public SearchController(PackageIndex index) { this.index=index; }

    @GetMapping("/package")
    public synchronized Map<String,Object> packageTree() throws IOException {
        long start=System.nanoTime(); PackageIndex.Snapshot s=index.snapshot();
        return Map.of("documents",s.tree,"warnings",s.warnings,"pageCount",s.pageCount,"timingsMs",Map.of("manifestAndIndex",s.buildMs,"total",elapsed(start)));
    }

    @GetMapping("/search")
    public synchronized Map<String,Object> search(@RequestParam String q, @RequestParam(defaultValue="20") int limit,
                                    @RequestParam(defaultValue="0") int offset,
                                    @RequestParam(required=false) String type) throws Exception {
        int pageSize=Math.max(1,Math.min(limit,100)); int start=Math.max(0,Math.min(offset,10_000));
        long total=System.nanoTime(); long check=System.nanoTime(); PackageIndex.Snapshot s=index.snapshot(); long manifestMs=elapsed(check);
        long parse=System.nanoTime(); QueryParser parser=new QueryParser("content",s.analyzer); parser.setDefaultOperator(QueryParser.Operator.AND);
        Query query;
        try { query=parser.parse(q); } catch (org.apache.lucene.queryparser.classic.ParseException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid query: "+e.getMessage()); }
        long parseMs=elapsed(parse);
        if(type!=null && !type.isBlank()) query=new BooleanQuery.Builder().add(query,BooleanClause.Occur.MUST).add(new TermQuery(new org.apache.lucene.index.Term("documentType",type)),BooleanClause.Occur.FILTER).build();
        long searchStart=System.nanoTime(); TopDocs hits=s.searcher.search(query,start+pageSize); long searchMs=elapsed(searchStart);
        long snippetStart=System.nanoTime(); List<Map<String,Object>> results=new ArrayList<>();
        QueryScorer scorer=new QueryScorer(query,"content"); Highlighter highlighter=new Highlighter(new SimpleHTMLFormatter("<mark>","</mark>"),scorer);
        for(int i=start;i<Math.min(hits.scoreDocs.length,start+pageSize);i++) {
            ScoreDoc hit=hits.scoreDocs[i];
            Document d=s.searcher.doc(hit.doc); String text=d.get("content"); String snippet;
            try (TokenStream ts=s.analyzer.tokenStream("content",text)) { snippet=highlighter.getBestFragment(ts,text); }
            if(snippet==null) snippet=excerpt(text);
            Map<String,Object> row=new LinkedHashMap<>(); row.put("pageId",d.get("pageId")); row.put("documentId",d.get("documentId")); row.put("document",d.get("documentName"));
            row.put("type",d.get("documentType")); row.put("versionId",d.get("versionId")); row.put("version",d.get("versionName"));
            row.put("page",d.getField("pageNumberStored").numericValue()); row.put("score",hit.score); row.put("snippet",snippet); results.add(row);
        }
        long snippetMs=elapsed(snippetStart);
        return Map.of("query",q,"totalHits",hits.totalHits.value,"offset",start,"limit",pageSize,"hasMore",start+results.size()<hits.totalHits.value,"results",results,"warnings",s.warnings,
                "timingsMs",Map.of("manifestCheck",manifestMs,"queryParse",parseMs,"search",searchMs,"snippet",snippetMs,"total",elapsed(total)));
    }

    @GetMapping("/pages/{pageId}")
    public synchronized Map<String,Object> page(@PathVariable String pageId) throws IOException {
        PackageIndex.Snapshot s=index.snapshot(); TopDocs found=s.searcher.search(new TermQuery(new org.apache.lucene.index.Term("pageId",pageId)),1);
        if(found.scoreDocs.length==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Page not found");
        Document d=s.searcher.doc(found.scoreDocs[0].doc);
        return Map.of("pageId",pageId,"document",d.get("documentName"),"version",d.get("versionName"),"page",d.getField("pageNumberStored").numericValue(),"text",d.get("content"));
    }

    @GetMapping("/pdf")
    public ResponseEntity<Resource> pdf(@RequestHeader(value="Range", required=false) String range) throws IOException {
        var path=index.pdfPath();
        if(!Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Sample PDF not found");
        long fileSize=Files.size(path);
        HttpHeaders headers=new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentLength(fileSize);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=\""+path.getFileName().toString().replace("\"", "")+"\"");
        headers.set("Accept-Ranges","bytes");
        if(range==null || range.isBlank()) return ResponseEntity.ok().headers(headers).body(new InputStreamResource(Files.newInputStream(path)));

        Matcher matcher=Pattern.compile("bytes=(\\d+)-(\\d*)").matcher(range.trim());
        if(!matcher.matches()) return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE,"bytes */"+fileSize).build();
        long start=Long.parseLong(matcher.group(1));
        long end=matcher.group(2).isBlank()?fileSize-1:Math.min(Long.parseLong(matcher.group(2)),fileSize-1);
        if(start>=fileSize || end<start) return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE,"bytes */"+fileSize).build();
        long length=end-start+1;
        InputStream source=Files.newInputStream(path);
        source.skipNBytes(start);
        InputStream bounded=new FilterInputStream(source) {
            private long remaining=length;
            @Override public int read() throws IOException {
                if(remaining<=0)return -1;
                int value=super.read(); if(value>=0)remaining--; return value;
            }
            @Override public int read(byte[] bytes,int offset,int count) throws IOException {
                if(remaining<=0)return -1;
                int read=super.read(bytes,offset,(int)Math.min(count,remaining)); if(read>0)remaining-=read; return read;
            }
        };
        headers.setContentLength(length);
        headers.set(HttpHeaders.CONTENT_RANGE,"bytes "+start+"-"+end+"/"+fileSize);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers).body(new InputStreamResource(bounded));
    }

    private static long elapsed(long start) { return (System.nanoTime()-start)/1_000_000; }
    private static String excerpt(String text) { return text.length()<=260?text:text.substring(0,260)+"…"; }
}
