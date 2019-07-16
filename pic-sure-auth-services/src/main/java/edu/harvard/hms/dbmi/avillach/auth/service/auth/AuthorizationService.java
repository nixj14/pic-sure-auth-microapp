package edu.harvard.hms.dbmi.avillach.auth.service.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

public class AuthorizationService {
	private Logger logger = LoggerFactory.getLogger(AuthorizationService.class);

	@Inject
	UserRepository userRepo;

	/**
	 * Checking based on AccessRule in Privilege
     * <br><br>
     * Thoughts of design:
     * <br>
     * <br>
     * We have three layers here: role, privilege, accessRule.
     * <br>
     * A role might have multiple privileges, a privilege might have multiple accessRules.
     * <br>
     * Currently, we retrieve all accessRule together. Between AccessRules, they should be OR relationship, which means
     * roles and privileges are OR relationship, pass one, you are good.
     * <br>
     * <br>
     * Inside each accessRule, it has subAccessRules and Gates.
     * <br>
     * Only if all gates applied, the accessRule will be checked.
     * <br>
     * the accessRule and subAccessRules are AND relationship
     *
	 *
	 * @param application
	 * @param requestBody
	 * @param userUuid
	 * @return
	 *
	 * @see edu.harvard.hms.dbmi.avillach.auth.data.entity.Privilege
	 * @see AccessRule
	 */
	public boolean isAuthorized(Application application , Object requestBody, UUID userUuid){

	    String applicationName = application.getName();

		User user = userRepo.getById(userUuid);
		
		//in some cases, we don't do checking
		if (requestBody == null) {
			logger.info("ACCESS_LOG ___ " + user.getUuid().toString() + "," + user.getEmail() + "," + user.getName() + 
					" ___ has been granted access to application ___ " + applicationName + " ___ NO REQUEST BODY FORWARDED BY APPLICATION");        
			return true;
		}

		//        Object parsedRequestBody = null;
		//        Configuration conf = Configuration.defaultConfiguration().addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL).addOptions(Option.ALWAYS_RETURN_LIST);
		//        try {
		//            parsedRequestBody = conf.jsonProvider().parse(JAXRSConfiguration.objectMapper.writeValueAsString(requestBody));
		//        } catch (JsonProcessingException e) {
		//            return true;
		//        }

		// start to process the jsonpath checking

		String requestJson = null;
		try {
			requestJson = new ObjectMapper().writeValueAsString(requestBody);
		} catch (JsonProcessingException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
			logger.info("ACCESS_LOG ___ " + user.getUuid().toString() + "," + user.getEmail() + "," + user.getName() + 
					" ___ has been denied access to execute query ___ " + requestBody + " ___ in application ___ " + applicationName + " ___ UNABLE TO PARSE REQUEST");
			return false;
		}

		Set<Privilege> privileges = user.getPrivilegesByApplication(application);

        Set<AccessRule> accessRules = preProcessAccessRules(privileges);

		if (accessRules == null || accessRules.isEmpty()) {
			logger.info("ACCESS_LOG ___ " + user.getUuid().toString() + "," + user.getEmail() + "," + user.getName() + 
					" ___ has been granted access to execute query ___ " + requestJson + " ___ in application ___ " + applicationName + " ___ NO ACCESS RULES EVALUATED");
			return true;        	
		}


         // loop through all accessRules
         // Current logic here is: among all accessRules, they are OR relationship
		Set<AccessRule> failedRules = new HashSet<>();
		AccessRule passByRule = null;
        boolean result = false;
		for (AccessRule accessRule : accessRules) {

			if (checkAccessRule(requestBody, accessRule)){
				result = true;
				passByRule = accessRule;
				break;
			} else {
			    failedRules.add(accessRule);
            }
		}

		String passRuleName = null;

		if (passByRule != null){
		    if (passByRule.getMergedName().isEmpty())
		        passRuleName = passByRule.getName();
             else
                passRuleName = passByRule.getMergedName();
        }

		logger.info("ACCESS_LOG ___ " + user.getUuid().toString() + "," + user.getEmail() + "," + user.getName() + 
				" ___ has been " + (result?"granted":"denied") + " access to execute query ___ " + requestJson + 
				" ___ in application ___ " + applicationName + " ___ " +
                (result?"passed by " + passRuleName:"failed by rules: ["
                        + failedRules.stream()
                        .map(ar->(ar.getMergedName().isEmpty()?ar.getName():ar.getMergedName()))
                        .collect(Collectors.joining(", ")) + "]"
                ));

		return result;
	}

