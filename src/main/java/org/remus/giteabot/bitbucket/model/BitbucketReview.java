package org.remus.giteabot.bitbucket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.repository.model.Review;

/**
 * Bitbucket-specific implementation of {@link Review}.
 * Bitbucket Cloud does not have a first-class "review" entity like Gitea;
 * pull-request comments with {@code inline} metadata are the closest equivalent.
 * This model represents a top-level PR comment treated as a review.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketReview implements Review {

    private Long id;

    private BitbucketContent content;

    private BitbucketUser user;

    @JsonProperty("created_on")
    private String createdOn;

    @Override
    public String getBody() {
        return content != null ? content.getRaw() : null;
    }

    /**
     * Bitbucket has no review state equivalent; returns {@code "COMMENT"} by default.
     */
    @Override
    public String getState() {
        return "COMMENT";
    }

    @Override
    public String getUserLogin() {
        return user != null ? user.getDisplayName() : null;
    }

    @Override
    public String getSubmittedAt() {
        return createdOn;
    }

    /**
     * Bitbucket comments are individual; there is no batch count.
     */
    @Override
    public Integer getCommentsCount() {
        return 0;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BitbucketContent {
        private String raw;
        private String markup;
        private String html;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BitbucketUser {
        @JsonProperty("display_name")
        private String displayName;

        private String nickname;

        private String uuid;
    }
}
