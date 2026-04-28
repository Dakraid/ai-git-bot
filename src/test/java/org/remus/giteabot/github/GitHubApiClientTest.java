package org.remus.giteabot.github;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link GitHubApiClient} verifying that it correctly implements
 * {@link RepositoryApiClient} and exposes the expected base URL, clone URL, and token.
 */
class GitHubApiClientTest {

    private static final RepositoryCredentials CREDS =
            RepositoryCredentials.of("https://api.github.com", "https://github.com", "ghp_token");

    @Test
    void implementsRepositoryApiClient() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertInstanceOf(RepositoryApiClient.class, client);
    }

    @Test
    void getBaseUrl_returnsConfiguredUrl() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertEquals("https://api.github.com", client.getBaseUrl());
    }

    @Test
    void getCloneUrl_returnsConfiguredUrl() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertEquals("https://github.com", client.getCloneUrl());
    }

    @Test
    void getToken_returnsConfiguredToken() {
        GitHubApiClient client = new GitHubApiClient(null, CREDS);
        assertEquals("ghp_token", client.getToken());
    }

    @Test
    void constructorWithEnterpriseUrl() {
        var enterpriseCreds = RepositoryCredentials.of(
                "https://github.example.com/api/v3", "https://github.example.com", "token123");
        GitHubApiClient client = new GitHubApiClient(null, enterpriseCreds);
        assertEquals("https://github.example.com/api/v3", client.getBaseUrl());
        assertEquals("https://github.example.com", client.getCloneUrl());
    }

    @Test
    void updatePullRequest_sendsPatchWithTitleAndBody() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubApiClient client = new GitHubApiClient(builder.build(), CREDS);

        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/7"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("""
                        {"title":"Better title","body":"Better body"}
                        """))
                .andRespond(withSuccess());

        client.updatePullRequest("owner", "repo", 7L, "Better title", "Better body");

        server.verify();
    }
}
