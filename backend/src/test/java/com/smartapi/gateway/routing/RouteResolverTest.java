package com.smartapi.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartapi.gateway.exception.InvalidGatewayRequestException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RouteResolverTest {

    private final RouteResolver resolver = new RouteResolver();

    @ParameterizedTest
    @CsvSource({
            "/gateway/orders/orders/123, /orders, /orders/123",
            "/gateway/orders/orders,     /orders, /orders",
            "/gateway/orders,            /orders, /",
            "/gateway/orders/,           /orders, /",
            "/gateway/payments/a/b/c,    /payments, /a/b/c"})
    void splitsServiceAndDownstreamPath(String uri, String basePath, String downstream) {
        assertThat(resolver.resolve(uri)).isEqualTo(new Route(basePath, downstream));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/gateway", "/gateway/", "/gatewayx/orders"})
    void rejectsUrlsWithoutAService(String uri) {
        assertThatThrownBy(() -> resolver.resolve(uri)).isInstanceOf(InvalidGatewayRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/gateway/orders/../admin", "/gateway/orders/%2e%2e/admin", "/gateway/orders/%2E%2E",
            "/gateway/orders/./x", "/gateway/orders/%zz"})
    void rejectsPathTraversalAndBadEncoding(String uri) {
        assertThatThrownBy(() -> resolver.resolve(uri)).isInstanceOf(InvalidGatewayRequestException.class);
    }
}
