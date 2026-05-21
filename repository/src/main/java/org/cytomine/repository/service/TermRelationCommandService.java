package org.cytomine.repository.service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.cytomine.repository.mapper.CommandMapper;
import org.cytomine.repository.mapper.OntologyMapper;
import org.cytomine.repository.persistence.CommandV2Repository;
import org.cytomine.repository.persistence.RelationRepository;
import org.cytomine.repository.persistence.TermRelationRepository;
import org.cytomine.repository.persistence.TermRepository;
import org.cytomine.repository.persistence.entity.CommandV2Entity;
import org.cytomine.repository.persistence.entity.TermEntity;
import org.cytomine.repository.persistence.entity.TermRelationEntity;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

import be.cytomine.common.repository.model.command.Commands;
import be.cytomine.common.repository.model.command.payload.request.TermRelationCommandPayload;
import be.cytomine.common.repository.model.command.payload.response.HttpCommandResponse;
import be.cytomine.common.repository.model.command.payload.response.TermRelationResponse;
import be.cytomine.common.repository.model.command.request.CreateTermRelationCommand;
import be.cytomine.common.repository.model.command.request.DeleteTermRelationCommand;
import be.cytomine.common.repository.model.command.request.UpdateTermRelationCommand;
import be.cytomine.common.repository.model.termrelation.payload.CreateTermRelation;
import be.cytomine.common.repository.model.termrelation.payload.UpdateTermRelation;

@Component
@RequiredArgsConstructor
public class TermRelationCommandService {
    private final TermRepository termRepository;
    private final TermRelationRepository termRelationRepository;
    private final RelationRepository relationRepository;
    private final OntologyMapper ontologyMapper;
    private final CommandV2Repository commandV2Repository;
    private final CommandMapper commandMapper;
    private final ACLService aclService;

    @Transactional
    public Optional<HttpCommandResponse> deleteTermRelation(Long id, Long userId, LocalDateTime now) {
        return termRelationRepository.findById(id)
            .flatMap(entity -> termRepository.findById(entity.getTerm1Id()).map(term1 -> Pair.of(entity, term1)))
            .filter(pair -> aclService.canWriteOntology(userId, pair.getSecond().getOntologyId())).map(pair -> {
                TermRelationEntity termEntity = pair.getFirst();
                long ontologyId = pair.getSecond().getOntologyId();
                DeleteTermRelationCommand deleteCommand = new DeleteTermRelationCommand(id,
                    ontologyMapper.mapToTermRelationCommandPayload(termEntity, ontologyId), userId, ontologyId);
                CommandV2Entity commandV2Entity =
                    commandV2Repository.save(commandMapper.map(deleteCommand, now, now, userId));
                termEntity.setDeleted(now);
                return saveAndBuildResponse(termEntity, Commands.DELETE_TERM_RELATION, commandV2Entity.getId(),
                    ontologyId);
            });
    }

    @Transactional
    public Optional<HttpCommandResponse> createTermRelation(Long userId, CreateTermRelation createTermRelation,
                                                            LocalDateTime now) {
        return termRepository.findById(createTermRelation.term1Id())
            .filter(firstTerm -> aclService.canWriteOntology(userId, firstTerm.getOntologyId()))
            // check that the second term is in the same ontology
            .filter(firstTerm -> termRepository.findById(createTermRelation.term2Id())
                .map(secondTerm -> secondTerm.getOntologyId().equals(firstTerm.getOntologyId())).orElse(false))
            // Check that a relation between those terms don't exist before adding a new one.
            .filter(i -> termRelationRepository.findByTerm1IdAndTerm2Id(createTermRelation.term1Id(),
                createTermRelation.term2Id()).isEmpty())
            // Reject relations that would introduce a cycle in the term hierarchy: such an
            // ontology cannot be rendered as a tree and previously crashed the server with a
            // StackOverflowError when fetched.
            .filter(firstTerm -> !wouldCreateCycle(firstTerm.getOntologyId(),
                createTermRelation.term1Id(), createTermRelation.term2Id()))
            .map(firstTerm -> {
                long ontologyId = firstTerm.getOntologyId();
                long relationId = relationRepository.findParent().getId();
                TermRelationEntity termEntity =
                    ontologyMapper.mapToTermRelationEntity(createTermRelation, now, relationId);
                TermRelationEntity savedEntity = termRelationRepository.save(termEntity);
                TermRelationCommandPayload termCommandPayload =
                    ontologyMapper.mapToTermRelationCommandPayload(savedEntity, ontologyId);
                CreateTermRelationCommand insertTermCommand =
                    new CreateTermRelationCommand(termCommandPayload, userId, termCommandPayload.ontologyId());

                CommandV2Entity commandV2Entity =
                    commandV2Repository.save(commandMapper.map(insertTermCommand, now, now, userId));

                TermRelationResponse termResponse = ontologyMapper.mapToTermRelationResponse(savedEntity, ontologyId);

                return new HttpCommandResponse(true, termResponse, commandV2Entity.getId(),
                    Commands.CREATE_TERM_RELATION);
            });
    }

