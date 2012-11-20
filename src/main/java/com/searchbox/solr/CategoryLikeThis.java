/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.searchbox.solr;

import com.searchbox.commons.params.SenseParams;
import com.searchbox.lucene.CategoryQuery;
import com.searchbox.math.RealTermFreqVector;
import com.searchbox.sense.CategorizationBase;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.*;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.FacetParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SimpleFacets;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.*;

/**
 * Solr MoreLikeThis --
 *
 * Return similar documents either based on a single document or based on posted
 * text.
 *
 * @since solr 1.3
 */
public class CategoryLikeThis extends RequestHandlerBase {
    // Pattern is thread safe -- TODO? share this with general 'fl' param

    private static final Pattern splitList = Pattern.compile(",| ");

    @Override
    public void init(NamedList args) {
        super.init(args);
    }

    @Override
    public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
        SolrParams params = req.getParams();
        String senseField = params.get(SenseParams.SENSE_FIELD, SenseParams.DEFAULT_SENSE_FIELD);
        BooleanQuery catfilter = new BooleanQuery();
        // Set field flags
        ReturnFields returnFields = new ReturnFields(req);
        rsp.setReturnFields(returnFields);
        int flags = 0;
        if (returnFields.wantsScore()) {
            flags |= SolrIndexSearcher.GET_SCORES;
        }

        String defType = params.get(QueryParsing.DEFTYPE, QParserPlugin.DEFAULT_QTYPE);
        String q = params.get(CommonParams.Q);
        Query query = null;
        SortSpec sortSpec = null;
        List<Query> filters = new LinkedList<Query>();
        List<RealTermFreqVector> prototypetfs = new LinkedList<RealTermFreqVector>();

        try {
            if (q != null) {
                QParser parser = QParser.getParser(q, defType, req);
                query = parser.getQuery();
                sortSpec = parser.getSort(true);
            }

            String[] fqs = req.getParams().getParams(CommonParams.FQ);
            if (fqs != null && fqs.length != 0) {
                for (String fq : fqs) {
                    if (fq != null && fq.trim().length() != 0) {
                        QParser fqp = QParser.getParser(fq, null, req);
                        filters.add(fqp.getQuery());
                    }
                }
            }
        } catch (ParseException e) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
        }

        SolrIndexSearcher searcher = req.getSearcher();
        DocListAndSet cltDocs = null;


        // Parse Required Params
        // This will either have a single Reader or valid query
        Reader reader = null;
        try {
            if (q == null || q.trim().length() < 1) {
                Iterable<ContentStream> streams = req.getContentStreams();
                if (streams != null) {
                    Iterator<ContentStream> iter = streams.iterator();
                    if (iter.hasNext()) {
                        reader = iter.next().getReader();
                    }
                    if (iter.hasNext()) {
                        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                                "SenseLikeThis does not support multiple ContentStreams");
                    }
                }
            }

            int start = params.getInt(CommonParams.START, 0);
            int rows = params.getInt(CommonParams.ROWS, 10);

            // Find documents SenseLikeThis - either with a reader or a query
            // --------------------------------------------------------------------------------
            if (reader != null) {
                throw new RuntimeException("SLT based on a reader is not yet implemented");
            } else if (q != null) {
                
                System.out.println("Query for category:\t"+query);
                DocList match = searcher.getDocList(query, null, null, 0, 10, flags); // get first 10

                // Create the TF of blah blah blah
                DocIterator iterator = match.iterator();
            while (iterator.hasNext()) {
                    // do a MoreLikeThis query for each document in results
                    int id = iterator.nextDoc();
                    System.out.println("Working on doc:\t"+id);
                    
                    HashMap<String, Float> termFreqMap = new HashMap<String, Float>();
                    TermsEnum termsEnum = searcher.getAtomicReader().getTermVector(id, senseField).iterator(null);
                    BytesRef text;
                    while ((text = termsEnum.next()) != null) {
                        String term = text.utf8ToString();
                        System.out.println("\tAdding Term:\t"+term);
                        TermQuery tq = new TermQuery(new Term(senseField, term));
                        catfilter.add(tq, BooleanClause.Occur.SHOULD);
                        int freq = (int) termsEnum.totalTermFreq();
                        Float prevFreq = termFreqMap.get(term);
                        if (prevFreq == null) {
                            termFreqMap.put(term, (float) freq);
                        } else {
                            termFreqMap.put(term, (float) freq + prevFreq);
                        }
                    }
                    prototypetfs.add(new RealTermFreqVector(termFreqMap));
                }
            } else {
                throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                        "CategoryLikeThis requires either a query (?q=) or text to find similar documents.");
            }

            
            System.out.println("document filter is: \t"+catfilter);
            CategorizationBase model = new CategorizationBase(prototypetfs);

            CategoryQuery clt = CategoryQuery.CategoryQueryForDocument(catfilter, model, searcher.getIndexReader(), senseField);
            DocSet filtered = searcher.getDocSet(filters);
            cltDocs = searcher.getDocListAndSet(clt, filtered, Sort.RELEVANCE, start, rows, flags);


        } finally {
            if (reader != null) {
                reader.close();
            }
        }

        if (cltDocs == null) {
            cltDocs = new DocListAndSet(); // avoid NPE
        }
        rsp.add("response", cltDocs.docList);

        // maybe facet the results
        if (params.getBool(FacetParams.FACET, false)) {
            if (cltDocs.docSet == null) {
                rsp.add("facet_counts", null);
            } else {
                SimpleFacets f = new SimpleFacets(req, cltDocs.docSet, params);
                rsp.add("facet_counts", f.getFacetCounts());
            }
        }



    }

    //////////////////////// SolrInfoMBeans methods //////////////////////
    @Override
    public String getDescription() {
        return "Searchbox SenseLikeThis";
    }

    @Override
    public String getSource() {
        return "$URL: https://svn.apache.org/repos/asf/lucene/dev/branches/lucene_solr_4_0/solr/core/src/java/org/apache/solr/handler/MoreLikeThisHandler.java $";
    }

    @Override
    public URL[] getDocs() {
        try {
            return new URL[]{new URL("http://wiki.apache.org/solr/MoreLikeThis")};
        } catch (MalformedURLException ex) {
            return null;
        }
    }
}