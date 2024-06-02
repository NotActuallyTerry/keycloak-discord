package br.com.luizcarlosvianamelo.keycloak.broker.oidc.mappers;

import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.JsonNode;
import org.keycloak.broker.oidc.KeycloakOIDCIdentityProviderFactory;
import org.keycloak.broker.oidc.OIDCIdentityProviderFactory;
import org.keycloak.broker.oidc.OIDCIdentityProvider;
import org.keycloak.broker.oidc.mappers.AbstractClaimMapper;
import org.keycloak.broker.oidc.mappers.AbstractJsonUserAttributeMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.models.*;
import org.keycloak.provider.ProviderConfigProperty;

import org.keycloak.social.discord.DiscordIdentityProviderFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Class with the implementation of the identity provider mapper that sync the
 * user's groups received from an external IdP into the Keycloak groups.
 *
 * @author Luiz Carlos Viana Melo
 */
public class ClaimToGroupMapper extends AbstractClaimMapper {

    // logger ------------------------------------------------

    private static final Logger logger = Logger.getLogger(ClaimToGroupMapper.class);

    // global properties -------------------------------------

    private static final String PROVIDER_ID = "oidc-group-idp-mapper";

    private static final String[] COMPATIBLE_PROVIDERS = {
            KeycloakOIDCIdentityProviderFactory.PROVIDER_ID,
            OIDCIdentityProviderFactory.PROVIDER_ID,
            DiscordIdentityProviderFactory.PROVIDER_ID
    };

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = new ArrayList<>();

    private static final String CONTAINS_TEXT = "contains_text";

    private static final String CREATE_GROUPS = "create_groups";

    static {
        ProviderConfigProperty property;

        property = new ProviderConfigProperty();
        property.setName(CLAIM);
        property.setLabel("Claim");
        property.setHelpText("Name of claim to search for in token. This claim must be a string array with " +
                "the names of the groups which the user is member. You can reference nested claims using a " +
                "'.', i.e. 'address.locality'. To use dot (.) literally, escape it with backslash (\\.)");

        property.setType(ProviderConfigProperty.STRING_TYPE);
        CONFIG_PROPERTIES.add(property);

        property = new ProviderConfigProperty();
        property.setName(CONTAINS_TEXT);
        property.setLabel("Contains text");
        property.setHelpText("Only sync groups that contains this text in its name. If empty, sync all groups.");

        property.setType(ProviderConfigProperty.STRING_TYPE);
        CONFIG_PROPERTIES.add(property);

        property = new ProviderConfigProperty();
        property.setName(CREATE_GROUPS);
        property.setLabel("Create groups if not exists");
        property.setHelpText("Indicates if missing groups must be created in the realms. Otherwise, they will " +
                "be ignored.");

        property.setType(ProviderConfigProperty.BOOLEAN_TYPE);
        CONFIG_PROPERTIES.add(property);
    }

    // properties --------------------------------------------

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String[] getCompatibleProviders() {
        return COMPATIBLE_PROVIDERS;
    }

    @Override
    public String getDisplayCategory() {
        return "Group Importer";
    }

    @Override
    public String getDisplayType() {
        return "Claim to Group Mapper";
    }

