/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.fetch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.search.TotalHits;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.index.fieldvisitor.LeafStoredFieldLoader;
import org.elasticsearch.index.fieldvisitor.StoredFieldLoader;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.SourceFieldMapper;
import org.elasticsearch.index.mapper.SourceLoader;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.search.LeafNestedDocuments;
import org.elasticsearch.search.NestedDocuments;
import org.elasticsearch.search.SearchContextSourcePrinter;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.fetch.FetchSubPhase.HitContext;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.search.fetch.subphase.InnerHitsContext;
import org.elasticsearch.search.fetch.subphase.InnerHitsPhase;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.lookup.SourceLookup;
import org.elasticsearch.search.profile.ProfileResult;
import org.elasticsearch.search.profile.Profilers;
import org.elasticsearch.tasks.TaskCancelledException;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;

/**
 * Fetch phase of a search request, used to fetch the actual top matching documents to be returned to the client, identified
 * after reducing all of the matches returned by the query phase
 */
public class FetchPhase {
    private static final Logger LOGGER = LogManager.getLogger(FetchPhase.class);

    private final FetchSubPhase[] fetchSubPhases;

    public FetchPhase(List<FetchSubPhase> fetchSubPhases) {
        this.fetchSubPhases = fetchSubPhases.toArray(new FetchSubPhase[fetchSubPhases.size() + 1]);
        this.fetchSubPhases[fetchSubPhases.size()] = new InnerHitsPhase(this);
    }

