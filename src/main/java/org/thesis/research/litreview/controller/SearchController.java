package org.thesis.research.litreview.controller;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thesis.research.litreview.service.retrieval.HybridSearchService;
import org.thesis.research.litreview.service.retrieval.SearchRequest;
import org.thesis.research.litreview.service.retrieval.SearchResult;

/**
 * Hybrid retrieval over the chunk corpus.
 *
 * <p>POST rather than GET even though it is a read: the query is a JSON body
 * with optional filters, and a query string would either lose the filters or
 * turn into an awkward parameter soup. This is a private research tool, so the
 * usual arguments for cacheable GETs do not apply.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final HybridSearchService searchService;

    public SearchController(HybridSearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * Runs a hybrid search.
     *
     * <p>An empty or blank query returns an empty list rather than an error -
     * the UI can send whatever is in the search box without special-casing.
     */
    @PostMapping
    public List<SearchResult> search(@RequestBody SearchRequest request) {
        return searchService.search(request);
    }
}
