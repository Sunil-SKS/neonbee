package io.neonbee.endpoint.odatav4.internal.cds;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.neonbee.data.DataAction;
import io.neonbee.data.DataQuery;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;

class CdsQueryParserTest {

    private static HttpServerRequest mockRequest(HttpMethod method, String... headers) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.method()).thenReturn(method);
        MultiMap headerMap = MultiMap.caseInsensitiveMultiMap();
        for (int i = 0; i < headers.length - 1; i += 2) {
            headerMap.add(headers[i], headers[i + 1]);
        }
        when(request.headers()).thenReturn(headerMap);
        return request;
    }

    @Test
    @DisplayName("parse builds DataQuery with correct action and uriPath")
    void parseBasic() {
        HttpServerRequest request = mockRequest(HttpMethod.GET);
        DataQuery query = CdsQueryParser.parse(DataAction.READ, "/io.neonbee.test.Service/Books", "", request, null);

        assertThat(query.getAction()).isEqualTo(DataAction.READ);
        assertThat(query.getUriPath()).isEqualTo("/io.neonbee.test.Service/Books");
    }

    @Test
    @DisplayName("parse correctly parses $filter and $top from query string")
    void parseQueryParams() {
        HttpServerRequest request = mockRequest(HttpMethod.GET);
        DataQuery query = CdsQueryParser.parse(DataAction.READ, "/svc/Books",
                "$filter=year%20gt%202020&$top=5", request, null);

        assertThat(query.getParameter("$filter")).isEqualTo("year gt 2020");
        assertThat(query.getParameter("$top")).isEqualTo("5");
    }

    @Test
    @DisplayName("parse passes $count, $skip, $orderby, $expand, $select through as-is")
    void parseAllSystemQueryOptions() {
        HttpServerRequest request = mockRequest(HttpMethod.GET);
        String qs = "$count=true&$skip=10&$orderby=title+asc&$expand=Author&$select=ID,title";
        DataQuery query = CdsQueryParser.parse(DataAction.READ, "/svc/Books", qs, request, null);

        assertThat(query.getParameter("$count")).isEqualTo("true");
        assertThat(query.getParameter("$skip")).isEqualTo("10");
        assertThat(query.getParameter("$orderby")).isEqualTo("title asc");
        assertThat(query.getParameter("$expand")).isEqualTo("Author");
        assertThat(query.getParameter("$select")).isEqualTo("ID,title");
    }

    @Test
    @DisplayName("parse sets X-HTTP-Method header from HTTP method name")
    void parseXHttpMethodHeader() {
        HttpServerRequest request = mockRequest(HttpMethod.POST);
        DataQuery query = CdsQueryParser.parse(DataAction.CREATE, "/svc/Books", "", request, null);

        assertThat(query.getHeader("X-HTTP-Method")).isEqualTo("POST");
    }

    @Test
    @DisplayName("parse propagates request headers into DataQuery")
    void parseRequestHeaders() {
        HttpServerRequest request = mockRequest(HttpMethod.GET, "Accept", "application/json", "Authorization",
                "Bearer token123");
        DataQuery query = CdsQueryParser.parse(DataAction.READ, "/svc/Books", "", request, null);

        assertThat(query.getHeader("Accept")).isEqualTo("application/json");
        assertThat(query.getHeader("Authorization")).isEqualTo("Bearer token123");
    }

    @Test
    @DisplayName("parse handles body buffer for mutation requests")
    void parseWithBody() {
        Buffer body = Buffer.buffer("{\"title\":\"Dune\"}");
        HttpServerRequest request = mockRequest(HttpMethod.POST);
        DataQuery query = CdsQueryParser.parse(DataAction.CREATE, "/svc/Books", "", request, body);

        assertThat(query.getBody()).isNotNull();
        assertThat(query.getBody().toString()).isEqualTo("{\"title\":\"Dune\"}");
    }

    @Test
    @DisplayName("parse with null body produces DataQuery with null body")
    void parseNullBody() {
        HttpServerRequest request = mockRequest(HttpMethod.GET);
        DataQuery query = CdsQueryParser.parse(DataAction.READ, "/svc/Books", "", request, null);

        assertThat(query.getBody()).isNull();
    }

    @Test
    @DisplayName("parse with empty query string produces empty parameters")
    void parseEmptyQuery() {
        HttpServerRequest request = mockRequest(HttpMethod.GET);
        DataQuery query = CdsQueryParser.parse(DataAction.READ, "/svc/Books", "", request, null);

        assertThat(query.getParameters()).isEmpty();
    }

    @Test
    @DisplayName("parse with multi-valued parameter collects all values")
    void parseMultiValuedParameter() {
        HttpServerRequest request = mockRequest(HttpMethod.GET);
        DataQuery query = CdsQueryParser.parse(DataAction.READ, "/svc/Books", "$expand=Author&$expand=Reviews",
                request, null);

        List<String> expandValues = query.getParameterValues("$expand");
        assertThat(expandValues).containsExactly("Author", "Reviews");
    }
}