    @Override
    public String getHelpText() {
        return "If a claim exists, sync the IdP user's groups with realm groups";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    // actions -----------------------------------------------


    @Override
    public void importNewUser(KeycloakSession session, RealmModel realm, UserModel user, IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {
        super.importNewUser(session, realm, user, mapperModel, context);

        this.syncGroups(realm, user, mapperModel, context);
    }

    @Override
    public void updateBrokeredUser(KeycloakSession session, RealmModel realm, UserModel user, IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {

        this.syncGroups(realm, user, mapperModel, context);
    }

    public static Object getClaimValue(BrokeredIdentityContext context, String claim) {
        JsonNode profileJsonNode = (JsonNode) context.getContextData().get(OIDCIdentityProvider.USER_INFO);
        return AbstractJsonUserAttributeMapper.getJsonValue(profileJsonNode, claim);
    }

    private void syncGroups(RealmModel realm, UserModel user, IdentityProviderMapperModel mapperModel, BrokeredIdentityContext context) {

        // check configurations
        String groupClaimName = mapperModel.getConfig().get(CLAIM);
        String containsText = mapperModel.getConfig().get(CONTAINS_TEXT);
        boolean createGroups = Boolean.parseBoolean(mapperModel.getConfig().get(CREATE_GROUPS));

        // do nothing if no claim was adjusted
        if (isEmpty(groupClaimName))
            return;

        // get new groups
        Object newGroupsObj = getClaimValue(context, groupClaimName);
        // useful to sync even if the user has no groups (remove roles the user had)
        if (newGroupsObj == null) {
            List<String> newList = new ArrayList<>();
            newGroupsObj = newList;
        }

        logger.debugf("Realm [%s], IdP [%s]: starting mapping groups for user [%s]",
                realm.getName(),
                mapperModel.getIdentityProviderAlias(),
                user.getUsername());

        // convert to string list if not list
        if (!List.class.isAssignableFrom(newGroupsObj.getClass())) {
            List<String> newList = new ArrayList<>();
            newList.add(newGroupsObj.toString());
            newGroupsObj = newList;
        }

        // get user current groups
        Set<GroupModel> currentGroups = user.getGroupsStream()
                .filter(g -> isEmpty(containsText) || g.getName().contains(containsText))
                .collect(Collectors.toSet());

        logger.debugf("Realm [%s], IdP [%s]: current groups for user [%s]: %s",
                realm.getName(),
                mapperModel.getIdentityProviderAlias(),
                user.getUsername(),
                currentGroups
                        .stream()
                        .map(GroupModel::getName)
                        .collect(Collectors.joining(","))
        );

        // filter the groups by its name
        @SuppressWarnings("unchecked")
        Set<String> newGroupsNames = ((List<String>) newGroupsObj)
                .stream()
                .filter(t -> isEmpty(containsText) || t.contains(containsText))
                .collect(Collectors.toSet());

        // get new groups
        Set<GroupModel> newGroups = getNewGroups(realm, newGroupsNames, createGroups);

        logger.debugf("Realm [%s], IdP [%s]: new groups for user [%s]: %s",
                realm.getName(),
                mapperModel.getIdentityProviderAlias(),
                user.getUsername(),
                newGroups
                        .stream()
                        .map(GroupModel::getName)
                        .collect(Collectors.joining(","))
        );

        // get the groups from which the user will be removed
        Set<GroupModel> removeGroups = getGroupsToBeRemoved(currentGroups, newGroups);
        for (GroupModel group : removeGroups)
            user.leaveGroup(group);

        // get the groups where the user will be added
        Set<GroupModel> addGroups = getGroupsToBeAdded(currentGroups, newGroups);
        for (GroupModel group : addGroups)
            user.joinGroup(group);

        logger.debugf("Realm [%s], IdP [%s]: finishing mapping groups for user [%s]",
                realm.getName(),
                mapperModel.getIdentityProviderAlias(),
                user.getUsername());
    }

    private Set<GroupModel> getNewGroups(RealmModel realm, Set<String> newGroupsNames, boolean createGroups) {

        Set<GroupModel> groups = new HashSet<>();

        for (String groupName : newGroupsNames) {
            GroupModel group = getGroupByName(realm, groupName);

            // create group if not found
            if (group == null && createGroups) {
                logger.debugf("Realm [%s]: creating group [%s]",
                        realm.getName(),
                        groupName);

                group = realm.createGroup(groupName);
            }

            if (group != null)
                groups.add(group);
        }

        return groups;
    }

    private static GroupModel getGroupByName(RealmModel realm, String name) {

        Optional<GroupModel> group = realm.getGroupsStream()
                .filter(g -> g.getName().equals(name))
                .findFirst();

        return group.orElse(null);
    }

    private static Set<GroupModel> getGroupsToBeRemoved(Set<GroupModel> currentGroups, Set<GroupModel> newGroups) {
        // perform a set difference
        Set<GroupModel> resultSet = new HashSet<>(currentGroups);

        // (Current - New) will result in a set with the groups from which the user will be removed
        resultSet.removeAll(newGroups);

        return resultSet;
    }

    private static Set<GroupModel> getGroupsToBeAdded(Set<GroupModel> currentGroups, Set<GroupModel> newGroups) {
        // perform a set difference
        Set<GroupModel> resultSet = new HashSet<>(newGroups);

        // (New - Current) will result in a set with the groups where the user will be added
        resultSet.removeAll(currentGroups);

        return resultSet;
    }

    private static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }
}
