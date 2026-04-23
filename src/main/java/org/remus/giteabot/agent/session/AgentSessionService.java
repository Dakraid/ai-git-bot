package org.remus.giteabot.agent.session;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.session.ConversationMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing agent coding sessions.
 */
@Slf4j
@Service
public class AgentSessionService {

    private final AgentSessionRepository repository;

    public AgentSessionService(AgentSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AgentSession createSession(String owner, String repo, Long issueNumber, String issueTitle) {
        log.info("Creating new agent session for issue #{} in {}/{}", issueNumber, owner, repo);
        AgentSession session = new AgentSession(owner, repo, issueNumber, issueTitle);
        return repository.save(session);
    }

    @Transactional(readOnly = true)
    public Optional<AgentSession> getSessionByIssue(String owner, String repo, Long issueNumber) {
        return repository.findByRepoOwnerAndRepoNameAndIssueNumber(owner, repo, issueNumber);
    }

    @Transactional(readOnly = true)
    public Optional<AgentSession> getSessionByPr(String owner, String repo, Long prNumber) {
        return repository.findByRepoOwnerAndRepoNameAndPrNumber(owner, repo, prNumber);
    }

    @Transactional
    public AgentSession addMessage(AgentSession session, String role, String content) {
        session.addMessage(role, content);
        return repository.save(session);
    }


    @Transactional
    public AgentSession setBranchName(AgentSession session, String branchName) {
        session.setBranchName(branchName);
        return repository.save(session);
    }

    @Transactional
    public AgentSession setPrNumber(AgentSession session, Long prNumber) {
        session.setPrNumber(prNumber);
        session.setStatus(AgentSession.AgentSessionStatus.PR_CREATED);
        return repository.save(session);
    }

    @Transactional
    public AgentSession setStatus(AgentSession session, AgentSession.AgentSessionStatus status) {
        session.setStatus(status);
        return repository.save(session);
    }

    @Transactional
    public void deleteSession(String owner, String repo, Long issueNumber) {
        log.info("Deleting agent session for issue #{} in {}/{}", issueNumber, owner, repo);
        repository.deleteByRepoOwnerAndRepoNameAndIssueNumber(owner, repo, issueNumber);
    }

    /**
     * Converts stored conversation messages to provider-agnostic AI message format.
     * Messages are sorted by creation time to maintain conversation order.
     */
    public List<AiMessage> toAiMessages(AgentSession session) {
        return session.getMessages().stream()
                .sorted(Comparator.comparing(ConversationMessage::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(m -> AiMessage.builder()
                        .role(m.getRole())
                        .content(m.getContent())
                        .build())
                .toList();
    }
}