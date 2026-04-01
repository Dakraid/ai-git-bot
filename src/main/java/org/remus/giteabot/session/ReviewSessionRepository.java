package org.remus.giteabot.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReviewSessionRepository extends JpaRepository<ReviewSession, Long> {

    Optional<ReviewSession> findByRepoOwnerAndRepoNameAndPrNumber(String repoOwner, String repoName, Long prNumber);

    void deleteByRepoOwnerAndRepoNameAndPrNumber(String repoOwner, String repoName, Long prNumber);
}