    /**
     * Check whether adding the PARENT relation parentTermId -> childTermId would create a cycle
     * in the ontology's term hierarchy. A cycle appears when the two terms are the same, or when
     * parentTermId is already a (transitive) descendant of childTermId.
     *
     * @param ontologyId   Ontology the terms belong to
     * @param parentTermId Candidate parent term (term1 of the relation)
     * @param childTermId  Candidate child term (term2 of the relation)
     *
     * @return true if creating the relation would introduce a cycle
     */
    private boolean wouldCreateCycle(long ontologyId, long parentTermId, long childTermId) {
        if (parentTermId == childTermId) {
            return true;
        }
        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        for (TermRelationEntity rel : termRelationRepository.findAllByOntologyId(ontologyId)) {
            childrenByParent.computeIfAbsent(rel.getTerm1Id(), k -> new ArrayList<>()).add(rel.getTerm2Id());
        }
        Set<Long> visited = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(childTermId);
        while (!stack.isEmpty()) {
            long current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            for (long descendant : childrenByParent.getOrDefault(current, List.of())) {
                if (descendant == parentTermId) {
                    return true;
                }
                stack.push(descendant);
            }
        }
        return false;
    }

    @Transactional
    public Optional<HttpCommandResponse> updateTerm(long id, Long userId, UpdateTermRelation updateTerm,
                                                    LocalDateTime now) {
        return termRelationRepository.findById(id).flatMap(
                termRelationEntity -> termRepository.findById(termRelationEntity.getTerm1Id())
                    .map(termEntity -> Pair.of(termRelationEntity, termEntity)))
            .filter(pair -> aclService.canWriteOntology(userId, pair.getSecond().getOntologyId()))
            // check that the second term is in the same ontology
            .filter(pair -> termRepository.findById(pair.getFirst().getTerm2Id())
                .map(secondTerm -> secondTerm.getOntologyId().equals(pair.getSecond().getOntologyId())).orElse(false))
            .map(pair -> {
                TermRelationEntity termRelationEntity = pair.getFirst();
                TermEntity termEntity = pair.getSecond();
                long ontologyId = termEntity.getOntologyId();
                TermRelationCommandPayload beforePayload =
                    ontologyMapper.mapToTermRelationCommandPayload(termRelationEntity, ontologyId);
                updateTerm.term1Id().ifPresent(termRelationEntity::setTerm1Id);
                updateTerm.term2Id().ifPresent(termRelationEntity::setTerm2Id);
                TermRelationEntity savedEntity = termRelationRepository.save(termRelationEntity);
                UpdateTermRelationCommand updateCommand = new UpdateTermRelationCommand(id, beforePayload,
                    ontologyMapper.mapToTermRelationCommandPayload(savedEntity, ontologyId), userId, ontologyId);

                CommandV2Entity commandV2Entity =
                    commandV2Repository.save(commandMapper.map(updateCommand, now, now, userId));

                TermRelationResponse termResponse = ontologyMapper.mapToTermRelationResponse(savedEntity, ontologyId);
                return new HttpCommandResponse(true, termResponse, commandV2Entity.getId(),
                    Commands.UPDATE_TERM_RELATION);
            });
    }


    public Optional<HttpCommandResponse> undoDeleteTermRelation(UUID commandId,
                                                                DeleteTermRelationCommand deleteTermCommand,
                                                                Long userId, LocalDateTime now) {
        if (!aclService.canWriteOntology(userId, deleteTermCommand.ontologyId())) {
            return Optional.empty();
        }
        return restoreTermRelation(commandId, deleteTermCommand.before().id(), Commands.DELETE_TERM_RELATION, now,
            deleteTermCommand.before().ontologyId());
    }

