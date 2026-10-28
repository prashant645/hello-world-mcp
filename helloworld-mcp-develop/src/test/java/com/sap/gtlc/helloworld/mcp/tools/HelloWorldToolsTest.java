package com.sap.gtlc.helloworld.mcp.tools;

import com.sap.gtlc.helloworld.mcp.client.HelloWorldClient;
import com.sap.gtlc.helloworld.mcp.model.ConfigUpdateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HelloWorldToolsTest {

    @Test
    void returnsConciseMessageWhenBackendDeniesConfigurationUpdate() {
        HelloWorldClient client = mock(HelloWorldClient.class);
        when(client.post(eq("/api/v1/greetings/update"), isNull(), eq(ConfigUpdateResponse.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));

        HelloWorldTools tools = new HelloWorldTools(client);

        assertThatThrownBy(() -> tools.updateConfiguration(null))
                .isInstanceOf(ToolAccessDeniedException.class)
                .hasMessage("Permission denied.");
    }
}