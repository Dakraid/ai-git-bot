package org.remus.giteabot.bitbucket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.remus.giteabot.repository.model.ReviewComment;

/**
 * Bitbucket-specific implementation of {@link ReviewComment}.
 * Maps to Bitbucket Cloud pull-request comments that include inline metadata
 * ({@code inline.path} and {@code inline.to}).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketReviewComment implements ReviewComment {

    private Long id;

    private BitbucketReview.BitbucketContent content;

    private BitbucketReview.BitbucketUser user;

    private Inline inline;

    @JsonProperty("created_on")
    private String createdOn;

    @Override
    public String getBody() {
        return content != null ? content.getRaw() : null;
    }

    @Override
    public String getPath() {
        return inline != null ? inline.getPath() : null;
    }

    /**
     * Bitbucket does not return diff hunks per comment.
     */
    @Override
    public String getDiffHunk() {
        return null;
    }

    @Override
    public Integer getLine() {
        return inline != null ? inline.getTo() : null;
    }

    @Override
    public String getUserLogin() {
        return user != null ? user.getDisplayName() : null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Inline {
        private String path;
        private Integer to;
        private Integer from;
    }
}
