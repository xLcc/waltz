/*
 * Waltz - Enterprise Architecture
 * Copyright (C) 2016, 2017 Waltz open source project
 * See README.md for more information
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.khartec.waltz.service.attestation;


import com.khartec.waltz.data.IdSelectorFactory;
import com.khartec.waltz.data.IdSelectorFactoryProvider;
import com.khartec.waltz.data.attestation.AttestationInstanceDao;
import com.khartec.waltz.data.attestation.AttestationInstanceRecipientDao;
import com.khartec.waltz.data.attestation.AttestationRunDao;
import com.khartec.waltz.data.involvement.InvolvementDao;
import com.khartec.waltz.model.*;
import com.khartec.waltz.model.attestation.*;
import com.khartec.waltz.model.person.Person;
import com.khartec.waltz.service.email.EmailService;
import org.jooq.Record1;
import org.jooq.Select;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.khartec.waltz.common.Checks.checkNotNull;
import static com.khartec.waltz.model.EntityReference.mkRef;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

@Service
public class AttestationRunService {

    private final AttestationInstanceDao attestationInstanceDao;
    private final AttestationInstanceRecipientDao attestationInstanceRecipientDao;
    private final AttestationRunDao attestationRunDao;
    private final EmailService emailService;
    private final IdSelectorFactoryProvider idSelectorFactoryProvider;
    private final InvolvementDao involvementDao;

    @Autowired
    public AttestationRunService(AttestationInstanceDao attestationInstanceDao,
                                 AttestationInstanceRecipientDao attestationInstanceRecipientDao,
                                 AttestationRunDao attestationRunDao,
                                 EmailService emailService,
                                 IdSelectorFactoryProvider idSelectorFactoryProvider,
                                 InvolvementDao involvementDao) {
        checkNotNull(attestationInstanceRecipientDao, "attestationInstanceRecipientDao cannot be null");
        checkNotNull(attestationInstanceDao, "attestationInstanceDao cannot be null");
        checkNotNull(attestationRunDao, "attestationRunDao cannot be null");
        checkNotNull(emailService, "emailService cannot be null");
        checkNotNull(idSelectorFactoryProvider, "idSelectorFactoryProvider cannot be null");
        checkNotNull(involvementDao, "involvementDao cannot be null");

        this.attestationInstanceDao = attestationInstanceDao;
        this.attestationInstanceRecipientDao = attestationInstanceRecipientDao;
        this.attestationRunDao = attestationRunDao;
        this.emailService = emailService;
        this.idSelectorFactoryProvider = idSelectorFactoryProvider;
        this.involvementDao = involvementDao;
    }


    public AttestationRun getById(long attestationRunId) {
        return attestationRunDao.getById(attestationRunId);
    }


    public List<AttestationRun> findAll() {
        return attestationRunDao.findAll();
    }


    public List<AttestationRun> findByRecipient(String userId) {
        checkNotNull(userId, "userId cannot be null");

        return attestationRunDao.findByRecipient(userId);
    }


    public List<AttestationRunResponseSummary> findResponseSummaries() {
        return attestationRunDao.findResponseSummaries();
    }


    public List<AttestationRun> findByEntityReference(EntityReference ref) {
        checkNotNull(ref, "ref cannot be null");

        return attestationRunDao.findByEntityReference(ref);
    }


    public AttestationCreateSummary getCreateSummary(AttestationRunCreateCommand command){

        Select<Record1<Long>> idSelector = mkIdSelector(command.targetEntityKind(), command.selectionOptions());

        Map<EntityReference, List<Person>> entityReferenceToPeople = getEntityReferenceToPeople(
                command.targetEntityKind(),
                command.selectionOptions(),
                command.involvementKindIds());

        int entityCount = attestationRunDao.getEntityCount(idSelector);

        int instanceCount = entityReferenceToPeople
                .keySet()
                .size();

        long recipientCount = entityReferenceToPeople.values()
                .stream()
                .flatMap(Collection::stream)
                .distinct()
                .count();

        return ImmutableAttestationCreateSummary.builder()
                .entityCount(entityCount)
                .instanceCount(instanceCount)
                .recipientCount(recipientCount)
                .build();

    }

    
    public IdCommandResponse create(String userId, AttestationRunCreateCommand command) {
        // create run
        Long runId = attestationRunDao.create(userId, command);

        // generate instances and recipients
        List<AttestationInstanceRecipient> instanceRecipients = generateAttestationInstanceRecipients(runId, command.attestedKinds());

        // store
        createAttestationInstancesAndRecipients(instanceRecipients);

        emailService.sendEmailNotification(mkRef(EntityKind.ATTESTATION_RUN, runId));

        return ImmutableIdCommandResponse.builder()
                .id(runId)
                .build();
    }


    private List<AttestationInstanceRecipient> generateAttestationInstanceRecipients(long attestationRunId, Set<EntityKind> attestedKinds) {
        AttestationRun attestationRun = attestationRunDao.getById(attestationRunId);
        checkNotNull(attestationRun, "attestationRun " + attestationRunId + " not found");

        Map<EntityReference, List<Person>> entityRefToPeople = getEntityReferenceToPeople(
                attestationRun.targetEntityKind(),
                attestationRun.selectionOptions(),
                attestationRun.involvementKindIds());

        return entityRefToPeople.entrySet()
                .stream()
                .flatMap(e -> e.getValue().stream()
                                .flatMap(p -> mkInstanceRecipients(attestationRunId, e.getKey(), p.email(), attestedKinds)))
                .distinct()
                .collect(toList());
    }


    private Map<EntityReference, List<Person>> getEntityReferenceToPeople(EntityKind targetEntityKind,
                                                                          IdSelectionOptions selectionOptions,
                                                                          Set<Long> involvementKindIds) {
        Select<Record1<Long>> idSelector = mkIdSelector(targetEntityKind, selectionOptions);
        return involvementDao.findPeopleByEntitySelectorAndInvolvement(
                targetEntityKind,
                idSelector,
                involvementKindIds);
    }


    private Select<Record1<Long>> mkIdSelector(EntityKind targetEntityKind, IdSelectionOptions selectionOptions) {
        IdSelectorFactory idSelectorFactory = idSelectorFactoryProvider.getForKind(targetEntityKind);
        return idSelectorFactory.apply(selectionOptions);
    }


    private Stream<AttestationInstanceRecipient> mkInstanceRecipients(long attestationRunId, EntityReference ref, String userId, Set<EntityKind> childKinds) {
        switch (ref.kind()) {
            case APPLICATION:
                return childKinds.stream()
                        .map(ck -> ImmutableAttestationInstanceRecipient.builder()
                                .attestationInstance(ImmutableAttestationInstance.builder()
                                        .attestationRunId(attestationRunId)
                                        .parentEntity(ref)
                                        .childEntityKind(ck)
                                        .build())
                                .userId(userId)
                                .build());
            default:
                throw new IllegalArgumentException("Cannot create attestation instances for entity kind: " + ref.kind());
        }
    }


    private void createAttestationInstancesAndRecipients(List<AttestationInstanceRecipient> instanceRecipients) {
        Map<AttestationInstance, List<AttestationInstanceRecipient>> instancesAndRecipientsToSave = instanceRecipients.stream()
                .collect(groupingBy(
                        AttestationInstanceRecipient::attestationInstance,
                        toList()
                ));


        // insert new instances and recipients
        instancesAndRecipientsToSave.forEach(
                (k, v) -> {
                    // create instance
                    long instanceId = attestationInstanceDao.create(k);

                    // create recipients for the instance
                    v.stream()
                            .forEach(r -> attestationInstanceRecipientDao.create(instanceId, r.userId()));
                }
        );
    }



}
