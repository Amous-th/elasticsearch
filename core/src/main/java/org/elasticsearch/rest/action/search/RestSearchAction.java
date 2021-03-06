/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.rest.action.search;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.index.query.TemplateQueryBuilder;
import org.elasticsearch.indices.query.IndicesQueriesRegistry;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.support.RestActions;
import org.elasticsearch.rest.action.support.RestStatusToXContentListener;
import org.elasticsearch.script.Template;
import org.elasticsearch.search.Scroll;
import org.elasticsearch.search.aggregations.AggregatorParsers;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.source.FetchSourceContext;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.search.suggest.SuggestBuilder;
import org.elasticsearch.search.suggest.Suggesters;
import org.elasticsearch.search.suggest.term.TermSuggestionBuilder.SuggestMode;

import java.io.IOException;
import java.util.Arrays;

import static org.elasticsearch.common.unit.TimeValue.parseTimeValue;
import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;
import static org.elasticsearch.search.suggest.SuggestBuilders.termSuggestion;

/**
 *
 */
public class RestSearchAction extends BaseRestHandler {

    private final IndicesQueriesRegistry queryRegistry;
    private final AggregatorParsers aggParsers;
    private final Suggesters suggesters;

    @Inject
    public RestSearchAction(Settings settings, RestController controller, Client client, IndicesQueriesRegistry queryRegistry,
            AggregatorParsers aggParsers, Suggesters suggesters) {
        super(settings, client);
        this.queryRegistry = queryRegistry;
        this.aggParsers = aggParsers;
        this.suggesters = suggesters;
        controller.registerHandler(GET, "/_search", this);
        controller.registerHandler(POST, "/_search", this);
        controller.registerHandler(GET, "/{index}/_search", this);
        controller.registerHandler(POST, "/{index}/_search", this);
        controller.registerHandler(GET, "/{index}/{type}/_search", this);
        controller.registerHandler(POST, "/{index}/{type}/_search", this);
        controller.registerHandler(GET, "/_search/template", this);
        controller.registerHandler(POST, "/_search/template", this);
        controller.registerHandler(GET, "/{index}/_search/template", this);
        controller.registerHandler(POST, "/{index}/_search/template", this);
        controller.registerHandler(GET, "/{index}/{type}/_search/template", this);
        controller.registerHandler(POST, "/{index}/{type}/_search/template", this);
    }

    @Override
    public void handleRequest(final RestRequest request, final RestChannel channel, final Client client) throws IOException {
        SearchRequest searchRequest = new SearchRequest();
        parseSearchRequest(searchRequest, queryRegistry, request, parseFieldMatcher, aggParsers, suggesters, null);
        client.search(searchRequest, new RestStatusToXContentListener<>(channel));
    }

    /**
     * Parses the rest request on top of the SearchRequest, preserving values
     * that are not overridden by the rest request.
     *
     * @param restContent
     *            override body content to use for the request. If null body
     *            content is read from the request using
     *            RestAction.hasBodyContent.
     */
    public static void parseSearchRequest(SearchRequest searchRequest, IndicesQueriesRegistry indicesQueriesRegistry, RestRequest request,
            ParseFieldMatcher parseFieldMatcher, AggregatorParsers aggParsers, Suggesters suggesters, BytesReference restContent)
        throws IOException {

        if (searchRequest.source() == null) {
            searchRequest.source(new SearchSourceBuilder());
        }
        searchRequest.indices(Strings.splitStringByCommaToArray(request.param("index")));
        // get the content, and put it in the body
        // add content/source as template if template flag is set
        boolean isTemplateRequest = request.path().endsWith("/template");
        if (restContent == null) {
            if (RestActions.hasBodyContent(request)) {
                restContent = RestActions.getRestContent(request);
            }
        }
        if (restContent != null) {
            try (XContentParser parser = XContentFactory.xContent(restContent).createParser(restContent)) {
                QueryParseContext context = new QueryParseContext(indicesQueriesRegistry, parser, parseFieldMatcher);
                if (isTemplateRequest) {
                    Template template = TemplateQueryBuilder.parse(parser, context.getParseFieldMatcher(), "params", "template");
                    searchRequest.template(template);
                } else {
                    searchRequest.source().parseXContent(context, aggParsers, suggesters);
                }
            }
        }

        // do not allow 'query_and_fetch' or 'dfs_query_and_fetch' search types
        // from the REST layer. these modes are an internal optimization and should
        // not be specified explicitly by the user.
        String searchType = request.param("search_type");
        if (SearchType.fromString(searchType, parseFieldMatcher).equals(SearchType.QUERY_AND_FETCH) ||
                SearchType.fromString(searchType, parseFieldMatcher).equals(SearchType.DFS_QUERY_AND_FETCH)) {
            throw new IllegalArgumentException("Unsupported search type [" + searchType + "]");
        } else {
            searchRequest.searchType(searchType);
        }
        parseSearchSource(searchRequest.source(), request);
        searchRequest.requestCache(request.paramAsBoolean("request_cache", null));

        String scroll = request.param("scroll");
        if (scroll != null) {
            searchRequest.scroll(new Scroll(parseTimeValue(scroll, null, "scroll")));
        }

        searchRequest.types(Strings.splitStringByCommaToArray(request.param("type")));
        searchRequest.routing(request.param("routing"));
        searchRequest.preference(request.param("preference"));
        searchRequest.indicesOptions(IndicesOptions.fromRequest(request, searchRequest.indicesOptions()));
    }

