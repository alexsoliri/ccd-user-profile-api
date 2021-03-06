package uk.gov.hmcts.ccd.data.userprofile;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.ccd.data.jurisdiction.JurisdictionEntity;
import uk.gov.hmcts.ccd.data.jurisdiction.JurisdictionRepository;
import uk.gov.hmcts.ccd.domain.model.Jurisdiction;
import uk.gov.hmcts.ccd.domain.model.UserProfile;
import uk.gov.hmcts.ccd.endpoint.exception.BadRequestException;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.ObjectUtils.allNotNull;
import static uk.gov.hmcts.ccd.data.userprofile.AuditAction.CREATE;
import static uk.gov.hmcts.ccd.data.userprofile.AuditAction.DELETE;
import static uk.gov.hmcts.ccd.data.userprofile.AuditAction.READ;
import static uk.gov.hmcts.ccd.data.userprofile.AuditAction.UPDATE;

@Repository
public class UserProfileRepository {

    public static final String NOT_APPLICABLE = "N/A";
    private final JurisdictionRepository jurisdictionRepository;
    private final UserProfileAuditEntityRepository userProfileAuditEntityRepository;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    public UserProfileRepository(JurisdictionRepository jurisdictionRepository,
                                 UserProfileAuditEntityRepository userProfileAuditEntityRepository) {
        this.jurisdictionRepository = jurisdictionRepository;
        this.userProfileAuditEntityRepository = userProfileAuditEntityRepository;
    }

    /**
     * Creates a user profile.
     *
     * @param userProfile user profile
     * @param actionedBy for audit trail
     * @return UserProfile
     */
    public UserProfile createUserProfile(UserProfile userProfile, final String actionedBy) {
        if (null != findEntityById(userProfile.getId(), actionedBy, false)) {
            throw new BadRequestException("User already exists with Id " + userProfile.getId());
        }

        Map<String, JurisdictionEntity> existingJurisdictions = new HashMap<>();
        for (Jurisdiction jurisdiction : userProfile.getJurisdictions()) {
            JurisdictionEntity jurisdictionEntity = jurisdictionRepository.findEntityById(jurisdiction.getId());
            if (null != jurisdictionEntity) {
                existingJurisdictions.put(jurisdiction.getId(), jurisdictionEntity);
            }
        }

        UserProfileEntity userProfileEntity = UserProfileMapper.modelToEntity(userProfile, existingJurisdictions);
        em.persist(userProfileEntity);
        userProfileAuditEntityRepository.createUserProfileAuditEntity(userProfile,
                                                                      CREATE,
                                                                      actionedBy,
                                                                      userProfile.getWorkBasketDefaultJurisdiction());
        return UserProfileMapper.entityToModel(userProfileEntity);
    }

    /**
     * Saves the user profile entity.
     *
     * @param userProfile
     *     user profile
     * @param actionedBy
     *     for audit trail
     * @return updated user profile
     */
    public UserProfile updateUserProfile(final UserProfile userProfile, final String actionedBy) {

        final UserProfileEntity userProfileEntity = findEntityById(userProfile.getId(), actionedBy, false);
        if (null == userProfileEntity) {
            throw new BadRequestException("User does not exist with Id " + userProfile.getId());
        }

        final boolean auditable = isAuditable(userProfileEntity);
        final UserProfile audit = UserProfileMapper.entityToModel(userProfileEntity);

        userProfileEntity.setWorkBasketDefaultCaseType(userProfile.getWorkBasketDefaultCaseType());
        userProfileEntity.setWorkBasketDefaultJurisdiction(userProfile.getWorkBasketDefaultJurisdiction());
        userProfileEntity.setWorkBasketDefaultState(userProfile.getWorkBasketDefaultState());

        if (userProfileEntity.getJurisdictions()
                             .stream()
                             .noneMatch(j -> j.getId().equals(userProfile.getWorkBasketDefaultJurisdiction()))) {
            final JurisdictionEntity
                jurisdictionEntity =
                jurisdictionRepository.findEntityById(userProfile.getWorkBasketDefaultJurisdiction());
            userProfileEntity.addJurisdiction(jurisdictionEntity);
        }

        em.persist(userProfileEntity);

        // Checks that userProfileEntity was not a deleted entity from before
        if (auditable) {
            userProfileAuditEntityRepository.createUserProfileAuditEntity(audit,
                                                                          UPDATE,
                                                                          actionedBy,
                                                                          audit.getWorkBasketDefaultJurisdiction());
        }
        return UserProfileMapper.entityToModel(userProfileEntity);
    }

