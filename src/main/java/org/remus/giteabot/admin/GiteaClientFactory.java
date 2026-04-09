package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory that creates and caches {@link RestClient} instances from persisted
 * {@link GitIntegration} entities.  Clients are cached by integration ID and
 * {@link GitIntegration#getUpdatedAt()} so that configuration changes
 * automatically produce fresh clients.
 * <p>
 * The authentication header varies by provider type:
 * <ul>
 *   <li>Gitea — {@code Authorization: token <TOKEN>}</li>
 *   <li>Bitbucket — {@code Authorization: Bearer <TOKEN>}</li>
 * </ul>
 */
@Slf4j
@Service
public class GiteaClientFactory {

    private final GitIntegrationService gitIntegrationService;

    /** Cache key = integrationId, value = (updatedAt-millis, restClient). */
    private final ConcurrentMap<Long, CachedClient> cache = new ConcurrentHashMap<>();

    public GiteaClientFactory(GitIntegrationService gitIntegrationService) {
        this.gitIntegrationService = gitIntegrationService;
    }

    /**
     * Returns a {@link RestClient} configured for the given Git integration
     * (base URL + authentication header).  Results are cached and re-created
     * when the integration's updatedAt changes.
     */
    public RestClient getClient(GitIntegration integration) {
        CachedClient cached = cache.get(integration.getId());
        long updatedMillis = integration.getUpdatedAt().toEpochMilli();
        if (cached != null && cached.updatedAtMillis == updatedMillis) {
            return cached.client;
        }

        RestClient client = buildClient(integration);
        cache.put(integration.getId(), new CachedClient(updatedMillis, client));
        log.info("Built new RestClient for integration '{}' (type={}, url={})",
                integration.getName(), integration.getProviderType(), integration.getUrl());
        return client;
    }

    /**
     * Returns the decrypted token for the given integration.
     */
    public String getDecryptedToken(GitIntegration integration) {
        return gitIntegrationService.decryptToken(integration);
    }

    public void evict(Long integrationId) {
        cache.remove(integrationId);
    }

    private RestClient buildClient(GitIntegration integration) {
        String decryptedToken = gitIntegrationService.decryptToken(integration);
        String authHeader = buildAuthHeader(integration.getProviderType(), decryptedToken);
        return RestClient.builder()
                .baseUrl(integration.getUrl())
                .defaultHeader("Authorization", authHeader)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * Builds the provider-specific Authorization header value.
     */
    private String buildAuthHeader(RepositoryType providerType, String token) {
        return switch (providerType) {
            case BITBUCKET -> "Bearer " + token;
            case GITEA, GITLAB -> "token " + token;
        };
    }

    private record CachedClient(long updatedAtMillis, RestClient client) {}
}