    /**
     * Parses the rest request on top of the SearchSourceBuilder, preserving
     * values that are not overridden by the rest request.
     */
    private static void parseSearchSource(final SearchSourceBuilder searchSourceBuilder, RestRequest request) {
        QueryBuilder queryBuilder = RestActions.urlParamsToQueryBuilder(request);
        if (queryBuilder != null) {
            searchSourceBuilder.query(queryBuilder);
        }

        int from = request.paramAsInt("from", -1);
        if (from != -1) {
            searchSourceBuilder.from(from);
        }
        int size = request.paramAsInt("size", -1);
        if (size != -1) {
            searchSourceBuilder.size(size);
        }

        if (request.hasParam("explain")) {
            searchSourceBuilder.explain(request.paramAsBoolean("explain", null));
        }
        if (request.hasParam("version")) {
            searchSourceBuilder.version(request.paramAsBoolean("version", null));
        }
        if (request.hasParam("timeout")) {
            searchSourceBuilder.timeout(request.paramAsTime("timeout", null));
        }
        if (request.hasParam("terminate_after")) {
            int terminateAfter = request.paramAsInt("terminate_after",
                    SearchContext.DEFAULT_TERMINATE_AFTER);
            if (terminateAfter < 0) {
                throw new IllegalArgumentException("terminateAfter must be > 0");
            } else if (terminateAfter > 0) {
                searchSourceBuilder.terminateAfter(terminateAfter);
            }
        }

        String sField = request.param("fields");
        if (sField != null) {
            if (!Strings.hasText(sField)) {
                searchSourceBuilder.noFields();
            } else {
                String[] sFields = Strings.splitStringByCommaToArray(sField);
                if (sFields != null) {
                    for (String field : sFields) {
                        searchSourceBuilder.field(field);
                    }
                }
            }
        }
        String sFieldDataFields = request.param("fielddata_fields");
        if (sFieldDataFields != null) {
            if (Strings.hasText(sFieldDataFields)) {
                String[] sFields = Strings.splitStringByCommaToArray(sFieldDataFields);
                if (sFields != null) {
                    for (String field : sFields) {
                        searchSourceBuilder.fieldDataField(field);
                    }
                }
            }
        }
        FetchSourceContext fetchSourceContext = FetchSourceContext.parseFromRestRequest(request);
        if (fetchSourceContext != null) {
            searchSourceBuilder.fetchSource(fetchSourceContext);
        }

        if (request.hasParam("track_scores")) {
            searchSourceBuilder.trackScores(request.paramAsBoolean("track_scores", false));
        }

        String sSorts = request.param("sort");
        if (sSorts != null) {
            String[] sorts = Strings.splitStringByCommaToArray(sSorts);
            for (String sort : sorts) {
                int delimiter = sort.lastIndexOf(":");
                if (delimiter != -1) {
                    String sortField = sort.substring(0, delimiter);
                    String reverse = sort.substring(delimiter + 1);
                    if ("asc".equals(reverse)) {
                        searchSourceBuilder.sort(sortField, SortOrder.ASC);
                    } else if ("desc".equals(reverse)) {
                        searchSourceBuilder.sort(sortField, SortOrder.DESC);
                    }
                } else {
                    searchSourceBuilder.sort(sort);
                }
            }
        }

        String sStats = request.param("stats");
        if (sStats != null) {
            searchSourceBuilder.stats(Arrays.asList(Strings.splitStringByCommaToArray(sStats)));
        }

        String suggestField = request.param("suggest_field");
        if (suggestField != null) {
            String suggestText = request.param("suggest_text", request.param("q"));
            int suggestSize = request.paramAsInt("suggest_size", 5);
            String suggestMode = request.param("suggest_mode");
            searchSourceBuilder.suggest(new SuggestBuilder().addSuggestion(suggestField,
                    termSuggestion(suggestField)
                        .text(suggestText).size(suggestSize)
                        .suggestMode(SuggestMode.resolve(suggestMode))));
        }
    }
}