    /**
     * This class is for preparing for a set of accessRule that used by the further checking
     *
     * Currently it contains a merge function
     *
     * @param privileges
     * @return
     */
	private Set<AccessRule> preProcessAccessRules(Set<Privilege> privileges){

        Set<AccessRule> accessRules = new HashSet<>();
        for (Privilege privilege : privileges) {
            accessRules.addAll(privilege.getAccessRules());
        }

        // now we use the function preProcess by sorted string keys
        // if needed, we could implement another function to sort by other key or combination of keys
        return preProcessARBySortedKeys(accessRules);
    }

    /**
     * This function is doing the merge by gathering all String keys together
     * and sorted to be the key in the map
     *
     * @param accessRules
     * @return
     */
    protected Set<AccessRule> preProcessARBySortedKeys(Set<AccessRule> accessRules){
        // key is a combination of uuid of gates and rule
        // value is a list of the same key accessrule,
        // later will be merged to be new AccessRule for evaluation
        Map<String, Set<AccessRule>> accessRuleMap  = new HashMap<>();

        for (AccessRule accessRule : accessRules) {

            // 1st generate the key by grabbing all related string and put them together in order
            // we use a treeSet here to put orderly combine Strings together
            Set<String> keys = new TreeSet<>();

            // the current accessRule rule
            keys.add(accessRule.getRule());

            // the accessRule type
            keys.add(accessRule.getType().toString());

            // all gates' UUID as strings
            for (AccessRule gate : accessRule.getGates()){
                keys.add(gate.getUuid().toString());
            }

            // all sub accessRule rules
            for (AccessRule subAccessRule : accessRule.getSubAccessRule()){
                keys.add(subAccessRule.getRule());
            }

            // then we combine them together as one string for the key
            String key = keys.stream().collect(Collectors.joining());

            // put it into the accessRuleMap
            if (accessRuleMap.containsKey(key)){
                accessRuleMap.get(key).add(accessRule);
            } else {
                Set<AccessRule> accessRuleSet = new HashSet<>();
                accessRuleSet.add(accessRule);
                accessRuleMap.put(key, accessRuleSet);
            }
        }


        return mergeSameKeyAccessRules(accessRuleMap.values());
    }

    /**
     * merge accessRules in the same collection into one accessRule
     * <br>
     * The accessRules in the same collection means they shared the same
     * standard and can be merged together
     *
     * @param accessRuleMap
     * @return
     */
    private Set<AccessRule> mergeSameKeyAccessRules(Collection<Set<AccessRule>> accessRuleMap){
        Set<AccessRule> accessRules = new HashSet<>();

        for (Set<AccessRule> accessRulesSet : accessRuleMap) {
            // merge one set of accessRule into one accessRule
            AccessRule accessRule = null;
            for (AccessRule innerAccessRule : accessRulesSet){
                accessRule = mergeAccessRules(accessRule, innerAccessRule);
            }

            // if the new merged accessRule exists, add it into the final result set
            if (accessRule != null)
                accessRules.add(accessRule);
        }

        return accessRules;
    }