    /**
     * Finds UserProfile by id.
     *
     * @param id
     *     user profile id
     * @param actionedBy
     *     for audit trail
     * @return UserProfile
     */
    public UserProfile findById(String id, final String actionedBy) {
        return UserProfileMapper.entityToModel(findEntityById(id, actionedBy, true));
    }

    private UserProfileEntity findEntityById(String id, final String actionedBy, final Boolean toAudit) {
        final UserProfileEntity userProfileEntity = em.find(UserProfileEntity.class, id);

        // check whether we need to audit
        if (toAudit && isAuditable(userProfileEntity)) {
            final UserProfile audit = UserProfileMapper.entityToModel(userProfileEntity);
            userProfileAuditEntityRepository.createUserProfileAuditEntity(audit,
                                                                          READ,
                                                                          actionedBy,
                                                                          audit.getWorkBasketDefaultJurisdiction());
        }
        return userProfileEntity;
    }

    private boolean isAuditable(final UserProfileEntity entity) {
        return allNotNull(entity) && allNotNull(entity.getWorkBasketDefaultJurisdiction(),
                                                entity.getWorkBasketDefaultCaseType(),
                                                entity.getWorkBasketDefaultState());
    }

    public List<UserProfile> findAll(String jurisdictionId, final String actionedBy) {
        TypedQuery<UserProfileEntity>
            query =
            em.createNamedQuery("UserProfileEntity.findAllByJurisdiction", UserProfileEntity.class);
        query.setParameter("jurisdiction", findJurisdictionEntityById(jurisdictionId));
        final List<UserProfile>
            list =
            query.getResultList().stream().map(UserProfileMapper::entityToModel).collect(Collectors.toList());
        userProfileAuditEntityRepository.createUserProfileAuditEntity(auditFindAll(),
                                                                      READ,
                                                                      actionedBy,
                                                                      jurisdictionId);
        return list;
    }

    public List<UserProfile> findAll() {
        TypedQuery<UserProfileEntity> query = em.createNamedQuery("UserProfileEntity.findAll", UserProfileEntity.class);
        return query.getResultList().stream().map(UserProfileMapper::entityToModel).collect(Collectors.toList());
    }