    public void execute(SearchContext context) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("{}", new SearchContextSourcePrinter(context));
        }

        if (context.isCancelled()) {
            throw new TaskCancelledException("cancelled");
        }

        if (context.docIdsToLoadSize() == 0) {
            // no individual hits to process, so we shortcut
            SearchHits hits = new SearchHits(new SearchHit[0], context.queryResult().getTotalHits(), context.queryResult().getMaxScore());
            context.fetchResult().shardResult(hits, null);
            return;
        }

        Profiler profiler = context.getProfilers() == null ? Profiler.NOOP : Profilers.startProfilingFetchPhase();
        SearchHits hits = null;
        try {
            hits = buildSearchHits(context, profiler);
        } finally {
            // Always finish profiling
            ProfileResult profileResult = profiler.finish();
            // Only set the shardResults if building search hits was successful
            if (hits != null) {
                context.fetchResult().shardResult(hits, profileResult);
            }
        }
    }

    private SearchHits buildSearchHits(SearchContext context, Profiler profiler) {
        DocIdToIndex[] docs = new DocIdToIndex[context.docIdsToLoadSize()];
        for (int index = 0; index < context.docIdsToLoadSize(); index++) {
            docs[index] = new DocIdToIndex(context.docIdsToLoad()[index], index);
        }
        // make sure that we iterate in doc id order
        Arrays.sort(docs);

        SourceLoader sourceLoader = context.newSourceLoader();
        Map<String, Set<String>> storedToRequestedFields = new HashMap<>();
        StoredFieldLoader storedFieldLoader = createStoredFieldLoader(context, sourceLoader, storedToRequestedFields);
        storedFieldLoader = profiler.storedFields(storedFieldLoader);

        FetchContext fetchContext = new FetchContext(context);

        SearchHit[] hits = new SearchHit[context.docIdsToLoadSize()];

        List<FetchSubPhaseProcessor> processors = getProcessors(context.shardTarget(), fetchContext, profiler);
        NestedDocuments nestedDocuments = context.getSearchExecutionContext().getNestedDocuments();

        List<LeafReaderContext> leaves = context.searcher().getIndexReader().leaves();
        LeafNestedDocuments leafNestedDocuments = null;
        SourceLoader.Leaf leafSourceLoader = null;
        int leafIndex = -1;
        LeafReaderContext leafReaderContext = null;
        int endReaderIdx = -1;
        LeafStoredFieldLoader leafStoredFieldLoader = null;
        for (int index = 0; index < context.docIdsToLoadSize(); index++) {
            if (context.isCancelled()) {
                throw new TaskCancelledException("cancelled");
            }
            int docId = docs[index].docId;
            try {
                if (index >= endReaderIdx) {
                    profiler.startNextReader();
                    try {
                        leafIndex++;
                        leafIndex = ReaderUtil.subIndex(docId, leaves.subList(leafIndex, leaves.size())) + leafIndex;
                        leafReaderContext = context.searcher().getIndexReader().leaves().get(leafIndex);
                        endReaderIdx = endReaderIdx(context, leafReaderContext, index, docs);
                        int[] docIdsInLeaf = docIdsInLeaf(index, endReaderIdx, docs, leafReaderContext.docBase);
                        leafStoredFieldLoader = storedFieldLoader.getLoader(leafReaderContext, docIdsInLeaf);
                        leafSourceLoader = fetchContext.sourceLoader().leaf(leafReaderContext.reader(), docIdsInLeaf);
                        for (FetchSubPhaseProcessor processor : processors) {
                            processor.setNextReader(leafReaderContext);
                        }
                        leafNestedDocuments = nestedDocuments.getLeafNestedDocuments(leafReaderContext);
                    } finally {
                        profiler.stopNextReader();
                    }
                }
                HitContext hit = prepareHitContext(
                    context,
                    profiler,
                    leafNestedDocuments,
                    leafStoredFieldLoader,
                    docId,
                    storedToRequestedFields,
                    leafReaderContext,
                    leafSourceLoader
                );
                for (FetchSubPhaseProcessor processor : processors) {
                    processor.process(hit);
                }
                hits[docs[index].index] = hit.hit();
            } catch (Exception e) {
                throw new FetchPhaseExecutionException(context.shardTarget(), "Error running fetch phase for doc [" + docId + "]", e);
            }
        }
        if (context.isCancelled()) {
            throw new TaskCancelledException("cancelled");
        }

        TotalHits totalHits = context.queryResult().getTotalHits();
        return new SearchHits(hits, totalHits, context.queryResult().getMaxScore());
    }

    private int endReaderIdx(SearchContext context, LeafReaderContext currentReaderContext, int index, DocIdToIndex[] docs) {
        int firstInNextReader = currentReaderContext.docBase + currentReaderContext.reader().maxDoc();
        int i = index + 1;
        while (i < context.docIdsToLoadSize()) {
            if (docs[i].docId >= firstInNextReader) {
                return i;
            }
            i++;
        }
        return i;
    }

    private int[] docIdsInLeaf(int index, int endReaderIdx, DocIdToIndex[] docs, int docBase) {
        int[] result = new int[endReaderIdx - index];
        int d = 0;
        for (int i = index; i < endReaderIdx; i++) {
            assert docs[i].docId >= docBase;
            result[d++] = docs[i].docId - docBase;
        }
        return result;
    }

    List<FetchSubPhaseProcessor> getProcessors(SearchShardTarget target, FetchContext context, Profiler profiler) {
        try {
            List<FetchSubPhaseProcessor> processors = new ArrayList<>();
            for (FetchSubPhase fsp : fetchSubPhases) {
                FetchSubPhaseProcessor processor = fsp.getProcessor(context);
                if (processor != null) {
                    processors.add(profiler.profile(fsp.getClass().getSimpleName(), "", processor));
                }
            }
            return processors;
        } catch (Exception e) {
            throw new FetchPhaseExecutionException(target, "Error building fetch sub-phases", e);
        }
    }

    static class DocIdToIndex implements Comparable<DocIdToIndex> {
        final int docId;
        final int index;

        DocIdToIndex(int docId, int index) {
            this.docId = docId;
            this.index = index;
        }

        @Override
        public int compareTo(DocIdToIndex o) {
            return Integer.compare(docId, o.docId);
        }
    }

    private static StoredFieldLoader createStoredFieldLoader(
        SearchContext context,
        SourceLoader sourceLoader,
        Map<String, Set<String>> storedToRequestedFields
    ) {
        StoredFieldsContext storedFieldsContext = context.storedFieldsContext();

        if (storedFieldsContext == null) {
            // no fields specified, default to return source if no explicit indication
            if (context.hasScriptFields() == false && context.hasFetchSourceContext() == false) {
                context.fetchSourceContext(FetchSourceContext.FETCH_SOURCE);
            }
            boolean loadSource = sourceRequired(context);
            return StoredFieldLoader.create(loadSource, sourceLoader.requiredStoredFields());
        } else if (storedFieldsContext.fetchFields() == false) {
            // disable stored fields entirely
            return StoredFieldLoader.empty();
        } else {
            for (String fieldNameOrPattern : context.storedFieldsContext().fieldNames()) {
                if (fieldNameOrPattern.equals(SourceFieldMapper.NAME)) {
                    FetchSourceContext fetchSourceContext = context.hasFetchSourceContext()
                        ? context.fetchSourceContext()
                        : FetchSourceContext.FETCH_SOURCE;
                    context.fetchSourceContext(FetchSourceContext.of(true, fetchSourceContext.includes(), fetchSourceContext.excludes()));
                    continue;
                }
                SearchExecutionContext searchExecutionContext = context.getSearchExecutionContext();
                Collection<String> fieldNames = searchExecutionContext.getMatchingFieldNames(fieldNameOrPattern);
                for (String fieldName : fieldNames) {
                    MappedFieldType fieldType = searchExecutionContext.getFieldType(fieldName);
                    String storedField = fieldType.name();
                    Set<String> requestedFields = storedToRequestedFields.computeIfAbsent(storedField, key -> new HashSet<>());
                    requestedFields.add(fieldName);
                }
            }
            boolean loadSource = sourceRequired(context);
            if (loadSource) {
                sourceLoader.requiredStoredFields().forEach(fieldName -> storedToRequestedFields.putIfAbsent(fieldName, Set.of()));
            }
            if (storedToRequestedFields.isEmpty()) {
                // empty list specified, default to disable _source if no explicit indication
                return StoredFieldLoader.create(loadSource, sourceLoader.requiredStoredFields());
            } else {
                return StoredFieldLoader.create(loadSource, storedToRequestedFields.keySet());
            }
        }
    }

    private static boolean sourceRequired(SearchContext context) {
        return context.sourceRequested() || context.fetchFieldsContext() != null;
    }

    private static HitContext prepareHitContext(
        SearchContext context,
        Profiler profiler,
        LeafNestedDocuments nestedDocuments,
        LeafStoredFieldLoader leafStoredFieldLoader,
        int docId,
        Map<String, Set<String>> storedToRequestedFields,
        LeafReaderContext subReaderContext,
        SourceLoader.Leaf sourceLoader
    ) throws IOException {
        if (nestedDocuments.advance(docId - subReaderContext.docBase) == null) {
            return prepareNonNestedHitContext(
                context,
                profiler,
                leafStoredFieldLoader,
                docId,
                storedToRequestedFields,
                subReaderContext,
                sourceLoader
            );
        } else {
            return prepareNestedHitContext(
                context,
                profiler,
                docId,
                nestedDocuments,
                storedToRequestedFields,
                subReaderContext,
                leafStoredFieldLoader
            );
        }
    }

    /**
     * Resets the provided {@link HitContext} with information on the current
     * document. This includes the following:
     *   - Adding an initial {@link SearchHit} instance.
     *   - Loading the document source and setting it on {@link HitContext#sourceLookup()}. This
     *     allows fetch subphases that use the hit context to access the preloaded source.
     */
    private static HitContext prepareNonNestedHitContext(
        SearchContext context,
        Profiler profiler,
        LeafStoredFieldLoader leafStoredFieldLoader,
        int docId,
        Map<String, Set<String>> storedToRequestedFields,
        LeafReaderContext subReaderContext,
        SourceLoader.Leaf sourceLoader
    ) throws IOException {
        int subDocId = docId - subReaderContext.docBase;

        leafStoredFieldLoader.advanceTo(subDocId);

        if (leafStoredFieldLoader.id() == null) {
            SearchHit hit = new SearchHit(docId, null, null, null);
            return new HitContext(hit, subReaderContext, subDocId);
        } else {
            SearchHit hit;
            if (leafStoredFieldLoader.storedFields().isEmpty() == false) {
                Map<String, DocumentField> docFields = new HashMap<>();
                Map<String, DocumentField> metaFields = new HashMap<>();
                fillDocAndMetaFields(context, leafStoredFieldLoader.storedFields(), storedToRequestedFields, docFields, metaFields);
                hit = new SearchHit(docId, leafStoredFieldLoader.id(), docFields, metaFields);
            } else {
                hit = new SearchHit(docId, leafStoredFieldLoader.id(), emptyMap(), emptyMap());
            }

            HitContext hitContext = new HitContext(hit, subReaderContext, subDocId);
            BytesReference source;
            if (sourceRequired(context)) {
                try {
                    profiler.startLoadingSource();
                    source = sourceLoader.source(leafStoredFieldLoader, subDocId);
                } finally {
                    profiler.stopLoadingSource();
                }
            } else {
                source = null;
            }
            if (source != null) {
                // Store the loaded source on the hit context so that fetch subphases can access it.
                // Also make it available to scripts by storing it on the shared SearchLookup instance.
                SourceLookup.BytesSourceProvider sourceBytes = new SourceLookup.BytesSourceProvider(source);
                hitContext.sourceLookup().setSourceProvider(sourceBytes);

                SourceLookup scriptSourceLookup = context.getSearchExecutionContext().lookup().source();
                scriptSourceLookup.setSegmentAndDocument(subReaderContext, subDocId);
                scriptSourceLookup.setSourceProvider(sourceBytes);
            }
            return hitContext;
        }
    }

    /**
     * Resets the provided {@link HitContext} with information on the current
     * nested document. This includes the following:
     *   - Adding an initial {@link SearchHit} instance.
     *   - Loading the document source, filtering it based on the nested document ID, then
     *     setting it on {@link HitContext#sourceLookup()}. This allows fetch subphases that
     *     use the hit context to access the preloaded source.
     */
    @SuppressWarnings("unchecked")
    private static HitContext prepareNestedHitContext(
        SearchContext context,
        Profiler profiler,
        int topDocId,
        LeafNestedDocuments nestedInfo,
        Map<String, Set<String>> storedToRequestedFields,
        LeafReaderContext subReaderContext,
        LeafStoredFieldLoader childFieldLoader
    ) throws IOException {
        // Also if highlighting is requested on nested documents we need to fetch the _source from the root document,
        // otherwise highlighting will attempt to fetch the _source from the nested doc, which will fail,
        // because the entire _source is only stored with the root document.
        boolean needSource = sourceRequired(context) || context.highlight() != null;

        String rootId;
        Map<String, Object> rootSourceAsMap = null;
        XContentType rootSourceContentType = null;

        if (context instanceof InnerHitsContext.InnerHitSubContext innerHitsContext) {
            rootId = innerHitsContext.getRootId();

            if (needSource) {
                SourceLookup rootLookup = innerHitsContext.getRootLookup();
                rootSourceAsMap = rootLookup.source();
                rootSourceContentType = rootLookup.sourceContentType();
            }
        } else {
            StoredFieldLoader rootLoader = profiler.storedFields(StoredFieldLoader.create(needSource, Collections.emptySet()));
            LeafStoredFieldLoader leafRootLoader = rootLoader.getLoader(subReaderContext, null);
            leafRootLoader.advanceTo(nestedInfo.rootDoc());
            rootId = leafRootLoader.id();

            if (needSource) {
                if (leafRootLoader.source() != null) {
                    Tuple<XContentType, Map<String, Object>> tuple = XContentHelper.convertToMap(leafRootLoader.source(), false);
                    rootSourceAsMap = tuple.v2();
                    rootSourceContentType = tuple.v1();
                } else {
                    rootSourceAsMap = Collections.emptyMap();
                }
            }
        }

        Map<String, DocumentField> docFields = emptyMap();
        Map<String, DocumentField> metaFields = emptyMap();
        if (context.hasStoredFields() && context.storedFieldsContext().fieldNames().isEmpty() == false) {
            childFieldLoader.advanceTo(nestedInfo.doc());
            if (childFieldLoader.storedFields().isEmpty() == false) {
                docFields = new HashMap<>();
                metaFields = new HashMap<>();
                fillDocAndMetaFields(context, childFieldLoader.storedFields(), storedToRequestedFields, docFields, metaFields);
            }
        }

        SearchHit.NestedIdentity nestedIdentity = nestedInfo.nestedIdentity();

        SearchHit hit = new SearchHit(topDocId, rootId, nestedIdentity, docFields, metaFields);
        HitContext hitContext = new HitContext(hit, subReaderContext, nestedInfo.doc());

        if (rootSourceAsMap != null && rootSourceAsMap.isEmpty() == false) {
            // Isolate the nested json array object that matches with nested hit and wrap it back into the same json
            // structure with the nested json array object being the actual content. The latter is important, so that
            // features like source filtering and highlighting work consistent regardless of whether the field points
            // to a json object array for consistency reasons on how we refer to fields
            Map<String, Object> nestedSourceAsMap = new HashMap<>();
            Map<String, Object> current = nestedSourceAsMap;
            for (SearchHit.NestedIdentity nested = nestedIdentity; nested != null; nested = nested.getChild()) {
                String nestedPath = nested.getField().string();
                current.put(nestedPath, new HashMap<>());
                List<Map<?, ?>> nestedParsedSource = XContentMapValues.extractNestedSources(nestedPath, rootSourceAsMap);
                if (nestedParsedSource == null) {
                    throw new IllegalStateException("Couldn't find nested source for path " + nestedPath);
                }
                rootSourceAsMap = (Map<String, Object>) nestedParsedSource.get(nested.getOffset());
                if (nested.getChild() == null) {
                    current.put(nestedPath, rootSourceAsMap);
                } else {
                    Map<String, Object> next = new HashMap<>();
                    current.put(nestedPath, next);
                    current = next;
                }
            }

            hitContext.sourceLookup().setSourceProvider(new SourceLookup.MapSourceProvider(nestedSourceAsMap, rootSourceContentType));
        }
        return hitContext;
    }

    public static List<Object> processStoredField(Function<String, MappedFieldType> fieldTypeLookup, String field, List<Object> input) {
        MappedFieldType ft = fieldTypeLookup.apply(field);
        if (ft == null) {
            return input;
        }
        return input.stream().map(ft::valueForDisplay).collect(Collectors.toList());
    }

    private static void fillDocAndMetaFields(
        SearchContext context,
        Map<String, List<Object>> storedFields,
        Map<String, Set<String>> storedToRequestedFields,
        Map<String, DocumentField> docFields,
        Map<String, DocumentField> metaFields
    ) {
        Function<String, MappedFieldType> fieldTypeLookup = context.getSearchExecutionContext()::getFieldType;
        for (Map.Entry<String, List<Object>> entry : storedFields.entrySet()) {
            String storedField = entry.getKey();
            List<Object> storedValues = processStoredField(fieldTypeLookup, storedField, entry.getValue());
            if (storedToRequestedFields.containsKey(storedField)) {
                for (String requestedField : storedToRequestedFields.get(storedField)) {
                    if (context.getSearchExecutionContext().isMetadataField(requestedField)) {
                        metaFields.put(requestedField, new DocumentField(requestedField, storedValues));
                    } else {
                        docFields.put(requestedField, new DocumentField(requestedField, storedValues));
                    }
                }
            } else {
                if (context.getSearchExecutionContext().isMetadataField(storedField)) {
                    metaFields.put(storedField, new DocumentField(storedField, storedValues));
                } else {
                    docFields.put(storedField, new DocumentField(storedField, storedValues));
                }
            }
        }
    }

    /**
     * Returns <code>true</code> if the provided <code>docs</code> are
     * stored sequentially (Dn = Dn-1 + 1).
     */
    static boolean hasSequentialDocs(DocIdToIndex[] docs) {
        return docs.length > 0 && docs[docs.length - 1].docId - docs[0].docId == docs.length - 1;
    }

    interface Profiler {
        ProfileResult finish();

        FetchSubPhaseProcessor profile(String type, String description, FetchSubPhaseProcessor processor);

        StoredFieldLoader storedFields(StoredFieldLoader storedFieldLoader);

        void startLoadingSource();

        void stopLoadingSource();

        void startNextReader();

        void stopNextReader();

        Profiler NOOP = new Profiler() {
            @Override
            public ProfileResult finish() {
                return null;
            }

            @Override
            public StoredFieldLoader storedFields(StoredFieldLoader storedFieldLoader) {
                return storedFieldLoader;
            }

            @Override
            public FetchSubPhaseProcessor profile(String type, String description, FetchSubPhaseProcessor processor) {
                return processor;
            }

            @Override
            public void startLoadingSource() {}

            @Override
            public void stopLoadingSource() {}

            @Override
            public void startNextReader() {}

            @Override
            public void stopNextReader() {}

            @Override
            public String toString() {
                return "noop";
            }
        };
    }
}