    public Optional<HttpCommandResponse> redoDeleteTermRelation(UUID commandId,
                                                                DeleteTermRelationCommand deleteTermCommand,
                                                                Long userId, LocalDateTime now) {
        if (!aclService.canWriteOntology(userId, deleteTermCommand.ontologyId())) {
            return Optional.empty();
        }
        return softDeleteTermRelation(commandId, deleteTermCommand.before().id(), Commands.DELETE_TERM_RELATION, now,
            deleteTermCommand.before().ontologyId());
    }

    public Optional<HttpCommandResponse> undoCreateTermRelation(UUID commandId,
                                                                CreateTermRelationCommand createTermCommand,
                                                                Long userId, LocalDateTime now) {
        if (!aclService.canWriteOntology(userId, createTermCommand.ontologyId())) {
            return Optional.empty();
        }
        return softDeleteTermRelation(commandId, createTermCommand.after().id(), Commands.CREATE_TERM_RELATION, now,
            createTermCommand.after().ontologyId());
    }

    public Optional<HttpCommandResponse> redoCreateTermRelation(UUID commandId,
                                                                CreateTermRelationCommand createTermCommand,
                                                                Long userId, LocalDateTime now) {
        if (!aclService.canWriteOntology(userId, createTermCommand.ontologyId())) {
            return Optional.empty();
        }
        return restoreTermRelation(commandId, createTermCommand.after().id(), Commands.CREATE_TERM_RELATION, now,
            createTermCommand.after().ontologyId());
    }

    public Optional<HttpCommandResponse> undoUpdateTermRelation(UUID commandId,
                                                                UpdateTermRelationCommand updateTermCommand,
                                                                Long userId) {
        if (!aclService.canWriteOntology(userId, updateTermCommand.ontologyId())) {
            return Optional.empty();
        }
        return termRelationRepository.findById(updateTermCommand.before().id()).map(entity -> {
            entity.setTerm1Id(updateTermCommand.before().term1Id());
            entity.setTerm2Id(updateTermCommand.before().term2Id());
            return saveAndBuildResponse(entity, Commands.UPDATE_TERM_RELATION, commandId,
                updateTermCommand.before().ontologyId());
        });
    }

    public Optional<HttpCommandResponse> redoUpdateTermRelation(UUID commandId,
                                                                UpdateTermRelationCommand updateTermCommand,
                                                                Long userId, LocalDateTime now) {
        if (!aclService.canWriteOntology(userId, updateTermCommand.ontologyId())) {
            return Optional.empty();
        }
        return termRelationRepository.findById(updateTermCommand.after().id()).map(entity -> {
            entity.setTerm1Id(updateTermCommand.after().term1Id());
            entity.setTerm2Id(updateTermCommand.after().term2Id());
            entity.setUpdated(now);
            return saveAndBuildResponse(entity, Commands.UPDATE_TERM_RELATION, commandId,
                updateTermCommand.after().ontologyId());
        });
    }

    private Optional<HttpCommandResponse> restoreTermRelation(UUID commandId, Long termId, String command,
                                                              LocalDateTime now, long ontologyId) {
        return termRelationRepository.findById(termId).map(entity -> {
            entity.setDeleted(null);
            entity.setUpdated(now);
            return saveAndBuildResponse(entity, command, commandId, ontologyId);
        });
    }

    private Optional<HttpCommandResponse> softDeleteTermRelation(UUID commandId, Long termId, String command,
                                                                 LocalDateTime now, long ontologyId) {
        return termRelationRepository.findById(termId).map(entity -> {
            entity.setDeleted(now);
            return saveAndBuildResponse(entity, command, commandId, ontologyId);
        });
    }

    private HttpCommandResponse saveAndBuildResponse(TermRelationEntity entity, String command, UUID commandId,
                                                     long ontologyId) {
        TermRelationEntity saved = termRelationRepository.save(entity);
        TermRelationResponse response = ontologyMapper.mapToTermRelationResponse(saved, ontologyId);
        return new HttpCommandResponse(true, response, commandId, command);
    }

}