    /**
     * Notice: we don't need to worry about if any accessRule value is null
     * since the mergedValues is a Set, it allows adding null value,
     * and later on when doing evaluation, this null value will be handled
     *
     * @param baseAccessRule the base accessRule and this will be returned
     * @param accessRuleToBeMerged the one that waits to be merged into base accessRule
     * @return
     */
    private AccessRule mergeAccessRules(AccessRule baseAccessRule, AccessRule accessRuleToBeMerged){
        if (baseAccessRule == null) {
            accessRuleToBeMerged.getMergedValues().add(accessRuleToBeMerged.getValue());
            return accessRuleToBeMerged;
        }

        baseAccessRule.getSubAccessRule().addAll(accessRuleToBeMerged.getSubAccessRule());
        baseAccessRule.getMergedValues().add(accessRuleToBeMerged.getValue());
        if (baseAccessRule.getMergedName().startsWith("Merged|")){
            baseAccessRule.setMergedName(baseAccessRule.getMergedName() +"|"+ accessRuleToBeMerged.getName());
        } else {
            baseAccessRule.setMergedName("Merged|" + baseAccessRule.getName() + "|"+ accessRuleToBeMerged.getName());
        }


        return baseAccessRule;
    }

    /**
     * inside one accessRule, it might contain a set of gates and a set of subAccessRule.
     * <br>
     * Gates are AND relationship, and are the first parts to be checked. If all the gates are passed,
     * it will start to check the rules. All rules are AND relationship.
     *
     *
     * @param parsedRequestBody
     * @param accessRule
     * @return
     */
	protected boolean checkAccessRule(Object parsedRequestBody, AccessRule accessRule) {

		Set<AccessRule> gates = accessRule.getGates();

		boolean applyGate = false;

		// check every each gate first to see if gate will be apply
		if (gates != null && !gates.isEmpty()) {
            applyGate = true;
			for (AccessRule gate : gates){
			    if (!extractAndCheckRule(gate, parsedRequestBody)){
			        applyGate = false;
			        break;
                }
			}
		}

        if (gates == null || gates.isEmpty() || applyGate) {
            if (extractAndCheckRule(accessRule, parsedRequestBody) == false)
                return false;
            else {
                if (accessRule.getSubAccessRule() != null) {
                    for (AccessRule subAccessRule : accessRule.getSubAccessRule()) {
                        if (extractAndCheckRule(subAccessRule, parsedRequestBody) == false)
                            return false;
                    }
                }
            }
        }

        return true;
	}

    /**
     * This function does two parts: extract the value from current node, then
     * call the evaluateNode() to check if it passed or not
     *
     * @param accessRule
     * @param parsedRequestBody
     * @return
     */
	private boolean extractAndCheckRule(AccessRule accessRule, Object parsedRequestBody){
        String rule = accessRule.getRule();
        String value = accessRule.getValue();

        if (rule == null || rule.isEmpty())
            return true;

        Object requestBodyValue;

        try {
            requestBodyValue = JsonPath.parse(parsedRequestBody).read(rule);
        } catch (PathNotFoundException ex){
            logger.debug("extractAndCheckRule() -> JsonPath.parse().read() throws exception with parsedRequestBody - {} : {} - {}", parsedRequestBody, ex.getClass().getSimpleName(), ex.getMessage());
            return false;
        }

        // AccessRule type IS_EMPTY is very special, needs to be checked in front of any others
        // in type IS_EMPTY, it doens't matter if the value is null or anything
        int accessRuleType = accessRule.getType();
        if (accessRuleType == AccessRule.TypeNaming.IS_EMPTY
                || accessRuleType == AccessRule.TypeNaming.IS_NOT_EMPTY){
            if (requestBodyValue == null
                    || (requestBodyValue instanceof String && ((String)requestBodyValue).isEmpty())
                    || (requestBodyValue instanceof Collection && ((Collection)requestBodyValue).isEmpty())
                    || (requestBodyValue instanceof Map && ((Map)requestBodyValue).isEmpty())){
                if (accessRuleType == AccessRule.TypeNaming.IS_EMPTY)
                    return true;
                else
                    return false;
            } else {
                if (accessRuleType == AccessRule.TypeNaming.IS_NOT_EMPTY)
                    return true;
                else
                    return false;
            }
        }

        return evaluateNode(requestBodyValue, accessRule);
    }


