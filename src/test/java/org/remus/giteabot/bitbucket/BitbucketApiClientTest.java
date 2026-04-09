package org.remus.giteabot.bitbucket;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.RepositoryApiClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BitbucketApiClient} covering the non-HTTP logic.
 */
class BitbucketApiClientTest {

    @Test
    void implementsRepositoryApiClient() {
        var client = new BitbucketApiClient(null, "https://api.bitbucket.org", "test-token");
        assertInstanceOf(RepositoryApiClient.class, client);
    }

    @Test
    void getBaseUrl_returnsConfiguredUrl() {
        var client = new BitbucketApiClient(null, "https://api.bitbucket.org", "test-token");
        assertEquals("https://api.bitbucket.org", client.getBaseUrl());
    }

    @Test
    void getToken_returnsConfiguredToken() {
        var client = new BitbucketApiClient(null, "https://api.bitbucket.org", "my-secret-token");
        assertEquals("my-secret-token", client.getToken());
    }

    @Test
    void addReaction_doesNotThrow() {
        // Bitbucket Cloud doesn't support reactions; the method should be a no-op
        var client = new BitbucketApiClient(null, "https://api.bitbucket.org", "token");
        assertDoesNotThrow(() -> client.addReaction("owner", "repo", 1L, "eyes"));
    }
}
