package com.infrrd.loansearch;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuerySemanticsTest {
    @Test void supportsPhraseBooleanAndPrefixQueries() throws Exception {
        try (var analyzer = new StandardAnalyzer(); var directory = new ByteBuffersDirectory()) {
            try (var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                add(writer, "cash to close appears here");
                add(writer, "cash reserve and signature");
                add(writer, "signature document only");
            }
            try (var reader = DirectoryReader.open(directory)) {
                var searcher = new IndexSearcher(reader); var parser = new QueryParser("content", analyzer);
                parser.setDefaultOperator(QueryParser.Operator.AND);
                assertEquals(1, searcher.count(parser.parse("\"cash to close\"")));
                assertEquals(3, searcher.count(parser.parse("cash OR signature")));
                assertEquals(1, searcher.count(parser.parse("cash NOT signature")));
                assertEquals(2, searcher.count(parser.parse("sign*")));
            }
        }
    }

    private static void add(IndexWriter writer, String text) throws Exception {
        Document d = new Document(); d.add(new TextField("content", text, TextField.Store.NO)); writer.addDocument(d);
    }
}