    /**
     * Updates a User Profile when attempting to create a user for a given Jurisdiction, when that user already exists.
     *
     * @param userProfile
     *     The UserProfile with the updated data
     * @param actionedBy
     *     for audit trail
     * @return The updated UserProfile
     * @throws BadRequestException
     *     If there is no such user, or if the user already belongs to the given Jurisdiction
     */
    public UserProfile updateUserProfileOnCreate(final UserProfile userProfile, final String actionedBy) {

        final UserProfileEntity userProfileEntity = findEntityById(userProfile.getId(), actionedBy, false);
        if (null == userProfileEntity) {
            throw new BadRequestException("User does not exist with ID " + userProfile.getId());
        }

        final UserProfile audit = UserProfileMapper.entityToModel(userProfileEntity);
        userProfileEntity.setWorkBasketDefaultCaseType(userProfile.getWorkBasketDefaultCaseType());
        userProfileEntity.setWorkBasketDefaultJurisdiction(userProfile.getWorkBasketDefaultJurisdiction());
        userProfileEntity.setWorkBasketDefaultState(userProfile.getWorkBasketDefaultState());

        if (userProfileEntity.getJurisdictions()
                             .stream()
                             .noneMatch(j -> j.getId().equals(userProfile.getWorkBasketDefaultJurisdiction()))) {
            final JurisdictionEntity
                jurisdictionEntity =
                jurisdictionRepository.findEntityById(userProfile.getWorkBasketDefaultJurisdiction());
            userProfileEntity.addJurisdiction(jurisdictionEntity);

            em.persist(userProfileEntity);

            // Checks whether this is an update on existing user or CREATE on a deleted user account
            if (null == audit.getWorkBasketDefaultJurisdiction()) {
                userProfileAuditEntityRepository.createUserProfileAuditEntity(userProfile,
                                                                              CREATE,
                                                                              actionedBy,
                                                                              userProfile
                                                                                  .getWorkBasketDefaultJurisdiction());
            } else {
                userProfileAuditEntityRepository.createUserProfileAuditEntity(audit,
                                                                              UPDATE,
                                                                              actionedBy,
                                                                              audit.getWorkBasketDefaultJurisdiction());
            }
            return UserProfileMapper.entityToModel(userProfileEntity);
        } else {
            throw new BadRequestException("User with ID "
                                          + userProfile.getId()
                                          + " is already a member of the "
                                          + userProfile.getWorkBasketDefaultJurisdiction()
                                          + " jurisdiction");
        }
    }

    /**
     * Removes an association from a User Profile to a Jurisdiction. Additionally, sets all workbasket defaults to null
     * if, after removing an association, the user no longer belongs to any Jurisdiction.
     *
     * @param userProfile
     *     UserProfile from which the Jurisdiction is to be removed
     * @param jurisdiction
     *     Jurisdiction to be removed
     * @param actionedBy
     *     for audit trail
     * @return The updated UserProfile
     * @throws BadRequestException
     *     If there is no such User Profile, or no such association to the Jurisdiction exists
     */
    public UserProfile deleteJurisdictionFromUserProfile(final UserProfile userProfile,
                                                         final Jurisdiction jurisdiction,
                                                         final String actionedBy) {
        final UserProfileEntity userProfileEntity = findEntityById(userProfile.getId(), actionedBy, false);
        if (userProfileEntity == null) {
            throw new BadRequestException("User does not exist with ID " + userProfile.getId());
        }

        final String currentJurisdiction = userProfileEntity.getWorkBasketDefaultJurisdiction();
        userProfileEntity.getJurisdictions().remove(jurisdictionRepository.findEntityById(jurisdiction.getId()));

        // Set all workbasket defaults to null if the user no longer belongs to any Jurisdiction
        if (userProfileEntity.getJurisdictions().isEmpty()) {
            userProfileEntity.setWorkBasketDefaultJurisdiction(null);
            userProfileEntity.setWorkBasketDefaultCaseType(null);
            userProfileEntity.setWorkBasketDefaultState(null);
        }

        em.merge(userProfileEntity);
        userProfileAuditEntityRepository.createUserProfileAuditEntity(userProfile,
                                                                      DELETE,
                                                                      actionedBy,
                                                                      currentJurisdiction);
        return UserProfileMapper.entityToModel(userProfileEntity);
    }

    private JurisdictionEntity findJurisdictionEntityById(String jurisdictionId) {
        return em.find(JurisdictionEntity.class, jurisdictionId);
    }

    private UserProfile auditFindAll() {
        final UserProfile profile = new UserProfile();
        profile.setId(NOT_APPLICABLE);
        profile.setWorkBasketDefaultState(NOT_APPLICABLE);
        profile.setWorkBasketDefaultCaseType(NOT_APPLICABLE);
        profile.setWorkBasketDefaultJurisdiction(NOT_APPLICABLE);
        return profile;
    }
}