    private boolean evaluateNode(Object requestBodyValue, AccessRule accessRule){
        /**
         * NOTE: if the path(driven by attribute rule) eventually leads to String values, we can do check,
         * otherwise, only means the path is not driving to useful places, just return true.
         *
         * a possible requestBodyValue could be:
         *
         *     NOTE: this size 13 JSONArray contains every element in the root node,
         *     which means the parent node and child node will be separately counted, meaning
         *     13 elements contains duplicated but parent-child related nodes.
         *
         * requestBodyValue = {JSONArray@1560}  size = 13
         *  0 = {LinkedHashMap@1566}  size = 5
         *  1 = "8e8c7ed0-87ea-4342-b8da-f939e46bac26"
         *  2 = {LinkedHashMap@1568}  size = 0
         *  3 = {LinkedHashMap@1569}  size = 1
         *  4 = {LinkedHashMap@1570}  size = 0
         *  5 = {ArrayList@1571}  size = 1
         *  6 = "COUNT"
         *  7 = {ArrayList@1573}  size = 2
         *  8 = {ArrayList@1574}  size = 1
         *  9 = "male"
         *  10 = "\demographics\AGE\"
         *  11 = "\demographics\SEX\"
         *  12 = "\demographics\AGE\"
         */

        if (requestBodyValue instanceof String){
            return decisionMaker(accessRule, (String)requestBodyValue);
        } else if (requestBodyValue instanceof Collection) {
            switch (accessRule.getType()){
                case (AccessRule.TypeNaming.ARRAY_EQUALS):
                case (AccessRule.TypeNaming.ARRAY_CONTAINS):
                case(AccessRule.TypeNaming.ARRAY_REG_MATCH):
                    for (Object item : (Collection)requestBodyValue) {
                        if (item instanceof String){
                            if (decisionMaker(accessRule, (String)item)){
                                return true;
                            }
                        } else {
                            if (evaluateNode(item, accessRule)){
                                return true;
                            }
                        }
                    }
                    // need to take care if the collection is empty
                    return false;
                default:
                    if (((Collection) requestBodyValue).isEmpty()){
                        // need to take care if the collection is empty
                        switch (accessRule.getType()){
                            case (AccessRule.TypeNaming.ALL_EQUALS_IGNORE_CASE):
                            case (AccessRule.TypeNaming.ALL_EQUALS):
                            case (AccessRule.TypeNaming.ALL_CONTAINS):
                            case (AccessRule.TypeNaming.ALL_CONTAINS_IGNORE_CASE):
                                // since collection is empty, nothing is complimented to the rule,
                                // it should return false
                                return false;
                            default:
                                // since collection is empty, nothing will be denied by the rule,
                                // so return true
                                return true;
                        }
                    }

                    for (Object item : (Collection)requestBodyValue){
                        if (item instanceof String) {
                            if (decisionMaker(accessRule, (String)item) == false){
                                return false;
                            }
                        } else {
                            if (evaluateNode(item, accessRule) == false)
                                return false;
                        }
                    }
            }
        } else if (accessRule.getCheckMapNode() != null && accessRule.getCheckMapNode() && requestBodyValue instanceof Map) {
            switch (accessRule.getType()) {
                case (AccessRule.TypeNaming.ARRAY_EQUALS):
                case (AccessRule.TypeNaming.ARRAY_CONTAINS):
                case(AccessRule.TypeNaming.ARRAY_REG_MATCH):
                    for (Map.Entry entry : ((Map<String, Object>) requestBodyValue).entrySet()){
                        if (decisionMaker(accessRule, (String) entry.getKey()))
                            return true;

                        if((accessRule.getCheckMapKeyOnly() == null || !accessRule.getCheckMapKeyOnly())
                                && evaluateNode(entry.getValue(), accessRule))
                            return true;
                    }
                    return false;
                default:
                    if (((Map) requestBodyValue).isEmpty()){
                        // need to take care if the collection is empty
                        switch (accessRule.getType()){
                            case (AccessRule.TypeNaming.ALL_EQUALS_IGNORE_CASE):
                            case (AccessRule.TypeNaming.ALL_EQUALS):
                            case (AccessRule.TypeNaming.ALL_CONTAINS):
                            case (AccessRule.TypeNaming.ALL_CONTAINS_IGNORE_CASE):
                                // since collection is empty, nothing is complimented to the rule,
                                // it should return false
                                return false;
                            default:
                                // since collection is empty, nothing will be denied by the rule,
                                // so return true
                                return true;
                        }
                    }
                    for (Map.Entry entry : ((Map<String, Object>) requestBodyValue).entrySet()){
                        if (decisionMaker(accessRule, (String) entry.getKey()) == false)
                            return false;

                        if( (accessRule.getCheckMapKeyOnly() == null || !accessRule.getCheckMapKeyOnly())
                                && evaluateNode(entry.getValue(), accessRule) == false)
                            return false;
                    }

            }

        }

        return true;
    }

