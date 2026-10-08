package com.vkplatform.iam.platform.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Controller
public class ApiDocsController {
    @GetMapping("/api/docs")
    ResponseEntity<Void> redirectToDocs() {
        return ResponseEntity.status(308).header(HttpHeaders.LOCATION, "/api/docs/").build();
    }

    @GetMapping(value = "/api/docs/openapi.yaml", produces = "application/yaml")
    @ResponseBody
    String openApi() throws IOException {
        return new ClassPathResource("openapi/openapi.yaml").getContentAsString(StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/api/docs/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String docs() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>VK Platform IAM API</title>
                  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
                </head>
                <body>
                  <div id="swagger-ui"></div>
                  <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
                  <script>
                    SwaggerUIBundle({
                      url: '/api/docs/openapi.yaml',
                      dom_id: '#swagger-ui',
                      deepLinking: true,
                      displayOperationId: true,
                      displayRequestDuration: true,
                      supportedSubmitMethods: ['get']
                    });
                  </script>
                </body>
                </html>
                """;
    }
}