    /**
     * The reason that the relationship between the mergedValues is OR is
     * the mergedValues actually come from merging multiple accessRules.
     * The relationship between multiple accessRules(=privileges/roles) is a OR relationship,
     * so as mergedValues sharing the same OR relationship
     *
     * <br><br>
     * Notice: all the values that need to be evaluated, will in accessRule.getMergedValues()
     * if the accessRule.getValue() is null, or accessRule.getMergedValues() is empty, meaning
     * it is a special case, which should be handle before the program hits this function
     *
     * @param accessRule
     * @param requestBodyValue
     * @return
     */
    private boolean decisionMaker(AccessRule accessRule, String requestBodyValue){

        // it might be possible that sometimes there is value in the accessRule.getValue()
        // but the mergedValues doesn't have elements in it...
        if (accessRule.getMergedValues().isEmpty()){
            String value = accessRule.getValue();
            if (value == null){
                if (requestBodyValue == null) {
                    return true;
                } else {
                    return false;
                }
            }
            return _decisionMaker(accessRule, requestBodyValue, value);
        }


        // recursively check the values
        // until one of them is true
        // if there is only one element in the merged value set
        // the operation equals to _decisionMaker(accessRule, requestBodyValue, value)
        boolean res = false;
        for (String s : accessRule.getMergedValues()){

            // check the special case value is null
            // if value is null, the check will stop here and
            // not goes to _decisionMaker()
            if (s == null){
                if (requestBodyValue == null) {
                    res = true;
                    break;
                } else {
                    res = false;
                    continue;
                }
            }

            // all the merged values are OR relationship
            // means if you pass one of them, you pass the rule
            if (_decisionMaker(accessRule, requestBodyValue, s)) {
                res = true;
                break;
            }
        }
        return res;
    }

	private boolean _decisionMaker(AccessRule accessRule, String requestBodyValue, String value){

	    switch (accessRule.getType()){
            case AccessRule.TypeNaming.NOT_CONTAINS:
                if (!requestBodyValue.contains(value))
                    return true;
                else
                    return false;
            case AccessRule.TypeNaming.NOT_CONTAINS_IGNORE_CASE:
                if (!requestBodyValue.toLowerCase().contains(value.toLowerCase()))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.NOT_EQUALS):
                if (!value.equals(requestBodyValue))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.ARRAY_EQUALS):
            case(AccessRule.TypeNaming.ALL_EQUALS):
                if (value.equals(requestBodyValue))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.ALL_CONTAINS):
            case(AccessRule.TypeNaming.ARRAY_CONTAINS):
                if (requestBodyValue.contains(value))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.ALL_CONTAINS_IGNORE_CASE):
                if (requestBodyValue.toLowerCase().contains(value.toLowerCase()))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.NOT_EQUALS_IGNORE_CASE):
                if (!value.equalsIgnoreCase(requestBodyValue))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.ALL_EQUALS_IGNORE_CASE):
                if (value.equalsIgnoreCase(requestBodyValue))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.ALL_REG_MATCH):
            case(AccessRule.TypeNaming.ARRAY_REG_MATCH):
                if (requestBodyValue.matches(value))
                    return true;
                else
                    return false;
            case(AccessRule.TypeNaming.IS_EMPTY):
//                if (requestBodyValue == null
//                        || (requestBodyValue instanceof String && requestBodyValue.isEmpty())
//                        || (requestBodyValue instanceof Collection))
//                    return true;


		default:
			logger.warn("checkAccessRule() incoming accessRule type is out of scope. Just return true.");
			return true;
		}
	}

}
